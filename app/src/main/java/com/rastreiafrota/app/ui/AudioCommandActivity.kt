package com.rastreiafrota.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.rastreiafrota.app.data.prefs.SettingsStore
import com.rastreiafrota.app.data.remote.AudioCommandDto
import com.rastreiafrota.app.data.repository.AudioCommandRepository
import com.rastreiafrota.app.data.repository.AudioRepository
import com.rastreiafrota.app.databinding.ActivityAudioCommandBinding
import com.rastreiafrota.app.service.AudioRecordingService
import com.rastreiafrota.app.util.AudioCommandNotifier
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Tela visível obrigatória para revisar e autorizar uma solicitação do painel. */
class AudioCommandActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAudioCommandBinding
    private lateinit var settings: SettingsStore
    private lateinit var commandRepository: AudioCommandRepository
    private lateinit var audioRepository: AudioRepository

    private var commandId = 0L
    private var occurrenceUuid = ""
    private var commandType = "safety"
    private var title = "Solicitação de gravação"
    private var reason = ""
    private var companyName = ""
    private var vehiclePlate = ""
    private var vehicleModel = ""
    private var durationMinutes = 5
    private var scheduledAt = ""
    private var expiresAt: String? = null

    private val microphoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ensureConsentAndAccept() else show("Permissão de microfone não concedida.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioCommandBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(applicationContext)
        commandRepository = AudioCommandRepository(applicationContext)
        audioRepository = AudioRepository(applicationContext)
        readExtras()

        if (commandId <= 0 || occurrenceUuid.isBlank()) {
            finish()
            return
        }
        render()
        binding.btnCommandAccept.setOnClickListener { reviewAccept() }
        binding.btnCommandReject.setOnClickListener { reject() }
    }

    private fun readExtras() {
        commandId = intent.getLongExtra(EXTRA_COMMAND_ID, 0L)
        occurrenceUuid = intent.getStringExtra(EXTRA_OCCURRENCE_UUID).orEmpty()
        commandType = intent.getStringExtra(EXTRA_TYPE) ?: "safety"
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Solicitação de gravação"
        reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        companyName = intent.getStringExtra(EXTRA_COMPANY).orEmpty()
        vehiclePlate = intent.getStringExtra(EXTRA_VEHICLE_PLATE).orEmpty()
        vehicleModel = intent.getStringExtra(EXTRA_VEHICLE_MODEL).orEmpty()
        durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 5).coerceIn(1, 120)
        scheduledAt = intent.getStringExtra(EXTRA_SCHEDULED_AT).orEmpty()
        expiresAt = intent.getStringExtra(EXTRA_EXPIRES_AT)
    }

    private fun render() {
        binding.tvCommandBadge.text = if (commandType == "sos") "SOLICITAÇÃO SOS" else "SOLICITAÇÃO DE ÁUDIO"
        binding.tvCommandTitle.text = title
        binding.tvCommandCompany.text = companyName.ifBlank { "Empresa responsável" }
        binding.tvCommandReason.text = reason.ifBlank { "Motivo não informado" }
        binding.tvCommandVehicle.text = "Veículo: ${vehiclePlate.ifBlank { "—" }} ${vehicleModel.takeIf { it.isNotBlank() }?.let { "· $it" }.orEmpty()}"
        binding.tvCommandDuration.text = "Duração solicitada: $durationMinutes minuto(s)"
        binding.tvCommandSchedule.text = "Programada para: ${formatDate(scheduledAt)}"
        if (isExpired()) {
            binding.tvCommandStatus.text = "Esta solicitação expirou."
            binding.btnCommandAccept.isEnabled = false
        }
    }

    private fun reviewAccept() {
        lifecycleScope.launch {
            if (isExpired()) { show("Esta solicitação já expirou."); return@launch }
            if (settings.audioRecording()) { show("Já existe uma gravação em andamento."); return@launch }
            if (!settings.audioRemoteRequestsEnabled()) { show("Solicitações remotas foram desativadas no plano."); return@launch }
            if (commandType == "sos" && !settings.audioSosEnabled()) { show("SOS por áudio não está liberado."); return@launch }
            if (ContextCompat.checkSelfPermission(this@AudioCommandActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                ensureConsentAndAccept()
            }
        }
    }

    private fun ensureConsentAndAccept() {
        lifecycleScope.launch {
            if (settings.isAudioConsentAccepted()) {
                acceptAndStart()
                return@launch
            }
            MaterialAlertDialogBuilder(this@AudioCommandActivity)
                .setTitle("Ciência sobre a gravação")
                .setMessage("O microfone será usado somente após sua autorização. A gravação continuará em segundo plano com notificação visível e indicador do Android. O áudio será enviado à empresa informada e ficará registrado na auditoria.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Estou ciente") { _, _ ->
                    lifecycleScope.launch {
                        val (ok, message) = audioRepository.registerConsent()
                        if (ok) acceptAndStart() else show(message)
                    }
                }.show()
        }
    }

    private suspend fun acceptAndStart() {
        setBusy(true, "Registrando autorização…")
        val (ok, message) = commandRepository.respond(commandId, occurrenceUuid, "accepted", "Autorizada no celular")
        if (!ok) {
            setBusy(false, message)
            show(message)
            return
        }
        AudioCommandNotifier.cancel(this, commandId)
        AudioRecordingService.startRequested(
            context = this,
            sos = commandType == "sos",
            commandId = commandId,
            occurrenceUuid = occurrenceUuid,
            durationMinutes = durationMinutes,
            reason = reason
        )
        binding.tvCommandStatus.text = "Autorizada. A gravação foi iniciada com notificação visível."
        binding.root.postDelayed({ finish() }, 1200)
    }

    private fun reject() {
        lifecycleScope.launch {
            setBusy(true, "Recusando…")
            val (ok, message) = commandRepository.respond(commandId, occurrenceUuid, "rejected", "Recusada na tela de confirmação")
            if (ok) {
                AudioCommandNotifier.cancel(this@AudioCommandActivity, commandId)
                finish()
            } else {
                setBusy(false, message)
                show(message)
            }
        }
    }

    private fun setBusy(busy: Boolean, status: String) {
        binding.btnCommandAccept.isEnabled = !busy
        binding.btnCommandReject.isEnabled = !busy
        binding.tvCommandStatus.text = status
    }

    private fun isExpired(): Boolean = try {
        expiresAt?.let { OffsetDateTime.parse(it).isBefore(OffsetDateTime.now()) } ?: false
    } catch (_: Exception) { false }

    private fun formatDate(value: String): String = try {
        OffsetDateTime.parse(value).format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", Locale("pt", "BR")))
    } catch (_: Exception) { value.ifBlank { "agora" } }

    private fun show(message: String) = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()

    companion object {
        const val EXTRA_COMMAND_ID = "audio_command_id"
        const val EXTRA_OCCURRENCE_UUID = "audio_occurrence_uuid"
        const val EXTRA_TYPE = "audio_command_type"
        const val EXTRA_TITLE = "audio_command_title"
        const val EXTRA_REASON = "audio_command_reason"
        const val EXTRA_COMPANY = "audio_command_company"
        const val EXTRA_VEHICLE_PLATE = "audio_command_vehicle_plate"
        const val EXTRA_VEHICLE_MODEL = "audio_command_vehicle_model"
        const val EXTRA_DURATION_MINUTES = "audio_command_duration_minutes"
        const val EXTRA_SCHEDULED_AT = "audio_command_scheduled_at"
        const val EXTRA_EXPIRES_AT = "audio_command_expires_at"

        fun intentFor(context: Context, command: AudioCommandDto): Intent = Intent(context, AudioCommandActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_COMMAND_ID, command.id)
            putExtra(EXTRA_OCCURRENCE_UUID, command.occurrenceUuid)
            putExtra(EXTRA_TYPE, command.type)
            putExtra(EXTRA_TITLE, command.title)
            putExtra(EXTRA_REASON, command.reason)
            putExtra(EXTRA_COMPANY, command.companyName)
            putExtra(EXTRA_VEHICLE_PLATE, command.vehicle?.plate)
            putExtra(EXTRA_VEHICLE_MODEL, command.vehicle?.model)
            putExtra(EXTRA_DURATION_MINUTES, command.durationMinutes)
            putExtra(EXTRA_SCHEDULED_AT, command.scheduledAt)
            putExtra(EXTRA_EXPIRES_AT, command.expiresAt)
        }
    }
}
