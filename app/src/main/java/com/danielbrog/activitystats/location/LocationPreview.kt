package com.danielbrog.activitystats.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.danielbrog.activitystats.stats.DistanceUnit
import com.danielbrog.activitystats.stats.Format
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Where the phone is during warm-up, and how sure the GPS is about it. */
data class PreviewFix(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy radius in metres, or null if the provider gave none. */
    val accuracyMeters: Float?,
)

/** The GPS as seen before recording: whether location is on, and the latest fix. */
data class GpsPreview(
    /** False when location is switched off in the phone's settings. */
    val locationEnabled: Boolean = true,
    /** The newest fix, or null before the first. Kept when the signal drops, for the map. */
    val fix: PreviewFix? = null,
    /** False when the provider reports it can't get a position right now. */
    val available: Boolean = true,
) {
    val status: GpsStatus get() = GpsStatus.of(this)
}

/** What the Record screen says about the GPS before Start. */
sealed interface GpsStatus {
    /** Location is switched off on the phone. */
    data object Off : GpsStatus

    /** No position yet, or the signal was lost. */
    data object Finding : GpsStatus

    /** A position, but too rough for recording yet; null when the provider gave no accuracy. */
    data class Weak(val accuracyMeters: Float?) : GpsStatus

    /** Accurate enough that recording would keep the fixes. */
    data class Ready(val accuracyMeters: Float) : GpsStatus

    /** The words shown on screen, with accuracy in metres or feet to match [unit]. */
    fun text(unit: DistanceUnit): String = when (this) {
        Off -> "Location is turned off"
        Finding -> "Finding GPS…"
        is Weak -> if (accuracyMeters == null) "GPS weak" else "GPS weak · ±${accuracy(accuracyMeters, unit)}"
        is Ready -> "GPS ready · ±${accuracy(accuracyMeters, unit)}"
    }

    companion object {
        /**
         * The worst accuracy that counts as ready: the same limit [GpsFilter] uses, so "ready"
         * means a recording would keep the fixes from the first second.
         */
        const val READY_ACCURACY_METERS = 20f

        fun of(preview: GpsPreview): GpsStatus {
            if (!preview.locationEnabled) return Off
            val fix = preview.fix
            if (fix == null || !preview.available) return Finding
            val accuracy = fix.accuracyMeters ?: return Weak(null)
            return if (accuracy <= READY_ACCURACY_METERS) Ready(accuracy) else Weak(accuracy)
        }

        /** At least 1, so a very good fix never reads "±0 m". */
        private fun accuracy(meters: Float, unit: DistanceUnit): String =
            Format.shortLength(meters.roundToInt().coerceAtLeast(1).toDouble(), unit)
    }
}

/**
 * Warms the GPS up before recording: high-accuracy fixes once a second, like a recording,
 * but nothing is saved. Updates run only while the flow is collected and stop when collection
 * ends, so the Record screen collects it just while it is visible and not recording.
 */
object LocationPreview {

    private val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
        .setMinUpdateIntervalMillis(500L)
        .setWaitForAccurateLocation(false)
        .build()

    /** The GPS state, updated on every fix and when location is switched on or off. */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun updates(context: Context): Flow<GpsPreview> = callbackFlow {
        val appContext = context.applicationContext
        val client = LocationServices.getFusedLocationProviderClient(appContext)
        val locationManager = appContext.getSystemService(LocationManager::class.java)

        // Callbacks arrive on the main thread, one at a time, so a plain var is safe here.
        var current = GpsPreview(locationEnabled = LocationManagerCompat.isLocationEnabled(locationManager))
        fun publish(next: GpsPreview) {
            current = next
            trySend(next)
        }
        publish(current)

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                publish(
                    current.copy(
                        fix = PreviewFix(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                        ),
                        available = true,
                    ),
                )
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                publish(current.copy(available = availability.isLocationAvailable))
            }
        }

        // Location switched on or off from quick settings while the screen is open.
        val providersChanged = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                publish(current.copy(locationEnabled = LocationManagerCompat.isLocationEnabled(locationManager)))
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            providersChanged,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose {
            client.removeLocationUpdates(callback)
            appContext.unregisterReceiver(providersChanged)
        }
    }
}
