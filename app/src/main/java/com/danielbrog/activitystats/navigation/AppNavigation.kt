package com.danielbrog.activitystats.navigation

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.danielbrog.activitystats.R
import com.danielbrog.activitystats.account.AccountScreen
import com.danielbrog.activitystats.history.RideDetailScreen
import com.danielbrog.activitystats.live.LiveScreen
import com.danielbrog.activitystats.location.GPSDisplayScreen
import com.danielbrog.activitystats.profile.ProfileScreen
import com.danielbrog.activitystats.records.RecordsScreen
import com.danielbrog.activitystats.segments.ui.CreateSegmentScreen
import com.danielbrog.activitystats.segments.ui.SegmentDetailScreen
import com.danielbrog.activitystats.settings.SettingsScreen

/** Navigation result key: the id of a ride just deleted from its detail screen. */
private const val DELETED_RIDE_ID = "deletedRideId"

/** Navigation result key: the id of a segment just deleted from its screen. */
private const val DELETED_SEGMENT_ID = "deletedSegmentId"

/** A tab in the bottom bar. */
private data class Tab(val route: Any, val label: String, @DrawableRes val icon: Int) {
    /** Whether [destination] is this tab's screen. Works for any route, so a new tab needs no change here. */
    fun isSelected(destination: NavDestination?): Boolean = destination?.hasRoute(route::class) == true
}

private val tabs = listOf(
    Tab(RecordRoute, "Record", R.drawable.ic_nav_record),
    Tab(ProfileRoute, "Profile", R.drawable.ic_nav_profile),
    Tab(RecordsRoute, "Records", R.drawable.ic_nav_records),
)

// --- Transitions ---
// Back is a predictive gesture (Android 16+), so the pop transitions below follow your finger.

/** How long a screen takes to slide over another, or the bottom bar to grow or shrink. */
private const val SLIDE_MS = 300
/** Fade-through between tabs: the old one fades out quickly, then the new one fades in. */
private const val FADE_OUT_MS = 90
private const val FADE_IN_MS = 210

/** Screens that open on top of a tab, without the bottom bar, and slide in from the side. */
private fun NavDestination.opensOnTop(): Boolean =
    hasRoute<RideDetailRoute>() || hasRoute<AccountRoute>() || hasRoute<GpsDetailsRoute>() ||
        hasRoute<SettingsRoute>() || hasRoute<CreateSegmentRoute>() || hasRoute<SegmentDetailRoute>()

private fun fadeThroughIn(): EnterTransition = fadeIn(tween(FADE_IN_MS, delayMillis = FADE_OUT_MS))

private fun fadeThroughOut(): ExitTransition = fadeOut(tween(FADE_OUT_MS))

/** Opening: a screen on top slides in from the right; a tab fades in. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    if (targetState.destination.opensOnTop()) {
        slideIntoContainer(SlideDirection.Start, tween(SLIDE_MS))
    } else {
        fadeThroughIn()
    }

/** Under a screen opening on top, the old one shifts a quarter left; between tabs it fades out. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    if (targetState.destination.opensOnTop()) {
        slideOutOfContainer(SlideDirection.Start, tween(SLIDE_MS)) { it / 4 }
    } else {
        fadeThroughOut()
    }

/** Back to the screen underneath: it shifts back from the left; a tab fades in. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    if (initialState.destination.opensOnTop()) {
        slideIntoContainer(SlideDirection.End, tween(SLIDE_MS)) { it / 4 }
    } else {
        fadeThroughIn()
    }

/** Back from a screen on top: it slides out to the right; a tab fades out. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    if (initialState.destination.opensOnTop()) {
        slideOutOfContainer(SlideDirection.End, tween(SLIDE_MS))
    } else {
        fadeThroughOut()
    }

/**
 * Gives a screen a solid background. Without it the screens are see-through, so during a
 * transition the one underneath shows through the one on top.
 */
