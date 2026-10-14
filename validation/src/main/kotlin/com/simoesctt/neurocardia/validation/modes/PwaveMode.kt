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
import com.simoesctt.neurocardia.validation.Spearman
import com.simoesctt.neurocardia.validation.features.PWaveFeatures
import com.simoesctt.neurocardia.validation.projectRoot
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object PwaveMode {

    data class RecordFeatures(
        val name: String,
        val label: Int,
        val csiMean: Double,
        val rrCv: Double,
        val pwave: Map<String, Double>
    )

    fun run(args: List<String>) {
        if (args.size < 2) { println("pwave requires two folders"); return }
        val folderA = File(args[0])
        val folderB = File(args[1])
        if (!folderA.isDirectory || !folderB.isDirectory) {
            println("Both arguments must be directories"); return
        }

        val arm: Arm = if (args.size >= 3) {
            Arm.all().firstOrNull { it.name == args[2].trim() } ?: Arm.A
        } else Arm.A

        val k: Double = if (args.size >= 4) {
            args[3].toDoubleOrNull() ?: SensitivityArms.DEFAULT_K
        } else SensitivityArms.DEFAULT_K

        val reportDir = File(projectRoot(), "validation/reports").apply { mkdirs() }
        val timestamp = System.currentTimeMillis()

        println("P-wave mechanism analysis")
        println("  folder A: ${folderA.absolutePath} (label 0)")
        println("  folder B: ${folderB.absolutePath} (label 1)")
        println("  arm:      ${arm.name}")
        println("  K:        $k")
        println()

        val rows = mutableListOf<RecordFeatures>()
        for ((folder, label) in listOf(folderA to 0, folderB to 1)) {
            for (rec in Corpus.findIn(folder)) {
                val rf = computeRecord(rec, label, arm, k)
                if (rf != null) rows.add(rf)
            }
        }

        println("Computed ${rows.size} records.")
        println()

        // Fit the residual regression.
        val csiVec = DoubleArray(rows.size) { rows[it].csiMean }
        val rrCvVec = DoubleArray(rows.size) { rows[it].rrCv }
        val validIdx = csiVec.indices.filter {
            !csiVec[it].isNaN() && !rrCvVec[it].isNaN()
        }
        val xVals = DoubleArray(validIdx.size) { rrCvVec[validIdx[it]] }
        val yVals = DoubleArray(validIdx.size) { csiVec[validIdx[it]] }
        val fit = ResidualAnalysis.fit(xVals, yVals)
        println("Regression: csi_mean = %.4f + %.4f * rr_cv".format(fit.a, fit.b))
        println()

        val residuals = DoubleArray(rows.size) { Double.NaN }
        for (i in validIdx.indices) {
            residuals[validIdx[i]] = csiVec[validIdx[i]] -
                (fit.a + fit.b * rrCvVec[validIdx[i]])
        }

        val pwaveKeys = listOf("pwave_energy", "pwave_pr_var", "pwave_fib_amp")

        println("Correlation of residual with P-wave features:")
        println("%-20s %10s %10s %10s".format("feature", "overall", "label0", "label1"))
        val featureCorrs = LinkedHashMap<String, Triple<Double, Double, Double>>()
        for (key in pwaveKeys) {
            val values = DoubleArray(rows.size) { rows[it].pwave[key] ?: Double.NaN }
            val overall = correlateFiltered(residuals, values, rows.indices.toList())
            val l0 = correlateFiltered(residuals, values, rows.indices.filter { rows[it].label == 0 })
            val l1 = correlateFiltered(residuals, values, rows.indices.filter { rows[it].label == 1 })
            featureCorrs[key] = Triple(overall, l0, l1)
            println("%-20s %10.4f %10.4f %10.4f".format(key, overall, l0, l1))
        }
        println()

        val summaryFile = File(reportDir, "pwave-summary-$timestamp.csv")
        writeSummary(summaryFile, arm, k, fit, rows.size, featureCorrs)

        val perRecordFile = File(reportDir, "pwave-per-record-$timestamp.csv")
        writePerRecord(perRecordFile, rows, residuals)

        println("Reports written to $reportDir")
        println("  ${summaryFile.name}")
        println("  ${perRecordFile.name}")
    }

    private fun computeRecord(
        rec: CorpusRecord,
        label: Int,
        arm: Arm,
        k: Double
    ): RecordFeatures? {
        val record = try {
            WfdbReader.read(rec.basePath, attachAnnotations = false)
        } catch (t: Throwable) { return null }

        val maxSamples = (record.sampleRate * 30 * 60).toInt()
        val trimmed: EcgRecord = if (record.samples.size > maxSamples) {
            EcgRecord(
                samples = record.samples.copyOfRange(0, maxSamples),
                sampleRate = record.sampleRate,
                units = record.units,
                leadLabel = record.leadLabel,
                metadata = record.metadata
            )
        } else record

        val detected = PanTompkins.detect(trimmed)
        val rPeaks = detected.annotations.map { it.sampleIndex }
        if (rPeaks.size < 20) return null

        val tachogram = RrTachogram.from(detected)
        val rrMs = tachogram.normalRr()
        if (rrMs.size < 20) return null

        val network = SensitivityArms.build(arm = arm, k = k)
        val csi = CsiRunner.run(network, rrMs) ?: return null

        val rrS = rrMs.map { it / 1000.0 }
        val mean = rrS.average()
        val sd = kotlin.math.sqrt(rrS.sumOf { (it - mean) * (it - mean) } / (rrS.size - 1))
        val rrCv = if (mean > 1e-9) sd / mean else Double.NaN

        val pwave = PWaveFeatures.computeAll(
            samples = trimmed.samples,
            sampleRate = trimmed.sampleRate,
            rPeaks = rPeaks
        )

        return RecordFeatures(
            name = rec.name,
            label = label,
            csiMean = csi.rMean,
            rrCv = rrCv,
            pwave = pwave
        )
    }

    private fun correlateFiltered(
        residuals: DoubleArray,
        values: DoubleArray,
        indices: List<Int>
    ): Double {
        val pairs = indices.filter {
            !residuals[it].isNaN() && !values[it].isNaN()
        }
        if (pairs.size < 3) return Double.NaN
        val x = DoubleArray(pairs.size) { residuals[pairs[it]] }
        val y = DoubleArray(pairs.size) { values[pairs[it]] }
        return Spearman.compute(x, y).rho
    }

    private fun writeSummary(
        summaryFile: File,
        arm: Arm,
        k: Double,
        fit: ResidualAnalysis.Fit,
        n: Int,
        featureCorrelations: Map<String, Triple<Double, Double, Double>>
    ) {
        BufferedWriter(
            OutputStreamWriter(summaryFile.outputStream(), StandardCharsets.UTF_8)
        ).use { w ->
            w.write("key,value\n")
            w.write("arm,${arm.name}\n")
            w.write("k,$k\n")
            w.write("n,$n\n")
            w.write("regression_intercept,%.6f\n".format(fit.a))
            w.write("regression_slope,%.6f\n".format(fit.b))
            w.write("\n")
            w.write("feature,rho_overall,rho_label0,rho_label1\n")
            for ((key, triple) in featureCorrelations) {
                w.write("$key,%.6f,%.6f,%.6f\n".format(
                    triple.first, triple.second, triple.third))
            }
        }
    }

    private fun writePerRecord(
        perRecordFile: File,
        rows: List<RecordFeatures>,
        residuals: DoubleArray
    ) {
        val keys = listOf("pwave_energy", "pwave_pr_var", "pwave_fib_amp")
        BufferedWriter(
            OutputStreamWriter(perRecordFile.outputStream(), StandardCharsets.UTF_8)
        ).use { w ->
            w.write("name,label,csi_mean,rr_cv,residual")
            for (key in keys) w.write(",$key")
            w.write("\n")
            for (i in rows.indices) {
                val r = rows[i]
                w.write(r.name)
                w.write(",${r.label}")
                w.write(",%.6f".format(r.csiMean))
                w.write(",%.6f".format(r.rrCv))
                w.write(",")
                w.write(if (residuals[i].isNaN()) "" else "%.6f".format(residuals[i]))
                for (key in keys) {
                    val v = r.pwave[key] ?: Double.NaN
                    w.write(",")
                    w.write(if (v.isNaN()) "" else "%.6f".format(v))
                }
                w.write("\n")
            }
        }
    }
}
