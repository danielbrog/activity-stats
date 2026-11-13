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

    // --- The number helpers: charts and other screens use these, so they must match the text.

    @Test
    fun numberHelpersConvertLikeTheText() {
        assertEquals(1.243, Format.distanceValue(1_243.0, DistanceUnit.Km), 1e-9)
        assertEquals(1.0, Format.distanceValue(1_609.344, DistanceUnit.Mi), 1e-9)
        assertEquals(10.8, Format.speedValue(3.0, DistanceUnit.Km), 1e-9)
        assertEquals(6.71, Format.speedValue(3.0, DistanceUnit.Mi), 0.01)
        assertEquals(152.0, Format.elevationValue(152.0, DistanceUnit.Km), 1e-9)
        assertEquals(498.69, Format.elevationValue(152.0, DistanceUnit.Mi), 0.01)
    }

    @Test
    fun milesReadAsExpectedEverywhere() {
        // Pinned strings, so a change to what riders see in miles can't slip through.
        assertEquals("7.72 mi", Format.distance(12_430.0, DistanceUnit.Mi))
        assertEquals("16.6 mph", Format.speed(7.4, DistanceUnit.Mi))
        assertEquals("1024 ft", Format.elevation(312.0, DistanceUnit.Mi))
        assertEquals("7.72", Format.distanceNumber(12_430.0, DistanceUnit.Mi))
    }

    @Test
    fun shortLengthsForAccuracy() {
        assertEquals("4 m", Format.shortLength(4.2, DistanceUnit.Km))
        assertEquals("4.2 m", Format.shortLength(4.2, DistanceUnit.Km, decimals = 1))
        assertEquals("13.8 ft", Format.shortLength(4.2, DistanceUnit.Mi, decimals = 1))
        assertEquals("112 ft", Format.shortLength(34.0, DistanceUnit.Mi))
    }

    @Test
    fun labelsMatchTheUnit() {
        assertEquals(listOf("km", "km/h", "m"), listOf(Format.distanceLabel(DistanceUnit.Km), Format.speedLabel(DistanceUnit.Km), Format.elevationLabel(DistanceUnit.Km)))
        assertEquals(listOf("mi", "mph", "ft"), listOf(Format.distanceLabel(DistanceUnit.Mi), Format.speedLabel(DistanceUnit.Mi), Format.elevationLabel(DistanceUnit.Mi)))
    }
}
