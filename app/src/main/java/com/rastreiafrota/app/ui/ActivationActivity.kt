package com.rastreiafrota.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.rastreiafrota.app.BuildConfig
import com.rastreiafrota.app.data.prefs.SettingsStore
import com.rastreiafrota.app.data.remote.ActivateRequest
import com.rastreiafrota.app.data.remote.ApiClient
import com.rastreiafrota.app.databinding.ActivityActivationBinding
import com.rastreiafrota.app.util.DeviceInfo
import com.rastreiafrota.app.push.FirebaseBootstrap
import kotlinx.coroutines.launch

/** Tela de ativação: código gerado no painel → credenciais seguras (Keystore). */
class ActivationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivationBinding
    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(applicationContext)

        // Disponível também em produção; HTTPS continua obrigatório pelo Network Security Config.
        binding.tilServer.visibility = if (BuildConfig.ALLOW_SERVER_CHANGE) View.VISIBLE else View.GONE
        lifecycleScope.launch { binding.etServer.setText(settings.baseUrl()) }

        binding.btnActivate.setOnClickListener { activate() }
    }

    private fun activate() {
        val code = binding.etCode.text?.toString()?.trim()?.uppercase() ?: ""
        if (code.length < 10) {
            binding.tilCode.error = "Informe o código completo (ex.: ABCD-EFGH-JKLM)"
            return
        }
        binding.tilCode.error = null
        binding.btnActivate.isEnabled = false
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                if (BuildConfig.ALLOW_SERVER_CHANGE) {
                    val url = binding.etServer.text?.toString()?.trim().orEmpty()
                    if (!url.startsWith("https://") && !BuildConfig.DEBUG) {
                        show("Em produção, informe uma URL HTTPS válida.")
                        binding.btnActivate.isEnabled = true
                        binding.progress.visibility = View.GONE
                        return@launch
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        show("Informe uma URL completa, começando com https://")
                        binding.btnActivate.isEnabled = true
                        binding.progress.visibility = View.GONE
                        return@launch
                    }
                    settings.setBaseUrl(url)
                    ApiClient.reset()
                }
                val api = ApiClient.service(settings)
                val response = api.activate(
                    ActivateRequest(
                        activationCode = code,
                        deviceUuid = settings.deviceUuid,
                        manufacturer = DeviceInfo.manufacturer,
                        model = DeviceInfo.model,
                        androidVersion = DeviceInfo.androidVersion,
                        appVersion = DeviceInfo.appVersion(this@ActivationActivity)
                    )
                )
                val body = response.body()
                if (response.isSuccessful && body?.success == true && body.data != null) {
                    val data = body.data
                    settings.accessToken = data.accessToken
                    settings.refreshToken = data.refreshToken
                    settings.hmacSecret = data.hmacSecret
                    data.trackingConfig?.let { settings.saveTrackingConfig(it) }
                    data.audioConfig?.let { settings.saveAudioConfig(it) }
                    settings.saveFirebaseConfig(data.firebaseConfig)
                    settings.setVehicleInfo(data.vehicle?.plate, data.company?.name, data.device?.name)
                    settings.setTrackingEnabled(true)
                    FirebaseBootstrap.initialize(applicationContext)
                    startActivity(Intent(this@ActivationActivity, MainActivity::class.java))
                    finish()
                } else {
                    show(body?.message ?: "Falha na ativação (HTTP ${response.code()}).")
                }
            } catch (e: Exception) {
                show("Erro de comunicação: ${e.message}")
            } finally {
                binding.btnActivate.isEnabled = true
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun show(msg: String) = Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
