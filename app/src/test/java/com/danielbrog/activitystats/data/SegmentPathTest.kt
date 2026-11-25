package com.danielbrog.activitystats.data

import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.segments.Polyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A stored segment line becomes a path for the matcher, or null if it can't be one. */
class SegmentPathTest {

    private fun segment(path: String) = SegmentEntity(
        id = "s",
        userId = null,
        name = "S",
        path = path,
        distanceMeters = 1_000.0,
        createdAt = 1L,
        updatedAt = 1L,
        syncState = SyncState.LOCAL_ONLY,
    )

    @Test
    fun aGoodLineBecomesAPath() {
        val line = Polyline.encode(listOf(RoutePoint(43.65, -79.38), RoutePoint(43.66, -79.38)))
        assertEquals(2, segment(line).toPathOrNull()?.points?.size)
    }

    @Test
    fun aLineThatCantBeOneIsNull() {
        assertNull(segment("_").toPathOrNull()) // ends in the middle of a number
        assertNull(segment("??").toPathOrNull()) // one point only
        assertNull(segment("").toPathOrNull()) // no points
        assertNull(segment(" ").toPathOrNull()) // not a polyline character
    }

    @Test
    fun decodeOrNullNeverThrows() {
        assertNull(Polyline.decodeOrNull("_"))
        assertEquals(1, Polyline.decodeOrNull("??")?.size)
    }
}
