package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.data.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRideTest {

    /** Shaped like the backend's reply: toApi() swaps userId and activityId for `id`. */
    private val reply = """
        {"items":[
          {"id":"ride-1","type":"ride","startedAt":1000,"endedAt":2000,"distanceMeters":12430.5,
           "movingTimeMs":1801000,"elapsedTimeMs":1954000,"avgSpeedMps":6.9,"maxSpeedMps":11.2,
           "elevationGainMeters":null,"title":"Morning ride","updatedAt":2000,"deletedAt":null,
           "syncedAt":3000},
          {"id":"ride-2","type":"run","startedAt":4000,"endedAt":5000,"distanceMeters":0,
           "movingTimeMs":0,"elapsedTimeMs":1000,"avgSpeedMps":0,"maxSpeedMps":0,
           "elevationGainMeters":12.5,"title":"Gone","updatedAt":6000,"deletedAt":6000,
           "syncedAt":7000,"somethingNew":true}
        ],"nextCursor":"abc"}
    """.trimIndent()

    @Test
    fun aListingIsRead() {
        val page = parsePage(reply)
        assertEquals("abc", page.nextCursor)
        assertEquals(listOf("ride-1", "ride-2"), page.items.map { it.id })
        val first = page.items[0]
        assertEquals(12_430.5, first.distanceMeters, 0.0)
        assertNull(first.elevationGainMeters)
        assertNull(first.deletedAt)
        assertEquals(3_000L, first.syncedAt)
        assertEquals(6_000L, page.items[1].deletedAt)
    }

    @Test
    fun theLastPageHasNoCursor() {
        assertNull(parsePage("""{"items":[],"nextCursor":null}""").nextCursor)
    }

    @Test
    fun aDownloadedRideIsSavedAsOwnedAndAlreadyInSync() {
        val ride = parsePage(reply).items[0].toEntity("user-1")
        assertEquals("user-1", ride.userId)
        assertEquals(SyncState.SYNCED, ride.syncState)
        // Its track is already in the cloud: this phone must never upload one for it.
        assertTrue(ride.trackUploaded)
        assertEquals("Morning ride", ride.title)
        assertEquals(2_000L, ride.endedAt)
    }
}
