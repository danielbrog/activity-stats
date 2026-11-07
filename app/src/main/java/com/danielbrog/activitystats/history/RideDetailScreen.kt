package com.danielbrog.activitystats.history

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danielbrog.activitystats.R
import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.stats.Format
import com.danielbrog.activitystats.stats.SpeedDisplay
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

/** One saved ride: its route on a map and its stats. */
@Composable
fun RideDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RideDetailViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    RideDetailContent(state, settings, use24Hour, onBack, modifier)
}

@Composable
private fun RideDetailContent(
    state: RideDetailState,
    settings: DisplaySettings,
    use24Hour: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
            }
            Text(
                text = (state as? RideDetailState.Loaded)?.ride?.title ?: "Ride",
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        when (state) {
            RideDetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            RideDetailState.NotFound -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This ride is no longer on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is RideDetailState.Loaded -> LoadedRide(state.ride, state.route, settings, use24Hour)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.LoadedRide(
    ride: ActivityEntity,
    route: List<RoutePoint>,
    settings: DisplaySettings,
    use24Hour: Boolean,
) {
    val unit = settings.unit
    val display = settings.speedDisplay
    val speedWord = if (display == SpeedDisplay.Speed) "speed" else "pace"

    Text(
        RideFormat.dateLine(ride.startedAt, use24Hour = use24Hour),
        style = MaterialTheme.typography.bodyLarge,
    )
    ride.endedAt?.let { end ->
        Text(
            RideFormat.timeRange(ride.startedAt, end, use24Hour = use24Hour),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))

    val mapModifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp))
    if (route.isEmpty()) {
        Box(
            modifier = mapModifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("No route recorded", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        RideMap(route, modifier = mapModifier)
    }

    Spacer(Modifier.height(16.dp))
    StatRow(
        "Distance" to Format.distance(ride.distanceMeters, unit),
        "Moving time" to Format.clock(ride.movingTimeMs),
    )
    StatRow(
        "Average $speedWord" to Format.speedOrPace(ride.avgSpeedMps, unit, display),
        "Elapsed time" to Format.clock(ride.elapsedTimeMs),
    )
    StatRow(
        (if (display == SpeedDisplay.Speed) "Max speed" else "Best pace") to
            Format.speedOrPace(ride.maxSpeedMps, unit, display),
        null,
    )
}

@Composable
private fun StatRow(left: Pair<String, String>, right: Pair<String, String>?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        StatTile(left.first, left.second, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        if (right != null) StatTile(right.first, right.second, Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Preview(showBackground = true, heightDp = 760)
@Composable
private fun RideDetailPreview() {
    val start = 1_793_000_000_000L
    ActivityStatsTheme {
        RideDetailContent(
            state = RideDetailState.Loaded(
                ride = ActivityEntity(
                    id = "a", startedAt = start, endedAt = start + 3_120_000, title = "Morning ride",
                    distanceMeters = 21_430.0, movingTimeMs = 2_820_000, elapsedTimeMs = 3_120_000,
                    avgSpeedMps = 7.6, maxSpeedMps = 12.4, updatedAt = start,
                ),
                route = listOf(RoutePoint(43.65, -79.38), RoutePoint(43.66, -79.37)),
            ),
            settings = DisplaySettings(),
            use24Hour = false,
            onBack = {},
        )
    }
}
