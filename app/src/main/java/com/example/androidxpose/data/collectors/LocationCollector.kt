package com.example.androidxpose.data.collectors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.androidxpose.data.db.LocationEvent
import com.example.androidxpose.data.db.XposeDatabase
import com.example.androidxpose.permissions.PermissionManager
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "LocationCollector"
private const val ACTIVE_FIX_TIMEOUT_MS = 15_000L

data class LocationSnapshot(
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val provider: String?,
    val foregroundGranted: Boolean,
    val backgroundGranted: Boolean,
    val isActiveFix: Boolean,
    val timestamp: Long
)

object LocationCollector {

    @SuppressLint("MissingPermission")
    suspend fun collectAndPersist(context: Context): LocationSnapshot {
        val foregroundGranted = PermissionManager.hasForegroundLocation(context)
        val backgroundGranted = PermissionManager.hasBackgroundLocation(context)

        if (!foregroundGranted) {
            val snapshot = LocationSnapshot(
                latitude = null, longitude = null, accuracyMeters = null,
                speedMps = null, bearingDegrees = null, provider = null,
                foregroundGranted = false, backgroundGranted = backgroundGranted,
                isActiveFix = false, timestamp = System.currentTimeMillis()
            )
            return snapshot
        }

        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val activeFix = requestActiveFix(locationManager)

        val resolved = activeFix ?: getBestCachedFix(locationManager)

        val snapshot = LocationSnapshot(
            latitude       = resolved?.latitude,
            longitude      = resolved?.longitude,
            accuracyMeters = resolved?.accuracy,
            speedMps       = resolved?.takeIf { it.hasSpeed() }?.speed,
            bearingDegrees = resolved?.takeIf { it.hasBearing() }?.bearing,
            provider       = resolved?.provider,
            foregroundGranted = true,
            backgroundGranted = backgroundGranted,
            isActiveFix    = activeFix != null,
            timestamp      = resolved?.time ?: System.currentTimeMillis()
        )

        val event = LocationEvent(
            latitude          = snapshot.latitude,
            longitude         = snapshot.longitude,
            accuracyMeters    = snapshot.accuracyMeters,
            provider          = snapshot.provider,
            speedMps          = snapshot.speedMps,
            bearingDegrees    = snapshot.bearingDegrees,
            foregroundGranted = snapshot.foregroundGranted,
            backgroundGranted = snapshot.backgroundGranted,
            timestamp         = snapshot.timestamp
        )

        try {
            XposeDatabase.getInstance(context).locationEventDao().insert(event)
            if (snapshot.latitude != null) {
                Log.d(TAG, "LocationEvent persisted: lat=${snapshot.latitude} " +
                        "lng=${snapshot.longitude} acc=${snapshot.accuracyMeters}m " +
                        "active=${snapshot.isActiveFix} via=${snapshot.provider}")
            } else {
                Log.w(TAG, "LocationEvent persisted with null coordinates — " +
                        "no fix available. On emulator: Extended Controls → Location → Send.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist LocationEvent: ${e.message}")
        }

        return snapshot
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestActiveFix(
        locationManager: LocationManager
    ): Location? = suspendCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        var resolved = false

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!resolved) {
                    resolved = true
                    handler.post { locationManager.removeUpdates(this) }
                    Log.d(TAG, "Active fix received: acc=${location.accuracy}m via=${location.provider}")
                    continuation.resume(location)
                }
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER
        ).filter { locationManager.isProviderEnabled(it) }

        if (providers.isEmpty()) {
            Log.w(TAG, "No providers enabled for active fix")
            continuation.resume(null)
            return@suspendCoroutine
        }

        try {
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider, 0L, 0f, listener, Looper.getMainLooper()
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission revoked before requestLocationUpdates: ${e.message}")
            if (!resolved) { resolved = true; continuation.resume(null) }
            return@suspendCoroutine
        }

        handler.postDelayed({
            if (!resolved) {
                resolved = true
                try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                Log.w(TAG, "Active fix timed out after ${ACTIVE_FIX_TIMEOUT_MS}ms — falling back to cache")
                continuation.resume(null)
            }
        }, ACTIVE_FIX_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun getBestCachedFix(locationManager: LocationManager): Location? {
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
            .filter { locationManager.isProviderEnabled(it) }
            .mapNotNull { locationManager.getLastKnownLocation(it) }
            .minByOrNull { it.accuracy }
            .also { fix ->
                if (fix != null) {
                    val ageSeconds = (System.currentTimeMillis() - fix.time) / 1000
                    Log.w(TAG, "Using cached fix: acc=${fix.accuracy}m age=${ageSeconds}s via=${fix.provider}")
                }
            }
    }
}