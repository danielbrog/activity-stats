package com.danielbrog.activitystats.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.danielbrog.activitystats.location.RouteBounds
import com.danielbrog.activitystats.location.RoutePoint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState

/** Zoom for a route with a single point: roughly a few city blocks. */
private const val SINGLE_POINT_ZOOM = 16f

/**
 * A [RouteMap]'s camera, and whether it has been fitted to the route yet. Remember one higher
 * up (with [rememberRouteMapState]) when the map can be thrown away and rebuilt, in a
 * scrolling list say: the rebuilt map then opens where it was, keeping any pan or zoom,
 * instead of fitting the route again.
 */
@Stable
class RouteMapState internal constructor(
    val camera: CameraPositionState,
    private val fittedState: MutableState<Boolean>,
) {
    /** Set once the camera has been fitted to the route, so it is only done the first time. */
    var fitted: Boolean by fittedState
}

/** A [RouteMapState] kept across rotation, like the camera itself. */
@Composable
fun rememberRouteMapState(): RouteMapState {
    val camera = rememberCameraPositionState()
    val fitted = rememberSaveable { mutableStateOf(false) }
    return remember(camera, fitted) { RouteMapState(camera, fitted) }
}

/**
 * A saved route (a ride, or a segment), zoomed to fit, with pins at its start and end titled
 * [startTitle] and [endTitle]. [lines] has one line per stretch between manual pauses, drawn
 * separately so a pause shows as a break. Pan and zoom freely; it doesn't follow anything.
 * See [RouteMapState] for keeping its place when it is rebuilt.
 */
@Composable
fun RouteMap(
    lines: List<List<RoutePoint>>,
    modifier: Modifier = Modifier,
    startTitle: String = "Start",
    endTitle: String = "Finish",
    state: RouteMapState = rememberRouteMapState(),
) {
    if (LocalInspectionMode.current) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("Map", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }

    val latLngLines = remember(lines) { lines.map { line -> line.map { LatLng(it.latitude, it.longitude) } } }
    val points = remember(latLngLines) { latLngLines.flatten() }
    val bounds = remember(lines) { RouteBounds.of(lines.flatten()) }
    var mapLoaded by remember { mutableStateOf(false) }
    val paddingPx = with(LocalDensity.current) { 48.dp.roundToPx() }

    // Fitting to bounds needs the map's size, so wait until it has loaded. Only the first time:
    // a rebuilt map keeps where the rider left it. (The camera's zoom can't tell us: the map
    // raises it to its own minimum as soon as it appears.)
    LaunchedEffect(mapLoaded, bounds) {
        if (!mapLoaded || bounds == null || state.fitted) return@LaunchedEffect
        val update = if (bounds.isSinglePoint) {
            CameraUpdateFactory.newLatLngZoom(LatLng(bounds.south, bounds.west), SINGLE_POINT_ZOOM)
        } else {
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds(LatLng(bounds.south, bounds.west), LatLng(bounds.north, bounds.east)),
                paddingPx,
            )
        }
        state.camera.move(update)
        state.fitted = true
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = state.camera,
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
        onMapLoaded = { mapLoaded = true },
    ) {
        latLngLines.filter { it.size >= 2 }.forEach { line ->
            Polyline(points = line, color = MaterialTheme.colorScheme.primary, width = 14f)
        }
        points.firstOrNull()?.let { start ->
            Marker(
                state = rememberUpdatedMarkerState(position = start),
                title = startTitle,
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
            )
        }
        if (points.size >= 2) {
            Marker(
                state = rememberUpdatedMarkerState(position = points.last()),
                title = endTitle,
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
            )
        }
    }
}
