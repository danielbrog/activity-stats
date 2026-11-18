package com.danielbrog.activitystats.stats

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.location.legMeters

/** One point on the elevation chart: how far into the ride, and how high. Raw units. */
data class ElevationSample(val distanceMeters: Double, val altitudeMeters: Double)

/**
 * Builds the elevation-over-distance data for a ride's chart. Plain Kotlin, unit-tested.
 * Phone altitude is noisy, so long rides are grouped into slices of distance and averaged, which
 * smooths most of it out.
 */
object ElevationSeries {

    /**
     * One sample per kept fix that has an altitude, in order. Distance is counted over every
     * kept fix, so it lines up with the speed chart even where altitude is missing.
     */
    fun fromFixes(fixes: List<GpsData>): List<ElevationSample> {
        val kept = fixes.filter { it.status == FixStatus.Kept }.sortedBy { it.seq }
        val samples = ArrayList<ElevationSample>(kept.size)
        var distance = 0.0
        var previous: GpsData? = null
        for (fix in kept) {
            val prev = previous
            if (prev != null) {
                distance += legMeters(prev, fix)
            }
            fix.altitudeMeters?.let { samples += ElevationSample(distance, it) }
            previous = fix
        }
        return samples
    }

    fun downsample(samples: List<ElevationSample>, maxPoints: Int = 200): List<ElevationSample> =
        averageByDistance(samples, maxPoints, { it.distanceMeters }, { it.altitudeMeters }, ::ElevationSample)
}
