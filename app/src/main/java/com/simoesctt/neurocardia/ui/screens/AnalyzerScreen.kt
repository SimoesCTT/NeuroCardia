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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaColors
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaTypography
import com.simoesctt.neurocardia.ui.viewmodel.AnalysisResultBundle

@Composable
fun AnalyzerScreen(
    state: AnalyzerScreenState,
    onFilePicked: (Uri) -> Unit,
    onFolderPicked: (Uri) -> Unit,
    onClear: () -> Unit,
    onShare: (() -> Unit)? = null,
    onShareCsv: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Window length for the waveform display, in seconds. 0 = whole
    // recording. Default is 10 seconds, which is the standard clinical
    // sweep width and shows clear QRS morphology.
    var windowSeconds by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(10)
    }

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
            HeaderRow(
                sourceName = (state as? AnalyzerScreenState.Ready)?.result?.sourceName,
                qualityVerdict = (state as? AnalyzerScreenState.Ready)?.result?.quality?.verdict,
                onFilePicked = onFilePicked,
                onFolderPicked = onFolderPicked,
                onShare = onShare,
                onShareCsv = onShareCsv
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(NeuroCardiaColors.NavySurface),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    is AnalyzerScreenState.Idle -> IdlePanel(onFilePicked, onFolderPicked)
                    is AnalyzerScreenState.Loading -> LoadingPanel(state.sourceName)
                    is AnalyzerScreenState.Ready -> {
                        val sampleRate = state.result.record.sampleRate
                        val windowSamples = if (windowSeconds == 0) {
                            null
                        } else {
                            (sampleRate * windowSeconds).toInt()
                        }
                        WaveformView(
                            samples = state.result.record.samples,
                            rPeakSamples = state.result.rPeakSamples,
                            windowSamples = windowSamples
                        )
                    }
                    is AnalyzerScreenState.Error -> ErrorPanel(
                        state.message, onFilePicked, onFolderPicked
                    )
                }
            }

            // Window selector. Shown only when a recording is loaded.
            // Four fixed widths: 10s, 30s, 1m, and Full. The 10s view
            // is the clinical standard for readable QRS morphology.
            if (state is AnalyzerScreenState.Ready) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Window:",
                        style = NeuroCardiaTypography.labelSmall,
                        color = NeuroCardiaColors.SignalMuted
                    )
                    WindowChip("10s", windowSeconds == 10) { windowSeconds = 10 }
                    WindowChip("30s", windowSeconds == 30) { windowSeconds = 30 }
                    WindowChip("1m", windowSeconds == 60) { windowSeconds = 60 }
                    WindowChip("Full", windowSeconds == 0) { windowSeconds = 0 }
                }
            }

            ResponsiveMetricStrip(state)
        }
    }
}

