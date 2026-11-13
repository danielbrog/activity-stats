package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.data.SyncState
import kotlinx.serialization.Serializable

/**
 * One ride as `GET /activities` lists it: the summary the phone uploaded, its `id`, and
 * `syncedAt`, the server's time of the last save, which the download picks up from.
 */
@Serializable
data class CloudRide(
    val id: String,
    val type: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val distanceMeters: Double,
    val movingTimeMs: Long,
    val elapsedTimeMs: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val elevationGainMeters: Double? = null,
    val title: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncedAt: Long,
) {
    /** The editable fields, for taking the cloud's copy of a ride already on the phone. */
    fun toCloudCopy() = CloudCopy(title = title, updatedAt = updatedAt, deletedAt = deletedAt)

    /**
     * The ride as saved on this phone after downloading it: owned by [userId], already in sync,
     * and with its track already in the cloud, so this phone never uploads it again.
     */
    fun toEntity(userId: String) = ActivityEntity(
        id = id,
        userId = userId,
        type = type,
        startedAt = startedAt,
        endedAt = endedAt,
        distanceMeters = distanceMeters,
        movingTimeMs = movingTimeMs,
        elapsedTimeMs = elapsedTimeMs,
        avgSpeedMps = avgSpeedMps,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = elevationGainMeters,
        title = title,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncState = SyncState.SYNCED,
        trackUploaded = true,
    )
}

/** One page of `GET /activities`, oldest `syncedAt` first; [nextCursor] is null on the last page. */
@Serializable
data class CloudPage(val items: List<CloudRide>, val nextCursor: String? = null)

internal fun parsePage(body: String): CloudPage = RideSummary.json.decodeFromString(CloudPage.serializer(), body)
