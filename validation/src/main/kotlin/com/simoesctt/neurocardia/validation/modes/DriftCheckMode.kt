package com.simoesctt.neurocardia.validation.modes

import com.simoesctt.neurocardia.dsp.PanTompkins
import com.simoesctt.neurocardia.io.EcgRecord
import com.simoesctt.neurocardia.io.wfdb.WfdbReader
import com.simoesctt.neurocardia.validation.Corpus
import com.simoesctt.neurocardia.validation.CorpusRecord
import com.simoesctt.neurocardia.validation.features.PWaveFeatures
import com.simoesctt.neurocardia.validation.projectRoot
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Diagnostic for the record-16786 false positive.
 *
 * For each record, computes the mean absolute amplitude of the pre-QRS
 * window used by PWaveFeatures.pWaveEnergy (200 ms to 60 ms before R,
 * on the 0.5-15 Hz bandpassed signal), in two variants:
 *
 *   raw_abs      -- mean |filtered[i]| over the window, as pWaveEnergy
 *                   computes it today
 *   dc_abs       -- mean |filtered[i] - mean(filtered window)| over the
 *                   same window, i.e. per-beat DC removed first
 *   dc_offset    -- the per-beat mean of the window; its std across
 *                   beats is the drift signature
 *
 * Hypothesis: for record 16786, raw_abs will be much larger than for a
 * control sinus record, while dc_abs will be comparable. That would
 * confirm that the residual is driven by per-beat DC offset in the
 * pre-QRS window, not by P-wave morphology.
 */
object DriftCheckMode {

    // Must match PWaveFeatures.pWaveEnergy.
    private const val WINDOW_PRE_MS  = 200.0
    private const val WINDOW_POST_MS =  60.0

    fun run(args: List<String>) {
        if (args.size < 2) {
            println("drift-check requires <corpus-folder> <record> [<record> ...]")
            return
        }
        val folder = File(args[0])
        if (!folder.isDirectory) {
            println("not a directory: ${folder.absolutePath}")
            return
        }
        val requested = args.drop(1).toSet()

        val reportDir = File(projectRoot(), "validation/reports").apply { mkdirs() }
        val timestamp = System.currentTimeMillis()
        val summaryFile = File(reportDir, "drift-check-summary-$timestamp.csv")
        val perBeatFile = File(reportDir, "drift-check-per-beat-$timestamp.csv")

        println("Baseline drift diagnostic")
        println("  corpus: ${folder.absolutePath}")
        println("  window: ${WINDOW_PRE_MS.toInt()} ms -> ${WINDOW_POST_MS.toInt()} ms before R (same as pWaveEnergy)")
        println("  records requested: ${requested.sorted().joinToString(", ")}")
        println()

        val available = Corpus.findIn(folder)
            .filter { it.name in requested }
        val missing = requested - available.map { it.name }.toSet()
        if (missing.isNotEmpty()) {
            println("WARNING: not found in corpus: ${missing.sorted().joinToString(", ")}")
            println()
        }

        val summaryWriter = BufferedWriter(
            OutputStreamWriter(summaryFile.outputStream(), StandardCharsets.UTF_8)
        )
        summaryWriter.write("record,n_beats,raw_abs_mean,dc_abs_mean,dc_offset_mean,dc_offset_std\n")

        val perBeatWriter = BufferedWriter(
            OutputStreamWriter(perBeatFile.outputStream(), StandardCharsets.UTF_8)
        )
        perBeatWriter.write("record,beat_index,r_sample,raw_abs,dc_abs,dc_offset,window_n\n")

        println("%-10s %8s %14s %14s %14s %14s".format(
            "record", "n_beats", "raw_abs", "dc_abs", "dc_off_mean", "dc_off_std"))

        for (rec in available) {
            val row = computeRecord(rec) ?: continue
            println("%-10s %8d %14.6f %14.6f %14.6f %14.6f".format(
                row.name, row.nBeats, row.rawAbsMean, row.dcAbsMean,
                row.dcOffsetMean, row.dcOffsetStd))

            summaryWriter.write("%s,%d,%.6f,%.6f,%.6f,%.6f\n".format(
                row.name, row.nBeats, row.rawAbsMean, row.dcAbsMean,
                row.dcOffsetMean, row.dcOffsetStd))

            for (b in row.beats) {
                perBeatWriter.write("%s,%d,%d,%.6f,%.6f,%.6f,%d\n".format(
                    row.name, b.index, b.rSample, b.rawAbs, b.dcAbs, b.dcOffset, b.windowN))
            }
        }

        summaryWriter.close()
        perBeatWriter.close()

        println()
        println("Reports written to $reportDir")
        println("  ${summaryFile.name}")
        println("  ${perBeatFile.name}")
    }

