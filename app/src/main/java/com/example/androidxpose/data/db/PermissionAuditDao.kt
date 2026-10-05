package com.example.androidxpose.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PermissionAuditDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(audit: PermissionAudit)

    @Query("SELECT * FROM permission_audits ORDER BY timestamp DESC")
    fun getAllAudits(): Flow<List<PermissionAudit>>

    @Query("SELECT * FROM permission_audits ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestAudit(): PermissionAudit?

    @Query("DELETE FROM permission_audits")
    suspend fun clearAll()
}
