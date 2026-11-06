package com.danielbrog.activitystats.location

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One GPS reading as delivered by the fused location provider. */
data class GpsData(
    /** 1-based order of arrival within the current recording. */
    val seq: Int,
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy radius in metres, or null if the provider gave none. */
    val accuracyMeters: Float?,
    /** Speed in metres per second, or null if the provider gave none. */
    val speedMps: Float?,
)

data class TrackingUiState(
    val isTracking: Boolean = false,
    /** Newest first. */
    val fixes: List<GpsData> = emptyList(),
    val locationServicesOff: Boolean = false,
)

/**
 * Requests high-accuracy location updates (about one per second) while tracking is on
 * and keeps the received fixes in memory. Nothing is saved yet; that comes with Room (M7).
 */
class LocationViewModel(application: Application) : AndroidViewModel(application) {

    private val client = LocationServices.getFusedLocationProviderClient(application)
    private val locationManager =
        application.getSystemService(Application.LOCATION_SERVICE) as LocationManager

    private val _state = MutableStateFlow(TrackingUiState())
    val state: StateFlow<TrackingUiState> = _state.asStateFlow()

    private val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
        .setMinUpdateIntervalMillis(500L)
        .setWaitForAccurateLocation(false)
        .build()

    private var seq = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val newFixes = result.locations.map { it.toGpsData(++seq) }.reversed()
            _state.update { it.copy(fixes = (newFixes + it.fixes).take(MAX_FIXES)) }
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun start() {
        if (_state.value.isTracking) return
        val servicesOff = !LocationManagerCompat.isLocationEnabled(locationManager)
        seq = 0
        _state.update { TrackingUiState(isTracking = true, locationServicesOff = servicesOff) }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    fun stop() {
        if (!_state.value.isTracking) return
        client.removeLocationUpdates(callback)
        _state.update { it.copy(isTracking = false) }
    }

    override fun onCleared() {
        client.removeLocationUpdates(callback)
    }

    private fun Location.toGpsData(seq: Int) = GpsData(
        seq = seq,
        timeMillis = time,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        speedMps = if (hasSpeed()) speed else null,
    )

    private companion object {
        const val MAX_FIXES = 1_000
    }
}
