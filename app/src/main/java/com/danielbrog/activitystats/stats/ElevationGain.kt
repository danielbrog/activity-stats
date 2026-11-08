package com.danielbrog.activitystats.stats

import com.danielbrog.activitystats.location.GpsData

/**
 * Total climbing in a ride, in metres. Plain Kotlin, unit-tested.
 *
 * Phone altitude jumps several metres up and down even on a flat road, so adding up every
 * rise would turn a flat ride into a hilly one. Two steps keep it honest:
 * 1. Smooth: each altitude becomes the average of those within [windowMeters] of road
 *    around it, which removes most jitter but keeps real hills.
 * 2. Hysteresis: a climb only counts once the road has risen [thresholdMeters] above its
 *    last low point, and a descent only ends a climb once it drops that far below the top.
 *    Small bumps never add up; a real climb counts in full, from its bottom to its top.
 *
 * Uses the kept fixes with an altitude (via [ElevationSeries.fromFixes]). The GPS altitude
 * offset from sea level doesn't matter here, since only differences in height count.
 */
object ElevationGain {

    const val WINDOW_METERS = 50.0
    const val THRESHOLD_METERS = 5.0

    fun of(
        fixes: List<GpsData>,
        windowMeters: Double = WINDOW_METERS,
        thresholdMeters: Double = THRESHOLD_METERS,
    ): Double = fromSamples(ElevationSeries.fromFixes(fixes), windowMeters, thresholdMeters)

    fun fromSamples(
        samples: List<ElevationSample>,
        windowMeters: Double = WINDOW_METERS,
        thresholdMeters: Double = THRESHOLD_METERS,
    ): Double {
        if (samples.size < 2) return 0.0
        return climbing(smooth(samples, windowMeters / 2), thresholdMeters)
    }

    /**
     * Centred moving average over distance, ±[halfWindow] metres, using a running sum. One
     * height per sample, in the same order. Also used by splits for their height change.
     */
    internal fun smooth(samples: List<ElevationSample>, halfWindow: Double = WINDOW_METERS / 2): DoubleArray {
        val n = samples.size
        val out = DoubleArray(n)
        var lo = 0
        var hi = 0 // exclusive
        var sum = 0.0
        for (i in 0 until n) {
            val centre = samples[i].distanceMeters
            while (hi < n && samples[hi].distanceMeters <= centre + halfWindow) sum += samples[hi++].altitudeMeters
            while (samples[lo].distanceMeters < centre - halfWindow) sum -= samples[lo++].altitudeMeters
            out[i] = sum / (hi - lo)
        }
        return out
    }

    /** Adds up each climb from its low point to its top, ignoring changes under [threshold]. */
    private fun climbing(heights: DoubleArray, threshold: Double): Double {
        var gain = 0.0
        var climbing = false
        var bottom = heights[0] // where the current climb started
        var extreme = heights[0] // highest point while climbing, lowest while not
        for (h in heights) {
            if (climbing) {
                if (h > extreme) {
                    extreme = h
                } else if (extreme - h >= threshold) {
                    gain += extreme - bottom
                    climbing = false
                    extreme = h
                }
            } else {
                if (h < extreme) {
                    extreme = h
                } else if (h - extreme >= threshold) {
                    bottom = extreme
                    climbing = true
                    extreme = h
                }
            }
        }
        if (climbing) gain += extreme - bottom
        return gain
    }
}
