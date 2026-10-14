package com.simoesctt.neurocardia.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simoesctt.neurocardia.batch.BatchImporter
import com.simoesctt.neurocardia.batch.BatchProcessor
import com.simoesctt.neurocardia.batch.BatchStudyBuilder
import com.simoesctt.neurocardia.batch.FileResult
import com.simoesctt.neurocardia.batch.InputFile
import com.simoesctt.neurocardia.dsp.Csi
import com.simoesctt.neurocardia.dsp.HeartRate
import com.simoesctt.neurocardia.dsp.Hrv
import com.simoesctt.neurocardia.dsp.PanTompkins
import com.simoesctt.neurocardia.dsp.SignalQuality
import com.simoesctt.neurocardia.io.AnalysisResult
import com.simoesctt.neurocardia.io.CsvReader
import com.simoesctt.neurocardia.io.EcgRecord
import com.simoesctt.neurocardia.io.MetricKeys
import com.simoesctt.neurocardia.io.RrTachogram
import com.simoesctt.neurocardia.ui.screens.BatchScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class BatchViewModel : ViewModel() {

    private val _state = MutableStateFlow<BatchScreenState>(BatchScreenState.Idle)
    val state: StateFlow<BatchScreenState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Handle a folder tree URI. Because Android scoped storage returns
     * content URIs that Java File cannot open, we materialise each
     * candidate file into the app cache dir before handing it to the
     * batch importer. This is slow for large folders but correct.
     */
    fun selectFolder(context: Context, treeUri: Uri) {
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    materialiseFolder(context, treeUri)
                }
                if (imported.isEmpty()) {
                    _state.value = BatchScreenState.Error(
                        "No supported files found in the selected folder."
                    )
                } else {
                    _state.value = BatchScreenState.Selected(
                        inputs = imported,
                        sourceDescription = "Folder (${imported.size} files)"
                    )
                }
            } catch (t: Throwable) {
                _state.value = BatchScreenState.Error(
                    t.message ?: "could not read folder"
                )
            }
        }
    }

    /**
     * Handle a list of URIs selected via OpenMultipleDocuments.
     */
    fun selectFiles(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    uris.mapNotNull { materialiseOne(context, it) }
                }
                if (imported.isEmpty()) {
                    _state.value = BatchScreenState.Error("No supported files selected.")
                } else {
                    _state.value = BatchScreenState.Selected(
                        inputs = imported,
                        sourceDescription = "Files (${imported.size})"
                    )
                }
            } catch (t: Throwable) {
                _state.value = BatchScreenState.Error(
                    t.message ?: "could not read files"
                )
            }
        }
    }

    fun run() {
        val selected = _state.value as? BatchScreenState.Selected ?: return
        val inputs = selected.inputs
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = BatchScreenState.Running(inputs, null)

            val pipeline: suspend (InputFile) -> FileResult = { input ->
                analyzeFile(input)
            }
            val processor = BatchProcessor(pipeline)

            val results = processor.process(inputs) { progress ->
                _state.value = BatchScreenState.Running(inputs, progress)
            }

            val successes = results.filterIsInstance<FileResult.Success>()
            val failures = results.filterIsInstance<FileResult.Failure>()

            val study = BatchStudyBuilder.summarize(
                records = successes.map { it.record },
                failures = failures,
                sources = successes.map { it.sourcePath }
            )

            _state.value = BatchScreenState.Done(study, inputs)
        }
    }

    fun cancel() {
        job?.cancel()
        val current = _state.value
        if (current is BatchScreenState.Running) {
            _state.value = BatchScreenState.Selected(current.inputs, "Cancelled")
        }
    }

    fun reset() {
        job?.cancel()
        _state.value = BatchScreenState.Idle
    }

    // ─── Pipeline ───────────────────────────────────────────────────────

    private fun analyzeFile(input: InputFile): FileResult {
        return try {
            android.util.Log.d("NeuroCardia", "analyzeFile: ${input.displayName} " +
                "format=${input.inferredFormat} path=${input.file.absolutePath}")
            val record = if (input.displayName.endsWith(".hea", ignoreCase = true)) {
                // WFDB record. The base path is the file path without
                // the .hea extension. WfdbReader finds the .dat and
                // .atr siblings from the same directory.
                val base = input.file.absolutePath.substringBeforeLast('.')
                com.simoesctt.neurocardia.io.wfdb.WfdbReader.read(base)
            } else {
                input.file.inputStream().use { stream ->
                    CsvReader.read(stream, sourceName = input.displayName)
                }
            }
            android.util.Log.d("NeuroCardia", "loaded: samples=${record.samples.size} " +
                "rate=${record.sampleRate} annotations=${record.annotations.size}")

            // Signal quality on the raw input.
            val quality = SignalQuality.compute(record.samples, record.sampleRate)
            android.util.Log.d("NeuroCardia",
                "quality: score=${quality.score} verdict=${quality.verdict} snr=${quality.snrDb}")

            PanTompkins.debug = true
            val detected = PanTompkins.detect(record)
            PanTompkins.debug = false
            android.util.Log.d("NeuroCardia", "detected: ${detected.annotations.size} R-peaks")

            val tachogram = RrTachogram.from(detected)
            android.util.Log.d("NeuroCardia", "tachogram: ${tachogram.nIntervals} intervals")

            val metrics = Hrv.computeAll(tachogram.normalRr()).toMutableMap()
            android.util.Log.d("NeuroCardia", "hrv metrics: ${metrics.keys}")

            if (!metrics.containsKey(MetricKeys.HEART_RATE_BPM)) {
                val hr = HeartRate.meanBpm(detected.annotations, detected.sampleRate)
                if (!hr.isNaN()) metrics[MetricKeys.HEART_RATE_BPM] = hr
            }

            // CSI and EWS, computed identically to the single-file path.
            metrics.putAll(Csi.compute(tachogram))

            // Signal quality sub-metrics, stored in the same map so
            // they appear in the cohort table and per-record CSV.
            metrics["quality_score"] = quality.score
            metrics["quality_snr_db"] = quality.snrDb
            metrics["quality_flatline"] = quality.flatlineFraction
            metrics["quality_clipping"] = quality.clippingFraction
            metrics["quality_baseline"] = quality.baselineFraction

            val withAnalysis = EcgRecord(
                samples = detected.samples,
                sampleRate = detected.sampleRate,
                units = detected.units,
                leadLabel = detected.leadLabel,
                metadata = detected.metadata,
                annotations = detected.annotations,
                analysis = AnalysisResult(metrics = metrics)
            )

            FileResult.Success(withAnalysis, input.file.absolutePath)
        } catch (t: Throwable) {
            android.util.Log.e("NeuroCardia", "analyzeFile failed: ${input.displayName}", t)
            FileResult.Failure(input.file.absolutePath, t.message ?: "error")
        }
    }

    // ─── URI materialisation ────────────────────────────────────────────

    /**
     * Read every file in a tree URI into the app cache directory so
     * that the JVM File-based importer can process them. Returns the
     * list of InputFile objects, or an empty list if nothing valid was
     * found.
     */
    private fun materialiseFolder(context: Context, treeUri: Uri): List<InputFile> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return emptyList()

        val cacheDir = File(context.cacheDir, "batch")
        if (cacheDir.exists()) cacheDir.deleteRecursively()
        cacheDir.mkdirs()

        val copied = mutableListOf<File>()
        copyRecursive(context, root, cacheDir, copied)

        return BatchImporter.fromFolder(cacheDir, recursive = true)
    }

    private fun materialiseOne(context: Context, uri: Uri): InputFile? {
        val name = queryDisplayName(context, uri) ?: "file-${uri.hashCode()}.csv"
        val cacheDir = File(context.cacheDir, "batch")
        cacheDir.mkdirs()
        val outFile = File(cacheDir, name)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        } ?: return null

        return InputFile(
            file = outFile,
            displayName = name,
            inferredFormat = com.simoesctt.neurocardia.batch.SourceFormat.CSV
        )
    }

    private fun copyRecursive(
        context: Context,
        dir: DocumentFile,
        outRoot: File,
        collected: MutableList<File>
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val outFile = File(outRoot, name)
            if (child.isDirectory) {
                outFile.mkdirs()
                copyRecursive(context, child, outFile, collected)
            } else if (child.isFile) {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("csv", "txt", "edf", "hea", "dat", "atr")) continue
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                    collected.add(outFile)
                }
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIdx)
            } else null
        }
    }
}
