package com.simoesctt.neurocardia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simoesctt.neurocardia.ui.screens.AnalyzerScreen
import com.simoesctt.neurocardia.ui.screens.AnalyzerScreenState
import com.simoesctt.neurocardia.ui.screens.BatchScreen
import com.simoesctt.neurocardia.ui.screens.BatchScreenState
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaColors
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaTheme
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaTypography
import com.simoesctt.neurocardia.ui.viewmodel.AnalyzerState
import com.simoesctt.neurocardia.ui.viewmodel.AnalyzerViewModel
import com.simoesctt.neurocardia.ui.viewmodel.BatchViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeuroCardiaTheme {
                RootScreen()
            }
        }
    }
}

@Composable
private fun RootScreen() {
    val analyzerVm: AnalyzerViewModel = viewModel()
    val batchVm: BatchViewModel = viewModel()
    var tab by remember { mutableStateOf(0) }

    Surface(color = NeuroCardiaColors.NavyDeep, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(tab, onSelect = { tab = it })
            Box(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> AnalyzerTab(analyzerVm)
                    1 -> BatchTab(batchVm)
                }
            }
        }
    }
}

@Composable
private fun TabRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .background(NeuroCardiaColors.NavyDeep)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Tab("Analyzer", selected == 0) { onSelect(0) }
        Tab("Batch", selected == 1) { onSelect(1) }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) NeuroCardiaColors.CyanGlow.copy(alpha = 0.15f)
                else NeuroCardiaColors.NavySurface
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = NeuroCardiaTypography.titleLarge,
            color = if (selected) NeuroCardiaColors.CyanGlow
                    else NeuroCardiaColors.SignalMuted
        )
    }
}

@Composable
private fun AnalyzerTab(vm: AnalyzerViewModel) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val screenState = when (val s = state) {
        is AnalyzerState.Idle -> AnalyzerScreenState.Idle
        is AnalyzerState.Loading -> AnalyzerScreenState.Loading(s.sourceName)
        is AnalyzerState.Ready -> AnalyzerScreenState.Ready(s.result)
        is AnalyzerState.Error -> AnalyzerScreenState.Error(s.message)
    }
    val bundle = (screenState as? AnalyzerScreenState.Ready)?.result
    val onShare: (() -> Unit)? = if (bundle != null) {
        { com.simoesctt.neurocardia.report.ShareReport.share(ctx, bundle) }
    } else null
    val onShareCsv: (() -> Unit)? = if (bundle != null) {
        { com.simoesctt.neurocardia.report.ShareCsv.shareRrIntervals(ctx, bundle) }
    } else null
    AnalyzerScreen(
        state = screenState,
        onFilePicked = { uri ->
            vm.load(
                context = ctx,
                uri = uri
            )
        },
        onFolderPicked = { treeUri ->
            vm.loadWfdbFolder(ctx, treeUri)
        },
        onClear = { vm.clear() },
        onShare = onShare,
        onShareCsv = onShareCsv
    )
}

@Composable
private fun BatchTab(vm: BatchViewModel) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val study = (state as? BatchScreenState.Done)?.study
    val onExportCsv: (() -> Unit)? = if (study != null) {
        { com.simoesctt.neurocardia.report.BatchShareReport.shareCsv(ctx, study) }
    } else null
    val onExportPdf: (() -> Unit)? = if (study != null) {
        { com.simoesctt.neurocardia.report.BatchShareReport.sharePdf(ctx, study) }
    } else null
    BatchScreen(
        state = state,
        onFolderPicked = { uri -> vm.selectFolder(ctx, uri) },
        onFilesPicked = { uris -> vm.selectFiles(ctx, uris) },
        onRun = { vm.run() },
        onCancel = { vm.cancel() },
        onReset = { vm.reset() },
        onExportCsv = onExportCsv,
        onExportPdf = onExportPdf
    )
}
