package com.danielbrog.activitystats.live

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import com.danielbrog.activitystats.data.RideTitle
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

/**
 * Shown when Finish is pressed on the Record screen, which pauses the ride first: the last step
 * before the ride is saved. For now it asks for the ride's name; more about the ride can be
 * added here later. (If the notification's Resume restarts the ride meanwhile, Save ends it
 * at that moment instead.)
 *
 * The name starts as [defaultTitle], all selected, so typing replaces it. [onSave] gets the
 * cleaned name, or null when it was left blank, which keeps the default. [onKeepRiding] (also
 * Back, or tapping outside) closes it without saving.
 */
@Composable
fun FinishRideDialog(
    defaultTitle: String,
    onSave: (title: String?) -> Unit,
    onKeepRiding: () -> Unit,
) {
    // All selected, so typing replaces the default. Kept through a rotation.
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(defaultTitle, selection = TextRange(0, defaultTitle.length)))
    }
    val focus = remember { FocusRequester() }
    val save = { onSave(RideTitle.clean(name.text)) }

    AlertDialog(
        onDismissRequest = onKeepRiding,
        title = { Text("Finish ride") },
        text = {
            // Here rather than outside the dialog: its window is built after the caller's
            // effects run, so the field must exist before focus can be asked for.
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.copy(text = it.text.take(RideTitle.MAX_LENGTH)) },
                label = { Text("Name") },
                singleLine = true,
                supportingText = {
                    Text(
                        if (RideTitle.clean(name.text) == null) "Left blank, it's saved as “$defaultTitle”"
                        else "${name.text.length} / ${RideTitle.MAX_LENGTH}"
                    )
                },
                // The keyboard's ✓ saves: one tap fewer with gloves on.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = save) { Text("Save") } },
        dismissButton = { TextButton(onClick = onKeepRiding) { Text("Keep riding") } },
    )
}

@Preview
@Composable
private fun FinishRideDialogPreview() {
    ActivityStatsTheme { FinishRideDialog(defaultTitle = "Morning ride", onSave = {}, onKeepRiding = {}) }
}
