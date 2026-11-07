package com.danielbrog.activitystats.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Queries on the `activities` table: one row per recorded ride. */
@Dao
abstract class ActivityDao {

    @Insert
    abstract suspend fun insertActivity(activity: ActivityEntity)

    @Query("SELECT * FROM activities WHERE id = :id")
    abstract suspend fun getActivity(id: String): ActivityEntity?

    /** One activity, updating itself whenever it changes (renamed, deleted, restored). */
    @Query("SELECT * FROM activities WHERE id = :id")
    abstract fun observeActivity(id: String): Flow<ActivityEntity?>

    // Edits bump updatedAt so sync can tell which copy is newer, and turn an already
    // synced activity back to PENDING so the change gets uploaded.

    @Query(
        "UPDATE activities SET title = :title, updatedAt = :now, " +
            "syncState = CASE WHEN syncState = '${SyncState.SYNCED}' THEN '${SyncState.PENDING}' " +
            "ELSE syncState END WHERE id = :id"
    )
    abstract suspend fun rename(id: String, title: String, now: Long)

    /** Soft delete: the row stays, marked with when it was deleted. */
    @Query(
        "UPDATE activities SET deletedAt = :now, updatedAt = :now, " +
            "syncState = CASE WHEN syncState = '${SyncState.SYNCED}' THEN '${SyncState.PENDING}' " +
            "ELSE syncState END WHERE id = :id"
    )
    abstract suspend fun softDelete(id: String, now: Long)

    /** Undoes a soft delete. */
    @Query(
        "UPDATE activities SET deletedAt = NULL, updatedAt = :now, " +
            "syncState = CASE WHEN syncState = '${SyncState.SYNCED}' THEN '${SyncState.PENDING}' " +
            "ELSE syncState END WHERE id = :id"
    )
    abstract suspend fun restore(id: String, now: Long)

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
