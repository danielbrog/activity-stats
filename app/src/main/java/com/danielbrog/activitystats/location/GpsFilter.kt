package com.danielbrog.activitystats.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What the noise filter decided about one fix. */
enum class FixStatus { Kept, DroppedAccuracy, DroppedStationary }

/**
 * Decides which GPS fixes to trust (milestone M4). Plain Kotlin, so it is unit-tested
 * without a device. Use one instance per recording; it remembers the last kept fix.
 *
 * Rules:
 * 1. Drop fixes with no accuracy value or accuracy worse than [maxAccuracyMeters].
 * 2. Drop a fix that is closer to the last kept fix than its own accuracy radius while
 *    moving slower than [stillSpeedMps] (or reporting no speed). That is GPS wander
 *    while standing still, and would otherwise add fake distance. Comparing against the
 *    last *kept* fix means slow real movement is still kept once it leaves the radius.
 */
class GpsFilter(
    private val maxAccuracyMeters: Float = 20f,
    private val stillSpeedMps: Float = 0.5f,
) {
    private var lastKept: GpsData? = null

    fun classify(fix: GpsData): FixStatus {
        val accuracy = fix.accuracyMeters
        if (accuracy == null || accuracy > maxAccuracyMeters) return FixStatus.DroppedAccuracy

        val last = lastKept
        if (last != null) {
            val moved = distanceMeters(last.latitude, last.longitude, fix.latitude, fix.longitude)
            val slow = fix.speedMps == null || fix.speedMps < stillSpeedMps
            if (moved < accuracy && slow) return FixStatus.DroppedStationary
        }

        lastKept = fix
        return FixStatus.Kept
    }

    companion object {
        /** Mean Earth radius in metres (IUGG). */
        private const val EARTH_RADIUS_M = 6_371_008.8

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
    }
}
