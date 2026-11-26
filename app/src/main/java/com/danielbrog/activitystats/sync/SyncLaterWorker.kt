package com.danielbrog.activitystats.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Booked by [Sync.requestAfter] for when a usage limit resets. It doesn't sync itself:
 * it asks for a normal sync, so two syncs never run at once.
 */
class SyncLaterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Sync.request(applicationContext)
        return Result.success()
    }
}
