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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun start() {
        if (state.value.isTracking) return
        LocationTrackingService.start(getApplication())
    }

    fun stop() {
        if (!state.value.isTracking) return
        LocationTrackingService.stop(getApplication())
    }

    fun setUnit(unit: DistanceUnit) {
        viewModelScope.launch { settingsRepository.setUnit(unit) }
    }

    fun setSpeedDisplay(display: SpeedDisplay) {
        viewModelScope.launch { settingsRepository.setSpeedDisplay(display) }
    }
}
