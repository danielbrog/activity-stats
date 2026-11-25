package com.danielbrog.activitystats.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danielbrog.activitystats.R
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.stats.Format
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Details view: every GPS fix of the current recording, newest first, with dropped fixes
 * greyed out and their reason. Recording is started and stopped on the live screen.
 */
@Composable
fun GPSDisplayScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: LocationViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    GPSDisplayContent(state = state, settings = settings, onBack = onBack, modifier = modifier)
}

@Composable
private fun GPSDisplayContent(
    state: TrackingUiState,
    settings: DisplaySettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
            }
            Text(
                "GPS fixes",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                state.totalCount > 0 ->
                    "${state.totalCount} fixes · ${state.keptCount} kept · " +
                        "${state.totalCount - state.keptCount} dropped"
                state.isTracking -> "Waiting for the first fix… (can take a minute outdoors)"
                else -> "Start a recording on the live screen to see GPS fixes as they arrive."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(state.fixes, key = { it.seq }) { fix ->
                FixRow(fix, settings)
                HorizontalDivider()
            }
        }
    }
}

/**
 * One fix as the GPS gave it. Position stays in raw degrees; accuracy and speed are in the
 * chosen units, with the raw m/s alongside the speed or pace.
 */
@Composable
private fun FixRow(fix: GpsData, settings: DisplaySettings) {
    val unit = settings.unit
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val kept = fix.status == FixStatus.Kept
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (kept) 1f else 0.45f)
            .padding(vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("#${fix.seq}", fontWeight = FontWeight.Bold)
            if (!kept) {
                Text(
                    text = "dropped · " + when (fix.status) {
                        FixStatus.DroppedAccuracy ->
                            fix.accuracyMeters
                                ?.let { "accuracy " + Format.shortLength(it.toDouble(), unit) } ?: "no accuracy"
                        else -> "standing still"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(timeFormat.format(Date(fix.timeMillis)), style = mono)
        }
        Text("lat  %.6f".format(Locale.US, fix.latitude), style = mono)
        Text("long %.6f".format(Locale.US, fix.longitude), style = mono)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text =
                    "acc  " + (
                        fix.accuracyMeters?.let {
                            Format.shortLength(it.toDouble(), unit, decimals = 1)
                        } ?: "—"
                        ),
                style = mono,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "speed " + (
                    fix.speedMps?.let {
                        "%.2f m/s (%s)".format(
                            Locale.US,
                            it,
                            Format.speedOrPace(it.toDouble(), unit, settings.speedDisplay),
                        )
                    } ?: "—"
                    ),
                style = mono,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GPSDisplayPreview() {
    val now = System.currentTimeMillis()
    ActivityStatsTheme {
        GPSDisplayContent(
            state = TrackingUiState(
                isTracking = true,
                fixes = listOf(
                    GpsData(4, now, 43.653226, -79.383184, 4.2f, 2.85f),
                    GpsData(3, now - 1_000, 43.653220, -79.383180, 5.0f, 0.1f, FixStatus.DroppedStationary),
                    GpsData(2, now - 2_000, 43.653201, -79.383150, 34.0f, 2.70f, FixStatus.DroppedAccuracy),
                    GpsData(1, now - 3_000, 43.653180, -79.383119, 12.6f, null),
                ),
                totalCount = 4,
                keptCount = 2,
            ),
            settings = DisplaySettings(),
            onBack = {},
        )
    }
}
