package com.codexusage.android

import android.content.Context
import android.os.SystemClock

// Visual feedback only. WorkManager still owns the request and deduplicates manual refreshes.
internal object WidgetRefreshState {
    private fun prefs(context: Context) = context.getSharedPreferences("widget_refresh", Context.MODE_PRIVATE)

    fun active(context: Context): Boolean = prefs(context).getLong("until", 0) > System.currentTimeMillis()

    fun start(context: Context) {
        // Recover from interrupted work instead of leaving a permanently blurred widget.
        prefs(context).edit().putLong("until", System.currentTimeMillis() + 120_000)
            .putLong("started_elapsed", SystemClock.elapsedRealtime()).apply()
    }

    // Called on the worker thread. Keep even immediate failures visible for one brief beat,
    // otherwise the launcher may coalesce the loading and finished RemoteViews updates.
    fun finish(context: Context) {
        val delay = (prefs(context).getLong("started_elapsed", -600) + 600 - SystemClock.elapsedRealtime())
            .coerceIn(0, 600)
        if (delay > 0 && active(context)) {
            try { Thread.sleep(delay) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        clear(context)
    }

    fun clear(context: Context) { prefs(context).edit().clear().apply() }
}
