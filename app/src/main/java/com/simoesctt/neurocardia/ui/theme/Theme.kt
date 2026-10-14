package com.simoesctt.neurocardia.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NeuroCardiaScheme = darkColorScheme(
    background   = NeuroCardiaColors.NavyDeep,
    surface      = NeuroCardiaColors.NavySurface,
    primary      = NeuroCardiaColors.CyanGlow,
    onBackground = NeuroCardiaColors.SignalWhite,
    onSurface    = NeuroCardiaColors.SignalWhite,
    secondary    = NeuroCardiaColors.SignalMuted,
)

@Composable
fun NeuroCardiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NeuroCardiaScheme,
        typography = NeuroCardiaTypography,
        content = content
    )
}
