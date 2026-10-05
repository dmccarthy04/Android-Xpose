package com.example.androidxpose.data.collectors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

private const val TAG = "NetworkStateService"
private const val CHANNEL_ID = "xpose_network_state"
private const val NOTIFICATION_ID = 1002

class NetworkStateService : Service() {

    private lateinit var networkReceiver: NetworkStateReceiver

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        networkReceiver = NetworkStateReceiver(this)
        networkReceiver.register()

        Log.d(TAG, "NetworkStateService started — NetworkCallback registered")
    }

    override fun onDestroy() {
        super.onDestroy()
        networkReceiver.unregister()
        Log.d(TAG, "NetworkStateService stopped — NetworkCallback unregistered")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Network State Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Tracks connectivity transitions for metadata transparency"
            setShowBadge(false)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Xpose")
            .setContentText("Monitoring network state metadata")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }
}
