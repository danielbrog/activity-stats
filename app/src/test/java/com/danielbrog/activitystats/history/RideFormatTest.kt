package com.danielbrog.activitystats.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class RideFormatTest {

    private val zone: ZoneId = ZoneId.of("America/Toronto")
    private val locale = Locale.US

    private fun at(day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun dateLineIn12HourTime() {
        assertEquals("Fri, Nov 6 · 7:42 AM", RideFormat.dateLine(at(6, 7, 42), zone, locale))
    }

    @Test
    fun dateWithYear() {
        assertEquals("Nov 6, 2026", RideFormat.date(at(6, 7, 42), zone, locale))
    }

    @Test
    fun dateLineIn24HourTime() {
        assertEquals("Fri, Nov 6 · 19:05", RideFormat.dateLine(at(6, 19, 5), zone, locale, use24Hour = true))
    }

    @Test
    fun dateLineUsesTheLocalTimeZone() {
        // 02:30 UTC on Nov 7 is still the evening of Nov 6 in Toronto.
        val millis = LocalDateTime.of(2026, 11, 7, 2, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("Fri, Nov 6 · 9:30 PM", RideFormat.dateLine(millis, zone, locale))
    }

    @Test
    fun timeRangeOnOneDay() {
        assertEquals("7:42 AM – 8:31 AM", RideFormat.timeRange(at(6, 7, 42), at(6, 8, 31), zone, locale))
    }

    @Test
    fun timeRangeCrossingMidnight() {
        assertEquals(
            "11:50 PM – 12:20 AM (+1 day)",
            RideFormat.timeRange(at(6, 23, 50), at(7, 0, 20), zone, locale),
        )
    }

    @Test
    fun timeRangeOverSeveralDays() {
        assertEquals(
            "10:00 PM – 6:00 AM (+2 days)",
            RideFormat.timeRange(at(6, 22, 0), at(8, 6, 0), zone, locale),
        )
    }
}
