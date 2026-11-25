package com.danielbrog.activitystats.stats

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.location.legMeters

/**
 * The set distances for best efforts. [key] is what the database stores, so the names here
 * can change without touching saved data. Km efforts show in km mode, mile efforts in miles.
 */
enum class Effort(val key: String, val meters: Double, val unit: DistanceUnit, val label: String) {
    Km5("5km", 5_000.0, DistanceUnit.Km, "5 km"),
    Km10("10km", 10_000.0, DistanceUnit.Km, "10 km"),
    Km20("20km", 20_000.0, DistanceUnit.Km, "20 km"),
    Km40("40km", 40_000.0, DistanceUnit.Km, "40 km"),
    Mi5("5mi", 5 * Format.METERS_PER_MILE, DistanceUnit.Mi, "5 mi"),
    Mi10("10mi", 10 * Format.METERS_PER_MILE, DistanceUnit.Mi, "10 mi"),
    Mi25("25mi", 25 * Format.METERS_PER_MILE, DistanceUnit.Mi, "25 mi"),
    ;

    companion object {
        fun fromKey(key: String): Effort? = entries.firstOrNull { it.key == key }
        fun forUnit(unit: DistanceUnit): List<Effort> = entries.filter { it.unit == unit }
    }
}

/**
 * Finds a ride's fastest time over each [Effort] distance, anywhere within the ride, like
 * Strava's best efforts. Plain Kotlin, unit-tested.
 *
 * Uses the kept fixes only. The time is clock time between the two ends of the effort's
 * window, so stops inside it count, manual pauses included. Ground covered while paused by
 * hand isn't counted, so a lift in a car can never make a best effort. The start is placed
 * exactly [Effort.meters] back from each fix, between two fixes, with its time interpolated,
 * so the window is the full distance and no more.
 */
object BestEfforts {

    /** Fastest milliseconds for each effort, or null when the ride is shorter than it. */
    fun find(fixes: List<GpsData>, efforts: List<Effort> = Effort.entries): Map<Effort, Long?> {
        val kept = fixes.filter { it.status == FixStatus.Kept }.sortedBy { it.seq }
        val n = kept.size
        val distance = DoubleArray(n)
        for (i in 1 until n) {
            val a = kept[i - 1]
            val b = kept[i]
            distance[i] = distance[i - 1] + legMeters(a, b)
        }
        return efforts.associateWith { fastest(kept, distance, it.meters) }
    }

    private fun fastest(kept: List<GpsData>, distance: DoubleArray, target: Double): Long? {
        var best: Double? = null
        var i = 0 // the last fix at least `target` metres before fix j
        for (j in kept.indices) {
            if (distance[j] - distance[0] < target) continue
            while (i + 1 < j && distance[j] - distance[i + 1] >= target) i++
            // The exact start lies between fix i and fix i + 1.
            val startAt = distance[j] - target
            val leg = distance[i + 1] - distance[i]
            val fraction = if (leg > 0.0) (startAt - distance[i]) / leg else 0.0
            val t0 = kept[i].timeMillis.toDouble()
            val startTime = t0 + (kept[i + 1].timeMillis - t0) * fraction
            val elapsed = kept[j].timeMillis - startTime
            if (best == null || elapsed < best) best = elapsed
        }
        return best?.let { Math.round(it) }
    }
}