    private data class BeatRow(
        val index: Int,
        val rSample: Int,
        val rawAbs: Double,
        val dcAbs: Double,
        val dcOffset: Double,
        val windowN: Int
    )

    private data class RecordRow(
        val name: String,
        val nBeats: Int,
        val rawAbsMean: Double,
        val dcAbsMean: Double,
        val dcOffsetMean: Double,
        val dcOffsetStd: Double,
        val beats: List<BeatRow>
    )

    private fun computeRecord(rec: CorpusRecord): RecordRow? {
        val record: EcgRecord = try {
            WfdbReader.read(rec.basePath, attachAnnotations = false)
        } catch (t: Throwable) {
            println("  ${rec.name}: read failed: ${t.message}")
            return null
        }

        val detected = PanTompkins.detect(record)
        val rPeaks = detected.annotations.map { it.sampleIndex }
        if (rPeaks.isEmpty()) return null

        // Reproduce the exact pre-processing pWaveEnergy applies, so the
        // window we measure is the window the feature actually sees.
        val filtered = bandpass(record.samples, record.sampleRate, 0.5, 15.0)

        val preSamples  = (WINDOW_PRE_MS  / 1000.0 * record.sampleRate).toInt()
        val postSamples = (WINDOW_POST_MS / 1000.0 * record.sampleRate).toInt()

        val beats = ArrayList<BeatRow>(rPeaks.size)
        for ((i, r) in rPeaks.withIndex()) {
            val start = (r - preSamples).coerceAtLeast(0)
            val end   = (r - postSamples).coerceAtLeast(start + 1)
            if (end > filtered.size) continue
            val n = end - start
            if (n < 4) continue

            var sum = 0.0
            var sumAbs = 0.0
            for (j in start until end) {
                val v = filtered[j]
                sum += v
                sumAbs += abs(v)
            }
            val mean = sum / n
            val rawAbs = sumAbs / n

            var sumAbsCentered = 0.0
            for (j in start until end) sumAbsCentered += abs(filtered[j] - mean)
            val dcAbs = sumAbsCentered / n

            beats.add(BeatRow(
                index = i,
                rSample = r,
                rawAbs = rawAbs,
                dcAbs = dcAbs,
                dcOffset = mean,
                windowN = n
            ))
        }

        if (beats.isEmpty()) return null

        val rawMean = beats.map { it.rawAbs }.average()
        val dcMean  = beats.map { it.dcAbs  }.average()
        val offMean = beats.map { it.dcOffset }.average()
        val offVar  = beats.map { (it.dcOffset - offMean) * (it.dcOffset - offMean) }.average()
        val offStd  = sqrt(offVar)

        return RecordRow(
            name = rec.name,
            nBeats = beats.size,
            rawAbsMean = rawMean,
            dcAbsMean = dcMean,
            dcOffsetMean = offMean,
            dcOffsetStd = offStd,
            beats = beats
        )
    }

    // Duplicated from PWaveFeatures so this mode is self-contained and
    // does not need to expose the private bandpass. Same coefficients.
    private fun bandpass(x: DoubleArray, fs: Double, low: Double, high: Double): DoubleArray {
        val lp = biquadLowpass(x, high, fs)
        return biquadHighpass(lp, low, fs)
    }

    private fun biquadLowpass(x: DoubleArray, cutoff: Double, fs: Double): DoubleArray {
        val w0 = 2.0 * Math.PI * cutoff / fs
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val alpha = sinW0 / (2.0 * 0.7071)
        val b0 = (1.0 - cosW0) / 2.0
        val b1 = 1.0 - cosW0
        val b2 = (1.0 - cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha
        return biquad(x, b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    private fun biquadHighpass(x: DoubleArray, cutoff: Double, fs: Double): DoubleArray {
        val w0 = 2.0 * Math.PI * cutoff / fs
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val alpha = sinW0 / (2.0 * 0.7071)
        val b0 = (1.0 + cosW0) / 2.0
        val b1 = -(1.0 + cosW0)
        val b2 = (1.0 + cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha
        return biquad(x, b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    private fun biquad(
        x: DoubleArray,
        b0: Double, b1: Double, b2: Double,
        a1: Double, a2: Double
    ): DoubleArray {
        val y = DoubleArray(x.size)
        var x1 = 0.0; var x2 = 0.0
        var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i]
            val yi = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi
            y2 = y1; y1 = yi
            y[i] = yi
        }
        return y
    }
}
