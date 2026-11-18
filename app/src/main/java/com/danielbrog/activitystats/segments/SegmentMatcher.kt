package com.danielbrog.activitystats.segments

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.location.RouteBounds
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.location.legMeters
import com.danielbrog.activitystats.stats.Geo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** One time a ride covered a segment, without stopping. [occurrence] counts from 0 within the ride. */
data class SegmentEffort(
    val occurrence: Int,
    /** When the ride left the segment's start, interpolated between fixes. */
    val startedAtMillis: Long,
    val elapsedMs: Long,
)

/**
 * Finds every time a ride covered a segment, in the segment's direction and in one go. Plain
 * Kotlin, unit-tested. Uses only the fixes the noise filter kept.
 *
 * A ride covers a segment when it passes within [END_RADIUS_METERS] of the start, then later
 * within the same of the end, and between the two:
 * - it passes the points a quarter, half and three quarters of the way along, in that order,
 *   so a loop ridden the other way round doesn't count;
 * - at least [MIN_ON_PATH_FRACTION] of its fixes lie within [ON_PATH_METERS] of the segment's
 *   line, so a different road that happens to join the same ends doesn't count;
 * - the distance it rode is within [DISTANCE_TOLERANCE] of the segment's (and at least
 *   [MIN_DISTANCE_TOLERANCE_METERS]), ruling out short cuts and detours; and
 * - it never stopped: no manual pause, and no stop of [STOP_AFTER_MS] or more (the same rule
 *   auto-pause uses). A wait at the start or just after the end is left out of the time.
 *
 * Both distances are measured on lines simplified the same way ([SegmentPath.lineMeters]),
 * so GPS wobble on either ride doesn't make them disagree.
 *
 * After a match the search carries on from its end, so a ride that laps a segment gets an
 * effort for each lap.
 */
object SegmentMatcher {

    const val END_RADIUS_METERS = 25.0
    const val ON_PATH_METERS = 25.0
    const val MIN_ON_PATH_FRACTION = 0.9
    const val DISTANCE_TOLERANCE = 0.10
    /** A short segment still gets this much leeway, since each end can be anywhere in its radius. */
    const val MIN_DISTANCE_TOLERANCE_METERS = 25.0
    /** Slower than [STOP_SPEED_MPS] for this long between two fixes is a stop, as for auto-pause. */
    const val STOP_AFTER_MS = 5_000L
    const val STOP_SPEED_MPS = 0.5

    fun find(segment: SegmentPath, fixes: List<GpsData>): List<SegmentEffort> {
        val kept = fixes.filter { it.status == FixStatus.Kept }.sortedBy { it.seq }
        if (kept.size < 2) return emptyList()
        val rideBounds = RouteBounds.of(kept.map { RoutePoint(it.latitude, it.longitude) }) ?: return emptyList()
        if (!rideBounds.overlaps(segment.bounds.expandedBy(END_RADIUS_METERS))) return emptyList()

        val ride = Ride(kept)
        val toStart = DoubleArray(kept.size) { ride.distanceTo(it, segment.start) }
        val toEnd = DoubleArray(kept.size) { ride.distanceTo(it, segment.end) }
        val match = Match(segment)

        val efforts = mutableListOf<SegmentEffort>()
        var i = 0
        while (i < kept.size) {
            if (toStart[i] > END_RADIUS_METERS) {
                i++
                continue
            }
            // One pass near the start: take the fix closest to it.
            val runEnd = runEnd(i, toStart)
            val start = ride.crossing(argMin(i, runEnd, toStart), segment.start, isStart = true)
            val end = findEnd(ride, start, toEnd, match)
            if (end == null) {
                i = runEnd + 1
                continue
            }
            efforts += SegmentEffort(
                occurrence = efforts.size,
                startedAtMillis = start.timeMillis.roundToLong(),
                elapsedMs = (end.timeMillis - start.timeMillis).roundToLong(),
            )
            i = end.toIndex + 1
        }
        return efforts
    }

