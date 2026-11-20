package com.danielbrog.activitystats.history

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danielbrog.activitystats.R
import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.data.RideSegmentEffort
import com.danielbrog.activitystats.data.RideTitle
import com.danielbrog.activitystats.export.GpxWriter
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.segments.EffortSpeed
import com.danielbrog.activitystats.segments.Laps
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.ElevationSample
import com.danielbrog.activitystats.stats.Format
import com.danielbrog.activitystats.stats.SpeedDisplay
import com.danielbrog.activitystats.stats.SpeedSample
import com.danielbrog.activitystats.stats.Split
import com.danielbrog.activitystats.ui.BestLabel
import com.danielbrog.activitystats.ui.RouteMap
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

/**
 * One saved ride: its route on a map, its stats, and the segments it covered (tapping one
 * opens it: [onOpenSegment]). The ⋮ menu exports it, makes a segment from part of it
 * ([onCreateSegment], with its id), edits the name or deletes the ride; [onDeleted] is called
 * with its id once it is deleted, so History can offer Undo.
 *
 * Shows Undo when a segment opened from here was deleted ([deletedSegmentId], then
 * [onDeletedSegmentHandled] so it isn't shown again).
 */
@Composable
fun RideDetailScreen(
    onBack: () -> Unit,
    onCreateSegment: (rideId: String) -> Unit,
    onOpenSegment: (id: String) -> Unit,
    onDeleted: (id: String) -> Unit,
    deletedSegmentId: String?,
    onDeletedSegmentHandled: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RideDetailViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val segmentEfforts by viewModel.segmentEfforts.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Same two-step handling as History, so clearing the result can't cancel the bar.
    var undoSegmentId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(deletedSegmentId) {
        if (deletedSegmentId != null) {
            undoSegmentId = deletedSegmentId
            onDeletedSegmentHandled()
        }
    }
    LaunchedEffect(undoSegmentId) {
        val id = undoSegmentId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Segment deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.restoreSegment(id)
        undoSegmentId = null
    }
    val context = LocalContext.current
    val use24Hour = DateFormat.is24HourFormat(context)
    var showEdit by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    val ride = (state as? RideDetailState.Loaded)?.ride

    Box(modifier = modifier.fillMaxSize()) {
        RideDetailContent(
            state = state,
            segmentEfforts = segmentEfforts,
            settings = settings,
            use24Hour = use24Hour,
            onBack = onBack,
            onExport = {
                viewModel.exportGpx(
                    onReady = { shareGpx(context, it) },
                    onError = { Toast.makeText(context, "Couldn't export this ride", Toast.LENGTH_SHORT).show() },
                )
            },
            onCreateSegment = { onCreateSegment(viewModel.rideId) },
            onEdit = { showEdit = true },
            onDelete = { showDelete = true },
            onToggleSplits = viewModel::setShowSplits,
            onOpenSegment = onOpenSegment,
        )
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (showEdit && ride != null) {
        EditRideDialog(
            currentTitle = ride.title,
            onSave = { name ->
                viewModel.rename(name)
                showEdit = false
            },
            onDismiss = { showEdit = false },
        )
    }
    if (showDelete && ride != null) {
        DeleteRideDialog(
            title = ride.title,
            onConfirm = {
                showDelete = false
                viewModel.delete { onDeleted(viewModel.rideId) }
            },
            onDismiss = { showDelete = false },
        )
    }
}

/** Opens the share sheet for an exported GPX file, letting the chosen app read it. */
private fun shareGpx(context: Context, export: GpxExport) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = GpxWriter.MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, export.uri)
        putExtra(Intent.EXTRA_TITLE, export.fileName)
        // ClipData carries the read grant through the chooser to the chosen app.
        clipData = ClipData.newRawUri(export.fileName, export.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export GPX"))
}

@Composable
private fun RideDetailContent(
    state: RideDetailState,
    segmentEfforts: List<RideSegmentEffort>,
    settings: DisplaySettings,
    use24Hour: Boolean,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onCreateSegment: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleSplits: (show: Boolean) -> Unit,
    onOpenSegment: (id: String) -> Unit,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state is RideDetailState.Loaded) {
                RideMenu(onExport = onExport, onCreateSegment = onCreateSegment, onEdit = onEdit, onDelete = onDelete)
            }
        }

        when (state) {
            RideDetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            RideDetailState.NotFound -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This ride is no longer on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is RideDetailState.Loaded -> LoadedRide(
                state.ride, state.route, state.speedSamples, state.elevationSamples,
                state.splits(settings.unit), settings, use24Hour, onToggleSplits,
                segmentEfforts, onOpenSegment,
            )
        }
    }
}

