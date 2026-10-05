package com.example.androidxpose.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "network_events")
data class NetworkEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val networkType: String,
    val isConnected: Boolean,
    val timestamp: Long
)
