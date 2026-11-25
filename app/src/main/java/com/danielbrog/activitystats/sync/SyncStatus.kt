package com.danielbrog.activitystats.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How syncing stands, for the Account screen: whether a sync is running, how many changes are
 * waiting to upload (new rides, edits and deletions), how many rides were refused, and when the
 * last complete sync finished (null before the first on this phone).
 */
data class SyncStatus(
    val running: Boolean = false,
    val waiting: Int = 0,
    val failed: Int = 0,
    val lastSyncedAt: Long? = null,
) {
    /**
     * The status line. What needs attention comes first: a sync running, then refused rides,
     * then waiting ones. Plain Kotlin, unit-tested.
     */
    fun text(nowMillis: Long, use24Hour: Boolean, zone: ZoneId = ZoneId.systemDefault()): String = when {
        running -> "Syncing…"
        failed > 0 -> "${count(failed, "ride")} couldn't sync. Sync now tries again."
        waiting > 0 -> "${count(waiting, "change")} waiting to sync"
        lastSyncedAt == null -> "Not synced yet"
        else -> "All rides synced · last synced ${whenSynced(lastSyncedAt, nowMillis, use24Hour, zone)}"
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    /** "10:42" (or "10:42 AM") today; "12 Nov, 10:42" on another day. */
    private fun whenSynced(millis: Long, nowMillis: Long, use24Hour: Boolean, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(millis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val clock = DateTimeFormatter.ofPattern(if (use24Hour) "H:mm" else "h:mm a", Locale.US).format(time)
        return if (time.toLocalDate() ==
            today
        ) {
            clock
        } else {
            "${DateTimeFormatter.ofPattern("d MMM", Locale.US).format(time)}, $clock"
        }
    }
}