/** Scrolls: date, map, stats, splits, segments (when it covered any), then the speed and elevation charts. */
@Composable
private fun LoadedRide(
    ride: ActivityEntity,
    route: List<List<RoutePoint>>,
    speedSamples: List<SpeedSample>,
    elevationSamples: List<ElevationSample>,
    splits: List<Split>,
    settings: DisplaySettings,
    use24Hour: Boolean,
    onToggleSplits: (show: Boolean) -> Unit,
    segmentEfforts: List<RideSegmentEffort>,
    onOpenSegment: (id: String) -> Unit,
) = Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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

    val mapModifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp))
    if (route.isEmpty()) {
        Box(
            modifier = mapModifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("No route recorded", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        RouteMap(route, modifier = mapModifier)
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
        // Null only for a moment after the app is updated, until it is worked out.
        "Elevation gain" to (ride.elevationGainMeters?.let { Format.elevation(it, unit) } ?: "–"),
    )

    if (splits.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        SplitsSection(splits, unit, display, expanded = settings.showSplits, onToggle = onToggleSplits)
    }

    if (segmentEfforts.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        SegmentsOnRide(segmentEfforts, unit, onOpenSegment)
    }

    Spacer(Modifier.height(16.dp))
    Text("Speed", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    if (speedSamples.size >= 2) {
        SpeedChart(speedSamples, ride.avgSpeedMps, unit)
    } else {
        Text(
            "Not enough data for a speed chart",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(24.dp))
    // "(GPS)": phone altitude is height above the GPS reference shape of the Earth, which can
    // be tens of metres off sea level. The shape of the climbs is right; the numbers are offset.
    Text("Elevation (GPS)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    if (elevationSamples.size >= 2) {
        ElevationChart(elevationSamples, unit)
    } else {
        Text(
            "No elevation data for this ride",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * The segments this ride covered, in riding order: name (with the lap when it covered one more
 * than once), time, average speed (always speed), and Best when it is your fastest there.
 */
@Composable
private fun SegmentsOnRide(efforts: List<RideSegmentEffort>, unit: DistanceUnit, onOpenSegment: (id: String) -> Unit) {
    val laps = remember(efforts) { Laps.numbers(efforts.map { it.segmentId to it.occurrence }) }
    Text("Segments", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        efforts.forEachIndexed { i, effort ->
            SegmentEffortRow(
                effort,
                lap = laps[i],
                unit = unit,
                onClick = { onOpenSegment(effort.segmentId) },
            )
        }
    }
}

@Composable
private fun SegmentEffortRow(effort: RideSegmentEffort, lap: Int?, unit: DistanceUnit, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Clip first, so the tap ripple follows the card's rounded corners.
            .clip(CardDefaults.shape)
            .clickable(onClickLabel = "Open segment", role = Role.Button, onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        effort.segmentName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (effort.isBest) {
                        Spacer(Modifier.width(8.dp))
                        BestLabel()
                    }
                }
                if (lap != null) {
                    Text(
                        "Lap $lap",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Format.clock(effort.elapsedMs),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    Format.speed(EffortSpeed.averageMps(effort.distanceMeters, effort.elapsedMs), unit),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The ⋮ button and its drop-down with Export GPX, Create segment, Edit and Delete. */
@Composable
private fun RideMenu(onExport: () -> Unit, onCreateSegment: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Export GPX") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) },
                onClick = {
                    expanded = false
                    onExport()
                },
            )
            DropdownMenuItem(
                text = { Text("Create segment") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_segment), contentDescription = null) },
                onClick = {
                    expanded = false
                    onCreateSegment()
                },
            )
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

/** Rename dialog. Save stays off while the name is blank; names are capped in length. */
@Composable
private fun EditRideDialog(currentTitle: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(currentTitle) }
    val cleaned = RideTitle.clean(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ride") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(RideTitle.MAX_LENGTH) },
                label = { Text("Name") },
                singleLine = true,
                isError = cleaned == null,
                supportingText = {
                    Text(
                        if (cleaned == null) "Name can't be blank"
                        else "${text.length} / ${RideTitle.MAX_LENGTH}"
                    )
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { cleaned?.let(onSave) }, enabled = cleaned != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteRideDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"$title\"?") },
        text = { Text("It will be removed from this phone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
            segmentEfforts = listOf(
                RideSegmentEffort("s", "Bayview climb", 1_240.0, 0, start + 600_000, 151_000L, isBest = true),
            ),
            onOpenSegment = {},
            state = RideDetailState.Loaded(
                ride = ActivityEntity(
                    id = "a", startedAt = start, endedAt = start + 3_120_000, title = "Morning ride",
                    distanceMeters = 21_430.0, movingTimeMs = 2_820_000, elapsedTimeMs = 3_120_000,
                    avgSpeedMps = 7.6, maxSpeedMps = 12.4, updatedAt = start,
                ),
                route = listOf(listOf(RoutePoint(43.65, -79.38), RoutePoint(43.66, -79.37))),
                speedSamples = (0..200).map {
                    SpeedSample(it * 107.0, 7.6 + 2.5 * kotlin.math.sin(it / 12.0))
                },
                elevationSamples = (0..200).map {
                    ElevationSample(it * 107.0, 110.0 + 25.0 * kotlin.math.sin(it / 30.0))
                },
            ),
            settings = DisplaySettings(),
            use24Hour = false,
            onBack = {},
            onExport = {},
            onCreateSegment = {},
            onEdit = {},
            onDelete = {},
            onToggleSplits = {},
        )
    }
}
