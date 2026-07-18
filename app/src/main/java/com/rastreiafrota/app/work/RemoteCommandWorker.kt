package com.rastreiafrota.app.work
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.rastreiafrota.app.data.repository.RemoteCommandRepository
class RemoteCommandWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
 override suspend fun doWork():Result=try{RemoteCommandRepository(applicationContext).pollAndExecute();Result.success()}catch(_:Exception){Result.retry()}
 companion object{
  fun enqueueNow(context:Context){
   val request=OneTimeWorkRequestBuilder<RemoteCommandWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
   WorkManager.getInstance(context).enqueueUniqueWork("rf_remote_push",ExistingWorkPolicy.REPLACE,request)
  }
 }
}
