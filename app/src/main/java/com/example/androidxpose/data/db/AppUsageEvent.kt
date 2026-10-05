package com.example.androidxpose.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_usage_events")
data class AppUsageEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val eventType: Int,
    val usageGranted: Boolean,
    val timestamp: Long
)
