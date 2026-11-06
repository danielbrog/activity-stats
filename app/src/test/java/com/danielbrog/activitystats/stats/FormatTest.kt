package com.danielbrog.activitystats.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun distanceInKmAndMiles() {
        assertEquals("1.24 km", Format.distance(1_243.0, DistanceUnit.Km))
        assertEquals("1.00 mi", Format.distance(1_609.344, DistanceUnit.Mi))
        assertEquals("0.00 km", Format.distance(0.0, DistanceUnit.Km))
    }

    @Test
    fun speedInKmhAndMph() {
        assertEquals("10.8 km/h", Format.speed(3.0, DistanceUnit.Km))
        assertEquals("6.7 mph", Format.speed(3.0, DistanceUnit.Mi))
    }

    @Test
    fun paceInMinutesPerKmAndMile() {
        assertEquals("5:33 /km", Format.pace(3.0, DistanceUnit.Km))
        assertEquals("8:56 /mi", Format.pace(3.0, DistanceUnit.Mi))
    }

    @Test
    fun paceRoundingNeverShowsSixtySeconds() {
        // 299.6 s per km rounds to 300 s, which must read 5:00, not 4:60.
        assertEquals("5:00 /km", Format.pace(1_000.0 / 299.6, DistanceUnit.Km))
    }

    @Test
    fun paceIsADashWhenTooSlow() {
        assertEquals("– /km", Format.pace(0.4, DistanceUnit.Km))
        assertEquals("– /mi", Format.pace(0.0, DistanceUnit.Mi))
    }

    @Test
    fun speedOrPaceFollowsTheToggle() {
        assertEquals("10.8 km/h", Format.speedOrPace(3.0, DistanceUnit.Km, SpeedDisplay.Speed))
        assertEquals("5:33 /km", Format.speedOrPace(3.0, DistanceUnit.Km, SpeedDisplay.Pace))
    }

    @Test
    fun clockUnderAndOverAnHour() {
        assertEquals("12:34", Format.clock(754_000L))
        assertEquals("1:02:03", Format.clock(3_723_000L))
        assertEquals("0:00", Format.clock(0L))
        assertEquals("0:00", Format.clock(-5_000L))
    }
}
