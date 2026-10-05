package com.example.androidxpose.data.collectors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.content.ContextCompat
import android.util.Log

private const val TAG = "DeviceStateService"
private const val CHANNEL_ID = "xpose_device_state"
private const val NOTIFICATION_ID = 1001

class DeviceStateService : Service() {

    private val receiver = DeviceStateReceiver()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        Log.d(TAG, "DeviceStateService started — receiver registered")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receiver)
            Log.d(TAG, "DeviceStateService stopped — receiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver was not registered: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Device State Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Tracks screen and unlock events for metadata transparency"
            setShowBadge(false)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Xpose")
            .setContentText("Monitoring device state metadata")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }
}
