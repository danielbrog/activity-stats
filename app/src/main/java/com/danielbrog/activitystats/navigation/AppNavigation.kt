package com.danielbrog.activitystats.navigation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.danielbrog.activitystats.R
import com.danielbrog.activitystats.history.HistoryScreen
import com.danielbrog.activitystats.history.RideDetailScreen
import com.danielbrog.activitystats.live.LiveScreen
import com.danielbrog.activitystats.location.GPSDisplayScreen

/** A tab in the bottom bar. */
private data class Tab(val route: Any, val label: String, @DrawableRes val icon: Int) {
    fun isSelected(destination: NavDestination?): Boolean = when (route) {
        RecordRoute -> destination?.hasRoute<RecordRoute>() == true
        HistoryRoute -> destination?.hasRoute<HistoryRoute>() == true
        else -> false
    }
}

private val tabs = listOf(
    Tab(RecordRoute, "Record", R.drawable.ic_nav_record),
    Tab(HistoryRoute, "History", R.drawable.ic_nav_history),
)

/**
 * The app's screens and how to move between them. Record and History are tabs in a bottom
 * bar; ride detail and GPS details open on top of them, without the bar, and Back closes them.
 */
@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = tabs.any { it.isSelected(destination) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
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
        ) {
            composable<RecordRoute> {
                LiveScreen(onOpenGpsDetails = { navController.navigate(GpsDetailsRoute) })
            }
            composable<HistoryRoute> {
                HistoryScreen(onOpenRide = { id -> navController.navigate(RideDetailRoute(id)) })
            }
            composable<RideDetailRoute> {
                RideDetailScreen(onBack = { navController.popBackStack() })
            }
            composable<GpsDetailsRoute> {
                GPSDisplayScreen(onBack = { navController.popBackStack() })
            }
        }
    }
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
