package com.danielbrog.activitystats.stats

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedSeriesTest {

    private val metresPerDegreeLat = 111_195.0
    private var seq = 0

    private fun fix(second: Int, northMeters: Double, speed: Float? = null, status: FixStatus = FixStatus.Kept) =
        GpsData(
            seq = ++seq,
            timeMillis = second * 1_000L,
            latitude = 43.65 + northMeters / metresPerDegreeLat,
            longitude = -79.38,
            accuracyMeters = 5f,
            speedMps = speed,
            status = status,
        )

    @Test
    fun onlyKeptFixesAreUsedAndDistanceAccumulates() {
        val samples = SpeedSeries.fromFixes(
            listOf(
                fix(0, 0.0, speed = 5f),
                fix(1, 5.0, speed = 5f),
                fix(2, 900.0, speed = 40f, status = FixStatus.DroppedAccuracy),
                fix(3, 15.0, speed = 5f),
            ),
        )
        assertEquals(3, samples.size)
        assertEquals(0.0, samples[0].distanceMeters, 0.01)
        assertEquals(5.0, samples[1].distanceMeters, 0.05)
        assertEquals(15.0, samples[2].distanceMeters, 0.05)
        assertTrue(samples.none { it.speedMps > 10 })
    }

    @Test
    fun reportedSpeedIsUsedWithFallbackWhenMissing() {
        val samples = SpeedSeries.fromFixes(
            listOf(
                fix(0, 0.0, speed = null),
                fix(1, 8.0, speed = 7.5f), // reported speed wins
                fix(3, 20.0, speed = null), // 12 m in 2 s -> 6 m/s
            ),
        )
        assertEquals(0.0, samples[0].speedMps, 0.0)
        assertEquals(7.5, samples[1].speedMps, 1e-6)
        assertEquals(6.0, samples[2].speedMps, 0.05)
    }

    @Test
    fun downsamplingStaysWithinTheLimitAndKeepsTheAverage() {
        // An hour at 1 fix per second, alternating 5 and 7 m/s, evenly spaced.
        val samples = (0 until 3_600).map { SpeedSample(it * 6.0, if (it % 2 == 0) 5.0 else 7.0) }
        val reduced = SpeedSeries.downsample(samples, maxPoints = 200)

        assertTrue(reduced.size <= 200)
        assertEquals(samples.map { it.speedMps }.average(), reduced.map { it.speedMps }.average(), 0.01)
        // Each group averages out the jitter.
        assertTrue(reduced.all { it.speedMps in 5.9..6.1 })
        // Still in distance order.
        assertEquals(reduced.sortedBy { it.distanceMeters }, reduced)
    }

    @Test
    fun shortRidesAreLeftAsTheyAre() {
        val samples = (0 until 50).map { SpeedSample(it * 5.0, 5.0) }
        assertSame(samples, SpeedSeries.downsample(samples, maxPoints = 200))
    }

    @Test
    fun axisTicksRoundToSensibleSteps() {
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0), SpeedSeries.niceTicks(34.0))
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0, 20.0), SpeedSeries.niceTicks(18.3))
        assertEquals(listOf(0.0, 0.5, 1.0, 1.5, 2.0), SpeedSeries.niceTicks(1.9))
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0), SpeedSeries.niceTicks(40.0))
        assertEquals(listOf(0.0), SpeedSeries.niceTicks(0.0))
    }
}
