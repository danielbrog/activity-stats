package com.danielbrog.activitystats.segments

/** Which of a ride's efforts get a lap number. Plain Kotlin, unit-tested. */
object Laps {
    /**
     * For each effort, given as its segment's id and its [occurrence] in the ride (0, 1, 2…),
     * the lap number to show (1, 2…), or null when that segment comes up only once in the ride:
     * "Lap 1" alone would only be noise.
     */
    fun numbers(efforts: List<Pair<String, Int>>): List<Int?> {
        val counts = efforts.groupingBy { it.first }.eachCount()
        return efforts.map { (segmentId, occurrence) ->
            if (counts.getValue(segmentId) > 1) occurrence + 1 else null
        }
    }
}
