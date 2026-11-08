package com.danielbrog.activitystats.export

import com.danielbrog.activitystats.data.ActivityType
import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GpxReaderTest {

    private fun read(xml: String): GpxTrack = GpxReader.read(xml.byteInputStream())

    private fun reason(xml: String): GpxImportException.Reason {
        try {
            read(xml)
        } catch (e: GpxImportException) {
            return e.reason
        }
        fail("expected the file to be rejected")
        throw AssertionError()
    }

    private val garmin = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx creator="Garmin Connect" version="1.1" xmlns="http://www.topografix.com/GPX/1/1"
             xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
          <metadata><name>Hill reps</name><time>2026-11-07T14:00:00Z</time></metadata>
          <trk>
            <name>Track name</name>
            <type>road_biking</type>
            <trkseg>
              <trkpt lat="43.6500000" lon="-79.3800000"><ele>101.4</ele><time>2026-11-07T14:03:05Z</time>
                <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>142</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>
              </trkpt>
              <trkpt lat="43.6501000" lon="-79.3801000"><time>2026-11-07T14:03:06Z</time></trkpt>
            </trkseg>
            <trkseg>
              <trkpt lat="43.6502000" lon="-79.3802000"><ele>103.0</ele><time>2026-11-07T14:10:00Z</time></trkpt>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent()

    @Test
    fun readsPointsElevationAndTimeAcrossSegments() {
        val track = read(garmin)
        assertEquals(3, track.points.size)
        val first = track.points[0]
        assertEquals(43.65, first.latitude, 1e-9)
        assertEquals(-79.38, first.longitude, 1e-9)
        assertEquals(101.4, first.elevation!!, 1e-9)
        assertEquals(1_794_060_185_000L, first.timeMillis)
        assertNull(track.points[1].elevation)
        assertEquals(103.0, track.points[2].elevation!!, 1e-9)
    }

    @Test
    fun readsNameAndTypeAndIgnoresExtensions() {
        val track = read(garmin)
        assertEquals("Hill reps", track.name)
        assertEquals(ActivityType.RIDE, track.type)
    }

    @Test
    fun readsGpx10WithTheNameOnTheRoot() {
        val track = read(
            """
            <gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">
              <name>Old file</name>
              <trk><type>running</type><trkseg>
                <trkpt lat="43.65" lon="-79.38"><time>2026-11-07T14:03:05Z</time></trkpt>
                <trkpt lat="43.66" lon="-79.38"><time>2026-11-07T14:03:15Z</time></trkpt>
              </trkseg></trk>
            </gpx>
            """.trimIndent()
        )
        assertEquals("Old file", track.name)
        assertEquals(ActivityType.RUN, track.type)
        assertEquals(2, track.points.size)
    }

    @Test
    fun timesWithFractionsAndOffsetsAreRead() {
        assertEquals(1_794_060_185_000L, GpxReader.parseTime("2026-11-07T14:03:05Z"))
        assertEquals(1_794_060_185_250L, GpxReader.parseTime("2026-11-07T14:03:05.250Z"))
        assertEquals(1_794_060_185_000L, GpxReader.parseTime("2026-11-07T09:03:05-05:00"))
        assertEquals(1_794_060_185_000L, GpxReader.parseTime("2026-11-07T14:03:05")) // no zone: UTC
        assertNull(GpxReader.parseTime("yesterday"))
    }

    @Test
    fun pointsArePutInTimeOrderAndDuplicateTimesDropped() {
        val track = read(
            """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
              <trkpt lat="43.62" lon="-79.38"><time>2026-11-07T14:00:02Z</time></trkpt>
              <trkpt lat="43.60" lon="-79.38"><time>2026-11-07T14:00:00Z</time></trkpt>
              <trkpt lat="43.61" lon="-79.38"><time>2026-11-07T14:00:01Z</time></trkpt>
              <trkpt lat="43.99" lon="-79.38"><time>2026-11-07T14:00:01Z</time></trkpt>
            </trkseg></trk></gpx>
            """.trimIndent()
        )
        assertEquals(listOf(43.60, 43.61, 43.62), track.points.map { it.latitude })
    }

    @Test
    fun aPlannedRouteWithoutTimesIsRejected() {
        assertEquals(
            GpxImportException.Reason.NoTimes,
            reason(
                """
                <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
                  <rte><rtept lat="43.65" lon="-79.38"/><rtept lat="43.66" lon="-79.38"/></rte>
                  <trk><trkseg><trkpt lat="43.65" lon="-79.38"/><trkpt lat="43.66" lon="-79.38"/></trkseg></trk>
                </gpx>
                """.trimIndent()
            ),
        )
    }

    @Test
    fun filesThatArentGpxAreRejected() {
        assertEquals(GpxImportException.Reason.NotGpx, reason("this is not xml"))
        assertEquals(GpxImportException.Reason.NotGpx, reason("<kml><Document/></kml>"))
        assertEquals(GpxImportException.Reason.NotGpx, reason(""))
        assertEquals(
            GpxImportException.Reason.NoPoints,
            reason("""<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><metadata/></gpx>"""),
        )
    }

    @Test
    fun readsBackThisAppsOwnExport() {
        var seq = 0
        fun fix(second: Int, lat: Double, alt: Double?) = GpsData(
            seq = ++seq, timeMillis = 1_794_060_185_000L + second * 1_000L, latitude = lat, longitude = -79.38,
            accuracyMeters = 5f, speedMps = 5f, status = FixStatus.Kept, altitudeMeters = alt,
        )
        val fixes = listOf(fix(0, 43.65, 100.0), fix(1, 43.6501, null), fix(2, 43.6502, 102.5))
        val gpx = GpxWriter.toGpx("Tom & Jo's ride", ActivityType.RIDE, fixes.first().timeMillis, fixes)

        val track = read(gpx)
        assertEquals("Tom & Jo's ride", track.name)
        assertEquals(ActivityType.RIDE, track.type)
        assertEquals(fixes.map { it.timeMillis }, track.points.map { it.timeMillis })
        assertEquals(fixes.map { it.latitude }, track.points.map { it.latitude })
        assertEquals(listOf(100.0, null, 102.5), track.points.map { it.elevation })

        val imported = track.toFixes()
        assertTrue(imported.all { it.status == FixStatus.Kept })
        assertEquals(listOf(1, 2, 3), imported.map { it.seq })
    }

    @Test
    fun activityTypesMapToOurs() {
        assertEquals(ActivityType.RIDE, GpxReader.activityType("cycling"))
        assertEquals(ActivityType.RIDE, GpxReader.activityType("1"))
        assertEquals(ActivityType.RIDE, GpxReader.activityType(null))
        assertEquals(ActivityType.RUN, GpxReader.activityType("trail_running"))
        assertEquals(ActivityType.WALK, GpxReader.activityType("hiking"))
        assertEquals(ActivityType.WALK, GpxReader.activityType("10"))
    }
}
