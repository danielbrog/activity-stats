package com.danielbrog.activitystats.segments.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danielbrog.activitystats.R
import com.danielbrog.activitystats.data.SegmentEffortWithRide
import com.danielbrog.activitystats.data.SegmentEntity
import com.danielbrog.activitystats.history.RideFormat
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.segments.EffortSpeed
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.Format
import com.danielbrog.activitystats.ui.RouteMap
import com.danielbrog.activitystats.ui.rememberRouteMapState
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

/**
 * One segment: its line on a map, its distance, best time and number of efforts, then every
 * effort, fastest first. Tapping an effort opens its ride ([onOpenRide], with the ride's id).
 * Average speed is always shown as speed, whatever the speed/pace setting.
 *
 * Like History and Records, shows Undo when a ride opened from here was deleted
 * ([deletedRideId], then [onDeletedRideHandled] so it isn't shown again).
 */
@Composable
fun SegmentDetailScreen(
    onBack: () -> Unit,
    onOpenRide: (id: String) -> Unit,
    deletedRideId: String?,
    onDeletedRideHandled: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SegmentDetailViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Same two-step handling as History, so clearing the result can't cancel the bar.
    var undoRideId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(deletedRideId) {
        if (deletedRideId != null) {
            undoRideId = deletedRideId
            onDeletedRideHandled()
        }
    }
    LaunchedEffect(undoRideId) {
        val id = undoRideId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Ride deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.restoreRide(id)
        undoRideId = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        SegmentDetailContent(state, unit, onBack, onOpenRide)
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SegmentDetailContent(
    state: SegmentDetailState,
    unit: DistanceUnit,
    onBack: () -> Unit,
    onOpenRide: (id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
            }
            Text(
                text = (state as? SegmentDetailState.Loaded)?.segment?.name ?: "Segment",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        when (state) {
            SegmentDetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            SegmentDetailState.NotFound -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("This segment is no longer on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is SegmentDetailState.Loaded -> LoadedSegment(state, unit, onOpenRide)
        }
    }
}

/**
 * Scrolls: map, stats, then the efforts. The list can grow long, so it is lazy; the map's
 * camera is kept out here, so scrolling the map away and back doesn't reset it, and the list
 * holds still while the map is being moved, so dragging on the map moves the map.
 */
@Composable
private fun LoadedSegment(state: SegmentDetailState.Loaded, unit: DistanceUnit, onOpenRide: (id: String) -> Unit) {
    val distance = state.segment.distanceMeters
    val mapState = rememberRouteMapState()
    val best = when {
        state.isFinding -> "…"
        else -> state.efforts.firstOrNull()?.let { Format.clock(it.elapsedMs) } ?: "–"
    }
    LazyColumn(
        userScrollEnabled = !mapState.camera.isMoving,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "map") {
            RouteMap(
                lines = listOf(state.line),
                modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp)),
                startTitle = "Segment start",
                endTitle = "Segment end",
                state = mapState,
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Stat("Distance", Format.distance(distance, unit), Modifier.weight(1f))
                Stat("Best", best, Modifier.weight(1f))
                Stat("Efforts", if (state.isFinding) "…" else state.efforts.size.toString(), Modifier.weight(1f))
            }
        }
        item {
            Text("Efforts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        }
        if (state.efforts.isEmpty()) {
            item {
                Text(
                    if (state.isFinding) "Finding efforts…"
                    else "No efforts yet. Rides that cover this segment will show up here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
        } else {
            itemsIndexed(state.efforts, key = { _, e -> "${e.activityId}/${e.occurrence}" }) { index, effort ->
                EffortRow(effort, distance, isBest = index == 0, unit, onClick = { onOpenRide(effort.activityId) })
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    // Read as one: "Best, 2:31".
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** When and on which ride, then the time and average speed; the fastest is marked Best. */
@Composable
private fun EffortRow(
    effort: SegmentEffortWithRide,
    distanceMeters: Double,
    isBest: Boolean,
    unit: DistanceUnit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Clip first, so the tap ripple follows the card's rounded corners.
            .clip(CardDefaults.shape)
            .clickable(onClickLabel = "Open ride", role = Role.Button, onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(RideFormat.date(effort.startedAt), style = MaterialTheme.typography.bodyMedium)
                    if (isBest) {
                        Spacer(Modifier.width(8.dp))
                        BestLabel()
                    }
                }
                Text(
                    effort.rideTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Format.clock(effort.elapsedMs),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    Format.speed(EffortSpeed.averageMps(distanceMeters, effort.elapsedMs), unit),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BestLabel() {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
        Text(
            "Best",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Preview(showBackground = true, heightDp = 760)
@Composable
private fun SegmentDetailPreview() {
    val day = 86_400_000L
    val now = 1_793_000_000_000L
    ActivityStatsTheme {
        SegmentDetailContent(
            state = SegmentDetailState.Loaded(
                segment = SegmentEntity(
                    id = "s", name = "Bayview climb", path = "", distanceMeters = 1_240.0,
                    createdAt = now, updatedAt = now,
                ),
                line = listOf(RoutePoint(43.65, -79.38), RoutePoint(43.66, -79.37)),
                efforts = listOf(
                    SegmentEffortWithRide("s", "a", 0, now, 151_000L, "Morning ride"),
                    SegmentEffortWithRide("s", "b", 0, now - day, 163_000L, "Evening ride"),
                ),
                isFinding = false,
            ),
            unit = DistanceUnit.Km,
            onBack = {},
            onOpenRide = {},
        )
    }
}
