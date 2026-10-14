package com.simoesctt.neurocardia.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.simoesctt.neurocardia.io.wfdb.WfdbReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class AnalyzerState {
    object Idle : AnalyzerState()
    data class Loading(val sourceName: String) : AnalyzerState()
    data class Ready(val result: AnalysisResultBundle) : AnalyzerState()
    data class Error(val message: String) : AnalyzerState()
}

data class AnalysisResultBundle(
    val record: EcgRecord,
    val tachogram: RrTachogram,
    val metrics: Map<String, Double>,
    val sourceName: String,
    val quality: SignalQuality.Result
) {
    val heartRateBpm: Double?
        get() = metrics[MetricKeys.HEART_RATE_BPM]

    val csiR: Double?
        get() = metrics[MetricKeys.CSI_R]

    val rPeakSamples: IntArray
        get() = record.annotations.map { it.sampleIndex }.toIntArray()
}

class AnalyzerViewModel : ViewModel() {

    private val _state = MutableStateFlow<AnalyzerState>(AnalyzerState.Idle)
    val state: StateFlow<AnalyzerState> = _state.asStateFlow()

    fun load(context: Context, uri: Uri, explicitDisplayName: String? = null) {
        val name = explicitDisplayName
            ?.takeUnless { it.isBlank() }
            ?: queryDisplayName(context, uri)
            ?: extractNameFallback(uri)
            ?: "input.csv"

        android.util.Log.d("NeuroCardia", "load: name=$name uri=$uri")

        _state.value = AnalyzerState.Loading(name)
        viewModelScope.launch {
            try {
                val bundle = withContext(Dispatchers.Default) {
                    analyzeCsv(context, uri, name)
                }
                _state.value = AnalyzerState.Ready(bundle)
            } catch (t: Throwable) {
                android.util.Log.e("NeuroCardia", "analyze failed", t)
                _state.value = AnalyzerState.Error(
                    t.message ?: t::class.simpleName ?: "unknown error"
                )
            }
        }
    }

    fun loadWfdbFolder(context: Context, treeUri: Uri) {
        val folder = DocumentFile.fromTreeUri(context, treeUri)
        if (folder == null) {
            _state.value = AnalyzerState.Error("could not open folder")
            return
        }

        val heaDoc = folder.listFiles().firstOrNull {
            it.isFile && it.name?.endsWith(".hea", ignoreCase = true) == true
        }
        if (heaDoc == null) {
            _state.value = AnalyzerState.Error(
                "no .hea file found in the selected folder"
            )
            return
        }

        val folderName = folder.name
        val fileName = heaDoc.name
        val name = if (folderName != null && !folderName.contains(":")) {
            folderName
        } else {
            fileName ?: "record"
        }

        _state.value = AnalyzerState.Loading(name)

        viewModelScope.launch {
            try {
                val bundle = withContext(Dispatchers.Default) {
                    analyzeWfdb(context, folder, heaDoc.name ?: "record", name)
                }
                _state.value = AnalyzerState.Ready(bundle)
            } catch (t: Throwable) {
                android.util.Log.e("NeuroCardia", "wfdb analyze failed", t)
                _state.value = AnalyzerState.Error(
                    t.message ?: t::class.simpleName ?: "unknown error"
                )
            }
        }
    }

    fun clear() {
        _state.value = AnalyzerState.Idle
    }

    // ─── Analysis pipelines ────────────────────────────────────────────

    private fun analyzeCsv(
        context: Context,
        uri: Uri,
        displayName: String
    ): AnalysisResultBundle {
        val record = context.contentResolver.openInputStream(uri)?.use { stream ->
            CsvReader.read(stream, sourceName = displayName)
        } ?: error("could not open $displayName")
        return finishAnalysis(record, displayName)
    }

    private fun analyzeWfdb(
        context: Context,
        folder: DocumentFile,
        heaName: String,
        displayName: String
    ): AnalysisResultBundle {
        val base = heaName.substringBeforeLast('.')
        val cacheDir = File(context.cacheDir, "wfdb/${folder.name ?: base}").apply {
            mkdirs()
        }

        var copied = 0
        for (child in folder.listFiles()) {
            val childName = child.name ?: continue
            if (!child.isFile) continue
            val childBase = childName.substringBeforeLast('.')
            if (childBase != base) continue

            val outFile = File(cacheDir, childName)
            context.contentResolver.openInputStream(child.uri)?.use { input ->
                outFile.outputStream().use { out -> input.copyTo(out) }
            } ?: continue
            copied++
        }

        require(copied >= 1) { "no files copied for record $base" }

        val basePath = File(cacheDir, base).absolutePath
        val record = WfdbReader.read(basePath, attachAnnotations = true)
        return finishAnalysis(record, displayName)
    }

    private fun finishAnalysis(record: EcgRecord, displayName: String): AnalysisResultBundle {
        val quality = SignalQuality.compute(record.samples, record.sampleRate)

        val detected = PanTompkins.detect(record)
        val tachogram = RrTachogram.from(detected)

        val metrics = mutableMapOf<String, Double>()
        metrics.putAll(Hrv.computeAll(tachogram.normalRr()))

        if (!metrics.containsKey(MetricKeys.HEART_RATE_BPM)) {
            val hr = HeartRate.meanBpm(detected.annotations, detected.sampleRate)
            if (!hr.isNaN()) metrics[MetricKeys.HEART_RATE_BPM] = hr
        }

        metrics.putAll(Csi.compute(tachogram))

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

        return AnalysisResultBundle(
            record = withAnalysis,
            tachogram = tachogram,
            metrics = metrics,
            sourceName = displayName,
            quality = quality
        )
    }

    // ─── Display name resolution ───────────────────────────────────────

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment

        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) name.trim() else null
                } else null
            }
        } catch (t: Throwable) {
            android.util.Log.d("NeuroCardia", "queryDisplayName failed for $uri", t)
            null
        }
    }

    private fun extractNameFallback(uri: Uri): String? {
        val seg = uri.lastPathSegment ?: return null
        if (seg.contains(":") || seg.all { it.isDigit() }) return null
        return seg
    }
}
