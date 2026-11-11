package com.danielbrog.activitystats.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {

    private fun fix(seq: Int, status: FixStatus = FixStatus.Kept, segment: Int = 0) = GpsData(
        seq = seq,
        timeMillis = seq * 1_000L,
        latitude = 43.0 + seq * 0.0001,
        longitude = -79.0,
        accuracyMeters = 5f,
        speedMps = 5f,
        status = status,
        segment = segment,
    )

    private fun point(seq: Int) = RoutePoint(fix(seq).latitude, -79.0)

    @Test
    fun onlyKeptFixesJoinTheRouteInOrder() {
        val route = Route.extend(
            emptyList(),
            listOf(
                fix(1),
                fix(2, FixStatus.DroppedAccuracy),
                fix(3),
                fix(4, FixStatus.DroppedStationary),
                fix(5),
            ),
        )
        assertEquals(listOf(listOf(point(1), point(3), point(5))), route)
    }

    @Test
    fun extendingAppendsAfterExistingPoints() {
        val first = Route.extend(emptyList(), listOf(fix(1), fix(2)))
        val second = Route.extend(first, listOf(fix(3)))
        assertEquals(1, second.size)
        assertEquals(3, second.single().size)
        assertEquals(point(3), second.single().last())
    }

    @Test
    fun allDroppedLeavesTheRouteUnchanged() {
        val route = Route.extend(emptyList(), listOf(fix(1)))
        assertSame(route, Route.extend(route, listOf(fix(2, FixStatus.DroppedAccuracy))))
    }

    @Test
    fun aNewRecordingStartsWithAnEmptyRoute() {
        // The service replaces the whole state with a fresh TrackingUiState on Start.
        assertTrue(TrackingUiState(isTracking = true).route.isEmpty())
    }

    @Test
    fun longRideKeepsEveryKeptPoint() {
        // Three hours at one fix per second, far beyond the 1,000-fix list cap.
        var route = emptyList<List<RoutePoint>>()
        for (batch in 0 until 1_080) {
            route = Route.extend(route, (1..10).map { fix(batch * 10 + it) })
        }
        assertEquals(10_800, route.single().size)
        assertEquals(point(1), route.single().first())
    }

    // --- Manual pauses ---

    @Test
    fun resumingStartsANewLine() {
        var route = Route.extend(emptyList(), listOf(fix(1), fix(2)))
        route = Route.startLine(route)
        route = Route.extend(route, listOf(fix(3, segment = 1), fix(4, segment = 1)))
        assertEquals(listOf(listOf(point(1), point(2)), listOf(point(3), point(4))), route)
    }

    @Test
    fun startingALineTwiceOrBeforeAnyPointAddsNoEmptyLines() {
        assertTrue(Route.startLine(emptyList()).isEmpty())
        val once = Route.startLine(Route.extend(emptyList(), listOf(fix(1))))
        assertSame(once, Route.startLine(once))
        assertEquals(2, once.size)
    }

    @Test
    fun latestIsTheNewestPointEvenRightAfterResume() {
        val route = Route.startLine(Route.extend(emptyList(), listOf(fix(1), fix(2))))
        assertEquals(point(2), Route.latest(route))
        assertNull(Route.latest(emptyList()))
    }

    @Test
    fun aSavedRideSplitsIntoOneLinePerSegment() {
        val fixes = listOf(
            fix(1), fix(2), fix(3, FixStatus.DroppedAccuracy),
            fix(4, segment = 1), fix(5, segment = 1),
            fix(6, segment = 2),
        )
        assertEquals(
            listOf(listOf(point(1), point(2)), listOf(point(4), point(5)), listOf(point(6))),
            Route.of(fixes.shuffled()),
        )
    }

    @Test
    fun legMetersIsZeroAcrossAPause() {
        assertTrue(legMeters(fix(1), fix(2)) > 10.0)
        assertEquals(0.0, legMeters(fix(1), fix(200, segment = 1)), 0.0)
    }
}
