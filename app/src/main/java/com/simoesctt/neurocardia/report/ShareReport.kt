package com.simoesctt.neurocardia.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.simoesctt.neurocardia.BuildConfig
import com.simoesctt.neurocardia.ui.viewmodel.AnalysisResultBundle
import java.io.File

object ShareReport {

    fun share(context: Context, bundle: AnalysisResultBundle) {
        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val baseName = bundle.sourceName
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(60)
        val pdfFile = File(reportsDir, "$baseName-report.pdf")

        pdfFile.outputStream().use { out ->
            PdfWriter.write(bundle, out, BuildConfig.VERSION_NAME)
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "NeuroCardia report — ${bundle.sourceName}"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(intent, "Share NeuroCardia report")
        )
    }
}
