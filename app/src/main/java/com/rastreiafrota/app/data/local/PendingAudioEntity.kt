package com.rastreiafrota.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_audios",
    indices = [Index(value = ["uuid"], unique = true), Index(value = ["syncState", "startedAt"])]
)
data class PendingAudioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val sessionUuid: String,
    val recordingType: String,
    val audioCommandId: Long? = null,
    val commandOccurrenceUuid: String? = null,
    val filePath: String,
    val mimeType: String = "audio/mp4",
    val fileSize: Long,
    val sha256: String,
    val durationSeconds: Int,
    val startedAt: String,
    val endedAt: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val syncState: Int = 0,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
