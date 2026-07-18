package com.rastreiafrota.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rastreiafrota.app.App
import com.rastreiafrota.app.R
import com.rastreiafrota.app.data.local.PendingAudioEntity
import com.rastreiafrota.app.data.remote.ApiClient
import com.rastreiafrota.app.data.repository.AudioCommandRepository
import com.rastreiafrota.app.data.repository.AudioRepository
import com.rastreiafrota.app.ui.MainActivity
import com.rastreiafrota.app.work.AudioSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Gravação visível iniciada pelo usuário. Pode continuar com a tela apagada,
 * mas sempre mantém notificação permanente e o indicador de microfone do Android.
 */
class AudioRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: AudioRepository
    private lateinit var commandRepository: AudioCommandRepository
    private var recordingJob: Job? = null
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var currentStartedAt: Date? = null
    private var currentStartedMs: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val recorderLock = Any()

    private var activeCommandId: Long? = null
    private var activeOccurrenceUuid: String? = null
    private var activeReason: String? = null
    private var requestedMaxSeconds: Int? = null
    private var activeSessionUuid: String? = null
    private var activeType: String = TYPE_SAFETY
    @Volatile private var paused = false

    override fun onCreate() {
        super.onCreate()
        repository = AudioRepository(applicationContext)
        commandRepository = AudioCommandRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { stopRecording() }
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> { pauseRecording(); return START_NOT_STICKY }
            ACTION_RESUME -> { resumeRecording(); return START_NOT_STICKY }
            ACTION_START_SOS -> startSession(TYPE_SOS, intent)
            ACTION_START_SAFETY -> startSession(TYPE_SAFETY, intent)
            else -> if (recordingJob == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession(type: String, intent: Intent) {
        if (recordingJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            scope.launch { repository.settings.setLastAudioError("Permissão de microfone não concedida") }
            stopSelf()
            return
        }

        activeType = type
        activeCommandId = intent.getLongExtra(EXTRA_COMMAND_ID, 0L).takeIf { it > 0 }
        activeOccurrenceUuid = intent.getStringExtra(EXTRA_OCCURRENCE_UUID)?.takeIf { it.isNotBlank() }
        activeReason = intent.getStringExtra(EXTRA_REASON)?.takeIf { it.isNotBlank() }
        requestedMaxSeconds = intent.getIntExtra(EXTRA_MAX_SECONDS, 0).takeIf { it > 0 }

        startAsForeground(type, 0)
        acquireWakeLock()
        recordingJob = scope.launch {
            val settings = repository.settings
            var finalAction = "finished"
            var finalMessage: String? = null
            try {
                if (!settings.audioEnabled() || (type == TYPE_SOS && !settings.audioSosEnabled())) {
                    finalAction = "failed"
                    finalMessage = "Recurso de áudio não autorizado pelo plano"
                    settings.setLastAudioError(finalMessage)
                    return@launch
                }

                val commandId = activeCommandId
                val occurrence = activeOccurrenceUuid
                if (commandId != null && occurrence != null) {
                    if (!settings.audioRemoteRequestsEnabled()) {
                        finalAction = "failed"
                        finalMessage = "Solicitações remotas foram desativadas"
                        settings.setLastAudioError(finalMessage)
                        return@launch
                    }
                    val (started, message) = commandRepository.respond(commandId, occurrence, "started", "Gravação iniciada no celular")
                    if (!started) {
                        finalAction = "failed"
                        finalMessage = message
                        settings.setLastAudioError(message)
                        return@launch
                    }
                }

                settings.setAudioRecording(true, type)
                settings.setAudioPaused(false)
                paused = false
                settings.setLastAudioError(null)
                val sessionUuid = UUID.randomUUID().toString()
                activeSessionUuid = sessionUuid
                val chunkSeconds = settings.audioChunkSeconds().coerceIn(15, 120)
                val manualPlanMax = settings.audioSessionMaxMinutes().coerceIn(1, 480) * 60
                val commandPlanMax = settings.audioScheduleMaxMinutes().coerceIn(1, 120) * 60
                val maxSeconds = if (commandId != null) {
                    min(manualPlanMax, min(commandPlanMax, requestedMaxSeconds ?: commandPlanMax))
                } else {
                    manualPlanMax
                }
                val sessionStart = System.currentTimeMillis()
                var elapsed = 0

                while (isActive && elapsed < maxSeconds) {
                    val targetSeconds = min(chunkSeconds, maxSeconds - elapsed)
                    startChunk()
                    updateNotification(type, elapsed)
                    delay(targetSeconds * 1000L)
                    finalizeChunk(sessionUuid, type)
                    elapsed = ((System.currentTimeMillis() - sessionStart) / 1000L).toInt()
                    AudioSyncWorker.enqueueNow(applicationContext)
                }
            } catch (e: CancellationException) {
                finalizeActiveChunkSafely(type)
                throw e
            } catch (e: Exception) {
                finalAction = "failed"
                finalMessage = e.message ?: "Falha na gravação"
                settings.setLastAudioError(finalMessage)
                finalizeActiveChunkSafely(type)
            } finally {
                settings.setAudioRecording(false, type)
                settings.setAudioPaused(false)
                val commandId = activeCommandId
                val occurrence = activeOccurrenceUuid
                if (commandId != null && occurrence != null) {
                    commandRepository.respond(commandId, occurrence, finalAction, finalMessage ?: "Gravação encerrada no celular")
                }
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                clearCommandContext()
                stopSelf()
            }
        }
    }

    private suspend fun finalizeActiveChunkSafely(type: String) {
        if (recorder == null) return
        val sessionUuid = activeSessionUuid ?: UUID.randomUUID().toString()
        try { finalizeChunk(sessionUuid, type) } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    private fun startChunk() {
        val dir = File(filesDir, "audio_pending").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.m4a")
        val mediaRecorder = newRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setAudioEncodingBitRate(32_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        synchronized(recorderLock) {
            recorder = mediaRecorder
            currentFile = file
            currentStartedAt = Date()
            currentStartedMs = System.currentTimeMillis()
        }
    }

    private suspend fun finalizeChunk(sessionUuid: String, type: String) {
        val finalized: Triple<File, Date, Int>? = synchronized(recorderLock) {
            val activeRecorder = recorder ?: return@synchronized null
            val file = currentFile
            val started = currentStartedAt
            val duration = max(1, ((System.currentTimeMillis() - currentStartedMs) / 1000L).toInt())
            var valid = true
            try { activeRecorder.stop() } catch (_: RuntimeException) { valid = false }
            try { activeRecorder.reset() } catch (_: Exception) { }
            activeRecorder.release()
            recorder = null
            currentFile = null
            currentStartedAt = null
            if (!valid) file?.delete()
            if (valid && file != null && started != null && file.isFile && file.length() > 0) Triple(file, started, duration) else null
        }
        finalized ?: return
        val (file, started, duration) = finalized
        val bytes = file.readBytes()
        repository.save(
            PendingAudioEntity(
                uuid = file.nameWithoutExtension,
                sessionUuid = sessionUuid,
                recordingType = type,
                audioCommandId = activeCommandId,
                commandOccurrenceUuid = activeOccurrenceUuid,
                filePath = file.absolutePath,
                fileSize = file.length(),
                sha256 = ApiClient.sha256Hex(bytes),
                durationSeconds = duration,
                startedAt = ApiClient.iso8601(started),
                endedAt = ApiClient.iso8601(Date())
            )
        )
    }

    private suspend fun stopRecording() {
        recordingJob?.cancel()
        recordingJob?.join()
        recordingJob = null
        repository.settings.setAudioRecording(false)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        clearCommandContext()
        stopSelf()
    }

    private fun pauseRecording() {
        if (recordingJob?.isActive != true || paused) return
        synchronized(recorderLock) {
            try {
                recorder?.pause()
                paused = true
                scope.launch { repository.settings.setAudioPaused(true) }
                updateNotification(activeType, 0)
            } catch (e: Exception) {
                scope.launch { repository.settings.setLastAudioError(e.message ?: "Não foi possível pausar") }
            }
        }
    }

    private fun resumeRecording() {
        if (recordingJob?.isActive != true || !paused) return
        synchronized(recorderLock) {
            try {
                recorder?.resume()
                paused = false
                scope.launch { repository.settings.setAudioPaused(false) }
                updateNotification(activeType, 0)
            } catch (e: Exception) {
                scope.launch { repository.settings.setLastAudioError(e.message ?: "Não foi possível continuar") }
            }
        }
    }

    private fun startAsForeground(type: String, elapsed: Int) {
        val notification = buildNotification(type, elapsed)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(type: String, elapsed: Int) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(type, elapsed))
    }

    private fun buildNotification(type: String, elapsed: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 20, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 21, Intent(this, AudioRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseOrResume = PendingIntent.getService(
            this, 22,
            Intent(this, AudioRecordingService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val requested = activeCommandId != null
        val title = when {
            type == TYPE_SOS -> "SOS: gravação de segurança"
            requested -> "Gravação autorizada pelo celular"
            else -> "Gravação de segurança ativa"
        }
        val reason = activeReason?.let { " · ${it.take(55)}" }.orEmpty()
        return NotificationCompat.Builder(this, App.CHANNEL_AUDIO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (paused) "Gravação pausada pelo usuário" else "Microfone em uso · %02d:%02d%s".format(elapsed / 60, elapsed % 60, reason))
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (paused) "Gravação pausada. Toque em Continuar quando desejar." else "Microfone em uso · %02d:%02d%s\nA gravação está visível e pode ser pausada ou encerrada a qualquer momento.".format(elapsed / 60, elapsed % 60, reason)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, if (paused) "Continuar" else "Pausar", pauseOrResume)
            .addAction(0, "Encerrar", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RastreiaFrota:Audio").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun clearCommandContext() {
        activeCommandId = null
        activeOccurrenceUuid = null
        activeReason = null
        requestedMaxSeconds = null
        activeSessionUuid = null
        activeType = TYPE_SAFETY
    }

    override fun onDestroy() {
        recordingJob?.cancel()
        synchronized(recorderLock) {
            try { recorder?.stop() } catch (_: Exception) { }
            try { recorder?.release() } catch (_: Exception) { }
            recorder = null
        }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_SAFETY = "com.rastreiafrota.audio.START_SAFETY"
        const val ACTION_START_SOS = "com.rastreiafrota.audio.START_SOS"
        const val ACTION_STOP = "com.rastreiafrota.audio.STOP"
        const val ACTION_PAUSE = "com.rastreiafrota.audio.PAUSE"
        const val ACTION_RESUME = "com.rastreiafrota.audio.RESUME"
        const val TYPE_SAFETY = "safety"
        const val TYPE_SOS = "sos"
        private const val NOTIF_ID = 2002
        private const val EXTRA_COMMAND_ID = "command_id"
        private const val EXTRA_OCCURRENCE_UUID = "command_occurrence_uuid"
        private const val EXTRA_MAX_SECONDS = "command_max_seconds"
        private const val EXTRA_REASON = "command_reason"

        fun start(context: Context, sos: Boolean = false) {
            val action = if (sos) ACTION_START_SOS else ACTION_START_SAFETY
            ContextCompat.startForegroundService(context, Intent(context, AudioRecordingService::class.java).setAction(action))
        }

        fun startRequested(
            context: Context,
            sos: Boolean,
            commandId: Long,
            occurrenceUuid: String,
            durationMinutes: Int,
            reason: String
        ) {
            val action = if (sos) ACTION_START_SOS else ACTION_START_SAFETY
            val intent = Intent(context, AudioRecordingService::class.java).setAction(action).apply {
                putExtra(EXTRA_COMMAND_ID, commandId)
                putExtra(EXTRA_OCCURRENCE_UUID, occurrenceUuid)
                putExtra(EXTRA_MAX_SECONDS, durationMinutes.coerceIn(1, 120) * 60)
                putExtra(EXTRA_REASON, reason)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AudioRecordingService::class.java).setAction(ACTION_STOP))
        }

        fun pause(context: Context) {
            context.startService(Intent(context, AudioRecordingService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            context.startService(Intent(context, AudioRecordingService::class.java).setAction(ACTION_RESUME))
        }
    }
}
