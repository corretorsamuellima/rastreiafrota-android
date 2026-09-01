package com.rastreiafrota.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rastreiafrota.app.BuildConfig
import com.rastreiafrota.app.data.remote.AudioConfigData
import com.rastreiafrota.app.data.remote.FirebaseConfigData
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "rf_settings")

class SettingsStore(private val context: Context) {
    private val secure by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "rf_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var accessToken: String?
        get() = secure.getString("access_token", null)
        set(v) { secure.edit().putString("access_token", v).apply() }
    var refreshToken: String?
        get() = secure.getString("refresh_token", null)
        set(v) { secure.edit().putString("refresh_token", v).apply() }
    var hmacSecret: String?
        get() = secure.getString("hmac_secret", null)
        set(v) { secure.edit().putString("hmac_secret", v).apply() }

    fun saveFirebaseConfig(config: FirebaseConfigData?) {
        val edit = secure.edit()
        if (config?.valid() == true) {
            edit.putString("firebase_app_id", config.appId)
                .putString("firebase_api_key", config.apiKey)
                .putString("firebase_project_id", config.projectId)
                .putString("firebase_sender_id", config.senderId)
        } else {
            edit.remove("firebase_app_id").remove("firebase_api_key")
                .remove("firebase_project_id").remove("firebase_sender_id")
        }
        edit.apply()
    }

    fun firebaseConfig(): FirebaseConfigData? {
        val config = FirebaseConfigData(
            appId = secure.getString("firebase_app_id", "").orEmpty(),
            apiKey = secure.getString("firebase_api_key", "").orEmpty(),
            projectId = secure.getString("firebase_project_id", "").orEmpty(),
            senderId = secure.getString("firebase_sender_id", "").orEmpty()
        )
        return config.takeIf { it.valid() }
    }

    fun clearCredentials() { secure.edit().clear().apply() }
    val isActivated: Boolean get() = !accessToken.isNullOrEmpty()

    val deviceUuid: String
        get() {
            var uuid = secure.getString("device_uuid", null)
            if (uuid == null) {
                uuid = UUID.randomUUID().toString()
                secure.edit().putString("device_uuid", uuid).apply()
            }
            return uuid
        }

    private val kBaseUrl = stringPreferencesKey("base_url")
    private val kVehiclePlate = stringPreferencesKey("vehicle_plate")
    private val kCompanyName = stringPreferencesKey("company_name")
    private val kDeviceName = stringPreferencesKey("device_name")
    private val kTrackingEnabled = booleanPreferencesKey("tracking_enabled")
    private val kIntervalMoving = intPreferencesKey("interval_moving_sec")
    private val kIntervalStopped = intPreferencesKey("interval_stopped_sec")
    private val kIntervalIdle = intPreferencesKey("interval_idle_sec")
    private val kMinDistance = intPreferencesKey("min_distance_m")
    private val kMaxAccuracy = intPreferencesKey("min_accuracy_m")
    private val kMaxBatch = intPreferencesKey("max_batch_size")
    private val kRetentionDays = intPreferencesKey("local_retention_days")
    private val kMaxLocal = intPreferencesKey("max_local_records")
    private val kLastSync = stringPreferencesKey("last_sync_at")
    private val kLastError = stringPreferencesKey("last_api_error")
    private val kLastCapture = stringPreferencesKey("last_capture_at")
    private val kLastTrackingError = stringPreferencesKey("last_tracking_error")
    private val kRouteSession = stringPreferencesKey("route_session_uuid")
    private val kRouteSequence = longPreferencesKey("route_sequence")
    private val kRouteStartedAt = longPreferencesKey("route_started_at")
    private val kLastRouteSession = stringPreferencesKey("last_route_session_uuid")

