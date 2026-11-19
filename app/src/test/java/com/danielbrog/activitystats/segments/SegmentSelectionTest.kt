package com.danielbrog.activitystats.segments

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.location.RoutePoint
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picking part of a ride: a road north from the origin, a fix every 10 m and every second. */
class SegmentSelectionTest {

    private val lat0 = 43.65
    private val lon0 = -79.38
    private val perDegLat = 111_195.0
    private val perDegLon = perDegLat * cos(Math.toRadians(lat0))

    private fun at(east: Double, north: Double) = RoutePoint(lat0 + north / perDegLat, lon0 + east / perDegLon)

    /** Fix [i] is [i] × 10 m north, at second [i] plus any [waits] (fix index to seconds) before it. */
    private fun ride(
        count: Int = 201,
        waits: Map<Int, Int> = emptyMap(),
        stretchFrom: Int = Int.MAX_VALUE,
    ): List<GpsData> {
        var extraSeconds = 0
        return (0 until count).map { i ->
            extraSeconds += waits[i] ?: 0
            val p = at(0.0, i * 10.0)
            GpsData(i + 1, (i + extraSeconds) * 1_000L, p.latitude, p.longitude, 5f, 10f, FixStatus.Kept,
                stretch = if (i >= stretchFrom) 1 else 0)
        }
    }

    @Test
    fun droppedFixesAreLeftOut() {
        val fixes = ride(11) + GpsData(99, 99_000L, lat0, lon0 + 1.0, 5f, 10f, FixStatus.DroppedAccuracy)
        assertEquals(11, SegmentSelection(fixes).kept.size)
    }

    @Test
    fun aTapPicksTheNearestFix() {
        val selection = SegmentSelection(ride())
        assertEquals(42, selection.tappedIndex(at(3.0, 421.0), current = 50..150, radiusMeters = 25.0))
    }

    @Test
    fun aTapFarFromTheRideIsIgnored() {
        assertNull(SegmentSelection(ride()).tappedIndex(at(200.0, 421.0), current = 50..150, radiusMeters = 25.0))
    }

    @Test
    fun onAnOutAndBackATapPicksThePassBeingAdjusted() {
        // 1 km north then back, a fix every 10 m: fix i and fix 200 - i are in the same place.
        val outAndBack = (0..200).map { i ->
            val p = at(0.0, (if (i <= 100) i else 200 - i) * 10.0)
            GpsData(i + 1, i * 1_000L, p.latitude, p.longitude, 5f, 10f, FixStatus.Kept)
        }
        val selection = SegmentSelection(outAndBack)
        // Picking part of the way out, a tap at 300 m means fix 30, not fix 170 on the way back.
        assertEquals(30, selection.tappedIndex(at(0.0, 300.0), current = 20..60, radiusMeters = 25.0))
        // Picking part of the way back, the same tap means fix 170.
        assertEquals(170, selection.tappedIndex(at(0.0, 300.0), current = 140..190, radiusMeters = 25.0))
    }

    @Test
    fun aPauseSplitsThePartIntoRuns() {
        val selection = SegmentSelection(ride(stretchFrom = 100))
        assertEquals(listOf(50..99, 100..150), selection.runs(50..150))
        assertEquals(listOf(0..99, 100..200), selection.stretches)
    }

    @Test
    fun aTapMovesTheNearerEnd() {
        val selection = SegmentSelection(ride())
        val range = 50..150
        assertEquals(20..150, selection.moveNearerEnd(range, 20)) // before the start
        assertEquals(50..180, selection.moveNearerEnd(range, 180)) // after the end
        assertEquals(70..150, selection.moveNearerEnd(range, 70)) // inside, nearer the start
        assertEquals(50..130, selection.moveNearerEnd(range, 130)) // inside, nearer the end
    }

    @Test
    fun aTapNeverLeavesNothingPicked() {
        val selection = SegmentSelection(ride())
        assertEquals(50..51, selection.moveNearerEnd(50..51, 51))
    }

    @Test
    fun distanceIsAlongTheRide() {
        assertEquals(1_000.0, SegmentSelection(ride()).distanceMeters(50..150), 1.0)
    }

    @Test
    fun aGoodPartShowsThisRidesTime() {
        val check = SegmentSelection(ride()).check(50..150)
        check as SelectionCheck.Ready
        assertEquals(1_000.0, check.distanceMeters, 1.0)
        assertEquals(100_000L, check.yourTimeMs)
        assertFalse(check.stoppedInside)
    }

    @Test
    fun aPartAcrossAPauseIsRefused() {
        assertEquals(SelectionCheck.CrossesPause, SegmentSelection(ride(stretchFrom = 100)).check(50..150))
    }

    @Test
    fun aPartUnder100mIsTooShort() {
        val check = SegmentSelection(ride()).check(50..55)
        assertTrue(check is SelectionCheck.TooShort)
        assertEquals(50.0, (check as SelectionCheck.TooShort).distanceMeters, 1.0)
    }

    @Test
    fun aStopInsideMeansThisRideWontCount() {
        val check = SegmentSelection(ride(waits = mapOf(101 to 60))).check(50..150)
        check as SelectionCheck.Ready
        assertNull(check.yourTimeMs)
        assertTrue(check.stoppedInside)
    }

    @Test
    fun aWaitAtTheStartIsLeftOutOfThisRidesTime() {
        // A minute at the lights at fix 50, where the part starts.
        val check = SegmentSelection(ride(waits = mapOf(51 to 60))).check(50..150)
        check as SelectionCheck.Ready
        assertEquals(99_000.0, check.yourTimeMs!!.toDouble(), 1_500.0)
        assertFalse(check.stoppedInside)
    }

    @Test
    fun theFirstPickIsTheMiddleHalf() {
        assertEquals(50..150, SegmentSelection(ride()).initialRange())
        assertEquals(0..1, SegmentSelection(ride(2)).initialRange())
    }
}
