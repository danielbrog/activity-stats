package com.danielbrog.activitystats.stats

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Geographic helpers shared by the noise filter, the stats engine and segment matching. Plain Kotlin. */
object Geo {
    /** Mean Earth radius in metres (IUGG). */
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance between two coordinates in metres (haversine formula). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Where a point falls against a straight line from a to b: how far off it, and how far along. */
    data class OnLeg(
        /** Metres from the point to the closest place on the line. */
        val distanceMeters: Double,
        /** Where that closest place is: 0 at a, 1 at b, in between along the line. */
        val fraction: Double,
    )

    /**
     * How far the point (lat, lon) is from the straight line between a and b, and where along it
     * the closest place is. Treats the earth as flat around the point, which is accurate to
     * about a metre over a few kilometres: plenty for the legs between fixes and the straight
     * lines of a simplified segment.
     */
    fun onLeg(lat: Double, lon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): OnLeg {
        // Metres east (x) and north (y) of the point.
        val metersPerDegLat = Math.toRadians(1.0) * EARTH_RADIUS_M
        val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(lat))
        val ax = (aLon - lon) * metersPerDegLon
        val ay = (aLat - lat) * metersPerDegLat
        val bx = (bLon - lon) * metersPerDegLon
        val by = (bLat - lat) * metersPerDegLat
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return OnLeg(hypot(ax + t * dx, ay + t * dy), t)
    }

    /**
     * How far a point is from a path of points joined by straight lines: the closest of all its
     * lines, or the distance to the only point if the path has one.
     */
    fun distanceToPathMeters(lat: Double, lon: Double, pathLats: DoubleArray, pathLons: DoubleArray): Double {
        require(pathLats.size == pathLons.size && pathLats.isNotEmpty()) { "A path needs at least one point" }
        if (pathLats.size == 1) return distanceMeters(lat, lon, pathLats[0], pathLons[0])
        var best = Double.MAX_VALUE
        for (i in 1 until pathLats.size) {
            val d = onLeg(lat, lon, pathLats[i - 1], pathLons[i - 1], pathLats[i], pathLons[i]).distanceMeters
            if (d < best) best = d
        }
        return best
    }
}
