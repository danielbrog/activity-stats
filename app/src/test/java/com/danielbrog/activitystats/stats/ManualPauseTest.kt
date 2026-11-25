package com.danielbrog.activitystats.stats

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.location.TrackingUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A manual pause starts a new stretch: nothing between the last fix before it and the first
 * after it counts, even if the bike went a long way in between (on a car, say).
 */
class ManualPauseTest {

    private val baseLat = 43.653226
    private val baseLon = -79.383184
    private val metresPerDegreeLat = 111_195.0
    private val start = 1_000_000L
    private var seq = 0

    private fun fix(second: Int, northMeters: Double, speed: Float, stretch: Int, altitude: Double? = null) = GpsData(
        seq = ++seq,
        timeMillis = start + second * 1_000L,
        latitude = baseLat + northMeters / metresPerDegreeLat,
        longitude = baseLon,
        accuracyMeters = 5f,
        speedMps = speed,
        status = FixStatus.Kept,
        altitudeMeters = altitude,
        stretch = stretch,
    )

    /** One fix a second at [speed] m/s, from [fromSecond] to [toSecond]. */
    private fun ride(fromSecond: Int, toSecond: Int, fromMeters: Double, speed: Float, stretch: Int) =
        (fromSecond..toSecond).map { s ->
            fix(s, fromMeters + (s - fromSecond) * speed, speed, stretch, altitude = 100.0)
        }

    /**
     * 100 s at 10 m/s (1 km), pause, carried 5 km north in 10 minutes, Resume, another
     * 100 s at 10 m/s. Ridden: 2 km in 200 s.
     */
    private fun rideWithALift(): List<GpsData> =
        ride(0, 100, 0.0, 10f, stretch = 0) + ride(700, 800, 6_000.0, 10f, stretch = 1)

    @Test
    fun theEngineCountsNeitherTheDistanceNorTheTimeAcrossAPause() {
        val engine = StatsEngine(start)
        rideWithALift().forEach(engine::add)
        val stats = engine.snapshot(start + 800_000L)

        assertEquals(2_000.0, stats.distanceMeters, 1.0)
        assertEquals(200_000L, stats.movingMs)
        // Elapsed still runs from Start, pause included.
        assertEquals(800_000L, stats.elapsedMs)
        assertEquals(10.0, stats.avgSpeedMps, 0.01)
    }

    @Test
    fun currentSpeedStartsAfreshAfterResume() {
        val engine = StatsEngine(start)
        ride(0, 100, 0.0, 10f, stretch = 0).forEach(engine::add)
        engine.add(fix(700, 6_000.0, 10f, stretch = 1))
        // One fix into the new stretch: too few to measure, and never the 5 km jump.
        val stats = engine.snapshot(start + 700_000L)
        assertEquals(0.0, stats.currentSpeedMps, 0.0)
        assertEquals(10.0, stats.maxSpeedMps, 0.5)
    }

    @Test
    fun anAutoPauseBeforeTheManualPauseDoesNotCarryOver() {
        val engine = StatsEngine(start)
        ride(0, 10, 0.0, 10f, stretch = 0).forEach(engine::add)
        // Standing for 20 s: auto-pause kicks in.
        (11..30).forEach { engine.add(fix(it, 100.0, 0.1f, stretch = 0)) }
        // Resume after a manual pause and ride on: moving again from the first fix.
        ride(100, 110, 100.0, 10f, stretch = 1).forEach(engine::add)
        val stats = engine.snapshot(start + 110_000L)
        assertFalse(stats.isPaused)
        // 10 s riding + 1 s until the first slow fix, then 10 s after Resume.
        assertEquals(21_000L, stats.movingMs)
    }

    @Test
    fun aLiftInACarCanNeverMakeABestEffort() {
        val efforts = BestEfforts.find(rideWithALift())
        // Without the pause the 6 km gap would count, giving a 5 km "effort" in minutes.
        assertNull(efforts[Effort.Km5])
    }

