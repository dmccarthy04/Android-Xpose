package com.example.androidxpose.debug

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.androidxpose.data.collectors.BluetoothScanService
import com.example.androidxpose.data.collectors.DeviceStateService
import com.example.androidxpose.data.collectors.LocationService
import com.example.androidxpose.data.collectors.NetworkStateService
import com.example.androidxpose.data.collectors.TemporalCollector
import com.example.androidxpose.data.db.XposeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DB_INSPECTOR"

object DatabaseInspector {

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private fun ts(ms: Long): String = dateFormat.format(Date(ms))

    suspend fun logFullReport(context: Context) = withContext(Dispatchers.IO) {
        val db = XposeDatabase.getInstance(context)
        val now = System.currentTimeMillis()
        val last24h = now - 24 * 60 * 60 * 1000L

        Log.d(TAG, "")
        Log.d(TAG, "═══════════════════════════════════════════════════════")
        Log.d(TAG, "  XPOSE — FULL DIAGNOSTIC REPORT")
        Log.d(TAG, "  Generated : ${ts(now)}")
        Log.d(TAG, "═══════════════════════════════════════════════════════")

        // SERVICE
        Log.d(TAG, "")
        Log.d(TAG, "── [1] COLLECTION SERVICES ──")
        listOf(
            "DeviceStateService"   to DeviceStateService::class.java,
            "NetworkStateService"  to NetworkStateService::class.java,
            "LocationService"      to LocationService::class.java,
            "BluetoothScanService" to BluetoothScanService::class.java
        ).forEach { (name, cls) ->
            Log.d(TAG, "  $name : ${if (isServiceRunning(context, cls)) "RUNNING ✓" else "NOT RUNNING ✗"}")
        }

        // WORK MANAGER
        Log.d(TAG, "")
        Log.d(TAG, "── [2] WORKMANAGER ──")
        val periodicInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("xpose_usage_sync").get()
        val immediateInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("xpose_usage_sync_immediate").get()
        if (periodicInfos.isEmpty()) {
            Log.d(TAG, "  Periodic worker : NOT ENQUEUED ✗")
        } else {
            periodicInfos.forEach { info ->
                val indicator = if (info.state == WorkInfo.State.ENQUEUED ||
                                    info.state == WorkInfo.State.RUNNING) "✓" else "✗"
                Log.d(TAG, "  Periodic worker : ${info.state} $indicator")
            }
        }
        if (immediateInfos.isNotEmpty()) {
            immediateInfos.forEach { info ->
                Log.d(TAG, "  Immediate worker: ${info.state}")
            }
        }

        // PERMISSION AUDIT
        Log.d(TAG, "")
        Log.d(TAG, "── [3] PERMISSION AUDITS ──")
        val latestAudit = db.permissionAuditDao().getLatestAudit()
        if (latestAudit != null) {
            Log.d(TAG, "  Latest audit @ ${ts(latestAudit.timestamp)}")
            Log.d(TAG, "  ForegroundLocation : ${flag(latestAudit.foregroundLocation)}")
            Log.d(TAG, "  BackgroundLocation : ${flag(latestAudit.backgroundLocation)}")
            Log.d(TAG, "  Bluetooth          : ${flag(latestAudit.bluetooth)}")
            Log.d(TAG, "  UsageStats         : ${flag(latestAudit.usageStats)}")
        } else {
            Log.d(TAG, "  No audits recorded yet ✗")
        }

        // LOCATION EVENTS
        val locationTotal = db.locationEventDao().getCount()
        val locationReal  = db.locationEventDao().getFixesSince(0L).size
        val locationNull  = locationTotal - locationReal
        Log.d(TAG, "")
        Log.d(TAG, "── [4] LOCATION EVENTS — $locationTotal total rows ──")
        Log.d(TAG, "  Real fixes (non-null coords) : $locationReal")
        Log.d(TAG, "  Null-coord rows (no GPS fix) : $locationNull")
        if (locationReal > 0) {
            val recent = db.locationEventDao().getFixesSince(last24h).takeLast(5)
            Log.d(TAG, "  Last ${recent.size} real fix(es) in 24h:")
            recent.forEach { e ->
                Log.d(TAG, "    [${ts(e.timestamp)}] lat=${e.latitude} " +
                        "lng=${e.longitude} acc=${e.accuracyMeters}m via=${e.provider}")
            }
        } else {
            Log.d(TAG, "  ⚠ No real GPS fixes yet.")
            Log.d(TAG, "    Emulator: use Extended Controls → Location → Send.")
            Log.d(TAG, "    Real device: ensure GPS is on and tap the Location tile.")
        }

        // BLUETOOTH
        val btTotal = db.bluetoothEventDao().getCount()
        Log.d(TAG, "")
        Log.d(TAG, "── [5] BLUETOOTH EVENTS — $btTotal total rows ──")
        if (btTotal > 0) {
            val recent = db.bluetoothEventDao().getEventsSince(last24h).takeLast(5)
            Log.d(TAG, "  Last ${recent.size} detection(s) in 24h:")
            recent.forEach { e ->
                Log.d(TAG, "    [${ts(e.timestamp)}] hash=${e.addressHash.take(12)}… " +
                        "rssi=${e.rssi} type=${e.deviceType}")
            }
        } else {
            Log.d(TAG, "  0 rows — expected on emulator (no BLE hardware).")
            Log.d(TAG, "  On a real device: scan requires nearby BLE advertising devices.")
        }

        // APP USAGE EVENTS
        val usageTotal   = db.appUsageEventDao().getCount()
        val resumeCount  = db.appUsageEventDao().getResumeEventsSince(last24h).size
        val distinctPkgs = db.appUsageEventDao().getDistinctPackages(last24h).size
        Log.d(TAG, "")
        Log.d(TAG, "── [6] APP USAGE EVENTS — $usageTotal total rows ──")
        Log.d(TAG, "  App launches in last 24h      : $resumeCount")
        Log.d(TAG, "  Distinct packages in last 24h : $distinctPkgs")
        if (usageTotal > 0) {
            val topPackages = db.appUsageEventDao().getLaunchCountsPerPackage(last24h).take(5)
            Log.d(TAG, "  Top apps by launch count:")
            topPackages.forEach { p ->
                Log.d(TAG, "    ${p.packageName} — ${p.launchCount} launch(es)")
            }
        } else {
            Log.d(TAG, "  ⚠ No usage events yet.")
            Log.d(TAG, "    Ensure Usage Stats permission is granted, then use other apps.")
            Log.d(TAG, "    UsageSyncWorker runs immediately on app start — check state above.")
        }

        // DEVICE STATE
        val deviceTotal  = db.deviceStateEventDao().getCount()
        val unlockCount  = db.deviceStateEventDao().getUnlockCountSince(last24h)
        val screenOnCount  = db.deviceStateEventDao().getScreenOnEventsSince(last24h).size
        val screenOffCount = db.deviceStateEventDao().getScreenOffEventsSince(last24h).size
        Log.d(TAG, "")
        Log.d(TAG, "── [7] DEVICE STATE EVENTS — $deviceTotal total rows ──")
        Log.d(TAG, "  Last 24h — SCREEN_ON: $screenOnCount  SCREEN_OFF: $screenOffCount  UNLOCK: $unlockCount")
        if (deviceTotal > 0) {
            val recent = db.deviceStateEventDao().getEventsSince(now - 30 * 60 * 1000L).takeLast(6)
            Log.d(TAG, "  Last ${recent.size} event(s) in past 30 min:")
            recent.forEach { e ->
                Log.d(TAG, "    [${ts(e.timestamp)}] ${e.eventType}")
            }
        } else {
            Log.d(TAG, "  ⚠ No events yet — lock and unlock the device to generate rows.")
        }

        // NETWORK
        val networkTotal  = db.networkEventDao().getCount()
        val transitions   = db.networkEventDao().getTransitionCountSince(last24h)
        Log.d(TAG, "")
        Log.d(TAG, "── [8] NETWORK EVENTS — $networkTotal total rows ──")
        Log.d(TAG, "  Connectivity transitions in last 24h: $transitions")
        if (networkTotal > 0) {
            val recent = db.networkEventDao().getEventsSince(last24h).takeLast(5)
            Log.d(TAG, "  Last ${recent.size} transition(s) in 24h:")
            recent.forEach { e ->
                Log.d(TAG, "    [${ts(e.timestamp)}] ${e.networkType} connected=${e.isConnected}")
            }
        } else {
            Log.d(TAG, "  ⚠ No events yet — toggle Wi-Fi or airplane mode to generate rows.")
        }

        // SNAPSHOT
        Log.d(TAG, "")
        Log.d(TAG, "── [9] TEMPORAL SNAPSHOT (7-day window) ──")
        val temporal = TemporalCollector.collect(context)
        Log.d(TAG, "  Total events across all categories : ${temporal.totalEventCount}")
        Log.d(TAG, "  Active days in window              : ${temporal.activeDaysInWindow}")
        if (temporal.earliestEventMs != null) {
            Log.d(TAG, "  Span : ${ts(temporal.earliestEventMs)} → ${ts(temporal.latestEventMs!!)}")
        } else {
            Log.d(TAG, "  No events recorded in 7-day window yet")
        }
        val peakHour = temporal.hourlyDistribution.maxByOrNull { it.count }
        if (peakHour != null && peakHour.count > 0) {
            Log.d(TAG, "  Peak activity hour : ${peakHour.hour.toString().padStart(2,'0')}:00 " +
                    "(${peakHour.count} events)")
        }
        val nonZeroHours = temporal.hourlyDistribution.filter { it.count > 0 }
        if (nonZeroHours.isNotEmpty()) {
            Log.d(TAG, "  Hourly distribution (non-zero hours):")
            nonZeroHours.forEach { b ->
                val bar = "█".repeat((b.count.coerceAtMost(20)))
                Log.d(TAG, "    ${b.hour.toString().padStart(2,'0')}:00 $bar ${b.count}")
            }
        }

        // SUMMARY
        Log.d(TAG, "")
        Log.d(TAG, "── [10] ROW COUNT SUMMARY ──")
        Log.d(TAG, "  permission_audits  : ${db.permissionAuditDao().getLatestAudit()?.let { "≥1" } ?: "0"}")
        Log.d(TAG, "  location_events    : $locationTotal  (real: $locationReal  null: $locationNull)")
        Log.d(TAG, "  bluetooth_events   : $btTotal")
        Log.d(TAG, "  app_usage_events   : $usageTotal")
        Log.d(TAG, "  device_state_events: $deviceTotal")
        Log.d(TAG, "  network_events     : $networkTotal")
        Log.d(TAG, "  ─────────────────────────────────────────")
        Log.d(TAG, "  TOTAL              : ${locationTotal + btTotal + usageTotal + deviceTotal + networkTotal}")
        Log.d(TAG, "")
        Log.d(TAG, "═══════════════════════════════════════════════════════")
        Log.d(TAG, "  TIP: View raw table data in Android Studio:")
        Log.d(TAG, "  View → Tool Windows → App Inspection → Database Inspector")
        Log.d(TAG, "═══════════════════════════════════════════════════════")
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    private fun flag(value: Boolean) = if (value) "GRANTED" else "DENIED"
}
