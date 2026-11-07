package com.danielbrog.activitystats.records

import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.data.BestEffortEntity
import com.danielbrog.activitystats.stats.Effort

/** The ride holding a record. */
data class RecordHolder(val rideId: String, val title: String, val startedAt: Long)

data class DistanceRecord(val holder: RecordHolder, val meters: Double)
data class TimeRecord(val holder: RecordHolder, val ms: Long)

/** Every record, each null until a ride sets it. [bestEfforts] has an entry per [Effort]. */
data class PersonalRecords(
    val longestRide: DistanceRecord?,
    val longestMovingTime: TimeRecord?,
    val bestEfforts: Map<Effort, TimeRecord?>,
) {
    companion object {
        /**
         * Picks the record holders. [rides] should be the finished, not-deleted rides; efforts
         * for any other ride are ignored, so deleting a record ride hands it to the next best.
         * On a tie the earlier ride keeps the record. Plain Kotlin, unit-tested.
         */
        fun from(rides: List<ActivityEntity>, efforts: List<BestEffortEntity>): PersonalRecords {
            val byId = rides.associateBy { it.id }
            val oldestFirst = rides.sortedBy { it.startedAt }
            fun ActivityEntity.holder() = RecordHolder(id, title, startedAt)

            // maxByOrNull keeps the first of equal values, so oldest-first breaks ties.
            val longest = oldestFirst.filter { it.distanceMeters > 0.0 }.maxByOrNull { it.distanceMeters }
            val longestMoving = oldestFirst.filter { it.movingTimeMs > 0L }.maxByOrNull { it.movingTimeMs }

            val fastest = efforts
                .mapNotNull { e ->
                    val ride = byId[e.activityId] ?: return@mapNotNull null
                    val effort = Effort.fromKey(e.effort) ?: return@mapNotNull null
                    val ms = e.elapsedMs ?: return@mapNotNull null
                    Triple(effort, ride, ms)
                }
                .groupBy { it.first }
                .mapValues { (_, list) ->
                    list.minWith(compareBy({ it.third }, { it.second.startedAt }))
                        .let { TimeRecord(it.second.holder(), it.third) }
                }

            return PersonalRecords(
                longestRide = longest?.let { DistanceRecord(it.holder(), it.distanceMeters) },
                longestMovingTime = longestMoving?.let { TimeRecord(it.holder(), it.movingTimeMs) },
                bestEfforts = Effort.entries.associateWith { fastest[it] },
            )
        }
    }
}
