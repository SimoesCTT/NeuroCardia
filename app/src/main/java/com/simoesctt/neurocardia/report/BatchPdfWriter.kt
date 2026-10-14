package com.simoesctt.neurocardia.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.simoesctt.neurocardia.batch.BatchStudy
import com.simoesctt.neurocardia.io.MetricKeys
import java.io.OutputStream
import java.time.LocalDateTime

object BatchPdfWriter {

    private const val PAGE_WIDTH = 842
    private const val PAGE_HEIGHT = 595
    private const val MARGIN = 30f
    private const val ROW_HEIGHT = 14f
    private const val CELL_PADDING = 4f

    fun write(study: BatchStudy, output: OutputStream) {
        val doc = PdfDocument()

        val titlePaint = Paint().apply {
            color = Color.parseColor("#0B1B2E")
            textSize = 20f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val subtitlePaint = Paint().apply {
            color = Color.parseColor("#475569")
            textSize = 10f
            isAntiAlias = true
        }
        val sectionPaint = Paint().apply {
            color = Color.parseColor("#0E7490")
            textSize = 12f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val labelPaint = Paint().apply {
            color = Color.parseColor("#64748B")
            textSize = 10f
            isAntiAlias = true
        }
        val valuePaint = Paint().apply {
            color = Color.parseColor("#0B1B2E")
            textSize = 11f
            typeface = Typeface.MONOSPACE
            isFakeBoldText = true
            isAntiAlias = true
        }
        val bodyPaint = Paint().apply {
            color = Color.parseColor("#0B1B2E")
            textSize = 9f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        val headerPaint = Paint().apply {
            color = Color.parseColor("#0E7490")
            textSize = 9f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val footerPaint = Paint().apply {
            color = Color.parseColor("#94A3B8")
            textSize = 8f
            isAntiAlias = true
        }

        // ─── Page 1: cohort headline + cohort table ─────────────────────
        val pageInfo1 = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page1 = doc.startPage(pageInfo1)
        val c1 = page1.canvas
        var y = MARGIN + 18f

        c1.drawText("NeuroCardia — Batch Cohort Summary", MARGIN, y, titlePaint)
        y += 16f
        c1.drawText("Generated: ${LocalDateTime.now()}", MARGIN, y, subtitlePaint)
        y += 12f
        c1.drawText(
            "Records: ${study.summary.nRecords}   Failures: ${study.summary.nFailures}",
            MARGIN, y, subtitlePaint
        )
        y += 20f

        // Cohort headline: means of the key metrics.
        c1.drawText("COHORT HEADLINE", MARGIN, y, sectionPaint)
        y += 16f
        val hrStats = study.summary.cohort[MetricKeys.HEART_RATE_BPM]
        val csiStats = study.summary.cohort[MetricKeys.CSI_R]
        val residualStats = study.summary.cohort[MetricKeys.CSI_RESIDUAL]
        val qualityStats = study.summary.cohort["quality_score"]

        drawMean(c1, "Heart rate", hrStats?.mean, "BPM",
            MARGIN, y, labelPaint, valuePaint); y += 15f
        drawMean(c1, "CSI r", csiStats?.mean, "",
            MARGIN, y, labelPaint, valuePaint); y += 15f
        drawMean(c1, "Residual", residualStats?.mean, "",
            MARGIN, y, labelPaint, valuePaint); y += 15f
        drawMean(c1, "Signal quality", qualityStats?.mean, "",
            MARGIN, y, labelPaint, valuePaint); y += 22f

        // Cohort statistics table.
        c1.drawText("COHORT STATISTICS", MARGIN, y, sectionPaint)
        y += 16f

        val cohortHeaders = listOf("metric", "n", "mean", "median", "sd", "min", "max", "p25", "p75")
        val cohortRows = ArrayList<List<String>>()
        for ((metric, s) in study.summary.cohort.entries.sortedBy { it.key }) {
            cohortRows.add(listOf(
                metric,
                s.n.toString(),
                fmt(s.mean),
                fmt(s.median),
                fmt(s.sd),
                fmt(s.min),
                fmt(s.max),
                fmt(s.p25),
                fmt(s.p75)
            ))
        }
        drawTable(c1, cohortHeaders, cohortRows, MARGIN, y,
            headerPaint, bodyPaint, PAGE_HEIGHT - MARGIN - 30f)

        // Residual fit block, if defined.
        val fit = study.summary.residualFit
        if (fit.valid) {
            y += 20f
            c1.drawText("Kuramoto residual fit", MARGIN, y, sectionPaint)
            y += 16f
            c1.drawText(
                "CSI = alpha + beta * rr_cv + epsilon, fit across ${fit.n} records.",
                MARGIN, y, bodyPaint
            )
            y += 12f
            c1.drawText("alpha = %.6f".format(fit.alpha), MARGIN, y, bodyPaint)
            y += 12f
            c1.drawText("beta  = %.6f".format(fit.beta), MARGIN, y, bodyPaint)
            y += 12f
            c1.drawText("mean rr_cv = %.6f".format(fit.meanCv), MARGIN, y, bodyPaint)
            y += 12f
            c1.drawText("mean CSI   = %.6f".format(fit.meanCsi), MARGIN, y, bodyPaint)
            y += 12f
            c1.drawText(
                "Per-record residuals are in the csi_residual column of the per-record table.",
                MARGIN, y, bodyPaint
            )
            y += 12f
        } else {
            y += 20f
            c1.drawText("Kuramoto residual fit", MARGIN, y, sectionPaint)
            y += 16f
            c1.drawText(
                "Not computed. The residual requires at least two records " +
                "with both csi_r and rr_cv present, and nonzero variance " +
                "in rr_cv across the cohort.",
                MARGIN, y, bodyPaint
            )
            y += 12f
        }

        c1.drawText(
            "NeuroCardia — GPL-3.0-or-later — all processing on-device",
            MARGIN, PAGE_HEIGHT - MARGIN + 5f, footerPaint
        )
        doc.finishPage(page1)

        // ─── Page 2: per-record table ───────────────────────────────────
        val pageInfo2 = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 2).create()
        val page2 = doc.startPage(pageInfo2)
        val c2 = page2.canvas
        y = MARGIN + 18f

        c2.drawText("PER-RECORD SUMMARY", MARGIN, y, sectionPaint)
        y += 20f

        // Show the headline metrics per record first, then the full table.
        val headlineKeys = listOf(
            MetricKeys.HEART_RATE_BPM,
            MetricKeys.CSI_R,
            MetricKeys.CSI_RESIDUAL,
            "quality_score",
            MetricKeys.RR_CV
        ).filter { key -> study.summary.perRecord.any { it.metrics.containsKey(key) } }

        val headers = ArrayList<String>()
        headers.add("source")
        headers.add("dur_s")
        for (key in headlineKeys) headers.add(abbreviate(key))

        val rows = ArrayList<List<String>>()
        for (row in study.summary.perRecord) {
            val line = ArrayList<String>()
            line.add(shortSource(row.source))
            line.add("%.1f".format(row.durationS))
            for (key in headlineKeys) {
                val v = row.metrics[key]
                line.add(if (v == null || v.isNaN()) "—" else fmt(v))
            }
            rows.add(line)
        }

        drawTable(c2, headers, rows, MARGIN, y,
            headerPaint, bodyPaint, PAGE_HEIGHT - MARGIN - 30f)

        c2.drawText(
            "Full-precision values, including all HRV and quality metrics, are in the CSV export.",
            MARGIN, PAGE_HEIGHT - MARGIN + 5f, footerPaint
        )
        doc.finishPage(page2)

        // ─── Page 3: disclaimer ─────────────────────────────────────────
        val pageInfo3 = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 3).create()
        val page3 = doc.startPage(pageInfo3)
        val c3 = page3.canvas
        y = MARGIN + 18f

        c3.drawText("DISCLAIMER AND INTERPRETATION", MARGIN, y, sectionPaint)
        y += 22f

        val disclaimer = """
            This batch report is generated by NeuroCardia, a decision-support
            tool for trained clinicians and researchers. It does not replace
            clinical judgment, does not provide a diagnosis, and does not
            guide treatment.

            The values in the tables above are descriptive summaries of the
            recordings processed. Cohort statistics characterize the
            distribution of each metric across the set of records.
            Per-record rows show the individual values. Neither explains why
            a value is what it is.

            The Cardiac Synchronization Index (CSI), the Kuramoto residual,
            and the associated early warning signals are research features.
            They are not cleared for clinical use. They should not be used
            to make clinical decisions.

            All processing was performed on-device. No data left the device.
        """.trimIndent()

        val lines = wrapText(disclaimer, bodyPaint, PAGE_WIDTH - 2 * MARGIN)
        for (line in lines) {
            if (y > PAGE_HEIGHT - MARGIN - 10f) break
            c3.drawText(line, MARGIN, y, bodyPaint)
            y += 12f
        }
        doc.finishPage(page3)

        doc.writeTo(output)
        doc.close()
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun drawMean(
        canvas: Canvas,
        label: String,
        value: Double?,
        unit: String,
        x: Float,
        y: Float,
        labelPaint: Paint,
        valuePaint: Paint
    ) {
        canvas.drawText(label, x, y, labelPaint)
        val text = when {
            value == null || value.isNaN() -> "—"
            unit.isEmpty() -> "%.4f".format(value)
            else -> "%.1f %s".format(value, unit)
        }
        canvas.drawText(text, x + 200f, y, valuePaint)
    }

    private fun drawTable(
        canvas: Canvas,
        headers: List<String>,
        rows: List<List<String>>,
        startX: Float,
        startY: Float,
        headerPaint: Paint,
        bodyPaint: Paint,
        maxY: Float
    ) {
        val nCols = headers.size
        if (nCols == 0) return

        val widths = FloatArray(nCols)
        for (i in 0 until nCols) {
            var w = headerPaint.measureText(headers[i])
            for (row in rows) {
                if (i < row.size) {
                    val vw = bodyPaint.measureText(row[i])
                    if (vw > w) w = vw
                }
            }
            widths[i] = w + 2 * CELL_PADDING
        }

        val available = (PAGE_WIDTH - 2 * MARGIN)
        var total = 0f
        for (w in widths) total += w
        if (total > available) {
            val scale = available / total
            for (i in widths.indices) widths[i] *= scale
        }

        var x = startX
        for (i in 0 until nCols) {
            canvas.drawText(headers[i], x + CELL_PADDING, startY, headerPaint)
            x += widths[i]
        }
        var y = startY + ROW_HEIGHT

        for (row in rows) {
            if (y > maxY) break
            x = startX
            for (i in 0 until nCols) {
                if (i < row.size) {
                    canvas.drawText(row[i], x + CELL_PADDING, y, bodyPaint)
                }
                x += widths[i]
            }
            y += ROW_HEIGHT
        }
    }

    private fun abbreviate(key: String): String = when (key) {
        MetricKeys.HEART_RATE_BPM -> "hr_bpm"
        MetricKeys.CSI_R -> "csi_r"
        MetricKeys.CSI_RESIDUAL -> "residual"
        MetricKeys.RR_CV -> "rr_cv"
        "quality_score" -> "qual"
        MetricKeys.RMSSD_MS -> "rmssd"
        MetricKeys.SDNN_MS -> "sdnn"
        else -> key.take(12)
    }

    private fun shortSource(source: String): String {
        val idx = source.lastIndexOf('/')
        return if (idx >= 0 && idx < source.length - 1) source.substring(idx + 1)
        else source
    }

    private fun fmt(v: Double): String =
        if (v.isNaN()) "—"
        else if (kotlin.math.abs(v) >= 1000) "%.0f".format(v)
        else "%.3f".format(v)

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        val paragraphs = text.split("\n")
        for (para in paragraphs) {
            if (para.isEmpty()) { result.add(""); continue }
            val words = para.split(" ")
            var current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current = StringBuilder(candidate)
                } else {
                    if (current.isNotEmpty()) result.add(current.toString())
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) result.add(current.toString())
        }
        return result
    }
}
