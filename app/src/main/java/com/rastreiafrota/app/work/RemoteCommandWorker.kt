package com.rastreiafrota.app.work
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rastreiafrota.app.data.repository.RemoteCommandRepository
class RemoteCommandWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
 override suspend fun doWork():Result=try{RemoteCommandRepository(applicationContext).pollAndExecute();Result.success()}catch(_:Exception){Result.retry()}
}
