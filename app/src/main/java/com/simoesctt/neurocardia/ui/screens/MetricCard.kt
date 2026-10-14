package com.simoesctt.neurocardia.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaColors
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaTypography

/**
 * A dark card showing a labeled numeric value with an optional unit.
 *
 * The label sits at the top in small uppercase muted text. The value
 * and unit are centered horizontally below. This layout works in both
 * portrait and landscape: the card fills whatever width it is given,
 * and the contents stay visually anchored at the center rather than
 * collapsing to the left edge when the card is wide.
 */
@Composable
fun MetricCard(
    label: String,
    value: String,
    unit: String = "",
    modifier: Modifier = Modifier,
    valueColor: Color = NeuroCardiaColors.SignalWhite,
    unitColor: Color = NeuroCardiaColors.CyanGlow
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(NeuroCardiaColors.NavySurface)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = NeuroCardiaTypography.labelSmall,
            color = NeuroCardiaColors.SignalMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = value,
                style = NeuroCardiaTypography.displayLarge,
                color = valueColor,
                maxLines = 1
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = unit,
                    style = NeuroCardiaTypography.titleLarge,
                    color = unitColor,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
        }
    }
}
