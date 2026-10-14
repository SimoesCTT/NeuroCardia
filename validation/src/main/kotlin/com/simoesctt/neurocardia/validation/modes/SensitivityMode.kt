package com.simoesctt.neurocardia.validation.modes

import com.simoesctt.neurocardia.dsp.PanTompkins
import com.simoesctt.neurocardia.io.RrTachogram
import com.simoesctt.neurocardia.io.wfdb.WfdbReader
import com.simoesctt.neurocardia.validation.Arm
import com.simoesctt.neurocardia.validation.Corpus
import com.simoesctt.neurocardia.validation.CsiRunner
import com.simoesctt.neurocardia.validation.Metrics
import com.simoesctt.neurocardia.validation.Report
import com.simoesctt.neurocardia.validation.SensitivityArms
import com.simoesctt.neurocardia.validation.projectRoot
import java.io.File

object SensitivityMode {

    fun run(args: List<String>) {
        if (args.isEmpty()) { println("sensitivity requires a corpus folder"); return }
        val corpusFolder = File(args[0])
        if (!corpusFolder.isDirectory) { println("Not a directory: $corpusFolder"); return }

        val kValues: List<Double> = if (args.size >= 2) {
            args[1].split(",").mapNotNull { it.trim().toDoubleOrNull() }
        } else listOf(SensitivityArms.DEFAULT_K)

        val records = Corpus.findIn(corpusFolder)
        println("Found ${records.size} records in $corpusFolder")
        println("K values to test: $kValues")
        if (records.isEmpty()) return

        val reportDir = File(projectRoot(), "validation/reports").apply { mkdirs() }

        println()
        println("Precomputing RR tachograms...")
        val tachograms = mutableMapOf<String, List<Double>>()
        records.forEach { rec ->
            try {
                val record = WfdbReader.read(rec.basePath, attachAnnotations = false)
                val detected = PanTompkins.detect(record)
                val tachogram = RrTachogram.from(detected)
                val rr = tachogram.normalRr()
                if (rr.size >= 10) tachograms[rec.name] = rr
            } catch (_: Throwable) { }
        }
        println("Precomputed ${tachograms.size} tachograms.")
        println()

        val seed = 42L
        for (k in kValues) {
            println("=== K = $k ===")
            val timestamp = System.currentTimeMillis()
            val armRuns = mutableListOf<Report.ArmRun>()
            val correlations = mutableListOf<Report.Correlation>()
            var count = 0
            tachograms.forEach { (name, rr) ->
                count++
                if (count % 10 == 0) println("  [$count/${tachograms.size}] $name")
                var armATrajectory: DoubleArray? = null
                for (arm in Arm.all()) {
                    val network = SensitivityArms.build(arm = arm, seed = seed, k = k)
                    val result = CsiRunner.run(network, rr) ?: continue
                    armRuns.add(Report.ArmRun(name, arm, seed, result))
                    if (arm == Arm.A) {
                        armATrajectory = result.trajectory
                    } else if (armATrajectory != null) {
                        val a = armATrajectory!!
                        val b = result.trajectory
                        val n = minOf(a.size, b.size)
                        correlations.add(
                            Report.Correlation(
                                name, arm, seed,
                                Metrics.pearson(a.copyOfRange(0, n), b.copyOfRange(0, n)),
                                Metrics.dtw(a.copyOfRange(0, n), b.copyOfRange(0, n))
                            )
                        )
                    }
                }
            }
            for (arm in Arm.all()) {
                if (arm == Arm.A) continue
                val armCorrs = correlations.filter { it.otherArm == arm }
                if (armCorrs.isEmpty()) continue
                val meanP = armCorrs.map { it.pearson }.filter { !it.isNaN() }.average()
                val passCount = armCorrs.count { it.pearson >= 0.80 }
                println("  Arm %s: mean r = %.4f, pass %d/%d".format(
                    arm.name, meanP, passCount, armCorrs.size))
            }
            Report.writeArmRuns(armRuns, File(reportDir, "sensitivity-arms-K$k-$timestamp.csv"))
            Report.writeCorrelations(correlations, File(reportDir, "sensitivity-correlation-K$k-$timestamp.csv"))
            Report.writeVerdict(correlations, File(reportDir, "sensitivity-verdict-K$k-$timestamp.txt"))
            println("  reports written")
            println()
        }
    }
}
