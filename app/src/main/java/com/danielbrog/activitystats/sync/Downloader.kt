package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.location.GpsData
import java.io.IOException
import kotlinx.serialization.SerializationException

/** What the download needs from the database. [com.danielbrog.activitystats.data.ActivityRepository] provides it. */
interface DownloadStore {
    suspend fun getActivity(id: String): ActivityEntity?

    /** Saves a ride new to this phone together with its points, then works out its best efforts. */
    suspend fun saveDownloadedRide(ride: ActivityEntity, fixes: List<GpsData>)

    /** Takes the cloud's newer copy unless the phone's is newer. Returns whether it was taken. */
    suspend fun applyCloudCopy(id: String, cloud: CloudCopy): Boolean
}

/**
 * A ride that isn't deleted has no track file in the cloud yet. The upload always sends the
 * track first, so this should be brief; the download stops and tries again later rather than
 * save a ride with no points.
 */
class TrackNotReadyException(id: String) : IOException("No track file yet for $id")

/**
 * Brings rides changed in the cloud onto this phone: rides it doesn't have, and newer renames
 * and deletes of rides it has. Plain Kotlin, unit-tested against a fake server.
 *
 * Works through the listing oldest first and saves the cursor as it goes, so an interrupted
 * restore carries on where it stopped. What happens to each ride is [decide]'s call.
 *
 * Each run reads back over the last [OVERLAP_MS] before the cursor. The cloud's listing can
 * show a ride slightly late (its index catches up a moment after the save), and two rides can
 * share a time; without the overlap, either could fall behind the cursor and never be seen.
 * Reading a ride twice is harmless: one already on the phone comes out as [Decision.Keep].
 */
class Downloader(
    private val store: DownloadStore,
    private val api: SyncApi,
    private val cursor: SyncCursor,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * How a run went: rides saved new, rides updated from the cloud, ids skipped because their
     * track couldn't be had, and whether it stopped for time with more still to fetch.
     */
    data class Report(val downloaded: Int, val updated: Int, val skipped: List<String>, val more: Boolean = false)

    /**
     * Downloads everything changed since the last run for [userId]. Stops early if
     * [stillSignedIn] turns false, or at [deadline] (with [Report.more] set, so the caller can
     * queue another run). Throws [IOException] on network trouble or a track not there yet,
     * keeping the progress made so far.
     */
    suspend fun downloadChanges(
        userId: String,
        stillSignedIn: () -> Boolean = { true },
        deadline: Long = Long.MAX_VALUE,
    ): Report {
        var downloaded = 0
        var updated = 0
        val skipped = mutableListOf<String>()
        var high = cursor.get(userId)
        // The server's page cursor continues one listing, so `since` stays fixed across its pages.
        val since = (high - OVERLAP_MS).coerceAtLeast(0L)
        var page: String? = null
        do {
            val listing = api.listActivities(since, page)
            for (ride in listing.items) {
                if (!stillSignedIn()) return Report(downloaded, updated, skipped)
                if (now() >= deadline) return Report(downloaded, updated, skipped, more = true)
                when (decide(store.getActivity(ride.id), ride)) {
                    Decision.Download -> when (val fixes = fetchTrack(ride)) {
                        null -> skipped += ride.id
                        else -> {
                            store.saveDownloadedRide(ride.toEntity(userId), fixes)
                            downloaded++
                        }
                    }
                    Decision.TakeCloud -> if (store.applyCloudCopy(ride.id, ride.toCloudCopy())) updated++
                    Decision.Keep, Decision.Skip -> Unit
                }
                // Never move back: the overlap re-reads rides older than the saved cursor.
                if (ride.syncedAt > high) {
                    high = ride.syncedAt
                    cursor.set(userId, high)
                }
            }
            page = listing.nextCursor
        } while (page != null)
        return Report(downloaded, updated, skipped)
    }

    /**
     * The ride's points, or null if they can't be had: its file is damaged or from a newer app,
     * the server refuses it, or it has been missing for over [MISSING_TRACK_GIVE_UP_MS]. That
     * ride is skipped rather than holding up the rest; a newer app restarts the cursor (see
     * [SyncCursorStore]), so it is tried again then. Throws [TrackNotReadyException] while a
     * missing track is recent, and [IOException] on network trouble.
     */
    private suspend fun fetchTrack(ride: CloudRide): List<GpsData>? {
        val bytes = try {
            val link = api.trackDownloadLink(ride.id)
            if (link == null) {
                if (now() - ride.syncedAt > MISSING_TRACK_GIVE_UP_MS) return null
                throw TrackNotReadyException(ride.id)
            }
            api.downloadTrack(link)
        } catch (e: RejectedException) {
            return null
        } catch (e: SerializationException) {
            return null // a link reply that isn't what the backend sends
        }
        return try {
            TrackFile.decode(bytes)
        } catch (e: IllegalArgumentException) {
            null // a newer format, or JSON that isn't a track (SerializationException is one of these)
        } catch (e: IOException) {
            null // not gzip, or cut short: decoding is in memory, so this is never the network
        }
    }

    companion object {
        /** How far back each run reads before the cursor, for rides listed late or sharing a time. */
        const val OVERLAP_MS = 5 * 60_000L

        /**
         * How long a live ride's missing track counts as "not uploaded yet". After that it is
         * skipped, so one lost file can't hold up every later download.
         */
        const val MISSING_TRACK_GIVE_UP_MS = 60 * 60_000L
    }
}
