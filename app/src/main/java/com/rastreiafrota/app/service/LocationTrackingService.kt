package com.rastreiafrota.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rastreiafrota.app.App
import com.rastreiafrota.app.R
import com.rastreiafrota.app.data.local.PendingLocationEntity
import com.rastreiafrota.app.data.repository.TrackingRepository
import com.rastreiafrota.app.data.repository.AudioCommandRepository
import com.rastreiafrota.app.data.repository.RemoteCommandRepository
import com.rastreiafrota.app.data.remote.ApiClient
import com.rastreiafrota.app.ui.MainActivity
import com.rastreiafrota.app.util.DeviceInfo
import com.rastreiafrota.app.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.UUID
import kotlin.math.abs

/**
 * ForegroundService de rastreamento:
 * - Notificação permanente (obrigatória e visível ao usuário);
 * - Fused Location Provider com frequência ADAPTATIVA (movimento/parado/parado longo);
 * - Grava tudo no Room; sincronização em lote via WorkManager + flush direto quando online;
 * - Funciona com tela desligada; reinicia via BootReceiver.
 */
class LocationTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var repository: TrackingRepository
    private lateinit var audioCommandRepository: AudioCommandRepository
    private lateinit var remoteCommandRepository: RemoteCommandRepository
    private var trackingJob: Job? = null

    private var currentIntervalSec = 10
    private var lastMovementAt = System.currentTimeMillis()
    private var lastLocation: Location? = null
    private var lastAudioCommandPollAt = 0L
    private val locationMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        repository = TrackingRepository(applicationContext)
        audioCommandRepository = AudioCommandRepository(applicationContext)
        remoteCommandRepository = RemoteCommandRepository(applicationContext)
        fused = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        // START_STICKY e múltiplos comandos não podem criar vários loops adaptativos concorrentes.
        if (trackingJob?.isActive == true) return START_STICKY
        trackingJob = scope.launch {
            if (!repository.settings.isActivated || !repository.settings.trackingEnabled()) {
                stopSelf(); return@launch
            }
            repository.refreshRemoteConfig()
            repository.settings.ensureRouteSession()
            if (repository.settings.audioRemoteRequestsEnabled() && DeviceInfo.isOnline(applicationContext)) {
                audioCommandRepository.pollAndNotify()
                lastAudioCommandPollAt = System.currentTimeMillis()
            }
            currentIntervalSec = repository.settings.intervalMovingSec()
            requestLocationUpdates(currentIntervalSec)
            repository.sendStatus("service_started")
            remoteCommandRepository.pollAndExecute()
            adaptiveLoop()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(subtitle: String? = null): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_tracking_title))
            .setContentText(subtitle ?: getString(R.string.notif_tracking_text))
            .setOngoing(true)
            .setContentIntent(pending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locations = result.locations.sortedBy { it.time }
            if (locations.isEmpty()) return
            scope.launch {
                locationMutex.withLock {
                    locations.forEach { handleLocation(it) }
                }
            }
        }
    }

    private fun requestLocationUpdates(intervalSec: Int) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) { stopSelf(); return }
        fused.removeLocationUpdates(callback)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalSec * 1000L)
            .setMinUpdateIntervalMillis((intervalSec * 1000L) / 2)
            .setMinUpdateDistanceMeters(0f) // filtro de distância é aplicado na gravação
            .build()
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private suspend fun handleLocation(location: Location) {
        val now = System.currentTimeMillis()
        val capturedMillis = location.time.takeIf { it > 0 } ?: now
        if (capturedMillis < now - MAX_LOCATION_AGE_MS || capturedMillis > now + MAX_FUTURE_DRIFT_MS) return
        if (location.hasAccuracy() && location.accuracy > repository.settings.maxAccuracyM()) return

        val speedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        val minDistance = repository.settings.minDistanceM()

        val previous = lastLocation
        if (previous != null) {
            val elapsedSeconds = ((capturedMillis - previous.time) / 1000.0).coerceAtLeast(0.001)
            val distanceM = previous.distanceTo(location).toDouble()
            val impliedSpeedKmh = (distanceM / elapsedSeconds) * 3.6
            if (impliedSpeedKmh > MAX_PLAUSIBLE_SPEED_KMH) return
            val moving = speedKmh > MOVING_SPEED_KMH || impliedSpeedKmh > MOVING_SPEED_KMH
            // Mantém curvas e pontos periódicos; remove apenas amostras redundantes.
            if (moving && distanceM < minDistance && elapsedSeconds < MAX_MOVING_GAP_SEC) return
            if (!moving && distanceM < 2.0 && elapsedSeconds < MIN_STOPPED_POINT_GAP_SEC) return
        }
        if (speedKmh > MOVING_SPEED_KMH || (previous?.distanceTo(location) ?: 0f) >= minDistance) {
            lastMovementAt = now
        }
        lastLocation = location
        val (routeSessionUuid, sequenceNo) = repository.settings.nextRoutePointIdentity()

        val entity = PendingLocationEntity(
            uuid = UUID.randomUUID().toString(),
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            speedKmh = if (location.hasSpeed()) speedKmh else null,
            bearing = if (location.hasBearing()) location.bearing.toDouble() else null,
            accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            networkType = DeviceInfo.networkType(applicationContext),
            battery = DeviceInfo.batteryLevel(applicationContext),
            gpsEnabled = DeviceInfo.isGpsEnabled(applicationContext),
            mockLocation = if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider,
            routeSessionUuid = routeSessionUuid,
            sequenceNo = sequenceNo,
            capturedAt = ApiClient.iso8601(Date(capturedMillis))
        )
        repository.saveLocation(entity)

        // Sincroniza imediatamente se online (senão o WorkManager cuida depois)
        if (DeviceInfo.isOnline(applicationContext)) {
            SyncWorker.enqueueNow(applicationContext)
        }
        updateNotificationSubtitle(speedKmh)
    }

    /** Ajusta a frequência conforme o estado do veículo (movimento / parado / parado longo). */
    private suspend fun adaptiveLoop() {
        while (scope.isActive) {
            delay(20_000)
            val settings = repository.settings
            if (!settings.trackingEnabled()) { stopSelf(); break }
            val stoppedForSec = (System.currentTimeMillis() - lastMovementAt) / 1000
            val target = when {
                stoppedForSec < 90 -> settings.intervalMovingSec()
                stoppedForSec < 15 * 60 -> settings.intervalStoppedSec()
                else -> settings.intervalIdleSec()
            }
            if (abs(target - currentIntervalSec) >= 5) {
                currentIntervalSec = target
                requestLocationUpdates(target)
            }
            val pollEveryMs = settings.audioCommandPollSeconds().coerceIn(30, 900) * 1000L
            if (settings.audioRemoteRequestsEnabled()
                && DeviceInfo.isOnline(applicationContext)
                && System.currentTimeMillis() - lastAudioCommandPollAt >= pollEveryMs) {
                audioCommandRepository.pollAndNotify()
                lastAudioCommandPollAt = System.currentTimeMillis()
            }
            if (DeviceInfo.isOnline(applicationContext)) remoteCommandRepository.pollAndExecute()
        }
    }

    private var lastNotifUpdate = 0L
    private suspend fun updateNotificationSubtitle(speedKmh: Double) {
        val now = System.currentTimeMillis()
        if (now - lastNotifUpdate < 30_000) return
        lastNotifUpdate = now
        val route = repository.currentRouteSnapshot()
        val subtitle = "${route.distanceKm.format1()} km · ${route.pointsCount} pontos · ${speedKmh.toInt()} km/h"
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIF_ID, buildNotification(subtitle))
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        trackingJob?.cancel()
        trackingJob = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001
        const val MOVING_SPEED_KMH = 3.0
        const val MAX_LOCATION_AGE_MS = 2 * 60 * 1000L
        const val MAX_FUTURE_DRIFT_MS = 30 * 1000L
        const val MAX_MOVING_GAP_SEC = 20.0
        const val MIN_STOPPED_POINT_GAP_SEC = 45.0
        const val MAX_PLAUSIBLE_SPEED_KMH = 250.0

        fun start(context: android.content.Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, LocationTrackingService::class.java))
        }
    }
}

private fun Double.format1(): String = String.format(java.util.Locale.US, "%.1f", this)
