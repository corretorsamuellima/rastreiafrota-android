package com.rastreiafrota.app.util

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rastreiafrota.app.App
import com.rastreiafrota.app.R
import com.rastreiafrota.app.data.remote.AudioCommandDto
import com.rastreiafrota.app.receiver.AudioCommandActionReceiver
import com.rastreiafrota.app.ui.AudioCommandActivity

object AudioCommandNotifier {
    fun notificationId(commandId: Long): Int = 3000 + (commandId % 100000).toInt()

    fun show(context: Context, command: AudioCommandDto) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val openIntent = AudioCommandActivity.intentFor(context, command)
        val openPending = PendingIntent.getActivity(
            context,
            notificationId(command.id),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rejectIntent = Intent(context, AudioCommandActionReceiver::class.java).apply {
            action = AudioCommandActionReceiver.ACTION_REJECT
            putExtra(AudioCommandActivity.EXTRA_COMMAND_ID, command.id)
            putExtra(AudioCommandActivity.EXTRA_OCCURRENCE_UUID, command.occurrenceUuid)
        }
        val rejectPending = PendingIntent.getBroadcast(
            context,
            notificationId(command.id) + 100000,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val vehicle = command.vehicle?.plate?.takeIf { it.isNotBlank() } ?: "veículo vinculado"
        val title = if (command.type == "sos") "Solicitação SOS de áudio" else "Solicitação de gravação"
        val text = "$vehicle · ${command.durationMinutes} min · ${command.reason}"
        val notification = NotificationCompat.Builder(context, App.CHANNEL_AUDIO_REQUESTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${command.companyName.orEmpty()} solicita uma gravação de ${command.durationMinutes} minuto(s).\n\nMotivo: ${command.reason}\n\nToque para revisar e autorizar."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, "Revisar", openPending)
            .addAction(0, "Recusar", rejectPending)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(command.id), notification)
    }

    fun cancel(context: Context, commandId: Long) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId(commandId))
    }
}
