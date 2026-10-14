package com.simoesctt.neurocardia.validation.modes

import com.simoesctt.neurocardia.validation.Arm
import com.simoesctt.neurocardia.validation.CorrelationTest
import com.simoesctt.neurocardia.validation.SensitivityArms
import com.simoesctt.neurocardia.validation.Spearman
import com.simoesctt.neurocardia.validation.projectRoot
import java.io.File

object CorrelateMode {

    fun run(args: List<String>) {
        if (args.size < 2) { println("correlate requires two folders"); return }
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

        println("Correlation analysis")
        println("  folder A: ${folderA.absolutePath} (label 0)")
        println("  folder B: ${folderB.absolutePath} (label 1)")
        println("  arm:      ${arm.name}")
        println("  K:        $k")
        println()

        val rows = CorrelationTest.run(folderA, folderB, 0, 1, arm, k)
        println("Computed ${rows.size} records.")
        println()

        val csiFeatures = CorrelationTest.csiFeatures
        val compareFeatures = CorrelationTest.compareFeatures

        val sb = StringBuilder("csi_feature")
        for (c in compareFeatures) sb.append(",").append(c)
        sb.append("\n")
        val pvSb = StringBuilder("csi_feature")
        for (c in compareFeatures) pvSb.append(",").append(c)
        pvSb.append("\n")

        for ((i, csiName) in csiFeatures.withIndex()) {
            val csiVec = CorrelationTest.extractCsi(rows, i)
            sb.append(csiName)
            pvSb.append(csiName)
            for (j in compareFeatures.indices) {
                val compVec = CorrelationTest.extractCompare(rows, j)
                val pairs = csiVec.indices.filter {
                    !csiVec[it].isNaN() && !compVec[it].isNaN()
                }
                if (pairs.size < 3) {
                    sb.append(",NaN")
                    pvSb.append(",NaN")
                    continue
                }
                val x = DoubleArray(pairs.size) { csiVec[pairs[it]] }
                val y = DoubleArray(pairs.size) { compVec[pairs[it]] }
                val result = Spearman.compute(x, y)
                sb.append(",%.4f".format(result.rho))
                pvSb.append(",%.4f".format(result.pValue))
            }
            sb.append("\n")
            pvSb.append("\n")
        }

        val rhoFile = File(reportDir, "correlations-$timestamp.csv")
        rhoFile.writeText(sb.toString())
        val pvFile = File(reportDir, "correlations-pvalues-$timestamp.csv")
        pvFile.writeText(pvSb.toString())

        println(sb.toString())
        println("Reports written to $reportDir")
        println("  ${rhoFile.name}")
        println("  ${pvFile.name}")
    }
}
