package com.danielbrog.activitystats.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RenameColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec

/**
 * Version history (schemas in app/schemas):
 * 1. activities and track_points.
 * 2. Adds best_efforts. An automatic migration: it only adds a table.
 * 3. Adds activities.elevationGainMeters. Automatic: a new nullable column.
 * 4. Adds track_points.segment, for manual pauses. Automatic: existing points get 0.
 * 5. Adds activities.trackUploaded, for cloud sync. Automatic: existing rides get false.
 * 6. Renames track_points.segment to stretch, freeing "segment" for user-made segments.
 *    Automatic, with [RenamePauseSegmentToStretch]: the values are kept.
 * 7. Adds segments and segment_efforts, and each ride's bounding box (activities.minLat and
 *    so on). Automatic: new tables, and nullable columns that rides saved before stay without.
 */
@Database(
    entities = [
        ActivityEntity::class,
        TrackPointEntity::class,
        BestEffortEntity::class,
        SegmentEntity::class,
        SegmentEffortEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = AppDatabase.RenamePauseSegmentToStretch::class),
        AutoMigration(from = 6, to = 7),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun bestEffortDao(): BestEffortDao
    abstract fun segmentDao(): SegmentDao

    /** Tells the 5 → 6 automatic migration that the column was renamed, not dropped and added. */
    @RenameColumn(tableName = "track_points", fromColumnName = "segment", toColumnName = "stretch")
    class RenamePauseSegmentToStretch : AutoMigrationSpec

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "activity_stats.db")
                .build()
    }
}
