package com.danielbrog.activitystats.live

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danielbrog.activitystats.location.LocationViewModel
import com.danielbrog.activitystats.location.SavedSummary
import com.danielbrog.activitystats.location.TrackingUiState
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.stats.ActivityStats
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.Format
import com.danielbrog.activitystats.stats.SpeedDisplay
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme
import kotlinx.coroutines.delay

private enum class PermissionProblem { None, OnlyApproximate, Denied, DeniedPermanently }

/** The main screen: large live numbers, unit and speed/pace toggles, and Start/Stop. */
@Composable
fun LiveScreen(
    onOpenGpsDetails: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocationViewModel = viewModel(),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saved by viewModel.savedSummary.collectAsStateWithLifecycle()
    var problem by remember { mutableStateOf(PermissionProblem.None) }
    // Ask for notification permission once per screen visit, not on every Start.
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }

    @SuppressLint("MissingPermission") // only called after checking ACCESS_FINE_LOCATION
    fun startIfAllowed(): Boolean {
        if (!context.hasFineLocation()) return false
        problem = PermissionProblem.None
        viewModel.start()
        return true
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (startIfAllowed()) return@rememberLauncherForActivityResult
        problem = when {
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true ->
                PermissionProblem.OnlyApproximate
            activity?.shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == false -> PermissionProblem.DeniedPermanently
            else -> PermissionProblem.Denied
        }
    }

    LiveContent(
        state = state,
        settings = settings,
        saved = saved,
        problem = problem,
        notificationsOff = state.isTracking && !context.canPostNotifications(),
        // Re-checked on every recomposition, so the map's blue dot appears once allowed.
        hasLocationPermission = context.hasFineLocation(),
        onStart = {
            val missing = buildList {
                if (!context.hasFineLocation()) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !askedForNotifications && !context.canPostNotifications()
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (missing.isEmpty()) {
                startIfAllowed()
            } else {
                askedForNotifications = true
                permissionLauncher.launch(missing.toTypedArray())
            }
        },
        onStop = viewModel::stop,
        onUnitChange = viewModel::setUnit,
        onSpeedDisplayChange = viewModel::setSpeedDisplay,
        onOpenSettings = { context.openAppSettings() },
        onOpenGpsDetails = onOpenGpsDetails,
        modifier = modifier,
    )
}

