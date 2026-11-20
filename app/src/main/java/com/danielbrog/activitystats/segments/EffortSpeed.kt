package com.danielbrog.activitystats.segments

/** The average speed of an effort on a segment. Plain Kotlin, unit-tested. */
object EffortSpeed {
    /**
     * Metres a second over [elapsedMs] for a segment [distanceMeters] long, or 0 for no time.
     * Uses the segment's length rather than what this ride's GPS measured, so every effort on
     * a segment is compared over the same distance and the fastest time is also the fastest speed.
     */
    fun averageMps(distanceMeters: Double, elapsedMs: Long): Double =
        if (elapsedMs <= 0L) 0.0 else distanceMeters / (elapsedMs / 1_000.0)
}
