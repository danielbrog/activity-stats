package com.danielbrog.activitystats.segments

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.location.RoutePoint
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rides made up of points on a flat grid of metres, 1 fix a second. The road runs north from
 * the origin; "east" and "north" below are metres from it.
 */
class SegmentMatcherTest {

    private val originLat = 43.65
    private val originLon = -79.38
    private val metersPerDegLat = 111_195.0
    private val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(originLat))

    private fun at(east: Double, north: Double) =
        RoutePoint(originLat + north / metersPerDegLat, originLon + east / metersPerDegLon)

    /** A path through the corners given, with a point every [step] metres along it. */
    private fun line(vararg corners: Pair<Double, Double>, step: Double = 10.0): List<Pair<Double, Double>> {
        val out = mutableListOf(corners.first())
        for (k in 1 until corners.size) {
            val (e0, n0) = corners[k - 1]
            val (e1, n1) = corners[k]
            val length = kotlin.math.hypot(e1 - e0, n1 - n0)
            val steps = kotlin.math.max(1, (length / step).toInt())
            for (s in 1..steps) out += (e0 + (e1 - e0) * s / steps) to (n0 + (n1 - n0) * s / steps)
        }
        return out
    }

    /** A ride along [points], one fix a second from [startSecond], in pause stretch [stretch]. */
    private fun ride(points: List<Pair<Double, Double>>, startSecond: Int = 0, firstSeq: Int = 1, stretch: Int = 0) =
        points.mapIndexed { i, (e, n) ->
            val p = at(e, n)
            GpsData(firstSeq + i, (startSecond + i) * 1_000L, p.latitude, p.longitude, 5f, 10f, FixStatus.Kept, stretch = stretch)
        }

    /** The segment: 1 km of the road, from 500 m to 1,500 m north. */
    private val segment = SegmentPath(line(0.0 to 500.0, 0.0 to 1_500.0).map { at(it.first, it.second) }, 1_000.0)

    private val road = line(0.0 to 0.0, 0.0 to 2_000.0)

    @Test
    fun aRideAlongTheRoadMakesOneEffortTimedBetweenTheEnds() {
        val efforts = SegmentMatcher.find(segment, ride(road))
        assertEquals(1, efforts.size)
        // 10 m a second: 1 km takes 100 s, and it starts at 500 m, 50 s in.
        assertEquals(100_000.0, efforts[0].elapsedMs.toDouble(), 1_500.0)
        assertEquals(50_000.0, efforts[0].startedAtMillis.toDouble(), 1_500.0)
    }

    @Test
    fun theOtherDirectionDoesNotCount() {
        assertTrue(SegmentMatcher.find(segment, ride(road.reversed())).isEmpty())
    }

    @Test
    fun aParallelRoad50mAwayDoesNotCount() {
        assertTrue(SegmentMatcher.find(segment, ride(line(50.0 to 0.0, 50.0 to 2_000.0))).isEmpty())
    }

    @Test
    fun startingPartWayAlongDoesNotCount() {
        assertTrue(SegmentMatcher.find(segment, ride(line(0.0 to 800.0, 0.0 to 2_000.0))).isEmpty())
    }

    @Test
    fun stoppingPartWayAlongDoesNotCount() {
        assertTrue(SegmentMatcher.find(segment, ride(line(0.0 to 0.0, 0.0 to 1_200.0))).isEmpty())
    }

    @Test
    fun aDetourOffTheRoadDoesNotCount() {
        // Leaves the road at 700 m, goes 300 m east, 400 m north and back, rejoining at 1,100 m.
        val detour = line(0.0 to 0.0, 0.0 to 700.0, 300.0 to 700.0, 300.0 to 1_100.0, 0.0 to 1_100.0, 0.0 to 2_000.0)
        assertTrue(SegmentMatcher.find(segment, ride(detour)).isEmpty())
    }

    @Test
    fun aShortCutAcrossABendDoesNotCount() {
        // An L-shaped segment: 500 m north, then 500 m east. The short cut goes straight across.
        val bend = SegmentPath(line(0.0 to 0.0, 0.0 to 500.0, 500.0 to 500.0).map { at(it.first, it.second) }, 1_000.0)
        val shortCut = line(0.0 to -100.0, 0.0 to 0.0, 500.0 to 500.0, 600.0 to 500.0)
        assertTrue(SegmentMatcher.find(bend, ride(shortCut)).isEmpty())
        // Riding the bend itself does count.
        assertEquals(1, SegmentMatcher.find(bend, ride(line(0.0 to -100.0, 0.0 to 500.0, 600.0 to 500.0))).size)
    }

    @Test
    fun twoLapsMakeTwoEfforts() {
        // A 500 m square loop; the segment is part of its west side, going north.
        val lap = line(0.0 to 0.0, 0.0 to 500.0, 500.0 to 500.0, 500.0 to 0.0, 0.0 to 0.0)
        val side = SegmentPath(line(0.0 to 100.0, 0.0 to 400.0).map { at(it.first, it.second) }, 300.0)
        val efforts = SegmentMatcher.find(side, ride(lap + lap.drop(1)))
        assertEquals(listOf(0, 1), efforts.map { it.occurrence })
        efforts.forEach { assertEquals(30_000.0, it.elapsedMs.toDouble(), 1_500.0) }
    }

    @Test
    fun gpsJitterStillCounts() {
        // GPS error drifts slowly, up to 10 m either side of the road, rather than jumping
        // about every second (which would also add distance that wasn't ridden).
        val jittery = road.mapIndexed { i, (e, n) -> (e + 10.0 * kotlin.math.sin(i / 15.0)) to n }
        assertEquals(1, SegmentMatcher.find(segment, ride(jittery)).size)
    }

    @Test
    fun droppedFixesAreIgnored() {
        val fixes = ride(road).mapIndexed { i, f ->
            // Every tenth fix is a wild one 400 m away that the filter dropped.
            if (i % 10 == 5) f.copy(longitude = f.longitude + 400 / metersPerDegLon, status = FixStatus.DroppedAccuracy) else f
        }
        assertEquals(1, SegmentMatcher.find(segment, fixes).size)
    }

    /** A ride along the road that waits [waitSeconds] at fix [at] (500 m is fix 50), as the noise filter leaves it. */
    private fun rideWaitingAt(at: Int, waitSeconds: Int) =
        ride(road).mapIndexed { i, f -> if (i > at) f.copy(timeMillis = f.timeMillis + waitSeconds * 1_000L) else f }

    @Test
    fun aManualPauseInsideDoesNotCount() {
        // Riding to 1,000 m, a 5-minute pause, then on: the pause falls inside the segment.
        val before = ride(line(0.0 to 0.0, 0.0 to 1_000.0))
        val after = ride(line(0.0 to 1_010.0, 0.0 to 2_000.0), startSecond = before.size + 300, firstSeq = before.size + 1, stretch = 1)
        assertTrue(SegmentMatcher.find(segment, before + after).isEmpty())
    }

    @Test
    fun aManualPauseOutsideStillCounts() {
        val before = ride(line(0.0 to 0.0, 0.0 to 1_600.0))
        val after = ride(line(0.0 to 1_610.0, 0.0 to 2_000.0), startSecond = before.size + 300, firstSeq = before.size + 1, stretch = 1)
        assertEquals(1, SegmentMatcher.find(segment, before + after).size)
    }

    @Test
    fun aStopInsideDoesNotCount() {
        // A minute at the lights at 1,000 m, half way along.
        assertTrue(SegmentMatcher.find(segment, rideWaitingAt(100, 60)).isEmpty())
    }

    @Test
    fun aWaitAtTheStartIsLeftOut() {
        // A minute at the lights right where the segment starts: timed from moving off.
        val efforts = SegmentMatcher.find(segment, rideWaitingAt(50, 60))
        assertEquals(1, efforts.size)
        assertEquals(100_000.0, efforts[0].elapsedMs.toDouble(), 1_500.0)
        assertEquals(111_000.0, efforts[0].startedAtMillis.toDouble(), 1_500.0)
    }

    @Test
    fun aWaitJustAfterTheEndIsLeftOut() {
        val efforts = SegmentMatcher.find(segment, rideWaitingAt(150, 60))
        assertEquals(1, efforts.size)
        assertEquals(100_000.0, efforts[0].elapsedMs.toDouble(), 1_500.0)
    }

    @Test
    fun aShortBreatherUnderFiveSecondsStillCounts() {
        val efforts = SegmentMatcher.find(segment, rideWaitingAt(100, 3))
        assertEquals(1, efforts.size)
        assertEquals(103_000.0, efforts[0].elapsedMs.toDouble(), 1_500.0)
    }

    @Test
    fun aSegmentAtTheVeryStartOfTheRideCounts() {
        val fromStart = SegmentPath(line(0.0 to 0.0, 0.0 to 1_000.0).map { at(it.first, it.second) }, 1_000.0)
        assertEquals(1, SegmentMatcher.find(fromStart, ride(road)).size)
    }

    @Test
    fun aRideSomewhereElseIsSkipped() {
        val farAway = road.map { (e, n) -> (e + 50_000.0) to n }
        assertTrue(SegmentMatcher.find(segment, ride(farAway)).isEmpty())
    }

    @Test
    fun aSegmentEndingWhereItStartsIsNotMatchedStraightAway() {
        // A loop segment: once round the square. Riding round once is one effort, not an instant one.
        val lap = line(0.0 to 0.0, 0.0 to 500.0, 500.0 to 500.0, 500.0 to 0.0, 0.0 to 0.0)
        val loop = SegmentPath(lap.map { at(it.first, it.second) }, 2_000.0)
        val efforts = SegmentMatcher.find(loop, ride(line(0.0 to -100.0, 0.0 to 0.0) + lap.drop(1)))
        assertEquals(1, efforts.size)
        assertEquals(200_000.0, efforts[0].elapsedMs.toDouble(), 2_000.0)
    }

    /** Round the 500 m square, starting and ending [startNorth] m up its west side. */
    private fun loopFrom(startNorth: Double, step: Double) = line(
        0.0 to startNorth, 0.0 to 500.0, 500.0 to 500.0, 500.0 to 0.0, 0.0 to 0.0, 0.0 to startNorth, step = step,
    )

    @Test
    fun eachLapOfALoopStartsWhereTheLastEnded() {
        // The segment starts 5 m up the west side, between two fixes 10 m apart. The second
        // lap must start at that crossing, not at the fix after it (half a second late).
        val loop = SegmentPath(loopFrom(5.0, 10.0).map { at(it.first, it.second) }, 2_000.0)
        val lap = line(0.0 to 0.0, 0.0 to 500.0, 500.0 to 500.0, 500.0 to 0.0, 0.0 to 0.0)
        val twoLaps = line(0.0 to -100.0, 0.0 to 0.0) + lap.drop(1) + lap.drop(1) + line(0.0 to 0.0, 0.0 to 50.0).drop(1)
        val efforts = SegmentMatcher.find(loop, ride(twoLaps))
        assertEquals(listOf(0, 1), efforts.map { it.occurrence })
        // 2,000 m at 10 m a second, each lap.
        efforts.forEach { assertEquals(200_000.0, it.elapsedMs.toDouble(), 100.0) }
    }

    @Test
    fun noLapOfALoopIsMissedWhenFixesAreFarApart() {
        // An import with a fix every ~42 m: only one fix is near the start (15 m up), the
        // one before it. The second lap's start is on the same leg as the first lap's end.
        val loop = SegmentPath(loopFrom(15.0, 10.0).map { at(it.first, it.second) }, 2_000.0)
        val lap = line(0.0 to 0.0, 0.0 to 500.0, 500.0 to 500.0, 500.0 to 0.0, 0.0 to 0.0, step = 40.0)
        val twoLaps = line(0.0 to -120.0, 0.0 to 0.0, step = 40.0) + lap.drop(1) + lap.drop(1) +
            line(0.0 to 0.0, 0.0 to 120.0, step = 40.0).drop(1)
        assertEquals(2, SegmentMatcher.find(loop, ride(twoLaps)).size)
    }

    @Test
    fun aGapAfterTheStartDoesNotMakeTheEffortTwice() {
        // A 100 m segment; the fixes between 510 m and 610 m were dropped, so the fix before
        // the end (510 m) is still near the start. That must not count as a second effort.
        val short = SegmentPath(line(0.0 to 500.0, 0.0 to 600.0).map { at(it.first, it.second) }, 100.0)
        val points = line(0.0 to 0.0, 0.0 to 480.0) + listOf(0.0 to 510.0, 0.0 to 610.0) +
            line(0.0 to 610.0, 0.0 to 800.0).drop(1)
        assertEquals(1, SegmentMatcher.find(short, ride(points)).size)
    }

    /** A loop segment from [startNorth] m up the square's west side, round, to [endNorth] m up it. */
    private fun nearLoop(startNorth: Double, endNorth: Double) = SegmentPath(
        line(0.0 to startNorth, 0.0 to 500.0, 500.0 to 500.0, 500.0 to 0.0, 0.0 to 0.0, 0.0 to endNorth)
            .map { at(it.first, it.second) },
        1_500.0 + 500.0 - startNorth + endNorth,
    )

    @Test
    fun twoLapsCountWhenALoopsEndIsJustPastItsStart() {
        val lap = line(0.0 to 0.0, 0.0 to 500.0, 500.0 to 500.0, 500.0 to 0.0, 0.0 to 0.0)
        val twoLaps = line(0.0 to -100.0, 0.0 to 0.0) + lap.drop(1) + lap.drop(1) + line(0.0 to 0.0, 0.0 to 100.0).drop(1)
        // Made from a ride, a loop's ends are never exactly the same point.
        assertEquals(2, SegmentMatcher.find(nearLoop(20.0, 25.0), ride(twoLaps)).size)
        assertEquals(2, SegmentMatcher.find(nearLoop(25.0, 20.0), ride(twoLaps)).size)
    }

    @Test
    fun oneLapOfALoopThenRidingOnIsOneEffort() {
        val lap = line(0.0 to 0.0, 0.0 to 500.0, 500.0 to 500.0, 500.0 to 0.0, 0.0 to 0.0)
        val oneLap = line(0.0 to -100.0, 0.0 to 0.0) + lap.drop(1) + line(0.0 to 0.0, 0.0 to 400.0).drop(1)
        assertEquals(1, SegmentMatcher.find(nearLoop(20.0, 25.0), ride(oneLap)).size)
    }

    @Test
    fun aLoopRiddenTheOtherWayRoundDoesNotCount() {
        val lap = line(0.0 to 0.0, 0.0 to 500.0, 500.0 to 500.0, 500.0 to 0.0, 0.0 to 0.0)
        val loop = SegmentPath(lap.map { at(it.first, it.second) }, 2_000.0)
        val otherWay = line(0.0 to 0.0, 500.0 to 0.0, 500.0 to 500.0, 0.0 to 500.0, 0.0 to 0.0)
        assertTrue(SegmentMatcher.find(loop, ride(line(0.0 to -100.0, 0.0 to 0.0) + otherWay.drop(1))).isEmpty())
    }

    @Test
    fun twoLapsOfALoopSegmentMakeTwoEfforts() {
        val lap = line(0.0 to 0.0, 0.0 to 500.0, 500.0 to 500.0, 500.0 to 0.0, 0.0 to 0.0)
        val loop = SegmentPath(lap.map { at(it.first, it.second) }, 2_000.0)
        val efforts = SegmentMatcher.find(loop, ride(line(0.0 to -100.0, 0.0 to 0.0) + lap.drop(1) + lap.drop(1)))
        assertEquals(2, efforts.size)
        efforts.forEach { assertEquals(200_000.0, it.elapsedMs.toDouble(), 2_000.0) }
    }

    @Test
    fun aShortSegmentWithWobblyGpsStillCounts() {
        // 150 m, ridden with the fix wobbling 3 m either side every second. Added up fix to
        // fix that is 17% too long, but the simplified line isn't.
        val sprint = SegmentPath(line(0.0 to 500.0, 0.0 to 650.0).map { at(it.first, it.second) }, 150.0)
        val wobbly = road.mapIndexed { i, (e, n) -> (e + if (i % 2 == 0) 3.0 else -3.0) to n }
        assertEquals(1, SegmentMatcher.find(sprint, ride(wobbly)).size)
    }

    @Test
    fun aRideWithFixesFarApartStillCounts() {
        // An imported file with a fix every 5 seconds, 50 m apart.
        val sparse = ride(line(0.0 to 0.0, 0.0 to 2_000.0, step = 50.0)).map { it.copy(timeMillis = it.timeMillis * 5) }
        val efforts = SegmentMatcher.find(segment, sparse)
        assertEquals(1, efforts.size)
        assertEquals(100_000.0, efforts[0].elapsedMs.toDouble(), 2_000.0)
    }

    @Test
    fun oneStrayFixOnAShortSegmentIsForgiven() {
        // 300 m with fixes every 50 m: one of the five inside is 26 m off, so only 80% are on it.
        val short = SegmentPath(line(0.0 to 500.0, 0.0 to 800.0).map { at(it.first, it.second) }, 300.0)
        val points = line(0.0 to 0.0, 0.0 to 2_000.0, step = 50.0).map { (e, n) -> if (n == 650.0) (e + 26.0) to n else e to n }
        assertEquals(1, SegmentMatcher.find(short, ride(points)).size)
    }
}
