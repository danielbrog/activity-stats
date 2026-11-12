package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.data.ActivityEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A ride's summary as `PUT /activities/{id}` takes it: exactly the fields the backend's
 * `parseSummary` accepts, in raw units. The owner and sync state never leave the phone; the
 * owner comes from the sign-in token. Every field is always written, nulls included, since
 * the backend rejects a missing one.
 */
@Serializable
data class RideSummary(
    val type: String,
    val startedAt: Long,
    val endedAt: Long?,
    val distanceMeters: Double,
    val movingTimeMs: Long,
    val elapsedTimeMs: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val elevationGainMeters: Double?,
    val title: String,
    val updatedAt: Long,
    val deletedAt: Long?,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        internal val json = Json { ignoreUnknownKeys = true }

        fun from(ride: ActivityEntity) = RideSummary(
            type = ride.type,
            startedAt = ride.startedAt,
            endedAt = ride.endedAt,
            distanceMeters = ride.distanceMeters,
            movingTimeMs = ride.movingTimeMs,
            elapsedTimeMs = ride.elapsedTimeMs,
            avgSpeedMps = ride.avgSpeedMps,
            maxSpeedMps = ride.maxSpeedMps,
            elevationGainMeters = ride.elevationGainMeters,
            title = ride.title,
            updatedAt = ride.updatedAt,
            deletedAt = ride.deletedAt,
        )
    }
}

/** The editable fields of the cloud's copy of a ride, from a 409 reply. */
@Serializable
data class CloudCopy(val title: String, val updatedAt: Long, val deletedAt: Long? = null)

/**
 * The cloud's copy from a 409 reply to `PUT /activities/{id}`: `{"applied":false,"current":{…}}`.
 * Null when the reply has no copy or isn't what the backend sends.
 */
internal fun parseConflict(body: String): CloudCopy? =
    // decodeFromString doesn't suspend, so runCatching can't swallow a coroutine cancellation.
    runCatching { RideSummary.json.decodeFromString(Conflict.serializer(), body).current }.getOrNull()

@Serializable
private data class Conflict(val current: CloudCopy? = null)
