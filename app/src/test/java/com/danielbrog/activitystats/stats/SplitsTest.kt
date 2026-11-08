package com.danielbrog.activitystats.stats

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitsTest {

    private val metresPerDegreeLat = 111_195.0
    private var seq = 0

    private fun fix(
        second: Int,
        northMeters: Double,
        speed: Float,
        altitude: Double? = null,
        status: FixStatus = FixStatus.Kept,
    ) = GpsData(
        seq = ++seq,
        timeMillis = second * 1_000L,
        latitude = 43.65 + northMeters / metresPerDegreeLat,
        longitude = -79.38,
        accuracyMeters = 5f,
        speedMps = speed,
        status = status,
        altitudeMeters = altitude,
    )

    /** One fix a second at a steady [speed] for [seconds]; height from [altitude]. */
    private fun steady(seconds: Int, speed: Double, altitude: ((Double) -> Double)? = null) =
        (0..seconds).map { s -> fix(s, s * speed, speed.toFloat(), altitude?.invoke(s * speed)) }

    @Test
    fun steadySpeedGivesEqualFullSplitsAndAPartialLastOne() {
        // 10 m/s for 350 s: 3.5 km.
        val splits = Splits.of(steady(350, 10.0), DistanceUnit.Km)
        assertEquals(4, splits.size)
        splits.take(3).forEach {
            assertFalse(it.isPartial)
            assertEquals(1_000.0, it.distanceMeters, 0.5)
            assertEquals(100_000.0, it.movingMs.toDouble(), 500.0)
            assertEquals(10.0, it.avgSpeedMps, 0.1)
        }
        assertTrue(splits.last().isPartial)
        assertEquals(500.0, splits.last().distanceMeters, 1.0)
        assertEquals(listOf(1, 2, 3, 4), splits.map { it.number })
    }

    @Test
    fun aStopInsideASplitDoesNotCountInItsTime() {
        // 500 m, a 60 s stop at a light, then 700 m, all at 10 m/s.
        val fixes = mutableListOf<GpsData>()
        for (s in 0..50) fixes += fix(s, s * 10.0, 10f)
        for (s in 51..110) fixes += fix(s, 500.0, 0f, status = FixStatus.DroppedStationary)
        for (s in 111..180) fixes += fix(s, 500.0 + (s - 110) * 10.0, 10f)
        val first = Splits.of(fixes, DistanceUnit.Km).first()
        // 100 s of riding; the stop is left out (give or take the second it takes to resume).
        assertEquals(100.0, first.movingMs / 1_000.0, 2.0)
    }

    @Test
    fun splitsAddUpToTheRide() {
        val fixes = steady(1_234, 8.3)
        val ride = summarize(fixes.first().timeMillis, fixes)
        val splits = Splits.of(fixes, DistanceUnit.Km)
        assertEquals(ride.distanceMeters, splits.sumOf { it.distanceMeters }, 0.01)
        assertEquals(ride.movingMs.toDouble(), splits.sumOf { it.movingMs }.toDouble(), splits.size.toDouble())
    }

    @Test
    fun mileSplitsAreAMileLong() {
        // 10 m/s for 400 s: 4 km, so two full miles and a partial.
        val splits = Splits.of(steady(400, 10.0), DistanceUnit.Mi)
        assertEquals(3, splits.size)
        assertEquals(Format.METERS_PER_MILE, splits[0].distanceMeters, 0.5)
        assertEquals(160.9, splits[0].movingMs / 1_000.0, 0.5)
        assertTrue(splits[2].isPartial)
    }

    @Test
    fun aSliverAtTheEndJoinsTheLastSplit() {
        // 2,005 m: two splits, the second 1,005 m, not a third 5 m row.
        val splits = Splits.of(steady(401, 5.0), DistanceUnit.Km)
        assertEquals(2, splits.size)
        assertFalse(splits[1].isPartial)
        assertEquals(1_005.0, splits[1].distanceMeters, 1.0)
    }

    @Test
    fun climbsGiveAPositiveHeightChange() {
        // 2 km: the first km climbs 30 m, the second is flat.
        val splits = Splits.of(steady(200, 10.0) { d -> 100.0 + minOf(d, 1_000.0) * 0.03 }, DistanceUnit.Km)
        assertEquals(30.0, splits[0].elevationChangeMeters!!, 2.0)
        assertEquals(0.0, splits[1].elevationChangeMeters!!, 2.0)
    }

    @Test
    fun noAltitudeMeansNoHeightChangeAndShortRidesHaveNoSplits() {
        assertNull(Splits.of(steady(200, 10.0), DistanceUnit.Km).first().elevationChangeMeters)
        assertTrue(Splits.of(steady(9, 10.0), DistanceUnit.Km).isEmpty()) // 90 m
        assertTrue(Splits.of(emptyList(), DistanceUnit.Km).isEmpty())
    }

    @Test
    fun heightChangeAndSplitLabelsFormat() {
        assertEquals("+12 m", Format.elevationChange(12.3, DistanceUnit.Km))
        assertEquals("−4 m", Format.elevationChange(-4.4, DistanceUnit.Km))
        assertEquals("0 m", Format.elevationChange(-0.2, DistanceUnit.Km))
        assertEquals("+33 ft", Format.elevationChange(10.0, DistanceUnit.Mi))
        assertEquals("0.43", Format.distanceNumber(430.0, DistanceUnit.Km))
    }
}
