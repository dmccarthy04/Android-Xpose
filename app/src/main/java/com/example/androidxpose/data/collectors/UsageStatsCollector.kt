package com.example.androidxpose.data.collectors

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.example.androidxpose.data.db.AppUsageEvent
import com.example.androidxpose.data.db.XposeDatabase
import com.example.androidxpose.permissions.PermissionManager

private const val TAG = "UsageStatsCollector"

data class AppUsageRecord(
    val packageName: String,
    val lastTimeUsed: Long,
    val totalTimeInForeground: Long
)

data class UsageStatsSnapshot(
    val usageGranted: Boolean,
    val records: List<AppUsageRecord>,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val timestamp: Long
)

object UsageStatsCollector {

    private const val DEFAULT_WINDOW_HOURS = 24L

    fun collect(context: Context, windowHours: Long = DEFAULT_WINDOW_HOURS): UsageStatsSnapshot {
        val usageGranted = PermissionManager.hasUsageStats(context)

        val endMs = System.currentTimeMillis()
        val startMs = endMs - (windowHours * 60 * 60 * 1000)

        if (!usageGranted) {
            return UsageStatsSnapshot(
                usageGranted = false,
                records = emptyList(),
                windowStartMs = startMs,
                windowEndMs = endMs,
                timestamp = endMs
            )
        }

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val statsMap = usageStatsManager.queryAndAggregateUsageStats(startMs, endMs)

        val records = statsMap.values
            .filter { stat -> stat.totalTimeInForeground > 0 }
            .map { stat ->
                AppUsageRecord(
                    packageName = stat.packageName,
                    lastTimeUsed = stat.lastTimeUsed,
                    totalTimeInForeground = stat.totalTimeInForeground
                )
            }
            .sortedByDescending { it.totalTimeInForeground }

        return UsageStatsSnapshot(
            usageGranted = true,
            records = records,
            windowStartMs = startMs,
            windowEndMs = endMs,
            timestamp = endMs
        )
    }

    suspend fun collectAndPersist(
        context: Context,
        windowHours: Long = DEFAULT_WINDOW_HOURS
    ): UsageStatsSnapshot {
        val snapshot = collect(context, windowHours)
        val dao = XposeDatabase.getInstance(context).appUsageEventDao()

        val events = snapshot.records.map { record ->
            AppUsageEvent(
                packageName  = record.packageName,
                eventType    = 1,
                usageGranted = snapshot.usageGranted,
                timestamp    = record.lastTimeUsed
            )
        }

        try {
            if (events.isNotEmpty()) {
                dao.insertAll(events)
                Log.d(TAG, "Persisted ${events.size} app usage event(s)")
            } else {
                dao.insert(
                    AppUsageEvent(
                        packageName  = "none",
                        eventType    = 0,
                        usageGranted = snapshot.usageGranted,
                        timestamp    = snapshot.timestamp
                    )
                )
                Log.d(TAG, "Persisted usage audit record (no usage data)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist app usage events: ${e.message}")
        }

        return snapshot
    }
}
