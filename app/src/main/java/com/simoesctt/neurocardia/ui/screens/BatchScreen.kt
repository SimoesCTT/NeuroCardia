package com.simoesctt.neurocardia.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaColors
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaTypography

@Composable
fun BatchScreen(
    state: BatchScreenState,
    onFolderPicked: (Uri) -> Unit,
    onFilesPicked: (List<Uri>) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onExportCsv: (() -> Unit)? = null,
    onExportPdf: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = NeuroCardiaColors.NavyDeep
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state) {
                is BatchScreenState.Idle -> IdleBatch(
                    onFolderPicked = onFolderPicked,
                    onFilesPicked = onFilesPicked
                )
                is BatchScreenState.Selected -> SelectedBatch(
                    state = state,
                    onRun = onRun,
                    onReset = onReset,
                    onFolderPicked = onFolderPicked,
                    onFilesPicked = onFilesPicked
                )
                is BatchScreenState.Running -> RunningBatch(
                    state = state,
                    onCancel = onCancel
                )
                is BatchScreenState.Done -> DoneBatch(
                    state = state,
                    onReset = onReset,
                    onExportCsv = onExportCsv,
                    onExportPdf = onExportPdf
                )
                is BatchScreenState.Error -> ErrorBatch(
                    message = state.message,
                    onReset = onReset
                )
            }
        }
    }
}

@Composable
private fun IdleBatch(
    onFolderPicked: (Uri) -> Unit,
    onFilesPicked: (List<Uri>) -> Unit
) {
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onFolderPicked(uri) }

    val filesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) onFilesPicked(uris) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Batch analysis",
            style = NeuroCardiaTypography.titleLarge,
            color = NeuroCardiaColors.SignalMuted
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            label = "Select folder",
            onClick = { folderLauncher.launch(null) }
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            label = "Select files",
            onClick = { filesLauncher.launch(arrayOf("*/*")) }
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Load a folder or a set of CSV recordings.\n" +
                   "Each is analyzed independently and combined into a cohort study.",
            style = NeuroCardiaTypography.labelSmall,
            color = NeuroCardiaColors.SignalMuted.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SelectedBatch(
    state: BatchScreenState.Selected,
    onRun: () -> Unit,
    onReset: () -> Unit,
    onFolderPicked: (Uri) -> Unit,
    onFilesPicked: (List<Uri>) -> Unit
) {
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onFolderPicked(uri) }

    val filesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) onFilesPicked(uris) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = state.sourceDescription,
            style = NeuroCardiaTypography.titleLarge,
            color = NeuroCardiaColors.SignalWhite
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Ready to process ${state.inputs.size} files.",
            style = NeuroCardiaTypography.bodyMedium,
            color = NeuroCardiaColors.SignalMuted
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Run batch", onRun)
            SecondaryButton("Cancel", onReset)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Change folder", { folderLauncher.launch(null) })
            SecondaryButton("Change files", { filesLauncher.launch(arrayOf("*/*")) })
        }
    }
}

@Composable
private fun RunningBatch(state: BatchScreenState.Running, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Processing…",
            style = NeuroCardiaTypography.titleLarge,
            color = NeuroCardiaColors.CyanGlow
        )
        Spacer(Modifier.height(16.dp))
        val p = state.progress
        if (p != null) {
            Text(
                text = "${p.completed} / ${p.total}",
                style = NeuroCardiaTypography.displayMedium,
                color = NeuroCardiaColors.SignalWhite
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = p.currentFile ?: "",
                style = NeuroCardiaTypography.bodyMedium,
                color = NeuroCardiaColors.SignalMuted,
                maxLines = 1
            )
            Spacer(Modifier.height(8.dp))
            ProgressBar(p.fraction)
        } else {
            Text(
                text = "Starting…",
                style = NeuroCardiaTypography.bodyMedium,
                color = NeuroCardiaColors.SignalMuted
            )
        }
        Spacer(Modifier.height(24.dp))
        SecondaryButton("Cancel", onCancel)
    }
}

@Composable
private fun DoneBatch(
    state: BatchScreenState.Done,
    onReset: () -> Unit,
    onExportCsv: (() -> Unit)?,
    onExportPdf: (() -> Unit)?
) {
    var tab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TabButton("Cohort", tab == 0) { tab = 0 }
            TabButton("Per record", tab == 1) { tab = 1 }
        }
        Text(
            text = "${state.study.summary.nRecords} succeeded, " +
                   "${state.study.summary.nFailures} failed",
            style = NeuroCardiaTypography.bodyMedium,
            color = NeuroCardiaColors.SignalMuted
        )

        // Scrollable results area.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
        ) {
            when (tab) {
                0 -> CohortTable(state.study.summary.cohort)
                1 -> PerRecordTable(state.study.summary.perRecord)
            }
        }

        // Fixed footer with navigation. Wrapped so it always stays
        // visible even on small screens.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onExportCsv != null) {
                    PrimaryButton("Share CSV", onExportCsv)
                }
                if (onExportPdf != null) {
                    PrimaryButton("Share PDF", onExportPdf)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Run another batch", onReset)
            }
        }
    }
}

@Composable
private fun ErrorBatch(message: String, onReset: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Batch error",
            style = NeuroCardiaTypography.titleLarge,
            color = NeuroCardiaColors.FlagCritical
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = NeuroCardiaTypography.bodyMedium,
            color = NeuroCardiaColors.SignalMuted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        SecondaryButton("Reset", onReset)
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NeuroCardiaColors.CyanGlow.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Text(label, style = NeuroCardiaTypography.titleLarge, color = NeuroCardiaColors.CyanGlow)
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NeuroCardiaColors.NavyElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(label, style = NeuroCardiaTypography.titleLarge, color = NeuroCardiaColors.SignalWhite)
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) NeuroCardiaColors.CyanGlow.copy(alpha = 0.18f)
                else NeuroCardiaColors.NavySurface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = NeuroCardiaTypography.bodyMedium,
            color = if (selected) NeuroCardiaColors.CyanGlow
                    else NeuroCardiaColors.SignalMuted
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(NeuroCardiaColors.NavyElevated)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(NeuroCardiaColors.CyanGlow)
        )
    }
}
