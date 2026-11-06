package com.danielbrog.activitystats.location

import com.danielbrog.activitystats.stats.ActivityStats
import com.danielbrog.activitystats.stats.StatsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One GPS reading as delivered by the fused location provider. */
data class GpsData(
    /** 1-based order of arrival within the current recording. */
    val seq: Int,
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy radius in metres, or null if the provider gave none. */
    val accuracyMeters: Float?,
    /** Speed in metres per second, or null if the provider gave none. */
    val speedMps: Float?,
    /** The noise filter's decision; see [GpsFilter]. */
    val status: FixStatus = FixStatus.Kept,
)

data class TrackingUiState(
    val isTracking: Boolean = false,
    /** Newest first, capped to the most recent fixes. */
    val fixes: List<GpsData> = emptyList(),
    /** All fixes received this recording, including ones no longer in [fixes]. */
    val totalCount: Int = 0,
    /** Fixes the noise filter kept this recording. */
    val keptCount: Int = 0,
    /** System clock time when this recording started, or 0 if none has started yet. */
    val startedAtMillis: Long = 0L,
    /** Latest stats from the [StatsEngine]. */
    val stats: ActivityStats = ActivityStats(),
    val locationServicesOff: Boolean = false,
)

/**
 * App-wide live tracking state. [LocationTrackingService] writes it; screens read it,
 * so they show the current recording whether or not they were open the whole time.
 * Lives in memory only; saving comes with Room (M7).
 */
object TrackingState {
    private val _state = MutableStateFlow(TrackingUiState())
    val state: StateFlow<TrackingUiState> = _state.asStateFlow()

    internal fun update(transform: (TrackingUiState) -> TrackingUiState) = _state.update(transform)
}
