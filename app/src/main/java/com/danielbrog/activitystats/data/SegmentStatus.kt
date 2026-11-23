package com.danielbrog.activitystats.data

/**
 * Where a personal segment is in review (see docs/database/segments.md). Stored in Room as its
 * name (TEXT). Only the server decides [PUBLIC], [REJECTED] and [UNPUBLISHED]; the phone only
 * submits and withdraws.
 *
 * When statuses start coming from the server (segment sync), read them with a mapping that
 * tolerates names this version doesn't know yet, rather than [valueOf], which throws.
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
}
