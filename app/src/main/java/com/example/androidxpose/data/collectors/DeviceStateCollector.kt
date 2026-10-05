package com.example.androidxpose.data.collectors

import android.content.Context
import com.example.androidxpose.data.db.DeviceStateEvent
import com.example.androidxpose.data.db.XposeDatabase

data class DeviceStateSnapshot(
    val screenOnEvents: List<DeviceStateEvent>,
    val screenOffEvents: List<DeviceStateEvent>,
    val unlockEvents: List<DeviceStateEvent>,
    val totalEventCount: Int,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val timestamp: Long
)

object DeviceStateCollector {

    private const val DEFAULT_WINDOW_HOURS = 24L

    suspend fun collect(
        context: Context,
        windowHours: Long = DEFAULT_WINDOW_HOURS
    ): DeviceStateSnapshot {
        val endMs = System.currentTimeMillis()
        val startMs = endMs - (windowHours * 60 * 60 * 1000)

        val dao = XposeDatabase.getInstance(context).deviceStateEventDao()

        val screenOnEvents  = dao.getScreenOnEventsSince(startMs)
        val screenOffEvents = dao.getScreenOffEventsSince(startMs)
        val unlockEvents    = dao.getUnlockEventsSince(startMs)
        val totalCount      = dao.getCount()

        return DeviceStateSnapshot(
            screenOnEvents  = screenOnEvents,
            screenOffEvents = screenOffEvents,
            unlockEvents    = unlockEvents,
            totalEventCount = totalCount,
            windowStartMs   = startMs,
            windowEndMs     = endMs,
            timestamp       = endMs
        )
    }
}
