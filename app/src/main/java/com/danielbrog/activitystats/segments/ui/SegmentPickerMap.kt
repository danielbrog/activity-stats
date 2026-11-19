package com.danielbrog.activitystats.segments.ui

import android.graphics.Point
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
import com.danielbrog.activitystats.stats.Geo
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
import kotlin.math.roundToInt

/** A tap counts as on the route if it is within this much of it on screen (about a fingertip). */
private val TAP_REACH = 24.dp

/** How long the map takes to glide to a new window. */
private const val FOCUS_GLIDE_MS = 500

/** Never ask for a tap closer than this on the ground, however far you zoom in: GPS isn't finer. */
private const val MIN_TAP_RADIUS_METERS = 15.0

/**
 * The whole ride in a muted colour with the [picked] part drawn over it, and pins at its
 * [start] and [end]. Fits [focus] (index range into [points]: the slider's window) on
 * screen, and glides to it again when it changes; in between, pan and zoom freely.
 *
 * [points] is every point of the ride; [stretches] and [picked] are index ranges into it, one
 * per stretch between manual pauses (as on the ride's own map), so a new part is drawn from
 * slices of the same list. A tap goes to [onTap] with how far a fingertip reaches on the
 * ground at the current zoom.
 */
@Composable
fun SegmentPickerMap(
    points: List<RoutePoint>,
    stretches: List<IntRange>,
    picked: List<IntRange>,
    start: RoutePoint,
    end: RoutePoint,
    focus: IntRange,
    onTap: (point: RoutePoint, radiusMeters: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("Map", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }

    // Converted once per ride; each line below is a slice of this list.
    val latLngs = remember(points) { points.map { it.toLatLng() } }
    val bounds = remember(points, focus) { RouteBounds.of(points.subList(focus.first, focus.last + 1)) }
    val cameraPositionState = rememberCameraPositionState()
    var mapLoaded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val paddingPx = with(density) { 48.dp.roundToPx() }
    val reachPx = with(density) { TAP_REACH.toPx() }

    // Fitting to bounds needs the map's size, so wait until it has loaded. The first fit is
    // instant; later ones glide, so it's clear where the map went.
    var fitted by remember { mutableStateOf(false) }
    LaunchedEffect(mapLoaded, bounds) {
        if (!mapLoaded || bounds == null || bounds.isSinglePoint) return@LaunchedEffect
        val update = CameraUpdateFactory.newLatLngBounds(
            LatLngBounds(LatLng(bounds.south, bounds.west), LatLng(bounds.north, bounds.east)),
            paddingPx,
        )
        if (fitted) cameraPositionState.animate(update, FOCUS_GLIDE_MS) else cameraPositionState.move(update)
        fitted = true
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
        onMapLoaded = { mapLoaded = true },
        onMapClick = { tap ->
            onTap(RoutePoint(tap.latitude, tap.longitude), reachMeters(cameraPositionState, tap, reachPx))
        },
    ) {
        stretches.filter { it.last > it.first }.forEach { run ->
            Polyline(points = latLngs.subList(run.first, run.last + 1), color = MaterialTheme.colorScheme.outline, width = 10f)
        }
        picked.filter { it.last > it.first }.forEach { run ->
            Polyline(
                points = latLngs.subList(run.first, run.last + 1),
                color = MaterialTheme.colorScheme.primary,
                width = 16f,
                zIndex = 1f,
            )
        }
        Marker(
            state = rememberUpdatedMarkerState(position = start.toLatLng()),
            title = "Segment start",
            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
        )
        Marker(
            state = rememberUpdatedMarkerState(position = end.toLatLng()),
            title = "Segment end",
            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
        )
    }
}

private fun RoutePoint.toLatLng() = LatLng(latitude, longitude)

/** How many metres on the ground [reachPx] covers at [tap], at the map's current zoom. */
private fun reachMeters(camera: CameraPositionState, tap: LatLng, reachPx: Float): Double {
    val projection = camera.projection ?: return MIN_TAP_RADIUS_METERS
    val onScreen = projection.toScreenLocation(tap)
    val reached = projection.fromScreenLocation(Point(onScreen.x + reachPx.roundToInt(), onScreen.y))
    val meters = Geo.distanceMeters(tap.latitude, tap.longitude, reached.latitude, reached.longitude)
    return maxOf(meters, MIN_TAP_RADIUS_METERS)
}
