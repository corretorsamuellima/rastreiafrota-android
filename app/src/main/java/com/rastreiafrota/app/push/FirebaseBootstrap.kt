package com.rastreiafrota.app.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.rastreiafrota.app.BuildConfig
import com.rastreiafrota.app.work.PushTokenWorker

object FirebaseBootstrap {
    fun configured(): Boolean = listOf(
        BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY,
        BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_SENDER_ID
    ).all { it.isNotBlank() }

    fun initialize(context: Context) {
        if (!configured()) return
        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(context, options)
        }
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        registerCurrentToken(context)
    }

    fun registerCurrentToken(context: Context) {
        if (!configured() || FirebaseApp.getApps(context).isEmpty()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (!token.isNullOrBlank()) PushTokenWorker.enqueue(context, token)
        }
    }
}

