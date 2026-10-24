package com.codexusage.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

fun percentage(value: Double?): String = value?.let { String.format(Locale.getDefault(), "%.0f%%", it) } ?: "—"
fun localTime(epochSeconds: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))

class UsageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
        RefreshWorker.schedule(context)
        if (CredentialStore(context).mode != null) RefreshWorker.now(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        updateAll(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) { updateAll(context) }
    override fun onDisabled(context: Context) { WidgetCountdown.cancel(context) }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> if (CredentialStore(context).mode != null) {
                RefreshWorker.schedule(context)
                RefreshWorker.now(context)
            }
            WidgetCountdown.ACTION_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> updateAll(context)
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.codexusage.android.REFRESH"

        fun updateAll(context: Context) = synchronized(ConfigurationLock) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, UsageWidget::class.java))
            val repository = UsageRepository(context)
            val configured = CredentialStore(context).mode != null
            val usage = repository.usage().takeIf { configured }
            val windows = widgetWindows(usage)
            val refreshing = configured && WidgetRefreshState.active(context)
            val now = System.currentTimeMillis() / 1000
            val status = when {
                !configured -> "请配置"
                refreshing -> "刷新中"
                repository.error != null -> if (usage != null) "缓存 · !" else "查询失败"
                usage == null -> "等待查询"
                else -> ""
            }
            val detail = when {
                !configured -> "请打开应用配置凭据"
                refreshing -> "正在刷新额度"
                repository.error != null -> listOfNotNull(
                    if (usage != null) "旧数据" else null,
                    if (repository.updatedAt > 0) "更新于 ${localTime(repository.updatedAt / 1000)}" else null,
                    repository.error
                ).joinToString("，")
                else -> ""
            }
            val rings = windows.mapIndexed { index, window -> quotaRing(window?.remaining, index == 1, refreshing) }
            for (id in ids) {
                val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    RemoteViews(WidgetSize.entries.associate { size ->
                        SizeF(size.width, size.height) to createViews(context, size, windows, rings, now, status, detail, configured, refreshing)
                    })
                } else {
                    val options = manager.getAppWidgetOptions(id)
                    val size = widgetSize(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320),
                        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 164))
                    createViews(context, size, windows, rings, now, status, detail, configured, refreshing)
                }
                manager.updateAppWidget(id, views)
            }
            WidgetCountdown.schedule(context, ids.isNotEmpty() && configured &&
                (refreshing || windows.any { (it?.resetAt ?: 0) > now }))
        }

        internal fun createViews(
            context: Context, size: WidgetSize, windows: List<UsageWindow?>, rings: List<Bitmap>,
            now: Long, status: String, detail: String, configured: Boolean, refreshing: Boolean = false
        ): RemoteViews {
            val layout = when (size) {
                WidgetSize.LARGE -> R.layout.usage_widget
                WidgetSize.WIDE -> R.layout.usage_widget_wide
                WidgetSize.COMPACT -> R.layout.usage_widget_compact
            }
            val views = RemoteViews(context.packageName, layout)
            val description = mutableListOf("CODEX")
            for (index in 0..1) {
                val value = windows[index]?.remaining
                val label = if (index == 0) "5h" else "Weekly"
                val percent = percentage(value)
                val countdown = recoveryCountdown(windows[index]?.resetAt, now)
                views.setTextViewText(if (index == 0) R.id.widget_primary_value else R.id.widget_weekly_value, if (refreshing) "" else percent)
                val loadingId = if (index == 0) R.id.widget_primary_loading_value else R.id.widget_weekly_loading_value
                views.setTextViewText(loadingId, percent)
                views.setViewVisibility(loadingId, if (refreshing) View.VISIBLE else View.GONE)
                views.setTextViewText(if (index == 0) R.id.widget_primary_countdown else R.id.widget_weekly_countdown, countdown)
                if (size != WidgetSize.COMPACT) {
                    views.setProgressBar(if (index == 0) R.id.widget_primary_progress else R.id.widget_weekly_progress,
                        1000, ((value ?: 0.0) * 10).roundToInt().coerceIn(0, 1000), false)
                    views.setImageViewBitmap(if (index == 0) R.id.widget_primary_ring else R.id.widget_weekly_ring, rings[index])
                    views.setContentDescription(if (index == 0) R.id.widget_primary_ring else R.id.widget_weekly_ring, "$label 可用 $percent")
                }
                description += "$label 可用 $percent，恢复倒计时 $countdown"
            }
            views.setTextViewText(R.id.widget_status, status)
            views.setViewVisibility(R.id.widget_status, if (status.isEmpty()) View.GONE else View.VISIBLE)
            views.setContentDescription(R.id.widget_status, detail)
            views.setContentDescription(R.id.widget_root, (description + detail.takeIf { it.isNotEmpty() }).filterNotNull().joinToString("。"))
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val refresh = PendingIntent.getBroadcast(context, 1, Intent(context, UsageWidget::class.java).setAction(ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.widget_refresh, if (configured) refresh else open)
            return views
        }

        // Scale the night-mode percentage with its ring. A separate full-column TextView
        // fits the column width rather than the circle's inner diameter on short widgets.
        internal fun quotaRing(value: Double?, weekly: Boolean, refreshing: Boolean = false): Bitmap {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 20f
                strokeCap = Paint.Cap.ROUND
                color = Color.rgb(47, 56, 61)
                if (refreshing) maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
            }
            val bounds = RectF(14f, 14f, 242f, 242f)
            canvas.drawOval(bounds, paint)
            if (value != null && value > 0) {
                paint.color = Color.parseColor(if (weekly) "#42D9E8" else "#29DDA0")
                canvas.drawArc(bounds, -90f, (value.coerceIn(0.0, 100.0) * 3.6).toFloat(), false, paint)
            }
            val label = percentage(value)
            paint.apply {
                style = Paint.Style.FILL
                color = Color.rgb(245, 248, 249)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textSize = 80f
            }
            val textBounds = Rect()
            paint.getTextBounds(label, 0, label.length, textBounds)
            // The 144 x 100 text box fits comfortably inside the 208px inner circle,
            // even at its corners. Measure the full label, including 100% and locale digits.
            val scale = minOf(1f, 144f / maxOf(1f, paint.measureText(label)),
                100f / maxOf(1, textBounds.height()).toFloat())
            paint.textSize *= scale
            paint.getTextBounds(label, 0, label.length, textBounds)
            if (value == null) {
                // A font's thin em dash can disappear when the launcher downsamples it.
                canvas.drawRoundRect(92f, 120f, 164f, 136f, 8f, 8f, paint)
            } else {
                canvas.drawText(label, 128f - textBounds.exactCenterX(),
                    128f - textBounds.exactCenterY(), paint)
            }
            return bitmap
        }
    }
}
