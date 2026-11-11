package com.danielbrog.activitystats.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState

/** Zoom for a ride with a single point: roughly a few city blocks. */
private const val SINGLE_POINT_ZOOM = 16f

/**
 * A saved ride's whole route, zoomed to fit, with start and finish markers. [route] has one
 * line per stretch between manual pauses, drawn separately so a pause shows as a break.
 * Pan and zoom freely; it doesn't follow anything.
 */
@Composable
fun RideMap(route: List<List<RoutePoint>>, modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("Map", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }

    val lines = remember(route) { route.map { line -> line.map { LatLng(it.latitude, it.longitude) } } }
    val points = remember(lines) { lines.flatten() }
    val bounds = remember(route) { RouteBounds.of(route.flatten()) }
    val cameraPositionState = rememberCameraPositionState()
    var mapLoaded by remember { mutableStateOf(false) }
    val paddingPx = with(LocalDensity.current) { 48.dp.roundToPx() }

    // Fitting to bounds needs the map's size, so wait until it has loaded.
    LaunchedEffect(mapLoaded, bounds) {
        if (!mapLoaded || bounds == null) return@LaunchedEffect
        val update = if (bounds.isSinglePoint) {
            CameraUpdateFactory.newLatLngZoom(LatLng(bounds.south, bounds.west), SINGLE_POINT_ZOOM)
        } else {
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds(LatLng(bounds.south, bounds.west), LatLng(bounds.north, bounds.east)),
                paddingPx,
            )
        }
        cameraPositionState.move(update)
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
        onMapLoaded = { mapLoaded = true },
    ) {
        lines.filter { it.size >= 2 }.forEach { line ->
            Polyline(points = line, color = MaterialTheme.colorScheme.primary, width = 14f)
        }
        points.firstOrNull()?.let { start ->
            Marker(
                state = rememberUpdatedMarkerState(position = start),
                title = "Start",
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
            )
        }
        if (points.size >= 2) {
            Marker(
                state = rememberUpdatedMarkerState(position = points.last()),
                title = "Finish",
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
            )
        }
    }
}
