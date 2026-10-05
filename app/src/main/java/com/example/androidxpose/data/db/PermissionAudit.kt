package com.example.androidxpose.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "permission_audits")
data class PermissionAudit(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val foregroundLocation: Boolean,
    val backgroundLocation: Boolean,
    val bluetooth: Boolean,
    val usageStats: Boolean,
    val timestamp: Long
)
