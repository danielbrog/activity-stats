package com.danielbrog.activitystats.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danielbrog.activitystats.export.GpxPoint
import com.danielbrog.activitystats.export.GpxTrack
import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.segments.Polyline
import com.danielbrog.activitystats.stats.ActivityStats
import com.danielbrog.activitystats.sync.CloudCopy
import com.danielbrog.activitystats.sync.CloudReview
import com.danielbrog.activitystats.sync.CloudSegment
import kotlin.math.cos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    /** How many times the repository asked for a sync, as the app wires it. */
    private var changes = 0
    private val matching = mutableListOf<Job>()

    private val lat0 = 43.65
    private val lon0 = -79.38
    private val perDegLat = 111_195.0
    private val perDegLon = perDegLat * cos(Math.toRadians(lat0))

    /** Where the repository matches, as the app's own scope; stopped after each test. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                AppDatabase::class.java,
            ).build()
        segments = SegmentRepository(db, background = background, onChanged = { changes++ })
        rides = ActivityRepository(db, onRideSaved = { id ->
            savedRides += id
            matching += segments.matchRideInBackground(id)
        })
    }

    /** Lets any matching finish and stops the scope before the database goes, so nothing runs on into the next test. */
    @After
    fun tearDown() {
        runBlocking {
            awaitMatching()
            segments.awaitSegmentMatching()
        }
        background.cancel()
        db.close()
    }

    /** 2 km north along a road [eastMeters] east of the origin, a fix every 10 m and every second. */
    private fun road(
        startMillis: Long,
        eastMeters: Double = 0.0,
        msPerFix: Long = 1_000L,
        firstSeq: Int = 1,
        stretch: Int = 0,
    ) = (0..200).map { i ->
        GpsData(
            firstSeq + i,
            startMillis + i * msPerFix,
            lat0 + i * 10.0 / perDegLat,
            lon0 + eastMeters / perDegLon,
            5f,
            10f,
            FixStatus.Kept,
            stretch = stretch,
        )
    }

    /** A finished ride along [road] ([msPerFix] apart: 500 is twice as fast), then waits for its matching. */
    private suspend fun rideAlong(id: String, startMillis: Long, eastMeters: Double = 0.0, msPerFix: Long = 1_000L) =
        finishRide(id, startMillis, road(startMillis, eastMeters, msPerFix))

    private suspend fun finishRide(id: String, startMillis: Long, fixes: List<GpsData>) {
        val end = fixes.last().timeMillis
        rides.startActivity(id, startMillis)
        rides.savePoints(id, fixes, ActivityStats(), end)
        rides.finishActivity(id, end, ActivityStats(distanceMeters = 2_000.0))
        awaitMatching()
    }

    /** Waits for every background match started so far. */
    private suspend fun awaitMatching() = matching.toList().joinAll()

    /** Makes a segment of fixes [from] to [to] (1 km by default) of the ride "first" and waits for its efforts. */
    private suspend fun createAndMatch(name: String, from: Int = 50, to: Int = 150): String {
        val created = requireNotNull(segments.create(name, "first", from, to))
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
        val created =
            requireNotNull(segments.create("Road", "first", startIndex = 50, endIndex = 150, nowMillis = 10_000_000L))
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
        val track = GpxTrack(
            "Imported ride",
            "ride",
            (0..200).map { i ->
                GpxPoint(lat0 + i * 10.0 / perDegLat, lon0, elevation = null, timeMillis = 9_000_000L + i * 1_000L)
            },
        )
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

    // --- Sport

    /** An imported run along the same road as [rideAlong], so only the sport differs. */
    private suspend fun runAlong(startMillis: Long): String {
        val track = GpxTrack(
            "A run",
            ActivityType.RUN,
            (0..200).map { i ->
                GpxPoint(lat0 + i * 10.0 / perDegLat, lon0, elevation = null, timeMillis = startMillis + i * 1_000L)
            },
        )
        val imported = rides.importTrack(track) as ImportResult.Imported
        awaitMatching()
        return imported.id
    }

    @Test
    fun aSegmentMadeFromARunIsARunSegmentAndOnlyRunsCount() = runBlocking {
        val runId = runAlong(1_000_000L)
        rideAlong("ride", 5_000_000L) // the same road, on a bike
        val created = requireNotNull(segments.create("Run route", runId, 50, 150))
        created.matching.join()
        assertEquals(ActivityType.RUN, segments.segment(created.id).first()?.sport)
        assertEquals(listOf(runId), segments.effortsFor(created.id).first().map { it.activityId })
    }

    @Test
    fun aRunDoesNotCountOnARideSegment() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        assertEquals(ActivityType.RIDE, segments.segment(id).first()?.sport)
        val runId = runAlong(5_000_000L) // matched automatically on import
        assertTrue(segments.effortsForRide(runId).first().isEmpty())
        assertEquals(listOf("first"), segments.effortsFor(id).first().map { it.activityId })
    }

    // --- Segments on a ride

    @Test
    fun aRidesSegmentsComeInTheOrderItRodeThem() = runBlocking {
        rideAlong("first", 1_000_000L)
        createAndMatch("Late", 120, 170)
        createAndMatch("Early", 20, 70)
        val efforts = segments.effortsForRide("first").first()
        assertEquals(listOf("Early", "Late"), efforts.map { it.segmentName })
        assertTrue(efforts.all { it.isBest }) // the only ride on each
    }

    @Test
    fun aSegmentRiddenTwiceInOneRideIsTwoLaps() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        // Up the road, a pause, then up it again.
        val twice = road(5_000_000L) + road(5_500_000L, firstSeq = 202, stretch = 1)
        finishRide("twice", 5_000_000L, twice)
        val efforts = segments.effortsForRide("twice").first()
        assertEquals(listOf(id, id), efforts.map { it.segmentId })
        assertEquals(listOf(0, 1), efforts.map { it.occurrence })
    }

    @Test
    fun bestMovesToTheFasterRideAndBackWhenItIsDeleted() = runBlocking {
        rideAlong("first", 1_000_000L)
        createAndMatch("Road")
        rideAlong("fast", 5_000_000L, msPerFix = 500L) // twice as fast
        assertFalse(segments.effortsForRide("first").first().single().isBest)
        assertTrue(segments.effortsForRide("fast").first().single().isBest)

        rides.delete("fast")
        assertTrue(segments.effortsForRide("first").first().single().isBest)
    }

    @Test
    fun aDeletedSegmentLeavesTheRideAndComesBackWithUndo() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        segments.delete(id)
        assertTrue(segments.effortsForRide("first").first().isEmpty())
        segments.restore(id)
        assertEquals(listOf("Road"), segments.effortsForRide("first").first().map { it.segmentName })
    }

    // --- Sync

    @Test
    fun editsAskForASync() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        segments.rename(id, "Hill climb")
        segments.delete(id)
        segments.restore(id)
        assertEquals(4, changes) // made, renamed, deleted, restored
    }

    @Test
    fun onlyAnUnchangedSegmentIsMarkedSynced() = runBlocking {
        rideAlong("first", 1_000_000L)
        val sql = db.openHelper.writableDatabase
        val id = createAndMatch("Road")
        sql.execSQL("UPDATE segments SET userId = 'me', syncState = 'PENDING', updatedAt = 5 WHERE id = '$id'")
        assertEquals(listOf(id), segments.pendingSegmentUploads("me").map { it.id })

        segments.markSegmentSynced(id, sentUpdatedAt = 4) // edited since that copy was sent
        assertEquals(SyncState.PENDING, segments.getSegment(id)?.syncState)
        segments.markSegmentSynced(id, sentUpdatedAt = 5)
        assertEquals(SyncState.SYNCED, segments.getSegment(id)?.syncState)
        assertTrue(segments.pendingSegmentUploads("me").isEmpty())
    }

    @Test
    fun aReviewChangesOnlyTheReviewFields() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        val before = checkNotNull(segments.getSegment(id))
        val review =
            CloudReview(id, "REJECTED", submittedAt = 10L, decidedAt = 20L, reviewNote = "Too short", syncedAt = 30L)
        assertTrue(segments.applyReview(review, SegmentStatus.REJECTED))
        val after = checkNotNull(segments.getSegment(id))
        assertEquals(SegmentStatus.REJECTED, after.status)
        assertEquals("Too short", after.reviewNote)
        assertEquals(before.updatedAt, after.updatedAt) // not an edit to upload
        assertEquals(before.syncState, after.syncState)
        assertFalse(segments.applyReview(review.copy(segmentId = "missing"), SegmentStatus.REJECTED))
    }

    @Test
    fun aDownloadedSegmentIsSavedOnceAndGetsItsEfforts() = runBlocking {
        rideAlong("first", 1_000_000L)
        rideAlong("second", 5_000_000L)
        // The same road as "first" fixes 50 to 150, as another phone would have uploaded it.
        val made = checkNotNull(segments.getSegment(createAndMatch("Mine")))
        val fromCloud = CloudSegment(
            id = "from-cloud",
            name = "From the cloud",
            path = made.path,
            distanceMeters = made.distanceMeters,
            sport = "ride",
            createdAt = 1L,
            updatedAt = 2L,
            syncedAt = 3L,
        )
        segments.saveDownloadedSegment(fromCloud.toEntity("me"))
        segments.saveDownloadedSegment(fromCloud.toEntity("me").copy(name = "Again")) // already here: ignored
        awaitSegmentMatching()
        assertEquals("From the cloud", segments.getSegment("from-cloud")?.name)
        assertEquals(setOf("first", "second"), segments.effortsFor("from-cloud").first().map { it.activityId }.toSet())
    }

    @Test
    fun aSegmentRestoredInTheCloudComesBackWithItsEfforts() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        segments.delete(id, nowMillis = 10L)
        val restored = checkNotNull(segments.getSegment(id)).let {
            CloudSegment(
                id, it.name, it.path, it.distanceMeters, it.sport, it.createdAt,
                updatedAt = 20L, deletedAt = null, syncedAt = 30L,
            )
        }
        assertTrue(segments.applyCloudSegment(restored))
        awaitSegmentMatching()
        assertNull(segments.getSegment(id)?.deletedAt)
        assertEquals(SyncState.SYNCED, segments.getSegment(id)?.syncState)
        assertEquals(listOf("first"), segments.effortsFor(id).first().map { it.activityId })
    }

    @Test
    fun signingInClaimsSegmentsMadeWhileSignedOut() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road") // no owner: made signed out
        assertEquals(SyncState.LOCAL_ONLY, segments.getSegment(id)?.syncState)
        rides.claimUnowned("me")
        val claimed = checkNotNull(segments.getSegment(id))
        assertEquals("me", claimed.userId)
        assertEquals(SyncState.PENDING, claimed.syncState)
    }

    @Test
    fun aLineChangeKeepsANewerRenameOnThePhone() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        segments.rename(id, "Hill climb", nowMillis = 5_000L)
        val made = checkNotNull(segments.getSegment(id))
        val older =
            CloudSegment(
                id,
                "Old name",
                made.path,
                made.distanceMeters,
                made.sport,
                made.createdAt,
                updatedAt = 4_000L,
                syncedAt = 30L,
            )
        assertFalse(segments.replaceWithCloudSegment(older)) // still has an edit to upload
        awaitSegmentMatching()
        val after = checkNotNull(segments.getSegment(id))
        assertEquals("Hill climb", after.name)
        assertEquals(5_000L, after.updatedAt)
        assertEquals(listOf("first"), segments.effortsFor(id).first().map { it.activityId })
    }

    @Test
    fun syncNowRetriesRefusedSegments() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        db.openHelper.writableDatabase.execSQL(
            "UPDATE segments SET userId = 'me', syncState = 'FAILED' WHERE id = '$id'",
        )
        rides.retryFailed("me")
        assertEquals(SyncState.PENDING, segments.getSegment(id)?.syncState)
    }

    @Test
    fun undoingADeleteFindsEffortsForRidesSavedMeanwhile() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        segments.delete(id)
        rideAlong("second", 5_000_000L) // matched while the segment was deleted: no effort
        awaitMatching()
        segments.restore(id)
        assertTrue(id in segments.finding.value) // "Finding efforts…" while it runs
        awaitSegmentMatching()
        assertEquals(setOf("first", "second"), segments.effortsFor(id).first().map { it.activityId }.toSet())
    }

    @Test
    fun matchingUsesTheSegmentAsItIsWhenItRuns() = runBlocking {
        rideAlong("first", 1_000_000L)
        val id = createAndMatch("Road")
        assertEquals(1, segments.effortsFor(id).first().size)
        // Its line changes (as sync can) before a waiting job runs: the job matches the new
        // line, 5 km away from every ride, not the one it was started for.
        val made = checkNotNull(segments.getSegment(id))
        val elsewhere = made.toPathOrNull()!!.points.map { RoutePoint(it.latitude + 0.05, it.longitude) }
        db.segmentDao().replaceSegment(made.copy(path = Polyline.encode(elsewhere)))
        assertEquals(0, segments.recomputeForSegment(id))
        assertTrue(segments.effortsFor(id).first().isEmpty())
        // Deleted by then: left alone.
        segments.delete(id)
        assertEquals(0, segments.recomputeForSegment(id))
    }

    @Test
    fun aSegmentWithABadLineDoesNotStopTheOthersMatching() = runBlocking {
        rideAlong("first", 1_000_000L)
        val good = createAndMatch("Road")
        db.segmentDao().insertSegment(
            checkNotNull(segments.getSegment(good)).copy(id = "bad", name = "Bad", path = "_"),
        )
        rideAlong("second", 5_000_000L)
        awaitMatching()
        assertEquals(setOf("first", "second"), segments.effortsFor(good).first().map { it.activityId }.toSet())
        assertEquals(0, segments.recomputeForSegment("bad"))
    }

    /** Waits for any segment matching started in the background (downloads, restores). */
    private suspend fun awaitSegmentMatching() = segments.awaitSegmentMatching()
}
