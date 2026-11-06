package com.danielbrog.activitystats.data

import com.danielbrog.activitystats.location.FixStatus
import com.danielbrog.activitystats.location.GpsData
import java.time.Instant
import java.time.ZoneId

fun GpsData.toTrackPoint(activityId: String) = TrackPointEntity(
    activityId = activityId,
    seq = seq,
    timestamp = timeMillis,
    latitude = latitude,
    longitude = longitude,
    altitude = altitudeMeters,
    accuracyMeters = accuracyMeters,
    speedMps = speedMps,
    status = status.name,
)

fun TrackPointEntity.toGpsData() = GpsData(
    seq = seq,
    timeMillis = timestamp,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    speedMps = speedMps,
    // An unknown name can only come from a future version; don't trust that point.
    status = FixStatus.entries.firstOrNull { it.name == status } ?: FixStatus.DroppedAccuracy,
    altitudeMeters = altitude,
)

/**
 * A default title from the local start time: "Morning ride" (5:00–11:59),
 * "Afternoon ride" (12:00–16:59), "Evening ride" (17:00–20:59), otherwise "Night ride".
 */
fun rideTitle(startedAtMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val hour = Instant.ofEpochMilli(startedAtMillis).atZone(zone).hour
    val partOfDay = when (hour) {
        in 5..11 -> "Morning"
        in 12..16 -> "Afternoon"
        in 17..20 -> "Evening"
        else -> "Night"
    }
    return "$partOfDay ride"
}
