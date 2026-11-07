package com.danielbrog.activitystats.navigation

import kotlinx.serialization.Serializable

// Type-safe navigation routes: each screen is a class, so a wrong route is a build error.

/** The live recording screen; where the app opens. */
@Serializable
object RecordRoute

/** The list of saved rides. */
@Serializable
object HistoryRoute

/** One saved ride, by its activity id. */
@Serializable
data class RideDetailRoute(val id: String)

/** The raw GPS fix list for the current recording. */
@Serializable
object GpsDetailsRoute
