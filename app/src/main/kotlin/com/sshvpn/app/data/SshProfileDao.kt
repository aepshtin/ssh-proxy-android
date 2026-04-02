package com.sshvpn.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SshProfileDao {
    @Query("SELECT * FROM ssh_profiles ORDER BY name ASC")
    fun getAllProfiles(): Flow<List<SshProfile>>

    @Query("SELECT * FROM ssh_profiles WHERE id = :id")
    suspend fun getProfileById(id: Long): SshProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: SshProfile): Long

    @Update
    suspend fun update(profile: SshProfile)

    @Delete
    suspend fun delete(profile: SshProfile)

    @Query("DELETE FROM ssh_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)
}
