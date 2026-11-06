package com.danielbrog.activitystats.location

import com.danielbrog.activitystats.stats.Geo

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
            val moved = Geo.distanceMeters(last.latitude, last.longitude, fix.latitude, fix.longitude)
            val slow = fix.speedMps == null || fix.speedMps < stillSpeedMps
            if (moved < accuracy && slow) return FixStatus.DroppedStationary
        }

        lastKept = fix
        return FixStatus.Kept
    }
}
