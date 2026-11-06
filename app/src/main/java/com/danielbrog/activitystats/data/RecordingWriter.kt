package com.danielbrog.activitystats.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs database writes for a recording one at a time, in the order they were queued
 * (start, then point batches, then finish). It lives in the app-wide scope, so writes
 * queued just before the service stops still complete.
 */
class RecordingWriter(private val repository: ActivityRepository, scope: CoroutineScope) {

    private val queue = Channel<suspend ActivityRepository.() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in queue) {
                try {
                    repository.write()
                } catch (e: Exception) {
                    // Keep recording even if one write fails; recovery fills gaps later.
                    Log.e("RecordingWriter", "Database write failed", e)
                }
            }
        }
    }

    fun enqueue(write: suspend ActivityRepository.() -> Unit) {
        queue.trySend(write)
    }
}
