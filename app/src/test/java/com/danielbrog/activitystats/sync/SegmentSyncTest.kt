package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.data.SegmentEntity
import com.danielbrog.activitystats.data.SegmentStatus
import com.danielbrog.activitystats.data.SyncState
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Segment sync against a fake server and a fake database. */
class SegmentSyncTest {

    private val user = "11111111-aaaa"

    private fun local(
        id: String,
        updatedAt: Long = 1_000L,
        syncState: SyncState = SyncState.PENDING,
        deletedAt: Long? = null,
        path: String = "_p~iF~ps|U",
    ) = SegmentEntity(
        id = id, userId = user, name = "Segment $id", path = path, distanceMeters = 1_240.0,
        createdAt = 500L, updatedAt = updatedAt, deletedAt = deletedAt, syncState = syncState,
    )

    private fun cloud(
        id: String,
        updatedAt: Long = 1_000L,
        syncedAt: Long = 10L,
        deletedAt: Long? = null,
        name: String = "Segment $id",
        path: String = "_p~iF~ps|U",
    ) = CloudSegment(
        id = id, name = name, path = path, distanceMeters = 1_240.0, sport = "ride",
        createdAt = 500L, updatedAt = updatedAt, deletedAt = deletedAt, syncedAt = syncedAt,
    )

    private fun review(segmentId: String, status: String? = "PUBLIC", syncedAt: Long = 20L) = CloudReview(
        segmentId = segmentId, status = status, submittedAt = 100L, decidedAt = 200L,
        reviewNote = null, verifiedSegmentId = "v-$segmentId", syncedAt = syncedAt,
    )

    /** The phone's segments, with the same rules as the real DAO queries. */
    private class FakeStore(segments: List<SegmentEntity> = emptyList()) : SegmentSyncStore {
        val rows = segments.associateBy { it.id }.toMutableMap()
        val matched = mutableListOf<String>()

        override suspend fun pendingSegmentUploads(userId: String) =
            rows.values.filter { it.userId == userId && it.syncState == SyncState.PENDING }.sortedBy { it.updatedAt }

        override suspend fun markSegmentSynced(id: String, sentUpdatedAt: Long) {
            val s = rows.getValue(id)
            if (s.updatedAt == sentUpdatedAt && s.syncState == SyncState.PENDING) rows[id] = s.copy(syncState = SyncState.SYNCED)
        }

        override suspend fun markSegmentFailed(id: String, sentUpdatedAt: Long) {
            val s = rows.getValue(id)
            if (s.updatedAt == sentUpdatedAt && s.syncState == SyncState.PENDING) rows[id] = s.copy(syncState = SyncState.FAILED)
        }

        override suspend fun getSegment(id: String) = rows[id]

        override suspend fun applyCloudSegment(cloud: CloudSegment): Boolean {
            val s = rows[cloud.id] ?: return false
            if (s.updatedAt >= cloud.updatedAt) return false
            rows[cloud.id] = s.copy(name = cloud.name, deletedAt = cloud.deletedAt, updatedAt = cloud.updatedAt, syncState = SyncState.SYNCED)
            return true
        }

        override suspend fun replaceWithCloudSegment(cloud: CloudSegment): Boolean {
            val merged = mergeLineChange(rows[cloud.id] ?: return false, cloud)
            rows[cloud.id] = merged
            if (merged.deletedAt == null) matched += cloud.id
            return merged.syncState == SyncState.SYNCED
        }

        override suspend fun saveDownloadedSegment(segment: SegmentEntity) {
            if (segment.id in rows) return
            rows[segment.id] = segment
            if (segment.deletedAt == null) matched += segment.id
        }

        override suspend fun applyReview(review: CloudReview, status: SegmentStatus): Boolean {
            val s = rows[review.segmentId] ?: return false
            rows[review.segmentId] = s.copy(
                status = status,
                submittedAt = review.submittedAt,
                decidedAt = review.decidedAt,
                reviewNote = review.reviewNote,
                verifiedSegmentId = review.verifiedSegmentId,
            )
            return true
        }
    }

