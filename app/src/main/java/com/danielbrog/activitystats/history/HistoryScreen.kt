package com.danielbrog.activitystats.history

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.stats.Format
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

/** The History tab: every saved ride, newest first. */
@Composable
fun HistoryScreen(
    onOpenRide: (id: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = viewModel(),
) {
    val rides by viewModel.rides.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    HistoryContent(rides, settings, use24Hour, onOpenRide, modifier)
}

@Composable
private fun HistoryContent(
    rides: List<ActivityEntity>?,
    settings: DisplaySettings,
    use24Hour: Boolean,
    onOpenRide: (id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            "History",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        when {
            rides == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            rides.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No rides yet. Record one from the Record tab.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rides, key = { it.id }) { ride ->
                    RideCard(ride, settings, use24Hour, onClick = { onOpenRide(ride.id) })
                }
            }
        }
    }
}

@Composable
private fun RideCard(
    ride: ActivityEntity,
    settings: DisplaySettings,
    use24Hour: Boolean,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(ride.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                RideFormat.dateLine(ride.startedAt, use24Hour = use24Hour),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                listOf(
                    Format.distance(ride.distanceMeters, settings.unit),
                    Format.clock(ride.movingTimeMs),
                    Format.speedOrPace(ride.avgSpeedMps, settings.unit, settings.speedDisplay),
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HistoryPreview() {
    val day = 86_400_000L
    val now = 1_793_000_000_000L
    ActivityStatsTheme {
        HistoryContent(
            rides = listOf(
                ActivityEntity(
                    id = "a", startedAt = now, endedAt = now + 3_000_000, title = "Morning ride",
                    distanceMeters = 21_430.0, movingTimeMs = 2_820_000, avgSpeedMps = 7.6,
                    updatedAt = now,
                ),
                ActivityEntity(
                    id = "b", startedAt = now - day, endedAt = now - day + 1_800_000,
                    title = "Evening ride", distanceMeters = 9_880.0, movingTimeMs = 1_600_000,
                    avgSpeedMps = 6.2, updatedAt = now,
                ),
            ),
            settings = DisplaySettings(),
            use24Hour = false,
            onOpenRide = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HistoryEmptyPreview() {
    ActivityStatsTheme {
        HistoryContent(emptyList(), DisplaySettings(), use24Hour = false, onOpenRide = {})
    }
}
