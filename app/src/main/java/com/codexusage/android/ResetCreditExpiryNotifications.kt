package com.codexusage.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat

internal class ResetCreditExpiryNotifications(private val context: Context) {
    fun notifyExpiring(credits: List<ResetCredit>, nowSeconds: Long) {
        val critical = credits.count { it.expiryLevel(nowSeconds) == ResetExpiryLevel.CRITICAL }
        val warning = credits.count { it.expiryLevel(nowSeconds) == ResetExpiryLevel.WARNING }
        if (critical + warning == 0) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.reset_expiry_channel), NotificationManager.IMPORTANCE_DEFAULT
        ))
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val soonest = credits.filter { it.expiryLevel(nowSeconds) != ResetExpiryLevel.NORMAL }.minOf { it.expiresAt!! }
        val message = buildList {
            if (critical > 0) add(context.getString(R.string.reset_expiry_critical_count, critical))
            if (warning > 0) add(context.getString(R.string.reset_expiry_warning_count, warning))
            add(context.getString(R.string.reset_expiry_soonest, localTime(soonest)))
        }.joinToString("；")
        val openApp = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_quota)
            .setContentTitle(context.getString(if (critical > 0) R.string.reset_expiry_critical_title else R.string.reset_expiry_warning_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build())
    }

    companion object {
        internal const val CHANNEL_ID = "reset_credit_expiry"
        internal const val NOTIFICATION_ID = 1004
    }
}
