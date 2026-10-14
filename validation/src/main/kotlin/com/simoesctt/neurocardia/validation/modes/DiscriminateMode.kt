package com.simoesctt.neurocardia.validation.modes

import com.simoesctt.neurocardia.validation.Arm
import com.simoesctt.neurocardia.validation.DiscriminativeTest
import com.simoesctt.neurocardia.validation.Feature
import com.simoesctt.neurocardia.validation.SensitivityArms
import com.simoesctt.neurocardia.validation.projectRoot
import java.io.File

object DiscriminateMode {

    fun run(args: List<String>) {
        if (args.size < 2) { println("discriminate requires sinus and AF folders"); return }
        val sinusFolder = File(args[0])
        val afFolder = File(args[1])
        if (!sinusFolder.isDirectory || !afFolder.isDirectory) {
            println("Both arguments must be directories"); return
        }

        val arms: List<Arm> = if (args.size >= 3) {
            args[2].split(",").mapNotNull { label ->
                Arm.all().firstOrNull { it.name == label.trim() }
            }
        } else listOf(Arm.A)

        val k: Double = if (args.size >= 4) {
            args[3].toDoubleOrNull() ?: SensitivityArms.DEFAULT_K
        } else SensitivityArms.DEFAULT_K

        val feature: Feature = if (args.size >= 5) {
            Feature.valueOf(args[4].uppercase())
        } else Feature.RR

        val reportDir = File(projectRoot(), "validation/reports").apply { mkdirs() }
        val timestamp = System.currentTimeMillis()

        println("Discriminative validity test")
        println("  sinus: ${sinusFolder.absolutePath}")
        println("  AF:    ${afFolder.absolutePath}")
        println("  arms:  ${arms.map { it.name }}")
        println("  K:     $k")
        println("  feat:  ${feature.name}")
        println()

        val results = mutableListOf<DiscriminativeTest.Result>()
        for (arm in arms) {
            println("=== Arm ${arm.name} (${arm.label}) ===")
            val result = DiscriminativeTest.run(sinusFolder, afFolder, arm, k, feature)
            results.add(result)
            println("  AUC: %.4f  (n_sinus=%d, n_AF=%d)".format(result.auc, result.nSinus, result.nAf))
            println()
        }

        val summaryCsv = File(reportDir, "discriminate-summary-$timestamp.csv")
        summaryCsv.bufferedWriter().use { w ->
            w.write("arm,feature,k,auc,n_sinus,n_af\n")
            for (r in results) {
                w.write("${r.arm.name},${r.feature.name},${r.k},%.4f,${r.nSinus},${r.nAf}\n".format(r.auc))
            }
        }
        println("Reports written to $reportDir")
    }
}
