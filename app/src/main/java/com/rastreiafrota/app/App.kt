package com.rastreiafrota.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rastreiafrota.app.work.AudioSyncWorker
import com.rastreiafrota.app.work.AudioCommandWorker
import com.rastreiafrota.app.work.SyncWorker
import com.rastreiafrota.app.work.RemoteCommandWorker
import com.rastreiafrota.app.push.FirebaseBootstrap
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        schedulePeriodicSync()
        FirebaseBootstrap.initialize(this)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, getString(R.string.notif_channel_tracking), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_AUDIO, getString(R.string.notif_channel_audio), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Informa quando uma gravação autorizada de segurança está ativa."
                    setShowBadge(false)
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_AUDIO_REQUESTS, getString(R.string.notif_channel_audio_requests), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Solicitações de gravação que exigem confirmação visível no celular." }
        )
        // Canal do alarme antifurto: importância máxima e som próprio do canal desligado,
        // porque quem toca é o AlarmService no stream de ALARME (que o modo silencioso não corta).
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Alarme antifurto", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Sirene disparada pelo painel quando o veículo sai da condição segura."
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
        )
    }

    private fun schedulePeriodicSync() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val tracking = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        val audio = PeriodicWorkRequestBuilder<AudioSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        val commands = PeriodicWorkRequestBuilder<AudioCommandWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        val remote = PeriodicWorkRequestBuilder<RemoteCommandWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("rf_periodic_sync", ExistingPeriodicWorkPolicy.KEEP, tracking)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("rf_periodic_audio_sync", ExistingPeriodicWorkPolicy.KEEP, audio)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("rf_periodic_audio_commands", ExistingPeriodicWorkPolicy.KEEP, commands)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("rf_periodic_remote_commands", ExistingPeriodicWorkPolicy.KEEP, remote)
    }

    companion object {
        const val CHANNEL_TRACKING = "rf_tracking"
        const val CHANNEL_AUDIO = "rf_audio"
        const val CHANNEL_AUDIO_REQUESTS = "rf_audio_requests"
        const val CHANNEL_ALARM = "rf_alarm"
    }
}
