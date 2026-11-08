package com.danielbrog.activitystats.stats

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import kotlin.math.max

/**
 * Stats for one recording, in raw units: metres, metres per second, milliseconds.
 * Converting to km, km/h or min/km happens only on screen.
 */
data class ActivityStats(
    val distanceMeters: Double = 0.0,
    /** From Start until now, including stops. */
    val elapsedMs: Long = 0L,
    /** Time not auto-paused: the stops are left out. */
    val movingMs: Long = 0L,
    /** Smoothed over the last few seconds; 0 when standing still. */
    val currentSpeedMps: Double = 0.0,
    /** Distance divided by moving time. */
    val avgSpeedMps: Double = 0.0,
    /** Highest smoothed current speed seen. */
    val maxSpeedMps: Double = 0.0,
    /** True while auto-pause has decided you are stopped. */
    val isPaused: Boolean = false,
    /** The time these stats were taken at, on the fixes' clock; 0 before the first fix. */
    val asOfMillis: Long = 0L,
)

/**
 * Stats for a saved recording, rebuilt from its points with the same engine used live.
 * Used to finish activities cut off by a crash. "Now" is the last point's time.
 */
fun summarize(startedAtMillis: Long, points: List<GpsData>): ActivityStats {
    val engine = StatsEngine(startedAtMillis)
    val ordered = points.sortedBy { it.seq }
    ordered.forEach(engine::add)
    val end = ordered.maxOfOrNull { it.timeMillis } ?: startedAtMillis
    return engine.snapshot(end).copy(currentSpeedMps = 0.0, isPaused = false)
}

/** Seconds per kilometre at [speedMps], or null below [minSpeedMps] where pace means nothing. */
fun paceSecondsPerKm(speedMps: Double, minSpeedMps: Double = 0.5): Double? =
    if (speedMps < minSpeedMps) null else 1_000.0 / speedMps

/**
 * Computes activity stats from GPS fixes. Plain Kotlin, unit-tested without a device.
 * One instance per recording.
 *
 * Distance and speeds use only the fixes the noise filter kept. Moving time comes from
 * auto-pause: you are paused once every fix for [pauseAfterMs] is slower than
 * [movingSpeedMps], and resume on the first fix at or above it. The pause counts from the
 * first slow fix, so the time spent deciding isn't counted as moving. A gap of over
 * [maxGapMs] between fixes is never moving time.
 */
