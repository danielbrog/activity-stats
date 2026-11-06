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
    /** Only the time spent actually moving. */
    val movingMs: Long = 0L,
    /** Smoothed over the last few seconds; 0 when standing still. */
    val currentSpeedMps: Double = 0.0,
    /** Distance divided by moving time. */
    val avgSpeedMps: Double = 0.0,
    /** Highest smoothed current speed seen. */
    val maxSpeedMps: Double = 0.0,
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
    return engine.snapshot(end).copy(currentSpeedMps = 0.0)
}

/** Seconds per kilometre at [speedMps], or null below [minSpeedMps] where pace means nothing. */
fun paceSecondsPerKm(speedMps: Double, minSpeedMps: Double = 0.5): Double? =
    if (speedMps < minSpeedMps) null else 1_000.0 / speedMps

/**
 * Computes activity stats from GPS fixes (milestone M5). Plain Kotlin, unit-tested
 * without a device. One instance per recording. Only fixes the noise filter kept count.
 */
class StatsEngine(
    private val startedAtMillis: Long,
    /** A gap between kept fixes counts as moving time at or above this speed. */
    private val movingSpeedMps: Double = 0.5,
    /** Current speed is averaged over kept fixes from this long before "now". */
    private val currentSpeedWindowMs: Long = 5_000L,
) {
    private var last: GpsData? = null
    private val recent = ArrayDeque<GpsData>()
    private var distanceMeters = 0.0
    private var movingMs = 0L
    private var maxSpeedMps = 0.0

    fun add(fix: GpsData) {
        if (fix.status != FixStatus.Kept) return
        val prev = last
        if (prev != null) {
            val dtMs = fix.timeMillis - prev.timeMillis
            if (dtMs <= 0) return // out of order or duplicate; ignore
            val d = distanceBetween(prev, fix)
            distanceMeters += d
            if (d / (dtMs / 1_000.0) >= movingSpeedMps) movingMs += dtMs
        }
        last = fix
        recent.addLast(fix)
        maxSpeedMps = max(maxSpeedMps, currentSpeed(fix.timeMillis))
    }

    /** Stats as of [nowMillis], on the same clock as the fixes' timestamps. */
    fun snapshot(nowMillis: Long): ActivityStats = ActivityStats(
        distanceMeters = distanceMeters,
        elapsedMs = max(0L, nowMillis - startedAtMillis),
        movingMs = movingMs,
        currentSpeedMps = currentSpeed(nowMillis),
        avgSpeedMps = if (movingMs > 0) distanceMeters / (movingMs / 1_000.0) else 0.0,
        maxSpeedMps = maxSpeedMps,
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
