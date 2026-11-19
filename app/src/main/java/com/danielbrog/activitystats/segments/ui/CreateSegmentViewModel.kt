package com.danielbrog.activitystats.segments.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.danielbrog.activitystats.ActivityStatsApp
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.navigation.CreateSegmentRoute
import com.danielbrog.activitystats.segments.SegmentSelection
import com.danielbrog.activitystats.segments.SelectionCheck
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.settings.DisplaySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CreateSegmentState {
    data object Loading : CreateSegmentState

    /** The ride has fewer than two kept points: nothing to pick from. */
    data object NoRoute : CreateSegmentState

    /**
     * [points] is every kept point of the ride, built once; [stretches] and [picked] are index
     * ranges into it (one per stretch between manual pauses), so moving an end copies nothing.
     */
    data class Picking(
        val points: List<RoutePoint>,
        val stretches: List<IntRange>,
        val picked: List<IntRange>,
        /** The chosen part, as indices into [points], for the slider. */
        val range: IntRange,
        val distanceMeters: Double,
        /** Null while it is being worked out. */
        val check: SelectionCheck?,
    ) : CreateSegmentState {
        val lastIndex: Int get() = points.lastIndex
        val start: RoutePoint get() = points[range.first]
        val end: RoutePoint get() = points[range.last]
    }
}

/** How Create ended, for the screen to act on once. */
sealed interface CreateOutcome {
    data object Created : CreateOutcome
    data object Failed : CreateOutcome
}

/**
 * Picking part of a ride, by the id in the navigation route, and saving it as a segment.
 *
 * The ride's points are loaded once. The check (and this ride's time on the part) runs off
 * the main thread a moment after the ends stop moving, and each result says which part it
 * was for, so it is only shown while that part is still chosen. The chosen ends are kept in
 * [savedStateHandle], so they survive Android stopping the app in the background.
 *
 * Saving is one quick write; the repository finds the segment's efforts in the background,
 * whether or not this screen is still open. The result arrives as [outcome] (state, not a
 * callback), so a screen rebuilt by rotation still sees it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateSegmentViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val app = application as ActivityStatsApp
    private val rideId: String = savedStateHandle.toRoute<CreateSegmentRoute>().rideId

    private val selection = MutableStateFlow<SegmentSelection?>(null)

    /** The chosen ends, saved with the screen; unset until the ride has loaded. */
    private val range: StateFlow<IntRange?> =
        combine(savedStateHandle.getStateFlow(START, -1), savedStateHandle.getStateFlow(END, -1)) { s, e ->
            if (s in 0 until e) s..e else null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A check result and the part it was made for. */
    private data class Checked(val range: IntRange, val result: SelectionCheck)

    private val checked: StateFlow<Checked?> = combine(selection, range) { picker, r -> picker to r }
        .transformLatest { (picker, r) ->
            if (picker == null || r == null) return@transformLatest
            delay(CHECK_DELAY_MS) // a newer part cancels this one
            emit(Checked(r, withContext(Dispatchers.Default) { picker.check(r) }))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<CreateSegmentState> =
        combine(selection, range, checked) { picker, r, done ->
            when {
                picker == null -> CreateSegmentState.Loading
                !picker.canPick -> CreateSegmentState.NoRoute
                r == null -> CreateSegmentState.Loading
                else -> CreateSegmentState.Picking(
                    points = picker.points,
                    stretches = picker.stretches,
                    picked = picker.runs(r),
                    range = r,
                    distanceMeters = picker.distanceMeters(r),
                    check = done?.takeIf { it.range == r }?.result,
                )
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), CreateSegmentState.Loading)

    val settings: StateFlow<DisplaySettings> = DisplaySettingsRepository(application).settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DisplaySettings())

    private val _saving = MutableStateFlow(false)
    /** True while the segment is being saved, so Create can't be pressed twice. */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _outcome = MutableStateFlow<CreateOutcome?>(null)
    val outcome: StateFlow<CreateOutcome?> = _outcome.asStateFlow()

    init {
        viewModelScope.launch {
            val points = app.activityRepository.pointsFor(rideId)
            val picker = withContext(Dispatchers.Default) { SegmentSelection(points) }
            // Coming back after Android stopped the app, the saved ends are still there.
            if (picker.canPick && savedStateHandle.get<Int>(START) == null) setEnds(picker.initialRange())
            selection.value = picker
        }
    }

    private fun setEnds(r: IntRange) {
        savedStateHandle[START] = r.first
        savedStateHandle[END] = r.last
    }

    /** From the slider. Ignored when both ends would meet. */
    fun setRange(start: Int, end: Int) {
        if (start < end) setEnds(start..end)
    }

    /**
     * A tap on the map within [radiusMeters] of the ride moves the nearer end there; see
     * [SegmentSelection.tappedIndex]. Searching every point runs off the main thread.
     */
    fun onMapTap(point: RoutePoint, radiusMeters: Double) {
        val picker = selection.value ?: return
        val current = range.value ?: return
        viewModelScope.launch {
            val moved = withContext(Dispatchers.Default) {
                picker.tappedIndex(point, current, radiusMeters)?.let { picker.moveNearerEnd(current, it) }
            }
            // Only if nothing else moved the ends meanwhile.
            if (moved != null && range.value == current) setEnds(moved)
        }
    }

    /** Saves the chosen part as [name]; the result arrives in [outcome]. */
    fun create(name: String) {
        val r = range.value ?: return
        if (_saving.value || _outcome.value == CreateOutcome.Created) return
        _saving.value = true
        viewModelScope.launch {
            val created = app.segmentRepository.create(name, rideId, r.first, r.last)
            _saving.value = false
            _outcome.value = if (created != null) CreateOutcome.Created else CreateOutcome.Failed
        }
    }

    /** The screen has shown a failure, so Create can be tried again. */
    fun failureShown() {
        if (_outcome.value == CreateOutcome.Failed) _outcome.value = null
    }

    private companion object {
        /** How long the ends must rest before the check runs. */
        const val CHECK_DELAY_MS = 250L
        const val START = "start"
        const val END = "end"
    }
}
