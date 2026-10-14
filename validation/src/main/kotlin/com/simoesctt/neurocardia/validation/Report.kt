package com.simoesctt.neurocardia.validation

import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object Report {

    data class ArmRun(
        val record: String,
        val arm: Arm,
        val seed: Long,
        val result: CsiRunner.Result
    )

    data class Correlation(
        val record: String,
        val otherArm: Arm,
        val seed: Long,
        val pearson: Double,
        val dtw: Double
    )

    fun writeArmRuns(runs: List<ArmRun>, output: File) {
        BufferedWriter(OutputStreamWriter(output.outputStream(), StandardCharsets.UTF_8)).use { w ->
            w.write("record,arm,seed,r_mean,r_var,r_skew,r_kurt,r_lag1\n")
            for (r in runs) {
                w.write(r.record); w.write(",")
                w.write(r.arm.name); w.write(",")
                w.write(r.seed.toString()); w.write(",")
                w.write("%.6f".format(r.result.rMean)); w.write(",")
                w.write("%.6f".format(r.result.rVar)); w.write(",")
                w.write("%.6f".format(r.result.rSkew)); w.write(",")
                w.write("%.6f".format(r.result.rKurt)); w.write(",")
                w.write("%.6f".format(r.result.rLag1)); w.write("\n")
            }
        }
    }

    fun writeCorrelations(corrs: List<Correlation>, output: File) {
        BufferedWriter(OutputStreamWriter(output.outputStream(), StandardCharsets.UTF_8)).use { w ->
            w.write("record,other_arm,seed,pearson,dtw\n")
            for (c in corrs) {
                w.write(c.record); w.write(",")
                w.write(c.otherArm.name); w.write(",")
                w.write(c.seed.toString()); w.write(",")
                w.write("%.6f".format(c.pearson)); w.write(",")
                w.write("%.6f".format(c.dtw)); w.write("\n")
            }
        }
    }

    fun writeVerdict(
        corrs: List<Correlation>,
        output: File,
        acceptanceThreshold: Double = 0.80
    ) {
        BufferedWriter(OutputStreamWriter(output.outputStream(), StandardCharsets.UTF_8)).use { w ->
            w.write("# NeuroCardia sensitivity analysis verdict\n")
            w.write("# Generated: ${java.time.Instant.now()}\n")
            w.write("# Threshold: Pearson correlation with Arm A >= %.2f\n".format(acceptanceThreshold))
            w.write("\n")

            for (arm in Arm.all()) {
                if (arm == Arm.A) continue
                val armCorrs = corrs.filter { it.otherArm == arm }
                if (armCorrs.isEmpty()) {
                    w.write("Arm ${arm.name} (${arm.label}): no data\n\n")
                    continue
                }
                val meanPearson = armCorrs.map { it.pearson }.filter { !it.isNaN() }.average()
                val meanDtw = armCorrs.map { it.dtw }.filter { !it.isNaN() }.average()
                val passCount = armCorrs.count { it.pearson >= acceptanceThreshold }
                val total = armCorrs.size
                val verdict = if (meanPearson >= acceptanceThreshold) "PASS" else "FAIL"

                w.write("Arm ${arm.name} (${arm.label})\n")
                w.write("  mean Pearson correlation with Arm A: %.4f\n".format(meanPearson))
                w.write("  mean normalized DTW:                 %.4f\n".format(meanDtw))
                w.write("  records passing threshold:           %d / %d\n".format(passCount, total))
                w.write("  verdict:                             %s\n".format(verdict))
                w.write("\n")
            }
        }
    }
}
