package com.danielbrog.activitystats.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideTitleTest {

    @Test
    fun normalNamesAreUnchanged() {
        assertEquals("Lakeshore loop", RideTitle.clean("Lakeshore loop"))
    }

    @Test
    fun spacesAtTheEndsAreTrimmed() {
        assertEquals("Hill repeats", RideTitle.clean("   Hill repeats  "))
    }

    @Test
    fun blankNamesAreRejected() {
        assertNull(RideTitle.clean(""))
        assertNull(RideTitle.clean("     "))
        assertNull(RideTitle.clean("\t\n"))
    }

    @Test
    fun longNamesAreCappedAtSixtyCharacters() {
        val cleaned = RideTitle.clean("x".repeat(75))!!
        assertEquals(RideTitle.MAX_LENGTH, cleaned.length)
    }

    @Test
    fun cappingNeverLeavesATrailingSpace() {
        // Character 60 is a space; it shouldn't end the saved name.
        val input = "a".repeat(59) + " bcd"
        assertEquals("a".repeat(59), RideTitle.clean(input))
    }
}
