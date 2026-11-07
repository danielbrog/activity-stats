package com.danielbrog.activitystats.stats

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** One point on the speed chart: how far into the ride, and how fast. Raw units. */
data class SpeedSample(val distanceMeters: Double, val speedMps: Double)

/** Builds the speed-over-distance data for a ride's chart. Plain Kotlin, unit-tested. */
object SpeedSeries {

    /**
     * One sample per kept fix, in order, with the distance ridden so far. Speed is the
     * GPS-reported speed (the most accurate), or distance over time from the previous kept fix.
     */
    fun fromFixes(fixes: List<GpsData>): List<SpeedSample> {
        val kept = fixes.filter { it.status == FixStatus.Kept }.sortedBy { it.seq }
        val samples = ArrayList<SpeedSample>(kept.size)
        var distance = 0.0
        var previous: GpsData? = null
        for (fix in kept) {
            var derived = 0.0
            val prev = previous
            if (prev != null) {
                val d = Geo.distanceMeters(prev.latitude, prev.longitude, fix.latitude, fix.longitude)
                distance += d
                val dtMs = fix.timeMillis - prev.timeMillis
                if (dtMs > 0) derived = d / (dtMs / 1_000.0)
            }
            samples += SpeedSample(distance, fix.speedMps?.toDouble() ?: derived)
            previous = fix
        }
        return samples
    }

    /**
     * Groups [samples] into at most [maxPoints] equal stretches of distance, each with the
     * average distance and speed of the samples in it. Smooths GPS jitter and keeps drawing
     * fast. Returns the samples unchanged when there are few enough.
     */
    fun downsample(samples: List<SpeedSample>, maxPoints: Int = 200): List<SpeedSample> {
        if (samples.size <= maxPoints) return samples
        val start = samples.first().distanceMeters
        val span = samples.last().distanceMeters - start
        if (span <= 0.0) return samples.take(maxPoints)

        val width = span / maxPoints
        val sumDistance = DoubleArray(maxPoints)
        val sumSpeed = DoubleArray(maxPoints)
        val count = IntArray(maxPoints)
        for (s in samples) {
            val bucket = ((s.distanceMeters - start) / width).toInt().coerceIn(0, maxPoints - 1)
            sumDistance[bucket] += s.distanceMeters
            sumSpeed[bucket] += s.speedMps
            count[bucket]++
        }
        return (0 until maxPoints)
            .filter { count[it] > 0 }
            .map { SpeedSample(sumDistance[it] / count[it], sumSpeed[it] / count[it]) }
    }

    /**
     * Round axis values from 0 up to at least [max], in steps of 1, 2, 2.5 or 5 times a power
     * of ten, aiming for about [targetCount] labels. For example 34 gives 0, 10, 20, 30, 40.
     */
    fun niceTicks(max: Double, targetCount: Int = 5): List<Double> {
        if (max <= 0.0 || max.isNaN()) return listOf(0.0)
        val rawStep = max / (targetCount - 1).coerceAtLeast(1)
        val magnitude = 10.0.pow(floor(log10(rawStep)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0)
            .map { it * magnitude }
            .first { it >= rawStep }
        val top = ceil(max / step - 1e-9) * step
        val ticks = mutableListOf<Double>()
        var value = 0.0
        while (value <= top + step / 2) {
            ticks += value
            value += step
        }
        return ticks
    }
}
