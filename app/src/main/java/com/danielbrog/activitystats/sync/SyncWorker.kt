package com.danielbrog.activitystats.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.danielbrog.activitystats.ActivityStatsApp
import com.danielbrog.activitystats.account.AuthConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException

/**
 * Syncs rides and segments in the background with WorkManager, which runs it once there is a
 * network, retries it with growing waits when it fails, and keeps it through the app closing.
 * Uploads this phone's waiting rides, downloads rides changed in the cloud, then does the same
 * for segments. Does nothing while signed out: everything waits, and signing in starts a sync.
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
            up.limitResetsAt?.let { resetsAt ->
                Log.i(TAG, "Weekly ride limit reached; ${up.limited.size} rides wait for the reset")
                // Nothing else may sync before then, so book one for just after it. Booked now,
                // so a download that fails below can't lose it.
                Sync.requestAfter(app, resetsAt)
            }

            // A first restore can be long: stop well inside WorkManager's 10 minutes and queue
            // the rest, rather than be stopped and use up a retry.
            val deadline = System.currentTimeMillis() + DOWNLOAD_BUDGET_MS
            val cursorStore = SyncCursorStore(app)
            val down = Downloader(app.activityRepository, api, cursorStore)
                .downloadChanges(session.userId, stillSignedIn, deadline)
            if (down.downloaded > 0 || down.updated > 0) {
                Log.i(TAG, "Downloaded ${down.downloaded} rides, updated ${down.updated}")
            }
            if (down.skipped.isNotEmpty()) Log.w(TAG, "Couldn't get the tracks of ${down.skipped}")
            down.limitResetsAt?.let { resetsAt ->
                Log.i(TAG, "Daily download limit reached; the rest wait for the reset")
                // Replaces a weekly booking above, safely: the day always resets first, and the
                // sync then books the week again.
                Sync.requestAfter(app, resetsAt)
            }

            // Then segments, the same way, within the same time budget.
            val segmentsUp = SegmentUploader(app.segmentRepository, api).uploadPending(session.userId, stillSignedIn)
            if (segmentsUp.synced > 0) Log.i(TAG, "Uploaded ${segmentsUp.synced} segments")
            if (segmentsUp.rejected.isNotEmpty()) Log.w(TAG, "Server refused segments ${segmentsUp.rejected}")
            val segmentsDown = SegmentDownloader(app.segmentRepository, api, SegmentSyncCursorStore(app))
                .downloadChanges(session.userId, stillSignedIn, deadline)
            if (segmentsDown.downloaded > 0 || segmentsDown.updated > 0 || segmentsDown.reviews > 0) {
                Log.i(
                    TAG,
                    "Downloaded ${segmentsDown.downloaded} segments, updated ${segmentsDown.updated}, " +
                        "applied ${segmentsDown.reviews} reviews",
                )
            }
            // Downloaded segments find their efforts in the background: wait, so the app isn't
            // stopped halfway through (nothing would find them again).
            app.segmentRepository.awaitSegmentMatching()
            when {
                down.more || segmentsDown.more -> Sync.request(app)
                // Not done either, but waiting for the booked sync.
                down.limitResetsAt != null -> Unit
                // Only a run that got to the end counts, not one cut short by a sign-out.
                stillSignedIn() -> cursorStore.setLastSynced(session.userId, System.currentTimeMillis())
            }
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

/** The one way to start a sync, and to see whether one is running. */
object Sync {

    private const val WORK_NAME = "sync"

    /** True while a sync is running. Updates itself. */
    fun running(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

    /**
     * Starts a sync at [atMillis] (or as soon as there is a network after it), for rides held
     * back by a usage limit (weekly rides, daily downloads) until it resets. Under its own name,
     * so a sync booked for days ahead never holds up the normal ones; when it comes, it only asks
     * for a normal sync. Booking again replaces the earlier booking.
     */
    fun requestAfter(context: Context, atMillis: Long) {
        // A minute after the reset, so the server's clock has surely passed it.
        val delayMs = (atMillis - System.currentTimeMillis() + 60_000L).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<SyncLaterWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(LATER_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private const val LATER_WORK_NAME = "sync-after-limit"

    /**
     * Queues a sync for as soon as there is a network. Only one runs at a time. By default
     * ([ExistingWorkPolicy.APPEND_OR_REPLACE]) one asked for while another runs follows it, so a
     * change made mid-sync isn't missed. Opening the app uses [ExistingWorkPolicy.KEEP], which
     * adds nothing if a sync is already queued; Sync now uses [ExistingWorkPolicy.REPLACE], which
     * starts at once instead of waiting behind a retry that is backing off.
     */
    fun request(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }
}
