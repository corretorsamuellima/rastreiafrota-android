package com.rastreiafrota.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rastreiafrota.app.data.repository.AudioCommandRepository
import com.rastreiafrota.app.ui.AudioCommandActivity
import com.rastreiafrota.app.util.AudioCommandNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AudioCommandActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REJECT) return
        val commandId = intent.getLongExtra(AudioCommandActivity.EXTRA_COMMAND_ID, 0L)
        val occurrence = intent.getStringExtra(AudioCommandActivity.EXTRA_OCCURRENCE_UUID).orEmpty()
        if (commandId <= 0 || occurrence.isBlank()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AudioCommandRepository(context).respond(commandId, occurrence, "rejected", "Recusada pela notificação do celular")
                AudioCommandNotifier.cancel(context, commandId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REJECT = "com.rastreiafrota.audio.COMMAND_REJECT"
    }
}
