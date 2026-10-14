package com.simoesctt.neurocardia.ui.screens

import com.simoesctt.neurocardia.ui.viewmodel.AnalysisResultBundle

/**
 * Screen-facing state. Projection of the ViewModel's AnalyzerState into
 * something the UI can render without knowing about ViewModel internals.
 */
sealed class AnalyzerScreenState {
    object Idle : AnalyzerScreenState()
    data class Loading(val sourceName: String) : AnalyzerScreenState()
    data class Ready(val result: AnalysisResultBundle) : AnalyzerScreenState()
    data class Error(val message: String) : AnalyzerScreenState()
}
