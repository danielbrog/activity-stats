package com.danielbrog.activitystats.location

/** One point on the drawn route. Plain Kotlin, so no map library types leak into state. */
data class RoutePoint(val latitude: Double, val longitude: Double)

/** The smallest box containing a route, for fitting it on a map. */
data class RouteBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    /** True when every point is in the same place, so there is no area to fit. */
    val isSinglePoint: Boolean get() = south == north && west == east

    val centre: RoutePoint get() = RoutePoint((south + north) / 2, (west + east) / 2)

    companion object {
        /** Bounds of [points], or null when there are none. */
        fun of(points: List<RoutePoint>): RouteBounds? {
            if (points.isEmpty()) return null
            return RouteBounds(
                south = points.minOf { it.latitude },
                west = points.minOf { it.longitude },
                north = points.maxOf { it.latitude },
                east = points.maxOf { it.longitude },
            )
        }
    }
}

/**
 * The route drawn on the map: every kept fix of the whole recording, in order, as one line
 * per stretch between manual pauses, so the map shows a break rather than a jump. Unlike
 * the fix list it is not capped, and it skips dropped fixes so the line doesn't show GPS noise.
 */
object Route {
    /**
     * [route] with the kept fixes from [newFixes] added to its last line in arrival order.
     * A recording calls [startLine] on Resume, so the fixes after a pause start a new line.
     */
    fun extend(route: List<List<RoutePoint>>, newFixes: List<GpsData>): List<List<RoutePoint>> {
        val kept = newFixes
            .filter { it.status == FixStatus.Kept }
            .map { RoutePoint(it.latitude, it.longitude) }
        if (kept.isEmpty()) return route
        val last = route.lastOrNull() ?: return listOf(kept)
        return route.dropLast(1) + listOf(last + kept)
    }

    /** [route] with an empty line at the end, for the fixes after a pause. */
    fun startLine(route: List<List<RoutePoint>>): List<List<RoutePoint>> =
        if (route.isEmpty() || route.last().isEmpty()) route else route + listOf(emptyList())

    /** A saved ride's route: its kept fixes in order, one line per segment. */
    fun of(fixes: List<GpsData>): List<List<RoutePoint>> {
        val lines = mutableListOf<MutableList<RoutePoint>>()
        var segment: Int? = null
        for (fix in fixes.filter { it.status == FixStatus.Kept }.sortedBy { it.seq }) {
            if (fix.segment != segment || lines.isEmpty()) {
                lines += mutableListOf<RoutePoint>()
                segment = fix.segment
            }
            lines.last() += RoutePoint(fix.latitude, fix.longitude)
        }
        return lines
    }

    /** The newest point of [route], or null when nothing has been drawn yet. */
    fun latest(route: List<List<RoutePoint>>): RoutePoint? = route.lastOrNull { it.isNotEmpty() }?.last()
}
