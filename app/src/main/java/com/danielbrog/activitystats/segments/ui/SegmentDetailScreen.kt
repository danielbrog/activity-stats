package com.danielbrog.activitystats.segments.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danielbrog.activitystats.R
import com.danielbrog.activitystats.data.SegmentEffortWithRide
import com.danielbrog.activitystats.data.SegmentEntity
import com.danielbrog.activitystats.data.SegmentName
import com.danielbrog.activitystats.history.RideFormat
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.Format
import com.danielbrog.activitystats.ui.ConfirmDeleteDialog
import com.danielbrog.activitystats.ui.EffortCard
import com.danielbrog.activitystats.ui.RenameDialog
import com.danielbrog.activitystats.ui.RouteMap
import com.danielbrog.activitystats.ui.UndoDeleteEffect
import com.danielbrog.activitystats.ui.rememberRouteMapState
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

/**
 * One segment: its line on a map, its distance, best time and number of efforts, then every
 * effort, fastest first. Tapping an effort opens its ride ([onOpenRide], with the ride's id).
 * Average speed is always shown as speed, whatever the speed/pace setting.
 *
 * Like History and Records, shows Undo when a ride opened from here was deleted
 * ([deletedRideId], then [onClearDeletedRide] so it isn't shown again).
 *
 * The ⋮ menu edits the name or deletes the segment; [onDeleteComplete] is called with its id once it
 * is deleted, so the Segments tab can offer Undo.
 */
@Composable
fun SegmentDetailScreen(
    onBack: () -> Unit,
    onOpenRide: (id: String) -> Unit,
    onDeleteComplete: (id: String) -> Unit,
    deletedRideId: String?,
    onClearDeletedRide: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SegmentDetailViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showEdit by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    val segment = (state as? SegmentDetailState.Loaded)?.segment

    UndoDeleteEffect(
        deletedId = deletedRideId,
        onClearResult = onClearDeletedRide,
        snackbarHostState = snackbarHostState,
        message = "Ride deleted",
        onUndo = viewModel::restoreRide,
    )

    Box(modifier = modifier.fillMaxSize()) {
        SegmentDetailContent(
            state,
            unit,
            onBack,
            onOpenRide,
            onEdit = { showEdit = true },
            onDelete = { showDelete = true },
        )
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (showEdit && segment != null) {
        RenameDialog(
            title = "Edit segment",
            currentName = segment.name,
            maxLength = SegmentName.MAX_LENGTH,
            clean = SegmentName::clean,
            // Road names: "Hill Climb".
            capitalization = KeyboardCapitalization.Words,
            onSave = { name ->
                viewModel.rename(name)
                showEdit = false
            },
            onDismiss = { showEdit = false },
        )
    }
    if (showDelete && segment != null) {
        ConfirmDeleteDialog(
            name = segment.name,
            onConfirm = {
                showDelete = false
                viewModel.delete(onDeleteComplete)
            },
            onDismiss = { showDelete = false },
        )
    }
}

/** The ⋮ button and its drop-down with Edit and Delete. */
@Composable
private fun SegmentMenu(onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun SegmentDetailContent(
    state: SegmentDetailState,
    unit: DistanceUnit,
    onBack: () -> Unit,
    onOpenRide: (id: String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
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
            if (state is SegmentDetailState.Loaded) SegmentMenu(onEdit = onEdit, onDelete = onDelete)
        }
        when (state) {
            SegmentDetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            SegmentDetailState.NotFound -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
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
                    if (state.isFinding) {
                        "Finding efforts…"
                    } else {
                        "No efforts yet. Rides that cover this segment will show up here."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
        } else {
            // Every effort with the fastest time is Best (ties are both best), as on the ride screen.
            val bestMs = state.efforts.first().elapsedMs
            items(state.efforts, key = { "${it.activityId}/${it.occurrence}" }) { effort ->
                EffortCard(
                    title = RideFormat.date(effort.startedAt),
                    subtitle = effort.rideTitle,
                    isBest = effort.elapsedMs == bestMs,
                    elapsedMs = effort.elapsedMs,
                    distanceMeters = distance,
                    unit = unit,
                    onClickLabel = "Open ride",
                    onClick = { onOpenRide(effort.activityId) },
                    titleStyle = MaterialTheme.typography.bodyMedium,
                )
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

@Preview(showBackground = true, heightDp = 760)
@Composable
private fun SegmentDetailPreview() {
    val day = 86_400_000L
    val now = 1_793_000_000_000L
    ActivityStatsTheme {
        SegmentDetailContent(
            state = SegmentDetailState.Loaded(
                segment = SegmentEntity(
                    id = "s",
                    name = "Bayview climb",
                    path = "",
                    distanceMeters = 1_240.0,
                    createdAt = now,
                    updatedAt = now,
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
            onEdit = {},
            onDelete = {},
        )
    }
}
