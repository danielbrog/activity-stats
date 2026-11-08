package com.danielbrog.activitystats.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.Format
import com.danielbrog.activitystats.stats.SpeedDisplay
import com.danielbrog.activitystats.stats.Split
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

/**
 * One row per km or mile: moving time, average speed (or pace), height change, and a bar
 * comparing its speed with the fastest split, which is in bold.
 */
@Composable
fun SplitsTable(
    splits: List<Split>,
    unit: DistanceUnit,
    display: SpeedDisplay,
    modifier: Modifier = Modifier,
) {
    val fastest = splits.maxOfOrNull { it.avgSpeedMps } ?: 0.0
    val fastestIndex = splits.indexOfFirst { it.avgSpeedMps == fastest }
    val hasHeight = splits.any { it.elevationChangeMeters != null }
    val numbers = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")

    Column(modifier = modifier.fillMaxWidth()) {
        SplitRow(
            first = if (unit == DistanceUnit.Km) "Km" else "Mi",
            time = "Time",
            speed = if (display == SpeedDisplay.Speed) "Speed" else "Pace",
            height = if (hasHeight) "Elev" else null,
            barFraction = null,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        splits.forEachIndexed { i, split ->
            SplitRow(
                first = if (split.isPartial) Format.distanceNumber(split.distanceMeters, unit) else split.number.toString(),
                time = Format.clock(split.movingMs),
                speed = Format.speedOrPace(split.avgSpeedMps, unit, display),
                height = if (hasHeight) split.elevationChangeMeters?.let { Format.elevationChange(it, unit) } ?: "–" else null,
                barFraction = if (fastest > 0.0) (split.avgSpeedMps / fastest).toFloat() else 0f,
                style = if (i == fastestIndex) numbers.copy(fontWeight = FontWeight.Bold) else numbers,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SplitRow(
    first: String,
    time: String,
    speed: String,
    height: String?,
    barFraction: Float?,
    style: TextStyle,
    color: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(first, style = style, color = color, modifier = Modifier.width(48.dp))
        Text(time, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(speed, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
        if (height != null) {
            Text(height, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        Box(modifier = Modifier.padding(start = 12.dp).width(56.dp).height(10.dp)) {
            if (barFraction != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(barFraction.coerceIn(0.02f, 1f))
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SplitsTablePreview() {
    ActivityStatsTheme {
        SplitsTable(
            splits = listOf(
                Split(1, 1_000.0, 125_000, 12.0, isPartial = false),
                Split(2, 1_000.0, 112_000, -4.0, isPartial = false),
                Split(3, 1_000.0, 131_000, 18.0, isPartial = false),
                Split(4, 430.0, 51_000, 1.0, isPartial = true),
            ),
            unit = DistanceUnit.Km,
            display = SpeedDisplay.Speed,
        )
    }
}
