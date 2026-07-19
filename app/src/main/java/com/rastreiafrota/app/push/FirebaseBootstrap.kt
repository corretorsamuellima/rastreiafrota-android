package com.rastreiafrota.app.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.rastreiafrota.app.BuildConfig
import com.rastreiafrota.app.data.prefs.SettingsStore
import com.rastreiafrota.app.data.remote.FirebaseConfigData
import com.rastreiafrota.app.work.PushTokenWorker

object FirebaseBootstrap {
    fun configured(context: Context): Boolean = effectiveConfig(context) != null

    @Synchronized
    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        val config = effectiveConfig(applicationContext)
        val existing = FirebaseApp.getApps(applicationContext)
            .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }

        if (config == null) {
            existing?.delete()
            return
        }

        val sameProject = existing != null
            && existing.options.applicationId == config.appId
            && existing.options.projectId == config.projectId
        if (existing != null && !sameProject) existing.delete()

        if (existing == null || !sameProject) {
            val options = FirebaseOptions.Builder()
                .setApplicationId(config.appId)
                .setApiKey(config.apiKey)
                .setProjectId(config.projectId)
                .setGcmSenderId(config.senderId)
                .build()
            FirebaseApp.initializeApp(applicationContext, options)
        }

        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        registerCurrentToken(applicationContext)
    }

    fun registerCurrentToken(context: Context) {
        if (!configured(context) || FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (!token.isNullOrBlank()) PushTokenWorker.enqueue(context, token)
        }
    }

    private fun effectiveConfig(context: Context): FirebaseConfigData? {
        SettingsStore(context.applicationContext).firebaseConfig()?.let { return it }
        val bundled = FirebaseConfigData(
            appId = BuildConfig.FIREBASE_APP_ID,
            apiKey = BuildConfig.FIREBASE_API_KEY,
            projectId = BuildConfig.FIREBASE_PROJECT_ID,
            senderId = BuildConfig.FIREBASE_SENDER_ID
        )
        return bundled.takeIf { it.valid() }
    }
}