@Composable
private fun Opaque(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

/**
 * The app's screens and how to move between them. Record, Profile and Records are tabs in a
 * bottom bar, switched with a quick fade; Profile holds History, Segments and Stats tabs of
 * its own. Ride detail, Create segment, Segment detail, Account, Settings and GPS details slide
 * in on top of them, without the bar, and Back slides them away again.
 */
@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = tabs.any { it.isSelected(destination) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            // Grows and shrinks rather than popping, so the content above moves smoothly.
            AnimatedVisibility(
                visible = showBottomBar,
                enter = expandVertically(tween(SLIDE_MS)),
                exit = shrinkVertically(tween(SLIDE_MS)),
            ) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = tab.isSelected(destination),
                            onClick = { navController.switchToTab(tab.route) },
                            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = RecordRoute,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { enter() },
            exitTransition = { exit() },
            popEnterTransition = { popEnter() },
            popExitTransition = { popExit() },
        ) {
            composable<RecordRoute> {
                Opaque { LiveScreen(onOpenGpsDetails = { navController.navigate(GpsDetailsRoute) }) }
            }
            composable<ProfileRoute> { entry ->
                // Set by the ride and segment screens when they delete one, so the tab can
                // offer Undo.
                val deletedRideId by entry.savedStateHandle
                    .getStateFlow<String?>(DELETED_RIDE_ID, null)
                    .collectAsStateWithLifecycle()
                val deletedSegmentId by entry.savedStateHandle
                    .getStateFlow<String?>(DELETED_SEGMENT_ID, null)
                    .collectAsStateWithLifecycle()
                Opaque {
                    ProfileScreen(
                        onOpenRide = { id -> navController.openRide(id) },
                        onOpenSegment = { id -> navController.openSegment(id) },
                        onOpenAccount = { navController.navigate(AccountRoute) },
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                        deletedRideId = deletedRideId,
                        onDeletedRideHandled = { entry.savedStateHandle[DELETED_RIDE_ID] = null },
                        deletedSegmentId = deletedSegmentId,
                        onDeletedSegmentHandled = { entry.savedStateHandle[DELETED_SEGMENT_ID] = null },
                    )
                }
            }
            composable<RecordsRoute> { entry ->
                val deletedRideId by entry.savedStateHandle
                    .getStateFlow<String?>(DELETED_RIDE_ID, null)
                    .collectAsStateWithLifecycle()
                Opaque {
                    RecordsScreen(
                        onOpenRide = { id -> navController.openRide(id) },
                        deletedRideId = deletedRideId,
                        onDeletedRideHandled = { entry.savedStateHandle[DELETED_RIDE_ID] = null },
                    )
                }
            }
            composable<RideDetailRoute> { entry ->
                // Set by the segment screen when a segment opened from this ride is deleted, for Undo.
                val deletedSegmentId by entry.savedStateHandle
                    .getStateFlow<String?>(DELETED_SEGMENT_ID, null)
                    .collectAsStateWithLifecycle()
                Opaque {
                    RideDetailScreen(
                        onBack = { navController.popBackStack() },
                        onCreateSegment = { id -> navController.navigate(CreateSegmentRoute(id)) },
                        onOpenSegment = { id -> navController.openSegment(id) },
                        deletedSegmentId = deletedSegmentId,
                        onDeletedSegmentHandled = { entry.savedStateHandle[DELETED_SEGMENT_ID] = null },
                        onDeleted = { id ->
                            navController.previousBackStackEntry?.savedStateHandle?.set(DELETED_RIDE_ID, id)
                            navController.popBackStack()
                        },
                    )
                }
            }
            composable<CreateSegmentRoute> {
                Opaque { CreateSegmentScreen(onBack = { navController.popBackStack() }) }
            }
            composable<SegmentDetailRoute> { entry ->
                // Set by the ride screen when a ride opened from here is deleted, for Undo.
                val deletedRideId by entry.savedStateHandle
                    .getStateFlow<String?>(DELETED_RIDE_ID, null)
                    .collectAsStateWithLifecycle()
                Opaque {
                    SegmentDetailScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRide = { id -> navController.openRide(id) },
                        onDeleted = { id ->
                            // Segments open from Profile or a ride; both offer Undo.
                            navController.previousBackStackEntry?.savedStateHandle?.set(DELETED_SEGMENT_ID, id)
                            navController.popBackStack()
                        },
                        deletedRideId = deletedRideId,
                        onDeletedRideHandled = { entry.savedStateHandle[DELETED_RIDE_ID] = null },
                    )
                }
            }
            composable<AccountRoute> {
                Opaque { AccountScreen(onBack = { navController.popBackStack() }) }
            }
            composable<SettingsRoute> {
                Opaque { SettingsScreen(onBack = { navController.popBackStack() }) }
            }
            composable<GpsDetailsRoute> {
                Opaque { GPSDisplayScreen(onBack = { navController.popBackStack() }) }
            }
        }
    }
}

// --- Rides and segments: one screen of each at most ---
// A ride opens segments and a segment opens rides, so without a rule they could stack up
// forever (ride → segment → ride → …). Instead there is never more than one ride screen and
// one segment screen: opening the same one again goes back to it; opening a different one
// replaces the open one (and anything above it). Back always reaches the tab in two steps.

/** Opens ride [id]: back to it if it's already open, otherwise in place of any open ride. */
private fun NavHostController.openRide(id: String) {
    if (popBackStack(RideDetailRoute(id), inclusive = false)) return
    navigate(RideDetailRoute(id)) { popUpTo<RideDetailRoute> { inclusive = true } }
}

/** Opens segment [id]: back to it if it's already open, otherwise in place of any open segment. */
private fun NavHostController.openSegment(id: String) {
    if (popBackStack(SegmentDetailRoute(id), inclusive = false)) return
    navigate(SegmentDetailRoute(id)) { popUpTo<SegmentDetailRoute> { inclusive = true } }
}

/**
 * Standard bottom-bar behaviour: one copy of each tab on the back stack, each tab keeps its
 * scroll position, and Back from any tab returns to Record.
 */
private fun NavHostController.switchToTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
