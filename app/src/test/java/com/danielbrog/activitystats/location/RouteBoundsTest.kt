package com.danielbrog.activitystats.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteBoundsTest {

    @Test
    fun noPointsHasNoBounds() {
        assertNull(RouteBounds.of(emptyList()))
    }

    @Test
    fun onePointIsASinglePoint() {
        val bounds = RouteBounds.of(listOf(RoutePoint(43.65, -79.38)))!!
        assertTrue(bounds.isSinglePoint)
        assertEquals(RoutePoint(43.65, -79.38), bounds.centre)
    }

    @Test
    fun repeatedSamePointIsStillASinglePoint() {
        val p = RoutePoint(43.65, -79.38)
        assertTrue(RouteBounds.of(listOf(p, p, p))!!.isSinglePoint)
    }

    @Test
    fun boundsContainEveryPoint() {
        val route = listOf(
            RoutePoint(43.650, -79.380),
            RoutePoint(43.672, -79.401), // furthest north and west
            RoutePoint(43.631, -79.365), // furthest south and east
            RoutePoint(43.660, -79.390),
        )
        val bounds = RouteBounds.of(route)!!
        assertEquals(43.631, bounds.south, 0.0)
        assertEquals(-79.401, bounds.west, 0.0)
        assertEquals(43.672, bounds.north, 0.0)
        assertEquals(-79.365, bounds.east, 0.0)
        assertFalse(bounds.isSinglePoint)
        assertEquals(43.6515, bounds.centre.latitude, 1e-9)
    }
}
