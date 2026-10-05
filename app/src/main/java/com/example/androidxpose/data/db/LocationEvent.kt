package com.example.androidxpose.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_events")
data class LocationEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val provider: String?,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val foregroundGranted: Boolean,
    val backgroundGranted: Boolean,
    val timestamp: Long
)
