package com.danielbrog.activitystats.data

import androidx.room.withTransaction
import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.stats.ActivityStats
import com.danielbrog.activitystats.stats.BestEfforts
import com.danielbrog.activitystats.stats.summarize
import kotlinx.coroutines.flow.Flow

/**
 * The only way into the activity database (build guide rule): screens and the tracking
 * service go through here, so cloud sync can slot in behind it later.
 */
class ActivityRepository(private val db: AppDatabase) {

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
        activities.insertActivity(
            ActivityEntity(
                id = id,
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

    /** Writes the final stats and end time, then works out the ride's best efforts. */
    suspend fun finishActivity(id: String, endedAtMillis: Long, stats: ActivityStats) {
        activities.finish(
            id = id,
            endedAt = endedAtMillis,
            distanceMeters = stats.distanceMeters,
            movingTimeMs = stats.movingMs,
            elapsedTimeMs = stats.elapsedMs,
            avgSpeedMps = stats.avgSpeedMps,
            maxSpeedMps = stats.maxSpeedMps,
        )
        updateBestEfforts(id)
    }

    /** Every best effort a ride reached, deleted rides included; screens filter those out. */
    val bestEfforts: Flow<List<BestEffortEntity>> = efforts.observeReached()

    /** Works out and saves one ride's best efforts from its saved points. */
    suspend fun updateBestEfforts(id: String) {
        val found = BestEfforts.find(pointsFor(id))
        efforts.replaceFor(id, found.map { (effort, ms) -> BestEffortEntity(id, effort.key, ms) })
    }

    /** Works out best efforts for finished rides that don't have them yet. Returns how many. */
    suspend fun fillMissingBestEfforts(): Int {
        val missing = efforts.finishedWithoutEfforts()
        missing.forEach { updateBestEfforts(it) }
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

    suspend fun getActivity(id: String): ActivityEntity? = activities.getActivity(id)

    fun observeActivity(id: String): Flow<ActivityEntity?> = activities.observeActivity(id)

    /** Renames an activity. [title] should already be cleaned with [RideTitle.clean]. */
    suspend fun rename(id: String, title: String, nowMillis: Long = System.currentTimeMillis()) {
        activities.rename(id, title, nowMillis)
    }

    /** Soft-deletes an activity; [restore] brings it back. Its points are kept for now. */
    suspend fun delete(id: String, nowMillis: Long = System.currentTimeMillis()) {
        activities.softDelete(id, nowMillis)
    }

    suspend fun restore(id: String, nowMillis: Long = System.currentTimeMillis()) {
        activities.restore(id, nowMillis)
    }

    suspend fun pointsFor(id: String): List<GpsData> = points.pointsFor(id).map { it.toGpsData() }
}