    /** A fake server: scripted replies to uploads, and the listing in pages of [pageSize]. */
    private class FakeApi(
        val listing: List<CloudSegmentItem> = emptyList(),
        val pageSize: Int = 100,
        val reply: (String) -> SegmentPutResult = { SegmentPutResult.Applied },
    ) : SegmentSyncApi {
        val sent = mutableListOf<String>()
        val asked = mutableListOf<Long>()

        override suspend fun putSegment(id: String, segment: SegmentUpload): SegmentPutResult {
            sent += id
            return reply(id)
        }

        override suspend fun listSegments(since: Long, page: String?): CloudSegmentPage {
            asked += since
            val start = page?.toInt() ?: 0
            val items = listing.filter { it.syncedAt > since }.drop(start).take(pageSize)
            val next = (start + pageSize).takeIf { it < listing.count { i -> i.syncedAt > since } }?.toString()
            return CloudSegmentPage(items, next)
        }
    }

    private class FakeCursor(var value: Long = 0L) : SyncCursor {
        override suspend fun get(userId: String) = value
        override suspend fun set(userId: String, syncedAt: Long) {
            value = syncedAt
        }
    }

    // --- Up

    @Test
    fun waitingSegmentsGoUpAndAreMarkedSynced() = runBlocking {
        val store = FakeStore(listOf(local("a"), local("b", syncState = SyncState.SYNCED)))
        val api = FakeApi()
        val report = SegmentUploader(store, api).uploadPending(user)
        assertEquals(listOf("a"), api.sent)
        assertEquals(1, report.synced)
        assertEquals(SyncState.SYNCED, store.rows.getValue("a").syncState)
    }

    @Test
    fun aNewerCloudCopyIsTaken() = runBlocking {
        val store = FakeStore(listOf(local("a", updatedAt = 1_000L)))
        val api = FakeApi(reply = { SegmentPutResult.Newer(cloud("a", updatedAt = 2_000L, name = "Renamed elsewhere")) })
        SegmentUploader(store, api).uploadPending(user)
        assertEquals("Renamed elsewhere", store.rows.getValue("a").name)
        assertEquals(SyncState.SYNCED, store.rows.getValue("a").syncState)
    }

    @Test
    fun aDifferentLineInTheCloudReplacesThePhonesCopyAndMatchesAgain() = runBlocking {
        val store = FakeStore(listOf(local("a", path = "_p~iF")))
        val api = FakeApi(reply = { SegmentPutResult.LineChanged(cloud("a", path = "_ulLnnqC")) })
        val report = SegmentUploader(store, api).uploadPending(user)
        assertEquals("_ulLnnqC", store.rows.getValue("a").path)
        assertEquals(SyncState.SYNCED, store.rows.getValue("a").syncState)
        assertEquals(1, report.synced)
        assertEquals(listOf("a"), store.matched)
    }

    @Test
    fun aNewerRenameOnThePhoneSurvivesADifferentLine() = runBlocking {
        val store = FakeStore(listOf(local("a", updatedAt = 3_000L, path = "_p~iF").copy(name = "Hill climb")))
        val api = FakeApi(reply = { SegmentPutResult.LineChanged(cloud("a", updatedAt = 2_000L, path = "_ulLnnqC", name = "Old name")) })
        val report = SegmentUploader(store, api).uploadPending(user)
        val row = store.rows.getValue("a")
        assertEquals("_ulLnnqC", row.path) // the cloud's line
        assertEquals("Hill climb", row.name) // the phone's newer rename
        assertEquals(3_000L, row.updatedAt)
        assertEquals(SyncState.PENDING, row.syncState) // goes up again, with the cloud's line
        assertEquals(0, report.synced)
    }

    @Test
    fun aDifferentLineOnASegmentDeletedInTheCloudIsNotMatched() = runBlocking {
        val store = FakeStore(listOf(local("a", updatedAt = 1_000L)))
        val api = FakeApi(reply = { SegmentPutResult.LineChanged(cloud("a", updatedAt = 2_000L, deletedAt = 2_000L, path = "_ulLnnqC")) })
        SegmentUploader(store, api).uploadPending(user)
        assertEquals(2_000L, store.rows.getValue("a").deletedAt)
        assertTrue(store.matched.isEmpty())
    }

