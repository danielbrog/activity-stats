package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFileTest {

    private val fixes = listOf(
        GpsData(1, 1_000L, 43.65, -79.38, 4.5f, 6.2f, FixStatus.Kept, 101.4, stretch = 0),
        GpsData(2, 2_000L, 43.6501, -79.3801, 35f, null, FixStatus.DroppedAccuracy, null, stretch = 0),
        GpsData(3, 3_000L, 43.6501, -79.3801, 5f, 0.1f, FixStatus.DroppedStationary, 101.0, stretch = 0),
        GpsData(4, 900_000L, 43.70, -79.40, null, null, FixStatus.Kept, null, stretch = 1),
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
        assertTrue(text.startsWith("""{"version":2,"""))
        assertTrue(text.contains(""""status":"DroppedStationary""""))
        // Version 2's key for the pause stretch; version 1 called it "segment".
        assertTrue(text.contains(""""stretch":1"""))
        assertFalse(text.contains("segment"))
    }

    @Test
    fun aVersionOneFileStillReadsWithItsPausesLost() {
        val text = """{"version":1,"fixes":[{"seq":1,"t":1000,"lat":43.65,"lon":-79.38,"status":"Kept","segment":0},""" +
            """{"seq":2,"t":2000,"lat":43.66,"lon":-79.38,"status":"Kept","segment":1}]}"""
        val fixes = TrackFile.decode(text.gzipped())
        assertEquals(listOf(1, 2), fixes.map { it.seq })
        assertEquals(listOf(0, 0), fixes.map { it.stretch })
    }

    @Test
    fun anEmptyTrackRoundTrips() {
        assertEquals(emptyList<GpsData>(), TrackFile.decode(TrackFile.encode(emptyList())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNewerFormatIsRefused() {
        val text = """{"version":3,"fixes":[]}"""
        TrackFile.decode(text.gzipped())
    }

    /** The bytes of a track file holding [this] JSON, for testing formats the app doesn't write. */
    private fun String.gzipped(): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(toByteArray()) } }.toByteArray()
}
