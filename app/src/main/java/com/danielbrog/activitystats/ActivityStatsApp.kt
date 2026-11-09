package com.danielbrog.activitystats

import android.app.Application
import android.util.Log
import com.danielbrog.activitystats.account.AuthConfig
import com.danielbrog.activitystats.account.AuthRepository
import com.danielbrog.activitystats.account.AuthStateStore
import com.danielbrog.activitystats.data.ActivityRepository
import com.danielbrog.activitystats.data.AppDatabase
import com.danielbrog.activitystats.data.RecordingWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Creates the database and repository once for the whole app. */
class ActivityStatsApp : Application() {

    /** Lives as long as the app process, for work that must outlast a screen or service. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val activityRepository: ActivityRepository by lazy {
        ActivityRepository(database, ownerId = { authRepository.signedInUserId })
    }
    val recordingWriter: RecordingWriter by lazy { RecordingWriter(activityRepository, appScope) }
    val authRepository: AuthRepository by lazy {
        AuthRepository(this, AuthConfig.current, AuthStateStore(this), { activityRepository }, appScope)
    }

    override fun onCreate() {
        super.onCreate()
        // Start reading the saved sign-in now, so rides recorded from here on get their owner.
        authRepository
        // A fresh process means no recording is running yet, so any open activity from
        // before now was cut off by a crash, force-stop or dead battery. Finish it.
        val processStartedAt = System.currentTimeMillis()
        appScope.launch {
            try {
                val recovered = activityRepository.recoverUnfinished(startedBefore = processStartedAt)
                if (recovered > 0) Log.i("ActivityStatsApp", "Recovered $recovered unfinished activities")
            } catch (e: Exception) {
                Log.e("ActivityStatsApp", "Recovery failed", e)
            }
            // Rides saved before climbing and best efforts existed (or whose first try failed)
            // get them now.
            try {
                val filled = activityRepository.fillMissingDerivedStats()
                if (filled > 0) Log.i("ActivityStatsApp", "Worked out climbing and best efforts for $filled rides")
            } catch (e: Exception) {
                Log.e("ActivityStatsApp", "Climbing and best efforts backfill failed", e)
            }
        }
    }
}
