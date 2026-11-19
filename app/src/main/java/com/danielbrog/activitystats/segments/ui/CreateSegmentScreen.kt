package com.danielbrog.activitystats.segments.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danielbrog.activitystats.R
import com.danielbrog.activitystats.data.SegmentName
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.segments.SegmentSelection
import com.danielbrog.activitystats.segments.SelectionCheck
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.Format
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme
import kotlin.math.roundToInt

/**
 * Makes a segment from part of a ride: pick the part on the map or with the slider, name it,
 * then Create. Closes itself once saved; its efforts are found in the background and appear
 * in the Segments tab.
 */
@Composable
fun CreateSegmentScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CreateSegmentViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // React to the saved result here, not in a callback, so a screen rebuilt by rotation sees it.
    LaunchedEffect(outcome) {
        when (outcome) {
            CreateOutcome.Created -> {
                Toast.makeText(context, "Segment created", Toast.LENGTH_SHORT).show()
                onBack()
            }
            CreateOutcome.Failed -> {
                Toast.makeText(context, "Couldn't create this segment", Toast.LENGTH_SHORT).show()
                viewModel.failureShown()
            }
            null -> Unit
        }
    }

    CreateSegmentContent(
        state = state,
        unit = settings.unit,
        saving = saving,
        onBack = onBack,
        onRangeChange = viewModel::setRange,
        onMapTap = viewModel::onMapTap,
        onCreate = viewModel::create,
        modifier = modifier,
    )
}

@Composable
private fun CreateSegmentContent(
    state: CreateSegmentState,
    unit: DistanceUnit,
    saving: Boolean,
    onBack: () -> Unit,
    onRangeChange: (start: Int, end: Int) -> Unit,
    onMapTap: (point: RoutePoint, radiusMeters: Double) -> Unit,
    onCreate: (name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
            }
            Text(
                "Create segment",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        when (state) {
            CreateSegmentState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            CreateSegmentState.NoRoute -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "This ride has no route to make a segment from.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is CreateSegmentState.Picking -> Picker(state, unit, saving, onRangeChange, onMapTap, onCreate)
        }
    }
}

/** The map on top, filling what's left; the slider, readout, name and Create underneath. */
@Composable
private fun Picker(
    state: CreateSegmentState.Picking,
    unit: DistanceUnit,
    saving: Boolean,
    onRangeChange: (start: Int, end: Int) -> Unit,
    onMapTap: (point: RoutePoint, radiusMeters: Double) -> Unit,
    onCreate: (name: String) -> Unit,
) = Column(Modifier.fillMaxSize()) {
    SegmentPickerMap(
        points = state.points,
        stretches = state.stretches,
        picked = state.picked,
        start = state.start,
        end = state.end,
        onTap = onMapTap,
        modifier = Modifier.weight(1f).fillMaxWidth(),
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        RangeSlider(
            value = state.range.first.toFloat()..state.range.last.toFloat(),
            onValueChange = { onRangeChange(it.start.roundToInt(), it.endInclusive.roundToInt()) },
            valueRange = 0f..state.lastIndex.toFloat(),
            // The values are point numbers, which mean nothing read aloud; say the distance.
            modifier = Modifier.semantics {
                contentDescription = "Segment start and end"
                stateDescription = Format.distance(state.distanceMeters, unit)
            },
        )
        Text(
            "Drag the ends, or tap the map near the route to move the nearer end there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Readout(state.distanceMeters, state.check, unit)
        Spacer(Modifier.height(8.dp))

        var name by rememberSaveable { mutableStateOf("") }
        val cleaned = SegmentName.clean(name)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(SegmentName.MAX_LENGTH) },
            label = { Text("Name") },
            singleLine = true,
            supportingText = { Text("${name.length} / ${SegmentName.MAX_LENGTH}") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { cleaned?.let(onCreate) },
            enabled = cleaned != null && state.check is SelectionCheck.Ready && !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp).semantics { contentDescription = "Saving" },
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Create")
            }
        }
    }
}

/** The distance, then this ride's time on the part or why it can't be a segment. */
@Composable
private fun Readout(distanceMeters: Double, check: SelectionCheck?, unit: DistanceUnit) {
    val distance = Format.distance(distanceMeters, unit)
    val (text, isProblem) = when (check) {
        null -> "$distance · Checking…" to false
        SelectionCheck.CrossesPause -> "This part crosses a pause. Pick a part you rode without pausing." to true
        is SelectionCheck.TooShort ->
            "$distance · A segment must be at least ${Format.distance(SegmentSelection.MIN_LENGTH_METERS, unit)}" to true
        is SelectionCheck.Ready -> when {
            check.yourTimeMs != null -> "$distance · Your time ${Format.clock(check.yourTimeMs)}" to false
            check.stoppedInside -> "$distance · You stopped inside this part, so this ride won't count on it." to false
            else -> "$distance · This ride won't count on it." to false
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
        color = if (isProblem) MaterialTheme.colorScheme.error else Color.Unspecified,
        // Read out when it changes, since it follows the ends as they move.
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Preview(showBackground = true, heightDp = 760)
@Composable
private fun CreateSegmentPreview() {
    val a = RoutePoint(43.65, -79.38)
    val b = RoutePoint(43.66, -79.37)
    ActivityStatsTheme {
        CreateSegmentContent(
            state = CreateSegmentState.Picking(
                points = listOf(a, b),
                stretches = listOf(0..1),
                picked = listOf(0..1),
                range = 0..1,
                distanceMeters = 1_240.0,
                check = SelectionCheck.Ready(1_240.0, yourTimeMs = 151_000L, stoppedInside = false),
            ),
            unit = DistanceUnit.Km,
            saving = false,
            onBack = {},
            onRangeChange = { _, _ -> },
            onMapTap = { _, _ -> },
            onCreate = {},
        )
    }
}
