package com.danielbrog.activitystats.history

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danielbrog.activitystats.ActivityStatsApp
import com.danielbrog.activitystats.data.ActivityEntity
import com.danielbrog.activitystats.data.ImportResult
import com.danielbrog.activitystats.export.GpxImportException
import com.danielbrog.activitystats.export.GpxReader
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.settings.DisplaySettingsRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a GPX import ended, for the History screen to act on. */
sealed interface ImportOutcome {
    data class Imported(val id: String) : ImportOutcome
    data class AlreadyImported(val id: String) : ImportOutcome
    data class Failed(val message: String) : ImportOutcome
}

/** The ride list, or null while it is first loading; also imports GPX files. */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as ActivityStatsApp).activityRepository

    val rides: StateFlow<List<ActivityEntity>?> = repository.finishedActivities
        .map<List<ActivityEntity>, List<ActivityEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val settings: StateFlow<DisplaySettings> = DisplaySettingsRepository(application).settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DisplaySettings())

    private val _importing = MutableStateFlow(false)
    /** True while a file is being read and saved, so the button can't start a second one. */
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Undoes a delete made from the ride detail screen. */
    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    /** Reads the GPX file at [uri] and saves it as a ride, then reports how it went. */
    fun importGpx(uri: Uri, onDone: (ImportOutcome) -> Unit) {
        if (_importing.value) return
        _importing.value = true
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    val track = resolver.openInputStream(uri)?.use { GpxReader.read(it.buffered()) }
                        ?: throw IOException("Couldn't open $uri")
                    when (val result = repository.importTrack(track)) {
                        is ImportResult.Imported -> ImportOutcome.Imported(result.id)
                        is ImportResult.AlreadyImported -> ImportOutcome.AlreadyImported(result.id)
                    }
                }
            } catch (e: GpxImportException) {
                ImportOutcome.Failed(
                    when (e.reason) {
                        GpxImportException.Reason.NotGpx -> "This isn't a GPX file."
                        GpxImportException.Reason.NoTimes ->
                            "This is a planned route with no times, not a recorded ride."
                        GpxImportException.Reason.NoPoints -> "This GPX file has no track in it."
                    }
                )
            } catch (e: IOException) {
                Log.w("History", "GPX import failed", e)
                ImportOutcome.Failed("Couldn't read that file.")
            } catch (e: SecurityException) {
                Log.w("History", "GPX import not allowed", e)
                ImportOutcome.Failed("Couldn't read that file.")
            } finally {
                _importing.value = false
            }
            onDone(outcome)
        }
    }
}
