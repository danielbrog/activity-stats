package com.danielbrog.activitystats.segments.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danielbrog.activitystats.ActivityStatsApp
import com.danielbrog.activitystats.data.SegmentSummary
import com.danielbrog.activitystats.settings.DisplaySettingsRepository
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.Format
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The segment list, or null while it is first loading. */
class SegmentsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ActivityStatsApp).segmentRepository

    val segments: StateFlow<List<SegmentSummary>?> = repository.summaries
        .stateIn<List<SegmentSummary>?>(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** Ids of new segments whose efforts are still being found. */
    val finding: StateFlow<Set<String>> = repository.finding

    val unit: StateFlow<DistanceUnit> = DisplaySettingsRepository(application).settings
        .map { it.unit }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DistanceUnit.Km)

    /** Undoes a delete made from the segment screen. */
    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}

/**
 * The Segments tab inside Profile: every segment, by name, with your best time on it. Tapping
 * one opens it ([onOpenSegment], with its id).
 *
 * When [deletedSegmentId] is set (a segment was just deleted from its screen), shows a
 * "Segment deleted" bar with Undo in [snackbarHostState] (Profile's, so it stays put when you
 * swipe), then calls [onDeletedSegmentHandled] so it isn't shown again.
 */
@Composable
fun SegmentsTab(
    onOpenSegment: (id: String) -> Unit,
    deletedSegmentId: String?,
    onDeletedSegmentHandled: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: SegmentsViewModel = viewModel(),
) {
    // Same two-step handling as History, so clearing the result can't cancel the bar.
    var undoSegmentId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(deletedSegmentId) {
        if (deletedSegmentId != null) {
            undoSegmentId = deletedSegmentId
            onDeletedSegmentHandled()
        }
    }
    LaunchedEffect(undoSegmentId) {
        val id = undoSegmentId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Segment deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.restore(id)
        undoSegmentId = null
    }

    val segments by viewModel.segments.collectAsStateWithLifecycle()
    val finding by viewModel.finding.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    SegmentsContent(segments, finding, unit, onOpenSegment, modifier)
}

@Composable
private fun SegmentsContent(
    segments: List<SegmentSummary>?,
    finding: Set<String>,
    unit: DistanceUnit,
    onOpenSegment: (id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        segments == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        segments.isEmpty() -> Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "No segments yet. Open a ride and choose Create segment from its menu.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(segments, key = { it.id }) { SegmentCard(it, isFinding = it.id in finding, unit, onClick = { onOpenSegment(it.id) }) }
        }
    }
}

@Composable
private fun SegmentCard(segment: SegmentSummary, isFinding: Boolean, unit: DistanceUnit, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Clip first, so the tap ripple follows the card's rounded corners.
            .clip(CardDefaults.shape)
            .clickable(onClickLabel = "Open segment", role = Role.Button, onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(segment.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    Format.distance(segment.distanceMeters, unit),
                    segment.bestMs?.let { "Best ${Format.clock(it)}" },
                    when {
                        isFinding -> "Finding efforts…"
                        segment.effortCount == 0 -> "No efforts yet"
                        segment.effortCount == 1 -> "1 effort"
                        else -> "${segment.effortCount} efforts"
                    },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SegmentsPreview() {
    ActivityStatsTheme {
        SegmentsContent(
            listOf(
                SegmentSummary("a", "Bayview climb", 1_240.0, bestMs = 151_000L, effortCount = 6),
                SegmentSummary("b", "Lakeshore sprint", 420.0, bestMs = null, effortCount = 0),
            ),
            finding = setOf("b"),
            unit = DistanceUnit.Km,
            onOpenSegment = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SegmentsEmptyPreview() {
    ActivityStatsTheme { SegmentsContent(emptyList(), emptySet(), DistanceUnit.Km, onOpenSegment = {}) }
}
