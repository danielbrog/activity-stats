package com.danielbrog.activitystats.location

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danielbrog.activitystats.ui.theme.ActivityStatsTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PermissionProblem { None, OnlyApproximate, Denied, DeniedPermanently }

@Composable
fun GPSDisplayScreen(
    modifier: Modifier = Modifier,
    viewModel: LocationViewModel = viewModel(),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    GPSDisplayContent(
        state = state,
        problem = problem,
        notificationsOff = state.isTracking && !context.canPostNotifications(),
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
        onOpenSettings = { context.openAppSettings() },
        modifier = modifier,
    )
}

@Composable
private fun GPSDisplayContent(
    state: TrackingUiState,
    problem: PermissionProblem,
    notificationsOff: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("GPS fixes", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        if (state.isTracking) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Stop", style = MaterialTheme.typography.titleMedium) }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Start", style = MaterialTheme.typography.titleMedium) }
        }

        PermissionMessage(problem, onOpenSettings)

        if (state.isTracking && state.locationServicesOff) {
            Notice("Location is turned off on this phone. Turn it on in quick settings to get fixes.")
        }

        if (notificationsOff) {
            Notice(
                "Notifications are off, so the recording notification and its Stop button are " +
                    "hidden. Tracking still works with the screen off."
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = when {
                state.totalCount > 0 ->
                    "${state.totalCount} fixes · ${state.keptCount} kept · " +
                        "${state.totalCount - state.keptCount} dropped"
                state.isTracking -> "Waiting for the first fix… (can take a minute outdoors)"
                else -> "Press Start to list GPS fixes as they arrive."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(state.fixes, key = { it.seq }) { fix ->
                FixRow(fix)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PermissionMessage(problem: PermissionProblem, onOpenSettings: () -> Unit) {
    val text = when (problem) {
        PermissionProblem.None -> return
        PermissionProblem.OnlyApproximate ->
            "Only approximate location was allowed. Tracking needs precise location to measure distance and speed."
        PermissionProblem.Denied ->
            "Location permission is needed to read GPS fixes. Press Start to be asked again."
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
    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FixRow(fix: GpsData) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val kept = fix.status == FixStatus.Kept
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (kept) 1f else 0.45f)
            .padding(vertical = 8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("#${fix.seq}", fontWeight = FontWeight.Bold)
            if (!kept) {
                Text(
                    text = "dropped · " + when (fix.status) {
                        FixStatus.DroppedAccuracy -> fix.accuracyMeters
                            ?.let { "accuracy %.0f m".format(Locale.US, it) } ?: "no accuracy"
                        else -> "standing still"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(timeFormat.format(Date(fix.timeMillis)), style = mono)
        }
        Text("lat  %.6f".format(Locale.US, fix.latitude), style = mono)
        Text("long %.6f".format(Locale.US, fix.longitude), style = mono)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "acc  " + (fix.accuracyMeters?.let { "%.1f m".format(Locale.US, it) } ?: "—"),
                style = mono,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "speed " + (fix.speedMps?.let {
                    "%.2f m/s (%.1f km/h)".format(Locale.US, it, it * 3.6f)
                } ?: "—"),
                style = mono,
            )
        }
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

@Preview(showBackground = true)
@Composable
private fun GPSDisplayPreview() {
    val now = System.currentTimeMillis()
    ActivityStatsTheme {
        GPSDisplayContent(
            state = TrackingUiState(
                isTracking = true,
                fixes = listOf(
                    GpsData(4, now, 43.653226, -79.383184, 4.2f, 2.85f),
                    GpsData(3, now - 1_000, 43.653220, -79.383180, 5.0f, 0.1f, FixStatus.DroppedStationary),
                    GpsData(2, now - 2_000, 43.653201, -79.383150, 34.0f, 2.70f, FixStatus.DroppedAccuracy),
                    GpsData(1, now - 3_000, 43.653180, -79.383119, 12.6f, null),
                ),
                totalCount = 4,
                keptCount = 2,
            ),
            problem = PermissionProblem.None,
            notificationsOff = false,
            onStart = {},
            onStop = {},
            onOpenSettings = {},
        )
    }
}
