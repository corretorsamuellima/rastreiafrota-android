package com.rastreiafrota.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rastreiafrota.app.work.PushTokenWorker
import com.rastreiafrota.app.work.RemoteCommandWorker

class RastreiaFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        PushTokenWorker.enqueue(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // O push não executa payload arbitrário: ele apenas acorda a busca autenticada e assinada da API.
        if (message.data["kind"] == "remote_command") {
            RemoteCommandWorker.enqueueNow(applicationContext)
        }
    }
}

