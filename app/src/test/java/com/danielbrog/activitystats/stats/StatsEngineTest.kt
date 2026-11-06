package com.danielbrog.activitystats.stats

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsEngineTest {

    private val baseLat = 43.653226
    private val baseLon = -79.383184
    private val metresPerDegreeLat = 111_195.0
    private val start = 1_000_000L
    private var seq = 0

    /** A fix [northMeters] north of the base point at [second] seconds after [start]. */
    private fun fix(
        second: Int,
        northMeters: Double,
        status: FixStatus = FixStatus.Kept,
    ) = GpsData(
        seq = ++seq,
        timeMillis = start + second * 1_000L,
        latitude = baseLat + northMeters / metresPerDegreeLat,
        longitude = baseLon,
        accuracyMeters = 5f,
        speedMps = null,
        status = status,
    )

    private fun ms(second: Int) = start + second * 1_000L

    /** Walk north at [speed] m/s from [fromSecond] to [toSecond], starting at [fromMeters]. */
    private fun StatsEngine.walk(fromSecond: Int, toSecond: Int, fromMeters: Double, speed: Double) {
        for (s in fromSecond..toSecond) add(fix(s, fromMeters + (s - fromSecond) * speed))
    }

    @Test
    fun steadyWalk() {
        val engine = StatsEngine(start)
        engine.walk(0, 60, 0.0, 3.0)
        val stats = engine.snapshot(ms(60))

        assertEquals(180.0, stats.distanceMeters, 180.0 * 0.01)
        assertEquals(60_000L, stats.elapsedMs)
        assertEquals(60_000L, stats.movingMs)
        assertEquals(3.0, stats.avgSpeedMps, 0.05)
        assertEquals(3.0, stats.currentSpeedMps, 0.05)
        assertEquals(3.0, stats.maxSpeedMps, 0.05)
        // 3 m/s is 333 s per km, which is 5:33 /km.
        assertEquals(333.3, paceSecondsPerKm(stats.avgSpeedMps)!!, 6.0)
    }

    @Test
    fun stopCountsInElapsedButNotMovingTime() {
        val engine = StatsEngine(start)
        engine.walk(0, 30, 0.0, 3.0) // 90 m
        // Standing still 30..60 s: the filter drops these, and dropped fixes are ignored.
        for (s in 31..59) engine.add(fix(s, 90.0 + (s % 3), FixStatus.DroppedStationary))
        engine.walk(60, 90, 90.0, 3.0) // another 90 m
        val stats = engine.snapshot(ms(90))

        assertEquals(180.0, stats.distanceMeters, 180.0 * 0.01)
        assertEquals(90_000L, stats.elapsedMs)
        assertEquals(60_000L, stats.movingMs)
        assertEquals(3.0, stats.avgSpeedMps, 0.05)
    }

    @Test
    fun currentSpeedFallsToZeroFiveSecondsAfterStopping() {
        val engine = StatsEngine(start)
        engine.walk(0, 10, 0.0, 3.0)
        assertEquals(3.0, engine.snapshot(ms(10)).currentSpeedMps, 0.05)
        assertEquals(0.0, engine.snapshot(ms(15)).currentSpeedMps, 0.0)
    }

    @Test
    fun droppedFixesNeverAddDistance() {
        val engine = StatsEngine(start)
        engine.add(fix(0, 0.0))
        engine.add(fix(1, 500.0, FixStatus.DroppedAccuracy))
        engine.add(fix(2, 3.0))
        assertEquals(3.0, engine.snapshot(ms(2)).distanceMeters, 0.1)
    }

    @Test
    fun gapWithRealMovementStillCounts() {
        val engine = StatsEngine(start)
        engine.walk(0, 10, 0.0, 3.0) // 30 m
        // GPS lost for 20 s while still walking at 3 m/s.
        engine.add(fix(30, 90.0))
        val stats = engine.snapshot(ms(30))

        assertEquals(90.0, stats.distanceMeters, 1.0)
        assertEquals(30_000L, stats.movingMs)
    }

    @Test
    fun oneSpikySegmentDoesNotSetMaxSpeed() {
        val engine = StatsEngine(start)
        engine.walk(0, 9, 0.0, 3.0)
        engine.add(fix(10, 36.0)) // 9 m in one second: a 9 m/s spike, 6 m ahead of the track
        engine.walk(11, 20, 33.0, 3.0) // back on the track
        val stats = engine.snapshot(ms(20))

        assertTrue("max ${stats.maxSpeedMps} should stay well below the 9 m/s spike", stats.maxSpeedMps < 5.0)
    }

    @Test
    fun paceIsNullWhenTooSlow() {
        assertNull(paceSecondsPerKm(0.4))
        assertEquals(300.0, paceSecondsPerKm(1_000.0 / 300.0)!!, 0.001) // 5:00 /km
    }

    @Test
    fun noFixesGivesZeros() {
        val stats = StatsEngine(start).snapshot(ms(10))
        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(10_000L, stats.elapsedMs)
        assertEquals(0L, stats.movingMs)
        assertEquals(0.0, stats.avgSpeedMps, 0.0)
    }
}
