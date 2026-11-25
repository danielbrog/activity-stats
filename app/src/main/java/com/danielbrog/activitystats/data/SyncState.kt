package com.danielbrog.activitystats.data

/**
 * Where a ride or segment is with the cloud. Stored in Room as its name (TEXT), so the column
 * holds the same text as before this was an enum. Phone-only: never sent to the server.
 */
enum class SyncState {
    /** No account: stays on this phone. */
    LOCAL_ONLY,

    /** Belongs to an account and is waiting to upload. */
    PENDING,

    /** The cloud has this copy. */
    SYNCED,

    /** The server refused it; it waits until it is edited or Sync now is pressed. */
    FAILED,

    ;

    companion object {
        /** A new ride waits to upload if it has an owner, and stays on the phone if not. */
        fun forNewRide(userId: String?): SyncState = if (userId == null) LOCAL_ONLY else PENDING
    }
}

/**
 * The same states as SQL text, for `@Query` strings: annotations need constants, and an enum's
 * name isn't one. Each must equal its [SyncState] name in quotes; `SyncStateTest` checks they do.
 */
object SyncStateSql {
    const val LOCAL_ONLY = "'LOCAL_ONLY'"
    const val PENDING = "'PENDING'"
    const val SYNCED = "'SYNCED'"
    const val FAILED = "'FAILED'"
}
