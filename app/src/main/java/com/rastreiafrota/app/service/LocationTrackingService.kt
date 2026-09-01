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
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.rastreiafrota.app.App
import com.rastreiafrota.app.R
import com.rastreiafrota.app.data.local.PendingLocationEntity
import com.rastreiafrota.app.data.remote.ApiClient
import com.rastreiafrota.app.data.repository.AudioCommandRepository
import com.rastreiafrota.app.data.repository.RemoteCommandRepository
import com.rastreiafrota.app.data.repository.TrackingRepository
import com.rastreiafrota.app.ui.MainActivity
import com.rastreiafrota.app.util.DeviceInfo
import com.rastreiafrota.app.util.TrackingPointPolicy
import com.rastreiafrota.app.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Serviço visível de rastreamento contínuo.
 *
 * O GPS é amostrado com alta precisão, os pontos são persistidos no Room antes de qualquer
 * tentativa de rede e um watchdog reinicia a assinatura quando o Android deixa de entregar
 * atualizações. A política mantém detalhes de curvas e deslocamentos a pé sem gravar jitter
 * excessivo quando o aparelho está parado.
 */
class LocationTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var repository: TrackingRepository
    private lateinit var audioCommandRepository: AudioCommandRepository
    private lateinit var remoteCommandRepository: RemoteCommandRepository
    private var trackingJob: Job? = null

    private var currentIntervalSec = MOVING_INTERVAL_SEC
    private var lastMovementAt = System.currentTimeMillis()
    private var lastLocation: Location? = null
    private var lastLocationCallbackAt = 0L
    private var lastLocationRequestAt = 0L
    private var lastSavedAt = 0L
    private var lastHeartbeatAt = 0L
    private var lastAudioCommandPollAt = 0L
    private var consecutiveRejected = 0
    private val locationMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        repository = TrackingRepository(applicationContext)
        audioCommandRepository = AudioCommandRepository(applicationContext)
        remoteCommandRepository = RemoteCommandRepository(applicationContext)
        fused = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasPreciseLocationPermission()) {
            scope.launch {
                repository.settings.setLastTrackingError(
                    "Permissão de localização precisa ausente; o serviço de rastreamento não foi iniciado."
                )
                repository.sendStatus("location_permission_missing")
            }
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        if (trackingJob?.isActive == true) return START_STICKY

        trackingJob = scope.launch {
            if (!repository.settings.isActivated || !repository.settings.trackingEnabled()) {
                stopSelf()
                return@launch
            }

            repository.refreshRemoteConfig()
            if (!repository.settings.trackingEnabled()) {
                repository.settings.setLastTrackingError(
                    "O servidor informou que o rastreamento está desativado para este dispositivo."
                )
                stopSelf()
                return@launch
            }

            repository.settings.ensureRouteSession()
            if (repository.settings.audioRemoteRequestsEnabled() && DeviceInfo.isOnline(applicationContext)) {
                audioCommandRepository.pollAndNotify()
                lastAudioCommandPollAt = System.currentTimeMillis()
            }

            currentIntervalSec = movingInterval()
            requestLocationUpdates(currentIntervalSec)
            repository.sendStatus("service_started")
            lastHeartbeatAt = System.currentTimeMillis()
            remoteCommandRepository.pollAndExecute()
            adaptiveLoop()
        }
        return START_STICKY
    }

    private fun hasPreciseLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

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
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_tracking_title))
            .setContentText(subtitle ?: getString(R.string.notif_tracking_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locations = result.locations.sortedBy { it.time }
            if (locations.isEmpty()) return
            lastLocationCallbackAt = System.currentTimeMillis()
            scope.launch {
                locationMutex.withLock {
                    locations.forEach { handleLocation(it) }
                }
            }
        }
    }

    private fun requestLocationUpdates(intervalSec: Int) {
        if (!hasPreciseLocationPermission()) {
            scope.launch {
                repository.settings.setLastTrackingError("A permissão de localização foi removida durante o rastreamento.")
                repository.sendStatus("location_permission_missing")
            }
            stopSelf()
            return
        }

        lastLocationRequestAt = System.currentTimeMillis()
        fused.removeLocationUpdates(callback)

        val intervalMs = intervalSec.coerceAtLeast(MIN_REQUEST_INTERVAL_SEC) * 1000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setMinUpdateIntervalMillis(max(500L, intervalMs / 2L))
            .setMaxUpdateDelayMillis(intervalMs)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()

        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnSuccessListener {
                scope.launch { repository.settings.setLastTrackingError(null) }
            }
            .addOnFailureListener { error ->
                scope.launch {
                    repository.settings.setLastTrackingError(
                        "Falha ao iniciar o GPS: ${error.message ?: error.javaClass.simpleName}"
                    )
                    repository.sendStatus("gps_request_failed")
                }
            }
    }

    private suspend fun handleLocation(location: Location) {
        val now = System.currentTimeMillis()
        val capturedMillis = location.time.takeIf { it > 0 } ?: now

        if (capturedMillis < now - MAX_LOCATION_AGE_MS) {
            reject("Localização antiga descartada; aguardando uma leitura atual do GPS.")
            return
        }
        if (capturedMillis > now + MAX_FUTURE_DRIFT_MS) {
            reject("Horário da localização está no futuro; confira data e hora automáticas do celular.")
            return
        }

        val accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        if (accuracyM != null && accuracyM > HARD_MAX_ACCURACY_M) {
            reject("Sinal de GPS muito impreciso (${accuracyM.toInt()} m); tentando novamente.")
            return
        }

        val speedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        val previous = lastLocation
        var distanceM = Double.POSITIVE_INFINITY
        var elapsedSeconds = Double.POSITIVE_INFINITY
        var impliedSpeedKmh = 0.0

        if (previous != null) {
            val previousMillis = previous.time.takeIf { it > 0 } ?: capturedMillis
            val elapsedMillis = capturedMillis - previousMillis
            if (elapsedMillis <= 0L) return

            elapsedSeconds = elapsedMillis / 1000.0
            distanceM = previous.distanceTo(location).toDouble()
            impliedSpeedKmh = (distanceM / elapsedSeconds) * 3.6
            if (impliedSpeedKmh > MAX_PLAUSIBLE_SPEED_KMH) {
                reject("Salto impossível de GPS descartado (${impliedSpeedKmh.toInt()} km/h calculados).")
                return
            }
        }

        val movementSpeedKmh = max(speedKmh, impliedSpeedKmh)
        val configuredMinDistance = repository.settings.minDistanceM()
        val effectiveMinDistance = TrackingPointPolicy.effectiveMinDistance(
            configuredMinDistance,
            movementSpeedKmh
        )
        val shouldStore = TrackingPointPolicy.shouldStore(
            hasPrevious = previous != null,
            distanceM = distanceM,
            elapsedSeconds = elapsedSeconds,
            movementSpeedKmh = movementSpeedKmh,
            accuracyM = accuracyM,
            configuredMaxAccuracyM = repository.settings.maxAccuracyM(),
            millisSinceLastSaved = if (lastSavedAt > 0L) capturedMillis - lastSavedAt else Long.MAX_VALUE,
            configuredMinDistanceM = configuredMinDistance,
            previousBearing = previous?.takeIf { it.hasBearing() }?.bearing?.toDouble(),
            currentBearing = location.takeIf { it.hasBearing() }?.bearing?.toDouble()
        )
        if (!shouldStore) return

        val moving = movementSpeedKmh > TrackingPointPolicy.MOVING_SPEED_KMH
        if (moving || distanceM >= effectiveMinDistance) lastMovementAt = now
        lastLocation = location

        val (routeSessionUuid, sequenceNo) = repository.settings.nextRoutePointIdentity()
        val entity = PendingLocationEntity(
            uuid = UUID.randomUUID().toString(),
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            speedKmh = if (location.hasSpeed()) speedKmh else null,
            bearing = if (location.hasBearing()) location.bearing.toDouble() else null,
            accuracy = accuracyM,
            networkType = DeviceInfo.networkType(applicationContext),
            battery = DeviceInfo.batteryLevel(applicationContext),
            gpsEnabled = DeviceInfo.isGpsEnabled(applicationContext),
            mockLocation = if (Build.VERSION.SDK_INT >= 31) {
                location.isMock
            } else {
                @Suppress("DEPRECATION")
                location.isFromMockProvider
            },
            routeSessionUuid = routeSessionUuid,
            sequenceNo = sequenceNo,
            capturedAt = ApiClient.iso8601(Date(capturedMillis))
        )

        repository.saveLocation(entity)
        repository.settings.setLastCapture(entity.capturedAt)
        repository.settings.setLastTrackingError(null)
        consecutiveRejected = 0
        lastSavedAt = capturedMillis

        if (DeviceInfo.isOnline(applicationContext)) SyncWorker.enqueueNow(applicationContext)
        updateNotificationSubtitle(speedKmh, accuracyM)
    }

    private suspend fun reject(message: String) {
        consecutiveRejected += 1
        if (consecutiveRejected == 1 || consecutiveRejected % 10 == 0) {
            repository.settings.setLastTrackingError(message)
        }
    }

    private suspend fun movingInterval(): Int =
        min(repository.settings.intervalMovingSec(), MOVING_INTERVAL_SEC)
            .coerceAtLeast(MIN_REQUEST_INTERVAL_SEC)

    private suspend fun stoppedInterval(): Int =
        min(repository.settings.intervalStoppedSec(), STOPPED_INTERVAL_SEC)
            .coerceAtLeast(MOVING_INTERVAL_SEC)

    private suspend fun idleInterval(): Int =
        min(repository.settings.intervalIdleSec(), IDLE_INTERVAL_SEC)
            .coerceAtLeast(STOPPED_INTERVAL_SEC)

    private suspend fun adaptiveLoop() {
        while (scope.isActive) {
            delay(WATCHDOG_TICK_MS)
            val settings = repository.settings
            if (!settings.trackingEnabled()) {
                stopSelf()
                break
            }

            val now = System.currentTimeMillis()
            val stoppedForSec = (now - lastMovementAt) / 1000L
            val target = when {
                stoppedForSec < 90L -> movingInterval()
                stoppedForSec < 15L * 60L -> stoppedInterval()
                else -> idleInterval()
            }
            if (target != currentIntervalSec) {
                currentIntervalSec = target
                requestLocationUpdates(target)
            }

            val lastGpsActivity = if (lastLocationCallbackAt > 0L) lastLocationCallbackAt else lastLocationRequestAt
            val gpsSilenceMs = now - lastGpsActivity
            val watchdogLimitMs = max(GPS_WATCHDOG_MIN_MS, currentIntervalSec * 4_000L)
            val gpsStalled = lastGpsActivity > 0L && gpsSilenceMs > watchdogLimitMs
            if (gpsStalled) {
                settings.setLastTrackingError(
                    "Sem atualização do GPS há ${gpsSilenceMs / 60_000L} min; reiniciando a captura."
                )
                requestLocationUpdates(currentIntervalSec)
            }

            if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                repository.sendStatus(if (gpsStalled) "gps_waiting" else "tracking")
                lastHeartbeatAt = now
            }

            val pollEveryMs = settings.audioCommandPollSeconds().coerceIn(30, 900) * 1000L
            if (
                settings.audioRemoteRequestsEnabled() &&
                DeviceInfo.isOnline(applicationContext) &&
                now - lastAudioCommandPollAt >= pollEveryMs
            ) {
                audioCommandRepository.pollAndNotify()
                lastAudioCommandPollAt = now
            }

            if (DeviceInfo.isOnline(applicationContext)) remoteCommandRepository.pollAndExecute()
        }
    }

    private var lastNotifUpdate = 0L

    private suspend fun updateNotificationSubtitle(speedKmh: Double, accuracyM: Double?) {
        val now = System.currentTimeMillis()
        if (now - lastNotifUpdate < NOTIFICATION_UPDATE_MS) return
        lastNotifUpdate = now
        val route = repository.currentRouteSnapshot()
        val accuracy = accuracyM?.let { " · ±${it.toInt()} m" }.orEmpty()
        val subtitle = "${route.distanceKm.format1()} km · ${route.pointsCount} pontos · ${speedKmh.toInt()} km/h$accuracy"
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIF_ID, buildNotification(subtitle))
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
        // Cadencia do GPS. Antes o minimo era 3 s (e o teto 5 s), o que a 60 km/h dava
        // 50 a 83 m entre pontos: uma rotatoria inteira cabia num intervalo e o tracado
        // saia por fora da via. Com 1 s o desenho acompanha a estrada. Custa mais bateria.
        const val MOVING_INTERVAL_SEC = 1
        const val STOPPED_INTERVAL_SEC = 10
        const val IDLE_INTERVAL_SEC = 60
        const val MIN_REQUEST_INTERVAL_SEC = 1
        const val MAX_LOCATION_AGE_MS = 2 * 60 * 1000L
        const val MAX_FUTURE_DRIFT_MS = 30 * 1000L
        const val HARD_MAX_ACCURACY_M = 250.0
        const val MAX_PLAUSIBLE_SPEED_KMH = 250.0
        const val WATCHDOG_TICK_MS = 20_000L
        const val GPS_WATCHDOG_MIN_MS = 2 * 60 * 1000L
        const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
        const val NOTIFICATION_UPDATE_MS = 15_000L

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, LocationTrackingService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, LocationTrackingService::class.java))
        }
    }
}

private fun Double.format1(): String = String.format(java.util.Locale.US, "%.1f", this)
