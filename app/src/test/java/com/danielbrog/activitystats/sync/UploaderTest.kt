package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.data.SyncState
import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UploaderTest {

    private val user = "user-1"

    private fun ride(
        id: String,
        startedAt: Long = 1_000L,
        endedAt: Long? = startedAt + 60_000L,
        trackUploaded: Boolean = false,
        deletedAt: Long? = null,
        userId: String? = user,
        syncState: SyncState = SyncState.PENDING,
        updatedAt: Long = startedAt + 60_000L,
        elevationGainMeters: Double? = 12.0,
    ) = ActivityEntity(
        id = id,
        userId = userId,
        startedAt = startedAt,
        endedAt = endedAt,
        title = "Ride $id",
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncState = syncState,
        trackUploaded = trackUploaded,
        elevationGainMeters = elevationGainMeters,
    )

    /** An in-memory database with the same rules as the real queries. */
    private class FakeStore(rides: List<ActivityEntity>) : SyncStore {
        val rides = rides.associateBy { it.id }.toMutableMap()

        override suspend fun pendingUploads(userId: String) = rides.values
            .filter {
                it.userId == userId && it.syncState == SyncState.PENDING && it.endedAt != null &&
                    it.elevationGainMeters != null
            }
            .sortedBy { it.startedAt }

        /** How many fixes each ride's track has. */
        var pointCount = 1

        override suspend fun pointsFor(id: String) =
            (1..pointCount).map { GpsData(it, 1_000L * it, 43.65, -79.38, 5f, 5f, FixStatus.Kept) }

        override suspend fun markTrackUploaded(id: String) {
            rides[id] = rides.getValue(id).copy(trackUploaded = true)
        }

        override suspend fun markFailed(id: String, sentUpdatedAt: Long) {
            // Same rule as the real query: only the waiting copy that was sent becomes failed.
            val current = rides.getValue(id)
            if (current.syncState == SyncState.PENDING && current.updatedAt == sentUpdatedAt) {
                rides[id] = current.copy(syncState = SyncState.FAILED)
            }
        }

        override suspend fun markSynced(id: String, sentUpdatedAt: Long) {
            val current = rides.getValue(id)
            if (current.updatedAt == sentUpdatedAt) rides[id] = current.copy(syncState = SyncState.SYNCED)
        }

        override suspend fun applyCloudCopy(id: String, cloud: CloudCopy): Boolean {
            val current = rides.getValue(id)
            // Same rule as the real query: only a newer cloud copy replaces the phone's.
            if (current.updatedAt >= cloud.updatedAt) return false
            rides[id] = current.copy(
                title = cloud.title,
                deletedAt = cloud.deletedAt,
                updatedAt = cloud.updatedAt,
                syncState = SyncState.SYNCED,
            )
            return true
        }

        fun state(id: String) = rides.getValue(id).syncState
    }

    /** A server that records every call, in order, and can be told how to answer. */
    private class FakeApi : SyncApi {
        val calls = mutableListOf<String>()
        val uploaded = mutableMapOf<String, ByteArray>()
        var answers: (String) -> PutResult = { PutResult.Applied }
        var failOn: String? = null
        var rejectTrackFor: String? = null

        /** Rides whose track the server refuses because the week's limit is reached. */
        var limitedTracks = emptySet<String>()

        /** Rides whose summary the server refuses because the week's limit is reached. */
        var limitedSummaries = emptySet<String>()

        /** Rides whose summary the server refuses because the day's edits are used up. */
        var editLimited = emptySet<String>()
        var beforeAnswer: (String) -> Unit = {}
        var badReplyFor: String? = null

        override suspend fun putActivity(id: String, summary: RideSummary): PutResult {
            calls += "summary $id"
            if (failOn == "summary $id") throw IOException("offline")
            if (badReplyFor == id) throw kotlinx.serialization.SerializationException("not JSON")
            if (id in limitedSummaries) throw RideLimitReachedException(resetsAt = 2_000_000L)
            if (id in editLimited) throw EditLimitReachedException(resetsAt = 3_000_000L)
            beforeAnswer(id)
            return answers(id)
        }

        override suspend fun uploadTrack(id: String, bytes: ByteArray) {
            calls += "track $id"
            if (rejectTrackFor == id) throw RejectedException(400)
            if (id in limitedTracks) throw RideLimitReachedException(resetsAt = 1_000_000L)
            if (failOn == "track $id") throw IOException("offline")
            uploaded[id] = bytes
        }

        // The upload never downloads.
        override suspend fun listActivities(since: Long, page: String?) = error("not used by the upload")
        override suspend fun downloadTrack(id: String) = error("not used by the upload")
    }

    private fun run(
        store: FakeStore,
        api: FakeApi,
        stillSignedIn: () -> Boolean = { true },
        maxTrackBytes: Int = TrackFile.MAX_BYTES,
    ) = runBlocking { Uploader(store, api, maxTrackBytes).uploadPending(user, stillSignedIn) }

    @Test
    fun aNewRideUploadsItsTrackBeforeItsSummary() {
        val store = FakeStore(listOf(ride("a")))
        val api = FakeApi()
        val report = run(store, api)

        assertEquals(listOf("track a", "summary a"), api.calls)
        assertEquals(1, TrackFile.decode(api.uploaded.getValue("a")).size)
        assertEquals(SyncState.SYNCED, store.state("a"))
        assertTrue(store.rides.getValue("a").trackUploaded)
        assertEquals(Uploader.Report(1, emptyList()), report)
    }

    @Test
    fun aRideWhoseTrackIsUpSendsOnlyItsSummary() {
        val api = FakeApi()
        run(FakeStore(listOf(ride("a", trackUploaded = true))), api)
        assertEquals(listOf("summary a"), api.calls)
    }

    @Test
    fun aDeletedRideThatNeverUploadedSendsOnlyItsSummary() {
        val store = FakeStore(listOf(ride("a", deletedAt = 5_000L)))
        val api = FakeApi()
        run(store, api)
        assertEquals(listOf("summary a"), api.calls)
        assertEquals(SyncState.SYNCED, store.state("a"))
    }

    @Test
    fun aNewerCloudCopyIsTaken() {
        val store = FakeStore(listOf(ride("a", trackUploaded = true)))
        val api = FakeApi().apply { answers = { PutResult.Newer(CloudCopy("Renamed elsewhere", 99_000L, null)) } }
        run(store, api)
        val saved = store.rides.getValue("a")
        assertEquals("Renamed elsewhere", saved.title)
        assertEquals(99_000L, saved.updatedAt)
        assertEquals(SyncState.SYNCED, saved.syncState)
    }

    @Test
    fun aRideEditedDuringTheUploadStaysWaiting() {
        val store = FakeStore(listOf(ride("a", trackUploaded = true)))
        // The rider renames the ride while its summary is on the way.
        val api = FakeApi().apply {
            beforeAnswer =
                { id -> store.rides[id] = store.rides.getValue(id).copy(title = "New name", updatedAt = 999_000L) }
        }
        run(store, api)
        assertEquals(SyncState.PENDING, store.state("a"))
    }

    @Test
    fun anEditMadeDuringTheUploadIsKeptOverAnOlderCloudCopy() {
        val store = FakeStore(listOf(ride("a", trackUploaded = true, updatedAt = 61_000L)))
        val api = FakeApi().apply {
            // The rider renames the ride while it uploads; the cloud then answers with a copy
            // newer than what was sent, but older than the rename.
            beforeAnswer =
                { id -> store.rides[id] = store.rides.getValue(id).copy(title = "Rider's name", updatedAt = 90_000L) }
            answers = { PutResult.Newer(CloudCopy("Cloud name", 70_000L, null)) }
        }
        val report = run(store, api)

        val saved = store.rides.getValue("a")
        assertEquals("Rider's name", saved.title)
        assertEquals(SyncState.PENDING, saved.syncState)
        assertEquals(0, report.synced)
    }

    @Test
    fun aRideWithAReplyThatIsNotJsonIsSkipped() {
        val store =
            FakeStore(
                listOf(
                    ride("a", startedAt = 1_000L, trackUploaded = true),
                    ride("b", startedAt = 2_000L, trackUploaded = true),
                ),
            )
        val api = FakeApi().apply { badReplyFor = "a" }
        val report = run(store, api)
        assertEquals(listOf("a"), report.rejected)
        // A garbled reply (a Wi-Fi login page, say) isn't the ride's fault: it just waits.
        assertEquals(SyncState.PENDING, store.state("a"))
        assertEquals(SyncState.SYNCED, store.state("b"))
    }

    @Test
    fun aMissingRouteOrRedirectLeavesTheRideWaiting() {
        val store =
            FakeStore(
                listOf(
                    ride("a", startedAt = 1_000L, trackUploaded = true),
                    ride("b", startedAt = 2_000L, trackUploaded = true),
                ),
            )
        val api = FakeApi().apply { answers = { id -> PutResult.Rejected(if (id == "a") 404 else 302) } }
        val report = run(store, api)
        assertEquals(listOf("a", "b"), report.rejected)
        assertEquals(SyncState.PENDING, store.state("a"))
        assertEquals(SyncState.PENDING, store.state("b"))
    }

    @Test
    fun anEditMadeWhileTheOldCopyIsRefusedStaysWaiting() {
        val store = FakeStore(listOf(ride("a", trackUploaded = true, updatedAt = 61_000L)))
        // The rider shortens the refused title while the old copy is being refused.
        val api = FakeApi().apply {
            beforeAnswer =
                { id -> store.rides[id] = store.rides.getValue(id).copy(title = "Shorter", updatedAt = 90_000L) }
            answers = { PutResult.Rejected(400) }
        }
        run(store, api)
        assertEquals(SyncState.PENDING, store.state("a"))
    }

    @Test
    fun failedRidesAreNotSentAgainUntilRetried() {
        val api = FakeApi()
        run(FakeStore(listOf(ride("a", syncState = SyncState.FAILED, trackUploaded = true))), api)
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun signingOutPartWayStopsBeforeTheNextRide() {
        val store =
            FakeStore(
                listOf(
                    ride("a", startedAt = 1_000L, trackUploaded = true),
                    ride("b", startedAt = 2_000L, trackUploaded = true),
                ),
            )
        val api = FakeApi()
        var signedIn = true
        api.beforeAnswer = { signedIn = false }
        run(store, api, stillSignedIn = { signedIn })
        assertEquals(listOf("summary a"), api.calls)
        assertEquals(SyncState.PENDING, store.state("b"))
    }

    @Test
    fun aRefusedRideIsMarkedFailedAndDoesNotStopTheOthers() {
        val store =
            FakeStore(
                listOf(ride("a", startedAt = 1_000L), ride("b", startedAt = 2_000L), ride("c", startedAt = 3_000L)),
            )
        val api = FakeApi().apply {
            rejectTrackFor = "a"
            answers = { id -> if (id == "b") PutResult.Rejected(400) else PutResult.Applied }
        }
        val report = run(store, api)

        assertEquals(listOf("a", "b"), report.rejected)
        assertEquals(1, report.synced)
        // Set aside rather than retried on every sync; an edit or Sync now puts them back.
        assertEquals(SyncState.FAILED, store.state("a"))
        assertEquals(SyncState.FAILED, store.state("b"))
        assertEquals(SyncState.SYNCED, store.state("c"))
    }

    @Test
    fun aNetworkFailureStopsAndKeepsWhatWasDone() {
        val store =
            FakeStore(
                listOf(ride("a", startedAt = 1_000L), ride("b", startedAt = 2_000L), ride("c", startedAt = 3_000L)),
            )
        val api = FakeApi().apply { failOn = "summary b" }
        try {
            run(store, api)
            fail("Expected the network failure to reach the worker, so it retries")
        } catch (_: IOException) {
        }
        assertEquals(SyncState.SYNCED, store.state("a"))
        // b's track got up, so next time only its summary is sent.
        assertTrue(store.rides.getValue("b").trackUploaded)
        assertEquals(SyncState.PENDING, store.state("b"))
        assertEquals(SyncState.PENDING, store.state("c"))
        assertTrue(api.calls.none { it.endsWith(" c") })
    }

    @Test
    fun ridesStillRecordingOrOfAnotherUserOrNotWaitingAreNeverSent() {
        val api = FakeApi()
        run(
            FakeStore(
                listOf(
                    ride("recording", endedAt = null),
                    ride("theirs", userId = "someone-else"),
                    ride("signed-out", userId = null, syncState = SyncState.LOCAL_ONLY),
                    ride("done", syncState = SyncState.SYNCED),
                    // Climbing not worked out yet: it would go up without it, and never again.
                    ride("no-climbing", elevationGainMeters = null),
                ),
            ),
            api,
        )
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun ridesGoUpOldestFirst() {
        val api = FakeApi()
        run(
            FakeStore(
                listOf(
                    ride("new", startedAt = 9_000L, trackUploaded = true),
                    ride("old", startedAt = 1_000L, trackUploaded = true),
                ),
            ),
            api,
        )
        assertEquals(listOf("summary old", "summary new"), api.calls)
    }

    @Test
    fun aTrackTooLargeIsNeverSentAndDoesNotStopTheOthers() {
        // "b" already has its track up, so only its summary goes.
        val store =
            FakeStore(listOf(ride("big", startedAt = 1_000L), ride("b", startedAt = 2_000L, trackUploaded = true)))
        val api = FakeApi()
        val oneFix = TrackFile.encode(listOf(GpsData(1, 1_000L, 43.65, -79.38, 5f, 5f, FixStatus.Kept))).size
        val report = run(store, api, maxTrackBytes = oneFix - 1) // so "big"'s track is over the limit

        assertEquals(listOf("big"), report.rejected)
        assertEquals(SyncState.FAILED, store.state("big"))
        // Not sent at all: the server would only refuse it.
        assertTrue(api.calls.none { it == "track big" || it == "summary big" })
        assertEquals(listOf("summary b"), api.calls)
        assertEquals(SyncState.SYNCED, store.state("b"))
    }

    @Test
    fun aTrackWithMoreFixesThanTheServerTakesIsNeverSent() {
        val store = FakeStore(listOf(ride("long")))
        store.pointCount = TrackFile.MAX_FIXES + 1
        val api = FakeApi()
        val report = run(store, api)

        assertEquals(listOf("long"), report.rejected)
        assertEquals(SyncState.FAILED, store.state("long"))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun theWeeklyLimitLeavesNewRidesWaitingAndStillSendsEdits() {
        val store = FakeStore(
            listOf(
                ride("new", startedAt = 1_000L),
                ride("edited", startedAt = 2_000L, trackUploaded = true), // already up: an edit
                ride("newer", startedAt = 3_000L),
            ),
        )
        val api = FakeApi().apply { limitedTracks = setOf("new") }
        val report = run(store, api)

        // Waiting, not failed: they go up after the reset.
        assertEquals(listOf("new", "newer"), report.limited)
        assertTrue(report.rejected.isEmpty())
        assertEquals(SyncState.PENDING, store.state("new"))
        assertEquals(SyncState.PENDING, store.state("newer"))
        // The edit still goes; the next new ride isn't even tried once the limit is known.
        assertEquals(listOf("track new", "summary edited"), api.calls)
        assertEquals(SyncState.SYNCED, store.state("edited"))
        assertEquals(1, report.synced)
        assertEquals(1_000_000L, report.limitResetsAt)
    }

    @Test
    fun aLimitOnTheSummaryAlsoLeavesTheRideWaiting() {
        // Its track went up, but the summary is the first the server stores of it.
        val store = FakeStore(
            listOf(
                ride("half", startedAt = 1_000L, trackUploaded = true),
                ride("next", startedAt = 2_000L),
            ),
        )
        val api = FakeApi().apply { limitedSummaries = setOf("half") }
        val report = run(store, api)

        assertEquals(listOf("half", "next"), report.limited)
        assertEquals(SyncState.PENDING, store.state("half"))
        assertEquals(SyncState.PENDING, store.state("next"))
        assertEquals(listOf("summary half"), api.calls)
        assertEquals(2_000_000L, report.limitResetsAt)
    }

    @Test
    fun aRideDeletedBeforeItSyncedStillGoesOnceTheLimitIsReached() {
        val store = FakeStore(
            listOf(
                ride("new", startedAt = 1_000L),
                ride("gone", startedAt = 2_000L, deletedAt = 5_000L),
            ),
        )
        val api = FakeApi().apply { limitedTracks = setOf("new") }
        val report = run(store, api)

        assertEquals(listOf("new"), report.limited)
        // Its deletion isn't held back: only a summary goes, and it doesn't count.
        assertEquals(listOf("track new", "summary gone"), api.calls)
        assertEquals(SyncState.SYNCED, store.state("gone"))
    }

    @Test
    fun theTrackLimitsMatchTheServer() {
        // The backend's MAX_TRACK_BYTES and MAX_FIXES (backend/lambda/track.ts).
        assertEquals(1_000_000, TrackFile.MAX_BYTES)
        assertEquals(40_000, TrackFile.MAX_FIXES)
    }

    @Test
    fun anEditPastTheDaysLimitHoldsBackTheOtherEditsButNotANewRide() {
        val store = FakeStore(
            listOf(
                ride("renamed", startedAt = 1_000L, trackUploaded = true),
                ride("new", startedAt = 2_000L),
                ride("deleted", startedAt = 3_000L, trackUploaded = true, deletedAt = 5_000L),
            ),
        )
        val api = FakeApi().apply { editLimited = setOf("renamed") }
        val report = run(store, api)

        assertEquals(listOf("renamed", "deleted"), report.editLimited)
        assertEquals(3_000_000L, report.editLimitResetsAt)
        assertEquals(SyncState.PENDING, store.state("renamed"))
        assertEquals(SyncState.PENDING, store.state("deleted"))
        // The second edit isn't sent: it would only be refused too.
        assertEquals(listOf("summary renamed", "track new", "summary new"), api.calls)
        // Not held back: a new ride isn't an edit.
        assertEquals(SyncState.SYNCED, store.state("new"))
        assertEquals(1, report.synced)
        // Neither limit is mixed up with the other, nor with a refusal.
        assertTrue(report.limited.isEmpty())
        assertNull(report.limitResetsAt)
        assertTrue(report.rejected.isEmpty())
    }

    @Test
    fun bothLimitsInOneRunAreReportedApart() {
        val store = FakeStore(
            listOf(
                ride("new", startedAt = 1_000L),
                ride("gone", startedAt = 2_000L, deletedAt = 5_000L),
                ride("another", startedAt = 3_000L),
            ),
        )
        val api = FakeApi().apply {
            limitedTracks = setOf("new")
            editLimited = setOf("gone")
        }
        val report = run(store, api)

        // A ride deleted before it synced is an edit: past the weekly limit it still goes, and
        // then waits for the edit limit instead.
        assertEquals(listOf("new", "another"), report.limited)
        assertEquals(listOf("gone"), report.editLimited)
        assertEquals(1_000_000L, report.limitResetsAt)
        assertEquals(3_000_000L, report.editLimitResetsAt)
        assertEquals(listOf("track new", "summary gone"), api.calls)
        assertTrue(report.rejected.isEmpty())
        listOf("new", "gone", "another").forEach { assertEquals(it, SyncState.PENDING, store.state(it)) }
    }
}
