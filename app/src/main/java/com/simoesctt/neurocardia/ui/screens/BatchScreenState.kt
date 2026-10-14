package com.simoesctt.neurocardia.ui.screens

import com.simoesctt.neurocardia.batch.BatchProgress
import com.simoesctt.neurocardia.batch.BatchStudy
import com.simoesctt.neurocardia.batch.InputFile

/**
 * Screen-facing state for the batch tab. Distinct from the Analyzer
 * state because a batch has its own lifecycle: pick inputs, run,
 * show results.
 */
sealed class BatchScreenState {
    object Idle : BatchScreenState()
    data class Selected(
        val inputs: List<InputFile>,
        val sourceDescription: String
    ) : BatchScreenState()
    data class Running(
        val inputs: List<InputFile>,
        val progress: BatchProgress?
    ) : BatchScreenState()
    data class Done(
        val study: BatchStudy,
        val inputs: List<InputFile>
    ) : BatchScreenState()
    data class Error(val message: String) : BatchScreenState()
}
