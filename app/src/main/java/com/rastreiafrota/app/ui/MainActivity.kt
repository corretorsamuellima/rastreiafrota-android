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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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
import com.rastreiafrota.app.util.TrackingReadiness
import com.rastreiafrota.app.push.FirebaseBootstrap
import com.rastreiafrota.app.work.AudioSyncWorker
import com.rastreiafrota.app.work.SyncWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore
    private lateinit var repository: TrackingRepository
    private lateinit var audioRepository: AudioRepository
    private lateinit var audioCommandRepository: AudioCommandRepository
    private var pendingAudioSos = false
    private var setupInProgress = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshUi()
            if (setupInProgress && TrackingReadiness.hasPreciseLocation(this)) advanceTrackerSetup()
            else if (setupInProgress) { setupInProgress = false; show("A localização precisa não foi autorizada. O rastreamento não pode iniciar sem ela.") }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshUi()
            if (setupInProgress && TrackingReadiness.notificationsEnabled(this)) advanceTrackerSetup()
            else if (setupInProgress) { setupInProgress = false; show("As notificações não foram autorizadas. Você pode liberá-las depois nos ajustes.") }
        }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshUi()
            if (setupInProgress && TrackingReadiness.hasBackgroundLocation(this)) advanceTrackerSetup()
            else if (setupInProgress) { setupInProgress = false; show("Escolha 'Permitir o tempo todo' para rastrear com a tela apagada.") }
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshUi()
            if (setupInProgress) {
                setupInProgress = false
                show("Configuração conferida. Toque em 'Continuar preparação' para verificar o próximo item.")
            }
        }

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
        binding.btnPrepareTracker.setOnClickListener { beginTrackerSetup() }
        binding.btnPermissions.setOnClickListener { beginTrackerSetup() }
        binding.btnBattery.setOnClickListener { requestIgnoreBatteryOptimization() }
        binding.btnGpsSettings.setOnClickListener { openLocationSettings() }
        binding.btnNetworkSettings.setOnClickListener { openNetworkSettings() }
        binding.btnNotificationSettings.setOnClickListener { openNotificationSettings() }
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refreshRouteUi()
                    delay(3_000)
                }
            }
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
            val readiness = TrackingReadiness.snapshot(this@MainActivity)
            binding.tvReadinessTitle.text = if (readiness.reliableReady) {
                "Celular preparado para rastreamento"
            } else {
                "Preparação ${readiness.readyCount}/6 concluída"
            }
            binding.tvReadinessDetails.text = listOf(
                "${mark(readiness.preciseLocation)} Localização precisa",
                "${mark(readiness.backgroundLocation)} Localização o tempo todo",
                "${mark(readiness.notifications)} Notificações permitidas",
                "${mark(readiness.batteryUnrestricted)} Sem restrição de bateria",
                "${mark(readiness.gpsEnabled)} GPS ligado",
                "${mark(readiness.online)} Internet disponível",
                "${if (readiness.firebaseConfigured) "⚡" else "○"} Firebase ${if (readiness.firebaseConfigured) "pronto para push" else "não configurado; usando polling"}"
            ).joinToString("\n")
            binding.btnPrepareTracker.text = if (readiness.reliableReady) "Conferir preparação novamente" else "Continuar preparação"
            binding.btnPermissions.visibility = if (readiness.preciseLocation && readiness.backgroundLocation) View.GONE else View.VISIBLE
            binding.btnBattery.visibility = if (readiness.batteryUnrestricted) View.GONE else View.VISIBLE
            binding.btnGpsSettings.visibility = if (readiness.gpsEnabled) View.GONE else View.VISIBLE
            binding.btnNetworkSettings.visibility = if (readiness.online) View.GONE else View.VISIBLE
            binding.btnNotificationSettings.visibility = if (readiness.notifications) View.GONE else View.VISIBLE

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
            refreshRouteUi()
        }
    }

    private suspend fun refreshRouteUi() {
        val route = repository.currentRouteSnapshot()
        binding.routeTrailView.setRoutePoints(route.points)
        binding.tvRouteTitle.text = when {
            route.sessionUuid.isBlank() -> "Trajeto atual"
            route.active -> "Trajeto sendo registrado"
            else -> "Último trajeto registrado"
        }
        val hours = route.durationSeconds / 3600
        val minutes = (route.durationSeconds % 3600) / 60
        val duration = if (hours > 0) "${hours}h ${minutes}min" else "${minutes} min"
        binding.tvRouteStats.text = String.format(
            Locale("pt", "BR"),
            "%.1f km · %d pontos · %s · %.0f km/h",
            route.distanceKm,
            route.pointsCount,
            duration,
            route.lastSpeedKmh ?: 0.0
        )
        binding.tvRouteQuality.text = when {
            route.pointsCount == 0 -> "Inicie o GPS para registrar o percurso ponto a ponto."
            route.accuracyPercent >= 85 -> "Sinal preciso: ${route.accuracyPercent}% dos pontos com precisão de até 30 m."
            else -> "Precisão variável (${route.accuracyPercent}%). Mantenha o GPS e a localização precisa ativados."
        }
    }

    private fun hasLocationPermissions(): Boolean {
        return TrackingReadiness.hasPreciseLocation(this) && TrackingReadiness.hasBackgroundLocation(this)
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun beginTrackerSetup() {
        setupInProgress = true
        advanceTrackerSetup()
    }

    private fun advanceTrackerSetup() {
        val readiness = TrackingReadiness.snapshot(this)
        when {
            !readiness.preciseLocation -> {
                permissionLauncher.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
            !readiness.notifications && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            !readiness.notifications -> explainAndOpen(
                "Ativar notificações",
                "As notificações mantêm o serviço de rastreamento visível e avisam quando o Android interrompe uma função.",
                ::notificationSettingsIntent
            )
            !readiness.backgroundLocation && Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            !readiness.backgroundLocation -> explainAndOpen(
                "Permitir o tempo todo",
                "Nos ajustes do aplicativo, abra Permissões → Localização e escolha 'Permitir o tempo todo'. Depois volte ao RastreiaFrota.",
                ::applicationSettingsIntent
            )
            !readiness.gpsEnabled -> explainAndOpen(
                "Ligar o GPS",
                "Ative a localização do aparelho para que novas posições possam ser capturadas.",
                { Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
            )
            !readiness.batteryUnrestricted -> {
                requestIgnoreBatteryOptimization()
            }
            !readiness.online -> explainAndOpen(
                "Ativar internet",
                "Ligue os dados móveis ou o Wi-Fi. Sem conexão, o app guarda as posições e envia posteriormente.",
                { Intent(Settings.ACTION_WIRELESS_SETTINGS) }
            )
            else -> {
                setupInProgress = false
                FirebaseBootstrap.registerCurrentToken(applicationContext)
                lifecycleScope.launch {
                    repository.sendStatus("readiness_ready")
                    if (settings.trackingEnabled()) LocationTrackingService.start(this@MainActivity)
                    refreshUi()
                }
                show(if (readiness.firebaseConfigured) "Preparação concluída. Rastreamento e push estão prontos." else "Preparação principal concluída. O Firebase ainda precisa das credenciais do projeto.")
            }
        }
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
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val intent = if (direct.resolveActivity(packageManager) != null) direct else fallback
        launchSettings(intent)
    }

    private fun openLocationSettings() = launchSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    private fun openNetworkSettings() = launchSettings(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    private fun openNotificationSettings() = launchSettings(notificationSettingsIntent())

    private fun applicationSettingsIntent() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun notificationSettingsIntent() = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    }

    private fun launchSettings(intent: Intent) {
        if (intent.resolveActivity(packageManager) != null) settingsLauncher.launch(intent)
        else show("Não foi possível abrir este ajuste automaticamente.")
    }

    private fun explainAndOpen(title: String, message: String, intent: () -> Intent) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Agora não") { _, _ -> setupInProgress = false }
            .setPositiveButton("Abrir ajustes") { _, _ -> launchSettings(intent()) }
            .show()
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "!"

    private fun startTracking() {
        if (!hasLocationPermissions()) {
            show("Conceda as permissões de localização antes de iniciar.")
            beginTrackerSetup()
            return
        }
        lifecycleScope.launch {
            if (!settings.trackingEnabled()) settings.startNewRouteSession()
            settings.setTrackingEnabled(true)
            LocationTrackingService.start(this@MainActivity)
            refreshUi()
        }
    }

    private fun stopTracking() {
        lifecycleScope.launch {
            settings.setTrackingEnabled(false)
            LocationTrackingService.stop(this@MainActivity)
            SyncWorker.enqueueNow(this@MainActivity)
            settings.finishRouteSession()
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
