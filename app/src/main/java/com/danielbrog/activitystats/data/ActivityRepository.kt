package com.danielbrog.activitystats.data

import androidx.room.withTransaction
import com.danielbrog.activitystats.export.GpxTrack
import java.util.UUID
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.stats.ActivityStats
import com.danielbrog.activitystats.stats.BestEfforts
import com.danielbrog.activitystats.stats.ElevationGain
import com.danielbrog.activitystats.stats.summarize
import com.danielbrog.activitystats.sync.CloudCopy
import com.danielbrog.activitystats.sync.DownloadStore
import com.danielbrog.activitystats.sync.SyncStore
import kotlinx.coroutines.flow.Flow

/** What happened to an imported track; both carry the id of the ride to open. */
sealed interface ImportResult {
    val id: String
    data class Imported(override val id: String) : ImportResult
    data class AlreadyImported(override val id: String) : ImportResult
}

/**
 * The only way into the activity database (build guide rule): screens and the tracking
 * service go through here, so cloud sync can slot in behind it later.
 *
 * [ownerId] gives the signed-in account's id, or null when signed out: new rides are filed
 * under it, so they belong to the account they were recorded in.
 *
 * [onChanged] is called after a change that should reach the cloud: a ride finished,
 * imported, renamed, deleted or restored. The app starts a sync from it.
 */