    private val kAudioEnabled = booleanPreferencesKey("audio_enabled")
    private val kAudioSosEnabled = booleanPreferencesKey("audio_sos_enabled")
    private val kAudioDownloadEnabled = booleanPreferencesKey("audio_download_enabled")
    private val kAudioChunkSeconds = intPreferencesKey("audio_chunk_seconds")
    private val kAudioSessionMaxMinutes = intPreferencesKey("audio_session_max_minutes")
    private val kAudioMonthlyLimitMb = intPreferencesKey("audio_monthly_limit_mb")
    private val kAudioRetentionDays = intPreferencesKey("audio_retention_days")
    private val kAudioConsentVersion = stringPreferencesKey("audio_consent_version")
    private val kAudioConsentAcceptedVersion = stringPreferencesKey("audio_consent_accepted_version")
    private val kAudioRecording = booleanPreferencesKey("audio_recording")
    private val kAudioPaused = booleanPreferencesKey("audio_paused")
    private val kAudioRecordingType = stringPreferencesKey("audio_recording_type")
    private val kLastAudioError = stringPreferencesKey("last_audio_error")
    private val kAudioRemoteRequestsEnabled = booleanPreferencesKey("audio_remote_requests_enabled")
    private val kAudioSchedulingEnabled = booleanPreferencesKey("audio_scheduling_enabled")
    private val kAudioScheduleMaxMinutes = intPreferencesKey("audio_schedule_max_minutes")
    private val kAudioCommandPollSeconds = intPreferencesKey("audio_command_poll_seconds")
    private val kLastAudioCommandCheck = stringPreferencesKey("last_audio_command_check")
    private val kLastAudioCommandMessage = stringPreferencesKey("last_audio_command_message")
    private val kLastPushSync = stringPreferencesKey("last_push_sync")
    private val kLastPushError = stringPreferencesKey("last_push_error")

    suspend fun baseUrl(): String {
        val saved = context.dataStore.data.first()[kBaseUrl]
        return if (BuildConfig.ALLOW_SERVER_CHANGE && !saved.isNullOrBlank()) saved else BuildConfig.BASE_URL
    }

    suspend fun setBaseUrl(url: String) {
        if (!BuildConfig.ALLOW_SERVER_CHANGE) return
        val normalized = url.trim().let { if (it.endsWith('/')) it else "$it/" }
        context.dataStore.edit { it[kBaseUrl] = normalized }
    }

    suspend fun setVehicleInfo(plate: String?, company: String?, deviceName: String?) {
        context.dataStore.edit {
            it[kVehiclePlate] = plate ?: ""
            it[kCompanyName] = company ?: ""
            it[kDeviceName] = deviceName ?: ""
        }
    }
    suspend fun vehiclePlate(): String = context.dataStore.data.first()[kVehiclePlate] ?: ""
    suspend fun companyName(): String = context.dataStore.data.first()[kCompanyName] ?: ""
    suspend fun deviceName(): String = context.dataStore.data.first()[kDeviceName] ?: ""

    suspend fun setTrackingEnabled(enabled: Boolean) { context.dataStore.edit { it[kTrackingEnabled] = enabled } }
    suspend fun trackingEnabled(): Boolean = context.dataStore.data.first()[kTrackingEnabled] ?: false

    suspend fun saveTrackingConfig(config: Map<String, Int>) {
        context.dataStore.edit { prefs ->
            config["interval_moving_sec"]?.let { prefs[kIntervalMoving] = it }
            config["interval_stopped_sec"]?.let { prefs[kIntervalStopped] = it }
            config["interval_idle_sec"]?.let { prefs[kIntervalIdle] = it }
            config["min_distance_m"]?.let { prefs[kMinDistance] = it }
            config["min_accuracy_m"]?.let { prefs[kMaxAccuracy] = it }
            config["max_batch_size"]?.let { prefs[kMaxBatch] = it }
            config["local_retention_days"]?.let { prefs[kRetentionDays] = it }
            config["max_local_records"]?.let { prefs[kMaxLocal] = it }
        }
    }
    suspend fun intervalMovingSec(): Int = context.dataStore.data.first()[kIntervalMoving] ?: 10
    suspend fun intervalStoppedSec(): Int = context.dataStore.data.first()[kIntervalStopped] ?: 60
    suspend fun intervalIdleSec(): Int = context.dataStore.data.first()[kIntervalIdle] ?: 300
    suspend fun minDistanceM(): Int = context.dataStore.data.first()[kMinDistance] ?: 20
    suspend fun maxAccuracyM(): Int = context.dataStore.data.first()[kMaxAccuracy] ?: 50
    suspend fun maxBatchSize(): Int = context.dataStore.data.first()[kMaxBatch] ?: 100
    suspend fun retentionDays(): Int = context.dataStore.data.first()[kRetentionDays] ?: 7
    suspend fun maxLocalRecords(): Int = context.dataStore.data.first()[kMaxLocal] ?: 50000

