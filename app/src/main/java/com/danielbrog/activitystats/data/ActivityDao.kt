package com.danielbrog.activitystats.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ActivityDao {

    @Insert
    abstract suspend fun insertActivity(activity: ActivityEntity)

    /** Ignores points already saved, so a repeated batch can't fail or duplicate. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("SELECT * FROM activities WHERE id = :id")
    abstract suspend fun getActivity(id: String): ActivityEntity?

    @Query("SELECT * FROM track_points WHERE activityId = :activityId ORDER BY seq")
    abstract suspend fun pointsFor(activityId: String): List<TrackPointEntity>

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

    /** Saves a batch of points and the activity's latest stats together. */
    @Transaction
    open suspend fun savePointsAndStats(
        points: List<TrackPointEntity>,
        id: String,
        distanceMeters: Double,
        movingTimeMs: Long,
        elapsedTimeMs: Long,
        avgSpeedMps: Double,
        maxSpeedMps: Double,
        updatedAt: Long,
    ) {
        if (points.isNotEmpty()) insertPoints(points)
        updateStats(id, distanceMeters, movingTimeMs, elapsedTimeMs, avgSpeedMps, maxSpeedMps, updatedAt)
    }

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
