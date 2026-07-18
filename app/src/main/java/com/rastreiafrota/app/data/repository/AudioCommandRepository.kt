package com.rastreiafrota.app.data.repository

import android.content.Context
import com.rastreiafrota.app.data.prefs.SettingsStore
import com.rastreiafrota.app.data.remote.ApiClient
import com.rastreiafrota.app.data.remote.AudioCommandDto
import com.rastreiafrota.app.data.remote.AudioCommandResponseRequest
import com.rastreiafrota.app.util.AudioCommandNotifier
import java.util.Date

/** Consulta solicitações do painel sem jamais iniciar o microfone automaticamente. */
class AudioCommandRepository(context: Context) {
    private val appContext = context.applicationContext
    val settings = SettingsStore(appContext)

    suspend fun fetchDue(): Pair<List<AudioCommandDto>, String> {
        if (!settings.isActivated || !settings.audioEnabled() || !settings.audioRemoteRequestsEnabled()) {
            val message = "Solicitações remotas não estão habilitadas."
            settings.setLastAudioCommandCheck(ApiClient.iso8601(Date()), message)
            return emptyList<AudioCommandDto>() to message
        }
        return try {
            val response = ApiClient.service(settings).audioCommands()
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data?.audioConfig?.let { settings.saveAudioConfig(it) }
                val commands = response.body()?.data?.commands.orEmpty()
                val message = if (commands.isEmpty()) "Nenhuma solicitação pendente." else "${commands.size} solicitação(ões) aguardando confirmação."
                settings.setLastAudioCommandCheck(ApiClient.iso8601(Date()), message)
                commands to message
            } else {
                val message = ApiClient.errorMessage(response)
                settings.setLastAudioCommandCheck(ApiClient.iso8601(Date()), message)
                emptyList<AudioCommandDto>() to message
            }
        } catch (e: Exception) {
            val message = e.message ?: "Falha ao consultar solicitações de áudio"
            settings.setLastAudioCommandCheck(ApiClient.iso8601(Date()), message)
            emptyList<AudioCommandDto>() to message
        }
    }

    suspend fun pollAndNotify(): Pair<Int, String> {
        val (commands, message) = fetchDue()
        commands.forEach { AudioCommandNotifier.show(appContext, it) }
        return commands.size to message
    }

    suspend fun respond(commandId: Long, occurrenceUuid: String, action: String, message: String? = null): Pair<Boolean, String> {
        return try {
            val response = ApiClient.service(settings).respondAudioCommand(
                commandId,
                AudioCommandResponseRequest(occurrenceUuid, action, message)
            )
            if (response.isSuccessful && response.body()?.success == true) {
                true to response.body()?.message.orEmpty().ifBlank { "Solicitação atualizada." }
            } else {
                false to ApiClient.errorMessage(response)
            }
        } catch (e: Exception) {
            false to (e.message ?: "Falha ao atualizar a solicitação")
        }
    }
}
