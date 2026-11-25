package com.danielbrog.activitystats.data

import android.util.Log
import androidx.room.withTransaction
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.location.RouteBounds
import com.danielbrog.activitystats.segments.Polyline
import com.danielbrog.activitystats.segments.SegmentEffort
import com.danielbrog.activitystats.segments.SegmentMatcher
import com.danielbrog.activitystats.segments.SegmentPath
import com.danielbrog.activitystats.segments.SegmentSelection
import com.danielbrog.activitystats.sync.CloudReview
import com.danielbrog.activitystats.sync.CloudSegment
import com.danielbrog.activitystats.sync.SegmentSyncStore
import com.danielbrog.activitystats.sync.mergeLineChange
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Road segments the rider makes, and the efforts their rides made on them. Matching uses each
 * ride's bounding box first, so only rides near a segment have their points loaded. Segments
 * are few and small, so they are all loaded and compared with a ride's box in Kotlin.
 *
 * [ownerId] gives the signed-in account, as for rides, so a new segment belongs to it.
 * [onChanged] is called after a change that should reach the cloud (a segment made, renamed,
 * deleted or restored); the app starts a sync from it. The repository is also the store
 * segment sync reads and writes ([SegmentSyncStore]).
 * [background] is where matching runs, for a new segment's efforts and a newly saved ride's:
 * a scope that outlives screens (the app's), so leaving a screen doesn't stop it.
 *
 * Matching is CPU work, so it runs on [Dispatchers.Default] whoever calls it. Recomputes run
 * one at a time: each reads, matches, then replaces rows, and two at once could each wipe an
 * effort the other had just written.
 */
class SegmentRepository(
    private val db: AppDatabase,
    private val background: CoroutineScope,
    private val ownerId: () -> String? = { null },
    private val onChanged: () -> Unit = {},
) : SegmentSyncStore {
    private val segments = db.segmentDao()
    private val activities = db.activityDao()
    private val points = db.trackPointDao()
    private val matching = Mutex()

    /** Every segment not deleted, by name. */
    val allSegments: Flow<List<SegmentEntity>> = segments.observeSegments()

    /** Every segment for the list, by name, with its best time and effort count. */
    val summaries: Flow<List<SegmentSummary>> = segments.observeSummaries()

    private val _finding = MutableStateFlow<Set<String>>(emptySet())
    /**
     * The ids of segments whose efforts are still being found, for "Finding efforts…": new ones,
     * and ones downloaded, brought back or given a new line by sync.
     */
    val finding: StateFlow<Set<String>> = _finding.asStateFlow()

    // Guards the two below. A segment can be matched twice at once (made, then given the
    // cloud's line), so each id counts its jobs and leaves [finding] when the last one ends.
    private val findingLock = Any()
    private val findingCounts = mutableMapOf<String, Int>()
    private val segmentJobs = mutableSetOf<Job>()

    /**
     * Saves a new name. False (and nothing saved) if it is blank after cleaning, the same as
     * now, or there is no such segment.
     */
    suspend fun rename(id: String, name: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val clean = SegmentName.clean(name) ?: return false
        val renamed = segments.rename(id, clean, nowMillis) > 0
        if (renamed) onChanged()
        return renamed
    }

    /** Soft-deletes a segment: it disappears everywhere, but [restore] brings it back as it was. */
    suspend fun delete(id: String, nowMillis: Long = System.currentTimeMillis()) {
        segments.softDelete(id, nowMillis)
        onChanged()
    }

    /**
     * Undoes [delete], then finds its efforts again: rides saved while it was deleted were
     * never matched against it, and sync may have changed its line meanwhile.
     */
    suspend fun restore(id: String, nowMillis: Long = System.currentTimeMillis()) {
        segments.restore(id, nowMillis)
        onChanged()
        matchSegmentInBackground(id)
    }

    /** One segment, or null when it is missing or deleted. */
    fun segment(id: String): Flow<SegmentEntity?> = segments.observeSegment(id)

    /** One ride's efforts on segments not deleted, in riding order; see [SegmentDao.observeEffortsForRide]. */
    fun effortsForRide(rideId: String): Flow<List<RideSegmentEffort>> = segments.observeEffortsForRide(rideId)

    /** A segment's efforts from rides not deleted, fastest first. */
    fun effortsFor(segmentId: String): Flow<List<SegmentEffortWithRide>> = segments.observeEfforts(segmentId)

    /**
     * Makes a segment named [name] from part of a ride: its kept fixes from [startIndex] to
     * [endIndex], as the map draws them. Saving is one quick write; the efforts every ride made
     * on it are then found in [background], and [CreatedSegment.matching] finishes when they
     * are saved. Returns null if the name is blank or that part can't be a segment: see
     * [SegmentPath.fromRide], and it must be from [SegmentSelection.MIN_LENGTH_METERS] to
     * [SegmentSelection.MAX_LENGTH_METERS] long.
     */
    suspend fun create(
        name: String,
        rideId: String,
        startIndex: Int,
        endIndex: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): CreatedSegment? = withContext(Dispatchers.Default) {
        val cleanName = SegmentName.clean(name) ?: return@withContext null
        val path = SegmentPath.fromRide(keptPointsFor(rideId), startIndex, endIndex)
        if (path == null || !SegmentSelection.isAllowedLength(path.distanceMeters)) return@withContext null
        val owner = ownerId()
        // A segment is for the sport it was ridden as: a run's segment only matches runs.
        val sport = activities.getActivity(rideId)?.type ?: ActivityType.RIDE
        val segment = SegmentEntity(
            id = UUID.randomUUID().toString(),
            userId = owner,
            name = cleanName,
            path = Polyline.encode(path.points),
            distanceMeters = path.distanceMeters,
            createdAt = nowMillis,
            updatedAt = nowMillis,
            syncState = SyncState.forNewRide(owner),
            sport = sport,
        )
        segments.insertSegment(segment)
        onChanged()
        CreatedSegment(segment.id, matchSegmentInBackground(segment.id))
    }

    /**
     * Finds every ride's efforts on segment [id] in [background], showing "Finding efforts…" while
     * it runs. For a segment just made, downloaded, or brought back. A failure is logged, not
     * thrown: the segment stays, with no efforts until the next recompute.
     */
    private fun matchSegmentInBackground(id: String): Job {
        synchronized(findingLock) {
            findingCounts[id] = (findingCounts[id] ?: 0) + 1
            _finding.value = findingCounts.keys.toSet()
        }
        val job = background.launch {
            try {
                recomputeForSegment(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SegmentRepository", "Finding efforts for a segment failed", e)
            }
        }
        synchronized(findingLock) { segmentJobs += job }
        // Runs however the job ends, even cancelled before it started.
        job.invokeOnCompletion {
            synchronized(findingLock) {
                segmentJobs -= job
                val left = (findingCounts[id] ?: 1) - 1
                if (left > 0) findingCounts[id] = left else findingCounts.remove(id)
                _finding.value = findingCounts.keys.toSet()
            }
        }
        return job
    }

    /**
     * Waits for every segment's efforts being found now. Sync calls it before it finishes, so
     * the app isn't left to be stopped halfway through a download's matching: nothing would
     * find those efforts again.
     */
    suspend fun awaitSegmentMatching() {
        synchronized(findingLock) { segmentJobs.toList() }.joinAll()
    }

    // --- SegmentSyncStore: what segment sync reads and writes ---

    override suspend fun pendingSegmentUploads(userId: String): List<SegmentEntity> = segments.pendingUploads(userId)

    override suspend fun markSegmentSynced(id: String, sentUpdatedAt: Long) {
        segments.markSynced(id, sentUpdatedAt)
    }

    override suspend fun markSegmentFailed(id: String, sentUpdatedAt: Long) = segments.markFailed(id, sentUpdatedAt)

    override suspend fun getSegment(id: String): SegmentEntity? = segments.getSegment(id)

    /** Takes the cloud's newer name and deleted state; a segment brought back gets its efforts again. */
    override suspend fun applyCloudSegment(cloud: CloudSegment): Boolean {
        val before = segments.getSegment(cloud.id)
        val taken = segments.applyCloudCopy(cloud.id, cloud.name, cloud.deletedAt, cloud.updatedAt) == 1
        if (taken && before?.deletedAt != null && cloud.deletedAt == null) {
            matchSegmentInBackground(cloud.id)
        }
        return taken
    }

    /**
     * The cloud's line, and the newer copy's name and deleted state ([mergeLineChange]), keeping
     * this phone's review fields; then matched again on the new line. Read and written in one
     * transaction, so an edit made meanwhile isn't overwritten.
     */
    override suspend fun replaceWithCloudSegment(cloud: CloudSegment): Boolean {
        val merged = db.withTransaction {
            segments.getSegment(cloud.id)?.let { local ->
                mergeLineChange(local, cloud).also { segments.replaceSegment(it) }
            }
        } ?: return false
        if (merged.deletedAt == null) matchSegmentInBackground(merged.id)
        return merged.syncState == SyncState.SYNCED
    }

    /** A segment new to this phone: saved (if it still is new) and matched against every ride. */
    override suspend fun saveDownloadedSegment(segment: SegmentEntity) {
        val inserted = segments.insertIfNew(segment) != -1L
        if (inserted && segment.deletedAt == null) matchSegmentInBackground(segment.id)
    }

    override suspend fun applyReview(review: CloudReview, status: SegmentStatus): Boolean =
        segments.applyReview(
            id = review.segmentId,
            status = status.name,
            submittedAt = review.submittedAt,
            decidedAt = review.decidedAt,
            reviewNote = review.reviewNote,
            verifiedSegmentId = review.verifiedSegmentId,
        ) > 0

    /**
     * Finds the efforts every ride near segment [id] made on it, replacing what was there.
     * Returns how many there are. The segment is read inside the lock, so a job that waited
     * behind others matches its current line, not the one it was started for. A segment that
     * is missing or deleted by then is left alone.
     */
    suspend fun recomputeForSegment(id: String): Int = matching.withLock {
        val segment = segments.getSegment(id)?.takeIf { it.deletedAt == null } ?: return@withLock 0
        withContext(Dispatchers.Default) { findForSegment(segment) }
    }

    private suspend fun findForSegment(segment: SegmentEntity): Int {
        val path = segment.toPathOrNull()
        if (path == null) {
            // A line that doesn't decode can't be matched; it has no efforts.
            Log.w("SegmentRepository", "Segment ${segment.id} has a line that can't be read")
            segments.replaceForSegment(segment.id, emptyList())
            return 0
        }
        val box = path.bounds.expandedBy(SegmentMatcher.END_RADIUS_METERS)
        val found = activities.ridesOverlapping(segment.sport, box.south, box.north, box.west, box.east).flatMap { rideId ->
            SegmentMatcher.find(path, keptPointsFor(rideId)).map { it.toEntity(segment.id, rideId) }
        }
        segments.replaceForSegment(segment.id, found)
        return found.size
    }

    /**
     * Finds [rideId]'s efforts on every segment in [background], for a ride just finished,
     * imported, downloaded or restored; see [recomputeForRide]. The job finishes when they are
     * saved. A failure is logged, not thrown: the ride is safe, it just has no efforts until
     * it is matched again.
     */
    fun matchRideInBackground(rideId: String): Job = background.launch {
        try {
            recomputeForRide(rideId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SegmentRepository", "Matching a ride against segments failed", e)
        }
    }

    /**
     * Finds the efforts one ride made on every segment near it, replacing what was there. For
     * when a ride is finished, imported, downloaded or restored. A deleted ride, or one with no
     * box yet, makes none.
     */
    suspend fun recomputeForRide(rideId: String) = matching.withLock {
        withContext(Dispatchers.Default) { findForRide(rideId) }
    }

    private suspend fun findForRide(rideId: String) {
        val ride = activities.getActivity(rideId)
        val bounds = ride?.boundsOrNull()
        if (ride == null || bounds == null || ride.deletedAt != null) {
            segments.replaceForRide(rideId, emptyList())
            return
        }
        val box = bounds.expandedBy(SegmentMatcher.END_RADIUS_METERS)
        // Only segments of this ride's sport: a run never counts on a ride segment. One whose
        // line can't be read is left out, so it can't stop the others matching.
        val nearby = segments.activeSegments()
            .filter { it.sport == ride.type }
            .mapNotNull { segment -> segment.toPathOrNull()?.let { segment to it } }
            .filter { (_, path) -> path.bounds.overlaps(box) }
        if (nearby.isEmpty()) {
            segments.replaceForRide(rideId, emptyList())
            return
        }
        val fixes = keptPointsFor(rideId)
        val found = nearby.flatMap { (segment, path) ->
            SegmentMatcher.find(path, fixes).map { it.toEntity(segment.id, rideId) }
        }
        segments.replaceForRide(rideId, found)
    }

    private suspend fun keptPointsFor(rideId: String): List<GpsData> = points.keptPointsFor(rideId).map { it.toGpsData() }
}

/** A segment just made, and the background work finding its efforts. */
class CreatedSegment(val id: String, val matching: Job)

/**
 * The segment's line and length, as the matcher takes it; null if the stored line can't be
 * one (it doesn't decode, or has fewer than two points). Only a bad upload or a server bug
 * could store such a line, but one must never stop matching or crash a screen.
 */
fun SegmentEntity.toPathOrNull(): SegmentPath? = Polyline.decodeOrNull(path)
    ?.takeIf { it.size >= 2 }
    ?.let { SegmentPath(it, distanceMeters) }

private fun ActivityEntity.boundsOrNull(): RouteBounds? {
    val south = minLat ?: return null
    val north = maxLat ?: return null
    val west = minLon ?: return null
    val east = maxLon ?: return null
    return RouteBounds(south, west, north, east)
}

private fun SegmentEffort.toEntity(segmentId: String, activityId: String) = SegmentEffortEntity(
    segmentId = segmentId,
    activityId = activityId,
    occurrence = occurrence,
    startedAt = startedAtMillis,
    elapsedMs = elapsedMs,
)
