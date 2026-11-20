package com.danielbrog.activitystats.data

import android.util.Log
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.location.RouteBounds
import com.danielbrog.activitystats.segments.Polyline
import com.danielbrog.activitystats.segments.SegmentEffort
import com.danielbrog.activitystats.segments.SegmentMatcher
import com.danielbrog.activitystats.segments.SegmentPath
import com.danielbrog.activitystats.segments.SegmentSelection
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * [background] is where a new segment's efforts are found: a scope that outlives screens (the
 * app's), so leaving the screen doesn't stop it.
 *
 * Matching is CPU work, so it runs on [Dispatchers.Default] whoever calls it. Recomputes run
 * one at a time: each reads, matches, then replaces rows, and two at once could each wipe an
 * effort the other had just written.
 */
class SegmentRepository(
    db: AppDatabase,
    private val background: CoroutineScope,
    private val ownerId: () -> String? = { null },
) {
    private val segments = db.segmentDao()
    private val activities = db.activityDao()
    private val points = db.trackPointDao()
    private val matching = Mutex()

    /** Every segment not deleted, by name. */
    val allSegments: Flow<List<SegmentEntity>> = segments.observeSegments()

    /** Every segment for the list, by name, with its best time and effort count. */
    val summaries: Flow<List<SegmentSummary>> = segments.observeSummaries()

    private val _finding = MutableStateFlow<Set<String>>(emptySet())
    /** The ids of new segments whose efforts are still being found, for "Finding efforts…". */
    val finding: StateFlow<Set<String>> = _finding.asStateFlow()

    /**
     * Saves a new name. False (and nothing saved) if it is blank after cleaning, the same as
     * now, or there is no such segment.
     */
    suspend fun rename(id: String, name: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val clean = SegmentName.clean(name) ?: return false
        return segments.rename(id, clean, nowMillis) > 0
    }

    /** Soft-deletes a segment: it disappears everywhere, but [restore] brings it back as it was. */
    suspend fun delete(id: String, nowMillis: Long = System.currentTimeMillis()) = segments.softDelete(id, nowMillis)

    /** Undoes [delete]. */
    suspend fun restore(id: String, nowMillis: Long = System.currentTimeMillis()) = segments.restore(id, nowMillis)

    /** One segment, or null when it is missing or deleted. */
    fun segment(id: String): Flow<SegmentEntity?> = segments.observeSegment(id)

    /** A segment's efforts from rides not deleted, fastest first. */
    fun effortsFor(segmentId: String): Flow<List<SegmentEffortWithRide>> = segments.observeEfforts(segmentId)

    /**
     * Makes a segment named [name] from part of a ride: its kept fixes from [startIndex] to
     * [endIndex], as the map draws them. Saving is one quick write; the efforts every ride made
     * on it are then found in [background], and [CreatedSegment.matching] finishes when they
     * are saved. Returns null if the name is blank or that part can't be a segment: see
     * [SegmentPath.fromRide], and it must be at least [SegmentSelection.MIN_LENGTH_METERS].
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
        if (path == null || path.distanceMeters < SegmentSelection.MIN_LENGTH_METERS) return@withContext null
        val owner = ownerId()
        val segment = SegmentEntity(
            id = UUID.randomUUID().toString(),
            userId = owner,
            name = cleanName,
            path = Polyline.encode(path.points),
            distanceMeters = path.distanceMeters,
            createdAt = nowMillis,
            updatedAt = nowMillis,
            syncState = SyncState.forNewRide(owner),
        )
        segments.insertSegment(segment)
        _finding.update { it + segment.id }
        val job = background.launch {
            try {
                recomputeForSegment(segment)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A database or decoding error: the segment stays, with no efforts until the
                // next recompute. Not worth crashing the app for.
                Log.e("SegmentRepository", "Finding efforts for a new segment failed", e)
            } finally {
                _finding.update { it - segment.id }
            }
        }
        CreatedSegment(segment.id, job)
    }

    /**
     * Finds the efforts every ride near [segment] made on it, replacing what was there.
     * Returns how many there are.
     */
    suspend fun recomputeForSegment(segment: SegmentEntity): Int = matching.withLock {
        withContext(Dispatchers.Default) { findForSegment(segment) }
    }

    private suspend fun findForSegment(segment: SegmentEntity): Int {
        val path = segment.toPath()
        val box = path.bounds.expandedBy(SegmentMatcher.END_RADIUS_METERS)
        val found = activities.ridesOverlapping(box.south, box.north, box.west, box.east).flatMap { rideId ->
            SegmentMatcher.find(path, keptPointsFor(rideId)).map { it.toEntity(segment.id, rideId) }
        }
        segments.replaceForSegment(segment.id, found)
        return found.size
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
        val nearby = segments.activeSegments()
            .map { it to it.toPath() }
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

/** The segment's line and length, as the matcher takes it. */
fun SegmentEntity.toPath(): SegmentPath = SegmentPath(Polyline.decode(path), distanceMeters)

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
