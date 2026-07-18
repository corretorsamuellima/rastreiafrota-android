package com.rastreiafrota.app.data.repository

import android.content.Context
import android.util.Base64
import com.rastreiafrota.app.data.local.AppDatabase
import com.rastreiafrota.app.data.local.PendingAudioEntity
import com.rastreiafrota.app.data.prefs.SettingsStore
import com.rastreiafrota.app.data.remote.ApiClient
import com.rastreiafrota.app.data.remote.AudioConsentRequest
import com.rastreiafrota.app.data.remote.AudioUploadRequest
import java.io.File
import java.util.concurrent.TimeUnit

/** Fila offline e envio seguro dos blocos de áudio. */
class AudioRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(appContext).pendingAudioDao()
    val settings = SettingsStore(appContext)

    suspend fun save(entity: PendingAudioEntity) {
        dao.insert(entity)
        dao.deleteSyncedBefore(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(settings.audioRetentionDays().toLong()))
    }

    fun pendingCountFlow() = dao.pendingCountFlow()
    suspend fun pendingCount() = dao.pendingCount()

    suspend fun registerConsent(): Pair<Boolean, String> {
        val version = settings.audioConsentVersion()
        return try {
            val response = ApiClient.service(settings).audioConsent(AudioConsentRequest(true, version))
            if (response.isSuccessful && response.body()?.success == true) {
                settings.acceptAudioConsent(version)
                settings.setLastAudioError(null)
                true to "Ciência registrada."
            } else {
                val message = ApiClient.errorMessage(response)
                settings.setLastAudioError(message)
                false to message
            }
        } catch (e: Exception) {
            val message = e.message ?: "Falha ao registrar ciência"
            settings.setLastAudioError(message)
            false to message
        }
    }

    suspend fun syncPending(): Pair<Int, Boolean> {
        if (!settings.isActivated || !settings.audioEnabled()) return 0 to false
        var sent = 0
        while (true) {
            val items = dao.nextPending(3)
            if (items.isEmpty()) break
            for (item in items) {
                val file = File(item.filePath)
                if (!file.isFile) {
                    dao.markFailed(item.uuid, "Arquivo local não encontrado")
                    dao.markSynced(item.uuid)
                    continue
                }
                val response = try {
                    val bytes = file.readBytes()
                    ApiClient.service(settings).uploadAudio(
                        AudioUploadRequest(
                            uuid = item.uuid,
                            sessionUuid = item.sessionUuid,
                            recordingType = item.recordingType,
                            startedAt = item.startedAt,
                            endedAt = item.endedAt,
                            durationSeconds = item.durationSeconds,
                            mimeType = item.mimeType,
                            sha256 = item.sha256,
                            fileBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                            latitude = item.latitude,
                            longitude = item.longitude,
                            audioCommandId = item.audioCommandId,
                            commandOccurrenceUuid = item.commandOccurrenceUuid
                        )
                    )
                } catch (e: Exception) {
                    val message = e.message ?: "Falha de rede ao enviar áudio"
                    dao.markFailed(item.uuid, message)
                    settings.setLastAudioError(message)
                    return sent to true
                }

                if (response.isSuccessful && response.body()?.success == true) {
                    dao.markSynced(item.uuid)
                    file.delete()
                    dao.deleteByUuid(item.uuid)
                    sent++
                    settings.setLastAudioError(null)
                } else {
                    val message = ApiClient.errorMessage(response)
                    dao.markFailed(item.uuid, message)
                    settings.setLastAudioError(message)
                    return sent to true
                }
            }
        }
        return sent to false
    }
}
