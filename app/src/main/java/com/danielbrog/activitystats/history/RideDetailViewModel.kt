package com.danielbrog.activitystats.history

import android.app.Application
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface RideDetailState {
    data object Loading : RideDetailState
    data object NotFound : RideDetailState
    /** [route] holds only the kept fixes, so the line skips GPS noise. */
    data class Loaded(val ride: ActivityEntity, val route: List<RoutePoint>) : RideDetailState
}

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

    private val route = MutableStateFlow<List<RoutePoint>?>(null)

    val state: StateFlow<RideDetailState> =
        combine(repository.observeActivity(rideId), route) { ride, points ->
            when {
                ride == null || ride.deletedAt != null -> RideDetailState.NotFound
                points == null -> RideDetailState.Loading
                else -> RideDetailState.Loaded(ride, points)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), RideDetailState.Loading)

    val settings: StateFlow<DisplaySettings> = DisplaySettingsRepository(application).settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DisplaySettings())

    init {
        viewModelScope.launch {
            route.value = repository.pointsFor(rideId)
                .filter { it.status == FixStatus.Kept }
                .map { RoutePoint(it.latitude, it.longitude) }
        }
    }

    /** Saves a new name; ignored if it is blank after cleaning. */
    fun rename(input: String) {
        val title = RideTitle.clean(input) ?: return
        viewModelScope.launch { repository.rename(rideId, title) }
    }

    /** Soft-deletes the ride, then calls [onDeleted] so the screen can close. */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(rideId)
            onDeleted()
        }
    }
}
