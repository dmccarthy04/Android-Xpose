package com.example.androidxpose.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceStateEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: DeviceStateEvent)

    @Query("SELECT * FROM device_state_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<DeviceStateEvent>>

    @Query("SELECT * FROM device_state_events WHERE timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun getEventsSince(sinceMs: Long): List<DeviceStateEvent>

    @Query("""
        SELECT * FROM device_state_events 
        WHERE eventType = 'SCREEN_ON' 
        AND timestamp >= :sinceMs 
        ORDER BY timestamp ASC
    """)
    suspend fun getScreenOnEventsSince(sinceMs: Long): List<DeviceStateEvent>

    @Query("""
        SELECT * FROM device_state_events 
        WHERE eventType = 'SCREEN_OFF' 
        AND timestamp >= :sinceMs 
        ORDER BY timestamp ASC
    """)
    suspend fun getScreenOffEventsSince(sinceMs: Long): List<DeviceStateEvent>

    @Query("""
        SELECT * FROM device_state_events 
        WHERE eventType = 'USER_PRESENT' 
        AND timestamp >= :sinceMs 
        ORDER BY timestamp ASC
    """)
    suspend fun getUnlockEventsSince(sinceMs: Long): List<DeviceStateEvent>

    @Query("SELECT COUNT(*) FROM device_state_events WHERE eventType = 'USER_PRESENT' AND timestamp >= :sinceMs")
    suspend fun getUnlockCountSince(sinceMs: Long): Int

    @Query("SELECT COUNT(*) FROM device_state_events")
    suspend fun getCount(): Int

    @Query("DELETE FROM device_state_events")
    suspend fun clearAll()
}