    @Test
    fun splitsAddUpToTheRiddenDistanceAndTime() {
        val splits = Splits.of(rideWithALift(), DistanceUnit.Km)
        assertEquals(2, splits.size)
        assertEquals(2_000.0, splits.sumOf { it.distanceMeters }, 1.0)
        assertEquals(200_000L, splits.sumOf { it.movingMs })
    }

    @Test
    fun theChartsDoNotJumpAcrossAPause() {
        assertEquals(2_000.0, SpeedSeries.fromFixes(rideWithALift()).last().distanceMeters, 1.0)
        assertEquals(2_000.0, ElevationSeries.fromFixes(rideWithALift()).last().distanceMeters, 1.0)
    }

    @Test
    fun summarizeMatchesTheLiveEngine() {
        // Crash recovery and imports rebuild a ride from its points; the pause must survive that.
        val stats = summarize(start, rideWithALift())
        assertEquals(2_000.0, stats.distanceMeters, 1.0)
        assertEquals(200_000L, stats.movingMs)
    }

    // --- The big Time on the Record screen ---

    @Test
    fun withAutoPauseOffTheTimeLeavesOutManualPauses() {
        val started = 0L
        // Recording, 5 minutes paused so far: 16:40 since Start shows as 11:40.
        assertEquals(700_000L, displayTimeMs(false, true, ActivityStats(), started, 1_000_000L, pausedMs = 300_000L))
        // After Finish: elapsed less the pauses.
        assertEquals(
            600_000L,
            displayTimeMs(false, false, ActivityStats(elapsedMs = 700_000L), started, 0L, pausedMs = 100_000L),
        )
    }

    @Test
    fun withAutoPauseOnTheTimeStandsStillWhilePausedByHand() {
        val stats = ActivityStats(movingMs = 600_000L, isPaused = false, asOfMillis = 2_000_000L)
        // Moving: ticks on from the last fix.
        assertEquals(603_000L, displayTimeMs(true, true, stats, 0L, 2_003_000L, sinceLastFixMs = 3_000L))
        // Paused by hand: frozen, though auto-pause hasn't decided anything.
        assertEquals(
            600_000L,
            displayTimeMs(true, true, stats, 0L, 2_003_000L, manuallyPaused = true, sinceLastFixMs = 3_000L),
        )
    }

    @Test
    fun resumingHoldsTheTimeUntilTheNextFix() {
        // Paused at 1:00:00 of moving time, after fixes; Resume clears the last fix.
        val recording = TrackingUiState(
            isTracking = true,
            stats = ActivityStats(movingMs = 3_600_000L, isPaused = false, asOfMillis = 2_000_000L),
            lastFixElapsedMs = 50_000L,
        )
        val resumed = recording.copy(lastFixElapsedMs = 0L)
        // 30 s after Resume, still no fix: the Time holds at 1:00:00, not 1:00:30.
        val since = resumed.sinceLastFixMs(elapsedNowMs = 80_000L)
        assertNull(since)
        assertEquals(3_600_000L, displayTimeMs(true, true, resumed.stats, 0L, 0L, sinceLastFixMs = since))
    }

    @Test
    fun timeSinceTheLastFixIsOnTheMonotonicClock() {
        val state = TrackingUiState(isTracking = true, lastFixElapsedMs = 10_000L)
        assertEquals(3_000L, state.sinceLastFixMs(elapsedNowMs = 13_000L))
        // Never negative, whatever the two readings.
        assertEquals(0L, state.sinceLastFixMs(elapsedNowMs = 9_000L))
        // Before the first fix (Start), or after Resume: none.
        assertNull(TrackingUiState(isTracking = true).sinceLastFixMs(elapsedNowMs = 13_000L))
    }

    @Test
    fun pausedTimeIncludesThePauseInProgress() {
        val state = TrackingUiState(isTracking = true, pausedMs = 60_000L, pausedAtMillis = 1_000_000L)
        assertEquals(90_000L, state.pausedMsAt(1_030_000L))
        assertEquals(60_000L, state.copy(pausedAtMillis = null).pausedMsAt(1_030_000L))
    }
}
