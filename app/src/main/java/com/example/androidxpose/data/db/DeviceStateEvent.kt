package com.example.androidxpose.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_state_events")
data class DeviceStateEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventType: String,
    val timestamp: Long
)
