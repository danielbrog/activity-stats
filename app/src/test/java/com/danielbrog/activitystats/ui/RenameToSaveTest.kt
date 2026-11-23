package com.danielbrog.activitystats.ui

import com.danielbrog.activitystats.data.RideTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the rename dialog lets you save, and what it saves. */
class RenameToSaveTest {

    private fun toSave(text: String, current: String = "Morning ride") =
        renameToSave(text, current, RideTitle::clean)

    @Test
    fun aNewNameIsSavedCleaned() {
        assertEquals("Evening ride", toSave("  Evening ride  "))
    }

    @Test
    fun aBlankNameIsNotSaved() {
        assertNull(toSave("   "))
    }

    @Test
    fun anUnchangedNameIsNotSaved() {
        assertNull(toSave("Morning ride"))
        // Only spaces added: the same name once cleaned.
        assertNull(toSave(" Morning ride "))
    }

    @Test
    fun changingOnlyTheCaseIsARename() {
        assertEquals("Morning Ride", toSave("Morning Ride"))
    }
}
