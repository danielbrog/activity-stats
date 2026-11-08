package com.danielbrog.activitystats.stats

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import org.junit.Assert.assertEquals
import org.junit.Test

class ElevationGainTest {

    private val metresPerDegreeLat = 111_195.0
    private var seq = 0

    /** A fix [northMeters] up the road at [altitude]. */
    private fun fix(northMeters: Double, altitude: Double?, status: FixStatus = FixStatus.Kept) = GpsData(
        seq = ++seq,
        timeMillis = seq * 1_000L,
        latitude = 43.65 + northMeters / metresPerDegreeLat,
        longitude = -79.38,
        accuracyMeters = 5f,
        speedMps = 5f,
        status = status,
        altitudeMeters = altitude,
    )

    /** One fix every 5 m of road, with the height given by [height] at each distance. */
    private fun road(lengthMeters: Int, height: (Double) -> Double): List<GpsData> =
        (0..lengthMeters step 5).map { d -> fix(d.toDouble(), height(d.toDouble())) }

    @Test
    fun aFlatRoadWithGpsNoiseHasAlmostNoGain() {
        // 20 km at 120 m, with the altitude jumping ±8 m from fix to fix.
        var i = 0
        val gain = ElevationGain.of(road(20_000) { 120.0 + if (i++ % 2 == 0) 8.0 else -8.0 })
        assertEquals(0.0, gain, 5.0)
    }

    @Test
    fun aSteadyClimbCountsInFull() {
        // 2 km rising from 100 m to 200 m.
        val gain = ElevationGain.of(road(2_000) { 100.0 + it / 20.0 })
        assertEquals(100.0, gain, 2.0)
    }

    @Test
    fun bumpsUnderTheThresholdAreIgnored() {
        // Rolling bumps every 400 m for 10 km, 4 m from trough to top (under the 5 m threshold).
        val gain = ElevationGain.of(road(10_000) { 150.0 + 2.0 * kotlin.math.sin(it / 400.0 * 2 * Math.PI) })
        assertEquals(0.0, gain, 0.01)
    }

    @Test
    fun upDownUpAddsBothClimbs() {
        // Up 40 m over 1 km, down 30 m over 1 km, up 50 m over 1 km: 90 m of climbing.
        val gain = ElevationGain.of(
            road(3_000) { d ->
                when {
                    d <= 1_000 -> 100.0 + d * 0.04
                    d <= 2_000 -> 140.0 - (d - 1_000) * 0.03
                    else -> 110.0 + (d - 2_000) * 0.05
                }
            }
        )
        assertEquals(90.0, gain, 3.0)
    }

    @Test
    fun fixesWithoutAltitudeAndDroppedFixesAreSkipped() {
        val fixes = road(1_000) { 100.0 + it / 20.0 }.toMutableList() // a 50 m climb
        fixes += fix(1_005.0, null)
        fixes += fix(1_010.0, 900.0, FixStatus.DroppedAccuracy)
        assertEquals(50.0, ElevationGain.of(fixes), 2.0)
    }

    @Test
    fun tooLittleDataIsZero() {
        assertEquals(0.0, ElevationGain.of(emptyList()), 0.0)
        assertEquals(0.0, ElevationGain.of(listOf(fix(0.0, 100.0))), 0.0)
        assertEquals(0.0, ElevationGain.of(listOf(fix(0.0, null), fix(10.0, null))), 0.0)
    }
}
