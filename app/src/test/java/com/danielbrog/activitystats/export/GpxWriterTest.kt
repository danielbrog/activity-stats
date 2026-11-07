package com.danielbrog.activitystats.export

import com.danielbrog.activitystats.data.ActivityType
import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class GpxWriterTest {

    // 2026-11-07 14:03:05 UTC
    private val start = 1_794_060_185_000L
    private var seq = 0

    private fun fix(
        second: Int,
        lat: Double = 43.65,
        altitude: Double? = 120.0,
        status: FixStatus = FixStatus.Kept,
    ) = GpsData(
        seq = ++seq,
        timeMillis = start + second * 1_000L,
        latitude = lat,
        longitude = -79.38,
        accuracyMeters = 5f,
        speedMps = 5f,
        status = status,
        altitudeMeters = altitude,
    )

    private fun gpx(fixes: List<GpsData>, title: String = "Morning Ride", type: String = ActivityType.RIDE) =
        GpxWriter.toGpx(title, type, start, fixes)

    @Test
    fun hasAGpx11HeaderAndCyclingType() {
        val text = gpx(listOf(fix(0)))
        assertTrue(text.startsWith("""<?xml version="1.0" encoding="UTF-8"?>"""))
        assertTrue(text.contains("""<gpx version="1.1" creator="Activity Stats" xmlns="http://www.topografix.com/GPX/1/1""""))
        assertTrue(text.contains("<name>Morning Ride</name>"))
        assertTrue(text.contains("<type>cycling</type>"))
        assertTrue(text.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun writesOnlyKeptPointsInOrder() {
        val text = gpx(
            listOf(
                fix(0, lat = 43.1),
                fix(1, lat = 43.2, status = FixStatus.DroppedAccuracy),
                fix(2, lat = 43.3, status = FixStatus.DroppedStationary),
                fix(3, lat = 43.4),
            )
        )
        assertEquals(2, Regex("<trkpt ").findAll(text).count())
        assertTrue(text.contains("lat=\"43.1000000\""))
        assertTrue(text.contains("lat=\"43.4000000\""))
        assertFalse(text.contains("43.2000000"))
        assertTrue(text.indexOf("43.1000000") < text.indexOf("43.4000000"))
    }

    @Test
    fun pointsWithoutAltitudeHaveNoElevation() {
        val text = gpx(listOf(fix(0, altitude = 152.34), fix(1, altitude = null)))
        assertEquals(1, Regex("<ele>").findAll(text).count())
        assertTrue(text.contains("<ele>152.3</ele>"))
    }

    @Test
    fun timesAreUtc() {
        val text = gpx(listOf(fix(0), fix(65)))
        assertTrue(text.contains("<time>2026-11-07T14:03:05Z</time>"))
        assertTrue(text.contains("<time>2026-11-07T14:04:10Z</time>"))
    }

    @Test
    fun specialCharactersInTheTitleAreEscaped() {
        val text = gpx(emptyList(), title = """Tom & Jo's <"hill"> ride""")
        assertTrue(text.contains("<name>Tom &amp; Jo&apos;s &lt;&quot;hill&quot;&gt; ride</name>"))
    }

    @Test
    fun aRideWithNoPointsIsStillAValidFile() {
        val text = gpx(emptyList())
        assertTrue(text.contains("<trkseg>"))
        assertTrue(text.contains("</trkseg>"))
        assertFalse(text.contains("<trkpt"))
    }

    @Test
    fun fileNameUsesTitleAndLocalDate() {
        val toronto = ZoneId.of("America/Toronto")
        assertEquals("Morning Ride 2026-11-07.gpx", GpxWriter.fileName("Morning Ride", start, toronto))
        assertEquals("Lap 1 2 2026-11-07.gpx", GpxWriter.fileName("Lap 1/2", start, toronto))
        assertEquals("Activity 2026-11-07.gpx", GpxWriter.fileName("???", start, toronto))
    }
}
