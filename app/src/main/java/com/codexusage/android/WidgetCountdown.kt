package com.codexusage.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

// Local-only ticks: no network work, wake locks, foreground service, or exact-alarm permission.
internal object WidgetCountdown {
    const val ACTION_TICK = "com.codexusage.android.WIDGET_COUNTDOWN"

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 2,
        Intent(context, UsageWidget::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(context: Context, active: Boolean) {
        if (!active) return cancel(context)
        val delay = 60000L - System.currentTimeMillis() % 60000L
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + delay, pendingIntent(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }
}
