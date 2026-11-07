package com.danielbrog.activitystats.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Queries on the `track_points` table: one row per GPS fix. */
@Dao
interface TrackPointDao {

    /** Ignores points already saved, so a repeated batch can't fail or duplicate. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("SELECT * FROM track_points WHERE activityId = :activityId ORDER BY seq")
    suspend fun pointsFor(activityId: String): List<TrackPointEntity>
}