class StatsEngine(
    private val startedAtMillis: Long,
    /** At or above this speed you are moving. */
    private val movingSpeedMps: Double = 0.5,
    /** Current speed is averaged over kept fixes from this long before "now". */
    private val currentSpeedWindowMs: Long = 5_000L,
    /** How long you must stay slow before auto-pause kicks in. */
    private val pauseAfterMs: Long = 5_000L,
    /**
     * A gap longer than this between two fixes counts as stopped: another device's file
     * left out its own auto-paused time, or GPS was lost. The distance across it still counts.
     */
    private val maxGapMs: Long = 60_000L,
) {
    private var lastKept: GpsData? = null
    private val recent = ArrayDeque<GpsData>()
    private var distanceMeters = 0.0
    private var maxSpeedMps = 0.0

    // Auto-pause state.
    private var movingMs = 0L
    private var paused = false
    private var lastPauseCheckMillis: Long? = null
    /** Time of the first slow fix in the current slow spell, or null while moving. */
    private var slowSinceMillis: Long? = null
    /** Slow time not yet counted: kept if you speed up again, dropped if you pause. */
    private var pendingMs = 0L

    fun add(fix: GpsData) {
        if (fix.status == FixStatus.DroppedAccuracy) return // untrustworthy: no say in anything

        var derivedSpeedMps = 0.0
        if (fix.status == FixStatus.Kept) {
            val prev = lastKept
            if (prev != null) {
                val dtMs = fix.timeMillis - prev.timeMillis
                if (dtMs <= 0) return // out of order or duplicate; ignore
                val d = distanceBetween(prev, fix)
                distanceMeters += d
                derivedSpeedMps = d / (dtMs / 1_000.0)
            }
            lastKept = fix
            recent.addLast(fix)
            maxSpeedMps = max(maxSpeedMps, currentSpeed(fix.timeMillis))
        }

        val speedMps = when (fix.status) {
            // The filter already decided this fix is standing still.
            FixStatus.DroppedStationary -> 0.0
            // The GPS-reported speed reacts instantly; fall back to distance over time.
            else -> fix.speedMps?.toDouble() ?: derivedSpeedMps
        }
        updatePause(fix.timeMillis, moving = speedMps >= movingSpeedMps)
    }

    private fun updatePause(timeMillis: Long, moving: Boolean) {
        val previous = lastPauseCheckMillis
        if (previous != null && timeMillis <= previous) return
        lastPauseCheckMillis = timeMillis
        var sincePrevious = if (previous == null) 0L else timeMillis - previous
        if (sincePrevious > maxGapMs) {
            // A long gap with no fixes: none of it is moving time, nor any slow time before it.
            sincePrevious = 0L
            pendingMs = 0L
        }

        when {
            paused && moving -> {
                // Resume at once; the gap before this fix was still stopped time.
                paused = false
                slowSinceMillis = null
                pendingMs = 0L
            }
            paused -> Unit
            moving -> {
                // Still moving, or a short slow spell that never became a pause: all counts.
                movingMs += pendingMs + sincePrevious
                pendingMs = 0L
                slowSinceMillis = null
            }
            else -> {
                val slowSince = slowSinceMillis
                if (slowSince == null) {
                    // First slow fix: you were moving until now.
                    slowSinceMillis = timeMillis
                    movingMs += sincePrevious
                } else {
                    pendingMs += sincePrevious
                    if (timeMillis - slowSince >= pauseAfterMs) {
                        paused = true
                        pendingMs = 0L
                    }
                }
            }
        }
    }

    /** Stats as of [nowMillis], on the same clock as the fixes' timestamps. */
    fun snapshot(nowMillis: Long): ActivityStats = ActivityStats(
        distanceMeters = distanceMeters,
        elapsedMs = max(0L, nowMillis - startedAtMillis),
        movingMs = movingMs,
        currentSpeedMps = currentSpeed(nowMillis),
        avgSpeedMps = if (movingMs > 0) distanceMeters / (movingMs / 1_000.0) else 0.0,
        maxSpeedMps = maxSpeedMps,
        isPaused = paused,
        asOfMillis = if (lastPauseCheckMillis == null) 0L else nowMillis,
    )

    private fun currentSpeed(nowMillis: Long): Double {
        while (recent.isNotEmpty() && recent.first().timeMillis < nowMillis - currentSpeedWindowMs) {
            recent.removeFirst()
        }
        if (recent.size < 2) return 0.0
        var d = 0.0
        for (i in 1 until recent.size) d += distanceBetween(recent[i - 1], recent[i])
        val spanMs = recent.last().timeMillis - recent.first().timeMillis
        return if (spanMs > 0) d / (spanMs / 1_000.0) else 0.0
    }

    private fun distanceBetween(a: GpsData, b: GpsData) =
        Geo.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
}

/**
 * The big Time on the Record screen. With auto-pause on it is moving time, which stops
 * while paused; with it off it is elapsed time. While moving it keeps ticking between fixes.
 */
fun displayTimeMs(
    autoPause: Boolean,
    isTracking: Boolean,
    stats: ActivityStats,
    startedAtMillis: Long,
    nowMillis: Long,
): Long = when {
    !autoPause && isTracking -> max(0L, nowMillis - startedAtMillis)
    !autoPause -> stats.elapsedMs
    isTracking && !stats.isPaused && stats.asOfMillis > 0L ->
        stats.movingMs + max(0L, nowMillis - stats.asOfMillis)
    else -> stats.movingMs
}
