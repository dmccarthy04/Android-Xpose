package com.example.androidxpose.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BluetoothEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: BluetoothEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<BluetoothEvent>)

    @Query("SELECT * FROM bluetooth_events WHERE timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun getEventsSince(sinceMs: Long): List<BluetoothEvent>

    @Query("SELECT COUNT(*) FROM bluetooth_events")
    suspend fun getCount(): Int

    @Query("DELETE FROM bluetooth_events")
    suspend fun clearAll()
}