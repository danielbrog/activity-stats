package com.danielbrog.activitystats.location

import com.danielbrog.activitystats.stats.ActivityStats
import com.danielbrog.activitystats.stats.Geo
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
    /** Metres above the WGS84 ellipsoid, or null if the provider gave none. */
    val altitudeMeters: Double? = null,
    /**
     * Which stretch between pauses this fix belongs to: 0 until the first manual pause, then
     * one more after each Resume. Distance and time are never counted between two fixes with
     * different numbers, since whatever happened in between wasn't ridden.
     */
    val stretch: Int = 0,
)

/**
 * Distance from [a] to [b] along the ride: straight-line between them, or 0 when a pause
 * lies between them, since the gap wasn't ridden.
 */
fun legMeters(a: GpsData, b: GpsData): Double =
    if (a.stretch != b.stretch) 0.0 else Geo.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

data class TrackingUiState(
    val isTracking: Boolean = false,
    /** Newest first, capped to the most recent fixes. */
    val fixes: List<GpsData> = emptyList(),
    /**
     * Every kept position of this recording, oldest first, for drawing on the map. One list
     * per stretch between pauses, so the line breaks where you paused.
     */
    val route: List<List<RoutePoint>> = emptyList(),
    /** All fixes received this recording, including ones no longer in [fixes]. */
    val totalCount: Int = 0,
    /** Fixes the noise filter kept this recording. */
    val keptCount: Int = 0,
    /** System clock time when this recording started, or 0 if none has started yet. */
    val startedAtMillis: Long = 0L,
    /** Latest stats from the [StatsEngine]. */
    val stats: ActivityStats = ActivityStats(),
    val locationServicesOff: Boolean = false,
    /** When the current manual pause began, or null while not paused by hand. */
    val pausedAtMillis: Long? = null,
    /** Time spent in manual pauses that have ended, this recording. */
    val pausedMs: Long = 0L,
    /**
     * When the newest fix was made, on the phone's monotonic clock
     * ([android.os.SystemClock.elapsedRealtime]), or 0 before the first fix of this stretch
     * (at Start, and after Resume). Only for "how long since the last fix" on the live screen:
     * that clock never jumps when the phone's time is wrong or changed, but it restarts at
     * boot, so it is never saved.
     */
    val lastFixElapsedMs: Long = 0L,
) {
    /** How long since the last fix, given the monotonic clock now; null before the first one. */
    fun sinceLastFixMs(elapsedNowMs: Long): Long? =
        lastFixElapsedMs.takeIf { it > 0L }?.let { (elapsedNowMs - it).coerceAtLeast(0L) }

    /** True while the rider has pressed Pause and not yet Resume. */
    val isManuallyPaused: Boolean get() = pausedAtMillis != null

    /** All manually paused time up to [nowMillis], the current pause included. */
    fun pausedMsAt(nowMillis: Long): Long =
        pausedMs + (pausedAtMillis?.let { (nowMillis - it).coerceAtLeast(0L) } ?: 0L)
}

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
