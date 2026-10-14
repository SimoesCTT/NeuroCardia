package com.simoesctt.neurocardia.batch

import com.simoesctt.neurocardia.io.MetricKeys
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object BatchReport {

    /**
     * Column ordering for the per-record CSV. Headline metrics first,
     * then CSI summary, then all remaining metrics alphabetically.
     * Column order is deterministic: two runs over the same input
     * produce byte-identical files.
     */
    private fun orderedMetricKeys(rows: List<StudyRow>): List<String> {
        val allKeys = rows.flatMap { it.metrics.keys }.toSortedSet()
        val headline = listOf(
            MetricKeys.HEART_RATE_BPM,
            MetricKeys.CSI_R,
            MetricKeys.CSI_RESIDUAL,
            "quality_score"
        )
        val summary = listOf(
            MetricKeys.CSI_VAR,
            MetricKeys.CSI_SKEW,
            MetricKeys.CSI_KURT,
            MetricKeys.CSI_LAG1AC,
            MetricKeys.RR_CV
        )
        val out = ArrayList<String>(allKeys.size)
        for (k in headline) if (k in allKeys) out.add(k)
        for (k in summary) if (k in allKeys && k !in out) out.add(k)
        for (k in allKeys) if (k !in out) out.add(k)
        return out
    }

    fun writePerRecordCsv(study: BatchStudy, output: File) {
        val metricKeys = orderedMetricKeys(study.summary.perRecord)

        BufferedWriter(
            OutputStreamWriter(output.outputStream(), StandardCharsets.UTF_8)
        ).use { w ->
            w.write("source,duration_s")
            for (k in metricKeys) { w.write(","); w.write(k) }
            w.write("\n")

            study.summary.perRecord.forEach { row ->
                w.write(escapeCsv(row.source)); w.write(",")
                w.write("%.4f".format(row.durationS))
                for (k in metricKeys) {
                    w.write(",")
                    val v = row.metrics[k]
                    w.write(if (v == null || v.isNaN()) "" else "%.6f".format(v))
                }
                w.write("\n")
            }
        }
    }

    fun writeCohortCsv(study: BatchStudy, output: File) {
        BufferedWriter(
            OutputStreamWriter(output.outputStream(), StandardCharsets.UTF_8)
        ).use { w ->
            w.write("metric,n,mean,median,sd,min,max,p25,p75\n")
            study.summary.cohort.entries
                .sortedBy { it.key }
                .forEach { (metric, s) ->
                    w.write(metric); w.write(",")
                    w.write(s.n.toString()); w.write(",")
                    w.write("%.6f".format(s.mean)); w.write(",")
                    w.write("%.6f".format(s.median)); w.write(",")
                    w.write("%.6f".format(s.sd)); w.write(",")
                    w.write("%.6f".format(s.min)); w.write(",")
                    w.write("%.6f".format(s.max)); w.write(",")
                    w.write("%.6f".format(s.p25)); w.write(",")
                    w.write("%.6f".format(s.p75)); w.write("\n")
                }
        }
    }

    fun writeAuditLog(
        study: BatchStudy,
        inputs: List<InputFile>,
        output: File,
        appVersion: String,
        appBuildHash: String
    ) {
        BufferedWriter(
            OutputStreamWriter(output.outputStream(), StandardCharsets.UTF_8)
        ).use { w ->
            w.write("# NeuroCardia audit log\n")
            w.write("# app_version=$appVersion\n")
            w.write("# app_build_hash=$appBuildHash\n")
            w.write("# started_at=${java.time.Instant.now()}\n")
            w.write("# n_inputs=${inputs.size}\n")
            w.write("# n_success=${study.records.size}\n")
            w.write("# n_failures=${study.failures.size}\n")
            w.write("source,size_bytes,sha256,outcome,error\n")

            val failureByPath = study.failures.associateBy { it.sourcePath }
            val successPaths = study.summary.perRecord.map { it.source }.toSet()

            inputs.forEach { input ->
                val sha = sha256Of(input.file)
                val path = input.file.absolutePath
                val outcome: String
                val error: String
                val failed = failureByPath[path]
                when {
                    path in successPaths -> { outcome = "success"; error = "" }
                    failed != null -> { outcome = "failure"; error = failed.error }
                    else -> { outcome = "skipped"; error = "" }
                }
                w.write(escapeCsv(path)); w.write(",")
                w.write(input.sizeBytes.toString()); w.write(",")
                w.write(sha); w.write(",")
                w.write(outcome); w.write(",")
                w.write(escapeCsv(error)); w.write("\n")
            }
        }
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun escapeCsv(s: String): String {
        val needsQuote = s.contains(',') || s.contains('"') ||
                         s.contains('\n') || s.contains('\r')
        return if (needsQuote) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    /**
     * Write the cohort-level Kuramoto residual fit as a small CSV.
     */
    fun writeResidualFitCsv(study: BatchStudy, output: java.io.File) {
        val fit = study.summary.residualFit
        BufferedWriter(
            OutputStreamWriter(output.outputStream(), java.nio.charset.StandardCharsets.UTF_8)
        ).use { w ->
            w.write("parameter,value\n")
            w.write("valid,${fit.valid}\n")
            w.write("n,${fit.n}\n")
            w.write("alpha,%.6f\n".format(fit.alpha))
            w.write("beta,%.6f\n".format(fit.beta))
            w.write("mean_cv,%.6f\n".format(fit.meanCv))
            w.write("mean_csi,%.6f\n".format(fit.meanCsi))
        }
    }
}
