package com.danielbrog.activitystats.location

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
)

data class TrackingUiState(
    val isTracking: Boolean = false,
    /** Newest first. */
    val fixes: List<GpsData> = emptyList(),
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
