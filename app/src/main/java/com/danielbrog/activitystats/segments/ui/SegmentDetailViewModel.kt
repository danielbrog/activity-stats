package com.danielbrog.activitystats.segments.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.danielbrog.activitystats.ActivityStatsApp
import com.danielbrog.activitystats.data.SegmentEffortWithRide
import com.danielbrog.activitystats.data.SegmentEntity
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.navigation.SegmentDetailRoute
import com.danielbrog.activitystats.segments.Polyline
import com.danielbrog.activitystats.settings.DisplaySettingsRepository
import com.danielbrog.activitystats.stats.DistanceUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SegmentDetailState {
    data object Loading : SegmentDetailState

    /** Deleted, or never on this phone. */
    data object NotFound : SegmentDetailState

    data class Loaded(
        val segment: SegmentEntity,
        /** The segment's line, decoded for the map. */
        val line: List<RoutePoint>,
        /** From rides not deleted, fastest first, so the first is the best. */
        val efforts: List<SegmentEffortWithRide>,
        /** True while a new segment's efforts are still being found. */
        val isFinding: Boolean,
    ) : SegmentDetailState
}

/** One segment and its efforts, by the id in the navigation route. Updates itself. */
class SegmentDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = (application as ActivityStatsApp).segmentRepository
    private val rides = (application as ActivityStatsApp).activityRepository
    val segmentId: String = savedStateHandle.toRoute<SegmentDetailRoute>().id

    /** The segment with its line decoded once, not on every change to its efforts. */
    private val segmentWithLine = repository.segment(segmentId)
        .distinctUntilChanged()
        .map { segment -> segment?.let { it to Polyline.decode(it.path) } }
        .flowOn(Dispatchers.Default)

    val state: StateFlow<SegmentDetailState> =
        combine(segmentWithLine, repository.effortsFor(segmentId), repository.finding) { loaded, efforts, finding ->
            val (segment, line) = loaded ?: return@combine SegmentDetailState.NotFound
            SegmentDetailState.Loaded(segment, line, efforts, isFinding = segmentId in finding)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SegmentDetailState.Loading)

    val unit: StateFlow<DistanceUnit> = DisplaySettingsRepository(application).settings
        .map { it.unit }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DistanceUnit.Km)

    /** Saves a new name; ignored if it is blank after cleaning. */
    fun rename(name: String) {
        viewModelScope.launch { repository.rename(segmentId, name) }
    }

    /** Soft-deletes the segment, then calls [onDeleted] so the screen can close. */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(segmentId)
            onDeleted()
        }
    }

    /** Undoes deleting a ride opened from this screen; its effort comes back with it. */
    fun restoreRide(id: String) {
        viewModelScope.launch { rides.restore(id) }
    }
}
