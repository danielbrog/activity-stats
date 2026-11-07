package com.danielbrog.activitystats.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {

    private fun fix(seq: Int, status: FixStatus = FixStatus.Kept) = GpsData(
        seq = seq,
        timeMillis = seq * 1_000L,
        latitude = 43.0 + seq * 0.0001,
        longitude = -79.0,
        accuracyMeters = 5f,
        speedMps = 5f,
        status = status,
    )

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
        assertEquals(listOf(fix(1), fix(3), fix(5)).map { RoutePoint(it.latitude, it.longitude) }, route)
    }

    @Test
    fun extendingAppendsAfterExistingPoints() {
        val first = Route.extend(emptyList(), listOf(fix(1), fix(2)))
        val second = Route.extend(first, listOf(fix(3)))
        assertEquals(3, second.size)
        assertEquals(RoutePoint(fix(3).latitude, -79.0), second.last())
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
        var route = emptyList<RoutePoint>()
        for (batch in 0 until 1_080) {
            route = Route.extend(route, (1..10).map { fix(batch * 10 + it) })
        }
        assertEquals(10_800, route.size)
        assertEquals(RoutePoint(fix(1).latitude, -79.0), route.first())
    }
}
