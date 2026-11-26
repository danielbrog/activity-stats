package com.danielbrog.activitystats.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** The sync after a limit resets: booking a later one over an earlier one would leave rides waiting. */
class ResetBookingTest {

    private val booked = mutableListOf<Long>()
    private val booking = ResetBooking { booked += it }

    @Test
    fun noLimitBooksNothing() {
        booking.after(null)
        assertEquals(emptyList<Long>(), booked)
    }

    @Test
    fun anEarlierResetReplacesALaterOne() {
        booking.after(WEEK) // the weekly ride limit, found first
        booking.after(MIDNIGHT) // then a daily one
        assertEquals(listOf(WEEK, MIDNIGHT), booked)
    }

    @Test
    fun aLaterOrTheSameResetIsNotBookedAgain() {
        booking.after(MIDNIGHT)
        booking.after(WEEK)
        booking.after(MIDNIGHT)
        booking.after(null)
        assertEquals(listOf(MIDNIGHT), booked)
    }

    private companion object {
        const val MIDNIGHT = 1_795_651_200_000L
        const val WEEK = 1_795_996_800_000L
    }
}
