package com.danielbrog.activitystats.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Queries on the `activities` table: one row per recorded ride. */
@Dao
abstract class ActivityDao {

    @Insert
    abstract suspend fun insertActivity(activity: ActivityEntity)

    /**
     * Inserts a ride downloaded from the cloud, unless one with its id is already here (then
     * returns -1 and leaves it alone): a download must never replace a ride on the phone.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfNew(activity: ActivityEntity): Long

    @Query("SELECT * FROM activities WHERE id = :id")
    abstract suspend fun getActivity(id: String): ActivityEntity?

    /** One activity, updating itself whenever it changes (renamed, deleted, restored). */
    @Query("SELECT * FROM activities WHERE id = :id")
    abstract fun observeActivity(id: String): Flow<ActivityEntity?>

    // Edits bump updatedAt so sync can tell which copy is newer, and turn an already synced
    // (or refused) activity back to PENDING so the change gets uploaded.

    @Query(
        "UPDATE activities SET title = :title, updatedAt = :now, " +
            "syncState = CASE WHEN syncState IN ('${SyncState.SYNCED}', '${SyncState.FAILED}') THEN '${SyncState.PENDING}' " +
            "ELSE syncState END WHERE id = :id"
    )
    abstract suspend fun rename(id: String, title: String, now: Long)

    /** Soft delete: the row stays, marked with when it was deleted. */
    @Query(
        "UPDATE activities SET deletedAt = :now, updatedAt = :now, " +
            "syncState = CASE WHEN syncState IN ('${SyncState.SYNCED}', '${SyncState.FAILED}') THEN '${SyncState.PENDING}' " +
            "ELSE syncState END WHERE id = :id"
    )
    abstract suspend fun softDelete(id: String, now: Long)

    /** Undoes a soft delete. */
    @Query(
        "UPDATE activities SET deletedAt = NULL, updatedAt = :now, " +
            "syncState = CASE WHEN syncState IN ('${SyncState.SYNCED}', '${SyncState.FAILED}') THEN '${SyncState.PENDING}' " +
            "ELSE syncState END WHERE id = :id"
    )
    abstract suspend fun restore(id: String, now: Long)

    /**
     * Gives every activity with no owner to [userId] and marks it waiting to upload. Leaves
     * updatedAt alone: the ride itself hasn't changed. Returns how many were claimed.
     */
    @Query("UPDATE activities SET userId = :userId, syncState = '${SyncState.PENDING}' WHERE userId IS NULL")
    abstract suspend fun claimUnowned(userId: String): Int

    // --- Cloud sync ---

    /**
     * Finished rides of [userId] waiting to upload, oldest first. A ride still recording is
     * never included, even though it is already PENDING, nor one whose climbing hasn't been
     * worked out yet: climbing goes up with the summary, and filling it in later isn't an edit,
     * so it wouldn't be re-sent.
     */
    @Query(
        "SELECT * FROM activities WHERE userId = :userId AND syncState = '${SyncState.PENDING}' " +
            "AND endedAt IS NOT NULL AND elevationGainMeters IS NOT NULL ORDER BY startedAt"
    )
    abstract suspend fun pendingUploads(userId: String): List<ActivityEntity>

    @Query("UPDATE activities SET trackUploaded = 1 WHERE id = :id")
    abstract suspend fun markTrackUploaded(id: String)

    /**
     * The server refused the copy at [sentUpdatedAt]: it waits for an edit or Sync now instead
     * of retrying every sync. A ride edited since that copy was sent stays PENDING, so the
     * edit, which may be the fix, goes up next time.
     */
    @Query(
        "UPDATE activities SET syncState = '${SyncState.FAILED}' " +
            "WHERE id = :id AND updatedAt = :sentUpdatedAt AND syncState = '${SyncState.PENDING}'"
    )
    abstract suspend fun markFailed(id: String, sentUpdatedAt: Long)

    /** Puts [userId]'s refused rides back in the queue, for Sync now. */
    @Query("UPDATE activities SET syncState = '${SyncState.PENDING}' WHERE userId = :userId AND syncState = '${SyncState.FAILED}'")
    abstract suspend fun retryFailed(userId: String)

    /**
     * How many changes of [userId]'s are waiting to upload: exactly what the next sync sends (the
     * same rules as [pendingUploads]). Deleted rides count too, since their deletion goes up.
     * Updates itself.
     */
    @Query(
        "SELECT COUNT(*) FROM activities WHERE userId = :userId AND syncState = '${SyncState.PENDING}' " +
            "AND endedAt IS NOT NULL AND elevationGainMeters IS NOT NULL"
    )
    abstract fun waitingCount(userId: String): Flow<Int>

    /** How many of [userId]'s rides the server refused. Updates itself. */
    @Query("SELECT COUNT(*) FROM activities WHERE userId = :userId AND syncState = '${SyncState.FAILED}'")
    abstract fun failedCount(userId: String): Flow<Int>

    /**
     * Marks a ride synced, but only if it hasn't changed since the copy at [sentUpdatedAt] was
     * sent. A rename made during the upload keeps it PENDING, so the rename goes up next time.
     */
    @Query(
        "UPDATE activities SET syncState = '${SyncState.SYNCED}' " +
            "WHERE id = :id AND updatedAt = :sentUpdatedAt AND syncState = '${SyncState.PENDING}'"
    )
    abstract suspend fun markSynced(id: String, sentUpdatedAt: Long): Int

