package com.simoesctt.neurocardia.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.simoesctt.neurocardia.batch.CohortStats
import com.simoesctt.neurocardia.batch.StudyRow
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaColors
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaTypography

@Composable
fun CohortTable(stats: Map<String, CohortStats>, modifier: Modifier = Modifier) {
    if (stats.isEmpty()) {
        Text(
            text = "No metrics available.",
            style = NeuroCardiaTypography.bodyMedium,
            color = NeuroCardiaColors.SignalMuted,
            modifier = modifier.padding(16.dp)
        )
        return
    }

    val rows = stats.entries.sortedBy { it.key }
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NeuroCardiaColors.NavySurface)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .background(NeuroCardiaColors.NavyElevated)
                .horizontalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HeaderCell("metric", 160.dp)
            HeaderCell("n", 40.dp)
            HeaderCell("mean", 80.dp)
            HeaderCell("median", 80.dp)
            HeaderCell("sd", 80.dp)
            HeaderCell("min", 80.dp)
            HeaderCell("max", 80.dp)
            HeaderCell("p25", 80.dp)
            HeaderCell("p75", 80.dp)
        }
        rows.forEachIndexed { idx, (metric, s) ->
            val bg = if (idx % 2 == 0) NeuroCardiaColors.NavySurface
                     else NeuroCardiaColors.NavyElevated
            Row(
                modifier = Modifier
                    .background(bg)
                    .horizontalScroll(scroll)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DataCell(metric, 160.dp)
                DataCell(s.n.toString(), 40.dp)
                DataCell(fmt(s.mean), 80.dp)
                DataCell(fmt(s.median), 80.dp)
                DataCell(fmt(s.sd), 80.dp)
                DataCell(fmt(s.min), 80.dp)
                DataCell(fmt(s.max), 80.dp)
                DataCell(fmt(s.p25), 80.dp)
                DataCell(fmt(s.p75), 80.dp)
            }
        }
    }
}

@Composable
fun PerRecordTable(rows: List<StudyRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) {
        Text(
            text = "No records.",
            style = NeuroCardiaTypography.bodyMedium,
            color = NeuroCardiaColors.SignalMuted,
            modifier = modifier.padding(16.dp)
        )
        return
    }

    val metricKeys = rows.flatMap { it.metrics.keys }.toSortedSet().toList()
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NeuroCardiaColors.NavySurface)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .background(NeuroCardiaColors.NavyElevated)
                .horizontalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HeaderCell("source", 200.dp)
            HeaderCell("dur (s)", 70.dp)
            metricKeys.forEach { HeaderCell(it, 100.dp) }
        }
        rows.forEachIndexed { idx, row ->
            val bg = if (idx % 2 == 0) NeuroCardiaColors.NavySurface
                     else NeuroCardiaColors.NavyElevated
            Row(
                modifier = Modifier
                    .background(bg)
                    .horizontalScroll(scroll)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DataCell(row.source, 200.dp)
                DataCell("%.1f".format(row.durationS), 70.dp)
                metricKeys.forEach { key ->
                    val v = row.metrics[key]
                    DataCell(if (v == null || v.isNaN()) "—" else "%.3f".format(v), 100.dp)
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        style = NeuroCardiaTypography.labelSmall,
        color = NeuroCardiaColors.CyanGlow,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.width(width),
        maxLines = 1
    )
}

@Composable
private fun DataCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        style = NeuroCardiaTypography.bodyMedium,
        color = NeuroCardiaColors.SignalWhite,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.width(width),
        maxLines = 1
    )
}

private fun fmt(v: Double): String =
    if (v.isNaN()) "—" else "%.3f".format(v)
