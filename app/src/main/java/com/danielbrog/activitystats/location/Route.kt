package com.danielbrog.activitystats.location

/** One point on the drawn route. Plain Kotlin, so no map library types leak into state. */
data class RoutePoint(val latitude: Double, val longitude: Double)

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
