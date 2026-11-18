package com.danielbrog.activitystats.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.stats.ActivityStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device or emulator against a throwaway in-memory database. */
@RunWith(AndroidJUnit4::class)
class ActivityRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository

    private val start = 1_793_000_000_000L
    private val metresPerDegreeLat = 111_195.0

    private fun fix(seq: Int, northMeters: Double, status: FixStatus = FixStatus.Kept) = GpsData(
        seq = seq,
        timeMillis = start + seq * 1_000L,
        latitude = 43.65 + northMeters / metresPerDegreeLat,
        longitude = -79.38,
        accuracyMeters = 5f,
        speedMps = 3f,
        status = status,
        altitudeMeters = 90.0,
    )

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ActivityRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun savesARideWithItsPointsInOrder() = runBlocking {
        repo.startActivity("ride-1", start)
        repo.savePoints("ride-1", (11..20).map { fix(it, it * 3.0) }, ActivityStats(), start + 20_000)
        repo.savePoints("ride-1", (1..10).map { fix(it, it * 3.0) }, ActivityStats(), start + 21_000)

        val saved = repo.getActivity("ride-1")
        assertNotNull(saved)
        assertEquals("ride", saved!!.type)
        assertEquals(SyncState.LOCAL_ONLY, saved.syncState)
        assertNull(saved.endedAt)

        val points = repo.pointsFor("ride-1")
        assertEquals((1..20).toList(), points.map { it.seq })
        assertEquals(90.0, points.first().altitudeMeters!!, 0.0)
    }

    @Test
    fun repeatedBatchDoesNotDuplicatePoints() = runBlocking {
        repo.startActivity("ride-1", start)
        val batch = (1..5).map { fix(it, it * 3.0) }
        repo.savePoints("ride-1", batch, ActivityStats(), start + 5_000)
        repo.savePoints("ride-1", batch, ActivityStats(), start + 6_000)
        assertEquals(5, repo.pointsFor("ride-1").size)
    }

    @Test
    fun finishingSetsEndTimeAndStats() = runBlocking {
        repo.startActivity("ride-1", start)
        val stats = ActivityStats(
            distanceMeters = 1_234.0, elapsedMs = 600_000, movingMs = 540_000,
            avgSpeedMps = 2.3, maxSpeedMps = 4.1,
        )
        repo.finishActivity("ride-1", start + 600_000, stats)

        val saved = repo.getActivity("ride-1")!!
        assertEquals(start + 600_000, saved.endedAt)
        assertEquals(start + 600_000, saved.updatedAt)
        assertEquals(1_234.0, saved.distanceMeters, 0.0)
        assertEquals(540_000L, saved.movingTimeMs)
        assertEquals(1, repo.savedCount.first())
        assertEquals("ride-1", repo.latestSaved.first()?.id)
    }

    @Test
    fun finishingWithANameSavesThatName() = runBlocking {
        repo.startActivity("named", start)
        repo.finishActivity("named", start + 600_000, ActivityStats(), title = "Lakeshore loop")
        val saved = repo.getActivity("named")!!
        assertEquals("Lakeshore loop", saved.title)
        // Saved in the same step as the end time: no separate edit, so nothing extra to sync.
        assertEquals(start + 600_000, saved.endedAt)
        assertEquals(start + 600_000, saved.updatedAt)
    }

    @Test
    fun finishingWithoutANameKeepsTheDefault() = runBlocking {
        repo.startActivity("unnamed", start)
        val default = repo.getActivity("unnamed")!!.title
        repo.finishActivity("unnamed", start + 600_000, ActivityStats(), title = null)
        assertEquals(default, repo.getActivity("unnamed")!!.title)
    }

    @Test
    fun historyListsFinishedRidesNewestFirst() = runBlocking {
        repo.startActivity("older", start)
        repo.finishActivity("older", start + 60_000, ActivityStats())
        repo.startActivity("newer", start + 3_600_000)
        repo.finishActivity("newer", start + 3_660_000, ActivityStats())
        // Still recording: not in history.
        repo.startActivity("open", start + 7_200_000)
        // Soft-deleted: not in history.
        db.activityDao().insertActivity(
            ActivityEntity(
                id = "deleted", startedAt = start + 1_800_000, endedAt = start + 1_860_000,
                title = "Gone", updatedAt = start, deletedAt = start + 2_000_000,
            )
        )

        assertEquals(listOf("newer", "older"), repo.finishedActivities.first().map { it.id })
    }

    @Test
    fun renameChangesTitleAndUpdatedAt() = runBlocking {
        repo.startActivity("ride-1", start)
        repo.finishActivity("ride-1", start + 60_000, ActivityStats())

        repo.rename("ride-1", "Lakeshore loop", nowMillis = start + 120_000)

        val saved = repo.getActivity("ride-1")!!
        assertEquals("Lakeshore loop", saved.title)
        assertEquals(start + 120_000, saved.updatedAt)
        assertEquals(SyncState.LOCAL_ONLY, saved.syncState)
    }

    @Test
    fun deleteHidesTheRideAndUndoBringsItBack() = runBlocking {
        repo.startActivity("ride-1", start)
        repo.finishActivity("ride-1", start + 60_000, ActivityStats())

        repo.delete("ride-1", nowMillis = start + 120_000)
        assertTrue(repo.finishedActivities.first().isEmpty())
        assertNull(repo.latestSaved.first())
        assertEquals(0, repo.savedCount.first())
        assertEquals(start + 120_000, repo.getActivity("ride-1")!!.deletedAt)

        repo.restore("ride-1", nowMillis = start + 130_000)
        assertEquals(listOf("ride-1"), repo.finishedActivities.first().map { it.id })
        assertEquals(1, repo.savedCount.first())
        val restored = repo.getActivity("ride-1")!!
        assertNull(restored.deletedAt)
        assertEquals(start + 130_000, restored.updatedAt)
    }

    @Test
    fun editingASyncedRideMarksItPending() = runBlocking {
        db.activityDao().insertActivity(
            ActivityEntity(
                id = "synced", startedAt = start, endedAt = start + 60_000, title = "Ride",
                updatedAt = start, syncState = SyncState.SYNCED,
            )
        )
        repo.rename("synced", "Renamed", nowMillis = start + 1)
        assertEquals(SyncState.PENDING, repo.getActivity("synced")!!.syncState)
    }

    private suspend fun insertFinished(id: String, syncState: String, userId: String? = "me", elevation: Double? = 10.0) =
        db.activityDao().insertActivity(
            ActivityEntity(
                id = id, userId = userId, startedAt = start, endedAt = start + 60_000, title = "Ride",
                updatedAt = start, syncState = syncState, elevationGainMeters = elevation,
            )
        )

    @Test
    fun editingAFailedRideMarksItPending() = runBlocking {
        insertFinished("renamed", SyncState.FAILED)
        insertFinished("deleted", SyncState.FAILED)
        insertFinished("restored", SyncState.FAILED)
        repo.rename("renamed", "Shorter", nowMillis = start + 1)
        repo.delete("deleted", nowMillis = start + 1)
        repo.restore("restored", nowMillis = start + 1)
        listOf("renamed", "deleted", "restored").forEach {
            assertEquals(it, SyncState.PENDING, repo.getActivity(it)!!.syncState)
        }
    }

    @Test
    fun onlyTheWaitingCopyThatWasSentIsMarkedFailed() = runBlocking {
        insertFinished("sent", SyncState.PENDING)
        insertFinished("edited", SyncState.PENDING)
        insertFinished("synced", SyncState.SYNCED)
        repo.markFailed("sent", sentUpdatedAt = start)
        repo.markFailed("edited", sentUpdatedAt = start - 1) // edited after that copy was sent
        repo.markFailed("synced", sentUpdatedAt = start)
        assertEquals(SyncState.FAILED, repo.getActivity("sent")!!.syncState)
        assertEquals(SyncState.PENDING, repo.getActivity("edited")!!.syncState)
        assertEquals(SyncState.SYNCED, repo.getActivity("synced")!!.syncState)
    }

    @Test
    fun retryFailedOnlyTouchesThatUser() = runBlocking {
        insertFinished("mine", SyncState.FAILED, userId = "me")
        insertFinished("theirs", SyncState.FAILED, userId = "someone-else")
        repo.retryFailed("me")
        assertEquals(SyncState.PENDING, repo.getActivity("mine")!!.syncState)
        assertEquals(SyncState.FAILED, repo.getActivity("theirs")!!.syncState)
    }

    @Test
    fun theCountsMatchWhatTheNextSyncSends() = runBlocking {
        insertFinished("waiting", SyncState.PENDING)
        insertFinished("no-climbing-yet", SyncState.PENDING, elevation = null)
        insertFinished("refused", SyncState.FAILED)
        insertFinished("other-user", SyncState.PENDING, userId = "someone-else")
        repo.startActivity("recording", start) // owned by nobody here, and unfinished
        assertEquals(1, repo.waitingCount("me").first())
        assertEquals(repo.pendingUploads("me").size, repo.waitingCount("me").first())
        assertEquals(1, repo.failedCount("me").first())
    }

    @Test
    fun unfinishedRidesAreRecoveredFromTheirPoints() = runBlocking {
        repo.startActivity("cut-off", start)
        repo.savePoints(
            "cut-off",
            (0..30).map { fix(it, it * 3.0) } + fix(31, 500.0, FixStatus.DroppedAccuracy),
            ActivityStats(),
            start + 31_000,
        )
        // A ride started after the app opened must be left alone.
        repo.startActivity("in-progress", start + 100_000)

        val recovered = repo.recoverUnfinished(startedBefore = start + 50_000)

        assertEquals(1, recovered)
        val fixed = repo.getActivity("cut-off")!!
        // Ends at the last saved point, dropped ones included.
        assertEquals(start + 31_000, fixed.endedAt)
        // 30 legs of 3 m between kept fixes; the dropped 500 m fix adds nothing.
        assertEquals(90.0, fixed.distanceMeters, 1.0)
        assertEquals(30_000L, fixed.movingTimeMs)
        assertNull(repo.getActivity("in-progress")!!.endedAt)
        assertTrue(repo.savedCount.first() == 1)
    }
}
