package com.rastreiafrota.app.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {
    @POST("api/v1/device/activate")
    suspend fun activate(@Body body: ActivateRequest): Response<ApiEnvelope<ActivateData>>
    @POST("api/v1/device/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<ApiEnvelope<ActivateData>>
    @POST("api/v1/tracking/batch")
    suspend fun sendBatch(@Body body: BatchRequest): Response<ApiEnvelope<BatchData>>
    @POST("api/v1/device/status")
    suspend fun sendStatus(@Body body: StatusRequest): Response<ApiEnvelope<Any>>
    @GET("api/v1/device/config")
    suspend fun getConfig(): Response<ApiEnvelope<ConfigData>>
    @GET("api/v1/app/version")
    suspend fun getVersion(): Response<ApiEnvelope<VersionData>>
    @GET("api/v1/health")
    suspend fun health(): Response<ApiEnvelope<Any>>
    @POST("api/v1/audio/consent")
    suspend fun audioConsent(@Body body: AudioConsentRequest): Response<ApiEnvelope<AudioConsentData>>
    @POST("api/v1/audio/upload")
    suspend fun uploadAudio(@Body body: AudioUploadRequest): Response<ApiEnvelope<AudioUploadData>>
    @GET("api/v1/audio/commands")
    suspend fun audioCommands(): Response<ApiEnvelope<AudioCommandsData>>
    @POST("api/v1/audio/commands/{id}/respond")
    suspend fun respondAudioCommand(@Path("id") id: Long, @Body body: AudioCommandResponseRequest): Response<ApiEnvelope<AudioCommandResponseData>>
    @GET("api/v1/device/commands")
    suspend fun deviceCommands(): Response<ApiEnvelope<DeviceCommandsData>>
    @POST("api/v1/device/commands/{id}/respond")
    suspend fun respondDeviceCommand(@Path("id") id: Long, @Body body: DeviceCommandResponseRequest): Response<ApiEnvelope<Any>>
}