    /** The segment's values the checks use, worked out once per ride. */
    private class Match(val segment: SegmentPath) {
        val length = segment.lineMeters
        val tolerance = max(length * DISTANCE_TOLERANCE, MIN_DISTANCE_TOLERANCE_METERS)
        val pathLats = DoubleArray(segment.points.size) { segment.points[it].latitude }
        val pathLons = DoubleArray(segment.points.size) { segment.points[it].longitude }
    }

    /** The first pass near the end, after [start], that makes a valid effort; or null. */
    private fun findEnd(ride: Ride, start: Crossing, toEnd: DoubleArray, match: Match): Crossing? {
        // A search window only; the checks below decide. Not before the shortest the effort
        // could be, so a segment that ends where it starts (a loop) isn't matched straight
        // away. The ride's raw distance runs longer than its simplified one, hence the room.
        val minAlong = start.along + match.length - match.tolerance - END_RADIUS_METERS
        val maxAlong = start.along + 2 * (match.length + match.tolerance) + END_RADIUS_METERS
        var j = start.toIndex
        while (j < ride.size && ride.along[j] <= maxAlong) {
            if (toEnd[j] > END_RADIUS_METERS || ride.along[j] < minAlong) {
                j++
                continue
            }
            val runEnd = runEnd(j, toEnd)
            val end = ride.crossing(argMin(j, runEnd, toEnd), match.segment.end, isStart = false)
            if (isEffort(ride, start, end, match)) return end
            j = runEnd + 1
        }
        return null
    }

    private fun isEffort(ride: Ride, start: Crossing, end: Crossing, match: Match): Boolean {
        if (ride.pausedOrStoppedBetween(start, end)) return false
        if (abs(ride.simplifiedMeters(start, end) - match.length) > match.tolerance) return false
        if (!followsPath(ride, start.toIndex, end.fromIndex, match)) return false
        return passesInOrder(ride, start.fromIndex, end.toIndex, match.segment.checkpoints)
    }

    /**
     * Whether enough of the ride's fixes from [from] to [to] lie on the segment's line. One
     * stray fix is always allowed, so a short segment with few fixes isn't failed by it.
     */
    private fun followsPath(ride: Ride, from: Int, to: Int, match: Match): Boolean {
        if (to < from) return true // no fixes between the two ends: nothing went astray
        val count = to - from + 1
        var offPath = 0
        for (k in from..to) {
            val f = ride.fixes[k]
            if (Geo.distanceToPathMeters(f.latitude, f.longitude, match.pathLats, match.pathLons) > ON_PATH_METERS) offPath++
        }
        return offPath <= max(1, (count * (1 - MIN_ON_PATH_FRACTION)).toInt())
    }

    /**
     * Whether the ride, between fixes [from] and [to], passes within [ON_PATH_METERS] of each
     * of [checkpoints] in order. Measured against the legs between fixes, not the fixes
     * themselves, so a ride with fixes far apart (an imported file) still passes.
     */
    private fun passesInOrder(ride: Ride, from: Int, to: Int, checkpoints: List<RoutePoint>): Boolean {
        var k = from
        for (cp in checkpoints) {
            while (k < to && ride.distanceToLeg(k, cp) > ON_PATH_METERS) k++
            if (k >= to) return false
        }
        return true
    }

    /** The last index of the run of fixes from [from] that are all within the end radius. */
    private fun runEnd(from: Int, distances: DoubleArray): Int {
        var k = from
        while (k + 1 < distances.size && distances[k + 1] <= END_RADIUS_METERS) k++
        return k
    }

    private fun argMin(from: Int, to: Int, values: DoubleArray): Int = (from..to).minBy { values[it] }

    /** Where the ride passed closest to a point, between two fixes. */
    private class Crossing(
        /** The fixes either side of the closest place (the same one when it is a fix itself). */
        val fromIndex: Int,
        val toIndex: Int,
        /** Metres along the ride at that place. */
        val along: Double,
        val timeMillis: Double,
        val latitude: Double,
        val longitude: Double,
    )

    /** A ride's kept fixes with the distance ridden up to each, for fast lookups. */
    private class Ride(val fixes: List<GpsData>) {
        val size = fixes.size
        val along = DoubleArray(size).also { a ->
            for (k in 1 until size) a[k] = a[k - 1] + legMeters(fixes[k - 1], fixes[k])
        }

