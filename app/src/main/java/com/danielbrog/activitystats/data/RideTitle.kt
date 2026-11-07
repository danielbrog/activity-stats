package com.danielbrog.activitystats.data

/** Rules for ride names typed by the user. Plain Kotlin, unit-tested. */
object RideTitle {
    const val MAX_LENGTH = 60

    /** The name to save: trimmed and capped at [MAX_LENGTH], or null if it is blank. */
    fun clean(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_LENGTH).trimEnd()
    }
}
