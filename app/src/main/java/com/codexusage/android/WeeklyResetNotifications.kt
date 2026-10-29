package com.codexusage.android

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationCompat

internal fun shouldNotifyWeeklyReset(previous: Usage?, current: Usage): Boolean {
    fun Usage.weeklyRemaining() = windows.firstOrNull { !it.additional && it.seconds == 604800L }?.remaining
    val before = previous?.weeklyRemaining() ?: return false
    val after = current.weeklyRemaining() ?: return false
    return after > before && after > 95.0
}

class WeeklyResetNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.weekly_reset_channel), NotificationManager.IMPORTANCE_DEFAULT
        ))
    }

    fun requestPermissionOnce(activity: Activity) {
        createChannel()
        val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
        if (prefs.getBoolean("permission_requested", false)) return
        prefs.edit().putBoolean("permission_requested", true).apply()
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST)
        }
    }

    fun openSettings() {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }

    fun notifyReset() {
        createChannel()
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val openApp = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_quota)
            .setContentTitle(context.getString(R.string.weekly_reset_title))
            .setContentText(context.getString(R.string.weekly_reset_message))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build())
    }

    companion object {
        internal const val CHANNEL_ID = "weekly_quota_reset"
        internal const val NOTIFICATION_ID = 1001
        internal const val PERMISSION_REQUEST = 1002
    }
}
