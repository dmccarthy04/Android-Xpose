package com.example.androidxpose.data.collectors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.androidxpose.data.collectors.UsageSyncWorker

private const val TAG = "BootReceiver"

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {
                Log.d(TAG, "Boot detected — restarting monitoring services")
                UsageSyncWorker.enqueue(context)

                listOf(
                    DeviceStateService::class.java,
                    NetworkStateService::class.java,
                    LocationService::class.java,
                    BluetoothScanService::class.java
                ).forEach { serviceClass ->
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, serviceClass)
                    )
                }
            }
        }
    }
}
