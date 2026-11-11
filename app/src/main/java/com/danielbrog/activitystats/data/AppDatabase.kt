package com.danielbrog.activitystats.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version history (schemas in app/schemas):
 * 1. activities and track_points.
 * 2. Adds best_efforts. An automatic migration: it only adds a table.
 * 3. Adds activities.elevationGainMeters. Automatic: a new nullable column.
 * 4. Adds track_points.segment, for manual pauses. Automatic: existing points get 0.
 */
@Database(
    entities = [ActivityEntity::class, TrackPointEntity::class, BestEffortEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun bestEffortDao(): BestEffortDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "activity_stats.db")
                .build()
    }
}
