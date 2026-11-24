package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.data.SegmentEntity
import com.danielbrog.activitystats.data.SyncState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Which segment listing this version reads fully. Bump it when [CloudSegmentItem] gains a kind or
 * [com.danielbrog.activitystats.data.SegmentStatus] a value: an older version skipped those
 * items, so the segment cursor then starts again from 0 and they are read again.
 */
const val SEGMENT_LISTING_VERSION = 1

/**
 * A personal segment as `PUT /segments/{id}` takes it: exactly the rider's fields the backend's
 * `parseSegment` accepts. The owner, sync state and review fields never leave the phone (the
 * server drops them anyway). Every field is always written, nulls included.
 */
@Serializable
data class SegmentUpload(
    val name: String,
    val path: String,
    val distanceMeters: Double,
    val sport: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
) {
    fun toJson(): String = RideSummary.json.encodeToString(serializer(), this)

    companion object {
        fun from(segment: SegmentEntity) = SegmentUpload(
            name = segment.name,
            path = segment.path,
            distanceMeters = segment.distanceMeters,
            sport = segment.sport,
            createdAt = segment.createdAt,
            updatedAt = segment.updatedAt,
            deletedAt = segment.deletedAt,
        )
    }
}

/** One item of `GET /segments`: a segment, or the server's review of one. */
sealed interface CloudSegmentItem {
    /** The server's time of the last save, which the download picks up from. */
    val syncedAt: Long
}

/** A segment item (`kind: "segment"`): the rider's fields as last saved in the cloud. */
@Serializable
data class CloudSegment(
    val id: String,
    val name: String,
    val path: String,
    val distanceMeters: Double,
    val sport: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    override val syncedAt: Long,
) : CloudSegmentItem {
    /** The segment as saved on this phone after downloading it: owned by [userId], in sync. */
    fun toEntity(userId: String) = SegmentEntity(
        id = id,
        userId = userId,
        name = name,
        path = path,
        distanceMeters = distanceMeters,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncState = SyncState.SYNCED,
        sport = sport,
    )
}

/**
 * A review item (`kind: "review"`): the server's decision about one of your segments. [status]
 * is text, not a [com.danielbrog.activitystats.data.SegmentStatus], so a status this version
 * doesn't know yet can't fail the whole listing; see `SegmentStatus.fromServer`.
 */
@Serializable
data class CloudReview(
    val segmentId: String,
    val status: String? = null,
    val submittedAt: Long? = null,
    val decidedAt: Long? = null,
    val reviewNote: String? = null,
    val verifiedSegmentId: String? = null,
    override val syncedAt: Long,
) : CloudSegmentItem

/** One page of `GET /segments`, oldest `syncedAt` first; [nextCursor] is null on the last page. */
data class CloudSegmentPage(val items: List<CloudSegmentItem>, val nextCursor: String? = null)

/** A page's outer shape, read by a serializer so any other shape is a SerializationException. */
@Serializable
private class RawSegmentPage(val items: List<JsonObject>, val nextCursor: String? = null)

/**
 * Reads a page of `GET /segments`. Items are told apart by `kind`; one of a kind this version
 * doesn't know is skipped, so a newer server can add kinds without breaking older phones (see
 * [SEGMENT_LISTING_VERSION]). Throws a SerializationException if the page isn't what the
 * backend sends, as for rides.
 */
internal fun parseSegmentPage(body: String): CloudSegmentPage {
    val raw = RideSummary.json.decodeFromString(RawSegmentPage.serializer(), body)
    val items = raw.items.mapNotNull { item ->
        when (item.kindOf()) {
            "segment" -> RideSummary.json.decodeFromJsonElement(CloudSegment.serializer(), item)
            "review" -> RideSummary.json.decodeFromJsonElement(CloudReview.serializer(), item)
            else -> null
        }
    }
    return CloudSegmentPage(items, raw.nextCursor)
}

private fun JsonObject.kindOf(): String? = (this["kind"] as? JsonPrimitive)?.contentOrNull

/**
 * The cloud's copy from a 409 or 400 reply to `PUT /segments/{id}`:
 * `{"applied":false,"current":{"kind":"segment",…}}`. Null when the reply has no copy or isn't
 * what the backend sends.
 */
internal fun parseSegmentConflict(body: String): CloudSegment? =
    // Neither call suspends, so runCatching can't swallow a coroutine cancellation.
    runCatching {
        val current: JsonElement = RideSummary.json.parseToJsonElement(body).jsonObject["current"] ?: return null
        if (current.jsonObject.kindOf() != "segment") return null
        RideSummary.json.decodeFromJsonElement(CloudSegment.serializer(), current)
    }.getOrNull()
