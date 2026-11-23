package com.danielbrog.activitystats.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStateTest {

    @Test
    fun `a ride recorded while signed out stays on the phone`() {
        assertEquals(SyncState.LOCAL_ONLY, SyncState.forNewRide(null))
    }

    @Test
    fun `a ride recorded while signed in waits to upload`() {
        assertEquals(SyncState.PENDING, SyncState.forNewRide("11111111-aaaa"))
    }

    @Test
    fun `the four states are distinct, and a new ride is never failed`() {
        val states = listOf(SyncState.LOCAL_ONLY, SyncState.PENDING, SyncState.SYNCED, SyncState.FAILED)
        assertEquals(4, states.toSet().size)
        assertEquals(false, SyncState.forNewRide("someone") == SyncState.FAILED)
    }

    @Test
    fun `the SQL text for each state matches its stored name`() {
        // Room stores an enum as its name; the DAOs' SQL compares against these constants.
        assertEquals("'${SyncState.LOCAL_ONLY.name}'", SyncStateSql.LOCAL_ONLY)
        assertEquals("'${SyncState.PENDING.name}'", SyncStateSql.PENDING)
        assertEquals("'${SyncState.SYNCED.name}'", SyncStateSql.SYNCED)
        assertEquals("'${SyncState.FAILED.name}'", SyncStateSql.FAILED)
        assertEquals(4, SyncState.entries.size) // a new state needs its SQL text too
    }

    @Test
    fun `a new segment is private, matching the column's default`() {
        // The column default is written as 'PRIVATE' by hand, since an enum name isn't a constant.
        assertEquals("PRIVATE", SegmentStatus.PRIVATE.name)
        val segment = SegmentEntity(id = "s", name = "Hill", path = "", distanceMeters = 100.0, createdAt = 0, updatedAt = 0)
        assertEquals(SegmentStatus.PRIVATE, segment.status)
    }

    @Test
    fun `a ride with no owner is local only by default`() {
        val ride = ActivityEntity(id = "a", startedAt = 0, title = "Ride", updatedAt = 0)
        assertEquals(null, ride.userId)
        assertEquals(SyncState.LOCAL_ONLY, ride.syncState)
    }
}
