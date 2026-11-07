package com.danielbrog.activitystats.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.danielbrog.activitystats.ActivityStatsApp
import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.RoutePoint
import com.danielbrog.activitystats.navigation.RideDetailRoute
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.settings.DisplaySettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface RideDetailState {
    data object Loading : RideDetailState
    data object NotFound : RideDetailState
    /** [route] holds only the kept fixes, so the line skips GPS noise. */
    data class Loaded(val ride: ActivityEntity, val route: List<RoutePoint>) : RideDetailState
}

/** Loads one saved ride and its route, by the id in the navigation route. */
class RideDetailViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository = (application as ActivityStatsApp).activityRepository
    private val rideId = savedStateHandle.toRoute<RideDetailRoute>().id

    private val _state = MutableStateFlow<RideDetailState>(RideDetailState.Loading)
    val state: StateFlow<RideDetailState> = _state.asStateFlow()

    val settings: StateFlow<DisplaySettings> = DisplaySettingsRepository(application).settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DisplaySettings())

    init {
        viewModelScope.launch {
            val ride = repository.getActivity(rideId)
            _state.value = if (ride == null || ride.deletedAt != null) {
                RideDetailState.NotFound
            } else {
                val route = repository.pointsFor(rideId)
                    .filter { it.status == FixStatus.Kept }
                    .map { RoutePoint(it.latitude, it.longitude) }
                RideDetailState.Loaded(ride, route)
            }
        }
    }
}
