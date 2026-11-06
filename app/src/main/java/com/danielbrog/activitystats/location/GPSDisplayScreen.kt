package com.danielbrog.activitystats.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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

    // GPS updates stop when the screen turns off until the foreground service (M3) exists,
    // so keep the screen on while recording.
    val view = LocalView.current
    DisposableEffect(state.isTracking) {
        view.keepScreenOn = state.isTracking
        onDispose { view.keepScreenOn = false }
    }

    GPSDisplayContent(
        state = state,
        problem = problem,
        onStart = {
            if (!startIfAllowed()) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
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

        Spacer(Modifier.height(12.dp))
        Text(
            text = when {
                state.fixes.isNotEmpty() -> "${state.fixes.size} fixes, newest first"
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
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("#${fix.seq}", fontWeight = FontWeight.Bold)
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
                    GpsData(3, now, 43.653226, -79.383184, 4.2f, 2.85f),
                    GpsData(2, now - 1_000, 43.653201, -79.383150, 5.0f, 2.70f),
                    GpsData(1, now - 2_000, 43.653180, -79.383119, 12.6f, null),
                ),
            ),
            problem = PermissionProblem.None,
            onStart = {},
            onStop = {},
            onOpenSettings = {},
        )
    }
}
