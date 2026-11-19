package com.danielbrog.activitystats.data

/**
 * Rules for segment names typed by the user. The same as a ride's today ([RideTitle]), kept
 * separate so either can change without the other. Plain Kotlin.
 */
object SegmentName {
    const val MAX_LENGTH = RideTitle.MAX_LENGTH

    /** The name to save: trimmed and capped at [MAX_LENGTH], or null if it is blank. */
    fun clean(input: String): String? = RideTitle.clean(input)
}
