package com.danielbrog.activitystats.sync

import com.danielbrog.activitystats.data.ActivityEntity

/** What to do with one ride from the cloud. */
enum class Decision {
    /** New to this phone: fetch its track and save it. */
    Download,

    /** The cloud's copy is newer: take its title and deleted state. */
    TakeCloud,

    /** The phone's copy is as new or newer; if newer, it goes up on the next upload. */
    Keep,

    /** Not worth saving here: deleted, or never finished. */
    Skip,
}

/**
 * Decides what a downloaded ride does to this phone's copy, [local], or null if the phone
 * doesn't have it. The newer `updatedAt` wins, as on the server. Plain Kotlin, unit-tested.
 */
fun decide(local: ActivityEntity?, cloud: CloudRide): Decision = when {
    // A ride this phone never had: only worth saving if it is a finished, live ride. A deleted
    // one that is later restored elsewhere comes back in a later listing.
    local == null -> if (cloud.deletedAt == null && cloud.endedAt != null) Decision.Download else Decision.Skip
    cloud.updatedAt > local.updatedAt -> Decision.TakeCloud
    else -> Decision.Keep
}
