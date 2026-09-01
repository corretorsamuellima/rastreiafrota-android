package com.rastreiafrota.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rastreiafrota.app.App
import com.rastreiafrota.app.R
import com.rastreiafrota.app.ui.MainActivity

/**
 * Sirene antifurto.
 *
 * Toca no canal de ALARME (que o Android não silencia junto com o toque normal), sobe o
 * volume ao máximo, vibra e mantém a tela ligável enquanto o alarme estiver disparado.
 * Guarda o volume original e devolve ao parar, para não deixar o aparelho no máximo depois.
 *
 * Só para com o comando remoto alarm_off, pelo botão da notificação ou pelo limite de
 * tempo (para não consumir a bateria se o painel ficar sem comunicação).
 */
class AlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var previousVolume: Int? = null
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (player != null) return START_STICKY

        startedAt = System.currentTimeMillis()
        startForegroundCompat()
        acquireWakeLock()
        startSiren()
        scheduleAutoStop()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, App.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ALARME DISPARADO")
            .setContentText("O veículo saiu da condição segura. Toque para abrir.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, "Parar sirene", stop)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rf:alarm").apply {
            setReferenceCounted(false)
            acquire(MAX_DURATION_MS)
        }
    }

    private fun startSiren() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Guarda o volume atual para restaurar depois; sem isso o aparelho ficava no máximo.
        previousVolume = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrNull()
        runCatching {
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
        }

        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        runCatching {
            val pattern = longArrayOf(0, 600, 400)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    /** Teto de tempo: evita sirene tocando por horas se o comando de parar não chegar. */
    private fun scheduleAutoStop() {
        val handler = android.os.Handler(mainLooper)
        handler.postDelayed({
            if (System.currentTimeMillis() - startedAt >= MAX_DURATION_MS - 1_000L) stopSelf()
        }, MAX_DURATION_MS)
    }

    override fun onDestroy() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        previousVolume?.let { volume ->
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
        }
        previousVolume = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 1003
        const val ACTION_STOP = "com.rastreiafrota.app.ALARM_STOP"

        /** 15 minutos e' agressivo o bastante para chamar atencao sem matar a bateria. */
        const val MAX_DURATION_MS = 15L * 60L * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmService::class.java))
        }
    }
}
