package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.data.SegmentEntity
import com.danielbrog.activitystats.data.SegmentStatus
import com.danielbrog.activitystats.data.SyncState
import java.io.IOException
import kotlinx.serialization.SerializationException

/** What segment sync needs from the phone's database. An interface, so tests can use a fake. */
interface SegmentSyncStore {
    /** Your segments waiting to upload, oldest edit first. */
    suspend fun pendingSegmentUploads(userId: String): List<SegmentEntity>

    /** Marks it synced, unless it was edited after [sentUpdatedAt] (then it goes up again). */
    suspend fun markSegmentSynced(id: String, sentUpdatedAt: Long)

    /** Marks it failed, unless it was edited after [sentUpdatedAt]. */
    suspend fun markSegmentFailed(id: String, sentUpdatedAt: Long)

    suspend fun getSegment(id: String): SegmentEntity?

    /** Takes the cloud's name and deleted state if it is newer than this phone's. True if taken. */
    suspend fun applyCloudSegment(cloud: CloudSegment): Boolean

    /**
     * The two disagreed about the line: takes the cloud's line, and the newer of the two
     * copies' name and deleted state (see [mergeLineChange]). True if the phone now matches
     * the cloud; false if it still has a newer edit to upload, or no such segment.
     */
    suspend fun replaceWithCloudSegment(cloud: CloudSegment): Boolean

    /** Saves a segment new to this phone (if it still is) and finds its efforts. */
    suspend fun saveDownloadedSegment(segment: SegmentEntity)

    /**
     * Applies the server's review of a segment: the server always wins, and nothing else about
     * the segment changes. False if that segment isn't on this phone.
     */
    suspend fun applyReview(review: CloudReview, status: SegmentStatus): Boolean
}

/** What to do with one segment from the cloud, as [decide] does for rides. Plain Kotlin. */
fun decideSegment(local: SegmentEntity?, cloud: CloudSegment): Decision = when {
    // New to this phone: saved even if deleted, so its review has somewhere to go and a
    // later restore finds it (with its efforts found then).
    local == null -> Decision.Download
    cloud.updatedAt > local.updatedAt -> Decision.TakeCloud
    else -> Decision.Keep
}

/**
 * The phone's segment after the server said the cloud's line differs. The line (and what comes
 * with it: length, sport, when it was made) is the cloud's, since the cloud's copy is the one
 * kept. The rider's edits, the name and the deleted state, come from whichever copy is newer:
 * if the phone's is, it stays waiting and goes up again, now with the cloud's line. Plain Kotlin.
 */
fun mergeLineChange(local: SegmentEntity, cloud: CloudSegment): SegmentEntity {
    val localNewer = local.updatedAt > cloud.updatedAt
    return local.copy(
        path = cloud.path,
        distanceMeters = cloud.distanceMeters,
        sport = cloud.sport,
        createdAt = cloud.createdAt,
        name = if (localNewer) local.name else cloud.name,
        deletedAt = if (localNewer) local.deletedAt else cloud.deletedAt,
        updatedAt = maxOf(local.updatedAt, cloud.updatedAt),
        syncState = if (localNewer) SyncState.PENDING else SyncState.SYNCED,
    )
}

/**
 * Uploads your waiting segments, as [Uploader] does rides. Plain Kotlin, unit-tested against a
 * fake server.
 */
class SegmentUploader(private val store: SegmentSyncStore, private val api: SegmentSyncApi) {

    /** Segments now in the cloud, and the ids not sent this time. */
    data class Report(
        val synced: Int,
        val rejected: List<String>,
        /** Segments left waiting because the day's edits are used up; they go up after the reset. */
        val limited: List<String> = emptyList(),
        /** When the server counts edits again (the next 00:00 UTC), if the limit was reached; else null. */
        val limitResetsAt: Long? = null,
    )

