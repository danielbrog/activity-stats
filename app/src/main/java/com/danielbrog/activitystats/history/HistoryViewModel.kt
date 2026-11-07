package com.danielbrog.activitystats.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danielbrog.activitystats.ActivityStatsApp
import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.settings.DisplaySettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The ride list, or null while it is first loading. */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ActivityStatsApp).activityRepository

    val rides: StateFlow<List<ActivityEntity>?> = repository.finishedActivities
        .map<List<ActivityEntity>, List<ActivityEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val settings: StateFlow<DisplaySettings> = DisplaySettingsRepository(application).settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DisplaySettings())

    /** Undoes a delete made from the ride detail screen. */
    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}
