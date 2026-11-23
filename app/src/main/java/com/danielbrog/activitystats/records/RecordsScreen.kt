package com.danielbrog.activitystats.records

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danielbrog.activitystats.history.RideFormat
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.stats.Effort
import com.danielbrog.activitystats.stats.Format
import com.danielbrog.activitystats.ui.UndoDeleteEffect
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

/**
 * The Records tab: longest ride, longest moving time and best efforts, each opening the ride
 * that set it. Like History, shows Undo when a ride opened from here was deleted.
 */
@Composable
fun RecordsScreen(
    onOpenRide: (id: String) -> Unit,
    deletedRideId: String?,
    onDeletedRideHandled: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecordsViewModel = viewModel(),
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    UndoDeleteEffect(
        deletedId = deletedRideId,
        onHandled = onDeletedRideHandled,
        snackbarHostState = snackbarHostState,
        message = "Ride deleted",
        onUndo = viewModel::restore,
    )

    Box(modifier = modifier.fillMaxSize()) {
        RecordsContent(records, settings, onOpenRide)
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun RecordsContent(
    records: PersonalRecords?,
    settings: DisplaySettings,
    onOpenRide: (id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            "Records",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        when {
            records == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            records.longestRide == null -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No records yet. Record a ride to set your first ones.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> RecordList(records, settings, onOpenRide)
        }
    }
}

@Composable
private fun RecordList(
    records: PersonalRecords,
    settings: DisplaySettings,
    onOpenRide: (id: String) -> Unit,
) = Column(
    modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    val unit = settings.unit

    SectionTitle("Ride records")
    RecordCard(
        label = "Longest ride",
        holder = records.longestRide?.holder,
        value = records.longestRide?.let { Format.distance(it.meters, unit) },
        detail = null,
        missing = "",
        onOpenRide = onOpenRide,
    )
    RecordCard(
        label = "Longest moving time",
        holder = records.longestMovingTime?.holder,
        value = records.longestMovingTime?.let { Format.clock(it.ms) },
        detail = null,
        missing = "",
        onOpenRide = onOpenRide,
    )
    RecordCard(
        label = "Biggest climb",
        holder = records.biggestClimb?.holder,
        value = records.biggestClimb?.let { Format.elevation(it.meters, unit) },
        detail = null,
        missing = "Ride some hills to set this",
        onOpenRide = onOpenRide,
    )

    Spacer(Modifier.height(8.dp))
    SectionTitle("Best efforts")
    Effort.forUnit(unit).forEach { effort ->
        val record = records.bestEfforts[effort]
        RecordCard(
            label = "Fastest ${effort.label}",
            holder = record?.holder,
            value = record?.let { Format.clock(it.ms) },
            // Average speed (or pace) over the effort's distance, as chosen in Settings.
            detail = record?.let { Format.speedOrPace(effort.meters / (it.ms / 1_000.0), unit, settings.speedDisplay) },
            missing = "Ride ${effort.label} to set this",
            onOpenRide = onOpenRide,
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

/** One record: what it is and which ride set it on the left, the value on the right. */
@Composable
private fun RecordCard(
    label: String,
    holder: RecordHolder?,
    value: String?,
    detail: String?,
    missing: String,
    onOpenRide: (id: String) -> Unit,
) {
    val cardModifier = Modifier.fillMaxWidth().let { m ->
        if (holder != null) m.clickable { onOpenRide(holder.rideId) } else m
    }
    Card(modifier = cardModifier) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    holder?.let { "${it.title} · ${RideFormat.date(it.startedAt)}" } ?: missing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    value ?: "–",
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RecordsPreview() {
    val day = 1_793_000_000_000L
    val a = RecordHolder("a", "Lakeshore loop", day)
    val b = RecordHolder("b", "Morning ride", day - 86_400_000L * 9)
    ActivityStatsTheme {
        RecordsContent(
            records = PersonalRecords(
                longestRide = DistanceRecord(a, 52_300.0),
                longestMovingTime = TimeRecord(a, 7_420_000),
                biggestClimb = DistanceRecord(b, 640.0),
                bestEfforts = mapOf(
                    Effort.Km5 to TimeRecord(b, 512_000),
                    Effort.Km10 to TimeRecord(b, 1_071_000),
                    Effort.Km20 to TimeRecord(a, 2_260_000),
                    Effort.Km40 to null,
                ),
            ),
            settings = DisplaySettings(),
            onOpenRide = {},
        )
    }
}