    @Test
    fun aRefusedSegmentIsMarkedFailedAndTheRestCarryOn() = runBlocking {
        val store = FakeStore(listOf(local("a", updatedAt = 1L), local("b", updatedAt = 2L)))
        val api = FakeApi(reply = { id -> if (id == "a") SegmentPutResult.Rejected(422) else SegmentPutResult.Applied })
        val report = SegmentUploader(store, api).uploadPending(user)
        assertEquals(listOf("a"), report.rejected)
        assertEquals(SyncState.FAILED, store.rows.getValue("a").syncState)
        assertEquals(SyncState.SYNCED, store.rows.getValue("b").syncState)
    }

    @Test
    fun signingOutStopsTheUpload() = runBlocking {
        val store = FakeStore(listOf(local("a", updatedAt = 1L), local("b", updatedAt = 2L)))
        val api = FakeApi()
        var signedIn = true
        val uploader = SegmentUploader(store, object : SegmentSyncApi by api {
            override suspend fun putSegment(id: String, segment: SegmentUpload): SegmentPutResult {
                signedIn = false
                return api.putSegment(id, segment)
            }
        })
        uploader.uploadPending(user) { signedIn }
        assertEquals(listOf("a"), api.sent)
    }

    // --- Down

    @Test
    fun newSegmentsAreSavedAndOnlyLiveOnesMatched() = runBlocking {
        val store = FakeStore()
        val api = FakeApi(listOf(cloud("a", syncedAt = 10L), cloud("gone", syncedAt = 11L, deletedAt = 5L)))
        val report = SegmentDownloader(store, api, FakeCursor()).downloadChanges(user)
        assertEquals(2, report.downloaded)
        assertEquals(setOf("a", "gone"), store.rows.keys)
        assertEquals(5L, store.rows.getValue("gone").deletedAt) // saved deleted, for its review and a restore
        assertEquals(SyncState.SYNCED, store.rows.getValue("a").syncState)
        assertEquals(user, store.rows.getValue("a").userId)
        assertEquals(listOf("a"), store.matched)
    }

    @Test
    fun aReviewOfADeletedSegmentIsKeptThroughItsRestore() = runBlocking {
        val store = FakeStore()
        val cursor = FakeCursor()
        val deleted = cloud("a", updatedAt = 1_000L, deletedAt = 1_000L, syncedAt = 10L)
        SegmentDownloader(store, FakeApi(listOf(deleted, review("a", status = "SUBMITTED", syncedAt = 20L))), cursor)
            .downloadChanges(user)
        // A later run: the segment was restored (Undo) elsewhere.
        val restored = cloud("a", updatedAt = 2_000L, syncedAt = 30L)
        SegmentDownloader(store, FakeApi(listOf(restored)), cursor).downloadChanges(user)
        val row = store.rows.getValue("a")
        assertNull(row.deletedAt)
        assertEquals(SegmentStatus.SUBMITTED, row.status)
    }

    @Test
    fun aNewerCloudCopyUpdatesAndAnOlderOneIsKept() = runBlocking {
        val store = FakeStore(listOf(local("new", updatedAt = 1_000L), local("old", updatedAt = 3_000L)))
        val api = FakeApi(listOf(cloud("new", updatedAt = 2_000L, name = "Renamed"), cloud("old", updatedAt = 2_000L, name = "Stale")))
        val report = SegmentDownloader(store, api, FakeCursor()).downloadChanges(user)
        assertEquals(1, report.updated)
        assertEquals("Renamed", store.rows.getValue("new").name)
        assertEquals("Segment old", store.rows.getValue("old").name)
    }

    @Test
    fun aReviewSetsTheStatusFromTheServer() = runBlocking {
        val store = FakeStore(listOf(local("a", syncState = SyncState.SYNCED)))
        val api = FakeApi(listOf(review("a", syncedAt = 20L)))
        val report = SegmentDownloader(store, api, FakeCursor()).downloadChanges(user)
        assertEquals(1, report.reviews)
        assertEquals(SegmentStatus.PUBLIC, store.rows.getValue("a").status)
        assertEquals("v-a", store.rows.getValue("a").verifiedSegmentId)
    }

