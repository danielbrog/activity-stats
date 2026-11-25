package com.danielbrog.activitystats.data

/**
 * Where a personal segment is in review (see docs/database/segments.md). Stored in Room as its
 * name (TEXT). Only the server decides [PUBLIC], [REJECTED] and [UNPUBLISHED]; the phone only
 * submits and withdraws.
 *
 * Statuses from the server are read with [fromServer], which tolerates names this version
 * doesn't know yet, rather than [valueOf], which throws.
 */
enum class SegmentStatus {
    /** Only yours. */
    PRIVATE,

    /** Waiting for an admin to review it. */
    SUBMITTED,

    /** Approved: a verified copy exists. */
    PUBLIC,

    /** Refused, with a note. */
    REJECTED,

    /** Was public; an admin took it down. */
    UNPUBLISHED,

    ;

    companion object {
        /**
         * The status the server sent, or null for one this version doesn't know (a newer server
         * may add some): the caller then keeps what it has until the app is updated, when the
         * segment listing is read again (see `SEGMENT_LISTING_VERSION`). Unlike [valueOf], never
         * throws.
         */
        fun fromServer(name: String?): SegmentStatus? = entries.firstOrNull { it.name == name }
    }
}
