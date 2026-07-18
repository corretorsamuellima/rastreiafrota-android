package com.rastreiafrota.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.rastreiafrota.app.data.prefs.SettingsStore
import com.rastreiafrota.app.data.remote.ApiClient
import com.rastreiafrota.app.data.repository.AudioRepository
import com.rastreiafrota.app.data.repository.AudioCommandRepository
import com.rastreiafrota.app.data.repository.TrackingRepository
import com.rastreiafrota.app.data.repository.RemoteCommandRepository
import com.rastreiafrota.app.databinding.ActivityMainBinding
import com.rastreiafrota.app.service.AudioRecordingService
import com.rastreiafrota.app.service.LocationTrackingService
import com.rastreiafrota.app.util.DeviceInfo
import com.rastreiafrota.app.work.AudioSyncWorker
import com.rastreiafrota.app.work.SyncWorker
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore
    private lateinit var repository: TrackingRepository
    private lateinit var audioRepository: AudioRepository
    private lateinit var audioCommandRepository: AudioCommandRepository
    private var pendingAudioSos = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshUi() }

    private val microphoneLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) confirmConsentAndStart(pendingAudioSos)
            else show("A gravação precisa da permissão de microfone.")
            refreshUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(applicationContext)
        repository = TrackingRepository(applicationContext)
        audioRepository = AudioRepository(applicationContext)
        audioCommandRepository = AudioCommandRepository(applicationContext)

        if (!settings.isActivated) {
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
            return
        }

        binding.btnStart.setOnClickListener { startTracking() }
        binding.btnStop.setOnClickListener { stopTracking() }
        binding.btnSync.setOnClickListener {
            SyncWorker.enqueueNow(this)
            show("Sincronização de localização solicitada.")
        }
        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnDiagnostics.setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        binding.btnPermissions.setOnClickListener { requestAllPermissions() }
        binding.btnBattery.setOnClickListener { requestIgnoreBatteryOptimization() }
        binding.btnChangeServer.setOnClickListener { confirmServerReconfiguration() }

        binding.btnAudioPermission.setOnClickListener { requestMicrophone(false) }
        binding.btnAudioStart.setOnClickListener { requestMicrophone(false) }
        binding.btnAudioSos.setOnClickListener { requestMicrophone(true) }
        binding.btnAudioStop.setOnClickListener {
            AudioRecordingService.stop(this)
            show("Encerrando gravação e preparando o último bloco.")
        }
        binding.btnAudioPause.setOnClickListener {
            lifecycleScope.launch {
                if (settings.audioPaused()) AudioRecordingService.resume(this@MainActivity)
                else AudioRecordingService.pause(this@MainActivity)
                binding.root.postDelayed({ refreshUi() }, 400)
            }
        }
        binding.btnAudioSync.setOnClickListener {
            AudioSyncWorker.enqueueNow(this)
            show("Sincronização dos áudios solicitada.")
        }
        binding.btnAudioCheckRequests.setOnClickListener { checkAudioCommands(true) }

        lifecycleScope.launch {
            repository.pendingCountFlow().collect { binding.tvPending.text = "Posições pendentes: $it" }
        }
        lifecycleScope.launch {
            audioRepository.pendingCountFlow().collect { binding.tvAudioPending.text = "Áudios pendentes: $it" }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!settings.isActivated) {
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
            return
        }
        lifecycleScope.launch {
            repository.refreshRemoteConfig()
            RemoteCommandRepository(applicationContext).pollAndExecute()
            refreshUi()
            if (settings.audioRemoteRequestsEnabled()) checkAudioCommands(false)
        }
    }

    private fun refreshUi() {
        lifecycleScope.launch {
            binding.tvVehicle.text = "Veículo: ${settings.vehiclePlate().ifEmpty { "—" }}"
            binding.tvCompany.text = "Empresa: ${settings.companyName().ifEmpty { "—" }}"
            binding.tvDevice.text = "Dispositivo: ${settings.deviceName().ifEmpty { "—" }}"
            binding.tvLastSync.text = "Última sincronização: ${settings.lastSync()}"
            binding.tvVersion.text = "Versão: ${DeviceInfo.appVersion(this@MainActivity)}"
            val tracking = settings.trackingEnabled()
            binding.tvStatus.text = if (tracking) "Rastreamento ativo" else "Rastreamento pausado"
            binding.tvGps.text = "GPS: ${if (DeviceInfo.isGpsEnabled(this@MainActivity)) "ativo" else "inativo"}"
            binding.tvNetwork.text = "Internet: ${DeviceInfo.networkType(this@MainActivity)}"
            binding.tvBattery.text = "Bateria: ${DeviceInfo.batteryLevel(this@MainActivity) ?: "—"}%"
            binding.btnPermissions.visibility = if (hasLocationPermissions()) View.GONE else View.VISIBLE
            binding.btnBattery.visibility = if (DeviceInfo.isIgnoringBatteryOptimizations(this@MainActivity)) View.GONE else View.VISIBLE

            val audioEnabled = settings.audioEnabled()
            val audioRunning = settings.audioRecording()
            val audioPaused = settings.audioPaused()
            val micGranted = hasMicrophonePermission()
            val sosEnabled = settings.audioSosEnabled()
            binding.tvAudioPlan.text = if (audioEnabled) "PLANO ATIVO" else "RECURSO BLOQUEADO"
            binding.tvAudioStatus.text = when {
                !audioEnabled -> "Proteção por Áudio não está incluída no plano atual"
                audioRunning && audioPaused -> "Gravação pausada pelo usuário"
                audioRunning -> "Gravação em segundo plano ativa"
                else -> "Pronto para gravação autorizada"
            }
            binding.tvAudioLimits.text = if (audioEnabled) {
                "Blocos de ${settings.audioChunkSeconds()}s · sessão de até ${settings.audioSessionMaxMinutes()} min · retenção ${settings.audioRetentionDays()} dias"
            } else {
                "Ative o recurso no Painel Master e atualize esta tela."
            }
            binding.btnAudioPermission.visibility = if (audioEnabled && !micGranted) View.VISIBLE else View.GONE
            binding.btnAudioStart.isEnabled = audioEnabled && micGranted && !audioRunning
            binding.btnAudioStart.alpha = if (binding.btnAudioStart.isEnabled) 1f else .45f
            binding.btnAudioStop.visibility = if (audioRunning) View.VISIBLE else View.GONE
            binding.btnAudioPause.visibility = if (audioRunning) View.VISIBLE else View.GONE
            binding.btnAudioPause.text = if (audioPaused) "Continuar gravação" else "Pausar gravação"
            binding.btnAudioSos.visibility = if (sosEnabled) View.VISIBLE else View.GONE
            binding.btnAudioSos.isEnabled = sosEnabled && micGranted && !audioRunning
            binding.btnAudioSync.isEnabled = audioEnabled
            val remoteEnabled = audioEnabled && settings.audioRemoteRequestsEnabled()
            binding.btnAudioCheckRequests.visibility = if (remoteEnabled) View.VISIBLE else View.GONE
            binding.tvAudioRemote.visibility = if (remoteEnabled) View.VISIBLE else View.GONE
            if (remoteEnabled) {
                binding.tvAudioRemote.text = "Solicitações do painel: ${settings.lastAudioCommandMessage()}\nÚltima consulta: ${settings.lastAudioCommandCheck()}"
            }
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine && bg
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestAllPermissions() {
        val base = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) base += Manifest.permission.POST_NOTIFICATIONS
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) permissionLauncher.launch(base.toTypedArray())
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    private fun requestMicrophone(sos: Boolean) {
        lifecycleScope.launch {
            if (!settings.audioEnabled()) {
                show("Proteção por Áudio não está liberada no plano da empresa.")
                return@launch
            }
            if (sos && !settings.audioSosEnabled()) {
                show("SOS com áudio não está liberado no plano.")
                return@launch
            }
            pendingAudioSos = sos
            if (!hasMicrophonePermission()) microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
            else confirmConsentAndStart(sos)
        }
    }

    private fun confirmConsentAndStart(sos: Boolean) {
        lifecycleScope.launch {
            if (settings.isAudioConsentAccepted()) {
                startAudio(sos)
                return@launch
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Ciência sobre a gravação")
                .setMessage(
                    "O microfone será usado para uma gravação de segurança. A gravação continuará com a tela apagada, " +
                        "mas permanecerá visível na notificação e no indicador de microfone do Android. Use somente com autorização e finalidade legítima."
                )
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Estou ciente e iniciar") { _, _ ->
                    lifecycleScope.launch {
                        val (ok, message) = audioRepository.registerConsent()
                        if (ok) startAudio(sos) else show(message)
                    }
                }
                .show()
        }
    }

    private fun startAudio(sos: Boolean) {
        AudioRecordingService.start(this, sos)
        show(if (sos) "SOS iniciado. A gravação ficará visível na notificação." else "Gravação iniciada. Pode apagar a tela.")
        binding.root.postDelayed({ refreshUi() }, 800)
    }

    private fun checkAudioCommands(showResult: Boolean) {
        binding.btnAudioCheckRequests.isEnabled = false
        lifecycleScope.launch {
            val (count, message) = audioCommandRepository.pollAndNotify()
            binding.btnAudioCheckRequests.isEnabled = true
            refreshUi()
            if (showResult) {
                show(if (count > 0) "$count solicitação(ões) encontrada(s). Abra a notificação para revisar." else message)
            }
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun startTracking() {
        if (!hasLocationPermissions()) {
            show("Conceda as permissões de localização antes de iniciar.")
            requestAllPermissions()
            return
        }
        lifecycleScope.launch {
            settings.setTrackingEnabled(true)
            LocationTrackingService.start(this@MainActivity)
            refreshUi()
        }
    }

    private fun stopTracking() {
        lifecycleScope.launch {
            settings.setTrackingEnabled(false)
            LocationTrackingService.stop(this@MainActivity)
            refreshUi()
        }
    }

    private fun testConnection() {
        binding.btnTest.isEnabled = false
        lifecycleScope.launch {
            val (ok, message) = repository.testAuthenticatedConnection()
            show(if (ok) "Comunicação autenticada OK." else message)
            binding.btnTest.isEnabled = true
            refreshUi()
        }
    }

    private fun confirmServerReconfiguration() {
        val input = EditText(this).apply {
            hint = "https://seu-dominio.com.br/"
            setSingleLine(true)
        }
        lifecycleScope.launch { input.setText(settings.baseUrl()) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Atualizar URL do servidor")
            .setMessage("Ao alterar o servidor, este celular será desconectado e precisará de um novo código de ativação.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Atualizar e reativar") { _, _ ->
                lifecycleScope.launch {
                    val url = input.text?.toString()?.trim().orEmpty()
                    if (!url.startsWith("https://") && !com.rastreiafrota.app.BuildConfig.DEBUG) {
                        show("Use uma URL HTTPS válida.")
                        return@launch
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        show("Informe a URL completa do servidor.")
                        return@launch
                    }
                    LocationTrackingService.stop(this@MainActivity)
                    AudioRecordingService.stop(this@MainActivity)
                    settings.setTrackingEnabled(false)
                    settings.clearCredentials()
                    settings.setBaseUrl(url)
                    ApiClient.reset()
                    startActivity(Intent(this@MainActivity, ActivationActivity::class.java))
                    finish()
                }
            }.show()
    }

    private fun show(message: String) = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
}
