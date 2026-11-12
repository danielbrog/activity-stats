package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFileTest {

    private val fixes = listOf(
        GpsData(1, 1_000L, 43.65, -79.38, 4.5f, 6.2f, FixStatus.Kept, 101.4, segment = 0),
        GpsData(2, 2_000L, 43.6501, -79.3801, 35f, null, FixStatus.DroppedAccuracy, null, segment = 0),
        GpsData(3, 3_000L, 43.6501, -79.3801, 5f, 0.1f, FixStatus.DroppedStationary, 101.0, segment = 0),
        GpsData(4, 900_000L, 43.70, -79.40, null, null, FixStatus.Kept, null, segment = 1),
    )

    @Test
    fun aRoundTripKeepsEveryFixExactly() {
        assertEquals(fixes, TrackFile.decode(TrackFile.encode(fixes)))
    }

    @Test
    fun fixesAreWrittenInOrder() {
        assertEquals(fixes, TrackFile.decode(TrackFile.encode(fixes.reversed())))
    }

    @Test
    fun theFileIsGzippedJsonWithItsVersion() {
        val bytes = TrackFile.encode(fixes)
        // Gzip's magic number.
        assertEquals(0x1f.toByte(), bytes[0])
        assertEquals(0x8b.toByte(), bytes[1])
        val text = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.decodeToString()
        assertTrue(text.startsWith("""{"version":1,"""))
        assertTrue(text.contains(""""status":"DroppedStationary""""))
    }

    @Test
    fun anEmptyTrackRoundTrips() {
        assertEquals(emptyList<GpsData>(), TrackFile.decode(TrackFile.encode(emptyList())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNewerFormatIsRefused() {
        val text = """{"version":2,"fixes":[]}"""
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        TrackFile.decode(out.toByteArray())
    }
}
