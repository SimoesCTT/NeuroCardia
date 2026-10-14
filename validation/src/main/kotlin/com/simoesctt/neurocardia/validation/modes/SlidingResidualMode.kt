package com.simoesctt.neurocardia.validation.modes

import com.simoesctt.neurocardia.dsp.PanTompkins
import com.simoesctt.neurocardia.io.EcgRecord
import com.simoesctt.neurocardia.io.RrTachogram
import com.simoesctt.neurocardia.io.wfdb.WfdbReader
import com.simoesctt.neurocardia.validation.Arm
import com.simoesctt.neurocardia.validation.Corpus
import com.simoesctt.neurocardia.validation.CorpusRecord
import com.simoesctt.neurocardia.validation.CsiRunner
import com.simoesctt.neurocardia.validation.ResidualAnalysis
import com.simoesctt.neurocardia.validation.SensitivityArms
import com.simoesctt.neurocardia.validation.features.PWaveFeatures
import com.simoesctt.neurocardia.validation.projectRoot
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Sliding-window residual analysis on a single record.
 *
 * For each window of [windowSec] seconds (default 300 = 5 min), advanced
 * by [hopSec] seconds (default 30), compute:
 *
 *   - r_mean:  the CSI mean under the chosen arm and K
 *   - rr_cv:   coefficient of variation of the RR series in the window
 *   - residual: CSI mean minus the OLS prediction
 *   - pwave_energy, pwave_pr_var, pwave_fib_amp: P-wave features
 *   - n_beats
 *
 * The residual is computed against a **reference regression** fitted on
 * the entire record's windows. This preserves the meaning of "residual
 * = component orthogonal to RR variability" and yields a per-window
 * score that can be compared across time within a record. A per-record
 * fixed regression is the right choice because a regression fitted on a
 * single window would be a tautology (two points determine a line).
 *
 * Output:
 *   - console table (one row per window)
 *   - CSV file with columns: record, window_index, t_start_s, t_end_s,
 *     n_beats, csi_mean, rr_cv, residual, pwave_energy, pwave_pr_var,
 *     pwave_fib_amp
 *
 * Usage:
 *   ./gradlew :validation:run --args="sliding-residual \
 *     /path/to/corpus 16786 [A] [2] [windowSec] [hopSec]"
 */
object SlidingResidualMode {

    private const val DEFAULT_WINDOW_SEC = 300.0
    private const val DEFAULT_HOP_SEC    = 30.0

