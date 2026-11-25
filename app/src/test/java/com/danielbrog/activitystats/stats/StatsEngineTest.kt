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
        speed: Float? = null,
    ) = GpsData(
        seq = ++seq,
        timeMillis = start + second * 1_000L,
        latitude = baseLat + northMeters / metresPerDegreeLat,
        longitude = baseLon,
        accuracyMeters = 5f,
        speedMps = speed,
        status = status,
    )

    /** Ride north at [speed] m/s with GPS-reported speed, one fix per second. */
    private fun StatsEngine.ride(fromSecond: Int, toSecond: Int, fromMeters: Double, speed: Float) {
        for (s in fromSecond..toSecond) {
            add(fix(s, fromMeters + (s - fromSecond) * speed, speed = speed))
        }
    }

    /** Stand still at [atMeters] with GPS-reported [speed], as a kept fix each second. */
    private fun StatsEngine.standKept(fromSecond: Int, toSecond: Int, atMeters: Double, speed: Float = 0.1f) {
        for (s in fromSecond..toSecond) add(fix(s, atMeters, speed = speed))
    }

    // --- Gaps ---

    @Test
    fun aLongGapBetweenFixesIsNotMovingTimeButItsDistanceCounts() {
        // As in a bike computer's file: it stopped recording for 10 minutes, then went on.
        val engine = StatsEngine(start)
        engine.ride(0, 60, 0.0, 6f)            // 60 s, 360 m
        engine.ride(660, 720, 400.0, 6f)       // 10 min later, 40 m on; another 60 s
        val stats = engine.snapshot(ms(720))

        assertEquals(120_000L, stats.movingMs)
        assertEquals(760.0, stats.distanceMeters, 0.5)
        assertEquals(720_000L, stats.elapsedMs)
    }

    @Test
    fun aShortGapStillCountsAsMoving() {
        // A 30 s gap while riding, under the 60 s limit, as when GPS drops briefly.
        val engine = StatsEngine(start)
        engine.ride(0, 60, 0.0, 6f)
        engine.ride(90, 150, 540.0, 6f)
        assertEquals(150_000L, engine.snapshot(ms(150)).movingMs)
    }

    // --- Auto-pause ---

    @Test
    fun aBriefSlowSpellDoesNotPauseAndStillCountsAsMoving() {
        val engine = StatsEngine(start)
        engine.ride(0, 10, 0.0, 6f)
        engine.standKept(11, 13, 60.0) // 3 slow seconds, under the 5 s limit
        engine.ride(14, 20, 60.0, 6f)
        val stats = engine.snapshot(ms(20))

        assertEquals(false, stats.isPaused)
        assertEquals(20_000L, stats.movingMs)
    }

    @Test
    fun fiveSlowSecondsPause() {
        val engine = StatsEngine(start)
        engine.ride(0, 10, 0.0, 6f)
        engine.standKept(11, 15, 60.0) // first slow fix at 11; 15 is 4 s later
        assertEquals(false, engine.snapshot(ms(15)).isPaused)
        engine.standKept(16, 16, 60.0) // 5 s after the first slow fix
        assertEquals(true, engine.snapshot(ms(16)).isPaused)
    }

    @Test
    fun pausedTimeIsLeftOutFromTheFirstSlowFix() {
        val engine = StatsEngine(start)
        engine.ride(0, 10, 0.0, 6f)       // 10 s moving
        engine.standKept(11, 40, 60.0)    // stopped 11..40
        engine.ride(41, 50, 60.0, 6f)     // resumes at 41; 41..50 is 9 s moving
        val stats = engine.snapshot(ms(50))

        // 0..11 counts (moving until the first slow fix), the stop doesn't, 41..50 does.
        assertEquals(11_000L + 9_000L, stats.movingMs)
        assertEquals(50_000L, stats.elapsedMs)
        assertEquals(false, stats.isPaused)
    }

    @Test
    fun theFirstMovingFixResumes() {
        val engine = StatsEngine(start)
        engine.ride(0, 5, 0.0, 6f)
        engine.standKept(6, 20, 30.0)
        assertEquals(true, engine.snapshot(ms(20)).isPaused)
        engine.add(fix(21, 33.0, speed = 3f))
        assertEquals(false, engine.snapshot(ms(21)).isPaused)
    }

    @Test
    fun standingStillDropsPauseAndPoorAccuracyFixesAreIgnored() {
        val engine = StatsEngine(start)
        engine.ride(0, 10, 0.0, 6f)
        // The filter marks the stop as standing still; the speed it reports doesn't matter.
        for (s in 11..20) engine.add(fix(s, 60.0, FixStatus.DroppedStationary, speed = 0.3f))
        assertEquals(true, engine.snapshot(ms(20)).isPaused)

        // A wild poor-accuracy fix claiming 20 m/s must not resume.
        engine.add(fix(21, 500.0, FixStatus.DroppedAccuracy, speed = 20f))
        assertEquals(true, engine.snapshot(ms(21)).isPaused)
    }

    @Test
    fun aGpsGapWhileMovingStillCountsAsMoving() {
        val engine = StatsEngine(start)
        engine.ride(0, 10, 0.0, 6f)
        engine.add(fix(30, 180.0, speed = 6f)) // tunnel: no fixes for 20 s, still riding
        assertEquals(30_000L, engine.snapshot(ms(30)).movingMs)
    }

    @Test
    fun displayTimeFollowsTheAutoPauseSetting() {
        val stats = ActivityStats(movingMs = 600_000L, elapsedMs = 700_000L, isPaused = false, asOfMillis = 2_000_000L)
        val started = 2_000_000L - 700_000L

        // Off: elapsed time, live while recording.
        assertEquals(701_000L, displayTimeMs(false, true, stats, started, 2_001_000L))
        assertEquals(700_000L, displayTimeMs(false, false, stats, started, 2_001_000L))
        // On and moving: moving time, ticking since the last fix.
        assertEquals(601_000L, displayTimeMs(true, true, stats, started, 2_001_000L, sinceLastFixMs = 1_000L))
        // On and paused: frozen at moving time.
        assertEquals(600_000L, displayTimeMs(true, true, stats.copy(isPaused = true), started, 2_009_000L))
        // On, after Stop: moving time.
        assertEquals(600_000L, displayTimeMs(true, false, stats, started, 2_009_000L))
    }

    @Test
    fun theTimeTicksForFiveSecondsAfterTheLastFixThenHolds() {
        val stats = ActivityStats(movingMs = 600_000L, isPaused = false)
        assertEquals(603_000L, displayTimeMs(true, true, stats, 0L, 0L, sinceLastFixMs = 3_000L))
        assertEquals(605_000L, displayTimeMs(true, true, stats, 0L, 0L, sinceLastFixMs = StatsEngine.CURRENT_SPEED_WINDOW_MS))
        // A tunnel: no fix for 90 s. The Time holds rather than running on and jumping back.
        assertEquals(605_000L, displayTimeMs(true, true, stats, 0L, 0L, sinceLastFixMs = 90_000L))
        // No fix yet since Start or Resume: nothing to tick from.
        assertEquals(600_000L, displayTimeMs(true, true, stats, 0L, 0L, sinceLastFixMs = null))
    }

    @Test
    fun afterAGapTheTimeNeverStepsBackMoreThanTheSpeedWindow() {
        // A short gap (30 s) the engine counts: the Time catches up when the fix comes.
        val short = StatsEngine(start)
        short.ride(0, 60, 0.0, 6f)
        val shownShort = displayTimeMs(true, true, short.snapshot(ms(60)), start, 0L, sinceLastFixMs = 29_000L)
        short.add(fix(90, 540.0, speed = 6f))
        assertEquals(65_000L, shownShort)
        assertEquals(90_000L, short.snapshot(ms(90)).movingMs)

        // A long gap (3 min) it doesn't count: the Time steps back by at most the 5 s it ticked.
        val long = StatsEngine(start)
        long.ride(0, 60, 0.0, 6f)
        val shownLong = displayTimeMs(true, true, long.snapshot(ms(60)), start, 0L, sinceLastFixMs = 179_000L)
        long.add(fix(240, 1_440.0, speed = 6f))
        val recorded = long.snapshot(ms(240)).movingMs
        assertEquals(60_000L, recorded)
        assertEquals(StatsEngine.CURRENT_SPEED_WINDOW_MS, shownLong - recorded)
    }

    @Test
    fun currentSpeedIsZeroWithNoRecentFix() {
        val stats = ActivityStats(currentSpeedMps = 8.0)
        assertEquals(8.0, liveSpeedMps(stats, isTracking = true, sinceLastFixMs = 3_000L), 0.0)
        assertEquals(8.0, liveSpeedMps(stats, isTracking = true, sinceLastFixMs = StatsEngine.CURRENT_SPEED_WINDOW_MS), 0.0)
        // No fix for longer than the speed window: the GPS is lost, not riding at 8 m/s.
        assertEquals(0.0, liveSpeedMps(stats, isTracking = true, sinceLastFixMs = 6_000L), 0.0)
        // No fix yet since Start or Resume: the stats' own speed (0 then) stands.
        assertEquals(0.0, liveSpeedMps(ActivityStats(), isTracking = true, sinceLastFixMs = null), 0.0)
        // After Finish the stored value stands.
        assertEquals(8.0, liveSpeedMps(stats, isTracking = false, sinceLastFixMs = 6_000L), 0.0)
    }

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
    fun oneSpikyLegDoesNotSetMaxSpeed() {
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
    fun summarizeMatchesTheLiveEngine() {
        // Walk, stop with dropped jitter, walk again, as the service would record it.
        val points = buildList {
            for (s in 0..30) add(fix(s, s * 3.0))
            for (s in 31..59) add(fix(s, 90.0 + (s % 3), FixStatus.DroppedStationary))
            for (s in 60..90) add(fix(s, 90.0 + (s - 60) * 3.0))
        }
        val live = StatsEngine(start).apply { points.forEach(::add) }.snapshot(ms(90))

        // Saved points can come back in any order; summarize sorts them by seq.
        val recovered = summarize(start, points.shuffled(java.util.Random(7)))

        assertEquals(live.distanceMeters, recovered.distanceMeters, 0.001)
        assertEquals(live.movingMs, recovered.movingMs)
        assertEquals(live.elapsedMs, recovered.elapsedMs)
        assertEquals(live.avgSpeedMps, recovered.avgSpeedMps, 0.001)
        assertEquals(live.maxSpeedMps, recovered.maxSpeedMps, 0.001)
        assertEquals(0.0, recovered.currentSpeedMps, 0.0)
    }

    @Test
    fun summarizeWithNoPointsGivesZeros() {
        val stats = summarize(start, emptyList())
        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(0L, stats.elapsedMs)
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
