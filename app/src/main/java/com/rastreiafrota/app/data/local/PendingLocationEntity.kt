package com.rastreiafrota.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Posição pendente de sincronização (fila offline). */
@Entity(
    tableName = "pending_locations",
    indices = [Index(value = ["uuid"], unique = true), Index(value = ["syncState", "capturedAt"])]
)
data class PendingLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val speedKmh: Double?,
    val bearing: Double?,
    val accuracy: Double?,
    val networkType: String?,
    val battery: Int?,
    val gpsEnabled: Boolean,
    val mockLocation: Boolean,
    /** ISO-8601 com fuso — horário REAL da captura. */
    val capturedAt: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null,
    /** 0 = pendente, 1 = sincronizada. */
    val syncState: Int = 0
)
