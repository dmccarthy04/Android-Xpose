package com.example.androidxpose.data.collectors

import android.content.Context
import com.example.androidxpose.data.db.XposeDatabase

data class TemporalEvent(
    val category: String,
    val eventType: String,
    val timestamp: Long
)

data class HourlyActivityBucket(
    val hour: Int,
    val count: Int
)

data class TemporalSnapshot(
    val allEvents: List<TemporalEvent>,
    val hourlyDistribution: List<HourlyActivityBucket>,
    val activeDaysInWindow: Int,
    val totalEventCount: Int,
    val earliestEventMs: Long?,
    val latestEventMs: Long?,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val timestamp: Long
)

object TemporalCollector {

    private const val DEFAULT_WINDOW_HOURS = 168L // 7 days

    suspend fun collect(
        context: Context,
        windowHours: Long = DEFAULT_WINDOW_HOURS
    ): TemporalSnapshot {
        val endMs = System.currentTimeMillis()
        val startMs = endMs - (windowHours * 60 * 60 * 1000)

        val db = XposeDatabase.getInstance(context)

        val events = mutableListOf<TemporalEvent>()

        db.locationEventDao()
            .getEventsSince(startMs)
            .forEach { events.add(TemporalEvent("LOCATION", "GPS_FIX", it.timestamp)) }

        db.bluetoothEventDao()
            .getEventsSince(startMs)
            .forEach { events.add(TemporalEvent("BLUETOOTH", "DEVICE_SEEN", it.timestamp)) }

        db.appUsageEventDao()
            .getEventsSince(startMs)
            .forEach { events.add(TemporalEvent("APP_USAGE", resolveUsageType(it.eventType), it.timestamp)) }

        db.deviceStateEventDao()
            .getEventsSince(startMs)
            .forEach { events.add(TemporalEvent("DEVICE_STATE", it.eventType, it.timestamp)) }

        db.networkEventDao()
            .getEventsSince(startMs)
            .forEach { events.add(TemporalEvent("NETWORK", if (it.isConnected) "CONNECTED" else "DISCONNECTED", it.timestamp)) }

        events.sortBy { it.timestamp }

        val hourlyDistribution = buildHourlyDistribution(events)
        val activeDays = countActiveDays(events)

        return TemporalSnapshot(
            allEvents            = events,
            hourlyDistribution   = hourlyDistribution,
            activeDaysInWindow   = activeDays,
            totalEventCount      = events.size,
            earliestEventMs      = events.firstOrNull()?.timestamp,
            latestEventMs        = events.lastOrNull()?.timestamp,
            windowStartMs        = startMs,
            windowEndMs          = endMs,
            timestamp            = endMs
        )
    }

    private fun buildHourlyDistribution(events: List<TemporalEvent>): List<HourlyActivityBucket> {
        val counts = IntArray(24) { 0 }
        val calendar = java.util.Calendar.getInstance()

        events.forEach { event ->
            calendar.timeInMillis = event.timestamp
            val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
            counts[hour]++
        }

        return counts.mapIndexed { hour, count ->
            HourlyActivityBucket(hour = hour, count = count)
        }
    }

    private fun countActiveDays(events: List<TemporalEvent>): Int {
        val calendar = java.util.Calendar.getInstance()
        return events.map { event ->
            calendar.timeInMillis = event.timestamp
            val year  = calendar.get(java.util.Calendar.YEAR)
            val day   = calendar.get(java.util.Calendar.DAY_OF_YEAR)
            "$year-$day"
        }.toSet().size
    }

    private fun resolveUsageType(eventType: Int): String = when (eventType) {
        1    -> "APP_RESUMED"
        2    -> "APP_PAUSED"
        else -> "APP_EVENT"
    }
}
