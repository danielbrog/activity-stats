package com.danielbrog.activitystats.history

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.danielbrog.activitystats.ActivityStatsApp
import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.data.RideTitle
import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.navigation.RideDetailRoute
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.settings.DisplaySettingsRepository
import com.danielbrog.activitystats.stats.ElevationSample
import com.danielbrog.activitystats.stats.ElevationSeries
import com.danielbrog.activitystats.stats.SpeedSample
import com.danielbrog.activitystats.stats.SpeedSeries
import com.danielbrog.activitystats.export.GpxWriter
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface RideDetailState {
    data object Loading : RideDetailState
    data object NotFound : RideDetailState
    /** [route] and [speedSamples] use only the kept fixes, so they skip GPS noise. */
    data class Loaded(
        val ride: ActivityEntity,
        val route: List<RoutePoint>,
        val speedSamples: List<SpeedSample>,
        val elevationSamples: List<ElevationSample>,
    ) : RideDetailState
}

/** A written GPX file, ready to share. */
data class GpxExport(val uri: Uri, val fileName: String)

/** The parts of a ride built from its saved points; they never change once loaded. */
private data class RideTrack(
    val route: List<RoutePoint>,
    val speedSamples: List<SpeedSample>,
    val elevationSamples: List<ElevationSample>,
)

/**
 * One saved ride and its route, by the id in the navigation route. The ride is watched,
 * so a rename shows up at once; the route is loaded once, since it never changes.
 */
class RideDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = (application as ActivityStatsApp).activityRepository
    val rideId: String = savedStateHandle.toRoute<RideDetailRoute>().id

    private val track = MutableStateFlow<RideTrack?>(null)

    val state: StateFlow<RideDetailState> =
        combine(repository.observeActivity(rideId), track) { ride, loaded ->
            when {
                ride == null || ride.deletedAt != null -> RideDetailState.NotFound
                loaded == null -> RideDetailState.Loading
                else -> RideDetailState.Loaded(ride, loaded.route, loaded.speedSamples, loaded.elevationSamples)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), RideDetailState.Loading)

    val settings: StateFlow<DisplaySettings> = DisplaySettingsRepository(application).settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DisplaySettings())

    init {
        viewModelScope.launch {
            val points = repository.pointsFor(rideId)
            // Building the chart data walks every point; keep it off the main thread.
            track.value = withContext(Dispatchers.Default) {
                RideTrack(
                    route = points
                        .filter { it.status == FixStatus.Kept }
                        .map { RoutePoint(it.latitude, it.longitude) },
                    speedSamples = SpeedSeries.downsample(SpeedSeries.fromFixes(points)),
                    elevationSamples = ElevationSeries.downsample(ElevationSeries.fromFixes(points)),
                )
            }
        }
    }

    /** Saves a new name; ignored if it is blank after cleaning. */
    fun rename(input: String) {
        val title = RideTitle.clean(input) ?: return
        viewModelScope.launch { repository.rename(rideId, title) }
    }

    private var exportJob: Job? = null

    /**
     * Writes the ride as a GPX file in the app's cache (replacing the last export) and calls
     * [onReady] with a content link other apps can read, or [onError] if it could not be written.
     */
    fun exportGpx(onReady: (GpxExport) -> Unit, onError: () -> Unit) {
        if (exportJob?.isActive == true) return
        val ride = (state.value as? RideDetailState.Loaded)?.ride ?: return
        val app = getApplication<Application>()
        exportJob = viewModelScope.launch {
            val export = try {
                withContext(Dispatchers.IO) {
                    val points = repository.pointsFor(rideId)
                    val dir = File(app.cacheDir, "gpx").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(dir, GpxWriter.fileName(ride.title, ride.startedAt))
                    file.bufferedWriter().use { out ->
                        GpxWriter.write(out, ride.title, ride.type, ride.startedAt, points)
                    }
                    GpxExport(
                        uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file),
                        fileName = file.name,
                    )
                }
            } catch (e: IOException) {
                Log.w("RideDetail", "GPX export failed", e)
                null
            }
            if (export != null) onReady(export) else onError()
        }
    }

    /** Soft-deletes the ride, then calls [onDeleted] so the screen can close. */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(rideId)
            onDeleted()
        }
    }
}
