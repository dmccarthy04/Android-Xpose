package com.example.androidxpose.data.collectors

import android.content.Context
import com.example.androidxpose.data.db.NetworkEvent
import com.example.androidxpose.data.db.XposeDatabase

data class NetworkSnapshot(
    val allEvents: List<NetworkEvent>,
    val wifiEvents: List<NetworkEvent>,
    val cellularEvents: List<NetworkEvent>,
    val disconnectEvents: List<NetworkEvent>,
    val transitionCount: Int,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val timestamp: Long
)

object NetworkStateCollector {

    private const val DEFAULT_WINDOW_HOURS = 24L

    suspend fun collect(
        context: Context,
        windowHours: Long = DEFAULT_WINDOW_HOURS
    ): NetworkSnapshot {
        val endMs = System.currentTimeMillis()
        val startMs = endMs - (windowHours * 60 * 60 * 1000)

        val dao = XposeDatabase.getInstance(context).networkEventDao()

        val allEvents        = dao.getEventsSince(startMs)
        val wifiEvents       = dao.getWifiEventsSince(startMs)
        val cellularEvents   = dao.getCellularEventsSince(startMs)
        val disconnectEvents = dao.getDisconnectEventsSince(startMs)
        val transitionCount  = dao.getTransitionCountSince(startMs)

        return NetworkSnapshot(
            allEvents        = allEvents,
            wifiEvents       = wifiEvents,
            cellularEvents   = cellularEvents,
            disconnectEvents = disconnectEvents,
            transitionCount  = transitionCount,
            windowStartMs    = startMs,
            windowEndMs      = endMs,
            timestamp        = endMs
        )
    }
}
