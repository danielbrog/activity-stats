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
    fun `a ride with no owner is local only by default`() {
        val ride = ActivityEntity(id = "a", startedAt = 0, title = "Ride", updatedAt = 0)
        assertEquals(null, ride.userId)
        assertEquals(SyncState.LOCAL_ONLY, ride.syncState)
    }
}
