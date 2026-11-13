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

    // --- Numbers in the chosen unit, for charts and anything else that needs the value itself.
    // The text functions below use these too, so every screen converts the same way.

    // Each is a `when` over the unit, so adding a unit won't compile until it is handled here.

    /** A distance in km or miles. */
    fun distanceValue(meters: Double, unit: DistanceUnit): Double = when (unit) {
        DistanceUnit.Km -> meters / 1_000.0
        DistanceUnit.Mi -> meters / METERS_PER_MILE
    }

    /** A speed in km/h or mph. */
    fun speedValue(mps: Double, unit: DistanceUnit): Double = when (unit) {
        DistanceUnit.Km -> mps * 3.6
        DistanceUnit.Mi -> mps * 3_600.0 / METERS_PER_MILE
    }

    /** A height or other short length: metres alongside km, feet alongside miles. */
    fun elevationValue(meters: Double, unit: DistanceUnit): Double = when (unit) {
        DistanceUnit.Km -> meters
        DistanceUnit.Mi -> meters * FEET_PER_METER
    }

    fun distanceLabel(unit: DistanceUnit): String = when (unit) {
        DistanceUnit.Km -> "km"
        DistanceUnit.Mi -> "mi"
    }

    fun speedLabel(unit: DistanceUnit): String = when (unit) {
        DistanceUnit.Km -> "km/h"
        DistanceUnit.Mi -> "mph"
    }

    fun elevationLabel(unit: DistanceUnit): String = when (unit) {
        DistanceUnit.Km -> "m"
        DistanceUnit.Mi -> "ft"
    }

    /**
     * A short length that isn't a height, such as GPS accuracy: "4 m", "13 ft", or with
     * [decimals], "4.2 m". Metres alongside km, feet alongside miles.
     */
    fun shortLength(meters: Double, unit: DistanceUnit, decimals: Int = 0): String =
        "%.${decimals}f %s".format(Locale.US, elevationValue(meters, unit), elevationLabel(unit))

    /** Elevation in metres alongside km, feet alongside miles: "152 m", "499 ft". */
    fun elevation(meters: Double, unit: DistanceUnit): String =
        "%.0f %s".format(Locale.US, elevationValue(meters, unit), elevationLabel(unit))

    /** A height change with its sign: "+12 m", "−4 m", "0 m"; feet alongside miles. */
    fun elevationChange(meters: Double, unit: DistanceUnit): String {
        val rounded = Math.round(elevationValue(meters, unit))
        val sign = when {
            rounded > 0 -> "+"
            rounded < 0 -> "−"
            else -> ""
        }
        return "$sign${kotlin.math.abs(rounded)} ${elevationLabel(unit)}"
    }

    /** Just the number of a distance, for split labels: "0.43" (km or mi). */
    fun distanceNumber(meters: Double, unit: DistanceUnit): String =
        "%.2f".format(Locale.US, distanceValue(meters, unit))

    fun distance(meters: Double, unit: DistanceUnit): String =
        "%.2f %s".format(Locale.US, distanceValue(meters, unit), distanceLabel(unit))

    fun speed(mps: Double, unit: DistanceUnit): String =
        "%.1f %s".format(Locale.US, speedValue(mps, unit), speedLabel(unit))

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
