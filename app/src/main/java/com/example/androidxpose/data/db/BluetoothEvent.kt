package com.example.androidxpose.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bluetooth_events")
data class BluetoothEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val addressHash: String,
    val deviceName: String?,
    val deviceType: String,
    val serviceUuids: String?,
    val txPowerLevel: Int?,
    val rssi: Int?,
    val estimatedDistanceM: Float?,
    val bluetoothGranted: Boolean,
    val timestamp: Long
)
