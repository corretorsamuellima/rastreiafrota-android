package com.rastreiafrota.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rastreiafrota.app.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reinicia o rastreamento após o celular ligar (BOOT_COMPLETED) ou o app atualizar.
 * Retoma somente se o dispositivo estava ativado e o rastreamento habilitado.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsStore(context)
                if (settings.isActivated && settings.trackingEnabled()) {
                    LocationTrackingService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
