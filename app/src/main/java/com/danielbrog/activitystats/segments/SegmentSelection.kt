package com.danielbrog.activitystats.segments

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.location.legMeters
import com.danielbrog.activitystats.stats.Geo
import kotlin.math.abs

/** What the chosen part of a ride would make, for the create screen to show. */
sealed interface SelectionCheck {
    /** A manual pause falls inside: the gap wasn't ridden, so it can't be a segment. */
    data object CrossesPause : SelectionCheck

    /** Shorter than [SegmentSelection.MIN_LENGTH_METERS]. */
    data class TooShort(val distanceMeters: Double) : SelectionCheck

    /**
     * Can be a segment. [yourTimeMs] is this ride's time on it, or null when this ride wouldn't
     * count on it; [stoppedInside] then says whether a stop inside is why.
     */
    data class Ready(val distanceMeters: Double, val yourTimeMs: Long?, val stoppedInside: Boolean) : SelectionCheck
}

/**
 * Picking the part of a ride to make a segment from. Works on the ride's kept fixes in order
 * (the same indices [SegmentPath.fromRide] and the repository take), and chosen parts are
 * index ranges into them, start to end. Plain Kotlin, unit-tested.
 */
class SegmentSelection(fixes: List<GpsData>) {

    val kept: List<GpsData> = fixes.filter { it.status == FixStatus.Kept }.sortedBy { it.seq }

    /** Where each kept fix is, for drawing. Built once; parts of it are drawn as index ranges. */
    val points: List<RoutePoint> = kept.map { RoutePoint(it.latitude, it.longitude) }

    /** The whole ride as index ranges into [points], one per stretch between manual pauses. */
    val stretches: List<IntRange> = if (kept.isEmpty()) emptyList() else runs(kept.indices)

    /** Metres ridden up to each kept fix, never across a pause. */
    private val along = DoubleArray(kept.size).also { a ->
        for (k in 1 until kept.size) a[k] = a[k - 1] + legMeters(kept[k - 1], kept[k])
    }

    /** Whether the ride has enough points to pick a part of it at all. */
    val canPick: Boolean get() = kept.size >= 2

    /** Where the picker starts: the middle half of the ride, so both ends can move either way. */
    fun initialRange(): IntRange {
        val last = kept.lastIndex
        return (last / 4)..maxOf(last / 4 + 1, last * 3 / 4).coerceAtMost(last)
    }

    /** Metres ridden from the start of [range] to its end. */
    fun distanceMeters(range: IntRange): Double = along[range.last] - along[range.first]

    /** [range] split where a manual pause falls, as index ranges into [points]. Copies nothing. */
    fun runs(range: IntRange): List<IntRange> = buildList {
        var from = range.first
        for (k in range.first + 1..range.last) {
            if (kept[k].stretch != kept[k - 1].stretch) {
                add(from..k - 1)
                from = k
            }
        }
        add(from..range.last)
    }

    /**
     * The fix a tap at [p] means, or null if the tap was further than [radiusMeters] from the
     * ride. Where a ride passes the same place more than once (out and back, or a loop), the
     * fixes within reach form one run per pass: take the pass closest along the ride to an end
     * of [current] (the one the rider is adjusting), then its fix closest to the tap.
     */
    fun tappedIndex(p: RoutePoint, current: IntRange, radiusMeters: Double): Int? {
        fun toTap(k: Int) = Geo.distanceMeters(kept[k].latitude, kept[k].longitude, p.latitude, p.longitude)
        fun toEnds(k: Int) = minOf(abs(along[k] - along[current.first]), abs(along[k] - along[current.last]))
        val near = kept.indices.filter { toTap(it) <= radiusMeters }
        if (near.isEmpty()) return null
        val passes = mutableListOf(mutableListOf(near.first()))
        for (k in near.drop(1)) if (k == passes.last().last() + 1) passes.last() += k else passes += mutableListOf(k)
        val pass = passes.minBy { run -> run.minOf { toEnds(it) } }
        return pass.minBy { toTap(it) }
    }

    /**
     * [range] with the end nearer to fix [index] moved there: the start if [index] is before
     * it, the end if after, otherwise whichever is closer along the ride. Returns [range]
     * unchanged when the move would leave no part at all.
     */
    fun moveNearerEnd(range: IntRange, index: Int): IntRange {
        val moved = when {
            index <= range.first -> index..range.last
            index >= range.last -> range.first..index
            along[index] - along[range.first] <= along[range.last] - along[index] -> index..range.last
            else -> range.first..index
        }
        return if (moved.first < moved.last) moved else range
    }

    /** Whether [range] can be a segment, and how this ride would do on it. Runs the matcher, so not on the main thread. */
    fun check(range: IntRange): SelectionCheck {
        val first = range.first
        val last = range.last
        if ((first..last).any { kept[it].stretch != kept[first].stretch }) return SelectionCheck.CrossesPause
        val path = SegmentPath.fromRide(kept, first, last)
        if (path == null || path.distanceMeters < MIN_LENGTH_METERS) return SelectionCheck.TooShort(distanceMeters(range))

        // This ride's effort is the one that starts here: at the first fix, or when the rider
        // moved off it after a wait.
        val earliest = kept[first].timeMillis - TIME_SLACK_MS
        val latest = kept[first + 1].timeMillis + TIME_SLACK_MS
        val effort = SegmentMatcher.find(path, kept).firstOrNull { it.startedAtMillis in earliest..latest }
        // A wait on the first leg is left out of the time, so only legs after it count.
        val stopped = (first + 1 until last).any { SegmentMatcher.isStop(kept[it], kept[it + 1]) }
        return SelectionCheck.Ready(path.distanceMeters, effort?.elapsedMs, stoppedInside = stopped)
    }

    companion object {
        /**
         * The shortest segment that can be made. Each end can be anywhere within
         * [SegmentMatcher.END_RADIUS_METERS], so anything shorter mostly measures GPS error.
         */
        const val MIN_LENGTH_METERS = 100.0

        /** Room either side when picking out this ride's own effort, for interpolation. */
        private const val TIME_SLACK_MS = 1_000L
    }
}
