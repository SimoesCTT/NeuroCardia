package com.simoesctt.neurocardia.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.simoesctt.neurocardia.dsp.SignalQuality
import com.simoesctt.neurocardia.io.EcgRecord
import com.simoesctt.neurocardia.io.MetricDescriptions
import com.simoesctt.neurocardia.io.MetricKeys
import com.simoesctt.neurocardia.ui.viewmodel.AnalysisResultBundle
import java.io.OutputStream
import java.time.LocalDateTime

object PdfWriter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f

    fun write(bundle: AnalysisResultBundle, output: OutputStream, appVersion: String) {
        val doc = PdfDocument()

        val titlePaint = Paint().apply {
            color = Color.parseColor("#0B1B2E")
            textSize = 22f
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
            textSize = 13f
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
            textSize = 10f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        val bigValuePaint = Paint().apply {
            color = Color.parseColor("#0B1B2E")
            textSize = 14f
            typeface = Typeface.MONOSPACE
            isFakeBoldText = true
            isAntiAlias = true
        }
        val bodyPaint = Paint().apply {
            color = Color.parseColor("#0B1B2E")
            textSize = 9f
            isAntiAlias = true
        }
        val descriptionPaint = Paint().apply {
            color = Color.parseColor("#475569")
            textSize = 9f
            isAntiAlias = true
        }
        val rangePaint = Paint().apply {
            color = Color.parseColor("#94A3B8")
            textSize = 8f
            isAntiAlias = true
        }
        val metricNamePaint = Paint().apply {
            color = Color.parseColor("#0B1B2E")
            textSize = 10f
            typeface = Typeface.MONOSPACE
            isFakeBoldText = true
            isAntiAlias = true
        }
        val footerPaint = Paint().apply {
            color = Color.parseColor("#94A3B8")
            textSize = 8f
            isAntiAlias = true
        }

        // ─── Page 1 ──────────────────────────────────────────────────────
        val pageInfo1 = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page1 = doc.startPage(pageInfo1)
        val c1 = page1.canvas
        var y = MARGIN + 20f

        // Header
        c1.drawText("NeuroCardia — ECG Analysis Report", MARGIN, y, titlePaint)
        y += 20f
        c1.drawText("Source: ${bundle.sourceName}", MARGIN, y, subtitlePaint)
        y += 12f
        c1.drawText("Generated: ${LocalDateTime.now()}", MARGIN, y, subtitlePaint)
        y += 12f
        c1.drawText("App version: $appVersion", MARGIN, y, subtitlePaint)
        y += 24f

        // Recording
        c1.drawText("RECORDING", MARGIN, y, sectionPaint)
        y += 16f
        val rec: EcgRecord = bundle.record
        val durationS = rec.samples.size / rec.sampleRate
        drawKeyValue(c1, "Sample rate", "%.1f Hz".format(rec.sampleRate), MARGIN, y, labelPaint, valuePaint)
        y += 13f
        drawKeyValue(c1, "Duration", "%.1f s".format(durationS), MARGIN, y, labelPaint, valuePaint)
        y += 13f
        drawKeyValue(c1, "Beats detected", "${rec.annotations.size}", MARGIN, y, labelPaint, valuePaint)
        y += 13f
        drawKeyValue(c1, "Lead", rec.leadLabel ?: "—", MARGIN, y, labelPaint, valuePaint)
        y += 13f
        drawKeyValue(c1, "Signal units", rec.units.toString(), MARGIN, y, labelPaint, valuePaint)
        y += 24f

        // Headline metrics
        c1.drawText("HEADLINE METRICS", MARGIN, y, sectionPaint)
        y += 20f
        val hr = bundle.heartRateBpm
        val csi = bundle.csiR
        val residual = bundle.metrics[MetricKeys.CSI_RESIDUAL]
        val verdict = bundle.quality.verdict
        val qualityLabel = when (verdict) {
            SignalQuality.Verdict.GOOD -> "Good"
            SignalQuality.Verdict.FAIR -> "Fair"
            SignalQuality.Verdict.POOR -> "Poor"
        }

        drawBigValue(c1, "Heart rate",
            hr?.let { "%.0f BPM".format(it) } ?: "—", MARGIN, y, labelPaint, bigValuePaint)
        y += 20f
        drawBigValue(c1, "CSI r",
            csi?.let { "%.3f".format(it) } ?: "—", MARGIN, y, labelPaint, bigValuePaint)
        y += 20f
        drawBigValue(c1, "Residual",
            residual?.let { "%+.3f".format(it) } ?: "—", MARGIN, y, labelPaint, bigValuePaint)
        y += 20f
        drawBigValue(c1, "Signal quality",
            "$qualityLabel (%.2f)".format(bundle.quality.score), MARGIN, y, labelPaint, bigValuePaint)
        y += 28f

        // Signal summary
        c1.drawText("SIGNAL SUMMARY", MARGIN, y, sectionPaint)
        y += 16f
        val csiVar = bundle.metrics[MetricKeys.CSI_VAR]
        val csiSkew = bundle.metrics[MetricKeys.CSI_SKEW]
        val csiKurt = bundle.metrics[MetricKeys.CSI_KURT]
        val csiLag1 = bundle.metrics[MetricKeys.CSI_LAG1AC]
        val rrCv = bundle.metrics[MetricKeys.RR_CV]

        drawKeyValue(c1, "CSI variance", csiVar?.let { "%.4f".format(it) } ?: "—",
            MARGIN, y, labelPaint, valuePaint); y += 13f
        drawKeyValue(c1, "CSI skewness", csiSkew?.let { "%.4f".format(it) } ?: "—",
            MARGIN, y, labelPaint, valuePaint); y += 13f
        drawKeyValue(c1, "CSI kurtosis", csiKurt?.let { "%.4f".format(it) } ?: "—",
            MARGIN, y, labelPaint, valuePaint); y += 13f
        drawKeyValue(c1, "CSI lag-1 autocorrelation", csiLag1?.let { "%.4f".format(it) } ?: "—",
            MARGIN, y, labelPaint, valuePaint); y += 13f
        drawKeyValue(c1, "RR coefficient of variation", rrCv?.let { "%.4f".format(it) } ?: "—",
            MARGIN, y, labelPaint, valuePaint); y += 24f

        // Method
        c1.drawText("METHOD", MARGIN, y, sectionPaint)
        y += 16f

        val methodText = """
            The Cardiac Synchronization Index (CSI) is computed by a
            Kuramoto coupled-oscillator network applied to the RR interval
            series. The network has N = 16 oscillators whose natural
            frequencies are drawn from the non-trivial Riemann zeros and
            normalized to unit variance. At each detected R-peak the phase
            of a dedicated heart oscillator is reset to the cumulative
            phase of the RR series. Between beats the network integrates
            the Kuramoto update rule

                dtheta_i/dt = omega_i + (K/N) * sum_j sin(theta_j - theta_i)

            with coupling strength K = 2 and integration step 50 ms. The
            order parameter

                r(t) = | (1/N) * sum_j e^{i theta_j} |

            is sampled along the trajectory. The CSI value reported here
            is the mean of r(t) over the recording.

            The residual is defined as the component of the CSI not
            explained by a linear relationship with the coefficient of
            variation of the RR series (rr_cv):

                residual = csi_mean - (alpha + beta * rr_cv)

            with alpha = 0.6730 and beta = -0.0184, the coefficients fit
            on a pilot corpus of nine MIT-BIH Normal Sinus Rhythm and nine
            MIT-BIH Atrial Fibrillation recordings. At K = 2, the residual
            separates normal sinus rhythm from atrial fibrillation with an
            AUC of 0.89 on the pilot corpus. The residual is a research
            feature, not cleared for clinical use.

            The choice of K = 2 is empirical. At higher coupling strengths
            the network saturates and the CSI becomes a monotone function
            of RR variability. At lower coupling strengths the residual
            inverts in sign. The useful regime is narrow. Full
            documentation is in the companion white paper and residual
            paper.
        """.trimIndent()

        val methodLines = wrapText(methodText, descriptionPaint, PAGE_WIDTH - 2 * MARGIN)
        for (line in methodLines) {
            if (y > PAGE_HEIGHT - MARGIN - 40f) break
            c1.drawText(line, MARGIN, y, descriptionPaint)
            y += 10f
        }

        y += 8f
        c1.drawText(
            "The Kuramoto residual (csi_residual) is a cohort-level quantity " +
            "and is not computed for single-file analysis.",
            MARGIN, y, rangePaint
        )

        c1.drawText(
            "NeuroCardia v$appVersion — GPL-3.0-or-later — all processing on-device",
            MARGIN, PAGE_HEIGHT - MARGIN + 5f, footerPaint
        )
        doc.finishPage(page1)

        // ─── Page 2+: metric table ───────────────────────────────────────
        val orderedKeys = MetricDescriptions.orderedKeys()
            .filter { bundle.metrics.containsKey(it) }
        val unknownKeys = bundle.metrics.keys
            .filter { it !in orderedKeys }
            .sorted()
        val allKeys = orderedKeys + unknownKeys

        var pageIndex = 2
        var page = startPage(doc, pageIndex)
        var canvas = page.canvas
        y = MARGIN + 20f
        canvas.drawText("ALL METRICS", MARGIN, y, sectionPaint)
        y += 20f

        for (key in allKeys) {
            val value = bundle.metrics[key] ?: continue
            val formatted = if (value.isNaN()) "—" else "%.6f".format(value)
            val desc = MetricDescriptions.of(key)

            // Estimate height needed for this metric block.
            val descLines = wrapText(desc.short, descriptionPaint, PAGE_WIDTH - 2 * MARGIN)
            val blockHeight = 12f + descLines.size * 10f + (if (desc.range.isNotEmpty()) 10f else 0f) + 6f

            if (y + blockHeight > PAGE_HEIGHT - MARGIN - 20f) {
                doc.finishPage(page)
                pageIndex++
                page = startPage(doc, pageIndex)
                canvas = page.canvas
                y = MARGIN + 20f
                canvas.drawText("ALL METRICS (continued)", MARGIN, y, sectionPaint)
                y += 20f
            }

            canvas.drawText("$key = $formatted", MARGIN, y, metricNamePaint)
            y += 12f
            for (line in descLines) {
                canvas.drawText(line, MARGIN + 8f, y, descriptionPaint)
                y += 10f
            }
            if (desc.range.isNotEmpty()) {
                canvas.drawText("Typical range: ${desc.range}", MARGIN + 8f, y, rangePaint)
                y += 10f
            }
            y += 6f
        }

        canvas.drawText(
            "End of metrics.",
            MARGIN, PAGE_HEIGHT - MARGIN + 5f, footerPaint
        )
        doc.finishPage(page)

        // ─── Disclaimer page ─────────────────────────────────────────────
        pageIndex++
        val disclaimerPage = startPage(doc, pageIndex)
        val cD = disclaimerPage.canvas
        y = MARGIN + 20f

        cD.drawText("DISCLAIMER AND INTERPRETATION", MARGIN, y, sectionPaint)
        y += 22f

        val disclaimer = """
            This report is generated by NeuroCardia, a decision-support
            tool for trained clinicians and researchers. It does not
            replace clinical judgment, does not provide a diagnosis, and
            does not guide treatment.

            The metrics above are descriptive. They characterize the
            recording; they do not explain it. A low RMSSD does not mean
            the subject is ill. A high RMSSD does not mean the subject is
            healthy. Interpretation requires context that this report
            does not carry: the subject's history, medications, activity
            state, and the clinical question being asked.

            The Cardiac Synchronization Index (CSI), the Kuramoto
            residual, and the associated early warning signals are
            research features. They are not cleared for clinical use.
            They should not be used to make clinical decisions. They are
            included in this report for research and reproducibility
            purposes only.

            All processing was performed on-device. No data left the
            device. No network connection was used. The values in this
            report are reproducible: running the same recording through
            the same app version on any compatible device produces
            byte-identical results.
        """.trimIndent()

        val disclaimerLines = wrapText(disclaimer, bodyPaint, PAGE_WIDTH - 2 * MARGIN)
        for (line in disclaimerLines) {
            if (y > PAGE_HEIGHT - MARGIN - 10f) break
            cD.drawText(line, MARGIN, y, bodyPaint)
            y += 12f
        }

        doc.finishPage(disclaimerPage)

        doc.writeTo(output)
        doc.close()
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun startPage(doc: PdfDocument, index: Int): PdfDocument.Page {
        val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index).create()
        return doc.startPage(info)
    }

    private fun drawKeyValue(
        canvas: Canvas,
        label: String,
        value: String,
        x: Float,
        y: Float,
        labelPaint: Paint,
        valuePaint: Paint
    ) {
        canvas.drawText(label, x, y, labelPaint)
        canvas.drawText(value, x + 160f, y, valuePaint)
    }

    private fun drawBigValue(
        canvas: Canvas,
        label: String,
        value: String,
        x: Float,
        y: Float,
        labelPaint: Paint,
        valuePaint: Paint
    ) {
        canvas.drawText(label, x, y, labelPaint)
        canvas.drawText(value, x + 160f, y, valuePaint)
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        val paragraphs = text.split("\n")
        for (para in paragraphs) {
            if (para.isEmpty()) {
                result.add("")
                continue
            }
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
