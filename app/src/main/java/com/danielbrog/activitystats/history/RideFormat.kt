package com.danielbrog.activitystats.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Date and time text for rides. Plain Kotlin, unit-tested with a fixed zone and locale. */
object RideFormat {

    private fun timeFormatter(use24Hour: Boolean, locale: Locale) =
        DateTimeFormatter.ofPattern(if (use24Hour) "H:mm" else "h:mm a", locale)

    /** "Fri, Nov 6 · 7:42 AM" (or "· 07:42"-style when [use24Hour]). */
    fun dateLine(
        startedAtMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        use24Hour: Boolean = false,
    ): String {
        val start = Instant.ofEpochMilli(startedAtMillis).atZone(zone)
        val date = DateTimeFormatter.ofPattern("EEE, MMM d", locale).format(start)
        return "$date · ${timeFormatter(use24Hour, locale).format(start)}"
    }

    /** "Nov 6, 2026": the day a ride started, with the year, for records. */
    fun date(
        startedAtMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
        .format(Instant.ofEpochMilli(startedAtMillis).atZone(zone))

    /** "7:42 AM – 8:31 AM", with "(+1 day)" when the ride ends on a later date. */
    fun timeRange(
        startedAtMillis: Long,
        endedAtMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        use24Hour: Boolean = false,
    ): String {
        val start = Instant.ofEpochMilli(startedAtMillis).atZone(zone)
        val end = Instant.ofEpochMilli(endedAtMillis).atZone(zone)
        val format = timeFormatter(use24Hour, locale)
        val days = ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate())
        val suffix = when {
            days == 1L -> " (+1 day)"
            days > 1L -> " (+$days days)"
            else -> ""
        }
        return "${format.format(start)} – ${format.format(end)}$suffix"
    }
}
