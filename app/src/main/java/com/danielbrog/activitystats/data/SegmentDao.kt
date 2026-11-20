package com.danielbrog.activitystats.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A segment's efforts in the order to show them, with the ride each came from. */
data class SegmentEffortWithRide(
    val segmentId: String,
    val activityId: String,
    val occurrence: Int,
    val startedAt: Long,
    val elapsedMs: Long,
    val rideTitle: String,
)

/** A segment for the list: its best time and how many efforts, from rides not deleted. */
data class SegmentSummary(
    val id: String,
    val name: String,
    val distanceMeters: Double,
    /** Null when no ride has covered it yet. */
    val bestMs: Long?,
    val effortCount: Int,
)

/** Queries on `segments` and `segment_efforts`. */
@Dao
abstract class SegmentDao {

    @Insert
    abstract suspend fun insertSegment(segment: SegmentEntity)

    @Query("SELECT * FROM segments WHERE id = :id")
    abstract suspend fun getSegment(id: String): SegmentEntity?

    /** One segment, or null when it is missing or deleted. Updates itself. */
    @Query("SELECT * FROM segments WHERE id = :id AND deletedAt IS NULL")
    abstract fun observeSegment(id: String): Flow<SegmentEntity?>

    // Edits bump updatedAt so sync can tell which copy is newer, and turn an already synced
    // (or refused) segment back to PENDING so the change gets uploaded. The same as rides.

    /**
     * Returns how many rows changed: 0 for an unknown id, or when the name is already [name]
     * (so an unchanged name isn't an edit that needs uploading).
     */
    @Query(
        "UPDATE segments SET name = :name, updatedAt = :now, " +
            "syncState = CASE WHEN syncState IN ('${SyncState.SYNCED}', '${SyncState.FAILED}') THEN '${SyncState.PENDING}' " +
            "ELSE syncState END WHERE id = :id AND name IS NOT :name"
    )
    abstract suspend fun rename(id: String, name: String, now: Long): Int

    /** Soft delete: the row and its efforts stay, marked with when it was deleted. */
    @Query(
        "UPDATE segments SET deletedAt = :now, updatedAt = :now, " +
            "syncState = CASE WHEN syncState IN ('${SyncState.SYNCED}', '${SyncState.FAILED}') THEN '${SyncState.PENDING}' " +
            "ELSE syncState END WHERE id = :id"
    )
    abstract suspend fun softDelete(id: String, now: Long)

    /** Undoes a soft delete; its efforts come back with it. */
    @Query(
        "UPDATE segments SET deletedAt = NULL, updatedAt = :now, " +
            "syncState = CASE WHEN syncState IN ('${SyncState.SYNCED}', '${SyncState.FAILED}') THEN '${SyncState.PENDING}' " +
            "ELSE syncState END WHERE id = :id"
    )
    abstract suspend fun restore(id: String, now: Long)

    /** Every segment not deleted, by name. Updates itself. */
    @Query("SELECT * FROM segments WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    abstract fun observeSegments(): Flow<List<SegmentEntity>>

    /** Every segment not deleted, by name, with its best time and effort count. Updates itself. */
    @Query(
        "SELECT s.id, s.name, s.distanceMeters, MIN(e.elapsedMs) AS bestMs, COUNT(e.activityId) AS effortCount " +
            "FROM segments s LEFT JOIN segment_efforts e ON e.segmentId = s.id " +
            "AND e.activityId IN (SELECT id FROM activities WHERE deletedAt IS NULL) " +
            "WHERE s.deletedAt IS NULL GROUP BY s.id ORDER BY s.name COLLATE NOCASE"
    )
    abstract fun observeSummaries(): Flow<List<SegmentSummary>>

    /** Every segment not deleted, once. There are few, so matching filters them in Kotlin. */
    @Query("SELECT * FROM segments WHERE deletedAt IS NULL")
    abstract suspend fun activeSegments(): List<SegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertEfforts(efforts: List<SegmentEffortEntity>)

    @Query("DELETE FROM segment_efforts WHERE segmentId = :segmentId")
    abstract suspend fun deleteEffortsForSegment(segmentId: String)

    /**
     * Removes one ride's efforts on segments not deleted. A deleted segment keeps its efforts,
     * so Undo brings it back whole even if the ride was matched again in between.
     */
    @Query(
        "DELETE FROM segment_efforts WHERE activityId = :activityId " +
            "AND segmentId IN (SELECT id FROM segments WHERE deletedAt IS NULL)"
    )
    abstract suspend fun deleteEffortsForRide(activityId: String)

    /** Replaces every effort on one segment, in one go. */
    @Transaction
    open suspend fun replaceForSegment(segmentId: String, efforts: List<SegmentEffortEntity>) {
        deleteEffortsForSegment(segmentId)
        insertEfforts(efforts)
    }

    /** Replaces every effort one ride made, on any segment, in one go. */
    @Transaction
    open suspend fun replaceForRide(activityId: String, efforts: List<SegmentEffortEntity>) {
        deleteEffortsForRide(activityId)
        insertEfforts(efforts)
    }

    /** A segment's efforts from rides not deleted, fastest first (ties: earliest first). Updates itself. */
    @Query(
        "SELECT e.segmentId, e.activityId, e.occurrence, e.startedAt, e.elapsedMs, " +
            "a.title AS rideTitle FROM segment_efforts e JOIN activities a ON a.id = e.activityId " +
            "WHERE e.segmentId = :segmentId AND a.deletedAt IS NULL " +
            "ORDER BY e.elapsedMs, e.startedAt"
    )
    abstract fun observeEfforts(segmentId: String): Flow<List<SegmentEffortWithRide>>
}
