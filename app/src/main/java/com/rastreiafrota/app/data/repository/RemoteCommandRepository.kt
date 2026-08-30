package com.rastreiafrota.app.data.repository

import android.content.Context
import com.rastreiafrota.app.data.prefs.SettingsStore
import com.rastreiafrota.app.data.remote.ApiClient
import com.rastreiafrota.app.data.remote.DeviceCommandResponseRequest
import com.rastreiafrota.app.service.AudioRecordingService
import com.rastreiafrota.app.service.LocationTrackingService
import com.rastreiafrota.app.work.AudioSyncWorker
import com.rastreiafrota.app.work.SyncWorker

class RemoteCommandRepository(private val context: Context) {
    private val settings=SettingsStore(context)
    suspend fun pollAndExecute():Int {
        if(!settings.isActivated)return 0
        return try {
            val api=ApiClient.service(settings);val response=api.deviceCommands()
            if(!response.isSuccessful||response.body()?.success!=true)return 0
            val commands=response.body()?.data?.commands.orEmpty()
            commands.forEach { command ->
                val result=runCatching { execute(command.commandType) }
                val ok=result.isSuccess
                api.respondDeviceCommand(command.id,DeviceCommandResponseRequest(if(ok)"executed" else "failed",result.getOrNull()?:result.exceptionOrNull()?.message))
            }
            commands.size
        } catch(_:Exception){0}
    }
    private suspend fun execute(type:String):String = when(type) {
        "gps_start"->{if(!settings.trackingEnabled())settings.startNewRouteSession();settings.setTrackingEnabled(true);LocationTrackingService.start(context);"GPS e novo trajeto iniciados remotamente"}
        "gps_pause"->{settings.setTrackingEnabled(false);LocationTrackingService.stop(context);settings.finishRouteSession();"GPS pausado e trajeto encerrado remotamente"}
        "audio_pause"->{if(!settings.audioRecording())error("Não há gravação ativa");AudioRecordingService.pause(context);"Áudio pausado"}
        "audio_resume"->{if(!settings.audioRecording())error("Não há gravação ativa");AudioRecordingService.resume(context);"Áudio continuado"}
        "audio_stop"->{if(!settings.audioRecording())error("Não há gravação ativa");AudioRecordingService.stop(context);"Áudio encerrado"}
        "sync_now"->{SyncWorker.enqueueNow(context);AudioSyncWorker.enqueueNow(context);"Sincronização solicitada"}
        "refresh_config"->{TrackingRepository(context).refreshRemoteConfig();"Configurações atualizadas"}
        else->error("Comando desconhecido")
    }
}
