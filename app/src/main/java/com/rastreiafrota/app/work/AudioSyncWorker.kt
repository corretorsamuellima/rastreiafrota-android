package com.rastreiafrota.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rastreiafrota.app.data.repository.AudioRepository
import java.util.concurrent.TimeUnit

class AudioSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val (_, failed) = AudioRepository(applicationContext).syncPending()
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AudioSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "rf_audio_sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request
            )
        }
    }
}
