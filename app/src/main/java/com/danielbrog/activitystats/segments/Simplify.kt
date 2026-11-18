package com.danielbrog.activitystats.segments

import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.stats.Geo

/**
 * Thins out a path, keeping its shape: the Douglas–Peucker algorithm. A point is dropped when
 * it is within [toleranceMeters] of the straight line its neighbours would draw without it.
 * The first and last points are always kept. Plain Kotlin, unit-tested.
 */
object Simplify {

    fun path(points: List<RoutePoint>, toleranceMeters: Double): List<RoutePoint> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        // Without recursion, so a very long path can't overflow the stack.
        val pending = ArrayDeque<Pair<Int, Int>>().apply { addLast(0 to points.lastIndex) }
        while (pending.isNotEmpty()) {
            val (from, to) = pending.removeLast()
            var farthest = -1
            var farthestMeters = 0.0
            val a = points[from]
            val b = points[to]
            for (i in from + 1 until to) {
                val p = points[i]
                val d = Geo.onLeg(p.latitude, p.longitude, a.latitude, a.longitude, b.latitude, b.longitude).distanceMeters
                if (d > farthestMeters) {
                    farthestMeters = d
                    farthest = i
                }
            }
            if (farthest >= 0 && farthestMeters > toleranceMeters) {
                keep[farthest] = true
                pending.addLast(from to farthest)
                pending.addLast(farthest to to)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}