class ActivityRepository(
    private val db: AppDatabase,
    private val ownerId: () -> String? = { null },
    private val onChanged: () -> Unit = {},
) : SyncStore, DownloadStore {

    private val activities = db.activityDao()
    private val points = db.trackPointDao()
    private val efforts = db.bestEffortDao()

    /** The most recent finished activity, or null if none is saved yet. */
    val latestSaved: Flow<ActivityEntity?> = activities.latestFinished()

    /** Every finished activity, newest first, for the history list. */
    val finishedActivities: Flow<List<ActivityEntity>> = activities.allFinished()

    /** How many finished activities are saved on this phone. */
    val savedCount: Flow<Int> = activities.finishedCount()

    suspend fun startActivity(id: String, startedAtMillis: Long) {
        val owner = ownerId()
        activities.insertActivity(
            ActivityEntity(
                id = id,
                userId = owner,
                syncState = SyncState.forNewRide(owner),
                type = ActivityType.RIDE,
                startedAt = startedAtMillis,
                title = rideTitle(startedAtMillis),
                updatedAt = startedAtMillis,
            )
        )
    }

    /** Saves a batch of fixes (kept and dropped) and the stats so far, together. */
    suspend fun savePoints(id: String, fixes: List<GpsData>, stats: ActivityStats, nowMillis: Long) {
        db.withTransaction {
            if (fixes.isNotEmpty()) points.insertPoints(fixes.map { it.toTrackPoint(id) })
            activities.updateStats(
                id = id,
                distanceMeters = stats.distanceMeters,
                movingTimeMs = stats.movingMs,
                elapsedTimeMs = stats.elapsedMs,
                avgSpeedMps = stats.avgSpeedMps,
                maxSpeedMps = stats.maxSpeedMps,
                updatedAt = nowMillis,
            )
        }
    }

    /**
     * Writes the final stats, end time and climbing together, then the best efforts.
     *
     * Climbing is worked out first and saved in the same transaction as the end time: the
     * moment a ride has an end time it can be uploaded, and its climbing goes up with it.
     */
    suspend fun finishActivity(id: String, endedAtMillis: Long, stats: ActivityStats) {
        val saved = pointsFor(id)
        db.withTransaction {
            activities.finish(
                id = id,
                endedAt = endedAtMillis,
                distanceMeters = stats.distanceMeters,
                movingTimeMs = stats.movingMs,
                elapsedTimeMs = stats.elapsedMs,
                avgSpeedMps = stats.avgSpeedMps,
                maxSpeedMps = stats.maxSpeedMps,
            )
            activities.setElevationGain(id, ElevationGain.of(saved))
        }
        saveBestEfforts(id, saved)
        onChanged()
    }

    /**
     * Saves a track read from a GPX file as a finished activity, with its stats worked out by
     * the same engine used while recording, then its climbing and best efforts. A track that
     * starts in the same second as an existing activity isn't saved again.
     */
    suspend fun importTrack(track: GpxTrack, nowMillis: Long = System.currentTimeMillis()): ImportResult {
        val fixes = track.toFixes()
        val startedAt = fixes.first().timeMillis
        activities.idStartedInSameSecond(startedAt)?.let { return ImportResult.AlreadyImported(it) }

        val stats = summarize(startedAt, fixes)
        val id = UUID.randomUUID().toString()
        val owner = ownerId()
        db.withTransaction {
            activities.insertActivity(
                ActivityEntity(
                    id = id,
                    userId = owner,
                    syncState = SyncState.forNewRide(owner),
                    type = track.type,
                    startedAt = startedAt,
                    endedAt = fixes.last().timeMillis,
                    distanceMeters = stats.distanceMeters,
                    movingTimeMs = stats.movingMs,
                    elapsedTimeMs = stats.elapsedMs,
                    avgSpeedMps = stats.avgSpeedMps,
                    maxSpeedMps = stats.maxSpeedMps,
                    // Saved with the ride, so it can never be uploaded without its climbing.
                    elevationGainMeters = ElevationGain.of(fixes),
                    title = track.name?.let { RideTitle.clean(it) } ?: rideTitle(startedAt),
                    updatedAt = nowMillis,
                )
            )
            fixes.chunked(500).forEach { batch -> points.insertPoints(batch.map { it.toTrackPoint(id) }) }
        }
        saveBestEfforts(id, fixes)
        onChanged()
        return ImportResult.Imported(id)
    }

    /** Every best effort a ride reached, deleted rides included; screens filter those out. */
    val bestEfforts: Flow<List<BestEffortEntity>> = efforts.observeReached()

    /**
     * Works out and saves the stats that need a ride's whole track: its climbing and its best
     * efforts. Loads the points once for both.
     */
    suspend fun updateDerivedStats(id: String) {
        val saved = pointsFor(id)
        activities.setElevationGain(id, ElevationGain.of(saved))
        saveBestEfforts(id, saved)
    }

    private suspend fun saveBestEfforts(id: String, fixes: List<GpsData>) {
        val found = BestEfforts.find(fixes)
        efforts.replaceFor(id, found.map { (effort, ms) -> BestEffortEntity(id, effort.key, ms) })
    }

    /**
     * Fills in climbing and best efforts for finished rides missing either, such as rides
     * saved before they existed. Returns how many rides were updated.
     */
    suspend fun fillMissingDerivedStats(): Int {
        val missing = (activities.finishedWithoutElevationGain() + efforts.finishedWithoutEfforts()).distinct()
        missing.forEach { updateDerivedStats(it) }
        // Rides waiting for their climbing can upload now.
        if (missing.isNotEmpty()) onChanged()
        return missing.size
    }

    /**
     * Finishes activities left open by a crash, force-stop or dead battery, using their
     * saved points. Only touches activities started before [startedBefore], so a ride that
     * began after the app opened is never closed by mistake. Returns how many were recovered.
     */
    suspend fun recoverUnfinished(startedBefore: Long): Int {
        val open = activities.unfinishedStartedBefore(startedBefore)
        for (activity in open) {
            val saved = pointsFor(activity.id)
            val stats = summarize(activity.startedAt, saved)
            val endedAt = saved.maxOfOrNull { it.timeMillis } ?: activity.startedAt
            finishActivity(activity.id, endedAt, stats)
        }
        return open.size
    }

    /** Files every ride with no owner under [userId], on sign-in. Returns how many. */
    suspend fun claimUnowned(userId: String): Int = activities.claimUnowned(userId)

    override suspend fun getActivity(id: String): ActivityEntity? = activities.getActivity(id)

    fun observeActivity(id: String): Flow<ActivityEntity?> = activities.observeActivity(id)

    /** Renames an activity. [title] should already be cleaned with [RideTitle.clean]. */
    suspend fun rename(id: String, title: String, nowMillis: Long = System.currentTimeMillis()) {
        activities.rename(id, title, nowMillis)
        onChanged()
    }

    /** Soft-deletes an activity; [restore] brings it back. Its points are kept for now. */
    suspend fun delete(id: String, nowMillis: Long = System.currentTimeMillis()) {
        activities.softDelete(id, nowMillis)
        onChanged()
    }

    suspend fun restore(id: String, nowMillis: Long = System.currentTimeMillis()) {
        activities.restore(id, nowMillis)
        onChanged()
    }

    override suspend fun pointsFor(id: String): List<GpsData> = points.pointsFor(id).map { it.toGpsData() }

    // --- Cloud sync ---

    override suspend fun pendingUploads(userId: String): List<ActivityEntity> = activities.pendingUploads(userId)

    override suspend fun markTrackUploaded(id: String) = activities.markTrackUploaded(id)

    override suspend fun markFailed(id: String, sentUpdatedAt: Long) = activities.markFailed(id, sentUpdatedAt)

    /** Puts [userId]'s refused rides back in the queue, for Sync now. */
    suspend fun retryFailed(userId: String) = activities.retryFailed(userId)

    fun waitingCount(userId: String): Flow<Int> = activities.waitingCount(userId)

    fun failedCount(userId: String): Flow<Int> = activities.failedCount(userId)

    override suspend fun markSynced(id: String, sentUpdatedAt: Long) {
        activities.markSynced(id, sentUpdatedAt)
    }

    override suspend fun applyCloudCopy(id: String, cloud: CloudCopy): Boolean =
        activities.applyCloudCopy(id, cloud.title, cloud.deletedAt, cloud.updatedAt) == 1

    /**
     * Saves a ride downloaded from the cloud with its points, in one transaction so a half-saved
     * ride never shows. Climbing comes from the cloud's copy, or the points if it has none; best
     * efforts are worked out here, as for an import. Doesn't ask for a sync: it already is in sync.
     */
    override suspend fun saveDownloadedRide(ride: ActivityEntity, fixes: List<GpsData>) {
        val inserted = db.withTransaction {
            val row = activities.insertIfNew(ride.copy(elevationGainMeters = ride.elevationGainMeters ?: ElevationGain.of(fixes)))
            // Already here (it can't normally be): leave that ride and its points as they are.
            if (row == -1L) return@withTransaction false
            fixes.chunked(500).forEach { batch -> points.insertPoints(batch.map { it.toTrackPoint(ride.id) }) }
            true
        }
        if (inserted) saveBestEfforts(ride.id, fixes)
    }
}