    suspend fun setLastSync(iso: String) { context.dataStore.edit { it[kLastSync] = iso } }
    suspend fun lastSync(): String = context.dataStore.data.first()[kLastSync] ?: "—"
    suspend fun setLastApiError(msg: String?) { context.dataStore.edit { it[kLastError] = msg ?: "" } }
    suspend fun lastApiError(): String = context.dataStore.data.first()[kLastError] ?: ""
    suspend fun setLastCapture(iso: String) { context.dataStore.edit { it[kLastCapture] = iso } }
    suspend fun lastCapture(): String = context.dataStore.data.first()[kLastCapture] ?: "—"
    suspend fun setLastTrackingError(msg: String?) { context.dataStore.edit { it[kLastTrackingError] = msg ?: "" } }
    suspend fun lastTrackingError(): String = context.dataStore.data.first()[kLastTrackingError] ?: ""

    /** Inicia explicitamente um novo percurso. Use apenas na transição pausado → ativo. */
    suspend fun startNewRouteSession(): String {
        val uuid = UUID.randomUUID().toString()
        context.dataStore.edit {
            it[kRouteSession] = uuid
            it[kRouteSequence] = 0L
            it[kRouteStartedAt] = System.currentTimeMillis()
        }
        return uuid
    }

    /** Mantém a sessão após reinício do Android; cria uma somente se ainda não existir. */
    suspend fun ensureRouteSession(): String {
        var uuid = ""
        context.dataStore.edit {
            uuid = it[kRouteSession].orEmpty()
            if (uuid.isBlank()) {
                uuid = UUID.randomUUID().toString()
                it[kRouteSession] = uuid
                it[kRouteSequence] = 0L
                it[kRouteStartedAt] = System.currentTimeMillis()
            }
        }
        return uuid
    }

    /** Reserva de forma atômica a próxima sequência do percurso. */
    suspend fun nextRoutePointIdentity(): Pair<String, Long> {
        var uuid = ""
        var sequence = 0L
        context.dataStore.edit {
            uuid = it[kRouteSession].orEmpty()
            if (uuid.isBlank()) {
                uuid = UUID.randomUUID().toString()
                it[kRouteSession] = uuid
                it[kRouteStartedAt] = System.currentTimeMillis()
            }
            sequence = (it[kRouteSequence] ?: 0L) + 1L
            it[kRouteSequence] = sequence
        }
        return uuid to sequence
    }

    suspend fun currentRouteSession(): String = context.dataStore.data.first()[kRouteSession].orEmpty()
    suspend fun latestRouteSession(): String {
        val values = context.dataStore.data.first()
        return values[kRouteSession].orEmpty().ifBlank { values[kLastRouteSession].orEmpty() }
    }
    suspend fun currentRouteStartedAt(): Long = context.dataStore.data.first()[kRouteStartedAt] ?: 0L
    suspend fun finishRouteSession() {
        context.dataStore.edit {
            it[kRouteSession]?.takeIf(String::isNotBlank)?.let { uuid -> it[kLastRouteSession] = uuid }
            it.remove(kRouteSession)
            it.remove(kRouteSequence)
            it.remove(kRouteStartedAt)
        }
    }

