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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import com.danielbrog.activitystats.data.RideTitle
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

/**
 * One saved ride: its route on a map and its stats. The ⋮ menu edits the name or deletes
 * the ride; [onDeleted] is called with its id once it is deleted, so History can offer Undo.
 */
@Composable
fun RideDetailScreen(
    onBack: () -> Unit,
    onDeleted: (id: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RideDetailViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    var showEdit by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    val ride = (state as? RideDetailState.Loaded)?.ride

    RideDetailContent(
        state = state,
        settings = settings,
        use24Hour = use24Hour,
        onBack = onBack,
        onEdit = { showEdit = true },
        onDelete = { showDelete = true },
        modifier = modifier,
    )

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

@Composable
private fun RideDetailContent(
    state: RideDetailState,
    settings: DisplaySettings,
    use24Hour: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
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
                RideMenu(onEdit = onEdit, onDelete = onDelete)
            }
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

/** The ⋮ button and its drop-down with Edit and Delete. */
@Composable
private fun RideMenu(onEdit: () -> Unit, onDelete: () -> Unit) {
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
            onEdit = {},
            onDelete = {},
        )
    }
}