        fun distanceTo(k: Int, p: RoutePoint) =
            Geo.distanceMeters(fixes[k].latitude, fixes[k].longitude, p.latitude, p.longitude)

        /** How far [p] is from the leg from fix [k] to the next. */
        fun distanceToLeg(k: Int, p: RoutePoint) = Geo.onLeg(
            p.latitude, p.longitude,
            fixes[k].latitude, fixes[k].longitude, fixes[k + 1].latitude, fixes[k + 1].longitude,
        ).distanceMeters

        /**
         * Whether the leg from fix [k] to the next is a stop: slow for [STOP_AFTER_MS] or more.
         * The noise filter drops fixes while standing still, so a stop shows up as one long leg
         * from where the rider stopped to the first fix after they moved off.
         */
        fun isStop(k: Int): Boolean {
            val ms = fixes[k + 1].timeMillis - fixes[k].timeMillis
            return ms >= STOP_AFTER_MS && legMeters(fixes[k], fixes[k + 1]) / (ms / 1_000.0) < STOP_SPEED_MPS
        }

        /** A manual pause anywhere in the effort, or a stop on a leg wholly inside it. */
        fun pausedOrStoppedBetween(start: Crossing, end: Crossing): Boolean {
            val stretch = fixes[start.fromIndex].stretch
            if ((start.fromIndex..end.toIndex).any { fixes[it].stretch != stretch }) return true
            return (start.toIndex until end.fromIndex).any { isStop(it) }
        }

        /**
         * The distance ridden from [start] to [end], measured on the line through them
         * simplified as a segment's is, so wobble doesn't add to it.
         */
        fun simplifiedMeters(start: Crossing, end: Crossing): Double {
            val line = buildList {
                add(RoutePoint(start.latitude, start.longitude))
                for (k in start.toIndex..end.fromIndex) add(RoutePoint(fixes[k].latitude, fixes[k].longitude))
                add(RoutePoint(end.latitude, end.longitude))
            }
            return SegmentPath.lengthOf(Simplify.path(line, SegmentPath.SIMPLIFY_TOLERANCE_METERS))
        }

        /**
         * Where the ride passed closest to [p] around fix [k]: on the leg before or after it,
         * never across a pause, with distance and position interpolated along that leg.
         *
         * Time is too, except on a stop, where the rider waited at the leg's first fix: a start
         * there counts from when they moved off, an end from when they arrived. So a wait at
         * the lights where a segment starts isn't counted.
         */
        fun crossing(k: Int, p: RoutePoint, isStart: Boolean): Crossing {
            val f = fixes[k]
            var best = Crossing(k, k, along[k], f.timeMillis.toDouble(), f.latitude, f.longitude)
            var bestMeters = distanceTo(k, p)
            for ((a, b) in listOf(k - 1 to k, k to k + 1)) {
                if (a < 0 || b >= size || fixes[a].stretch != fixes[b].stretch) continue
                val fa = fixes[a]
                val fb = fixes[b]
                val on = Geo.onLeg(p.latitude, p.longitude, fa.latitude, fa.longitude, fb.latitude, fb.longitude)
                if (on.distanceMeters < bestMeters) {
                    bestMeters = on.distanceMeters
                    val t = on.fraction
                    val time = when {
                        !isStop(a) -> fa.timeMillis + t * (fb.timeMillis - fa.timeMillis)
                        isStart -> fb.timeMillis.toDouble()
                        else -> fa.timeMillis.toDouble()
                    }
                    best = Crossing(
                        fromIndex = a,
                        toIndex = b,
                        along = along[a] + t * (along[b] - along[a]),
                        timeMillis = time,
                        latitude = fa.latitude + t * (fb.latitude - fa.latitude),
                        longitude = fa.longitude + t * (fb.longitude - fa.longitude),
                    )
                }
            }
            // Closest at a fix where the rider then waited: the start is when they moved off.
            if (isStart && best.fromIndex == best.toIndex && k + 1 < size &&
                fixes[k + 1].stretch == f.stretch && isStop(k)
            ) {
                best = Crossing(k, k + 1, along[k], fixes[k + 1].timeMillis.toDouble(), f.latitude, f.longitude)
            }
            return best
        }
    }
}
