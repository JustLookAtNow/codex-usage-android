package com.codexusage.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun percentage(value: Double?): String = value?.let { String.format(Locale.getDefault(), "%.0f%%", it) } ?: "—"
fun localTime(epochSeconds: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))

class UsageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
        RefreshWorker.schedule(context)
        if (CredentialStore(context).mode != null) RefreshWorker.now(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            if (CredentialStore(context).mode != null) {
                RefreshWorker.schedule(context)
                RefreshWorker.now(context)
            }
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.codexusage.android.REFRESH"
        fun updateAll(context: Context) = synchronized(ConfigurationLock) {
            val manager = AppWidgetManager.getInstance(context)
            val repository = UsageRepository(context)
            val configured = CredentialStore(context).mode != null
            val usage = repository.usage().takeIf { configured }
            val windows = usage?.windows?.filter { !it.additional }.orEmpty()
            val main = windows.take(2)
            val status = if (!configured) "请打开应用配置凭据" else listOfNotNull(
                if (repository.updatedAt > 0) "更新于 ${localTime(repository.updatedAt / 1000)}" else "等待首次查询",
                repository.error?.let { (if (usage != null) "旧数据 · " else "") + it }
            ).joinToString("\n")
            val views = RemoteViews(context.packageName, R.layout.usage_widget)
            views.setTextViewText(R.id.widget_usage, if (main.isEmpty()) "5h  —\nWeekly  —" else main.joinToString("\n") { "${it.label}  ${percentage(it.remaining)}" })
            views.setTextViewText(R.id.widget_reset, if (main.isEmpty()) "点击打开应用配置与查看详情" else main.joinToString("\n") { "重置 ${it.resetAt?.let(::localTime) ?: "未提供"}" })
            views.setTextViewText(R.id.widget_status, status)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val refresh = PendingIntent.getBroadcast(context, 1, Intent(context, UsageWidget::class.java).setAction(ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.widget_refresh, if (configured) refresh else open)
            for (id in manager.getAppWidgetIds(ComponentName(context, UsageWidget::class.java))) manager.updateAppWidget(id, views)
        }
    }
}
