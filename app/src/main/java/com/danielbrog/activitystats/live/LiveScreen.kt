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
import androidx.compose.material3.FilledTonalButton
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
import com.danielbrog.activitystats.data.rideTitle
import com.danielbrog.activitystats.location.LocationViewModel
import com.danielbrog.activitystats.location.SavedSummary
import com.danielbrog.activitystats.location.TrackingUiState
import com.danielbrog.activitystats.settings.DisplaySettings
import com.danielbrog.activitystats.stats.ActivityStats
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.Format
import com.danielbrog.activitystats.stats.displayTimeMs
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.danielbrog.activitystats.location.GpsPreview
import com.danielbrog.activitystats.location.GpsStatus
import com.danielbrog.activitystats.location.LocationPreview
import com.danielbrog.activitystats.location.PreviewFix
import com.danielbrog.activitystats.location.RoutePoint
import androidx.compose.material3.Surface
import com.danielbrog.activitystats.stats.SpeedDisplay
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme
import kotlinx.coroutines.delay

private enum class PermissionProblem { None, OnlyApproximate, Denied, DeniedPermanently }

/** The main screen: large live numbers, unit and speed/pace toggles, and Start, Pause and Finish. */
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
    // Re-checked whenever the screen comes back, since it can be allowed in system settings.
    var hasLocation by remember { mutableStateOf(context.hasFineLocation()) }
    LifecycleResumeEffect(Unit) {
        hasLocation = context.hasFineLocation()
        onPauseOrDispose { }
    }

    /** Why location isn't allowed, after the user answered the permission request. */
    fun problemAfterAsking(granted: Map<String, Boolean>): PermissionProblem = when {
        context.hasFineLocation() -> PermissionProblem.None
        granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> PermissionProblem.OnlyApproximate
        activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) == false ->
            PermissionProblem.DeniedPermanently
        else -> PermissionProblem.Denied
    }

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
        hasLocation = context.hasFineLocation()
        if (startIfAllowed()) return@rememberLauncherForActivityResult
        problem = problemAfterAsking(granted)
    }

    // Opening Record asks for location once, so the GPS can warm up before Start. Declining
    // isn't asked again until Start is pressed or the app is next opened.
    val openPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasLocation = context.hasFineLocation()
        problem = problemAfterAsking(granted)
    }
    LaunchedEffect(Unit) {
        if (!hasLocation && !state.isTracking && !viewModel.askedForLocationOnOpen) {
            viewModel.askedForLocationOnOpen = true
            openPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    // Warm the GPS up while the screen is visible and nothing is recording, so the first
    // fixes of a ride are already accurate. Stops on leaving the tab or the app.
    val lifecycleOwner = LocalLifecycleOwner.current
    var gps by remember { mutableStateOf<GpsPreview?>(null) }
    val warmUp = hasLocation && !state.isTracking
    LaunchedEffect(warmUp, lifecycleOwner) {
        gps = null
        if (!warmUp) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                @SuppressLint("MissingPermission") // warmUp is only true with ACCESS_FINE_LOCATION
                val updates = LocationPreview.updates(context)
                updates.collect { gps = it }
            } catch (e: SecurityException) {
                // Permission was taken away while the screen was open.
                hasLocation = false
            }
        }
    }

    // Finish pauses the ride and asks for its name before saving. Remembered through a rotation,
    // along with whether the rider had already paused, so Keep riding puts things back as they were.
    var finishing by rememberSaveable { mutableStateOf(false) }
    var pausedBeforeFinish by rememberSaveable { mutableStateOf(false) }
    // Finished another way (the notification's Finish): nothing left to name here.
    LaunchedEffect(state.isTracking) { if (!state.isTracking) finishing = false }
    if (finishing && state.isTracking) {
        FinishRideDialog(
            defaultTitle = rideTitle(state.startedAtMillis),
            onSave = { title ->
                finishing = false
                viewModel.finish(title)
            },
            onKeepRiding = {
                finishing = false
                if (!pausedBeforeFinish) viewModel.resumeAfterFinishCancelled()
            },
        )
    }

    LiveContent(
        state = state,
        settings = settings,
        saved = saved,
        problem = problem,
        notificationsOff = state.isTracking && !context.canPostNotifications(),
        hasLocationPermission = hasLocation,
        gps = gps,
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
        onFinish = onFinish@{
            // A second tap (easy with gloves) before the dialog covers the button: ignore it, or
            // it would see Finish's own pause and Keep riding wouldn't resume.
            if (finishing) return@onFinish
            // Stop the clock while the rider names the ride: typing time isn't riding time. The
            // ride ends when the pause began: now, or earlier if the rider had already paused.
            pausedBeforeFinish = state.isManuallyPaused
            if (!pausedBeforeFinish) viewModel.pause()
            finishing = true
        },
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onUnitChange = viewModel::setUnit,
        onSpeedDisplayChange = viewModel::setSpeedDisplay,
        onAutoPauseChange = viewModel::setAutoPause,
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
    /** The GPS warm-up before recording, or null while recording or without permission. */
    gps: GpsPreview?,
    onStart: () -> Unit,
    /** Finish was pressed: pause and ask for the ride's name. */
    onFinish: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onUnitChange: (DistanceUnit) -> Unit,
    onSpeedDisplayChange: (SpeedDisplay) -> Unit,
    onAutoPauseChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGpsDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = state.stats
    val unit = settings.unit
    val display = settings.speedDisplay
    val manuallyPaused = state.isTracking && state.isManuallyPaused
    val showPaused = manuallyPaused || (settings.autoPause && state.isTracking && stats.isPaused)

    // Tick the clock every second while recording; after Finish, show the final values.
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
    val timeMs = displayTimeMs(
        autoPause = settings.autoPause,
        isTracking = state.isTracking,
        stats = stats,
        startedAtMillis = state.startedAtMillis,
        nowMillis = now,
        pausedMs = state.pausedMsAt(now),
        manuallyPaused = manuallyPaused,
    )

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {
                if (showPaused) {
                    PausedBadge()
                } else {
                    Text(
                        if (state.isTracking) "Recording" else "Ready",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
            AutoPauseChip(settings.autoPause, onAutoPauseChange)
            TextButton(onClick = onOpenGpsDetails) { Text("GPS details") }
        }
        if (!state.isTracking && gps != null) {
            GpsStatusLine(gps.status, unit)
        }

        SettingsRow(settings, onUnitChange, onSpeedDisplayChange)

        PermissionMessage(problem, onOpenSettings)
        if (state.isTracking && state.locationServicesOff) {
            Notice("Location is turned off on this phone. Turn it on in quick settings to get fixes.")
        }
        if (notificationsOff) {
            Notice(
                "Notifications are off, so the recording notification and its Pause and Finish buttons " +
                    "are hidden. Tracking still works with the screen off."
            )
        }

        LiveMap(
            route = state.route,
            hasLocationPermission = hasLocationPermission,
            currentPosition = gps?.fix?.let { RoutePoint(it.latitude, it.longitude) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp)),
        )

        val speedLabel = if (display == SpeedDisplay.Speed) "speed" else "pace"
        Row(modifier = Modifier.fillMaxWidth()) {
            BigStat(
                if (settings.autoPause) "Moving time" else "Time",
                Format.clock(timeMs),
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
            // Whichever time isn't the big one goes here.
            text = (if (settings.autoPause) "Elapsed ${Format.clock(elapsedMs)}" else "Moving ${Format.clock(stats.movingMs)}") +
                " · " + (if (display == SpeedDisplay.Speed) "Max " else "Best ") +
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                val buttonModifier = Modifier.weight(1f).height(64.dp)
                if (manuallyPaused) {
                    Button(onClick = onResume, modifier = buttonModifier) {
                        Text("Resume", style = MaterialTheme.typography.titleLarge)
                    }
                } else {
                    FilledTonalButton(onClick = onPause, modifier = buttonModifier) {
                        Text("Pause", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Button(
                    onClick = onFinish,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = buttonModifier,
                ) { Text("Finish", style = MaterialTheme.typography.titleLarge) }
            }
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

/** Shown in place of "Recording" while paused, by hand or by auto-pause. */
@Composable
private fun PausedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            "PAUSED",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun AutoPauseChip(enabled: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = enabled,
        onClick = { onChange(!enabled) },
        label = { Text("Auto-pause") },
    )
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

/** Before Start: a coloured dot and how the GPS is doing, so you know when to press Start. */
@Composable
private fun GpsStatusLine(status: GpsStatus, unit: DistanceUnit) {
    val dark = isSystemInDarkTheme()
    val color = when (status) {
        is GpsStatus.Ready -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        is GpsStatus.Weak -> if (dark) Color(0xFFFFB74D) else Color(0xFFB26A00)
        GpsStatus.Finding -> MaterialTheme.colorScheme.onSurfaceVariant
        GpsStatus.Off -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            status.text(unit),
            style = MaterialTheme.typography.bodyMedium.withTabularDigits(),
            color = color,
            fontWeight = if (status is GpsStatus.Ready) FontWeight.SemiBold else FontWeight.Normal,
        )
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
            gps = GpsPreview(fix = PreviewFix(0.0, 0.0, accuracyMeters = 4f)),
            onStart = {},
            onFinish = {},
            onPause = {},
            onResume = {},
            onUnitChange = {},
            onSpeedDisplayChange = {},
            onAutoPauseChange = {},
            onOpenSettings = {},
            onOpenGpsDetails = {},
        )
    }
}
