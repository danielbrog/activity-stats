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

    /**
     * The server refused the copy at [sentUpdatedAt]: it waits for an edit or Sync now instead of
     * retrying every sync. Leaves the ride waiting if it changed after that copy was sent.
     */
    suspend fun markFailed(id: String, sentUpdatedAt: Long)

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
class Uploader(
    private val store: SyncStore,
    private val api: SyncApi,
    /** The largest track that is sent; tests set a small one. */
    private val maxTrackBytes: Int = TrackFile.MAX_BYTES,
) {

    /**
     * How a run went: rides now in the cloud, and the ids not sent this time. Those the server
     * clearly refused are marked failed; the rest just wait for the next sync.
     */
    data class Report(
        val synced: Int,
        val rejected: List<String>,
        /** New rides left waiting because the week's limit is reached; they go up after it resets. */
        val limited: List<String> = emptyList(),
        /** When the server counts again (Monday 00:00 UTC), if the limit was reached; else null. */
        val limitResetsAt: Long? = null,
        /** Renames and deletes left waiting because the day's edits are used up. */
        val editLimited: List<String> = emptyList(),
        /** When the server counts edits again (the next 00:00 UTC), if that limit was reached; else null. */
        val editLimitResetsAt: Long? = null,
    )

    /**
     * Uploads every waiting ride of [userId], oldest first. A ride the server refuses (a 4xx) is
     * marked failed; a reply that looks like trouble on the way rather than with the ride (a
     * redirect, a missing route, a garbled body) leaves it waiting. Either way the rest carry on. Stops early if [stillSignedIn] turns false, so a sign-out
     * or a switch to another account never sends one account's rides under another's sign-in.
     * Throws [IOException] on network trouble, leaving the rides done so far synced and the
     * rest waiting, for the next run.
     */
    suspend fun uploadPending(userId: String, stillSignedIn: () -> Boolean = { true }): Report {
        var synced = 0
        val rejected = mutableListOf<String>()
        val limited = mutableListOf<String>()
        var limitResetsAt: Long? = null
        val editLimited = mutableListOf<String>()
        var editLimitResetsAt: Long? = null

        /** Notes a ride not sent; parks it as failed only if the server clearly refused it. */
        suspend fun notSent(ride: ActivityEntity, code: Int?) {
            if (code != null && refusesTheUpload(code)) store.markFailed(ride.id, ride.updatedAt)
            rejected += ride.id
        }

        for (ride in store.pendingUploads(userId)) {
            if (!stillSignedIn()) break
            // A live ride whose track isn't up yet is new to the server and counts towards the
            // week; anything else counts as an edit, towards the day. Once either limit is
            // reached, the other rides it would count don't go this run, and wait for the reset.
            // (Two the phone can't tell apart wait a day too: a restore of a ride the server has
            // as deleted, and a ride whose track went up but whose summary didn't.)
            val isNewRide = !ride.trackUploaded && ride.deletedAt == null
            if (limited.isNotEmpty() && isNewRide) {
                limited += ride.id
                continue
            }
            if (editLimitResetsAt != null && !isNewRide) {
                editLimited += ride.id
                continue
            }
            try {
                if (isNewRide) {
                    val points = store.pointsFor(ride.id)
                    val file = TrackFile.encode(points)
                    if (file.size > maxTrackBytes || points.size > TrackFile.MAX_FIXES) {
                        // The server would refuse it: don't send it, and set the ride aside.
                        notSent(ride, TRACK_TOO_LARGE)
                        continue
                    }
                    api.uploadTrack(ride.id, file)
                    store.markTrackUploaded(ride.id)
                }
                when (val result = api.putActivity(ride.id, RideSummary.from(ride))) {
                    PutResult.Applied -> {
                        store.markSynced(ride.id, ride.updatedAt)
                        synced++
                    }
                    // If the phone's copy changed meanwhile, it is newer still and goes up next time.
                    is PutResult.Newer -> if (store.applyCloudCopy(ride.id, result.cloud)) synced++
                    is PutResult.Rejected -> notSent(ride, result.code)
                }
            } catch (e: RideLimitReachedException) {
                // Not refused: it stays waiting, and the first sync after the reset sends it.
                limited += ride.id
                limitResetsAt = e.resetsAt
            } catch (e: EditLimitReachedException) {
                // The same, for the day's edits.
                editLimited += ride.id
                editLimitResetsAt = e.resetsAt
            } catch (e: RejectedException) {
                notSent(ride, e.code)
            } catch (e: SerializationException) {
                // A reply that isn't what the backend sends, like a Wi-Fi login page: not the
                // ride's fault, so it just waits for the next sync.
                notSent(ride, code = null)
            }
        }
        return Report(synced, rejected, limited, limitResetsAt, editLimited, editLimitResetsAt)
    }
}

/**
 * A reply that refuses the ride or segment itself, so retrying it unchanged won't help: a 4xx
 * other than 404, which here means a missing route (a setup problem), not a missing item.
 * Redirects and the retried codes (401, 403, 408, 429) never mark anything failed. Shared by
 * the ride and segment uploads, so the two can't drift apart.
 */
internal fun refusesTheUpload(code: Int) = code in 400..499 && code != 404

/** HTTP's "content too large": what the server answers for a track over its limit. */
internal const val TRACK_TOO_LARGE = 413