    /**
     * Uploads every waiting segment of [userId]. A segment the server refuses (a 4xx other than
     * 404) is marked failed; anything that looks like trouble on the way leaves it waiting.
     * Stops early if [stillSignedIn] turns false. Throws [IOException] on network trouble,
     * keeping what was done so far.
     */
    suspend fun uploadPending(userId: String, stillSignedIn: () -> Boolean = { true }): Report {
        var synced = 0
        val rejected = mutableListOf<String>()
        val limited = mutableListOf<String>()
        var limitResetsAt: Long? = null

        suspend fun notSent(segment: SegmentEntity, code: Int?) {
            if (code != null && refusesTheUpload(code)) store.markSegmentFailed(segment.id, segment.updatedAt)
            rejected += segment.id
        }

        for (segment in store.pendingSegmentUploads(userId)) {
            if (!stillSignedIn()) break
            // Every segment save is an edit: once the day's are used up, the rest wait too.
            if (limitResetsAt != null) {
                limited += segment.id
                continue
            }
            try {
                when (val result = api.putSegment(segment.id, SegmentUpload.from(segment))) {
                    SegmentPutResult.Applied -> {
                        store.markSegmentSynced(segment.id, segment.updatedAt)
                        synced++
                    }
                    // If the phone's copy changed meanwhile, it is newer still and goes up next time.
                    is SegmentPutResult.Newer -> if (store.applyCloudSegment(result.cloud)) synced++
                    is SegmentPutResult.LineChanged -> if (store.replaceWithCloudSegment(result.cloud)) synced++
                    is SegmentPutResult.Rejected -> notSent(segment, result.code)
                }
            } catch (e: EditLimitReachedException) {
                // Not refused: it stays waiting, and the first sync after the reset sends it.
                limited += segment.id
                limitResetsAt = e.resetsAt
            } catch (e: RejectedException) {
                notSent(segment, e.code)
            } catch (e: SerializationException) {
                notSent(segment, code = null) // a reply that isn't the backend's: just wait
            }
        }
        return Report(synced, rejected, limited, limitResetsAt)
    }
}

/**
 * Brings segments and their reviews changed in the cloud onto this phone, as [Downloader] does
 * rides: oldest first, saving the cursor as it goes, and reading back [Downloader.OVERLAP_MS]
 * before it. Plain Kotlin, unit-tested against a fake server.
 *
 * A review can come before its segment in the listing (a segment renamed after its review is
 * saved later), so on a fresh phone its segment may not be here yet. Those reviews are held and
 * tried again at the end of the run, and the saved cursor stays just before the oldest one held,
 * so a run stopped early reads it again next time. Any still without a segment at the end are
 * dropped: their segment isn't in the cloud (deleted ones are saved too), so they have nothing
 * to apply to.
 */
class SegmentDownloader(
    private val store: SegmentSyncStore,
    private val api: SegmentSyncApi,
    private val cursor: SyncCursor,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Segments saved new, ones updated from the cloud, reviews applied, and whether it stopped
     * for time with more still to fetch.
     */
    data class Report(val downloaded: Int, val updated: Int, val reviews: Int, val more: Boolean = false)

    /**
     * Downloads everything changed since the last run for [userId]. Stops early if
     * [stillSignedIn] turns false, or at [deadline] (with [Report.more] set). Throws
     * [IOException] on network trouble, keeping the progress made so far.
     */
    suspend fun downloadChanges(
        userId: String,
        stillSignedIn: () -> Boolean = { true },
        deadline: Long = Long.MAX_VALUE,
    ): Report {
        var downloaded = 0
        var updated = 0
        var reviews = 0
        val waiting = mutableListOf<Pair<CloudReview, SegmentStatus>>()
        val saved = cursor.get(userId)
        var high = saved
        var written = saved
        val since = (high - Downloader.OVERLAP_MS).coerceAtLeast(0L)

        /** Saves the cursor as far as is safe: never past a review still waiting for its segment. */
        suspend fun saveCursor(final: Boolean = false) {
            val held = if (final) null else waiting.minOfOrNull { it.first.syncedAt - 1 }
            val safe = if (held == null) high else minOf(high, held)
            if (safe > written) {
                cursor.set(userId, safe)
                written = safe
            }
        }
        var page: String? = null
        do {
            val listing = api.listSegments(since, page)
            for (item in listing.items) {
                if (!stillSignedIn()) return Report(downloaded, updated, reviews)
                if (now() >= deadline) {
                    // Apply what can be; anything still waiting is read again next run.
                    val iterator = waiting.iterator()
                    while (iterator.hasNext()) {
                        val (review, status) = iterator.next()
                        if (store.applyReview(review, status)) {
                            reviews++
                            iterator.remove()
                        }
                    }
                    saveCursor()
                    return Report(downloaded, updated, reviews, more = true)
                }
                when (item) {
                    is CloudSegment -> when (decideSegment(store.getSegment(item.id), item)) {
                        Decision.Download -> {
                            store.saveDownloadedSegment(item.toEntity(userId))
                            downloaded++
                        }
                        Decision.TakeCloud -> if (store.applyCloudSegment(item)) updated++
                        Decision.Keep, Decision.Skip -> Unit
                    }
                    is CloudReview -> {
                        // A status this version doesn't know: keep what the phone has.
                        val status = SegmentStatus.fromServer(item.status)
                        if (status != null) {
                            if (store.applyReview(item, status)) reviews++ else waiting += item to status
                        }
                    }
                }
                // Never move back: the overlap re-reads items older than the saved cursor.
                if (item.syncedAt > high) high = item.syncedAt
                saveCursor()
            }
            page = listing.nextCursor
        } while (page != null)
        for ((review, status) in waiting) if (store.applyReview(review, status)) reviews++
        saveCursor(final = true)
        return Report(downloaded, updated, reviews)
    }
}
