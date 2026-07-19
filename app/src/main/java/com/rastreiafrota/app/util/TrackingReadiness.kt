package com.rastreiafrota.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rastreiafrota.app.push.FirebaseBootstrap

data class TrackingReadinessSnapshot(
    val preciseLocation: Boolean,
    val backgroundLocation: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
    val gpsEnabled: Boolean,
    val online: Boolean,
    val firebaseConfigured: Boolean
) {
    val reliableItems = listOf(
        preciseLocation, backgroundLocation, notifications,
        batteryUnrestricted, gpsEnabled, online
    )
    val reliableReady: Boolean get() = reliableItems.all { it }
    val readyCount: Int get() = reliableItems.count { it }
}

/** Estado real das condições que o Android permite conferir sem privilégios especiais. */
object TrackingReadiness {
    fun hasPreciseLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun notificationsEnabled(context: Context): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun snapshot(context: Context) = TrackingReadinessSnapshot(
        preciseLocation = hasPreciseLocation(context),
        backgroundLocation = hasBackgroundLocation(context),
        notifications = notificationsEnabled(context),
        batteryUnrestricted = DeviceInfo.isIgnoringBatteryOptimizations(context),
        gpsEnabled = DeviceInfo.isGpsEnabled(context),
        online = DeviceInfo.isOnline(context),
        firebaseConfigured = FirebaseBootstrap.configured(context)
    )
}
