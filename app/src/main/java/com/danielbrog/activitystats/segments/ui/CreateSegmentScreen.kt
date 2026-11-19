package com.danielbrog.activitystats.segments.ui

import android.widget.Toast
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.delay

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
        actions = PickerActions(
            onRangeChange = viewModel::setRange,
            onSliderReleased = viewModel::onSliderReleased,
            onShowWholeRide = viewModel::showWholeRide,
            onNudge = viewModel::nudge,
            onMapTap = viewModel::onMapTap,
            onCreate = viewModel::create,
        ),
        modifier = modifier,
    )
}

/** What the picker can ask for, gathered so they pass down as one. */
private class PickerActions(
    val onRangeChange: (start: Int, end: Int) -> Unit,
    val onSliderReleased: () -> Unit,
    val onShowWholeRide: () -> Unit,
    val onNudge: (moveStart: Boolean, forward: Boolean) -> Unit,
    val onMapTap: (point: RoutePoint, radiusMeters: Double) -> Unit,
    val onCreate: (name: String) -> Unit,
)

@Composable
private fun CreateSegmentContent(
    state: CreateSegmentState,
    unit: DistanceUnit,
    saving: Boolean,
    onBack: () -> Unit,
    actions: PickerActions,
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
            is CreateSegmentState.Picking -> Picker(state, unit, saving, actions)
        }
    }
}

/**
 * The map on top, filling what's left; underneath, the slider with its nudge buttons, the
 * readout, the name and Create. Everything underneath keeps the same height whatever it
 * says, so the map doesn't jump about.
 */
@Composable
private fun Picker(
    state: CreateSegmentState.Picking,
    unit: DistanceUnit,
    saving: Boolean,
    actions: PickerActions,
) = Column(Modifier.fillMaxSize()) {
    SegmentPickerMap(
        points = state.points,
        stretches = state.stretches,
        picked = state.picked,
        start = state.start,
        end = state.end,
        focus = state.window,
        onTap = actions.onMapTap,
        modifier = Modifier.weight(1f).fillMaxWidth(),
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        // The slider covers [window]: the whole ride, or zoomed in around the part once you
        // let go of it, so shorter parts move in finer steps.
        RangeSlider(
            value = state.range.first.toFloat()..state.range.last.toFloat(),
            onValueChange = { actions.onRangeChange(it.start.roundToInt(), it.endInclusive.roundToInt()) },
            onValueChangeFinished = actions.onSliderReleased,
            valueRange = state.window.first.toFloat()..state.window.last.toFloat(),
            // The values are point numbers, which mean nothing read aloud; say the distance.
            modifier = Modifier.semantics {
                contentDescription = "Segment start and end"
                stateDescription = Format.distance(state.distanceMeters, unit)
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            NudgeButtons(end = "start", onNudge = { forward -> actions.onNudge(true, forward) })
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${Format.distance(state.windowStartMeters, unit)} → ${Format.distance(state.windowEndMeters, unit)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Always there (greyed out over the whole ride), so the controls keep their height.
                TextButton(onClick = actions.onShowWholeRide, enabled = !state.isWholeRide) { Text("Whole ride") }
            }
            NudgeButtons(end = "end", onNudge = { forward -> actions.onNudge(false, forward) })
        }
        Text(
            "Drag the ends, tap the map near the route, or use ‹ › to move an end a point at a time.",
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
            onClick = { cleaned?.let(actions.onCreate) },
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

/** ‹ and › for one [end] of the part ("start" or "end"): one point a tap, repeating while held. */
@Composable
private fun NudgeButtons(end: String, onNudge: (forward: Boolean) -> Unit) {
    Row {
        RepeatingButton(symbol = "‹", description = "Move the $end back", onStep = { onNudge(false) })
        RepeatingButton(symbol = "›", description = "Move the $end forward", onStep = { onNudge(true) })
    }
}

/**
 * A button that calls [onStep] once when tapped, and over and over while held down (after a
 * short wait), so an end can be walked along the route.
 */
@Composable
private fun RepeatingButton(symbol: String, description: String, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Set once holding has started repeating, so letting go doesn't add one more step.
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        repeated = false
        delay(REPEAT_AFTER_MS)
        repeated = true
        while (true) {
            step()
            delay(REPEAT_EVERY_MS)
        }
    }
    FilledTonalIconButton(
        onClick = { if (!repeated) step() },
        interactionSource = interaction,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(symbol, style = MaterialTheme.typography.titleLarge)
    }
}

/** How long a nudge button must be held before it repeats, then how often. */
private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_EVERY_MS = 80L

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
        // Always room for two lines, so the map above (which takes what's left) doesn't jump
        // as the message changes length.
        minLines = 2,
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
                window = 0..1,
                windowStartMeters = 3_200.0,
                windowEndMeters = 4_400.0,
                isWholeRide = false,
                distanceMeters = 1_240.0,
                check = SelectionCheck.Ready(1_240.0, yourTimeMs = 151_000L, stoppedInside = false),
            ),
            unit = DistanceUnit.Km,
            saving = false,
            onBack = {},
            actions = PickerActions({ _, _ -> }, {}, {}, { _, _ -> }, { _, _ -> }, {}),
        )
    }
}
