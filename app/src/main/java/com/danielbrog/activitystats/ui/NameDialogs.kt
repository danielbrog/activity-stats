package com.danielbrog.activitystats.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization

/**
 * Renames something (a ride, a segment). [clean] is its name rule (trim, cap at [maxLength],
 * null when blank). Save, or Done on the keyboard, stays off while the name is blank or
 * unchanged, so an unchanged name isn't saved as an edit. [capitalization]: sentences for a
 * ride ("Morning ride"), words for a road ("Hill Climb").
 */
@Composable
fun RenameDialog(
    title: String,
    currentName: String,
    maxLength: Int,
    clean: (String) -> String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
) {
    var text by rememberSaveable { mutableStateOf(currentName) }
    val cleaned = clean(text)
    val toSave = renameToSave(text, currentName, clean)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(maxLength) },
                label = { Text("Name") },
                singleLine = true,
                isError = cleaned == null,
                // Done saves, for one hand on the bars.
                keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { toSave?.let(onSave) }),
                supportingText = {
                    Text(if (cleaned == null) "Name can't be blank" else "${text.length} / $maxLength")
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { toSave?.let(onSave) }, enabled = toSave != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The name to save from what was typed, or null when there's nothing to save: blank after
 * [clean], or the same as [currentName] (an unchanged name isn't an edit). Plain Kotlin.
 */
internal fun renameToSave(text: String, currentName: String, clean: (String) -> String?): String? =
    clean(text)?.takeIf { it != currentName }

/** Asks before deleting [name] from this phone; Delete is in red. */
@Composable
fun ConfirmDeleteDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"$name\"?") },
        text = { Text("It will be removed from this phone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
