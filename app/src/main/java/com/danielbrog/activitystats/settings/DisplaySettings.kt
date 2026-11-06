package com.danielbrog.activitystats.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.SpeedDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** How stats are shown. Defaults suit cyclists: km and speed. */
data class DisplaySettings(
    val unit: DistanceUnit = DistanceUnit.Km,
    val speedDisplay: SpeedDisplay = SpeedDisplay.Speed,
)

private val Context.displaySettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "display_settings",
)

/** Reads and saves [DisplaySettings] with Jetpack DataStore, so choices survive restarts. */
class DisplaySettingsRepository(context: Context) {

    private val store = context.applicationContext.displaySettingsStore

    val settings: Flow<DisplaySettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            DisplaySettings(
                unit = prefs[UNIT].toEnumOr(DistanceUnit.Km),
                speedDisplay = prefs[SPEED_DISPLAY].toEnumOr(SpeedDisplay.Speed),
            )
        }

    suspend fun setUnit(unit: DistanceUnit) {
        store.edit { it[UNIT] = unit.name }
    }

    suspend fun setSpeedDisplay(display: SpeedDisplay) {
        store.edit { it[SPEED_DISPLAY] = display.name }
    }

    private companion object {
        val UNIT = stringPreferencesKey("unit")
        val SPEED_DISPLAY = stringPreferencesKey("speed_display")

        inline fun <reified E : Enum<E>> String?.toEnumOr(default: E): E =
            enumValues<E>().firstOrNull { it.name == this } ?: default
    }
}
