package com.danielbrog.activitystats.live

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.danielbrog.activitystats.location.RoutePoint
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.CancellationException

/** Zoom used when the map first centres on you: roughly a few city blocks. */
private const val FOLLOW_ZOOM = 16f

/**
 * Google Map showing the current route as a line. Follows your latest position until you
 * drag the map; then a Re-centre button turns following back on.
 */
@Composable
fun LiveMap(
    route: List<RoutePoint>,
    hasLocationPermission: Boolean,
    modifier: Modifier = Modifier,
) {
    // Android Studio's preview can't draw a real map, so show a stand-in there.
    if (LocalInspectionMode.current) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("Map", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }

    val context = LocalContext.current
    val cameraPositionState = rememberCameraPositionState()
    var following by rememberSaveable { mutableStateOf(true) }
    val points = remember(route) { route.map { LatLng(it.latitude, it.longitude) } }
    val latest = points.lastOrNull()

    // Dragging or pinching the map stops following.
    LaunchedEffect(cameraPositionState.isMoving) {
        if (cameraPositionState.isMoving &&
            cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE
        ) {
            following = false
        }
    }

    // Keep the newest point in view while following.
    LaunchedEffect(latest, following) {
        if (!following || latest == null) return@LaunchedEffect
        val zoom = cameraPositionState.position.zoom.takeIf { it >= 10f } ?: FOLLOW_ZOOM
        try {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(latest, zoom), 500)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A gesture interrupted the animation; following has already been switched off.
        }
    }

    // Before any route exists, centre on the phone's last known position.
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission && points.isEmpty()) {
            centreOnLastKnownLocation(context) { here ->
                if (following && points.isEmpty()) {
                    cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(here, FOLLOW_ZOOM))
                }
            }
        }
    }

    Box(modifier = modifier) {
        GoogleMap(
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = false,
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
            ),
        ) {
            if (points.size >= 2) {
                Polyline(points = points, color = MaterialTheme.colorScheme.primary, width = 14f)
            }
        }
        if (!following) {
            FilledTonalButton(
                onClick = { following = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) { Text("Re-centre") }
        }
    }
}

@SuppressLint("MissingPermission") // only called when location permission is granted
private fun centreOnLastKnownLocation(context: android.content.Context, onFound: (LatLng) -> Unit) {
    LocationServices.getFusedLocationProviderClient(context).lastLocation
        .addOnSuccessListener { location ->
            if (location != null) onFound(LatLng(location.latitude, location.longitude))
        }
}
