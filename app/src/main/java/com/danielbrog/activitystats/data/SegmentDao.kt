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

/** Queries on `segments` and `segment_efforts`. */
@Dao
abstract class SegmentDao {

    @Insert
    abstract suspend fun insertSegment(segment: SegmentEntity)

    @Query("SELECT * FROM segments WHERE id = :id")
    abstract suspend fun getSegment(id: String): SegmentEntity?

    /** Every segment not deleted, by name. Updates itself. */
    @Query("SELECT * FROM segments WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    abstract fun observeSegments(): Flow<List<SegmentEntity>>

    /** Segments not deleted whose box overlaps the given one. */
    @Query(
        "SELECT * FROM segments WHERE deletedAt IS NULL " +
            "AND minLat <= :maxLat AND maxLat >= :minLat AND minLon <= :maxLon AND maxLon >= :minLon"
    )
    abstract suspend fun segmentsOverlapping(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<SegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertEfforts(efforts: List<SegmentEffortEntity>)

    @Query("DELETE FROM segment_efforts WHERE segmentId = :segmentId")
    abstract suspend fun deleteEffortsForSegment(segmentId: String)

    @Query("DELETE FROM segment_efforts WHERE activityId = :activityId")
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
