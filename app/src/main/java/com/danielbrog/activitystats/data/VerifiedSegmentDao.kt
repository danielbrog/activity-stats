package com.danielbrog.activitystats.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Queries on `verified_segments`. There are no edit queries (no rename or user delete): only
 * saving what the server sent and removing what it no longer lists. Filled in by the public
 * catalogue slice; until then the table stays empty.
 */
@Dao
interface VerifiedSegmentDao {

    @Upsert
    suspend fun upsert(segments: List<VerifiedSegmentEntity>)

    /** For when the server no longer lists it (unpublished, or replaced). */
    @Query("DELETE FROM verified_segments WHERE id = :id")
    suspend fun delete(id: String)

    /** Every verified segment of one [ActivityType], for matching rides of that type. */
    @Query("SELECT * FROM verified_segments WHERE sport = :sport")
    suspend fun forSport(sport: String): List<VerifiedSegmentEntity>
}
