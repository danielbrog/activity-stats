package com.danielbrog.activitystats.location

import android.Manifest
import android.app.Application
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Gives the screen the live tracking state and starts or stops [LocationTrackingService],
 * which owns the GPS updates so recording survives the screen turning off.
 */
class LocationViewModel(application: Application) : AndroidViewModel(application) {

    val state: StateFlow<TrackingUiState> = TrackingState.state

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun start() {
        if (state.value.isTracking) return
        LocationTrackingService.start(getApplication())
    }

    fun stop() {
        if (!state.value.isTracking) return
        LocationTrackingService.stop(getApplication())
    }
}
