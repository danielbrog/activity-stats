package com.danielbrog.activitystats.navigation

import kotlinx.serialization.Serializable

// Type-safe navigation routes: each screen is a class, so a wrong route is a build error.

/** The live recording screen; where the app opens. */
@Serializable
object RecordRoute

/** The Profile tab: the account and settings, with History and Stats tabs inside it. */
@Serializable
object ProfileRoute

/** Personal records: longest ride, longest moving time and best efforts. */
@Serializable
object RecordsRoute

/** One saved ride, by its activity id. */
@Serializable
data class RideDetailRoute(val id: String)

/** Sign in, sign out and see the account; opened from Profile. */
@Serializable
object AccountRoute

/** The raw GPS fix list for the current recording. */
@Serializable
object GpsDetailsRoute

/** App settings; opened from Profile. */
@Serializable
object SettingsRoute
