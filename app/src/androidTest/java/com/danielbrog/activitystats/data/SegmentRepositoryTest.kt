package com.danielbrog.activitystats.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danielbrog.activitystats.export.GpxPoint
import com.danielbrog.activitystats.export.GpxTrack
import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.stats.ActivityStats
import com.danielbrog.activitystats.sync.CloudCopy
import kotlin.math.cos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Segments against a real database: creating one finds efforts on the rides that cover it. */
@RunWith(AndroidJUnit4::class)
class SegmentRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var rides: ActivityRepository
    private lateinit var segments: SegmentRepository

    /** Every ride the repository reported as saved, and the matching started for it, as the app wires it. */
    private val savedRides = mutableListOf<String>()
    private val matching = mutableListOf<Job>()

    private val lat0 = 43.65
    private val lon0 = -79.38
    private val perDegLat = 111_195.0
    private val perDegLon = perDegLat * cos(Math.toRadians(lat0))

    /** Where the repository matches, as the app's own scope; stopped after each test. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        segments = SegmentRepository(db, background = background)
        rides = ActivityRepository(db, onRideSaved = { id ->
            savedRides += id
            matching += segments.matchRideInBackground(id)
        })
    }

    /** Lets any matching finish and stops the scope before the database goes, so nothing runs on into the next test. */
    @After
    fun tearDown() {
        runBlocking { awaitMatching() }
        background.cancel()
        db.close()
    }

    /** 2 km north along a road [eastMeters] east of the origin, a fix every 10 m and every second. */
    private fun road(startMillis: Long, eastMeters: Double = 0.0) = (0..200).map { i ->
        GpsData(i + 1, startMillis + i * 1_000L, lat0 + i * 10.0 / perDegLat, lon0 + eastMeters / perDegLon,
            5f, 10f, FixStatus.Kept)
    }

    /** A finished ride along [road], then waits for its matching. */
    private suspend fun rideAlong(id: String, startMillis: Long, eastMeters: Double = 0.0) {
        rides.startActivity(id, startMillis)
        val fixes = road(startMillis, eastMeters)
        rides.savePoints(id, fixes, ActivityStats(), startMillis + 200_000L)
        rides.finishActivity(id, startMillis + 200_000L, ActivityStats(distanceMeters = 2_000.0))
        awaitMatching()
    }

    /** Waits for every background match started so far. */
    private suspend fun awaitMatching() = matching.toList().joinAll()

    /** Makes a segment of fixes 50 to 150 of the ride "first" and waits for its efforts. */
    private suspend fun createAndMatch(name: String): String {
        val created = requireNotNull(segments.create(name, "first", 50, 150))
        created.matching.join()
        return created.id
    }

    @Test
    fun finishingARideStoresItsBox() = runBlocking {
        rideAlong("a", 1_000_000L)
        val ride = rides.getActivity("a")!!
        assertEquals(lat0, ride.minLat!!, 1e-9)
        assertEquals(lat0 + 2_000.0 / perDegLat, ride.maxLat!!, 1e-6)
    }

    @Test
    fun creatingASegmentFindsEffortsOnRidesThatCoverIt() = runBlocking {
        rideAlong("first", 1_000_000L)
        rideAlong("second", 5_000_000L)
        rideAlong("elsewhere", 9_000_000L, eastMeters = 30_000.0)

        // Fixes 50 to 150 of the first ride: 1 km of the road.
        val created = requireNotNull(segments.create("Road", "first", startIndex = 50, endIndex = 150, nowMillis = 10_000_000L))
        created.matching.join()
        assertTrue(segments.finding.value.isEmpty())

        val efforts = segments.effortsFor(created.id).first()
        assertEquals(setOf("first", "second"), efforts.map { it.activityId }.toSet())
        efforts.forEach { assertEquals(100_000.0, it.elapsedMs.toDouble(), 1_500.0) }
        assertEquals(listOf("Road"), segments.allSegments.first().map { it.name })
        assertEquals("Road", segments.segment(created.id).first()?.name)
        assertNull(segments.segment("missing").first())
        val summary = segments.summaries.first().single()
        assertEquals(2, summary.effortCount)
        assertEquals(100_000.0, summary.bestMs!!.toDouble(), 1_500.0)

        // Last, since it hides the segment from everything above: once deleted, it's gone.
        db.openHelper.writableDatabase.execSQL("UPDATE segments SET deletedAt = 1 WHERE id = '${created.id}'")
        assertNull(segments.segment(created.id).first())
        assertTrue(segments.summaries.first().isEmpty())
    }

    @Test
    fun aRideFinishedLaterIsMatchedAutomatically() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        rideAlong("later", 5_000_000L) // finishes, then waits for its matching
        assertEquals(setOf("first", "later"), segments.effortsFor(id).first().map { it.activityId }.toSet())
    }

    @Test
    fun anImportedRideIsMatchedAutomatically() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        val track = GpxTrack("Imported ride", "ride", (0..200).map { i ->
            GpxPoint(lat0 + i * 10.0 / perDegLat, lon0, elevation = null, timeMillis = 9_000_000L + i * 1_000L)
        })
        val imported = rides.importTrack(track) as ImportResult.Imported
        awaitMatching()
        assertTrue(imported.id in segments.effortsFor(id).first().map { it.activityId })
    }

    @Test
    fun aRestoredRideCatchesUpOnSegmentsMadeWhileItWasDeleted() = runBlocking {
        rideAlong("first", 1_000_000L)
        rideAlong("second", 5_000_000L)
        rides.delete("second")
        val id = createAndMatch("Road") // can't see the deleted ride
        assertEquals(listOf("first"), segments.effortsFor(id).first().map { it.activityId })

        rides.restore("second")
        awaitMatching()
        assertEquals(setOf("first", "second"), segments.effortsFor(id).first().map { it.activityId }.toSet())
    }

    @Test
    fun aRideRestoredOnAnotherPhoneIsMatchedWhenItSyncsBack() = runBlocking {
        rideAlong("first", 1_000_000L)
        rideAlong("second", 5_000_000L)
        rides.delete("second", nowMillis = 6_000_000L)
        val id = createAndMatch("Road")

        // The cloud's copy, undeleted elsewhere and newer than this phone's.
        assertTrue(rides.applyCloudCopy("second", CloudCopy("Second", updatedAt = 7_000_000L, deletedAt = null)))
        awaitMatching()
        assertEquals(setOf("first", "second"), segments.effortsFor(id).first().map { it.activityId }.toSet())

        // A rename from the cloud doesn't change the route, so it isn't matched again.
        savedRides.clear()
        assertTrue(rides.applyCloudCopy("second", CloudCopy("Renamed", updatedAt = 8_000_000L)))
        assertTrue(savedRides.isEmpty())
    }

    @Test
    fun aDownloadedRideIsMatchedOnceEvenIfDownloadedAgain() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        savedRides.clear()
        val downloaded = ActivityEntity(
            id = "downloaded", startedAt = 9_000_000L, endedAt = 9_200_000L, title = "From the cloud",
            distanceMeters = 2_000.0, movingTimeMs = 200_000L, elapsedTimeMs = 200_000L,
            avgSpeedMps = 10.0, maxSpeedMps = 10.0, updatedAt = 9_200_000L,
        )
        rides.saveDownloadedRide(downloaded, road(9_000_000L))
        rides.saveDownloadedRide(downloaded, road(9_000_000L)) // already here: not saved or matched again
        awaitMatching()
        assertEquals(listOf("downloaded"), savedRides)
        assertTrue("downloaded" in segments.effortsFor(id).first().map { it.activityId })
    }

    @Test
    fun finishingAndRestoringAskForMatchingButRenamingAndDeletingDont() = runBlocking {
        rideAlong("first", 1_000_000L)
        rides.rename("first", "Renamed")
        rides.delete("first")
        rides.restore("first")
        // Finished, then restored; not renamed or deleted.
        assertEquals(listOf("first", "first"), savedRides)
    }

    @Test
    fun deletedRidesDropOutOfASegmentsEfforts() = runBlocking {
        rideAlong("first", 1_000_000L)
        rideAlong("second", 5_000_000L)
        val id = createAndMatch("Road")
        rides.delete("second")
        assertEquals(listOf("first"), segments.effortsFor(id).first().map { it.activityId })
    }

    @Test
    fun aPartThatCantBeASegmentIsRefused() = runBlocking {
        rideAlong("first", 1_000_000L)
        assertNull(segments.create("Nothing", "first", 80, 80))
        assertNull(segments.create("Too short", "first", 80, 85)) // 50 m
        assertNull(segments.create("   ", "first", 50, 150)) // no name
        assertTrue(segments.allSegments.first().isEmpty())
    }

    @Test
    fun renamingChangesTheNameAndRefusesABlankOne() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        assertTrue(segments.rename(id, "  Hill climb  ", nowMillis = 20_000_000L))
        val renamed = checkNotNull(segments.segment(id).first()) { "the segment should still be there" }
        assertEquals("Hill climb", renamed.name)
        assertEquals(20_000_000L, renamed.updatedAt)

        assertFalse(segments.rename(id, "   ")) // blank
        assertFalse(segments.rename(id, "Hill climb", nowMillis = 30_000_000L)) // unchanged: not an edit
        assertEquals(20_000_000L, segments.segment(id).first()?.updatedAt)
        assertFalse(segments.rename("missing", "Anything")) // no such segment
    }

    @Test
    fun deletingHidesASegmentAndRestoringBringsItBackWithItsEfforts() = runBlocking {
        rideAlong("first", 1_000_000L)
        rideAlong("second", 5_000_000L)
        val id = createAndMatch("Road")

        segments.delete(id)
        assertNull(segments.segment(id).first())
        assertTrue(segments.summaries.first().isEmpty())

        // Matching a ride again while the segment is deleted leaves its efforts alone.
        segments.recomputeForRide("second")

        segments.restore(id)
        assertEquals("Road", segments.segment(id).first()?.name)
        assertEquals(2, segments.summaries.first().single().effortCount)
    }

    @Test
    fun editingASyncedSegmentMarksItWaitingToUpload() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE segments SET syncState = '${SyncState.SYNCED}' WHERE id = '$id'")
        segments.rename(id, "Hill climb")
        assertEquals(SyncState.PENDING, segments.segment(id).first()?.syncState)

        // One that never belonged to an account stays on this phone.
        sql.execSQL("UPDATE segments SET syncState = '${SyncState.LOCAL_ONLY}' WHERE id = '$id'")
        segments.delete(id)
        segments.restore(id)
        assertEquals(SyncState.LOCAL_ONLY, segments.segment(id).first()?.syncState)
    }
}
