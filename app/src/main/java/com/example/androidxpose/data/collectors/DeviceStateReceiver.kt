package com.example.androidxpose.data.collectors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.androidxpose.data.db.DeviceStateEvent
import com.example.androidxpose.data.db.XposeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "DeviceStateReceiver"

class DeviceStateReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val eventType = when (intent.action) {
            Intent.ACTION_SCREEN_ON    -> "SCREEN_ON"
            Intent.ACTION_SCREEN_OFF   -> "SCREEN_OFF"
            Intent.ACTION_USER_PRESENT -> "USER_PRESENT"
            else -> {
                Log.w(TAG, "Received unhandled action: ${intent.action}")
                return
            }
        }

        Log.d(TAG, "Device state event: $eventType")

        val event = DeviceStateEvent(
            eventType = eventType,
            timestamp = System.currentTimeMillis()
        )

        scope.launch {
            try {
                XposeDatabase.getInstance(context)
                    .deviceStateEventDao()
                    .insert(event)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert DeviceStateEvent: ${e.message}")
            }
        }
    }
}
