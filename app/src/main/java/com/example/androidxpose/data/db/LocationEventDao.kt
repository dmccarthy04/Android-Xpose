package com.example.androidxpose.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocationEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: LocationEvent)

    @Query("SELECT * FROM location_events WHERE timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun getEventsSince(sinceMs: Long): List<LocationEvent>

    @Query("""
        SELECT * FROM location_events 
        WHERE latitude IS NOT NULL AND longitude IS NOT NULL 
        AND timestamp >= :sinceMs 
        ORDER BY timestamp ASC
    """)
    suspend fun getFixesSince(sinceMs: Long): List<LocationEvent>

    @Query("SELECT COUNT(*) FROM location_events WHERE timestamp >= :sinceMs")
    suspend fun getCountSince(sinceMs: Long): Int

    @Query("SELECT COUNT(*) FROM location_events")
    suspend fun getCount(): Int

    @Query("DELETE FROM location_events")
    suspend fun clearAll()
}