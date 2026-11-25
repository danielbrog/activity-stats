package com.danielbrog.activitystats.segments

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.location.RouteBounds
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.location.legMeters
import com.danielbrog.activitystats.stats.Geo

/**
 * The road a segment covers, start to finish, as a simplified line of points. Plain Kotlin.
 * [distanceMeters] is measured along the original ride, before simplifying, so it is the
 * distance actually ridden and the one to show. Matching compares [lineMeters] instead.
 */
data class SegmentPath(val points: List<RoutePoint>, val distanceMeters: Double) {
    init {
        require(points.size >= 2) { "A segment needs a start and an end" }
    }

    val start: RoutePoint get() = points.first()
    val end: RoutePoint get() = points.last()
    val bounds: RouteBounds by lazy { requireNotNull(RouteBounds.of(points)) }

    /** The length of the simplified line, which matching compares a ride's simplified line with. */
    val lineMeters: Double by lazy { lengthOf(points) }

    /** The points a quarter, half and three quarters of the way along, which set the direction. */
    val checkpoints: List<RoutePoint> by lazy { listOf(0.25, 0.5, 0.75).map { pointAt(it) } }

    /** The point [fraction] of the way along the line, from 0 at the start to 1 at the end. */
    fun pointAt(fraction: Double): RoutePoint {
        var remaining = fraction * lineMeters
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val side = Geo.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            if (side > 0 && remaining <= side) {
                val t = remaining / side
                return RoutePoint(
                    a.latitude + t * (b.latitude - a.latitude),
                    a.longitude + t * (b.longitude - a.longitude),
                )
            }
            remaining -= side
        }
        return end
    }

    companion object {
        /** Points that add less than this much to the line's shape are dropped when saving. */
        const val SIMPLIFY_TOLERANCE_METERS = 5.0

        /** The length of a line of points joined by straight lines. */
        fun lengthOf(line: List<RoutePoint>): Double = line.zipWithNext { a, b ->
            Geo.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        }.sum()

        /**
         * The part of a ride from [startIndex] to [endIndex], counting only its kept fixes in
         * order (as the map draws them). Null if that part can't be a segment: fewer than two
         * fixes, no length, or a manual pause inside it (the gap wasn't ridden).
         */
        fun fromRide(fixes: List<GpsData>, startIndex: Int, endIndex: Int): SegmentPath? {
            val kept = fixes.filter { it.status == FixStatus.Kept }.sortedBy { it.seq }
            if (startIndex < 0 || endIndex > kept.lastIndex || endIndex <= startIndex) return null
            val part = kept.subList(startIndex, endIndex + 1)
            if (part.any { it.stretch != part.first().stretch }) return null
            val distance = part.zipWithNext(::legMeters).sum()
            if (distance <= 0.0) return null
            val points = Simplify.path(part.map { RoutePoint(it.latitude, it.longitude) }, SIMPLIFY_TOLERANCE_METERS)
            return SegmentPath(points, distance)
        }
    }
}
