package com.example.androidxpose.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NetworkEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: NetworkEvent)

    @Query("SELECT * FROM network_events WHERE timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun getEventsSince(sinceMs: Long): List<NetworkEvent>

    @Query("""
        SELECT * FROM network_events
        WHERE networkType = 'WIFI' AND isConnected = 1
        AND timestamp >= :sinceMs
        ORDER BY timestamp ASC
    """)
    suspend fun getWifiEventsSince(sinceMs: Long): List<NetworkEvent>

    @Query("""
        SELECT * FROM network_events
        WHERE networkType = 'CELLULAR' AND isConnected = 1
        AND timestamp >= :sinceMs
        ORDER BY timestamp ASC
    """)
    suspend fun getCellularEventsSince(sinceMs: Long): List<NetworkEvent>

    @Query("""
        SELECT * FROM network_events
        WHERE isConnected = 0
        AND timestamp >= :sinceMs
        ORDER BY timestamp ASC
    """)
    suspend fun getDisconnectEventsSince(sinceMs: Long): List<NetworkEvent>

    @Query("SELECT COUNT(*) FROM network_events WHERE timestamp >= :sinceMs")
    suspend fun getTransitionCountSince(sinceMs: Long): Int

    @Query("SELECT COUNT(*) FROM network_events")
    suspend fun getCount(): Int

    @Query("DELETE FROM network_events")
    suspend fun clearAll()
}