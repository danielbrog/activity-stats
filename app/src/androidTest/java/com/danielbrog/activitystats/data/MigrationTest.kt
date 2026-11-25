package com.danielbrog.activitystats.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real database migrations against the saved schemas (app/schemas), with a ride in
 * the database, and checks it comes through. Room validates each migrated schema too.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(AppDatabase.RenamePauseSegmentToStretch()),
    )

    @Test
    fun version6To7KeepsTheRideAndAddsEmptySegmentTables() {
        helper.createDatabase(dbName, 6).use { db ->
            db.execSQL(
                "INSERT INTO activities (id, userId, type, startedAt, endedAt, distanceMeters, movingTimeMs, " +
                    "elapsedTimeMs, avgSpeedMps, maxSpeedMps, elevationGainMeters, title, updatedAt, deletedAt, " +
                    "syncState, trackUploaded) VALUES ('ride-1', NULL, 'ride', 1000, 2000, 1234.5, 900, 1000, " +
                    "1.37, 4.1, 12.0, 'Morning ride', 2000, NULL, 'LOCAL_ONLY', 0)",
            )
            db.execSQL(
                "INSERT INTO track_points (activityId, seq, timestamp, latitude, longitude, altitude, " +
                    "accuracyMeters, speedMps, status, stretch) VALUES ('ride-1', 1, 1000, 43.65, -79.38, " +
                    "100.0, 5.0, 3.0, 'Kept', 1)",
            )
        }

        helper.runMigrationsAndValidate(dbName, 7, true).use { db ->
            db.query("SELECT title, distanceMeters, minLat FROM activities WHERE id = 'ride-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Morning ride", c.getString(0))
                assertEquals(1234.5, c.getDouble(1), 0.0)
                // Rides from before segments have no box, so segments ignore them.
                assertTrue(c.isNull(2))
            }
            db.query("SELECT stretch FROM track_points WHERE activityId = 'ride-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM segments").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM segment_efforts").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun version7To8KeepsSegmentsAsPrivateRideSegments() {
        helper.createDatabase(dbName, 7).use { db ->
            db.execSQL(
                "INSERT INTO segments (id, userId, name, path, distanceMeters, createdAt, updatedAt, deletedAt, " +
                    "syncState) VALUES ('seg-1', NULL, 'Bayview climb', '_p~iF~ps|U_ulLnnqC', 1240.0, 1000, 2000, NULL, 'LOCAL_ONLY')",
            )
            db.execSQL(
                "INSERT INTO segment_efforts (segmentId, activityId, occurrence, startedAt, elapsedMs) " +
                    "VALUES ('seg-1', 'ride-1', 0, 1500, 151000)",
            )
        }

        helper.runMigrationsAndValidate(dbName, 8, true).use { db ->
            db.query(
                "SELECT name, sport, status, submittedAt, verifiedSegmentId FROM segments WHERE id = 'seg-1'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Bayview climb", c.getString(0))
                assertEquals("ride", c.getString(1))
                assertEquals("PRIVATE", c.getString(2))
                assertTrue(c.isNull(3))
                assertTrue(c.isNull(4))
            }
            db.query("SELECT elapsedMs FROM segment_efforts WHERE segmentId = 'seg-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(151_000L, c.getLong(0))
            }
            db.query("SELECT COUNT(*) FROM verified_segments").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM catalog_tiles").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun everyMigrationFromVersion1KeepsTheRide() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO activities (id, userId, type, startedAt, endedAt, distanceMeters, movingTimeMs, " +
                    "elapsedTimeMs, avgSpeedMps, maxSpeedMps, title, updatedAt, deletedAt, syncState) VALUES " +
                    "('ride-1', NULL, 'ride', 1000, 2000, 1234.5, 900, 1000, 1.37, 4.1, 'Morning ride', 2000, NULL, 'LOCAL_ONLY')",
            )
            db.execSQL(
                "INSERT INTO track_points (activityId, seq, timestamp, latitude, longitude, altitude, " +
                    "accuracyMeters, speedMps, status) VALUES ('ride-1', 1, 1000, 43.65, -79.38, 100.0, 5.0, 3.0, 'Kept')",
            )
        }

        helper.runMigrationsAndValidate(dbName, 8, true).use { db ->
            db.query("SELECT title, trackUploaded, elevationGainMeters FROM activities WHERE id = 'ride-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Morning ride", c.getString(0))
                assertEquals(0, c.getInt(1)) // added in 5, defaults to false
                assertTrue(c.isNull(2)) // added in 3, worked out at app start
            }
            db.query("SELECT stretch, status FROM track_points WHERE activityId = 'ride-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0)) // added in 4 as segment, renamed in 6
                assertEquals("Kept", c.getString(1))
            }
        }
    }
}