@Composable
private fun LiveContent(
    state: TrackingUiState,
    settings: DisplaySettings,
    saved: SavedSummary?,
    problem: PermissionProblem,
    notificationsOff: Boolean,
    hasLocationPermission: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUnitChange: (DistanceUnit) -> Unit,
    onSpeedDisplayChange: (SpeedDisplay) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGpsDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = state.stats
    val unit = settings.unit
    val display = settings.speedDisplay

    // Tick the elapsed time every second while recording; after Stop, show the final value.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isTracking) {
        while (state.isTracking) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val elapsedMs = when {
        state.isTracking -> now - state.startedAtMillis
        else -> stats.elapsedMs
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (state.isTracking) "Recording" else "Ready",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenGpsDetails) { Text("GPS details") }
        }

        SettingsRow(settings, onUnitChange, onSpeedDisplayChange)

        PermissionMessage(problem, onOpenSettings)
        if (state.isTracking && state.locationServicesOff) {
            Notice("Location is turned off on this phone. Turn it on in quick settings to get fixes.")
        }
        if (notificationsOff) {
            Notice(
                "Notifications are off, so the recording notification and its Stop button " +
                    "are hidden. Tracking still works with the screen off."
            )
        }

        LiveMap(
            route = state.route,
            hasLocationPermission = hasLocationPermission,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp)),
        )

        val speedLabel = if (display == SpeedDisplay.Speed) "speed" else "pace"
        Row(modifier = Modifier.fillMaxWidth()) {
            BigStat(
                "Time",
                Format.clock(elapsedMs),
                MaterialTheme.typography.displayMedium,
                Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            BigStat(
                "Distance",
                Format.distance(stats.distanceMeters, unit),
                MaterialTheme.typography.displayMedium,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            BigStat(
                "Current $speedLabel",
                Format.speedOrPace(stats.currentSpeedMps, unit, display),
                MaterialTheme.typography.displaySmall,
                Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            BigStat(
                "Average $speedLabel",
                Format.speedOrPace(stats.avgSpeedMps, unit, display),
                MaterialTheme.typography.displaySmall,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Moving ${Format.clock(stats.movingMs)} · " +
                (if (display == SpeedDisplay.Speed) "Max " else "Best ") +
                Format.speedOrPace(stats.maxSpeedMps, unit, display),
            style = MaterialTheme.typography.bodyLarge.withTabularDigits(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.isTracking && saved != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Saved \"${saved.title}\" · ${saved.count} " +
                    (if (saved.count == 1) "ride" else "rides") + " on this phone",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(12.dp))

        if (state.isTracking) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("Stop", style = MaterialTheme.typography.titleLarge) }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("Start", style = MaterialTheme.typography.titleLarge) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRow(
    settings: DisplaySettings,
    onUnitChange: (DistanceUnit) -> Unit,
    onSpeedDisplayChange: (SpeedDisplay) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
            val units = listOf(DistanceUnit.Km to "km", DistanceUnit.Mi to "mi")
            units.forEachIndexed { i, (unit, label) ->
                SegmentedButton(
                    selected = settings.unit == unit,
                    onClick = { onUnitChange(unit) },
                    shape = SegmentedButtonDefaults.itemShape(i, units.size),
                ) { Text(label) }
            }
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
            val modes = listOf(SpeedDisplay.Speed to "Speed", SpeedDisplay.Pace to "Pace")
            modes.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    selected = settings.speedDisplay == mode,
                    onClick = { onSpeedDisplayChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(label) }
            }
        }
    }
}

/** A label over a large number, with the unit set smaller after it ("24.3" + "km/h"). */
@Composable
private fun BigStat(label: String, text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val value = text.substringBefore(' ')
    val unit = text.substringAfter(' ', missingDelimiterValue = "")
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = style.withTabularDigits(),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
}

/** Fixed-width digits, so numbers don't shift sideways as they change. */
private fun TextStyle.withTabularDigits() = copy(fontFeatureSettings = "tnum")

@Composable
private fun PermissionMessage(problem: PermissionProblem, onOpenSettings: () -> Unit) {
    val text = when (problem) {
        PermissionProblem.None -> return
        PermissionProblem.OnlyApproximate ->
            "Only approximate location was allowed. Tracking needs precise location to measure distance and speed."
        PermissionProblem.Denied ->
            "Location permission is needed to record. Press Start to be asked again."
        PermissionProblem.DeniedPermanently ->
            "Location permission is turned off for this app. Allow precise location in the app's settings."
    }
    Notice(text)
    if (problem != PermissionProblem.Denied) {
        OutlinedButton(onClick = onOpenSettings) { Text("Open app settings") }
    }
}

@Composable
private fun Notice(text: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Context.hasFineLocation() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.canPostNotifications() =
    NotificationManagerCompat.from(this).areNotificationsEnabled()

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun LivePreview() {
    ActivityStatsTheme {
        LiveContent(
            state = TrackingUiState(
                isTracking = false,
                startedAtMillis = 1L,
                stats = ActivityStats(
                    distanceMeters = 12_430.0,
                    elapsedMs = 1_954_000L,
                    movingMs = 1_801_000L,
                    currentSpeedMps = 7.4,
                    avgSpeedMps = 6.9,
                    maxSpeedMps = 11.2,
                ),
            ),
            settings = DisplaySettings(),
            saved = SavedSummary(title = "Morning ride", count = 3),
            problem = PermissionProblem.None,
            notificationsOff = false,
            hasLocationPermission = true,
            onStart = {},
            onStop = {},
            onUnitChange = {},
            onSpeedDisplayChange = {},
            onOpenSettings = {},
            onOpenGpsDetails = {},
        )
    }
}