    suspend fun saveAudioConfig(config: AudioConfigData) {
        context.dataStore.edit {
            it[kAudioEnabled] = config.enabled
            it[kAudioSosEnabled] = config.sosEnabled
            it[kAudioDownloadEnabled] = config.downloadEnabled
            it[kAudioChunkSeconds] = config.chunkSeconds.coerceIn(15, 120)
            it[kAudioSessionMaxMinutes] = config.sessionMaxMinutes.coerceIn(1, 480)
            it[kAudioMonthlyLimitMb] = config.monthlyLimitMb.coerceAtLeast(10)
            it[kAudioRetentionDays] = config.retentionDays.coerceIn(1, 3650)
            it[kAudioConsentVersion] = config.consentVersion
            it[kAudioRemoteRequestsEnabled] = config.remoteRequestsEnabled
            it[kAudioSchedulingEnabled] = config.schedulingEnabled
            it[kAudioScheduleMaxMinutes] = config.scheduleMaxMinutes.coerceIn(1, 120)
            it[kAudioCommandPollSeconds] = config.commandPollSeconds.coerceIn(30, 900)
        }
    }
    suspend fun audioEnabled(): Boolean = context.dataStore.data.first()[kAudioEnabled] ?: false
    suspend fun audioSosEnabled(): Boolean = context.dataStore.data.first()[kAudioSosEnabled] ?: false
    suspend fun audioDownloadEnabled(): Boolean = context.dataStore.data.first()[kAudioDownloadEnabled] ?: false
    suspend fun audioChunkSeconds(): Int = context.dataStore.data.first()[kAudioChunkSeconds] ?: 30
    suspend fun audioSessionMaxMinutes(): Int = context.dataStore.data.first()[kAudioSessionMaxMinutes] ?: 30
    suspend fun audioMonthlyLimitMb(): Int = context.dataStore.data.first()[kAudioMonthlyLimitMb] ?: 500
    suspend fun audioRetentionDays(): Int = context.dataStore.data.first()[kAudioRetentionDays] ?: 30
    suspend fun audioConsentVersion(): String = context.dataStore.data.first()[kAudioConsentVersion] ?: "1.0"
    suspend fun acceptAudioConsent(version: String) { context.dataStore.edit { it[kAudioConsentAcceptedVersion] = version } }
    suspend fun isAudioConsentAccepted(): Boolean {
        val p = context.dataStore.data.first()
        return (p[kAudioConsentAcceptedVersion] ?: "") == (p[kAudioConsentVersion] ?: "1.0")
    }
    suspend fun setAudioRecording(recording: Boolean, type: String = "safety") {
        context.dataStore.edit {
            it[kAudioRecording] = recording
            it[kAudioRecordingType] = type
            if (!recording) it[kAudioPaused] = false
        }
    }
    suspend fun audioRecording(): Boolean = context.dataStore.data.first()[kAudioRecording] ?: false
    suspend fun setAudioPaused(paused: Boolean) { context.dataStore.edit { it[kAudioPaused] = paused } }
    suspend fun audioPaused(): Boolean = context.dataStore.data.first()[kAudioPaused] ?: false
    suspend fun audioRecordingType(): String = context.dataStore.data.first()[kAudioRecordingType] ?: "safety"
    suspend fun setLastAudioError(msg: String?) { context.dataStore.edit { it[kLastAudioError] = msg ?: "" } }
    suspend fun lastAudioError(): String = context.dataStore.data.first()[kLastAudioError] ?: ""
    suspend fun audioRemoteRequestsEnabled(): Boolean = context.dataStore.data.first()[kAudioRemoteRequestsEnabled] ?: false
    suspend fun audioSchedulingEnabled(): Boolean = context.dataStore.data.first()[kAudioSchedulingEnabled] ?: false
    suspend fun audioScheduleMaxMinutes(): Int = context.dataStore.data.first()[kAudioScheduleMaxMinutes] ?: 15
    suspend fun audioCommandPollSeconds(): Int = context.dataStore.data.first()[kAudioCommandPollSeconds] ?: 60
    suspend fun setLastAudioCommandCheck(value: String, message: String) { context.dataStore.edit { it[kLastAudioCommandCheck] = value; it[kLastAudioCommandMessage] = message } }
    suspend fun lastAudioCommandCheck(): String = context.dataStore.data.first()[kLastAudioCommandCheck] ?: "—"
    suspend fun lastAudioCommandMessage(): String = context.dataStore.data.first()[kLastAudioCommandMessage] ?: "Nenhuma solicitação consultada."

    suspend fun setPushStatus(lastSync: String?, error: String?) {
        context.dataStore.edit {
            if (lastSync != null) it[kLastPushSync] = lastSync
            it[kLastPushError] = error ?: ""
        }
    }
    suspend fun lastPushSync(): String = context.dataStore.data.first()[kLastPushSync] ?: "—"
    suspend fun lastPushError(): String = context.dataStore.data.first()[kLastPushError] ?: ""
}
