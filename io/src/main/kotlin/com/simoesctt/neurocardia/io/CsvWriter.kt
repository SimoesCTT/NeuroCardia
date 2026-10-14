package com.simoesctt.neurocardia.io

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object CsvWriter {

    /**
     * Write the RR tachogram derived from the record's R-peak annotations.
     * One row per beat. Columns: beat_index, r_peak_sample, r_peak_time_s,
     * rr_ms, beat_type.
     */
    fun writeRrIntervals(record: EcgRecord, outputStream: OutputStream) {
        val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
        writer.use { w ->
            w.write("beat_index,r_peak_sample,r_peak_time_s,rr_ms,beat_type\n")

            val sorted = record.annotations.sortedBy { it.sampleIndex }
            var previousSample: Int? = null

            sorted.forEachIndexed { index, annotation ->
                val prev = previousSample
                val rrMs = if (prev != null) {
                    ((annotation.sampleIndex - prev) / record.sampleRate) * 1000.0
                } else 0.0

                w.write(
                    "${index + 1}," +
                    "${annotation.sampleIndex}," +
                    "%.4f,".format(annotation.timeS(record.sampleRate)) +
                    "%.2f,".format(rrMs) +
                    "${annotation.type}\n"
                )
                previousSample = annotation.sampleIndex
            }
            w.flush()
        }
    }

    /**
     * Write the full metric set for a single record. Two-column format:
     * key,value. Ordered by tier:
     *
     *   1. Headline: heart_rate_bpm, csi_r, csi_residual, quality_score
     *   2. CSI summary: csi_var, csi_skew, csi_kurt, csi_lag1ac, rr_cv
     *   3. Everything else, sorted alphabetically.
     *
     * Every metric in the record's analysis map appears exactly once.
     */
    fun writeMetrics(record: EcgRecord, outputStream: OutputStream) {
        val metrics = record.analysis?.metrics ?: emptyMap()
        val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
        writer.use { w ->
            w.write("key,value\n")

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

            val written = mutableSetOf<String>()

            for (key in headline) {
                val v = metrics[key] ?: continue
                w.write("$key,")
                w.write(if (v.isNaN()) "" else "%.6f".format(v))
                w.write("\n")
                written.add(key)
            }

            for (key in summary) {
                val v = metrics[key] ?: continue
                if (key in written) continue
                w.write("$key,")
                w.write(if (v.isNaN()) "" else "%.6f".format(v))
                w.write("\n")
                written.add(key)
            }

            val remaining = metrics.keys.filter { it !in written }.sorted()
            for (key in remaining) {
                val v = metrics[key] ?: continue
                w.write("$key,")
                w.write(if (v.isNaN()) "" else "%.6f".format(v))
                w.write("\n")
            }
            w.flush()
        }
    }

    /**
     * Write a record's metadata as a two-column key,value CSV. Includes
     * source, reader, sample rate, units, lead label, duration, and beat
     * count. Useful for provenance and for joining with the metrics CSV.
     */
    fun writeRecordInfo(record: EcgRecord, outputStream: OutputStream) {
        val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
        writer.use { w ->
            w.write("key,value\n")
            w.write("source,${escapeCsv(record.metadata["source"] ?: "")}\n")
            w.write("reader,${escapeCsv(record.metadata["reader"] ?: "")}\n")
            w.write("sample_rate_hz,%.4f\n".format(record.sampleRate))
            w.write("units,${record.units}\n")
            w.write("lead_label,${escapeCsv(record.leadLabel ?: "")}\n")
            w.write("samples,${record.samples.size}\n")
            w.write("duration_s,%.4f\n".format(record.durationS))
            w.write("beats_detected,${record.annotations.size}\n")
            for ((k, v) in record.metadata.entries.sortedBy { it.key }) {
                if (k in setOf("source", "reader")) continue
                w.write("$k,${escapeCsv(v)}\n")
            }
            w.flush()
        }
    }

    private fun escapeCsv(s: String): String {
        val needsQuote = s.contains(',') || s.contains('"') ||
                         s.contains('\n') || s.contains('\r')
        return if (needsQuote) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
