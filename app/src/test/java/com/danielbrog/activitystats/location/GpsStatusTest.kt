package com.danielbrog.activitystats.location

import com.danielbrog.activitystats.stats.DistanceUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsStatusTest {

    private fun fix(accuracy: Float?) = PreviewFix(51.5, -0.1, accuracy)

    @Test
    fun `no fix yet is finding`() {
        assertEquals(GpsStatus.Finding, GpsPreview().status)
    }

    @Test
    fun `location switched off wins over everything`() {
        assertEquals(GpsStatus.Off, GpsPreview(locationEnabled = false).status)
        assertEquals(GpsStatus.Off, GpsPreview(locationEnabled = false, fix = fix(3f)).status)
    }

    @Test
    fun `accurate to 20 m or better is ready`() {
        assertEquals(GpsStatus.Ready(4f), GpsPreview(fix = fix(4f)).status)
        assertEquals(GpsStatus.Ready(20f), GpsPreview(fix = fix(20f)).status)
    }

    @Test
    fun `worse than 20 m is weak`() {
        assertEquals(GpsStatus.Weak(20.5f), GpsPreview(fix = fix(20.5f)).status)
        assertEquals(GpsStatus.Weak(35f), GpsPreview(fix = fix(35f)).status)
    }

    @Test
    fun `ready matches the recording filter's limit`() {
        // A fix the status calls ready must be one GpsFilter keeps.
        val filter = GpsFilter()
        val atLimit = GpsData(1, 0L, 51.5, -0.1, GpsStatus.READY_ACCURACY_METERS, 5f)
        assertEquals(FixStatus.Kept, filter.classify(atLimit))
    }

    @Test
    fun `a fix with no accuracy is weak`() {
        assertEquals(GpsStatus.Weak(null), GpsPreview(fix = fix(null)).status)
    }

    @Test
    fun `losing the signal goes back to finding, keeping the last fix for the map`() {
        val lost = GpsPreview(fix = fix(4f), available = false)
        assertEquals(GpsStatus.Finding, lost.status)
        assertEquals(fix(4f), lost.fix)
    }

    @Test
    fun `text shows accuracy in metres or feet to match the unit`() {
        assertEquals("Finding GPS…", GpsStatus.Finding.text(DistanceUnit.Km))
        assertEquals("Location is turned off", GpsStatus.Off.text(DistanceUnit.Km))
        assertEquals("GPS ready · ±4 m", GpsStatus.Ready(4.2f).text(DistanceUnit.Km))
        assertEquals("GPS ready · ±13 ft", GpsStatus.Ready(4f).text(DistanceUnit.Mi))
        assertEquals("GPS weak · ±35 m", GpsStatus.Weak(35f).text(DistanceUnit.Km))
        assertEquals("GPS weak", GpsStatus.Weak(null).text(DistanceUnit.Km))
    }

    @Test
    fun `a very good fix never reads zero`() {
        assertEquals("GPS ready · ±1 m", GpsStatus.Ready(0.3f).text(DistanceUnit.Km))
    }
}
