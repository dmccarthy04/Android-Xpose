package com.example.androidxpose.data.collectors

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.example.androidxpose.data.db.BluetoothEvent
import com.example.androidxpose.data.db.XposeDatabase
import com.example.androidxpose.permissions.PermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest
import kotlin.math.pow

private const val TAG = "BluetoothScanService"
private const val CHANNEL_ID = "xpose_bluetooth_scan"
private const val NOTIFICATION_ID = 1004
private const val SCAN_DURATION_MS  = 15_000L
private const val SCAN_INTERVAL_MS  = 120_000L

const val ACTION_MANUAL_SCAN = "com.example.androidxpose.ACTION_BT_MANUAL_SCAN"
private val AUDIO_UUIDS = setOf("0000110b", "0000110a", "0000110e", "0000111e",
    "0000111f", "00001108", "00001112")
private val FITNESS_UUIDS = setOf("0000180d", "00001816", "00001818", "00001814")
private val HEALTH_UUIDS = setOf("00001809", "00001810", "00001808", "0000180f")
private val PROXIMITY_UUIDS = setOf("00001802", "00001803", "00001804")
private val HID_UUIDS = setOf("00001812", "00001124")

class BluetoothScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val windowResults = mutableMapOf<String, Pair<Int, ScanResult>>()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val hash = hashAddress(result.device.address)
                val existing = windowResults[hash]
                if (existing == null || result.rssi > existing.first) {
                    windowResults[hash] = Pair(result.rssi, result)
                }
            } catch (e: Exception) {
                Log.w(TAG, "onScanResult dropped — permission may have been revoked: ${e.message}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
        }
    }

    private val stopScanRunnable = Runnable {
        flushWindowResults()
        stopScan()
        handler.postDelayed(startScanRunnable, SCAN_INTERVAL_MS)
    }

    private val startScanRunnable = Runnable { startScan() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startScan()
        Log.d(TAG, "BluetoothScanService started — ${SCAN_DURATION_MS / 1000}s scan / " +
                "${SCAN_INTERVAL_MS / 1000}s interval")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        flushWindowResults()
        stopScan()
        Log.d(TAG, "BluetoothScanService stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MANUAL_SCAN) {
            if (!PermissionManager.hasBluetooth(this)) {
                Log.w(TAG, "Manual scan requested but Bluetooth permission not granted — ignoring")
                return START_STICKY
            }
            Log.d(TAG, "Manual scan triggered from UI")
            handler.removeCallbacks(stopScanRunnable)
            handler.removeCallbacks(startScanRunnable)
            windowResults.clear()
            startScan()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!PermissionManager.hasBluetooth(this)) {
            Log.w(TAG, "Bluetooth permission not granted — skipping scan")
            return
        }
        val scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE scanner unavailable — adapter may be off")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        Log.d(TAG, "BLE scan started (LOW_LATENCY) — active for ${SCAN_DURATION_MS / 1000}s")
        handler.postDelayed(stopScanRunnable, SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            Log.d(TAG, "BLE scan stopped — idle for ${SCAN_INTERVAL_MS / 1000}s")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun flushWindowResults() {
        if (windowResults.isEmpty()) {
            Log.d(TAG, "Scan window complete — no devices detected")
            return
        }

        Log.d(TAG, "Scan window complete — ${windowResults.size} unique device(s) detected")

        val events = windowResults.map { (hash, pair) ->
            val (rssi, result) = pair
            val record = result.scanRecord
            val uuids = record?.serviceUuids
            val txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
            val deviceName = record?.deviceName?.takeIf { it.isNotBlank() }
                ?: try { result.device.name?.takeIf { it.isNotBlank() } } catch (e: Exception) { null }

            val deviceType = classifyDevice(uuids)
            val serviceUuidString = uuids?.joinToString("|") { it.uuid.toString() }
            val estimatedDistance = if (txPower != null) {
                estimateDistance(rssi, txPower)
            } else null

            Log.d(TAG, "  Device: hash=${hash.take(12)}… name=$deviceName " +
                    "type=$deviceType rssi=$rssi " +
                    "dist=${estimatedDistance?.let { "%.1fm".format(it) } ?: "unknown"}")

            BluetoothEvent(
                addressHash       = hash,
                deviceName        = deviceName,
                deviceType        = deviceType,
                serviceUuids      = serviceUuidString,
                txPowerLevel      = txPower,
                rssi              = rssi,
                estimatedDistanceM = estimatedDistance,
                bluetoothGranted  = true,
                timestamp         = System.currentTimeMillis()
            )
        }

        scope.launch {
            try {
                XposeDatabase.getInstance(this@BluetoothScanService)
                    .bluetoothEventDao()
                    .insertAll(events)
                Log.d(TAG, "Persisted ${events.size} BluetoothEvent(s) from scan window")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist BluetoothEvents: ${e.message}")
            }
        }

        windowResults.clear()
    }

    private fun classifyDevice(uuids: List<ParcelUuid>?): String {
        if (uuids.isNullOrEmpty()) return "UNKNOWN"

        val prefixes = uuids.map { it.uuid.toString().take(8).lowercase() }

        return when {
            prefixes.any { it in AUDIO_UUIDS }     -> "AUDIO"
            prefixes.any { it in FITNESS_UUIDS }   -> "FITNESS"
            prefixes.any { it in HEALTH_UUIDS }    -> "HEALTH"
            prefixes.any { it in HID_UUIDS }       -> "HID"
            prefixes.any { it in PROXIMITY_UUIDS } -> "PROXIMITY_BEACON"
            else -> "UNKNOWN"
        }
    }

    private fun estimateDistance(rssi: Int, txPower: Int): Float {
        val pathLossExponent = 2.0
        return 10.0.pow((txPower - rssi) / (10.0 * pathLossExponent)).toFloat()
    }

    private fun hashAddress(address: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(address.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID,
            "Bluetooth Proximity Monitoring", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Detects nearby Bluetooth devices for metadata transparency"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Xpose")
            .setContentText("Monitoring Bluetooth proximity metadata")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
}