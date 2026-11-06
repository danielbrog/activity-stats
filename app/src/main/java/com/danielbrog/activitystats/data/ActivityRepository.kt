package com.danielbrog.activitystats.data

import com.danielbrog.activitystats.location.GpsData
import com.danielbrog.activitystats.stats.ActivityStats
import com.danielbrog.activitystats.stats.summarize
import kotlinx.coroutines.flow.Flow

/**
 * The only way into the activity database (build guide rule): screens and the tracking
 * service go through here, so cloud sync can slot in behind it later.
 */
class ActivityRepository(private val dao: ActivityDao) {

    /** The most recent finished activity, or null if none is saved yet. */
    val latestSaved: Flow<ActivityEntity?> = dao.latestFinished()

    /** How many finished activities are saved on this phone. */
    val savedCount: Flow<Int> = dao.finishedCount()

    suspend fun startActivity(id: String, startedAtMillis: Long) {
        dao.insertActivity(
            ActivityEntity(
                id = id,
                type = ActivityType.RIDE,
                startedAt = startedAtMillis,
                title = rideTitle(startedAtMillis),
                updatedAt = startedAtMillis,
            )
        )
    }

    /** Saves a batch of fixes (kept and dropped) and the stats so far. */
    suspend fun savePoints(id: String, fixes: List<GpsData>, stats: ActivityStats, nowMillis: Long) {
        dao.savePointsAndStats(
            points = fixes.map { it.toTrackPoint(id) },
            id = id,
            distanceMeters = stats.distanceMeters,
            movingTimeMs = stats.movingMs,
            elapsedTimeMs = stats.elapsedMs,
            avgSpeedMps = stats.avgSpeedMps,
            maxSpeedMps = stats.maxSpeedMps,
            updatedAt = nowMillis,
        )
    }

    suspend fun finishActivity(id: String, endedAtMillis: Long, stats: ActivityStats) {
        dao.finish(
            id = id,
            endedAt = endedAtMillis,
            distanceMeters = stats.distanceMeters,
            movingTimeMs = stats.movingMs,
            elapsedTimeMs = stats.elapsedMs,
            avgSpeedMps = stats.avgSpeedMps,
            maxSpeedMps = stats.maxSpeedMps,
        )
    }

    /**
     * Finishes activities left open by a crash, force-stop or dead battery, using their
     * saved points. Only touches activities started before [startedBefore], so a ride that
     * began after the app opened is never closed by mistake. Returns how many were recovered.
     */
    suspend fun recoverUnfinished(startedBefore: Long): Int {
        val open = dao.unfinishedStartedBefore(startedBefore)
        for (activity in open) {
            val points = dao.pointsFor(activity.id).map { it.toGpsData() }
            val stats = summarize(activity.startedAt, points)
            val endedAt = points.maxOfOrNull { it.timeMillis } ?: activity.startedAt
            finishActivity(activity.id, endedAt, stats)
        }
        return open.size
    }

    suspend fun getActivity(id: String): ActivityEntity? = dao.getActivity(id)

    suspend fun pointsFor(id: String): List<GpsData> = dao.pointsFor(id).map { it.toGpsData() }
}
