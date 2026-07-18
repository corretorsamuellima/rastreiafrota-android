package com.rastreiafrota.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingAudioDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(audio: PendingAudioEntity): Long

    @Query("SELECT * FROM pending_audios WHERE syncState=0 ORDER BY startedAt ASC LIMIT :limit")
    suspend fun nextPending(limit: Int = 3): List<PendingAudioEntity>

    @Query("UPDATE pending_audios SET syncState=1,lastError=NULL WHERE uuid=:uuid")
    suspend fun markSynced(uuid: String)

    @Query("UPDATE pending_audios SET attempts=attempts+1,lastError=:error WHERE uuid=:uuid")
    suspend fun markFailed(uuid: String, error: String?)

    @Query("SELECT COUNT(*) FROM pending_audios WHERE syncState=0")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM pending_audios WHERE syncState=0")
    fun pendingCountFlow(): Flow<Int>

    @Query("DELETE FROM pending_audios WHERE uuid=:uuid")
    suspend fun deleteByUuid(uuid: String)

    @Query("DELETE FROM pending_audios WHERE syncState=1 AND createdAt<:before")
    suspend fun deleteSyncedBefore(before: Long): Int
}
