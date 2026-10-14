package com.simoesctt.neurocardia.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.simoesctt.neurocardia.io.CsvWriter
import com.simoesctt.neurocardia.ui.viewmodel.AnalysisResultBundle
import java.io.File

object ShareCsv {

    fun shareRrIntervals(context: Context, bundle: AnalysisResultBundle) {
        val file = writeCsv(context, bundle, "rr") { out ->
            CsvWriter.writeRrIntervals(bundle.record, out)
        }
        share(context, file, "text/csv",
            "NeuroCardia RR intervals — ${bundle.sourceName}")
    }

    fun shareMetrics(context: Context, bundle: AnalysisResultBundle) {
        val file = writeCsv(context, bundle, "metrics") { out ->
            CsvWriter.writeMetrics(bundle.record, out)
        }
        share(context, file, "text/csv",
            "NeuroCardia metrics — ${bundle.sourceName}")
    }

    private fun writeCsv(
        context: Context,
        bundle: AnalysisResultBundle,
        suffix: String,
        writer: (java.io.OutputStream) -> Unit
    ): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val baseName = bundle.sourceName
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(60)
        val csv = File(dir, "$baseName-$suffix.csv")
        csv.outputStream().use { out -> writer(out) }
        return csv
    }

    private fun share(context: Context, file: File, mime: String, subject: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "com.simoesctt.neurocardia.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, subject))
    }
}
