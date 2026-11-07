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
 * The route drawn on the map: every kept fix of the whole recording, in order. Unlike the
 * fix list it is not capped, and it skips dropped fixes so the line doesn't show GPS noise.
 */
object Route {
    /** [route] with the kept fixes from [newFixes] appended in arrival order. */
    fun extend(route: List<RoutePoint>, newFixes: List<GpsData>): List<RoutePoint> {
        val kept = newFixes
            .filter { it.status == FixStatus.Kept }
            .map { RoutePoint(it.latitude, it.longitude) }
        return if (kept.isEmpty()) route else route + kept
    }
}