@Composable
private fun HeaderRow(
    sourceName: String?,
    qualityVerdict: com.simoesctt.neurocardia.dsp.SignalQuality.Verdict?,
    onFilePicked: (Uri) -> Unit,
    onFolderPicked: (Uri) -> Unit,
    onShare: (() -> Unit)?,
    onShareCsv: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Text(
                text = sourceName ?: "NeuroCardia",
                style = NeuroCardiaTypography.bodyMedium,
                color = NeuroCardiaColors.SignalMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (qualityVerdict != null) {
                QualityBadge(qualityVerdict)
            }
        }
        if (sourceName != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onShare != null) {
                    Text(
                        text = "PDF",
                        style = NeuroCardiaTypography.titleLarge,
                        color = NeuroCardiaColors.CyanGlow,
                        modifier = Modifier
                            .clickable { onShare() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                if (onShareCsv != null) {
                    Text(
                        text = "CSV",
                        style = NeuroCardiaTypography.titleLarge,
                        color = NeuroCardiaColors.CyanGlow,
                        modifier = Modifier
                            .clickable { onShareCsv() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                FilePickerTextButton(
                    label = "New CSV",
                    onFilePicked = onFilePicked
                )
                FolderPickerTextButton(
                    label = "New WFDB",
                    onFolderPicked = onFolderPicked
                )
            }
        }
    }
}

@Composable
private fun QualityBadge(verdict: com.simoesctt.neurocardia.dsp.SignalQuality.Verdict) {
    val (label, color) = when (verdict) {
        com.simoesctt.neurocardia.dsp.SignalQuality.Verdict.GOOD ->
            "Good signal" to NeuroCardiaColors.FlagOk
        com.simoesctt.neurocardia.dsp.SignalQuality.Verdict.FAIR ->
            "Fair signal" to NeuroCardiaColors.FlagWarning
        com.simoesctt.neurocardia.dsp.SignalQuality.Verdict.POOR ->
            "Poor signal" to NeuroCardiaColors.FlagCritical
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = NeuroCardiaTypography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun ResponsiveMetricStrip(state: AnalyzerScreenState) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val wideEnoughForRow = maxWidth >= 600.dp

        val labels = listOf("HEART RATE", "CSI r", "RESIDUAL")
        val values: List<String>
        val units: List<String>

        when (state) {
            is AnalyzerScreenState.Ready -> {
                val r = state.result
                values = listOf(
                    r.heartRateBpm?.let { "%.0f".format(it) } ?: "—",
                    r.csiR?.let { "%.3f".format(it) } ?: "—",
                    r.metrics["csi_residual"]?.let { "%+.3f".format(it) } ?: "—"
                )
                units = listOf("BPM", "", "")
            }
            else -> {
                values = listOf("—", "—", "—")
                units = listOf("BPM", "", "")
            }
        }

        if (wideEnoughForRow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (i in labels.indices) {
                    MetricCard(
                        label = labels[i],
                        value = values[i],
                        unit = units[i],
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (i in labels.indices) {
                    MetricCard(
                        label = labels[i],
                        value = values[i],
                        unit = units[i],
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun IdlePanel(
    onFilePicked: (Uri) -> Unit,
    onFolderPicked: (Uri) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Text(
            text = "No recording loaded",
            style = NeuroCardiaTypography.titleLarge,
            color = NeuroCardiaColors.SignalMuted
        )
        FilePickerButton(
            label = "Open CSV file",
            onFilePicked = onFilePicked
        )
        FolderPickerButton(
            label = "Open WFDB folder",
            onFolderPicked = onFolderPicked
        )
        Text(
            text = "CSV: single column or time + value\n" +
                   "WFDB: folder containing .hea, .dat, .atr",
            style = NeuroCardiaTypography.labelSmall,
            color = NeuroCardiaColors.SignalMuted.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LoadingPanel(sourceName: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Analyzing…",
            style = NeuroCardiaTypography.titleLarge,
            color = NeuroCardiaColors.CyanGlow
        )
        Text(
            text = sourceName,
            style = NeuroCardiaTypography.bodyMedium,
            color = NeuroCardiaColors.SignalMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ErrorPanel(
    message: String,
    onFilePicked: (Uri) -> Unit,
    onFolderPicked: (Uri) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(24.dp)
    ) {
        Text(
            text = "Could not load file",
            style = NeuroCardiaTypography.titleLarge,
            color = NeuroCardiaColors.FlagCritical
        )
        Text(
            text = message,
            style = NeuroCardiaTypography.bodyMedium,
            color = NeuroCardiaColors.SignalMuted,
            textAlign = TextAlign.Center
        )
        FilePickerButton(
            label = "Try a CSV file",
            onFilePicked = onFilePicked
        )
        FolderPickerButton(
            label = "Try a WFDB folder",
            onFolderPicked = onFolderPicked
        )
    }

}

@Composable
private fun WindowChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) NeuroCardiaColors.CyanGlow.copy(alpha = 0.18f)
                else NeuroCardiaColors.NavySurface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = NeuroCardiaTypography.bodyMedium,
            color = if (selected) NeuroCardiaColors.CyanGlow
                    else NeuroCardiaColors.SignalMuted
        )
    }
}
