package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.data.SyncState
import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DownloaderTest {

    private val user = "user-1"
    private val track = listOf(
        GpsData(1, 1_000L, 43.65, -79.38, 5f, 5f, FixStatus.Kept),
        GpsData(2, 2_000L, 43.66, -79.38, 5f, 5f, FixStatus.Kept, stretch = 1),
    )

    /** "Now", on the same clock as syncedAt. Rides below are synced a little before it. */
    private var clock = 10_000_000L

    private fun cloud(id: String, syncedAt: Long, updatedAt: Long = syncedAt, deletedAt: Long? = null, title: String = "Ride $id") =
        CloudRide(
            id = id, type = "ride", startedAt = 1_000L, endedAt = 2_000L, distanceMeters = 100.0,
            movingTimeMs = 1_000L, elapsedTimeMs = 1_000L, avgSpeedMps = 5.0, maxSpeedMps = 6.0,
            title = title, updatedAt = updatedAt, deletedAt = deletedAt, syncedAt = syncedAt,
        )

    /** An in-memory database with the same rules as the real queries. */
    private class FakeStore(rides: List<ActivityEntity> = emptyList()) : DownloadStore {
        val rides = rides.associateBy { it.id }.toMutableMap()
        val points = mutableMapOf<String, List<GpsData>>()

        override suspend fun getActivity(id: String) = rides[id]

        override suspend fun saveDownloadedRide(ride: ActivityEntity, fixes: List<GpsData>) {
            check(ride.id !in rides) { "saved twice" }
            rides[ride.id] = ride
            points[ride.id] = fixes
        }

        override suspend fun applyCloudCopy(id: String, cloud: CloudCopy): Boolean {
            val current = rides.getValue(id)
            if (current.updatedAt >= cloud.updatedAt) return false
            rides[id] = current.copy(title = cloud.title, deletedAt = cloud.deletedAt, updatedAt = cloud.updatedAt,
                syncState = SyncState.SYNCED)
            return true
        }
    }

    private class FakeCursor(var saved: Pair<String, Long>? = null) : SyncCursor {
        val history = mutableListOf<Long>()
        override suspend fun get(userId: String) = saved?.takeIf { it.first == userId }?.second ?: 0L
        override suspend fun set(userId: String, syncedAt: Long) {
            saved = userId to syncedAt
            history += syncedAt
        }
    }

    /**
     * A server listing [pages] of rides, each page's cursor being its index, filtered by `since`
     * the way the real query is (syncedAt > since). Records every listing asked for.
     */
    private class FakeApi(val pages: List<List<CloudRide>>) : SyncApi {
        val listings = mutableListOf<Pair<Long, String?>>()
        val missingTracks = mutableSetOf<String>()
        val brokenTracks = mutableSetOf<String>()
        val refusedTracks = mutableSetOf<String>()
        val badLinkReplies = mutableSetOf<String>()
        var failListingAt: Int? = null

        override suspend fun listActivities(since: Long, page: String?): CloudPage {
            listings += since to page
            val index = page?.toInt() ?: 0
            if (failListingAt == index) throw IOException("offline")
            val next = if (index + 1 < pages.size) "${index + 1}" else null
            return CloudPage(pages[index].filter { it.syncedAt > since }, next)
        }

        override suspend fun trackDownloadLink(id: String): String? {
            if (id in badLinkReplies) throw SerializationException("not JSON")
            return if (id in missingTracks) null else "https://example.test/$id"
        }

        override suspend fun downloadTrack(url: String): ByteArray {
            val id = url.substringAfterLast('/')
            if (id in refusedTracks) throw RejectedException(403)
            return if (id in brokenTracks) "not gzip".toByteArray() else TrackFile.encode(listOf(
                GpsData(1, 1_000L, 43.65, -79.38, 5f, 5f, FixStatus.Kept),
                GpsData(2, 2_000L, 43.66, -79.38, 5f, 5f, FixStatus.Kept, stretch = 1),
            ))
        }

        override suspend fun putActivity(id: String, summary: RideSummary) = error("not used by the download")
        override suspend fun trackUploadLink(id: String) = error("not used by the download")
        override suspend fun uploadTrack(link: UploadLink, bytes: ByteArray) = error("not used by the download")
    }

    private fun downloader(store: DownloadStore, api: FakeApi, cursor: FakeCursor) =
        Downloader(store, api, cursor, now = { clock })

    private fun run(store: FakeStore, api: FakeApi, cursor: FakeCursor, stillSignedIn: () -> Boolean = { true }) =
        runBlocking { downloader(store, api, cursor).downloadChanges(user, stillSignedIn) }

    @Test
    fun aFreshPhoneGetsEveryRideWithItsTrack() {
        val store = FakeStore()
        val api = FakeApi(listOf(listOf(cloud("a", 10), cloud("b", 20)), listOf(cloud("c", 30))))
        val cursor = FakeCursor()
        val report = run(store, api, cursor)

        assertEquals(3, report.downloaded)
        assertEquals(setOf("a", "b", "c"), store.rides.keys)
        assertEquals(track, store.points.getValue("a"))
        val saved = store.rides.getValue("a")
        assertEquals(user, saved.userId)
        assertEquals(SyncState.SYNCED, saved.syncState)
        assertTrue(saved.trackUploaded)
        assertEquals(user to 30L, cursor.saved)
    }

    @Test
    fun eachRunReadsBackOverTheOverlapAndKeepsSinceAcrossPages() {
        val saved = 1_000_000L
        val api = FakeApi(listOf(listOf(cloud("a", saved + 10)), listOf(cloud("b", saved + 20))))
        run(FakeStore(), api, FakeCursor(user to saved))
        val since = saved - Downloader.OVERLAP_MS
        assertEquals(listOf(since to null, since to "1"), api.listings)
    }

    @Test
    fun aRideListedLateBehindTheCursorIsStillFound() {
        // The cloud's index showed b before a, though a was saved first: the cursor already
        // passed a when it appeared.
        val store = FakeStore()
        val cursor = FakeCursor()
        run(store, FakeApi(listOf(listOf(cloud("b", 2_000)))), cursor)
        run(store, FakeApi(listOf(listOf(cloud("a", 1_000), cloud("b", 2_000)))), cursor)
        assertEquals(setOf("a", "b"), store.rides.keys)
    }

    @Test
    fun twoRidesSharingATimeAreBothFetchedAcrossAStop() {
        // a and b were saved in the same millisecond; the first run stops after a.
        val store = FakeStore()
        val cursor = FakeCursor()
        val api = FakeApi(listOf(listOf(cloud("a", clock - 1_000), cloud("b", clock - 1_000)))).apply { missingTracks += "b" }
        try {
            run(store, api, cursor)
            fail("Expected a retry while b's track is missing")
        } catch (_: TrackNotReadyException) {
        }
        api.missingTracks.clear()
        run(store, api, cursor)
        assertEquals(setOf("a", "b"), store.rides.keys)
    }

    @Test
    fun theCursorNeverMovesBack() {
        val saved = 1_000_000L
        val cursor = FakeCursor(user to saved)
        // The overlap brings back a ride older than the cursor.
        run(FakeStore(), FakeApi(listOf(listOf(cloud("old", saved - 60_000)))), cursor)
        assertEquals(user to saved, cursor.saved)
        assertTrue(cursor.history.isEmpty())
    }

    @Test
    fun aNetworkFailureKeepsTheProgressMadeSoFar() {
        val store = FakeStore()
        val api = FakeApi(listOf(listOf(cloud("a", 10), cloud("b", 20)), listOf(cloud("c", 30)))).apply { failListingAt = 1 }
        val cursor = FakeCursor()
        try {
            run(store, api, cursor)
            fail("Expected the failure to reach the worker, so it retries")
        } catch (_: IOException) {
        }
        assertEquals(setOf("a", "b"), store.rides.keys)
        assertEquals(user to 20L, cursor.saved)
    }

    @Test
    fun aRecentlyMissingTrackStopsWithoutMovingPastIt() {
        val store = FakeStore()
        val api = FakeApi(listOf(listOf(cloud("a", clock - 3_000), cloud("b", clock - 2_000), cloud("c", clock - 1_000))))
            .apply { missingTracks += "b" }
        val cursor = FakeCursor()
        try {
            run(store, api, cursor)
            fail("Expected a retry")
        } catch (_: TrackNotReadyException) {
        }
        // b is never saved without its points, and the next run tries it again.
        assertEquals(setOf("a"), store.rides.keys)
        assertEquals(user to clock - 3_000, cursor.saved)
    }

    @Test
    fun aTrackMissingForOverAnHourIsSkippedSoItCantBlockTheRest() {
        val store = FakeStore()
        val old = clock - Downloader.MISSING_TRACK_GIVE_UP_MS - 1
        val api = FakeApi(listOf(listOf(cloud("lost", old), cloud("next", old + 1)))).apply { missingTracks += "lost" }
        val report = run(store, api, FakeCursor())
        assertEquals(listOf("lost"), report.skipped)
        assertEquals(setOf("next"), store.rides.keys)
    }

    @Test
    fun aTrackThatCantBeHadSkipsJustThatRide() {
        val store = FakeStore()
        val api = FakeApi(listOf(listOf(cloud("broken", 10), cloud("refused", 20), cloud("badLink", 30), cloud("fine", 40)))).apply {
            brokenTracks += "broken"
            refusedTracks += "refused"
            badLinkReplies += "badLink"
        }
        val report = run(store, api, FakeCursor())
        assertEquals(listOf("broken", "refused", "badLink"), report.skipped)
        assertEquals(setOf("fine"), store.rides.keys)
    }

    @Test
    fun aRunStopsAtItsDeadlineAndSaysThereIsMore() {
        val store = FakeStore()
        val cursor = FakeCursor()
        val api = FakeApi(listOf(listOf(cloud("a", 10), cloud("b", 20))))
        val report = runBlocking {
            // The clock passes the deadline once a has been saved.
            downloader(object : DownloadStore by store {
                override suspend fun saveDownloadedRide(ride: ActivityEntity, fixes: List<GpsData>) {
                    store.saveDownloadedRide(ride, fixes)
                    clock += 1_000
                }
            }, api, cursor).downloadChanges(user, deadline = clock + 500)
        }
        assertTrue(report.more)
        assertEquals(setOf("a"), store.rides.keys)
        assertEquals(user to 10L, cursor.saved)
    }

    @Test
    fun deletedRidesThisPhoneNeverHadAreSkipped() {
        val store = FakeStore()
        run(store, FakeApi(listOf(listOf(cloud("gone", 10, deletedAt = 9)))), FakeCursor())
        assertTrue(store.rides.isEmpty())
    }

    @Test
    fun newerRenamesAndDeletesReachRidesThisPhoneHas() {
        val phone = ActivityEntity(id = "a", userId = user, startedAt = 1_000L, endedAt = 2_000L, title = "Old",
            updatedAt = 5L, syncState = SyncState.SYNCED, trackUploaded = true)
        val store = FakeStore(listOf(phone, phone.copy(id = "b")))
        val report = run(store, FakeApi(listOf(listOf(
            cloud("a", 10, updatedAt = 8, title = "Renamed elsewhere"),
            cloud("b", 11, updatedAt = 8, deletedAt = 8),
        ))), FakeCursor())

        assertEquals(2, report.updated)
        assertEquals("Renamed elsewhere", store.rides.getValue("a").title)
        assertEquals(8L, store.rides.getValue("b").deletedAt)
        // Nothing downloaded: the points this phone has stay as they are.
        assertTrue(store.points.isEmpty())
    }

    @Test
    fun thisPhonesOwnUploadsComingBackChangeNothing() {
        val phone = ActivityEntity(id = "a", userId = user, startedAt = 1_000L, endedAt = 2_000L, title = "Mine",
            updatedAt = 8L, syncState = SyncState.SYNCED, trackUploaded = true)
        val store = FakeStore(listOf(phone))
        val report = run(store, FakeApi(listOf(listOf(cloud("a", 10, updatedAt = 8, title = "Mine")))), FakeCursor())
        assertEquals(Downloader.Report(0, 0, emptyList()), report)
        assertFalse(report.more)
        assertEquals(phone, store.rides.getValue("a"))
    }

    @Test
    fun anotherAccountStartsFromTheBeginning() {
        val api = FakeApi(listOf(listOf(cloud("a", 10))))
        run(FakeStore(), api, FakeCursor("someone-else" to 999_999_999L))
        assertEquals(0L, api.listings.single().first)
    }

    @Test
    fun signingOutPartWayStops() {
        val store = FakeStore()
        var signedIn = true
        val api = FakeApi(listOf(listOf(cloud("a", 10), cloud("b", 20))))
        runBlocking {
            downloader(object : DownloadStore by store {
                override suspend fun saveDownloadedRide(ride: ActivityEntity, fixes: List<GpsData>) {
                    store.saveDownloadedRide(ride, fixes)
                    signedIn = false
                }
            }, api, FakeCursor()).downloadChanges(user, { signedIn })
        }
        assertEquals(setOf("a"), store.rides.keys)
        assertNull(store.rides["b"])
    }
}
