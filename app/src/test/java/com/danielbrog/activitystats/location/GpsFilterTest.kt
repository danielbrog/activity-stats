package com.danielbrog.activitystats.location

import com.danielbrog.activitystats.stats.Geo
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsFilterTest {

    // About 1.11 m of latitude per 0.00001 degrees.
    private val baseLat = 43.653226
    private val baseLon = -79.383184
    private val metresPerDegreeLat = 111_195.0

    private var seq = 0

    private fun fix(
        northMeters: Double = 0.0,
        accuracy: Float? = 5f,
        speed: Float? = 0f,
    ) = GpsData(
        seq = ++seq,
        timeMillis = seq * 1_000L,
        latitude = baseLat + northMeters / metresPerDegreeLat,
        longitude = baseLon,
        accuracyMeters = accuracy,
        speedMps = speed,
    )

    @Test
    fun accuracyAtLimitIsKept() {
        assertEquals(FixStatus.Kept, GpsFilter().classify(fix(accuracy = 20f)))
    }

    @Test
    fun accuracyJustOverLimitIsDropped() {
        assertEquals(FixStatus.DroppedAccuracy, GpsFilter().classify(fix(accuracy = 20.1f)))
    }

    @Test
    fun missingAccuracyIsDropped() {
        assertEquals(FixStatus.DroppedAccuracy, GpsFilter().classify(fix(accuracy = null)))
    }

    @Test
    fun firstGoodFixIsKept() {
        assertEquals(FixStatus.Kept, GpsFilter().classify(fix(speed = 0f)))
    }

    @Test
    fun jitterWithinRadiusWhileStillIsDropped() {
        val filter = GpsFilter()
        filter.classify(fix(northMeters = 0.0))
        assertEquals(
            FixStatus.DroppedStationary,
            filter.classify(fix(northMeters = 2.0, accuracy = 5f, speed = 0.1f)),
        )
    }

    @Test
    fun jitterWithNoSpeedIsTreatedAsStill() {
        val filter = GpsFilter()
        filter.classify(fix(northMeters = 0.0))
        assertEquals(
            FixStatus.DroppedStationary,
            filter.classify(fix(northMeters = 2.0, accuracy = 5f, speed = null)),
        )
    }

    @Test
    fun movingFixWithinRadiusIsKept() {
        val filter = GpsFilter()
        filter.classify(fix(northMeters = 0.0))
        assertEquals(
            FixStatus.Kept,
            filter.classify(fix(northMeters = 2.0, accuracy = 5f, speed = 1.5f)),
        )
    }

    @Test
    fun slowDriftIsKeptOnceItLeavesTheRadiusOfTheLastKeptFix() {
        val filter = GpsFilter()
        assertEquals(FixStatus.Kept, filter.classify(fix(northMeters = 0.0)))
        assertEquals(FixStatus.DroppedStationary, filter.classify(fix(northMeters = 2.0, speed = 0.2f)))
        assertEquals(FixStatus.DroppedStationary, filter.classify(fix(northMeters = 4.0, speed = 0.2f)))
        // 6 m from the last kept fix, beyond the 5 m radius.
        assertEquals(FixStatus.Kept, filter.classify(fix(northMeters = 6.0, speed = 0.2f)))
        // The reference moved: 2 m beyond that is jitter again.
        assertEquals(FixStatus.DroppedStationary, filter.classify(fix(northMeters = 8.0, speed = 0.2f)))
    }

    @Test
    fun droppedAccuracyFixDoesNotBecomeTheReference() {
        val filter = GpsFilter()
        filter.classify(fix(northMeters = 0.0))
        filter.classify(fix(northMeters = 50.0, accuracy = 40f))
        // 2 m from the kept fix, not 48 m from the dropped one.
        assertEquals(FixStatus.DroppedStationary, filter.classify(fix(northMeters = 2.0, speed = 0f)))
    }

    @Test
    fun oneDegreeOfLatitudeIsAbout111Km() {
        val d = Geo.distanceMeters(43.0, -79.0, 44.0, -79.0)
        assertEquals(111_195.0, d, 111_195.0 * 0.01)
    }

    @Test
    fun knownDistanceTorontoToMontrealWithinOnePercent() {
        // Toronto City Hall to Montreal City Hall is about 504 km great-circle.
        val d = Geo.distanceMeters(43.6534, -79.3841, 45.5088, -73.5540)
        assertEquals(504_000.0, d, 504_000.0 * 0.01)
    }
}
