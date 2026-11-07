package com.danielbrog.activitystats.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Queries on the `best_efforts` table: each ride's fastest time over the set distances. */
@Dao
abstract class BestEffortDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertEfforts(efforts: List<BestEffortEntity>)

    @Query("DELETE FROM best_efforts WHERE activityId = :activityId")
    abstract suspend fun deleteFor(activityId: String)

    /** Replaces a ride's efforts in one go. */
    @Transaction
    open suspend fun replaceFor(activityId: String, efforts: List<BestEffortEntity>) {
        deleteFor(activityId)
        insertEfforts(efforts)
    }

    /** Every effort a ride actually reached; updates itself on changes. */
    @Query("SELECT * FROM best_efforts WHERE elapsedMs IS NOT NULL")
    abstract fun observeReached(): Flow<List<BestEffortEntity>>

    /** Finished rides whose efforts haven't been worked out yet, like those saved before this table. */
    @Query(
        "SELECT id FROM activities WHERE endedAt IS NOT NULL " +
            "AND id NOT IN (SELECT DISTINCT activityId FROM best_efforts)"
    )
    abstract suspend fun finishedWithoutEfforts(): List<String>
}
