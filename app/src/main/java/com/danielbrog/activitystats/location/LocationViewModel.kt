package com.danielbrog.activitystats.location

import android.Manifest
import android.app.Application
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.settings.DisplaySettingsRepository
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.SpeedDisplay
import com.danielbrog.activitystats.ActivityStatsApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the live screen shows about saved activities. */
data class SavedSummary(val title: String, val count: Int)

/**
 * Gives the screens the live tracking state and display settings, and starts or stops
 * [LocationTrackingService], which owns the GPS updates so recording survives the screen
 * turning off.
 */
class LocationViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = DisplaySettingsRepository(application)

    val state: StateFlow<TrackingUiState> = TrackingState.state

    val settings: StateFlow<DisplaySettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, DisplaySettings())

    private val activities = (application as ActivityStatsApp).activityRepository

    /** The latest saved activity and how many are saved, or null before the first one. */
    val savedSummary: StateFlow<SavedSummary?> =
        combine(activities.latestSaved, activities.savedCount) { latest, count ->
            latest?.let { SavedSummary(title = it.title, count = count) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /**
     * Whether the Record screen already asked for location permission on opening. Kept here
     * rather than in the screen, so switching tabs and back doesn't ask again.
     */
    var askedForLocationOnOpen = false

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun start() {
        if (state.value.isTracking) return
        LocationTrackingService.start(getApplication())
    }

    /**
     * Finishes the recording and saves the ride, named [title] (cleaned with RideTitle.clean),
     * or with its default name when null.
     */
    fun finish(title: String? = null) {
        if (!state.value.isTracking) return
        LocationTrackingService.stop(getApplication(), title)
    }

    /** Pauses by hand: the GPS goes off and the timers stand still until [resume]. */
    fun pause() {
        if (!state.value.isTracking || state.value.isManuallyPaused) return
        LocationTrackingService.pause(getApplication())
    }

    /**
     * Undoes the pause Finish made, when the rider chooses Keep riding. Sent without checking
     * the pause has landed: the service handles requests in order, so this always follows it,
     * and a resume with nothing paused does nothing.
     */
    fun resumeAfterFinishCancelled() {
        if (state.value.isTracking) LocationTrackingService.resume(getApplication())
    }

    fun resume() {
        if (!state.value.isTracking || !state.value.isManuallyPaused) return
        LocationTrackingService.resume(getApplication())
    }

    fun setUnit(unit: DistanceUnit) {
        viewModelScope.launch { settingsRepository.setUnit(unit) }
    }

    fun setSpeedDisplay(display: SpeedDisplay) {
        viewModelScope.launch { settingsRepository.setSpeedDisplay(display) }
    }

    fun setAutoPause(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoPause(enabled) }
    }
}
