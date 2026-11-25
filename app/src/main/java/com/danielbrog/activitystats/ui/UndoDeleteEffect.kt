package com.danielbrog.activitystats.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Offers Undo for something deleted on the screen above: when [deletedId] arrives (a
 * navigation result), shows [message] with Undo in [snackbarHostState], calling [onUndo] with
 * the id if it is tapped.
 *
 * Two steps, so the bar is shown once and can't be cancelled: the id is copied into saved
 * state and [onClearResult] clears the navigation result straight away; a second effect, keyed
 * on the copy, shows the bar. Clearing the result therefore never cancels the bar, and the
 * copy brings it back after Android stops the app.
 */
@Composable
fun UndoDeleteEffect(
    deletedId: String?,
    onClearResult: () -> Unit,
    snackbarHostState: SnackbarHostState,
    message: String,
    onUndo: (id: String) -> Unit,
) {
    // Effects outlive a redraw, so they read the newest callbacks, not the ones they started with.
    val undo by rememberUpdatedState(onUndo)
    val clearResult by rememberUpdatedState(onClearResult)
    var undoId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(deletedId) {
        if (deletedId != null) {
            undoId = deletedId
            clearResult()
        }
    }
    LaunchedEffect(undoId) {
        val id = undoId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) undo(id)
        undoId = null
    }
}
