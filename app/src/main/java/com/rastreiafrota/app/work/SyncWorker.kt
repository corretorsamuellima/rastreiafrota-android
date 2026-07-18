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
import com.rastreiafrota.app.data.repository.TrackingRepository
import java.util.concurrent.TimeUnit

/**
 * Sincroniza a fila offline em lotes, com retentativa exponencial do WorkManager.
 * Nunca remove pendentes sem confirmação do servidor.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = TrackingRepository(applicationContext)
        if (!repository.settings.isActivated) return Result.success()

        val (sent, failed) = repository.syncPending()
        repository.sendStatus(if (sent > 0) "sync" else "heartbeat")

        return when {
            !failed -> Result.success()
            runAttemptCount < 8 -> Result.retry() // backoff exponencial
            else -> Result.failure()
        }
    }

    companion object {
        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("rf_sync_now", ExistingWorkPolicy.KEEP, request)
        }
    }
}
