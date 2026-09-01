package com.rastreiafrota.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rastreiafrota.app.data.local.AppDatabase
import com.rastreiafrota.app.data.prefs.SettingsStore
import com.rastreiafrota.app.databinding.ActivityDiagnosticsBinding
import com.rastreiafrota.app.util.DeviceInfo
import com.rastreiafrota.app.util.TrackingReadiness
import kotlinx.coroutines.launch

class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        load()
        binding.btnRefresh.setOnClickListener { load() }
    }

    private fun load() {
        val settings = SettingsStore(applicationContext)
        val db = AppDatabase.get(applicationContext)
        lifecycleScope.launch {
            val granted = { p: String ->
                ContextCompat.checkSelfPermission(this@DiagnosticsActivity, p) == PackageManager.PERMISSION_GRANTED
            }
            val bg = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            val readiness = TrackingReadiness.snapshot(this@DiagnosticsActivity)
            val lines = listOf(
                "LOCALIZAÇÃO E SISTEMA",
                "Permissão GPS precisa: ${ok(granted(Manifest.permission.ACCESS_FINE_LOCATION))}",
                "Permissão 2º plano: ${ok(bg)}",
                "Notificações realmente ativas: ${ok(readiness.notifications)}",
                "Otimização de bateria ignorada: ${ok(readiness.batteryUnrestricted)}",
                "GPS do aparelho: ${ok(readiness.gpsEnabled)}",
                "Internet: ${DeviceInfo.networkType(this@DiagnosticsActivity)}",
                "Dispositivo ativado: ${ok(settings.isActivated)}",
                "Rastreamento habilitado: ${ok(settings.trackingEnabled())}",
                "Fila de posições: ${db.pendingLocationDao().pendingCount()} registros",
                "Última captura GPS: ${settings.lastCapture()}",
                "Último ponto armazenado: ${db.pendingLocationDao().lastCapturedAt() ?: "—"}",
                "Última sincronização: ${settings.lastSync()}",
                "Último erro do rastreamento: ${settings.lastTrackingError().ifEmpty { "nenhum" }}",
                "Último erro da API: ${settings.lastApiError().ifEmpty { "nenhum" }}",
                "Condições principais prontas: ${ok(readiness.reliableReady)} (${readiness.readyCount}/6)",
                "Firebase configurado no APK: ${ok(readiness.firebaseConfigured)}",
                "Último registro do push: ${settings.lastPushSync()}",
                "Último erro do push: ${settings.lastPushError().ifEmpty { "nenhum" }}",
                "",
                "PROTEÇÃO POR ÁUDIO",
                "Permissão de microfone: ${ok(granted(Manifest.permission.RECORD_AUDIO))}",
                "Plano com áudio: ${ok(settings.audioEnabled())}",
                "SOS por áudio: ${ok(settings.audioSosEnabled())}",
                "Gravação ativa: ${ok(settings.audioRecording())}",
                "Fila de áudios: ${db.pendingAudioDao().pendingCount()} registros",
                "Último erro de áudio: ${settings.lastAudioError().ifEmpty { "nenhum" }}",
                "",
                "DISPOSITIVO",
                "Servidor: ${settings.baseUrl()}",
                "Aparelho: ${DeviceInfo.manufacturer} ${DeviceInfo.model}",
                "Android: ${DeviceInfo.androidVersion}",
                "Versão do app: ${DeviceInfo.appVersion(this@DiagnosticsActivity)}",
                "Bateria: ${DeviceInfo.batteryLevel(this@DiagnosticsActivity) ?: "—"}%"
            )
            binding.tvDiagnostics.text = lines.joinToString("\n\n")
        }
    }

    private fun ok(value: Boolean) = if (value) "✅ sim" else "❌ NÃO"
}
