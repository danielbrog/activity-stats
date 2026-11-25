package com.danielbrog.activitystats.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.danielbrog.activitystats.R
import com.danielbrog.activitystats.history.HistoryScreen
import com.danielbrog.activitystats.segments.ui.SegmentsTab
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme
import kotlinx.coroutines.launch

/** The tabs inside Profile, in order. */
private enum class ProfileTab(val label: String) { History("History"), Segments("Segments"), Stats("Stats") }

/**
 * The Profile tab: a header with the account and settings, then History, Segments and Stats tabs.
 * Tap a tab or swipe between them; the chosen one is kept while a ride or segment is open on top.
 * Messages, like "Ride deleted · Undo", are shown here rather than in a tab, so they stay
 * put when you swipe.
 *
 * [deletedRideId] and [onClearDeletedRide] pass a ride deleted from its detail screen on
 * to History, which offers Undo; [deletedSegmentId] and [onClearDeletedSegment] do the same
 * for a segment, to the Segments tab.
 */
@Composable
fun ProfileScreen(
    onOpenRide: (id: String) -> Unit,
    onOpenSegment: (id: String) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    deletedRideId: String?,
    onClearDeletedRide: () -> Unit,
    deletedSegmentId: String?,
    onClearDeletedSegment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    ProfileContent(
        onOpenAccount = onOpenAccount,
        onOpenSettings = onOpenSettings,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        history = {
            HistoryScreen(
                onOpenRide = onOpenRide,
                deletedRideId = deletedRideId,
                onClearDeletedRide = onClearDeletedRide,
                snackbarHostState = snackbarHostState,
            )
        },
        segments = {
            SegmentsTab(
                onOpenSegment = onOpenSegment,
                deletedSegmentId = deletedSegmentId,
                onClearDeletedSegment = onClearDeletedSegment,
                snackbarHostState = snackbarHostState,
            )
        },
        stats = { StatsTab() },
    )
}

@Composable
private fun ProfileContent(
    onOpenAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    snackbarHostState: SnackbarHostState,
    history: @Composable () -> Unit,
    segments: @Composable () -> Unit,
    stats: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = ProfileTab.entries
    // Saved with the screen, so coming back from a ride shows the same tab.
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Profile",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = onOpenAccount) {
                    Icon(painterResource(R.drawable.ic_account), contentDescription = "Account")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                }
            }
            PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(tab.label) },
                    )
                }
            }
            // Keep every tab loaded while off screen. They are light lists, and it means History
            // carries on (an import reports back, Undo stays up) while you look at another tab.
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = tabs.size - 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (tabs[page]) {
                    ProfileTab.History -> history()
                    ProfileTab.Segments -> segments()
                    ProfileTab.Stats -> stats()
                }
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Empty for now; stats about all your rides will go here. */
@Composable
private fun StatsTab() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            "Stats are coming soon.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true, heightDp = 600)
@Composable
private fun ProfilePreview() {
    ActivityStatsTheme {
        ProfileContent(
            onOpenAccount = {},
            onOpenSettings = {},
            snackbarHostState = remember { SnackbarHostState() },
            history = { Text("History list", modifier = Modifier.padding(16.dp)) },
            segments = { Text("Segment list", modifier = Modifier.padding(16.dp)) },
            stats = { StatsTab() },
        )
    }
}
