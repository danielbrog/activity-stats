package com.danielbrog.activitystats.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.danielbrog.activitystats.MainActivity
import com.danielbrog.activitystats.R
import com.danielbrog.activitystats.ActivityStatsApp
import com.danielbrog.activitystats.data.RecordingWriter
import com.danielbrog.activitystats.stats.ActivityStats
import com.danielbrog.activitystats.stats.StatsEngine
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import java.util.UUID

/**
 * Foreground service of type `location` that owns GPS updates while recording, so tracking
 * keeps going with the screen off or the app in the background. Shows an ongoing
 * notification with elapsed time, the fix count and a Stop action. Saves the activity and
 * its points to Room while recording, through the app's [RecordingWriter] queue.
 */
class LocationTrackingService : Service() {

    private lateinit var client: FusedLocationProviderClient
    private lateinit var notifications: NotificationManagerCompat
    private var isRunning = false
    private var startedAtMillis = 0L
    private var seq = 0
    private var filter = GpsFilter()
    private var stats = StatsEngine(startedAtMillis = 0L)

    // Saving: fixes are buffered and written in batches, so a crash loses at most ~10 s.
    private val writer by lazy { (application as ActivityStatsApp).recordingWriter }
    private var activityId = ""
    private val pendingFixes = mutableListOf<GpsData>()
    private var lastSavedAtMillis = 0L

    private val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
        .setMinUpdateIntervalMillis(500L)
        .setWaitForAccurateLocation(false)
        .build()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // Classify in arrival order, since the filter compares to the last kept fix.
            val newFixes = result.locations.map { location ->
                val fix = location.toGpsData(++seq)
                fix.copy(status = filter.classify(fix))
            }
            val newlyKept = newFixes.count { it.status == FixStatus.Kept }
            newFixes.forEach(stats::add)
            // Recalculate on every update, dropped fixes included, so current speed falls
            // to 0 when standing still. "Now" is the newest fix's time, the engine's clock.
            val newestMillis = newFixes.maxOf { it.timeMillis }
            val latestStats = stats.snapshot(newestMillis)
            TrackingState.update {
                it.copy(
                    fixes = (newFixes.reversed() + it.fixes).take(MAX_FIXES),
                    route = Route.extend(it.route, newFixes),
                    totalCount = it.totalCount + newFixes.size,
                    keptCount = it.keptCount + newlyKept,
                    stats = latestStats,
                )
            }
            pendingFixes += newFixes
            if (pendingFixes.size >= SAVE_EVERY_FIXES ||
                newestMillis - lastSavedAtMillis >= SAVE_EVERY_MS
            ) {
                savePending(latestStats, newestMillis)
            }
            postNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        notifications = NotificationManagerCompat.from(this)
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Tracking")
                .setDescription("Shown while an activity is being recorded")
                .setShowBadge(false)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTracking()
            else -> startTracking()
        }
        // A recording can't be resumed after the system kills the process, so don't restart.
        return START_NOT_STICKY
    }

    private fun startTracking() {
        if (isRunning) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        startedAtMillis = System.currentTimeMillis()
        seq = 0
        filter = GpsFilter()
        stats = StatsEngine(startedAtMillis)
        activityId = UUID.randomUUID().toString()
        pendingFixes.clear()
        lastSavedAtMillis = startedAtMillis
        val id = activityId
        val startedAt = startedAtMillis
        writer.enqueue { startActivity(id, startedAt) }
        // Must be called promptly after startForegroundService().
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(distanceMeters = 0.0, kept = 0, total = 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
        isRunning = true

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        TrackingState.update {
            TrackingUiState(
                isTracking = true,
                startedAtMillis = startedAtMillis,
                locationServicesOff = !LocationManagerCompat.isLocationEnabled(locationManager),
            )
        }
        requestUpdates()
    }

    @SuppressLint("MissingPermission") // checked in startTracking()
    private fun requestUpdates() {
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun stopTracking() {
        if (isRunning) endRecording()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (isRunning) endRecording()
        super.onDestroy()
    }

    /**
     * Stops GPS, saves the last fixes and finalizes the activity. Stats are frozen at this
     * moment: full elapsed time, no current speed.
     */
    private fun endRecording() {
        client.removeLocationUpdates(callback)
        isRunning = false
        val endedAt = System.currentTimeMillis()
        val finalStats = stats.snapshot(endedAt).copy(currentSpeedMps = 0.0)
        savePending(finalStats, endedAt)
        val id = activityId
        writer.enqueue { finishActivity(id, endedAt, finalStats) }
        TrackingState.update { it.copy(isTracking = false, stats = finalStats) }
    }

    /** Queues the buffered fixes and the stats so far for saving. */
    private fun savePending(latestStats: ActivityStats, nowMillis: Long) {
        val batch = pendingFixes.toList()
        pendingFixes.clear()
        lastSavedAtMillis = nowMillis
        val id = activityId
        writer.enqueue { savePoints(id, batch, latestStats, nowMillis) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS checked below
    private fun postNotification() {
        if (!isRunning) return
        // Without notification permission (Android 13+) the notification is hidden, but the
        // service keeps running; skip updates rather than crash.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        val state = TrackingState.state.value
        notifications.notify(
            NOTIFICATION_ID,
            buildNotification(
                distanceMeters = state.stats.distanceMeters,
                kept = state.keptCount,
                total = state.totalCount,
            ),
        )
    }

    private fun buildNotification(distanceMeters: Double, kept: Int, total: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_tracking)
            .setContentTitle("Recording")
            .setContentText(
                "%.2f km · %d kept of %d %s".format(
                    Locale.US, distanceMeters / 1_000.0, kept, total, if (total == 1) "fix" else "fixes",
                )
            )
            .setWhen(startedAtMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun Location.toGpsData(seq: Int) = GpsData(
        seq = seq,
        timeMillis = time,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        speedMps = if (hasSpeed()) speed else null,
        altitudeMeters = if (hasAltitude()) altitude else null,
    )

    companion object {
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "com.danielbrog.activitystats.action.START_TRACKING"
        private const val ACTION_STOP = "com.danielbrog.activitystats.action.STOP_TRACKING"
        private const val MAX_FIXES = 1_000
        private const val SAVE_EVERY_FIXES = 10
        private const val SAVE_EVERY_MS = 10_000L

        /** Call from the foreground (e.g. a button press) after fine location is granted. */
        @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationTrackingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocationTrackingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
