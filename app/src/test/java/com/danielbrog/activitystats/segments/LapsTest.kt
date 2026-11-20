package com.danielbrog.activitystats.segments

import org.junit.Assert.assertEquals
import org.junit.Test

class LapsTest {

    @Test
    fun aSegmentRiddenOnceHasNoLapNumber() {
        assertEquals(listOf<Int?>(null), Laps.numbers(listOf("a" to 0)))
    }

    @Test
    fun aSegmentRiddenTwiceIsLapsOneAndTwo() {
        assertEquals(listOf(1, 2), Laps.numbers(listOf("a" to 0, "a" to 1)))
    }

    @Test
    fun onlyTheRepeatedSegmentGetsLaps() {
        // Out on "a", along "b", back round "a" again.
        assertEquals(listOf(1, null, 2), Laps.numbers(listOf("a" to 0, "b" to 0, "a" to 1)))
    }

    @Test
    fun noEffortsNoLaps() {
        assertEquals(emptyList<Int?>(), Laps.numbers(emptyList()))
    }
}
