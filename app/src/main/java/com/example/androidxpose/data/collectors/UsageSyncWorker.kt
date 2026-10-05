package com.example.androidxpose.data.collectors

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.androidxpose.data.db.AppUsageEvent
import com.example.androidxpose.data.db.XposeDatabase
import com.example.androidxpose.permissions.PermissionManager
import java.util.concurrent.TimeUnit

private const val TAG = "UsageSyncWorker"
private const val PREF_NAME = "xpose_usage_sync"
private const val KEY_LAST_SYNC_MS = "last_sync_ms"
private const val WORK_NAME = "xpose_usage_sync"

class UsageSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!PermissionManager.hasUsageStats(applicationContext)) {
            Log.w(TAG, "Usage stats permission not granted — skipping sync")
            return Result.success()
        }

        val prefs = applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        val nowMs = System.currentTimeMillis()

        val lastSyncMs = prefs.getLong(
            KEY_LAST_SYNC_MS,
            nowMs - TimeUnit.HOURS.toMillis(24)
        )

        val usageStatsManager = applicationContext
            .getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val usageEvents = usageStatsManager.queryEvents(lastSyncMs, nowMs)
        val event = UsageEvents.Event()
        val newEvents = mutableListOf<AppUsageEvent>()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)

            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.ACTIVITY_PAUSED
            ) {
                newEvents.add(
                    AppUsageEvent(
                        packageName  = event.packageName,
                        eventType    = event.eventType,
                        usageGranted = true,
                        timestamp    = event.timeStamp
                    )
                )
            }
        }

        return try {
            if (newEvents.isNotEmpty()) {
                XposeDatabase.getInstance(applicationContext)
                    .appUsageEventDao()
                    .insertAll(newEvents)
                Log.d(TAG, "Persisted ${newEvents.size} new usage event(s) " +
                        "covering ${(nowMs - lastSyncMs) / 1000}s window")
            } else {
                Log.d(TAG, "No new usage events since last sync")
            }

            prefs.edit().putLong(KEY_LAST_SYNC_MS, nowMs).apply()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "UsageSyncWorker failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {

        fun enqueue(context: Context) {
            val wm = WorkManager.getInstance(context)

            val immediate = OneTimeWorkRequestBuilder<UsageSyncWorker>().build()
            wm.enqueueUniqueWork(
                "${WORK_NAME}_immediate",
                ExistingWorkPolicy.REPLACE,
                immediate
            )

            val periodic = PeriodicWorkRequestBuilder<UsageSyncWorker>(
                15, TimeUnit.MINUTES
            ).build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
            )

            Log.d(TAG, "UsageSyncWorker enqueued — immediate + 15 min periodic")
        }
    }
}
