package com.danielbrog.activitystats.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.SpeedDisplay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Settings screen: the only place the display settings are changed. Every screen, the
 * recording notification and the charts read the same saved [DisplaySettings], so a change
 * here shows everywhere at once.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DisplaySettingsRepository(application)

    val settings: StateFlow<DisplaySettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DisplaySettings())

    fun setUnit(unit: DistanceUnit) {
        viewModelScope.launch { repository.setUnit(unit) }
    }

    fun setSpeedDisplay(display: SpeedDisplay) {
        viewModelScope.launch { repository.setSpeedDisplay(display) }
    }

    fun setAutoPause(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoPause(enabled) }
    }
}
