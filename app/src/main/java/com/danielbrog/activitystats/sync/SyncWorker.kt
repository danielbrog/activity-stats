package com.danielbrog.activitystats.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.danielbrog.activitystats.ActivityStatsApp
import com.danielbrog.activitystats.account.AuthConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads waiting rides in the background with WorkManager, which runs it once there is a
 * network, retries it with growing waits when it fails, and keeps it through the app closing.
 * Does nothing while signed out: the rides wait, and signing in starts a sync.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ActivityStatsApp
        val config = AuthConfig.current ?: return Result.success()
        val auth = app.authRepository
        // In a fresh process the saved sign-in may still be loading.
        auth.awaitSignedInUserId() ?: return Result.success()
        // The account and its token, read together, so rides only ever go up under their owner.
        val session = try {
            auth.freshSession()
        } catch (e: IOException) {
            Log.i(TAG, "Couldn't refresh the sign-in", e)
            return retryOrGiveUp()
        } ?: return Result.success() // the sign-in expired; rides wait for the next one

        return try {
            val report = Uploader(app.activityRepository, HttpSyncApi(config.apiUrl, session.accessToken))
                .uploadPending(session.userId, stillSignedIn = { auth.signedInUserId == session.userId })
            if (report.synced > 0) Log.i(TAG, "Synced ${report.synced} rides")
            if (report.rejected.isNotEmpty()) Log.w(TAG, "Server refused rides ${report.rejected}")
            Result.success()
        } catch (e: SignInRejectedException) {
            Log.i(TAG, "Sign-in token refused; a new one is fetched next try", e)
            auth.tokenRejected()
            retryOrGiveUp()
        } catch (e: IOException) {
            Log.i(TAG, "Sync interrupted", e)
            retryOrGiveUp()
        }
    }

    /**
     * Retries with growing waits, but not forever. After [MAX_ATTEMPTS] it gives up; the rides
     * stay waiting, and the next change, sign-in or app start queues a fresh sync.
     */
    private fun retryOrGiveUp(): Result = if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()

    private companion object {
        const val TAG = "SyncWorker"

        /** About two hours of retries with the 30-second, doubling backoff. */
        const val MAX_ATTEMPTS = 9
    }
}

/** The one way to start a sync. */
object Sync {

    private const val WORK_NAME = "sync"

    /**
     * Queues a sync for as soon as there is a network. Only one runs at a time; one asked for
     * while another runs follows it, so a change made mid-sync isn't missed.
     */
    fun request(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
