package com.rastreiafrota.app.data.repository

import android.content.Context
import android.location.Location
import com.rastreiafrota.app.data.local.AppDatabase
import com.rastreiafrota.app.data.local.PendingLocationEntity
import com.rastreiafrota.app.data.prefs.SettingsStore
import com.rastreiafrota.app.data.remote.ApiClient
import com.rastreiafrota.app.data.remote.BatchRequest
import com.rastreiafrota.app.data.remote.LocationDto
import com.rastreiafrota.app.data.remote.StatusRequest
import com.rastreiafrota.app.push.FirebaseBootstrap
import com.rastreiafrota.app.util.DeviceInfo
import com.rastreiafrota.app.util.TrackingReadiness
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Repositório de rastreamento: fila offline (Room) + sincronização em lote.
 * Fluxo: GPS → banco local → lote → confirmação da API → marca como sincronizada.
 */
class TrackingRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).pendingLocationDao()
    val settings = SettingsStore(context)

    suspend fun saveLocation(entity: PendingLocationEntity) {
        dao.insert(entity)
        enforceLocalLimits()
    }

    suspend fun pendingCount(): Int = dao.pendingCount()
    fun pendingCountFlow() = dao.pendingCountFlow()

    /** Prévia local do percurso ativo; funciona mesmo sem internet ou mapa externo. */
    suspend fun currentRouteSnapshot(): LocalRouteSnapshot {
        val activeSession = settings.currentRouteSession()
        val sessionUuid = activeSession.ifBlank { settings.latestRouteSession() }
        if (sessionUuid.isBlank()) return LocalRouteSnapshot.EMPTY
        val rows = dao.routePoints(sessionUuid, 2000)
        var distanceM = 0f
        var previous: PendingLocationEntity? = null
        val result = FloatArray(1)
        rows.forEach { point ->
            previous?.let {
                Location.distanceBetween(it.latitude, it.longitude, point.latitude, point.longitude, result)
                if (result[0].isFinite() && result[0] in 0f..5_000f) distanceM += result[0]
            }
            previous = point
        }
        val startedAt = settings.currentRouteStartedAt().takeIf { activeSession.isNotBlank() && it > 0 }
            ?: rows.firstOrNull()?.createdAt ?: 0L
        val endedAt = if (activeSession.isNotBlank()) System.currentTimeMillis() else rows.lastOrNull()?.createdAt ?: startedAt
        val goodAccuracy = rows.count { (it.accuracy ?: 999.0) <= 30.0 }
        return LocalRouteSnapshot(
            sessionUuid = sessionUuid,
            active = activeSession.isNotBlank(),
            points = rows.map { RouteTrailPoint(it.latitude, it.longitude, it.sequenceNo) },
            pointsCount = rows.size,
            distanceKm = distanceM / 1000.0,
            durationSeconds = if (startedAt > 0) ((endedAt - startedAt) / 1000L).coerceAtLeast(0) else 0,
            lastSpeedKmh = rows.lastOrNull()?.speedKmh,
            accuracyPercent = if (rows.isEmpty()) 0 else ((goodAccuracy * 100.0) / rows.size).toInt()
        )
    }

    /**
     * Sincroniza a fila em lotes. Nunca marca como enviada antes da confirmação.
     * Retorna Pair(enviadas, falhou?).
     */
    suspend fun syncPending(): Pair<Int, Boolean> {
        if (!settings.isActivated) return 0 to false
        val api = ApiClient.service(settings)
        val batchSize = settings.maxBatchSize()
        var totalSent = 0

        while (true) {
            val batch = dao.nextBatch(batchSize)
            if (batch.isEmpty()) break

            val request = BatchRequest(
                deviceUuid = settings.deviceUuid,
                batchUuid = UUID.randomUUID().toString(),
                locations = batch.map { it.toDto() }
            )

            val response = try {
                api.sendBatch(request)
            } catch (e: Exception) {
                val message = e.message ?: "Erro de rede"
                settings.setLastApiError(message)
                dao.markFailed(batch.map { it.uuid }, message)
                return totalSent to true
            }

            val body = response.body()
            if (response.isSuccessful && body?.success == true) {
                val accepted = body.data?.accepted.orEmpty()
                val rejected = body.data?.rejected.orEmpty()

                if (accepted.isNotEmpty()) dao.markSynced(accepted)
                if (rejected.isNotEmpty()) {
                    dao.markFailed(rejected.keys.toList(), rejected.values.firstOrNull())
                    // Rejeição definitiva de validação: não reenviar eternamente.
                    dao.markSynced(rejected.keys.toList())
                }

                totalSent += accepted.size
                settings.setLastSync(ApiClient.iso8601(java.util.Date()))
                settings.setLastApiError(null)
            } else {
                val message = body?.message ?: ApiClient.errorMessage(response)
                settings.setLastApiError(message)
                dao.markFailed(batch.map { it.uuid }, message)
                return totalSent to true
            }
        }

        return totalSent to false
    }

    /** Heartbeat de estado do aparelho, agora validando também a resposta HTTP. */
    suspend fun sendStatus(event: String? = null): Boolean {
        if (!settings.isActivated) return false
        return try {
            val readiness = TrackingReadiness.snapshot(context)
            val response = ApiClient.service(settings).sendStatus(
                StatusRequest(
                    battery = DeviceInfo.batteryLevel(context),
                    networkType = DeviceInfo.networkType(context),
                    gpsEnabled = DeviceInfo.isGpsEnabled(context),
                    pendingCount = dao.pendingCount(),
                    appVersion = DeviceInfo.appVersion(context),
                    event = event,
                    lastError = settings.lastApiError().takeIf { it.isNotBlank() },
                    backgroundLocation = readiness.backgroundLocation,
                    notificationsEnabled = readiness.notifications,
                    batteryOptimizationIgnored = readiness.batteryUnrestricted,
                    pushConfigured = readiness.firebaseConfigured
                )
            )
            if (response.isSuccessful && response.body()?.success == true) {
                true
            } else {
                settings.setLastApiError(ApiClient.errorMessage(response))
                false
            }
        } catch (e: Exception) {
            settings.setLastApiError(e.message ?: "Falha ao enviar status")
            false
        }
    }

    /** Busca configuração remota e aplica localmente. */
    suspend fun refreshRemoteConfig(): Boolean {
        if (!settings.isActivated) return false
        return try {
            val response = ApiClient.service(settings).getConfig()
            if (!response.isSuccessful || response.body()?.success != true) {
                settings.setLastApiError(ApiClient.errorMessage(response))
                return false
            }
            val data = response.body()?.data ?: return false
            data.trackingConfig?.let { settings.saveTrackingConfig(it) }
            data.audioConfig?.let { settings.saveAudioConfig(it) }
            settings.saveFirebaseConfig(data.firebaseConfig)
            FirebaseBootstrap.initialize(context)
            data.vehicle?.let {
                settings.setVehicleInfo(it.plate, settings.companyName(), settings.deviceName())
            }
            if (data.deviceStatus != null && data.deviceStatus != "active") {
                settings.setTrackingEnabled(false)
            }
            settings.setLastApiError(null)
            true
        } catch (e: Exception) {
            settings.setLastApiError(e.message ?: "Falha ao consultar configuração")
            false
        }
    }

    /** Teste autenticado: diferencia servidor disponível de token/assinatura válidos. */
    suspend fun testAuthenticatedConnection(): Pair<Boolean, String> {
        if (!settings.isActivated) return false to "Dispositivo não ativado."
        return try {
            val health = ApiClient.service(settings).health()
            if (!health.isSuccessful || health.body()?.success != true) {
                return false to "Servidor indisponível: HTTP ${health.code()}."
            }

            val authenticated = ApiClient.service(settings).getConfig()
            if (authenticated.isSuccessful && authenticated.body()?.success == true) {
                settings.setLastApiError(null)
                true to "Comunicação autenticada OK."
            } else {
                val message = ApiClient.errorMessage(authenticated)
                settings.setLastApiError(message)
                false to message
            }
        } catch (e: Exception) {
            val message = e.message ?: "Falha de comunicação"
            settings.setLastApiError(message)
            false to message
        }
    }

    /** Política de retenção local: nunca descarta pendentes; limpa apenas sincronizadas. */
    private suspend fun enforceLocalLimits() {
        val retentionMs = TimeUnit.DAYS.toMillis(settings.retentionDays().toLong())
        dao.deleteSyncedBefore(System.currentTimeMillis() - retentionMs)
        dao.trimSynced(settings.maxLocalRecords())
    }

    private fun PendingLocationEntity.toDto() = LocationDto(
        uuid = uuid,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        speedKmh = speedKmh,
        bearing = bearing,
        accuracy = accuracy,
        battery = battery,
        networkType = networkType,
        gpsEnabled = gpsEnabled,
        mockLocation = mockLocation,
        routeSessionUuid = routeSessionUuid,
        sequenceNo = sequenceNo,
        capturedAt = capturedAt
    )
}

data class RouteTrailPoint(val latitude: Double, val longitude: Double, val sequenceNo: Long)

data class LocalRouteSnapshot(
    val sessionUuid: String,
    val active: Boolean,
    val points: List<RouteTrailPoint>,
    val pointsCount: Int,
    val distanceKm: Double,
    val durationSeconds: Long,
    val lastSpeedKmh: Double?,
    val accuracyPercent: Int
) {
    companion object {
        val EMPTY = LocalRouteSnapshot("", false, emptyList(), 0, 0.0, 0, null, 0)
    }
}
