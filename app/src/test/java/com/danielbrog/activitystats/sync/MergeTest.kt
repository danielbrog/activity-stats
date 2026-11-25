package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.data.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeTest {

    private fun cloud(updatedAt: Long = 5_000L, deletedAt: Long? = null, endedAt: Long? = 2_000L) = CloudRide(
        id = "a", type = "ride", startedAt = 1_000L, endedAt = endedAt,
        distanceMeters = 1_000.0, movingTimeMs = 60_000L, elapsedTimeMs = 60_000L,
        avgSpeedMps = 5.0, maxSpeedMps = 8.0, title = "Cloud", updatedAt = updatedAt,
        deletedAt = deletedAt, syncedAt = 9_000L,
    )

    private fun local(updatedAt: Long, syncState: SyncState = SyncState.SYNCED, deletedAt: Long? = null) =
        ActivityEntity(
            id = "a",
            startedAt = 1_000L,
            endedAt = 2_000L,
            title = "Phone",
            updatedAt = updatedAt,
            syncState = syncState,
            deletedAt = deletedAt,
        )

    @Test
    fun aRideNewToThisPhoneIsDownloaded() {
        assertEquals(Decision.Download, decide(null, cloud()))
    }

    @Test
    fun aDeletedRideNewToThisPhoneIsSkipped() {
        assertEquals(Decision.Skip, decide(null, cloud(deletedAt = 4_000L)))
    }

    @Test
    fun anUnfinishedRideNewToThisPhoneIsSkipped() {
        assertEquals(Decision.Skip, decide(null, cloud(endedAt = null)))
    }

    @Test
    fun aNewerCloudCopyIsTaken() {
        assertEquals(Decision.TakeCloud, decide(local(updatedAt = 4_000L), cloud(updatedAt = 5_000L)))
    }

    @Test
    fun aNewerCloudCopyWinsOverOlderUnsentEdits() {
        // The phone renamed it earlier but hasn't uploaded yet; the cloud's later change wins.
        assertEquals(
            Decision.TakeCloud,
            decide(local(updatedAt = 4_000L, syncState = SyncState.PENDING), cloud(updatedAt = 5_000L)),
        )
    }

    @Test
    fun aDeletionFromTheCloudReachesARideThisPhoneHas() {
        assertEquals(
            Decision.TakeCloud,
            decide(local(updatedAt = 4_000L), cloud(updatedAt = 5_000L, deletedAt = 5_000L)),
        )
    }

    @Test
    fun aRestoreFromTheCloudReachesARideDeletedHere() {
        assertEquals(
            Decision.TakeCloud,
            decide(local(updatedAt = 4_000L, deletedAt = 4_000L), cloud(updatedAt = 5_000L)),
        )
    }

    @Test
    fun aRefusedRideTakesANewerCloudCopy() {
        assertEquals(
            Decision.TakeCloud,
            decide(local(updatedAt = 4_000L, syncState = SyncState.FAILED), cloud(updatedAt = 5_000L)),
        )
    }

    @Test
    fun aRefusedRideKeepsItsCopyOverAnOlderCloudOne() {
        assertEquals(
            Decision.Keep,
            decide(local(updatedAt = 6_000L, syncState = SyncState.FAILED), cloud(updatedAt = 5_000L)),
        )
    }

    @Test
    fun theSameCopyIsKept() {
        // Most often this phone's own upload coming back in the listing.
        assertEquals(Decision.Keep, decide(local(updatedAt = 5_000L), cloud(updatedAt = 5_000L)))
    }

    @Test
    fun aNewerPhoneCopyIsKeptForTheNextUpload() {
        assertEquals(
            Decision.Keep,
            decide(local(updatedAt = 6_000L, syncState = SyncState.PENDING), cloud(updatedAt = 5_000L)),
        )
    }
}
