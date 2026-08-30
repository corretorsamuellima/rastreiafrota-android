package com.rastreiafrota.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingLocationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(location: PendingLocationEntity): Long

    /** Próximo lote pendente em ordem cronológica. */
    @Query("SELECT * FROM pending_locations WHERE syncState = 0 ORDER BY capturedAt ASC LIMIT :limit")
    suspend fun nextBatch(limit: Int): List<PendingLocationEntity>

    @Query("UPDATE pending_locations SET syncState = 1 WHERE uuid IN (:uuids)")
    suspend fun markSynced(uuids: List<String>)

    @Query("UPDATE pending_locations SET attempts = attempts + 1, lastError = :error WHERE uuid IN (:uuids)")
    suspend fun markFailed(uuids: List<String>, error: String?)

    @Query("SELECT COUNT(*) FROM pending_locations WHERE syncState = 0")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM pending_locations WHERE syncState = 0")
    fun pendingCountFlow(): Flow<Int>

    /** Limpeza controlada: apaga apenas SINCRONIZADAS além da retenção. */
    @Query("DELETE FROM pending_locations WHERE syncState = 1 AND createdAt < :beforeMillis")
    suspend fun deleteSyncedBefore(beforeMillis: Long): Int

    /** Proteção de armazenamento: remove as sincronizadas mais antigas acima do limite. */
    @Query("""DELETE FROM pending_locations WHERE syncState = 1 AND id NOT IN
              (SELECT id FROM pending_locations ORDER BY id DESC LIMIT :keep)""")
    suspend fun trimSynced(keep: Int): Int

    @Query("SELECT MAX(capturedAt) FROM pending_locations")
    suspend fun lastCapturedAt(): String?

    /** Pontos recentes da sessão para desenhar o trajeto local sem depender da internet. */
    @Query("""SELECT * FROM (
              SELECT * FROM pending_locations
              WHERE routeSessionUuid = :sessionUuid
              ORDER BY sequenceNo DESC LIMIT :limit
            ) ORDER BY sequenceNo ASC""")
    suspend fun routePoints(sessionUuid: String, limit: Int = 1500): List<PendingLocationEntity>
}
