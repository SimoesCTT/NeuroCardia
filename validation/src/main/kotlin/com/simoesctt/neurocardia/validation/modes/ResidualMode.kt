package com.simoesctt.neurocardia.validation.modes

import com.simoesctt.neurocardia.validation.Arm
import com.simoesctt.neurocardia.validation.CorrelationTest
import com.simoesctt.neurocardia.validation.ResidualAnalysis
import com.simoesctt.neurocardia.validation.SensitivityArms
import com.simoesctt.neurocardia.validation.projectRoot
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object ResidualMode {

    fun run(args: List<String>) {
        if (args.size < 2) { println("residual requires two folders"); return }
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

        println("Residual analysis")
        println("  folder A: ${folderA.absolutePath} (label 0)")
        println("  folder B: ${folderB.absolutePath} (label 1)")
        println("  arm:      ${arm.name}")
        println("  K:        $k")
        println()

        val rows = CorrelationTest.run(folderA, folderB, 0, 1, arm, k)
        println("Computed ${rows.size} records.")
        println()

        val csiMean = CorrelationTest.extractCsi(rows, 0)
        val rrCv = CorrelationTest.extractCompare(rows, 11)

        val validIdx = csiMean.indices.filter {
            !csiMean[it].isNaN() && !rrCv[it].isNaN()
        }
        val xVals = DoubleArray(validIdx.size) { rrCv[validIdx[it]] }
        val yVals = DoubleArray(validIdx.size) { csiMean[validIdx[it]] }

        val fit = ResidualAnalysis.fit(xVals, yVals)
        println("Regression: csi_mean = %.4f + %.4f * rr_cv".format(fit.a, fit.b))
        println()

        val residuals = DoubleArray(rows.size) { Double.NaN }
        for (i in validIdx.indices) residuals[validIdx[i]] = fit.residuals[i]

        val results = ResidualAnalysis.correlateResiduals(residuals, rows)
        println("Correlation of residual with each feature:")
        println("%-20s %10s %10s".format("feature", "rho", "p"))
        for (r in results.sortedByDescending { kotlin.math.abs(it.rho) }) {
            println("%-20s %10.4f %10.4f".format(r.feature, r.rho, r.pValue))
        }
        println()

        val labelResult = ResidualAnalysis.correlateAgainstLabel(residuals, rows)
        println("Residual vs label (0=sinus, 1=AF):")
        println("  rho = %.4f   p = %.4f".format(labelResult.rho, labelResult.pValue))
        println()

        val auc = ResidualAnalysis.residualAuc(residuals, rows)
        println("AUC of residual separating sinus from AF: %.4f".format(auc))
        println()

        println("Per-record residuals:")
        println("%-10s %6s %10s %10s %10s".format("name", "label", "rr_cv", "csi_mean", "residual"))
        for (i in rows.indices) {
            val r = rows[i]
            val rrCvVal = if (rrCv[i].isNaN()) "NaN" else "%.4f".format(rrCv[i])
            val csiVal = if (csiMean[i].isNaN()) "NaN" else "%.4f".format(csiMean[i])
            val resVal = if (residuals[i].isNaN()) "NaN" else "%.4f".format(residuals[i])
            println("%-10s %6d %10s %10s %10s".format(r.name, r.label, rrCvVal, csiVal, resVal))
        }

        // ─── Write reports ──────────────────────────────────────────────

        val summaryFile = File(reportDir, "residual-summary-$timestamp.csv")
        writeSummary(
            summaryFile = summaryFile,
            arm = arm,
            k = k,
            fit = fit,
            labelRho = labelResult.rho,
            labelP = labelResult.pValue,
            auc = auc,
            n = rows.size,
            nValid = validIdx.size,
            featureCorrelations = results
        )

        val perRecordFile = File(reportDir, "residual-per-record-$timestamp.csv")
        writePerRecord(
            perRecordFile = perRecordFile,
            rows = rows,
            csiMean = csiMean,
            rrCv = rrCv,
            residuals = residuals
        )

        println()
        println("Reports written to $reportDir")
        println("  ${summaryFile.name}")
        println("  ${perRecordFile.name}")
    }

    private fun writeSummary(
        summaryFile: File,
        arm: Arm,
        k: Double,
        fit: ResidualAnalysis.Fit,
        labelRho: Double,
        labelP: Double,
        auc: Double,
        n: Int,
        nValid: Int,
        featureCorrelations: List<ResidualAnalysis.Result>
    ) {
        BufferedWriter(
            OutputStreamWriter(summaryFile.outputStream(), StandardCharsets.UTF_8)
        ).use { w ->
            w.write("key,value\n")
            w.write("arm,${arm.name}\n")
            w.write("k,$k\n")
            w.write("n,$n\n")
            w.write("n_valid,$nValid\n")
            w.write("regression_intercept,%.6f\n".format(fit.a))
            w.write("regression_slope,%.6f\n".format(fit.b))
            w.write("residual_rho_vs_label,%.6f\n".format(labelRho))
            w.write("residual_p_vs_label,%.6f\n".format(labelP))
            w.write("residual_auc,%.6f\n".format(auc))
            w.write("\n")
            w.write("feature,rho,p\n")
            for (r in featureCorrelations.sortedByDescending { kotlin.math.abs(it.rho) }) {
                w.write("${r.feature},%.6f,%.6f\n".format(r.rho, r.pValue))
            }
        }
    }

    private fun writePerRecord(
        perRecordFile: File,
        rows: List<CorrelationTest.Row>,
        csiMean: DoubleArray,
        rrCv: DoubleArray,
        residuals: DoubleArray
    ) {
        BufferedWriter(
            OutputStreamWriter(perRecordFile.outputStream(), StandardCharsets.UTF_8)
        ).use { w ->
            w.write("name,label,rr_cv,csi_mean,residual\n")
            for (i in rows.indices) {
                val r = rows[i]
                val rrCvVal = if (rrCv[i].isNaN()) "" else "%.6f".format(rrCv[i])
                val csiVal = if (csiMean[i].isNaN()) "" else "%.6f".format(csiMean[i])
                val resVal = if (residuals[i].isNaN()) "" else "%.6f".format(residuals[i])
                w.write("${r.name},${r.label},$rrCvVal,$csiVal,$resVal\n")
            }
        }
    }
}
