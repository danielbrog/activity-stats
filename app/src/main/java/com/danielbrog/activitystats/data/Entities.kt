package com.danielbrog.activitystats.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Sync states from the build guide's data model. LOCAL_ONLY: no account, stays on this phone.
 * PENDING: belongs to an account and is waiting to upload. SYNCED: the cloud has this copy.
 */
object SyncState {
    const val LOCAL_ONLY = "LOCAL_ONLY"
    const val PENDING = "PENDING"
    const val SYNCED = "SYNCED"

    /** A new ride waits to upload if it has an owner, and stays on the phone if not. */
    fun forNewRide(userId: String?): String = if (userId == null) LOCAL_ONLY else PENDING
}

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
    val syncState: String = SyncState.LOCAL_ONLY,
    /**
     * True once the ride's track file is in the cloud. A finished ride's track never changes,
     * so it is uploaded once; later edits only re-send the summary.
     */
    @ColumnInfo(defaultValue = "0")
    val trackUploaded: Boolean = false,
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
    /** The stretch between manual pauses; see [com.danielbrog.activitystats.location.GpsData.segment]. */
    @ColumnInfo(defaultValue = "0")
    val segment: Int = 0,
)
