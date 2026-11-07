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
 */
@Database(
    entities = [ActivityEntity::class, TrackPointEntity::class, BestEffortEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
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
