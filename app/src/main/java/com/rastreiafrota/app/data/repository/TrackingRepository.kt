package com.rastreiafrota.app.data.repository

import android.content.Context
import com.rastreiafrota.app.data.local.AppDatabase
import com.rastreiafrota.app.data.local.PendingLocationEntity
import com.rastreiafrota.app.data.prefs.SettingsStore
import com.rastreiafrota.app.data.remote.ApiClient
import com.rastreiafrota.app.data.remote.BatchRequest
import com.rastreiafrota.app.data.remote.LocationDto
import com.rastreiafrota.app.data.remote.StatusRequest
import com.rastreiafrota.app.util.DeviceInfo
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
            val response = ApiClient.service(settings).sendStatus(
                StatusRequest(
                    battery = DeviceInfo.batteryLevel(context),
                    networkType = DeviceInfo.networkType(context),
                    gpsEnabled = DeviceInfo.isGpsEnabled(context),
                    pendingCount = dao.pendingCount(),
                    appVersion = DeviceInfo.appVersion(context),
                    event = event,
                    lastError = settings.lastApiError().takeIf { it.isNotBlank() }
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
        capturedAt = capturedAt
    )
}
