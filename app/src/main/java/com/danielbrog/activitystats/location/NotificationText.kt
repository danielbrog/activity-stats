package com.danielbrog.activitystats.location

import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.Format

/**
 * The recording notification's second line: the distance so far in the chosen unit, and how
 * many GPS fixes were kept. "7.67 mi · 412 kept of 430 fixes". Plain Kotlin, unit-tested.
 */
fun notificationText(distanceMeters: Double, unit: DistanceUnit, kept: Int, total: Int): String =
    "${Format.distance(distanceMeters, unit)} · $kept kept of $total ${if (total == 1) "fix" else "fixes"}"
