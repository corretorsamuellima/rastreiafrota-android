package com.rastreiafrota.app.data.remote

import com.google.gson.annotations.SerializedName

data class ApiEnvelope<T>(val success: Boolean, val message: String, val data: T?)

data class ActivateRequest(
    @SerializedName("activation_code") val activationCode: String,
    @SerializedName("device_uuid") val deviceUuid: String,
    val manufacturer: String,
    val model: String,
    @SerializedName("android_version") val androidVersion: String,
    @SerializedName("app_version") val appVersion: String
)

data class ActivateData(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("hmac_secret") val hmacSecret: String,
    val device: DeviceInfoDto?,
    val company: CompanyDto?,
    val vehicle: VehicleDto?,
    @SerializedName("tracking_config") val trackingConfig: Map<String, Int>?,
    @SerializedName("audio_config") val audioConfig: AudioConfigData?,
    @SerializedName("min_version_code") val minVersionCode: Int?
)

data class DeviceInfoDto(val id: Int, val name: String?)
data class CompanyDto(val name: String?)
data class VehicleDto(val id: Int, val plate: String?, val model: String?)
data class RefreshRequest(@SerializedName("refresh_token") val refreshToken: String)

data class LocationDto(
    val uuid: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    @SerializedName("speed_kmh") val speedKmh: Double?,
    val bearing: Double?,
    val accuracy: Double?,
    val battery: Int?,
    @SerializedName("network_type") val networkType: String?,
    @SerializedName("gps_enabled") val gpsEnabled: Boolean,
    @SerializedName("mock_location") val mockLocation: Boolean,
    @SerializedName("captured_at") val capturedAt: String
)

data class BatchRequest(
    @SerializedName("device_uuid") val deviceUuid: String,
    @SerializedName("batch_uuid") val batchUuid: String,
    val locations: List<LocationDto>
)

data class BatchData(
    val accepted: List<String>?,
    val rejected: Map<String, String>?,
    @SerializedName("server_time") val serverTime: String?
)

data class StatusRequest(
    val battery: Int?,
    @SerializedName("network_type") val networkType: String?,
    @SerializedName("gps_enabled") val gpsEnabled: Boolean,
    @SerializedName("pending_count") val pendingCount: Int,
    @SerializedName("app_version") val appVersion: String,
    val event: String? = null,
    @SerializedName("last_error") val lastError: String? = null
)

data class ConfigData(
    @SerializedName("tracking_config") val trackingConfig: Map<String, Int>?,
    @SerializedName("audio_config") val audioConfig: AudioConfigData?,
    val vehicle: VehicleDto?,
    @SerializedName("device_status") val deviceStatus: String?,
    @SerializedName("min_version_code") val minVersionCode: Int?,
    @SerializedName("server_time") val serverTime: String?
)

data class VersionData(val version: VersionInfo?)
data class VersionInfo(
    @SerializedName("version_name") val versionName: String?,
    @SerializedName("version_code") val versionCode: Int?,
    @SerializedName("min_version_code") val minVersionCode: Int?,
    val mandatory: Int?,
    @SerializedName("apk_url") val apkUrl: String?
)

data class AudioConfigData(
    val enabled: Boolean = false,
    @SerializedName("sos_enabled") val sosEnabled: Boolean = false,
    @SerializedName("download_enabled") val downloadEnabled: Boolean = false,
    @SerializedName("chunk_seconds") val chunkSeconds: Int = 30,
    @SerializedName("session_max_minutes") val sessionMaxMinutes: Int = 30,
    @SerializedName("monthly_limit_mb") val monthlyLimitMb: Int = 500,
    @SerializedName("retention_days") val retentionDays: Int = 30,
    @SerializedName("consent_version") val consentVersion: String = "1.0",
    @SerializedName("background_visible") val backgroundVisible: Boolean = true,
    @SerializedName("remote_requests_enabled") val remoteRequestsEnabled: Boolean = false,
    @SerializedName("scheduling_enabled") val schedulingEnabled: Boolean = false,
    @SerializedName("schedule_max_minutes") val scheduleMaxMinutes: Int = 15,
    @SerializedName("command_poll_seconds") val commandPollSeconds: Int = 60,
    @SerializedName("schedule_requires_confirmation") val scheduleRequiresConfirmation: Boolean = true
)

data class AudioConsentRequest(
    val accepted: Boolean = true,
    @SerializedName("consent_version") val consentVersion: String
)

data class AudioConsentData(@SerializedName("consent_version") val consentVersion: String?)

data class AudioUploadRequest(
    val uuid: String,
    @SerializedName("session_uuid") val sessionUuid: String,
    @SerializedName("recording_type") val recordingType: String,
    @SerializedName("started_at") val startedAt: String,
    @SerializedName("ended_at") val endedAt: String,
    @SerializedName("duration_seconds") val durationSeconds: Int,
    @SerializedName("mime_type") val mimeType: String,
    val sha256: String,
    @SerializedName("file_base64") val fileBase64: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerializedName("audio_command_id") val audioCommandId: Long? = null,
    @SerializedName("command_occurrence_uuid") val commandOccurrenceUuid: String? = null
)

data class AudioUploadData(
    val id: Long?,
    val uuid: String?,
    val duplicate: Boolean? = false,
    @SerializedName("usage_bytes") val usageBytes: Long? = null,
    @SerializedName("limit_bytes") val limitBytes: Long? = null
)


data class AudioCommandVehicleDto(val plate: String?, val model: String?)

data class AudioCommandDto(
    val id: Long,
    @SerializedName("occurrence_uuid") val occurrenceUuid: String,
    val type: String,
    val title: String,
    val reason: String,
    @SerializedName("scheduled_at") val scheduledAt: String,
    @SerializedName("expires_at") val expiresAt: String?,
    @SerializedName("duration_minutes") val durationMinutes: Int,
    @SerializedName("requires_confirmation") val requiresConfirmation: Boolean = true,
    val vehicle: AudioCommandVehicleDto?,
    @SerializedName("device_name") val deviceName: String?,
    @SerializedName("company_name") val companyName: String?
)

data class AudioCommandsData(
    val enabled: Boolean = false,
    val commands: List<AudioCommandDto> = emptyList(),
    @SerializedName("audio_config") val audioConfig: AudioConfigData?,
    @SerializedName("server_time") val serverTime: String?
)

data class AudioCommandResponseRequest(
    @SerializedName("occurrence_uuid") val occurrenceUuid: String,
    val action: String,
    val message: String? = null
)

data class AudioCommandResponseData(
    val status: String?,
    @SerializedName("next_scheduled_at") val nextScheduledAt: String?,
    val duplicate: Boolean? = false
)

data class DeviceCommandDto(
    val id: Long,
    @SerializedName("command_type") val commandType: String,
    val payload: Map<String, Any>?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("expires_at") val expiresAt: String?
)
data class DeviceCommandsData(val commands: List<DeviceCommandDto> = emptyList(), @SerializedName("server_time") val serverTime: String?)
data class DeviceCommandResponseRequest(val status: String, val message: String? = null)
