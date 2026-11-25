package com.danielbrog.activitystats.data

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class MappingTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(hour: Int, minute: Int) =
        LocalDateTime.of(2026, 11, 6, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun titleFollowsTimeOfDayBoundaries() {
        assertEquals("Night ride", rideTitle(at(4, 59), zone))
        assertEquals("Morning ride", rideTitle(at(5, 0), zone))
        assertEquals("Morning ride", rideTitle(at(11, 59), zone))
        assertEquals("Afternoon ride", rideTitle(at(12, 0), zone))
        assertEquals("Afternoon ride", rideTitle(at(16, 59), zone))
        assertEquals("Evening ride", rideTitle(at(17, 0), zone))
        assertEquals("Evening ride", rideTitle(at(20, 59), zone))
        assertEquals("Night ride", rideTitle(at(21, 0), zone))
        assertEquals("Night ride", rideTitle(at(0, 0), zone))
    }

    @Test
    fun titleUsesTheLocalTimeZone() {
        // 13:00 UTC is 08:00 in Toronto (EST, UTC-5) on this date.
        assertEquals("Morning ride", rideTitle(at(13, 0), ZoneId.of("America/Toronto")))
    }

    @Test
    fun fixRoundTripsThroughATrackPoint() {
        val fix = GpsData(
            seq = 42,
            timeMillis = 1_793_000_000_000L,
            latitude = 43.653226,
            longitude = -79.383184,
            accuracyMeters = 4.5f,
            speedMps = 6.25f,
            status = FixStatus.DroppedStationary,
            altitudeMeters = 91.3,
        )
        val point = fix.toTrackPoint("ride-1")

        assertEquals("ride-1", point.activityId)
        assertEquals("DroppedStationary", point.status)
        assertEquals(fix, point.toGpsData())
    }

    @Test
    fun missingValuesStayMissing() {
        val fix = GpsData(1, 1_000L, 1.0, 2.0, accuracyMeters = null, speedMps = null)
        assertEquals(fix, fix.toTrackPoint("a").toGpsData())
    }

    @Test
    fun unknownStatusIsNotTrusted() {
        val point = GpsData(1, 1_000L, 1.0, 2.0, 5f, 1f).toTrackPoint("a").copy(status = "FromTheFuture")
        assertEquals(FixStatus.DroppedAccuracy, point.toGpsData().status)
    }
}
