package com.danielbrog.activitystats.records

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danielbrog.activitystats.ActivityStatsApp
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.settings.DisplaySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The personal records, or null while first loading. Updates itself when a ride is saved,
 * renamed, deleted or restored, or when best efforts are worked out in the background.
 */
class RecordsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ActivityStatsApp).activityRepository

    val records: StateFlow<PersonalRecords?> =
        combine(repository.finishedActivities, repository.bestEfforts) { rides, efforts ->
            PersonalRecords.from(rides, efforts)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val settings: StateFlow<DisplaySettings> = DisplaySettingsRepository(application).settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DisplaySettings())

    /** Undoes a delete made from the ride detail screen. */
    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}
