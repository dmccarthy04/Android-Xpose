package com.example.androidxpose.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppUsageEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AppUsageEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<AppUsageEvent>)

    @Query("SELECT * FROM app_usage_events WHERE timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun getEventsSince(sinceMs: Long): List<AppUsageEvent>

    @Query("""
        SELECT * FROM app_usage_events
        WHERE eventType = 1
        AND timestamp >= :sinceMs
        ORDER BY timestamp ASC
    """)
    suspend fun getResumeEventsSince(sinceMs: Long): List<AppUsageEvent>

    @Query("SELECT DISTINCT packageName FROM app_usage_events WHERE timestamp >= :sinceMs")
    suspend fun getDistinctPackages(sinceMs: Long): List<String>

    @Query("""
        SELECT packageName, COUNT(*) as launchCount
        FROM app_usage_events
        WHERE eventType = 1 AND timestamp >= :sinceMs
        GROUP BY packageName
        ORDER BY launchCount DESC
    """)
    suspend fun getLaunchCountsPerPackage(sinceMs: Long): List<PackageLaunchCount>

    @Query("SELECT COUNT(*) FROM app_usage_events")
    suspend fun getCount(): Int

    @Query("DELETE FROM app_usage_events")
    suspend fun clearAll()
}

data class PackageLaunchCount(
    val packageName: String,
    val launchCount: Int
)