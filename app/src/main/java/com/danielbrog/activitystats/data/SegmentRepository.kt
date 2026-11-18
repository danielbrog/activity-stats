package com.danielbrog.activitystats.data

import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.location.RouteBounds
import com.danielbrog.activitystats.segments.Polyline
import com.danielbrog.activitystats.segments.SegmentEffort
import com.danielbrog.activitystats.segments.SegmentMatcher
import com.danielbrog.activitystats.segments.SegmentPath
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Road segments the rider makes, and the efforts their rides made on them. Matching uses each
 * ride's bounding box first, so only rides near a segment have their points loaded. Segments
 * are few and small, so they are all loaded and compared with a ride's box in Kotlin.
 *
 * [ownerId] gives the signed-in account, as for rides, so a new segment belongs to it.
 *
 * Matching is CPU work, so it runs on [Dispatchers.Default] whoever calls it. Recomputes run
 * one at a time: each reads, matches, then replaces rows, and two at once could each wipe an
 * effort the other had just written.
 */
class SegmentRepository(
    db: AppDatabase,
    private val ownerId: () -> String? = { null },
) {
    private val segments = db.segmentDao()
    private val activities = db.activityDao()
    private val points = db.trackPointDao()
    private val matching = Mutex()

    /** Every segment not deleted, by name. */
    val allSegments: Flow<List<SegmentEntity>> = segments.observeSegments()

    /** A segment's efforts from rides not deleted, fastest first. */
    fun effortsFor(segmentId: String): Flow<List<SegmentEffortWithRide>> = segments.observeEfforts(segmentId)

    /**
     * Makes a segment named [name] from part of a ride: its kept fixes from [startIndex] to
     * [endIndex], as the map draws them. Then finds the efforts every ride made on it. Returns
     * the new segment's id, or null if that part can't be a segment (see [SegmentPath.fromRide]).
     */
    suspend fun create(
        name: String,
        rideId: String,
        startIndex: Int,
        endIndex: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): String? = withContext(Dispatchers.Default) {
        val path = SegmentPath.fromRide(keptPointsFor(rideId), startIndex, endIndex) ?: return@withContext null
        val owner = ownerId()
        val segment = SegmentEntity(
            id = UUID.randomUUID().toString(),
            userId = owner,
            name = name,
            path = Polyline.encode(path.points),
            distanceMeters = path.distanceMeters,
            createdAt = nowMillis,
            updatedAt = nowMillis,
            syncState = SyncState.forNewRide(owner),
        )
        segments.insertSegment(segment)
        recomputeForSegment(segment)
        segment.id
    }

    /** Finds the efforts every ride near [segment] made on it, replacing what was there. */
    suspend fun recomputeForSegment(segment: SegmentEntity) = matching.withLock {
        withContext(Dispatchers.Default) { findForSegment(segment) }
    }

    private suspend fun findForSegment(segment: SegmentEntity) {
        val path = segment.toPath()
        val box = path.bounds.expandedBy(SegmentMatcher.END_RADIUS_METERS)
        val found = activities.ridesOverlapping(box.south, box.north, box.west, box.east).flatMap { rideId ->
            SegmentMatcher.find(path, keptPointsFor(rideId)).map { it.toEntity(segment.id, rideId) }
        }
        segments.replaceForSegment(segment.id, found)
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
