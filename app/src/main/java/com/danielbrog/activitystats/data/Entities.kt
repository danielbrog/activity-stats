package com.danielbrog.activitystats.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Activity types from the build guide. Rides are the default; the others come with editing. */
object ActivityType {
    const val RIDE = "ride"
    const val RUN = "run"
    const val WALK = "walk"
}

/**
 * One row per recorded activity, shaped for cloud sync from day one (build guide data model).
 * A row with no [endedAt] is still recording, or was cut off by a crash and needs recovery.
 */
@Entity(tableName = "activities", indices = [Index("endedAt"), Index("startedAt")])
data class ActivityEntity(
    /** UUID made on the phone, so two devices never create the same ID. */
    @PrimaryKey val id: String,
    /**
     * The account the ride belongs to (its Cognito id), or null if it was recorded while
     * signed out. Signing in claims every ride with no owner.
     */
    val userId: String? = null,
    val type: String = ActivityType.RIDE,
    /** Epoch milliseconds, UTC. Time zones are handled in the UI only. */
    val startedAt: Long,
    val endedAt: Long? = null,
    val distanceMeters: Double = 0.0,
    val movingTimeMs: Long = 0L,
    val elapsedTimeMs: Long = 0L,
    val avgSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    /** Total climbing, worked out from the points when the ride is saved; null until then. */
    val elevationGainMeters: Double? = null,
    val title: String,
    /** Lets sync tell which copy is newer. */
    val updatedAt: Long,
    /** Soft delete, so deletions reach other devices. */
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.LOCAL_ONLY,
    /**
     * True once the ride's track file is in the cloud. A finished ride's track never changes,
     * so it is uploaded once; later edits only re-send the summary.
     */
    @ColumnInfo(defaultValue = "0")
    val trackUploaded: Boolean = false,
    /**
     * The smallest box around the ride's kept fixes, so segment matching can skip rides that
     * are nowhere near a segment. Set when a ride is finished, imported or downloaded. Null for
     * a ride still recording, one with no kept fixes, or one saved before segments existed.
     */
    val minLat: Double? = null,
    val maxLat: Double? = null,
    val minLon: Double? = null,
    val maxLon: Double? = null,
)

/**
 * A ride's fastest time over one set distance, worked out from its track points. Every ride
 * gets a row for every [com.danielbrog.activitystats.stats.Effort], with [elapsedMs] null
 * when the ride is shorter than it, so a ride with no rows is one still to be worked out.
 * Derived data: it can be rebuilt from the points, so it is never synced.
 */
@Entity(tableName = "best_efforts", primaryKeys = ["activityId", "effort"], indices = [Index("effort")])
data class BestEffortEntity(
    val activityId: String,
    /** An [com.danielbrog.activitystats.stats.Effort.key], like "10km". */
    val effort: String,
    val elapsedMs: Long?,
)

/**
 * One row per GPS fix. Every fix is kept, dropped ones included, with the noise filter's
 * decision in [status], so the filter can be re-tuned and re-run on old activities.
 */
@Entity(tableName = "track_points", primaryKeys = ["activityId", "seq"])
data class TrackPointEntity(
    val activityId: String,
    val seq: Int,
    /** Epoch milliseconds, UTC. */
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    /** A [com.danielbrog.activitystats.location.FixStatus] name. */
    val status: String,
    /** The stretch between manual pauses; see [com.danielbrog.activitystats.location.GpsData.stretch]. */
    @ColumnInfo(defaultValue = "0")
    val stretch: Int = 0,
)

/**
 * A road segment the rider made from part of one of their rides: your own segments only
 * (downloaded public ones are [VerifiedSegmentEntity]). Rides of the same [sport] that cover
 * it get a [SegmentEffortEntity]. The user's own data, with the same sync fields as an
 * activity. Where it is (its ends and area) comes from [path]; see [toPathOrNull].
 *
 * The review fields ([status] to [verifiedSegmentId]) are a copy of what the server decided;
 * on the phone only Submit and Withdraw change them.
 */
@Entity(tableName = "segments", indices = [Index("deletedAt")])
data class SegmentEntity(
    /** UUID made on the phone. */
    @PrimaryKey val id: String,
    val userId: String? = null,
    val name: String,
    /** The segment's line, start to end, in Google's encoded polyline format. */
    val path: String,
    /** Measured along the ride it was made from, before simplifying: the distance to show. */
    val distanceMeters: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.LOCAL_ONLY,
    /** An [ActivityType]: only rides of this type are matched. Taken from the ride it was made from. */
    @ColumnInfo(defaultValue = "'${ActivityType.RIDE}'")
    val sport: String = ActivityType.RIDE,
    /** Stored as its name; the default must be [SegmentStatus.PRIVATE]'s (an enum name isn't a constant). */
    @ColumnInfo(defaultValue = "'PRIVATE'")
    val status: SegmentStatus = SegmentStatus.PRIVATE,
    val submittedAt: Long? = null,
    /** When an admin approved, rejected or unpublished it. */
    val decidedAt: Long? = null,
    /** The admin's reason, when rejected or unpublished. */
    val reviewNote: String? = null,
    /** The verified copy it became, once approved. */
    val verifiedSegmentId: String? = null,
)

/**
 * A verified public segment, downloaded from the server's catalogue by map tile. Its DAO has
 * no edit queries: it only saves what the server sent and removes what it no longer lists. Its [path]
 * never changes for an [id]; an admin can change only its [name] (which bumps [version]).
 */
@Entity(tableName = "verified_segments")
data class VerifiedSegmentEntity(
    /** The server's id: never the same as a personal segment's. */
    @PrimaryKey val id: String,
    val name: String,
    val path: String,
    val distanceMeters: Double,
    /** An [ActivityType]. */
    val sport: String,
    /** Its box, for a quick nearby check without decoding [path], and to know its tiles. */
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val version: Int,
    val verifiedAt: Long,
    val updatedAt: Long,
    /** When it was last downloaded, for clearing out ones not needed lately. */
    val cachedAt: Long,
)

/** A catalogue tile (a 4-character geohash, about 39 × 20 km) and when it was last downloaded. */
@Entity(tableName = "catalog_tiles")
data class CatalogTileEntity(
    @PrimaryKey val tile: String,
    val fetchedAt: Long,
)

/**
 * One time a ride covered a segment. Derived data, like [BestEffortEntity]: rebuilt from the
 * ride's points, so it is never synced. [occurrence] counts the times within one ride (laps).
 * An effort is always ridden in one go, with no pause or stop inside it.
 */
@Entity(
    tableName = "segment_efforts",
    primaryKeys = ["segmentId", "activityId", "occurrence"],
    indices = [Index("activityId")],
)
data class SegmentEffortEntity(
    val segmentId: String,
    val activityId: String,
    val occurrence: Int,
    val startedAt: Long,
    val elapsedMs: Long,
)
