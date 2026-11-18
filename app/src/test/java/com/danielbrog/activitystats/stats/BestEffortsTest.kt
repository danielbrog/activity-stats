package com.danielbrog.activitystats.stats

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BestEffortsTest {

    // Geo.distanceMeters uses this Earth radius, so northward steps come out exact.
    private val metresPerDegreeLat = 111_195.0
    private var seq = 0

    private fun fix(millis: Long, northMeters: Double, status: FixStatus = FixStatus.Kept) = GpsData(
        seq = ++seq,
        timeMillis = millis,
        latitude = 43.65 + northMeters / metresPerDegreeLat,
        longitude = -79.38,
        accuracyMeters = 5f,
        speedMps = null,
        status = status,
    )

    /** One fix a second at [speeds] metres per second, one value per second. */
    private fun ride(speeds: List<Double>): List<GpsData> {
        var north = 0.0
        val fixes = mutableListOf(fix(0, 0.0))
        speeds.forEachIndexed { second, v ->
            north += v
            fixes += fix((second + 1) * 1_000L, north)
        }
        return fixes
    }

    private fun Long?.seconds(): Double {
        assertNotNull("expected an effort", this)
        return this!! / 1_000.0
    }

    @Test
    fun steadySpeedGivesDistanceOverSpeed() {
        // 25 minutes at 10 m/s = 15 km.
        val efforts = BestEfforts.find(ride(List(1_500) { 10.0 }))
        assertEquals(500.0, efforts[Effort.Km5].seconds(), 0.5)
        assertEquals(1_000.0, efforts[Effort.Km10].seconds(), 0.5)
        assertEquals(804.7, efforts[Effort.Mi5].seconds(), 0.5)
    }

    @Test
    fun findsTheFastWindowInTheMiddle() {
        // 5 km slow at 5 m/s, then 5 km fast at 12.5 m/s, then 5 km slow again.
        val speeds = List(1_000) { 5.0 } + List(400) { 12.5 } + List(1_000) { 5.0 }
        val efforts = BestEfforts.find(ride(speeds))
        assertEquals(400.0, efforts[Effort.Km5].seconds(), 0.5)
    }

    @Test
    fun aRideShorterThanTheDistanceHasNoEffort() {
        // 8 km: a 5 km and a 5 mi (8.05 km) effort? Only the 5 km.
        val efforts = BestEfforts.find(ride(List(800) { 10.0 }))
        assertNotNull(efforts[Effort.Km5])
        assertNull(efforts[Effort.Mi5])
        assertNull(efforts[Effort.Km10])
        assertNull(efforts[Effort.Km40])
    }

    @Test
    fun stopsInsideTheWindowCount() {
        // 2.5 km, a 60 s stop at a light, then 2.6 km, all at 10 m/s. Every 5 km window
        // contains the stop, so the best is 500 s of riding plus the 60 s stop.
        val fixes = mutableListOf(fix(0, 0.0))
        for (s in 1..250) fixes += fix(s * 1_000L, s * 10.0)
        for (s in 251..310) fixes += fix(s * 1_000L, 2_500.0)
        for (s in 311..570) fixes += fix(s * 1_000L, 2_500.0 + (s - 310) * 10.0)
        val efforts = BestEfforts.find(fixes)
        assertEquals(560.0, efforts[Effort.Km5].seconds(), 0.5)
    }

    @Test
    fun droppedFixesAreIgnored() {
        val fixes = ride(List(600) { 10.0 }).toMutableList()
        // A wild jump 50 km away that the filter dropped must not create a fake effort.
        fixes.add(300, fix(300_500L, 50_000.0, FixStatus.DroppedAccuracy))
        val efforts = BestEfforts.find(fixes)
        assertEquals(500.0, efforts[Effort.Km5].seconds(), 0.5)
        assertNull(efforts[Effort.Km10])
    }

    @Test
    fun theStartIsInterpolatedBetweenFixes() {
        // Fixes 3 km apart: 0 m at 0 s, 3 km at 300 s, 6 km at 600 s. A 5 km window ending
        // at 6 km starts at 1 km, a third of the way to the second fix: 100 s in.
        val fixes = listOf(fix(0, 0.0), fix(300_000, 3_000.0), fix(600_000, 6_000.0))
        val efforts = BestEfforts.find(fixes, listOf(Effort.Km5))
        assertEquals(500.0, efforts[Effort.Km5].seconds(), 0.01)
    }

    @Test
    fun keysRoundTripAndUnitsSplit() {
        Effort.entries.forEach { assertEquals(it, Effort.fromKey(it.key)) }
        assertNull(Effort.fromKey("1km"))
        assertEquals(listOf(Effort.Km5, Effort.Km10, Effort.Km20, Effort.Km40), Effort.forUnit(DistanceUnit.Km))
        assertEquals(listOf(Effort.Mi5, Effort.Mi10, Effort.Mi25), Effort.forUnit(DistanceUnit.Mi))
    }
}
