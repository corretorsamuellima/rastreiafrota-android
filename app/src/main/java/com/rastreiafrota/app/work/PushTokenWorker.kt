package com.rastreiafrota.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rastreiafrota.app.data.prefs.SettingsStore
import com.rastreiafrota.app.data.remote.ApiClient
import com.rastreiafrota.app.data.remote.PushTokenRequest

class PushTokenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val token = inputData.getString(KEY_TOKEN).orEmpty()
        if (token.isBlank()) return Result.failure()
        val settings = SettingsStore(applicationContext)
        if (!settings.isActivated) return Result.retry()
        return try {
            val response = ApiClient.service(settings).registerPushToken(PushTokenRequest(token))
            if (response.isSuccessful && response.body()?.success == true) Result.success()
            else if (response.code() in 400..499) Result.failure() else Result.retry()
        } catch (_: Exception) { Result.retry() }
    }

    companion object {
        private const val KEY_TOKEN = "fcm_token"
        fun enqueue(context: Context, token: String) {
            val request = OneTimeWorkRequestBuilder<PushTokenWorker>()
                .setInputData(Data.Builder().putString(KEY_TOKEN, token).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("rf_push_token", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

