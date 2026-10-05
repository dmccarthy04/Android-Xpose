package com.example.androidxpose.data.db

import androidx.room.*

@Dao
interface InferredPlaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: InferredPlace): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(places: List<InferredPlace>)

    @Query("SELECT * FROM inferred_places ORDER BY confidenceScore DESC")
    suspend fun getAll(): List<InferredPlace>

    @Query("SELECT COUNT(*) FROM inferred_places")
    suspend fun getCount(): Int

    @Query("DELETE FROM inferred_places")
    suspend fun clearAll()
}