    @Test
    fun aReviewBeforeItsSegmentIsAppliedOnceTheSegmentArrives() = runBlocking {
        // The segment was renamed after its review, so it is listed after it.
        val store = FakeStore()
        val api = FakeApi(listOf(review("a", syncedAt = 20L), cloud("a", syncedAt = 30L)))
        val report = SegmentDownloader(store, api, FakeCursor()).downloadChanges(user)
        assertEquals(1, report.reviews)
        assertEquals(SegmentStatus.PUBLIC, store.rows.getValue("a").status)
    }

    @Test
    fun aRunStoppedForTimeKeepsTheCursorBeforeAWaitingReview() = runBlocking {
        val store = FakeStore()
        val api = FakeApi(listOf(review("a", syncedAt = 20L), cloud("other", syncedAt = 25L), cloud("a", syncedAt = 30L)))
        val cursor = FakeCursor()
        var clock = 0L
        // Time runs out after two items: the review is waiting, its segment not read yet.
        val report = SegmentDownloader(store, api, cursor, now = { clock++ }).downloadChanges(user, deadline = 2L)
        assertTrue(report.more)
        assertEquals(19L, cursor.value) // just before the review, so the next run reads it again
    }

    @Test
    fun aReviewWhoseSegmentNeverComesIsDroppedAndTheCursorMovesOn() = runBlocking {
        val cursor = FakeCursor()
        val report = SegmentDownloader(FakeStore(), FakeApi(listOf(review("missing", syncedAt = 20L))), cursor)
            .downloadChanges(user)
        assertEquals(0, report.reviews)
        assertEquals(20L, cursor.value)
    }

    @Test
    fun signingOutPartWayStopsTheDownload() = runBlocking {
        val store = FakeStore()
        val cursor = FakeCursor()
        val api = FakeApi(listOf(cloud("a", syncedAt = 10L), cloud("b", syncedAt = 20L), cloud("c", syncedAt = 30L)))
        var checks = 0
        SegmentDownloader(store, api, cursor).downloadChanges(user, stillSignedIn = { checks++ < 1 })
        assertEquals(setOf("a"), store.rows.keys)
        assertEquals(10L, cursor.value) // only what was handled
    }

    @Test
    fun anUnknownStatusIsIgnoredNotACrash() = runBlocking {
        val store = FakeStore(listOf(local("a", syncState = SyncState.SYNCED)))
        val api = FakeApi(listOf(review("a", status = "FEATURED")))
        SegmentDownloader(store, api, FakeCursor()).downloadChanges(user)
        assertEquals(SegmentStatus.PRIVATE, store.rows.getValue("a").status)
    }

    @Test
    fun theCursorMovesForwardAndEachRunReadsBackFiveMinutes() = runBlocking {
        val cursor = FakeCursor(value = 1_000_000L)
        val api = FakeApi(listOf(cloud("a", syncedAt = 2_000_000L)))
        SegmentDownloader(FakeStore(), api, cursor).downloadChanges(user)
        assertEquals(listOf(1_000_000L - Downloader.OVERLAP_MS), api.asked)
        assertEquals(2_000_000L, cursor.value)
    }

    @Test
    fun pagesAreFollowedToTheEnd() = runBlocking {
        val items = (1..5).map { cloud("s$it", syncedAt = it * 10L) }
        val store = FakeStore()
        SegmentDownloader(store, FakeApi(items, pageSize = 2), FakeCursor()).downloadChanges(user)
        assertEquals(5, store.rows.size)
    }

    @Test(expected = IOException::class)
    fun networkTroubleIsThrownForARetry() = runBlocking<Unit> {
        val api = object : SegmentSyncApi {
            override suspend fun putSegment(id: String, segment: SegmentUpload) = SegmentPutResult.Applied
            override suspend fun listSegments(since: Long, page: String?): CloudSegmentPage = throw IOException("offline")
        }
        SegmentDownloader(FakeStore(), api, FakeCursor()).downloadChanges(user)
    }

