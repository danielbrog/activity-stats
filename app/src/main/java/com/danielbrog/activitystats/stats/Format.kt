package com.danielbrog.activitystats.stats

import java.util.Locale
import kotlin.math.roundToLong

enum class DistanceUnit { Km, Mi }

enum class SpeedDisplay { Speed, Pace }

/**
 * Turns raw stats (metres, m/s, ms) into display text in the chosen units.
 * Plain Kotlin, unit-tested. Every result is "value unit" with one space, except [clock].
 */
object Format {
    const val METERS_PER_MILE = 1_609.344
    const val FEET_PER_METER = 3.280_839_9

    /** Elevation in metres alongside km, feet alongside miles: "152 m", "499 ft". */
    fun elevation(meters: Double, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.Km -> "%.0f m".format(Locale.US, meters)
        DistanceUnit.Mi -> "%.0f ft".format(Locale.US, meters * FEET_PER_METER)
    }

    fun distance(meters: Double, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.Km -> "%.2f km".format(Locale.US, meters / 1_000.0)
        DistanceUnit.Mi -> "%.2f mi".format(Locale.US, meters / METERS_PER_MILE)
    }

    fun speed(mps: Double, unit: DistanceUnit): String = when (unit) {
        DistanceUnit.Km -> "%.1f km/h".format(Locale.US, mps * 3.6)
        DistanceUnit.Mi -> "%.1f mph".format(Locale.US, mps * 3_600.0 / METERS_PER_MILE)
    }

    /** Minutes and seconds per km or mile, or a dash when too slow for pace to mean anything. */
    fun pace(mps: Double, unit: DistanceUnit): String {
        val label = if (unit == DistanceUnit.Km) "/km" else "/mi"
        val perKm = paceSecondsPerKm(mps) ?: return "– $label"
        val perUnit = if (unit == DistanceUnit.Km) perKm else perKm * METERS_PER_MILE / 1_000.0
        // Round the total first, so 299.6 s reads 5:00 and never 4:60.
        val seconds = perUnit.roundToLong()
        return "%d:%02d %s".format(Locale.US, seconds / 60, seconds % 60, label)
    }

    fun speedOrPace(mps: Double, unit: DistanceUnit, display: SpeedDisplay): String =
        when (display) {
            SpeedDisplay.Speed -> speed(mps, unit)
            SpeedDisplay.Pace -> pace(mps, unit)
        }

    /** "12:34" under an hour, "1:02:03" from an hour on. */
    fun clock(ms: Long): String {
        val total = (ms / 1_000L).coerceAtLeast(0L)
        val h = total / 3_600
        val m = (total % 3_600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%d:%02d".format(Locale.US, m, s)
    }
}
