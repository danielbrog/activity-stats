package com.danielbrog.activitystats.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danielbrog.activitystats.R
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.SpeedDisplay
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme

/**
 * App settings, opened from the gear on the Profile screen: units, speed or pace, and
 * auto-pause. The only place they are changed; the whole app follows them.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onBack = onBack,
        onUnitChange = viewModel::setUnit,
        onSpeedDisplayChange = viewModel::setSpeedDisplay,
        onAutoPauseChange = viewModel::setAutoPause,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    settings: DisplaySettings,
    onBack: () -> Unit,
    onUnitChange: (DistanceUnit) -> Unit,
    onSpeedDisplayChange: (SpeedDisplay) -> Unit,
    onAutoPauseChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
            }
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.height(16.dp))

        SectionTitle("Units")
        Setting(
            label = "Distance",
            explanation = if (settings.unit ==
                DistanceUnit.Km
            ) {
                "Kilometres, km/h and metres"
            } else {
                "Miles, mph and feet"
            },
        ) {
            // TalkBack reads the spoken name, not the bare abbreviation.
            val units = listOf(
                Triple(DistanceUnit.Km, "km", "Kilometres"),
                Triple(DistanceUnit.Mi, "mi", "Miles"),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                units.forEachIndexed { i, (unit, label, spoken) ->
                    SegmentedButton(
                        selected = settings.unit == unit,
                        onClick = { onUnitChange(unit) },
                        shape = SegmentedButtonDefaults.itemShape(i, units.size),
                    ) { Text(label, modifier = Modifier.semantics { contentDescription = spoken }) }
                }
            }
        }
        Setting(
            label = "Speed or pace",
            explanation = if (settings.speedDisplay == SpeedDisplay.Speed) {
                "Speed, as cyclists usually do"
            } else {
                "Pace: time per km or mile. The ride's chart still shows speed."
            },
        ) {
            val modes = listOf(SpeedDisplay.Speed to "Speed", SpeedDisplay.Pace to "Pace")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = settings.speedDisplay == mode,
                        onClick = { onSpeedDisplayChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(label) }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SectionTitle("Recording")
        // The whole row toggles, so it's easy to hit; the switch only shows the state.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = settings.autoPause, role = Role.Switch, onValueChange = onAutoPauseChange)
                .padding(vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-pause", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Stops the timer when you stop riding. Off: the timer shows total elapsed time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.autoPause, onCheckedChange = null)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp).semantics { heading() },
    )
}

/** One setting: a label, the control, then a line saying what the current choice means. */
@Composable
private fun Setting(label: String, explanation: String, control: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        control()
        Spacer(Modifier.height(4.dp))
        Text(
            explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    ActivityStatsTheme {
        SettingsContent(DisplaySettings(), onBack = {
        }, onUnitChange = {}, onSpeedDisplayChange = {}, onAutoPauseChange = {})
    }
}
