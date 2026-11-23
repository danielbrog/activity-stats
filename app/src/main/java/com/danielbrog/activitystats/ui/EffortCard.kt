package com.danielbrog.activitystats.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danielbrog.activitystats.segments.EffortSpeed
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.Format

/**
 * One effort on a segment, as a tappable card: [title] (with Best when [isBest]) and an
 * optional [subtitle] on the left; the time and average speed (always speed) on the right.
 * Used on the segment screen (date, ride) and the ride screen (segment, lap). [onClickLabel]
 * is what TalkBack says a tap does, e.g. "Open ride".
 */
@Composable
fun EffortCard(
    title: String,
    subtitle: String?,
    isBest: Boolean,
    elapsedMs: Long,
    distanceMeters: Double,
    unit: DistanceUnit,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            // Clip first, so the tap ripple follows the card's rounded corners.
            .clip(CardDefaults.shape)
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = titleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isBest) {
                        Spacer(Modifier.width(8.dp))
                        BestLabel()
                    }
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Format.clock(elapsedMs),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    Format.speed(EffortSpeed.averageMps(distanceMeters, elapsedMs), unit),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
