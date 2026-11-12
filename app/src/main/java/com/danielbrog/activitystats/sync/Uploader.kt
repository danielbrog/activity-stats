package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.location.GpsData
import java.io.IOException
import kotlinx.serialization.SerializationException

/** What the upload needs from the database. [com.danielbrog.activitystats.data.ActivityRepository] provides it. */
interface SyncStore {
    /** Finished rides of [userId] waiting to upload, oldest first. */
    suspend fun pendingUploads(userId: String): List<ActivityEntity>

    suspend fun pointsFor(id: String): List<GpsData>

    suspend fun markTrackUploaded(id: String)

    /** Marks the ride synced unless it changed after the copy at [sentUpdatedAt] was sent. */
    suspend fun markSynced(id: String, sentUpdatedAt: Long)

    /**
     * Takes the cloud's copy and marks the ride synced, unless the phone's copy is newer (it
     * was edited during the upload). Returns whether the cloud's copy was taken.
     */
    suspend fun applyCloudCopy(id: String, cloud: CloudCopy): Boolean
}

/**
 * Sends the signed-in user's waiting rides to the cloud. Plain Kotlin, unit-tested against a
 * fake server.
 *
 * Each ride's track goes up before its summary, so a ride in the cloud has its track there
 * too, unless it was deleted before its track went up: then only its summary, marked deleted,
 * is sent. Code that downloads rides must expect that. A track is uploaded once; later edits
 * only re-send the summary.
 */
class Uploader(private val store: SyncStore, private val api: SyncApi) {

    /** How a run went: rides now in the cloud, and the ids skipped because the server refused them. */
    data class Report(val synced: Int, val rejected: List<String>)

    /**
     * Uploads every waiting ride of [userId], oldest first. A ride the server refuses is
     * skipped and the rest carry on. Stops early if [stillSignedIn] turns false, so a sign-out
     * or a switch to another account never sends one account's rides under another's sign-in.
     * Throws [IOException] on network trouble, leaving the rides done so far synced and the
     * rest waiting, for the next run.
     */
    suspend fun uploadPending(userId: String, stillSignedIn: () -> Boolean = { true }): Report {
        var synced = 0
        val rejected = mutableListOf<String>()
        for (ride in store.pendingUploads(userId)) {
            if (!stillSignedIn()) break
            try {
                if (!ride.trackUploaded && ride.deletedAt == null) {
                    val file = TrackFile.encode(store.pointsFor(ride.id))
                    api.uploadTrack(api.trackUploadLink(ride.id), file)
                    store.markTrackUploaded(ride.id)
                }
                when (val result = api.putActivity(ride.id, RideSummary.from(ride))) {
                    PutResult.Applied -> {
                        store.markSynced(ride.id, ride.updatedAt)
                        synced++
                    }
                    // If the phone's copy changed meanwhile, it is newer still and goes up next time.
                    is PutResult.Newer -> if (store.applyCloudCopy(ride.id, result.cloud)) synced++
                    is PutResult.Rejected -> rejected += ride.id
                }
            } catch (e: RejectedException) {
                rejected += ride.id
            } catch (e: SerializationException) {
                // A reply that isn't what the backend sends: skip this ride rather than stall the rest.
                rejected += ride.id
            }
        }
        return Report(synced, rejected)
    }
}
