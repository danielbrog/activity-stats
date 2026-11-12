package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.data.SyncState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideSummaryTest {

    private val ride = ActivityEntity(
        id = "ride-1",
        userId = "user-1",
        startedAt = 1_000L,
        endedAt = 2_000L,
        distanceMeters = 12_430.0,
        movingTimeMs = 1_801_000L,
        elapsedTimeMs = 1_954_000L,
        avgSpeedMps = 6.9,
        maxSpeedMps = 11.2,
        elevationGainMeters = null,
        title = "Morning ride",
        updatedAt = 2_000L,
        syncState = SyncState.PENDING,
    )

    @Test
    fun theJsonHasExactlyTheBackendsFields() {
        val obj = Json.parseToJsonElement(RideSummary.from(ride).toJson()).jsonObject
        // The same 12 fields as the backend's ActivitySummary: nothing more, nothing missing.
        assertEquals(
            setOf(
                "type", "startedAt", "endedAt", "distanceMeters", "movingTimeMs", "elapsedTimeMs",
                "avgSpeedMps", "maxSpeedMps", "elevationGainMeters", "title", "updatedAt", "deletedAt",
            ),
            obj.keys,
        )
        assertEquals("ride", obj["type"]!!.jsonPrimitive.content)
        assertEquals(1_801_000L, obj["movingTimeMs"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun nullsAreWrittenNotLeftOut() {
        // The backend rejects a missing field, but accepts null.
        val obj = Json.parseToJsonElement(RideSummary.from(ride).toJson()).jsonObject
        assertEquals(JsonNull, obj["elevationGainMeters"])
        assertEquals(JsonNull, obj["deletedAt"])
    }

    @Test
    fun theOwnerAndSyncStateNeverLeaveThePhone() {
        val text = RideSummary.from(ride).toJson()
        assertEquals(false, text.contains("user-1"))
        assertEquals(false, text.contains("syncState"))
        assertEquals(false, text.contains("ride-1"))
    }

    @Test
    fun aConflictReplyGivesTheCloudCopy() {
        val body = """{"applied":false,"current":{"id":"ride-1","type":"ride","title":"Renamed elsewhere",""" +
            """"updatedAt":5000,"deletedAt":null,"syncedAt":6000,"distanceMeters":1.0}}"""
        assertEquals(CloudCopy("Renamed elsewhere", 5_000L, null), parseConflict(body))
    }

    @Test
    fun aConflictWithoutACopyGivesNull() {
        assertNull(parseConflict("""{"applied":false,"current":null}"""))
    }
}
