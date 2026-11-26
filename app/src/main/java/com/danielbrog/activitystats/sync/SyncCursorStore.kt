package com.danielbrog.activitystats.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Where the download picks up from: the `syncedAt` of the last cloud ride handled, for one
 * account. An interface, so tests can use a fake. ([SyncCursorStore] also keeps when the last
 * complete sync finished, for the Account screen.)
 */
interface SyncCursor {
    /** The saved cursor for [userId], or 0 (everything) if none is saved for that account. */
    suspend fun get(userId: String): Long

    suspend fun set(userId: String, syncedAt: Long)
}

private val Context.syncStore: DataStore<Preferences> by preferencesDataStore(name = "sync")

/**
 * How this app fetches tracks. Raising it reads the ride listing again from 0 once.
 * 2: through the API, counted per day, instead of a link to S3. An app from before reads the
 * new reply as a damaged track and skips that ride, so rides it skipped between the backend
 * update and this app's install are fetched now.
 */
internal const val TRACK_DOWNLOAD_VERSION = 2

/**
 * [SyncCursor] in DataStore. The cursor is saved with the account it belongs to, so signing
 * in as someone else starts from 0 and gets all of that account's rides.
 *
 * It is also saved with the track file version this app could read, and how it fetched them
 * ([TRACK_DOWNLOAD_VERSION]). Rides whose tracks were too new, or came in a way the app didn't
 * understand, got skipped; once the app is updated, the cursor starts again from 0, so they
 * are fetched. Rides already on the phone just come out as "keep".
 */
class SyncCursorStore(context: Context) : SyncCursor {

    private val store = context.applicationContext.syncStore

    override suspend fun get(userId: String): Long {
        val prefs = store.data.first()
        val readerUpToDate = (prefs[READER] ?: 1) >= TrackFile.VERSION &&
            (prefs[DOWNLOAD] ?: 1) >= TRACK_DOWNLOAD_VERSION
        return if (prefs[USER] == userId && readerUpToDate) prefs[SYNCED_AT] ?: 0L else 0L
    }

    /**
     * When [userId]'s last complete sync on this phone finished, or null before the first (or
     * if the last one was another account's). Updates itself.
     */
    fun lastSynced(userId: String): Flow<Long?> =
        store.data.map { if (it[LAST_SYNCED_USER] == userId) it[LAST_SYNCED] else null }

    suspend fun setLastSynced(userId: String, millis: Long) {
        store.edit {
            it[LAST_SYNCED_USER] = userId
            it[LAST_SYNCED] = millis
        }
    }

    override suspend fun set(userId: String, syncedAt: Long) {
        store.edit {
            it[USER] = userId
            it[SYNCED_AT] = syncedAt
            it[READER] = TrackFile.VERSION
            it[DOWNLOAD] = TRACK_DOWNLOAD_VERSION
        }
    }

    private companion object {
        val USER = stringPreferencesKey("cursor_user")
        val SYNCED_AT = longPreferencesKey("cursor_synced_at")
        val READER = intPreferencesKey("cursor_track_version")
        val DOWNLOAD = intPreferencesKey("cursor_download_version")
        val LAST_SYNCED = longPreferencesKey("last_synced_at")
        val LAST_SYNCED_USER = stringPreferencesKey("last_synced_user")
    }
}

/**
 * Where the segment download picks up from: the `syncedAt` of the last segment item handled,
 * kept apart from the ride cursor (the two listings run on their own clocks), and saved with
 * its account like the ride cursor, so another account starts from 0.
 *
 * Like the ride cursor, it is saved with the listing version this app read
 * ([SEGMENT_LISTING_VERSION]): items of a kind or status an older version skipped are read again
 * from 0 once the app is updated. Segments already on the phone just come out as "keep".
 */
class SegmentSyncCursorStore(context: Context) : SyncCursor {

    private val store = context.applicationContext.syncStore

    override suspend fun get(userId: String): Long {
        val prefs = store.data.first()
        val readerUpToDate = (prefs[READER] ?: 1) >= SEGMENT_LISTING_VERSION
        return if (prefs[USER] == userId && readerUpToDate) prefs[SYNCED_AT] ?: 0L else 0L
    }

    override suspend fun set(userId: String, syncedAt: Long) {
        store.edit {
            it[USER] = userId
            it[SYNCED_AT] = syncedAt
            it[READER] = SEGMENT_LISTING_VERSION
        }
    }

    private companion object {
        val USER = stringPreferencesKey("segment_cursor_user")
        val SYNCED_AT = longPreferencesKey("segment_cursor_synced_at")
        val READER = intPreferencesKey("segment_cursor_listing_version")
    }
}
