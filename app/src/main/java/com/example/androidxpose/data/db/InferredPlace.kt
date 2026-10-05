package com.example.androidxpose.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inferred_places")
data class InferredPlace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val centroidLat: Double,
    val centroidLng: Double,
    val radiusM: Double,
    val confidenceScore: Int,
    val distinctDays: Int,
    val distinctNightDays: Int,
    val distinctWeekdayDays: Int,
    val totalStationaryFixes: Int,
    val cohabitationCount: Int,
    val firstInferredMs: Long,
    val lastConfirmedMs: Long
)