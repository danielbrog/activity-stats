package com.danielbrog.activitystats.sync

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusTest {

    private val zone = ZoneOffset.UTC
    private fun at(day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(2026, 11, day, hour, minute).toInstant(zone).toEpochMilli()

    private val now = at(13, 15, 0)

    private fun text(status: SyncStatus, use24Hour: Boolean = true) = status.text(now, use24Hour, zone)

    @Test
    fun aRunningSyncSaysSo() {
        assertEquals("Syncing…", text(SyncStatus(running = true, waiting = 3, failed = 1)))
    }

    @Test
    fun beforeTheFirstSync() {
        assertEquals("Not synced yet", text(SyncStatus()))
    }

    @Test
    fun allSyncedTodayShowsTheTime() {
        val status = SyncStatus(lastSyncedAt = at(13, 10, 42))
        assertEquals("All rides synced · last synced 10:42", text(status))
        assertEquals("All rides synced · last synced 10:42 AM", text(status, use24Hour = false))
    }

    @Test
    fun allSyncedOnAnotherDayShowsTheDate() {
        assertEquals(
            "All rides synced · last synced 12 Nov, 21:05",
            text(SyncStatus(lastSyncedAt = at(12, 21, 5))),
        )
    }

    @Test
    fun waitingChangesAreCounted() {
        // "Changes", since a deletion or rename waiting to go up counts too.
        assertEquals("1 change waiting to sync", text(SyncStatus(waiting = 1, lastSyncedAt = at(13, 9, 0))))
        assertEquals("3 changes waiting to sync", text(SyncStatus(waiting = 3)))
    }

    @Test
    fun refusedRidesComeBeforeWaitingOnes() {
        assertEquals("1 ride couldn't sync. Sync now tries again.", text(SyncStatus(waiting = 4, failed = 1)))
        assertEquals("2 rides couldn't sync. Sync now tries again.", text(SyncStatus(failed = 2)))
    }
}
