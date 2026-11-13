package com.danielbrog.activitystats.stats

import com.danielbrog.activitystats.location.GpsData

/**
 * One split: [number] counts from 1; [distanceMeters] is a full km or mile except for the
 * last, shorter one. [elevationChangeMeters] is the net height change from its start to its
 * end, or null when the ride has no altitude.
 */
data class Split(
    val number: Int,
    val distanceMeters: Double,
    val movingMs: Long,
    val elevationChangeMeters: Double?,
    val isPartial: Boolean,
) {
    val avgSpeedMps: Double get() = if (movingMs > 0) distanceMeters / (movingMs / 1_000.0) else 0.0
}

/**
 * Cuts a ride into km or mile splits. Plain Kotlin, unit-tested.
 *
 * The saved points are replayed through [StatsEngine], the same engine used while recording,
 * so each split's time is moving time and the splits add up exactly to the ride's distance
 * and moving time. Each cut falls between two fixes, with its moving time interpolated by
 * distance, so every full split is exactly one km or mile.
 */
object Splits {

    /** Rides shorter than this get no splits. */
    const val MIN_RIDE_METERS = 100.0
    /** A last piece shorter than this is too small for its own row and joins the split before. */
    const val MIN_LAST_METERS = 10.0

    fun of(fixes: List<GpsData>, unit: DistanceUnit): List<Split> {
        val splitMeters = if (unit == DistanceUnit.Km) 1_000.0 else Format.METERS_PER_MILE
        val ordered = fixes.sortedBy { it.seq }
        if (ordered.isEmpty()) return emptyList()

        // Distance and moving time after each fix, as the engine saw them.
        val engine = StatsEngine(ordered.first().timeMillis)
        val distance = DoubleArray(ordered.size + 1)
        val moving = LongArray(ordered.size + 1)
        ordered.forEachIndexed { i, fix ->
            engine.add(fix)
            val s = engine.snapshot(fix.timeMillis)
            distance[i + 1] = s.distanceMeters
            moving[i + 1] = s.movingMs
        }
        val total = distance.last()
        if (total < MIN_RIDE_METERS) return emptyList()

        val height = heightAlongRide(ordered)

        // Moving time at an exact distance along the ride, interpolated between fixes.
        var cursor = 0
        fun movingAt(d: Double): Double {
            while (cursor + 1 < distance.size && distance[cursor + 1] < d) cursor++
            val next = (cursor + 1).coerceAtMost(distance.size - 1)
            val span = distance[next] - distance[cursor]
            val fraction = if (span > 0.0) ((d - distance[cursor]) / span).coerceIn(0.0, 1.0) else 1.0
            return moving[cursor] + (moving[next] - moving[cursor]) * fraction
        }

        val splits = mutableListOf<Split>()
        var start = 0.0
        var startMoving = 0.0
        var number = 1
        while (start < total) {
            val nominal = start + splitMeters
            // A sliver left at the very end joins this split instead of becoming its own row.
            val end = if (total - nominal < MIN_LAST_METERS) total else nominal
            val partial = end < nominal
            // The last split ends at the ride's end, so its time is the rest of the moving time.
            val endMoving = if (end >= total) moving.last().toDouble() else movingAt(end)
            splits += Split(
                number = number++,
                distanceMeters = end - start,
                movingMs = Math.round(endMoving - startMoving),
                elevationChangeMeters = height?.let { it(end) - it(start) },
                isPartial = partial,
            )
            start = end
            startMoving = endMoving
        }
        return splits
    }

    /**
     * The fastest split, as the table shows it in bold (the first one if speeds tie), or
     * null with no splits.
     */
    fun fastest(splits: List<Split>): Split? = splits.maxByOrNull { it.avgSpeedMps }

    /**
     * The line shown while the table is closed: "12.43 km · fastest: km 4, 32.1 km/h".
     * The last, shorter split is named by its length: "fastest: last 0.43 km, …".
     */
    fun summary(splits: List<Split>, unit: DistanceUnit, display: SpeedDisplay): String {
        val best = fastest(splits) ?: return ""
        val unitWord = Format.distanceLabel(unit)
        val which = if (best.isPartial) {
            "last ${Format.distanceNumber(best.distanceMeters, unit)} $unitWord"
        } else {
            "$unitWord ${best.number}"
        }
        val total = Format.distance(splits.sumOf { it.distanceMeters }, unit)
        return "$total · fastest: $which, ${Format.speedOrPace(best.avgSpeedMps, unit, display)}"
    }

    /** Smoothed height at any distance along the ride, or null with no altitude data. */
    private fun heightAlongRide(fixes: List<GpsData>): ((Double) -> Double)? {
        val samples = ElevationSeries.fromFixes(fixes)
        if (samples.isEmpty()) return null
        val smoothed = ElevationGain.smooth(samples)
        val at = samples.map { it.distanceMeters }
        return { d ->
            val i = at.binarySearch(d).let { if (it >= 0) it else -it - 1 } // first index at or past d
            when {
                i <= 0 -> smoothed.first()
                i >= at.size -> smoothed.last()
                else -> {
                    val span = at[i] - at[i - 1]
                    val f = if (span > 0.0) (d - at[i - 1]) / span else 1.0
                    smoothed[i - 1] + (smoothed[i] - smoothed[i - 1]) * f
                }
            }
        }
    }
}
