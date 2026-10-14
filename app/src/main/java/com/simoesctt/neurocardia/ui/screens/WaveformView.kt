package com.simoesctt.neurocardia.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaColors
import kotlin.math.abs

@Composable
fun WaveformView(
    samples: DoubleArray,
    rPeakSamples: IntArray,
    windowSamples: Int? = null,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        if (samples.isEmpty() || w <= 0f || h <= 0f) return@Canvas

        // When a window is set and smaller than the recording, draw only
        // the first windowSamples samples. This gives the user a view of
        // a fixed time slice where QRS morphology is visible.
        val viewSamples: DoubleArray = if (windowSamples != null &&
                                           windowSamples > 0 &&
                                           windowSamples < samples.size) {
            samples.copyOfRange(0, windowSamples)
        } else {
            samples
        }

        val gridColor = NeuroCardiaColors.CyanDim.copy(alpha = 0.15f)
        val gridStroke = 0.5.dp.toPx()
        drawLine(gridColor, Offset(0f, h * 0.25f), Offset(w, h * 0.25f), gridStroke)
        drawLine(gridColor, Offset(0f, h * 0.75f), Offset(w, h * 0.75f), gridStroke)

        // Y-scale from the 99th percentile of absolute amplitude. This
        // captures the QRS peak while ignoring a small fraction of
        // artifacts. A QRS at the 99th percentile fits the panel; a
        // QRS at the 100th percentile clips at the edges.
        val minVal = samples.minOrNull() ?: 0.0
        val maxVal = samples.maxOrNull() ?: 0.0
        val meanVal = samples.average()
        val absMax = kotlin.math.max(kotlin.math.abs(minVal), kotlin.math.abs(maxVal))
        val p99 = percentileAbs(samples, 0.99)
        val p999 = percentileAbs(viewSamples, 0.999)
        val p9999 = percentileAbs(samples, 0.9999)
        println("WV: n=${samples.size} min=$minVal max=$maxVal mean=$meanVal absMax=$absMax " +
                "p99=$p99 p999=$p999 p9999=$p9999")

        val scaleAmplitude = percentileAbs(viewSamples, 0.999).coerceAtLeast(1e-9)
        val usableHalfHeight = h * 0.45f
        val scale = (usableHalfHeight / scaleAmplitude).toFloat()
        val centerY = h / 2f
        println("WV: scaleAmplitude=$scaleAmplitude scale=$scale centerY=$centerY h=$h")

        drawLine(
            NeuroCardiaColors.SignalMuted.copy(alpha = 0.3f),
            Offset(0f, centerY),
            Offset(w, centerY),
            gridStroke
        )

        val columnCount = w.toInt().coerceAtLeast(1)
        val samplesPerColumn = viewSamples.size.toFloat() / columnCount

        if (samplesPerColumn >= 1f) {
            // Envelope mode: one vertical segment per pixel column.
            val strokeWidth = 1.dp.toPx()
            for (col in 0 until columnCount) {
                val startIdx = (col * samplesPerColumn).toInt()
                val endIdx = ((col + 1) * samplesPerColumn).toInt()
                    .coerceAtMost(samples.size)
                if (startIdx >= endIdx) continue

                var minVal = viewSamples[startIdx]
                var maxVal = viewSamples[startIdx]
                for (i in startIdx until endIdx) {
                    val v = viewSamples[i]
                    if (v < minVal) minVal = v
                    if (v > maxVal) maxVal = v
                }

                val x = col.toFloat()
                val yTop = (centerY - (maxVal * scale).toFloat())
                    .coerceIn(0f, h)
                val yBot = (centerY - (minVal * scale).toFloat())
                    .coerceIn(0f, h)

                drawLine(
                    color = NeuroCardiaColors.SignalWhite,
                    start = Offset(x, yTop),
                    end = Offset(x, yBot),
                    strokeWidth = strokeWidth
                )
            }
        } else {
            // Polyline mode: samples sparser than pixels.
            val path = androidx.compose.ui.graphics.Path()
            val stepX = w / (viewSamples.size - 1).coerceAtLeast(1)
            for (i in viewSamples.indices) {
                val x = i * stepX
                val y = (centerY - (viewSamples[i] * scale).toFloat())
                    .coerceIn(0f, h)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = NeuroCardiaColors.SignalWhite,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        val markerRadius = 2.5.dp.toPx()
        for (sampleIdx in rPeakSamples) {
            if (sampleIdx < 0 || sampleIdx >= viewSamples.size) continue
            val col = (sampleIdx / samplesPerColumn).toInt()
                .coerceIn(0, columnCount - 1)
            val x = col.toFloat()
            val y = (centerY - (viewSamples[sampleIdx] * scale).toFloat())
                .coerceIn(0f, h)

            drawLine(
                color = NeuroCardiaColors.CyanGlow.copy(alpha = 0.5f),
                start = Offset(x, centerY),
                end = Offset(x, y),
                strokeWidth = 1.dp.toPx()
            )
            drawCircle(
                color = NeuroCardiaColors.CyanGlow,
                radius = markerRadius,
                center = Offset(x, y)
            )
        }
    }
}

/**
 * Value at the given quantile of |samples|. Linear interpolation
 * between adjacent ranks. For very long signals, samples a subset
 * to keep the computation fast.
 */
private fun percentileAbs(samples: DoubleArray, q: Double): Double {
    val maxSamples = 100_000
    val subset: DoubleArray = if (samples.size <= maxSamples) {
        samples
    } else {
        val stride = samples.size / maxSamples
        DoubleArray(maxSamples) { samples[it * stride] }
    }

    val abs = DoubleArray(subset.size) { abs(subset[it]) }
    abs.sort()
    if (abs.isEmpty()) return 0.0
    if (abs.size == 1) return abs[0]

    val pos = q * (abs.size - 1)
    val lower = pos.toInt().coerceIn(0, abs.size - 1)
    val upper = (lower + 1).coerceAtMost(abs.size - 1)
    val frac = pos - lower
    return abs[lower] * (1 - frac) + abs[upper] * frac
}
