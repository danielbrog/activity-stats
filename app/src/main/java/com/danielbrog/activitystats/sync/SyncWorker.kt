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
import kotlinx.serialization.SerializationException
import java.util.concurrent.TimeUnit

/**
 * Syncs rides in the background with WorkManager, which runs it once there is a network,
 * retries it with growing waits when it fails, and keeps it through the app closing. Uploads
 * this phone's waiting rides, then downloads rides changed in the cloud. Does nothing while
 * signed out: the rides wait, and signing in starts a sync.
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

        val api = HttpSyncApi(config.apiUrl, session.accessToken)
        val stillSignedIn = { auth.signedInUserId == session.userId }
        return try {
            // Up first, so this phone's changes are in the cloud before others come down.
            val up = Uploader(app.activityRepository, api).uploadPending(session.userId, stillSignedIn)
            if (up.synced > 0) Log.i(TAG, "Uploaded ${up.synced} rides")
            if (up.rejected.isNotEmpty()) Log.w(TAG, "Server refused rides ${up.rejected}")

            // A first restore can be long: stop well inside WorkManager's 10 minutes and queue
            // the rest, rather than be stopped and use up a retry.
            val deadline = System.currentTimeMillis() + DOWNLOAD_BUDGET_MS
            val down = Downloader(app.activityRepository, api, SyncCursorStore(app))
                .downloadChanges(session.userId, stillSignedIn, deadline)
            if (down.downloaded > 0 || down.updated > 0) {
                Log.i(TAG, "Downloaded ${down.downloaded} rides, updated ${down.updated}")
            }
            if (down.skipped.isNotEmpty()) Log.w(TAG, "Couldn't get the tracks of ${down.skipped}")
            if (down.more) Sync.request(app)
            Result.success()
        } catch (e: SignInRejectedException) {
            Log.i(TAG, "Sign-in token refused; a new one is fetched next try", e)
            auth.tokenRejected()
            retryOrGiveUp()
        } catch (e: IOException) {
            Log.i(TAG, "Sync interrupted", e)
            retryOrGiveUp()
        } catch (e: RejectedException) {
            // The listing itself was refused: retrying the same request won't help. The next
            // change, sign-in or app start tries again.
            Log.w(TAG, "Server refused the listing", e)
            Result.failure()
        } catch (e: SerializationException) {
            Log.w(TAG, "A reply from the server wasn't understood", e)
            Result.failure()
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

        /** How long one run spends downloading before it queues another run for the rest. */
        const val DOWNLOAD_BUDGET_MS = 7 * 60_000L
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
