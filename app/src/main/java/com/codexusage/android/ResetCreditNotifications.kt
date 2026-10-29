package com.codexusage.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat

class ResetCreditNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun notifyAdded(added: Int, available: Int) {
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.reset_credit_channel), NotificationManager.IMPORTANCE_DEFAULT
        ))
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val openApp = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_quota)
            .setContentTitle(context.getString(R.string.reset_credit_title))
            .setContentText(context.getString(R.string.reset_credit_message, added, available))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build())
    }

    companion object {
        internal const val CHANNEL_ID = "new_reset_credits"
        internal const val NOTIFICATION_ID = 1003
    }
}
