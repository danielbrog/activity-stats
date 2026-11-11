package com.danielbrog.activitystats.export

import com.danielbrog.activitystats.data.ActivityType
import com.danielbrog.activitystats.data.toGpsData
import com.danielbrog.activitystats.data.toTrackPoint
import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import org.junit.Assert.assertEquals
import org.junit.Test

/** Manual pauses survive saving, export and import as track segments. */
class GpxSegmentsTest {

    private val start = 1_794_060_185_000L

    private fun fix(seq: Int, segment: Int, status: FixStatus = FixStatus.Kept) = GpsData(
        seq = seq,
        timeMillis = start + seq * 1_000L,
        latitude = 43.65 + seq * 0.0001,
        longitude = -79.38,
        accuracyMeters = 5f,
        speedMps = 5f,
        status = status,
        segment = segment,
    )

    private val paused = listOf(
        fix(1, 0), fix(2, 0),
        fix(3, 1), fix(4, 1, FixStatus.DroppedAccuracy), fix(5, 1),
        fix(6, 2),
    )

    @Test
    fun eachStretchIsItsOwnTrackSegment() {
        val text = GpxWriter.toGpx("Ride", ActivityType.RIDE, start, paused)
        assertEquals(3, Regex("<trkseg>").findAll(text).count())
        assertEquals(3, Regex("</trkseg>").findAll(text).count())
    }

    @Test
    fun aRideWithoutPausesIsOneSegment() {
        val text = GpxWriter.toGpx("Ride", ActivityType.RIDE, start, listOf(fix(1, 0), fix(2, 0)))
        assertEquals(1, Regex("<trkseg>").findAll(text).count())
    }

    @Test
    fun segmentsRoundTripThroughExportAndImport() {
        val text = GpxWriter.toGpx("Ride", ActivityType.RIDE, start, paused)
        val fixes = GpxReader.read(text.byteInputStream()).toFixes()
        assertEquals(listOf(0, 0, 1, 1, 2), fixes.map { it.segment })
    }

    @Test
    fun segmentsAreSavedWithThePoints() {
        val saved = fix(3, 1).toTrackPoint("a")
        assertEquals(1, saved.segment)
        assertEquals(1, saved.toGpsData().segment)
    }
}