    // --- Reading replies

    @Test
    fun aPageReadsBothKindsAndSkipsUnknownOnes() {
        val page = parseSegmentPage(
            """
            {"items":[
              {"kind":"segment","id":"a","name":"Hill","path":"_p~iF","distanceMeters":1240,"sport":"ride",
               "createdAt":1,"updatedAt":2,"deletedAt":null,"syncedAt":10},
              {"kind":"review","segmentId":"a","status":"SUBMITTED","submittedAt":3,"syncedAt":11},
              {"kind":"leaderboard","segmentId":"a","syncedAt":12}
            ],"nextCursor":"next"}
            """.trimIndent()
        )
        assertEquals(2, page.items.size)
        assertEquals("Hill", (page.items[0] as CloudSegment).name)
        assertEquals("SUBMITTED", (page.items[1] as CloudReview).status)
        assertEquals("next", page.nextCursor)
    }

    @Test
    fun aPageOfAnotherShapeIsASerializationError() {
        for (body in listOf("""{"nextCursor":"x"}""", """{"items":null}""", """{"items":[1]}""", "[]", "<html>Wi-Fi login</html>")) {
            assertThrows(body, SerializationException::class.java) { parseSegmentPage(body) }
        }
    }

    @Test
    fun aPageWithOnlyACursorIsEmpty() {
        val page = parseSegmentPage("""{"items":[],"nextCursor":"x"}""")
        assertTrue(page.items.isEmpty())
        assertEquals("x", page.nextCursor)
    }

    @Test
    fun uploadRepliesAreReadByCodeAndBody() {
        val copy = """{"applied":false,"current":{"kind":"segment","id":"a","name":"Hill","path":"_p~iF",""" +
            """"distanceMeters":1240,"sport":"ride","createdAt":1,"updatedAt":9,"syncedAt":10}}"""
        assertEquals(SegmentPutResult.Applied, segmentPutResultOf(200, """{"applied":true}"""))
        assertTrue(segmentPutResultOf(409, copy) is SegmentPutResult.Newer)
        assertTrue(segmentPutResultOf(400, copy) is SegmentPutResult.LineChanged)
        // Without a stored copy, a 400 or 409 is a plain refusal.
        assertEquals(SegmentPutResult.Rejected(400), segmentPutResultOf(400, """{"error":"Invalid name"}"""))
        assertEquals(SegmentPutResult.Rejected(409), segmentPutResultOf(409, ""))
        assertThrows(IOException::class.java) { segmentPutResultOf(503, "") }
    }

    @Test
    fun onlyAClearRefusalMarksAnUploadFailed() {
        assertTrue(refusesTheUpload(400))
        assertTrue(refusesTheUpload(422))
        assertFalse(refusesTheUpload(404))
        assertFalse(refusesTheUpload(500))
        assertFalse(refusesTheUpload(302))
    }

    @Test
    fun aConflictReplyGivesTheStoredCopy() {
        val body = """{"applied":false,"current":{"kind":"segment","id":"a","name":"Hill","path":"_p~iF",""" +
            """"distanceMeters":1240,"sport":"ride","createdAt":1,"updatedAt":9,"syncedAt":10}}"""
        assertEquals(9L, parseSegmentConflict(body)?.updatedAt)
        assertNull(parseSegmentConflict("""{"error":"Invalid name"}"""))
        assertNull(parseSegmentConflict("<html>Wi-Fi login</html>"))
    }

    @Test
    fun anUploadCarriesOnlyTheRidersFields() {
        val json = SegmentUpload.from(local("a").copy(status = SegmentStatus.SUBMITTED)).toJson()
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("\"deletedAt\":null"))
        assertFalse(json.contains("status"))
        assertFalse(json.contains("userId"))
        assertFalse(json.contains("syncState"))
    }

    @Test
    fun statusesFromTheServerAreReadSafely() {
        assertEquals(SegmentStatus.REJECTED, SegmentStatus.fromServer("REJECTED"))
        assertNull(SegmentStatus.fromServer("FEATURED"))
        assertNull(SegmentStatus.fromServer(null))
    }
}
