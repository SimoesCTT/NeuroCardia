package com.simoesctt.neurocardia.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.simoesctt.neurocardia.batch.BatchReport
import com.simoesctt.neurocardia.batch.BatchStudy
import java.io.File

object BatchShareReport {

    fun shareCsv(context: Context, study: BatchStudy) {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val timestamp = System.currentTimeMillis()

        val perRecord = File(dir, "cohort-per-record-$timestamp.csv")
        val cohort = File(dir, "cohort-summary-$timestamp.csv")
        val fit = File(dir, "cohort-residual-fit-$timestamp.csv")

        BatchReport.writePerRecordCsv(study, perRecord)
        BatchReport.writeCohortCsv(study, cohort)
        BatchReport.writeResidualFitCsv(study, fit)

        shareFiles(context, listOf(perRecord, cohort, fit), "text/csv")
    }

    fun sharePdf(context: Context, study: BatchStudy) {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val pdf = File(dir, "batch-summary-${System.currentTimeMillis()}.pdf")

        pdf.outputStream().use { out ->
            BatchPdfWriter.write(study, out)
        }

        shareFiles(context, listOf(pdf), "application/pdf")
    }

    private fun shareFiles(context: Context, files: List<File>, mimeType: String) {
        val uris = files.map { file ->
            FileProvider.getUriForFile(
                context,
                "com.simoesctt.neurocardia.fileprovider",
                file
            )
        }

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        context.startActivity(
            Intent.createChooser(intent, "Share batch report")
        )
    }
}