    /**
     * Takes the cloud's copy of the fields a ride can be edited in and marks the ride synced,
     * but only if the cloud's copy is newer than the phone's. An edit made on the phone after
     * the upload began is newer still, so it is kept, stays PENDING and goes up next time.
     * Returns 1 if the cloud's copy was taken, 0 if not.
     */
    @Query(
        "UPDATE activities SET title = :title, deletedAt = :deletedAt, updatedAt = :updatedAt, " +
            "syncState = '${SyncState.SYNCED}' WHERE id = :id AND updatedAt < :updatedAt"
    )
    abstract suspend fun applyCloudCopy(id: String, title: String, deletedAt: Long?, updatedAt: Long): Int

    // --- Bounding boxes, for segment matching ---

    @Query("UPDATE activities SET minLat = :minLat, maxLat = :maxLat, minLon = :minLon, maxLon = :maxLon WHERE id = :id")
    abstract suspend fun setBounds(id: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double)

    /** Finished rides whose box hasn't been worked out yet, like those saved before it existed. */
    @Query(
        "SELECT id FROM activities a WHERE endedAt IS NOT NULL AND minLat IS NULL " +
            "AND EXISTS (SELECT 1 FROM track_points t WHERE t.activityId = a.id AND t.status = 'Kept')"
    )
    abstract suspend fun finishedWithoutBounds(): List<String>

    /** Finished rides not deleted whose box overlaps the given one. */
    @Query(
        "SELECT id FROM activities WHERE endedAt IS NOT NULL AND deletedAt IS NULL " +
            "AND minLat <= :maxLat AND maxLat >= :minLat AND minLon <= :maxLon AND maxLon >= :minLon"
    )
    abstract suspend fun ridesOverlapping(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<String>

    /** A not-deleted activity that started in the same second, or null: used to spot re-imports. */
    @Query(
        "SELECT id FROM activities WHERE deletedAt IS NULL AND startedAt / 1000 = :startedAtMillis / 1000 LIMIT 1"
    )
    abstract suspend fun idStartedInSameSecond(startedAtMillis: Long): String?

    /** Activities that never got an end time and started before [startedBefore]. */
    @Query(
        "SELECT * FROM activities WHERE endedAt IS NULL AND deletedAt IS NULL " +
            "AND startedAt < :startedBefore"
    )
    abstract suspend fun unfinishedStartedBefore(startedBefore: Long): List<ActivityEntity>

    @Query(
        "SELECT * FROM activities WHERE endedAt IS NOT NULL AND deletedAt IS NULL " +
            "ORDER BY startedAt DESC LIMIT 1"
    )
    abstract fun latestFinished(): Flow<ActivityEntity?>

    /** Every finished, not-deleted activity, newest first. Updates itself on changes. */
    @Query(
        "SELECT * FROM activities WHERE endedAt IS NOT NULL AND deletedAt IS NULL " +
            "ORDER BY startedAt DESC"
    )
    abstract fun allFinished(): Flow<List<ActivityEntity>>

    @Query("SELECT COUNT(*) FROM activities WHERE endedAt IS NOT NULL AND deletedAt IS NULL")
    abstract fun finishedCount(): Flow<Int>

    @Query(
        "UPDATE activities SET distanceMeters = :distanceMeters, movingTimeMs = :movingTimeMs, " +
            "elapsedTimeMs = :elapsedTimeMs, avgSpeedMps = :avgSpeedMps, " +
            "maxSpeedMps = :maxSpeedMps, updatedAt = :updatedAt WHERE id = :id"
    )
    abstract suspend fun updateStats(
        id: String,
        distanceMeters: Double,
        movingTimeMs: Long,
        elapsedTimeMs: Long,
        avgSpeedMps: Double,
        maxSpeedMps: Double,
        updatedAt: Long,
    )

    /**
     * Stores a ride's climbing, worked out from its points. Leaves updatedAt and syncState
     * alone: it is filled in from data the ride already had, not an edit.
     */
    @Query("UPDATE activities SET elevationGainMeters = :meters WHERE id = :id")
    abstract suspend fun setElevationGain(id: String, meters: Double)

    /** Finished rides whose climbing hasn't been worked out yet, like those saved before it existed. */
    @Query("SELECT id FROM activities WHERE endedAt IS NOT NULL AND elevationGainMeters IS NULL")
    abstract suspend fun finishedWithoutElevationGain(): List<String>

    @Query("UPDATE activities SET endedAt = :endedAt, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun setEndedAt(id: String, endedAt: Long, updatedAt: Long)

    /** Writes the final stats and the end time together. */
    @Transaction
    open suspend fun finish(
        id: String,
        endedAt: Long,
        distanceMeters: Double,
        movingTimeMs: Long,
        elapsedTimeMs: Long,
        avgSpeedMps: Double,
        maxSpeedMps: Double,
    ) {
        updateStats(id, distanceMeters, movingTimeMs, elapsedTimeMs, avgSpeedMps, maxSpeedMps, endedAt)
        setEndedAt(id, endedAt, endedAt)
    }
}