    fun run(args: List<String>) {
        if (args.size < 2) {
            println("sliding-residual requires <corpus-folder> <record> " +
                    "[arm] [K] [windowSec] [hopSec]")
            return
        }
        val folder = File(args[0])
        if (!folder.isDirectory) {
            println("not a directory: ${folder.absolutePath}")
            return
        }
        val recordName = args[1]
        val arm: Arm = if (args.size >= 3) {
            Arm.all().firstOrNull { it.name == args[2].trim() } ?: Arm.A
        } else Arm.A
        val k: Double = if (args.size >= 4) {
            args[3].toDoubleOrNull() ?: SensitivityArms.DEFAULT_K
        } else SensitivityArms.DEFAULT_K
        val windowSec = if (args.size >= 5) {
            args[4].toDoubleOrNull() ?: DEFAULT_WINDOW_SEC
        } else DEFAULT_WINDOW_SEC
        val hopSec = if (args.size >= 6) {
            args[5].toDoubleOrNull() ?: DEFAULT_HOP_SEC
        } else DEFAULT_HOP_SEC

        val corpus = Corpus.findIn(folder).firstOrNull { it.name == recordName }
        if (corpus == null) {
            println("record $recordName not found in ${folder.absolutePath}")
            return
        }

        val reportDir = File(projectRoot(), "validation/reports").apply { mkdirs() }
        val timestamp = System.currentTimeMillis()
        val outFile = File(reportDir,
            "sliding-residual-$recordName-${arm.name}-K$k-$timestamp.csv")

        println("Sliding-window residual analysis")
        println("  record:   ${corpus.name}")
        println("  arm:      ${arm.name}")
        println("  K:        $k")
        println("  window:   ${windowSec.toInt()} s")
        println("  hop:      ${hopSec.toInt()} s")
        println()

        val full = try {
            WfdbReader.read(corpus.basePath, attachAnnotations = false)
        } catch (t: Throwable) {
            println("read failed: ${t.message}")
            return
        }
        val fs = full.sampleRate
        val durationS = full.durationS
        println("  sample rate: $fs Hz")
        println("  duration:    %.1f s (%.2f h)".format(durationS, durationS / 3600.0))
        println()

        // Detect R-peaks once on the full record.
        val detected = PanTompkins.detect(full)
        val rPeaks = detected.annotations.map { it.sampleIndex }
        if (rPeaks.isEmpty()) {
            println("no R-peaks detected")
            return
        }
        println("  detected ${rPeaks.size} R-peaks across the full record")
        println()

        val network = SensitivityArms.build(arm = arm, k = k)

        // Compute per-window features.
        val winSamples = (windowSec * fs).toInt()
        val hopSamples = (hopSec * fs).toInt()
        if (hopSamples < 1) {
            println("hop too small")
            return
        }

        data class Row(
            val index: Int,
            val tStart: Double,
            val tEnd: Double,
            val nBeats: Int,
            val csiMean: Double,
            val rrCv: Double,
            val pwaveEnergy: Double,
            val pwavePrVar: Double,
            val pwaveFibAmp: Double
        )

        val rows = ArrayList<Row>()
        var idx = 0
        var startSample = 0
        while (startSample + winSamples <= full.samples.size) {
            val endSample = startSample + winSamples
            val tStart = startSample / fs
            val tEnd = endSample / fs

            // R-peaks inside this window.
            val localPeaks = ArrayList<Int>()
            for (p in rPeaks) {
                if (p >= startSample && p < endSample) {
                    localPeaks.add(p - startSample)
                }
            }

            if (localPeaks.size < 20) {
                // Not enough beats to compute a meaningful CSI.
                rows.add(Row(idx, tStart, tEnd, localPeaks.size,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN))
                idx++
                startSample += hopSamples
                continue
            }

            // RR series in this window (in seconds).
            val rrS = ArrayList<Double>(localPeaks.size - 1)
            for (i in 0 until localPeaks.size - 1) {
                rrS.add((localPeaks[i + 1] - localPeaks[i]) / fs)
            }
            if (rrS.size < 20) {
                rows.add(Row(idx, tStart, tEnd, localPeaks.size,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN))
                idx++
                startSample += hopSamples
                continue
            }
            val rrMs = rrS.map { it * 1000.0 }

            // CSI mean.
            val csi = CsiRunner.run(network, rrMs)

            // RR CV.
            val mean = rrS.average()
            val sd = kotlin.math.sqrt(
                rrS.sumOf { (it - mean) * (it - mean) } / (rrS.size - 1)
            )
            val rrCv = if (mean > 1e-9) sd / mean else Double.NaN

            // P-wave features on the raw samples of this window.
            val winSamples = full.samples.copyOfRange(startSample, endSample)
            val pwave = PWaveFeatures.computeAll(
                samples = winSamples,
                sampleRate = fs,
                rPeaks = localPeaks
            )

            rows.add(Row(
                index = idx,
                tStart = tStart,
                tEnd = tEnd,
                nBeats = localPeaks.size,
                csiMean = csi?.rMean ?: Double.NaN,
                rrCv = rrCv,
                pwaveEnergy = pwave["pwave_energy"] ?: Double.NaN,
                pwavePrVar = pwave["pwave_pr_var"] ?: Double.NaN,
                pwaveFibAmp = pwave["pwave_fib_amp"] ?: Double.NaN
            ))

            idx++
            startSample += hopSamples
        }

        println("  computed ${rows.size} windows")

        // Fit the reference regression on all windows with valid CSI + RR CV.
        val valid = rows.filter {
            !it.csiMean.isNaN() && !it.rrCv.isNaN()
        }
        if (valid.size < 5) {
            println("not enough valid windows to fit the regression")
            return
        }
        val xVals = DoubleArray(valid.size) { valid[it].rrCv }
        val yVals = DoubleArray(valid.size) { valid[it].csiMean }
        val fit = ResidualAnalysis.fit(xVals, yVals)
        println("  regression: csi_mean = %.4f + %.4f * rr_cv"
            .format(fit.a, fit.b))
        println()

        // Compute residuals.
        val residuals = HashMap<Int, Double>()
        for (r in rows) {
            residuals[r.index] = if (r.csiMean.isNaN() || r.rrCv.isNaN()) {
                Double.NaN
            } else {
                r.csiMean - (fit.a + fit.b * r.rrCv)
            }
        }

        // Print a compact console table.
        println("%6s %10s %10s %8s %12s %10s %12s".format(
            "win", "t_start", "t_end", "n_beats", "csi_mean", "rr_cv",
            "residual"))
        println("-".repeat(76))
        for (r in rows) {
            val res = residuals[r.index] ?: Double.NaN
            val cs = if (r.csiMean.isNaN()) "     -    " else "%.5f".format(r.csiMean)
            val cv = if (r.rrCv.isNaN())    "     -    " else "%.5f".format(r.rrCv)
            val rs = if (res.isNaN())       "     -    " else "%+9.5f".format(res)
            println("%6d %10.1f %10.1f %8d %12s %10s %12s".format(
                r.index, r.tStart, r.tEnd, r.nBeats, cs, cv, rs))
        }

        // Write CSV.
        BufferedWriter(
            OutputStreamWriter(outFile.outputStream(), StandardCharsets.UTF_8)
        ).use { w ->
            w.write("record,window_index,t_start_s,t_end_s,n_beats," +
                    "csi_mean,rr_cv,residual,pwave_energy,pwave_pr_var," +
                    "pwave_fib_amp\n")
            for (r in rows) {
                val res = residuals[r.index] ?: Double.NaN
                w.write("${corpus.name},${r.index},")
                w.write("%.3f,%.3f,".format(r.tStart, r.tEnd))
                w.write("${r.nBeats},")
                w.write(if (r.csiMean.isNaN()) "," else "%.6f,".format(r.csiMean))
                w.write(if (r.rrCv.isNaN()) "," else "%.6f,".format(r.rrCv))
                w.write(if (res.isNaN()) "," else "%.6f,".format(res))
                w.write(if (r.pwaveEnergy.isNaN()) "," else "%.6f,".format(r.pwaveEnergy))
                w.write(if (r.pwavePrVar.isNaN()) "," else "%.6f,".format(r.pwavePrVar))
                w.write(if (r.pwaveFibAmp.isNaN()) "," else "%.6f".format(r.pwaveFibAmp))
                w.write("\n")
            }
        }

        // Summary statistics.
        val validRes = rows.mapNotNull { residuals[it.index] }
            .filter { !it.isNaN() }
        if (validRes.isNotEmpty()) {
            val mean = validRes.average()
            val sd = kotlin.math.sqrt(
                validRes.sumOf { (it - mean) * (it - mean) } / (validRes.size - 1)
            )
            val nHigh = validRes.count { it > 2.0 * sd + mean }
            val nLow  = validRes.count { it < -2.0 * sd + mean }
            println()
            println("Residual summary across windows:")
            println("  n     = ${validRes.size}")
            println("  mean  = %+.6f".format(mean))
            println("  sd    =  %.6f".format(sd))
            println("  min   = %+.6f".format(validRes.min()))
            println("  max   = %+.6f".format(validRes.max()))
            println("  windows > +2SD: $nHigh")
            println("  windows < -2SD: $nLow")
        }

        println()
        println("Report written to $reportDir")
        println("  ${outFile.name}")
    }
}
