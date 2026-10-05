package com.example.androidxpose.data.collectors

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.androidxpose.data.db.LocationEvent
import com.example.androidxpose.data.db.XposeDatabase
import com.example.androidxpose.permissions.PermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "LocationService"
private const val CHANNEL_ID = "xpose_location"
private const val NOTIFICATION_ID = 1003
private const val MIN_TIME_MS    = 60_000L
private const val MIN_DISTANCE_M = 0f

class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d(TAG, "Fix: lat=${location.latitude} lng=${location.longitude} " +
                    "acc=${location.accuracy}m spd=${location.speed}mps " +
                    "brg=${location.bearing}deg via=${location.provider}")
            persistEvent(location)
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "Provider enabled: $provider")
            registerUpdates()
        }

        override fun onProviderDisabled(provider: String) {
            Log.d(TAG, "Provider disabled: $provider")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        registerUpdates()
        Log.d(TAG, "LocationService started — 60s time-only updates")
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(locationListener)
        Log.d(TAG, "LocationService stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun registerUpdates() {
        if (!PermissionManager.hasForegroundLocation(this)) {
            Log.w(TAG, "Foreground location not granted — cannot register updates")
            return
        }
        try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { locationManager.isProviderEnabled(it) }
                .also { if (it.isEmpty()) { Log.w(TAG, "No providers enabled"); return } }
                .forEach { provider ->
                    locationManager.requestLocationUpdates(
                        provider, MIN_TIME_MS, MIN_DISTANCE_M,
                        locationListener, Looper.getMainLooper()
                    )
                    Log.d(TAG, "Updates registered: $provider")
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission revoked during registerUpdates: ${e.message}")
        }
    }

    private fun persistEvent(location: Location) {
        val event = LocationEvent(
            latitude          = location.latitude,
            longitude         = location.longitude,
            accuracyMeters    = location.accuracy,
            provider          = location.provider,
            speedMps          = if (location.hasSpeed()) location.speed else null,
            bearingDegrees    = if (location.hasBearing()) location.bearing else null,
            foregroundGranted = PermissionManager.hasForegroundLocation(this),
            backgroundGranted = PermissionManager.hasBackgroundLocation(this),
            timestamp         = location.time
        )
        scope.launch {
            try {
                XposeDatabase.getInstance(this@LocationService).locationEventDao().insert(event)
                Log.d(TAG, "LocationEvent persisted: lat=${location.latitude} lng=${location.longitude}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist LocationEvent: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Location Monitoring",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Tracks location updates for metadata transparency"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Xpose")
            .setContentText("Monitoring location metadata")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
}