package com.danielbrog.activitystats.stats

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationSeriesTest {

    private val metresPerDegreeLat = 111_195.0
    private var seq = 0

    private fun fix(
        northMeters: Double,
        altitude: Double?,
        status: FixStatus = FixStatus.Kept,
    ) = GpsData(
        seq = ++seq,
        timeMillis = seq * 1_000L,
        latitude = 43.65 + northMeters / metresPerDegreeLat,
        longitude = -79.38,
        accuracyMeters = 5f,
        speedMps = 5f,
        status = status,
        altitudeMeters = altitude,
    )

    @Test
    fun skipsFixesWithoutAltitudeAndDroppedFixes() {
        val samples = ElevationSeries.fromFixes(
            listOf(
                fix(0.0, 100.0),
                fix(10.0, null),                                   // no altitude: skipped
                fix(20.0, 300.0, FixStatus.DroppedAccuracy),       // dropped: skipped
                fix(30.0, 104.0),
            )
        )
        assertEquals(2, samples.size)
        assertEquals(100.0, samples[0].altitudeMeters, 0.0)
        // Distance still counts the kept fix that had no altitude.
        assertEquals(30.0, samples[1].distanceMeters, 0.1)
        assertEquals(104.0, samples[1].altitudeMeters, 0.0)
    }

    @Test
    fun groupingKeepsTheAverageHeight() {
        // A 20 km ride, evenly spaced, with ±8 m of GPS noise around a 120 m road.
        val samples = (0 until 4_000).map {
            ElevationSample(it * 5.0, 120.0 + if (it % 2 == 0) 8.0 else -8.0)
        }
        val reduced = ElevationSeries.downsample(samples, maxPoints = 200)
        assertTrue(reduced.size <= 200)
        assertEquals(120.0, reduced.map { it.altitudeMeters }.average(), 0.01)
        assertTrue("noise is averaged out", reduced.all { it.altitudeMeters in 119.0..121.0 })
    }

    @Test
    fun rangeTicksRoundToTheRidesOwnRange() {
        assertEquals(listOf(60.0, 80.0, 100.0, 120.0, 140.0), SpeedSeries.niceRangeTicks(74.0, 131.0))
        assertEquals(listOf(-20.0, 0.0, 20.0, 40.0), SpeedSeries.niceRangeTicks(-12.0, 33.0))
    }

    @Test
    fun aFlatRideStillGetsASensibleAxis() {
        val ticks = SpeedSeries.niceRangeTicks(87.0, 87.0)
        assertTrue(ticks.size >= 2)
        assertTrue(ticks.first() < 87.0 && ticks.last() > 87.0)
    }

    @Test
    fun metresConvertToFeet() {
        assertEquals("152 m", Format.elevation(152.0, DistanceUnit.Km))
        assertEquals("499 ft", Format.elevation(152.0, DistanceUnit.Mi))
        assertEquals("1000 ft", Format.elevation(304.8, DistanceUnit.Mi))
    }
}
