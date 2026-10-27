package com.codexusage.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun percentage(value: Double?): String = value?.let { String.format(Locale.getDefault(), "%.0f%%", it) } ?: "—"
fun localTime(epochSeconds: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))

open class UsageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
        RefreshWorker.schedule(context)
        if (CredentialStore(context).mode != null) RefreshWorker.now(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        updateAll(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) { updateAll(context) }
    override fun onDisabled(context: Context) { updateAll(context) }

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
            val idsByStyle = mapOf(
                WidgetStyle.STANDARD to manager.getAppWidgetIds(ComponentName(context, UsageWidget::class.java)),
                WidgetStyle.GLASS to manager.getAppWidgetIds(ComponentName(context, GlassUsageWidget::class.java))
            )
            val repository = UsageRepository(context)
            val configured = CredentialStore(context).mode != null
            val usage = repository.usage().takeIf { configured }
            val windows = widgetWindows(usage)
            val refreshing = configured && WidgetRefreshState.active(context)
            val now = System.currentTimeMillis() / 1000
            val status = when {
                !configured -> "请配置"
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
            for ((style, ids) in idsByStyle) {
                if (ids.isEmpty()) continue
                val rings = windows.mapIndexed { index, window -> quotaRing(context, window?.remaining, index == 1, style) }
                for (id in ids) {
                    val options = manager.getAppWidgetOptions(id)
                    fun viewsFor(dimensions: SizeF): RemoteViews = createViews(context,
                        widgetSize(dimensions.width.toInt(), dimensions.height.toInt()), windows, rings,
                        now, status, detail, configured, refreshing, repository.updatedAt, dimensions, style)
                    val exactSizes = BundleCompat.getParcelableArrayList(options,
                        AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                    val views = if (!exactSizes.isNullOrEmpty()) {
                        RemoteViews(exactSizes.associateWith(::viewsFor))
                    } else {
                        val portrait = SizeF(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320).toFloat(),
                            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 164).toFloat())
                        val landscape = SizeF(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, portrait.width.toInt()).toFloat(),
                            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, portrait.height.toInt()).toFloat())
                        RemoteViews(viewsFor(landscape), viewsFor(portrait))
                    }
                    manager.updateAppWidget(id, views)
                }
            }
            WidgetCountdown.schedule(context, idsByStyle.values.any { it.isNotEmpty() } && configured &&
                (refreshing || windows.any { (it?.resetAt ?: 0) > now }))
        }

        internal fun createViews(
            context: Context, size: WidgetSize, windows: List<UsageWindow?>, rings: List<Bitmap>,
            now: Long, status: String, detail: String, configured: Boolean, refreshing: Boolean = false,
            updatedAt: Long = 0, displaySize: SizeF = SizeF(size.width, size.height),
            style: WidgetStyle = WidgetStyle.STANDARD
        ): RemoteViews {
            val singleWeekly = weeklyOnly(windows)
            val layout = if (singleWeekly) when (size) {
                WidgetSize.LARGE -> R.layout.usage_widget_weekly
                WidgetSize.WIDE -> R.layout.usage_widget_weekly_wide
                WidgetSize.COMPACT -> R.layout.usage_widget_weekly_compact
            } else when (size) {
                WidgetSize.LARGE -> R.layout.usage_widget
                WidgetSize.WIDE -> R.layout.usage_widget_wide
                WidgetSize.COMPACT -> R.layout.usage_widget_compact
            }
            val views = RemoteViews(context.packageName, layout)
            if (style == WidgetStyle.GLASS) applyGlassStyle(context, views, size)
            val description = mutableListOf("CODEX")
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            for (index in 0..1) {
                if (singleWeekly && index == 0) continue
                val value = windows[index]?.remaining
                val label = if (index == 0) "5h" else "Weekly"
                val percent = percentage(value)
                val countdown = recoveryCountdown(windows[index]?.resetAt, now)
                val normalColor = if (style == WidgetStyle.GLASS) context.getColor(
                    if (singleWeekly) R.color.widget_glass_weekly
                    else if (size == WidgetSize.COMPACT) R.color.widget_glass_text
                    else if (index == 0) R.color.widget_glass_primary else R.color.widget_glass_weekly)
                else context.getColor(if (singleWeekly) R.color.widget_weekly
                    else if (size == WidgetSize.COMPACT) R.color.widget_text
                    else if (index == 0) R.color.widget_primary else R.color.widget_weekly)
                val color = widgetQuotaColor(context, value, normalColor, style)
                val valueId = if (index == 0) R.id.widget_primary_value else R.id.widget_weekly_value
                views.setTextViewText(valueId, percent)
                views.setTextColor(valueId, color)
                val countdownLabel = when {
                    !singleWeekly || size == WidgetSize.LARGE -> countdown
                    windows[index]?.resetAt == null -> context.getString(R.string.widget_recovery_unknown)
                    else -> context.getString(R.string.widget_recovery_after, countdown)
                }
                views.setTextViewText(if (index == 0) R.id.widget_primary_countdown else R.id.widget_weekly_countdown, countdownLabel)
                if (size != WidgetSize.COMPACT) {
                    val progressId = if (index == 0) R.id.widget_primary_progress else R.id.widget_weekly_progress
                    views.setImageViewBitmap(progressId, quotaProgress(context, value, color, style))
                    views.setContentDescription(progressId, "$label 可用 $percent")
                    views.setImageViewBitmap(if (index == 0) R.id.widget_primary_ring else R.id.widget_weekly_ring, rings[index])
                    views.setContentDescription(if (index == 0) R.id.widget_primary_ring else R.id.widget_weekly_ring, "$label 可用 $percent")
                    if (singleWeekly) {
                        views.setViewVisibility(valueId, if (night) View.GONE else View.VISIBLE)
                        views.setViewVisibility(progressId, if (night) View.GONE else View.VISIBLE)
                        views.setViewVisibility(R.id.widget_weekly_ring, if (night) View.VISIBLE else View.GONE)
                    }
                }
                description += "$label 可用 $percent，恢复倒计时 $countdown"
            }
            if (singleWeekly) {
                val updated = context.getString(R.string.widget_updated_at,
                    if (updatedAt > 0) localTime(updatedAt / 1000) else context.getString(R.string.widget_unknown))
                if (size != WidgetSize.COMPACT) views.setTextViewText(R.id.widget_updated_at, updated)
                if (updatedAt > 0) description += updated
            }
            views.setTextViewText(R.id.widget_status, status)
            views.setViewVisibility(R.id.widget_status, if (status.isEmpty()) View.GONE else View.VISIBLE)
            views.setContentDescription(R.id.widget_status, detail)
            views.setContentDescription(R.id.widget_root, (description + detail.takeIf { it.isNotEmpty() }).filterNotNull().joinToString("。"))
            val openIntent = Intent(context, MainActivity::class.java).apply {
                // Launcher identity avoids reusing the old, action-less PendingIntent;
                // FLAG_UPDATE_CURRENT alone only updates its extras, not launch flags.
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val open = PendingIntent.getActivity(context, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val refresh = PendingIntent.getBroadcast(context, 1, Intent(context, UsageWidget::class.java).setAction(ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val installedChatGpt = context.packageManager.getLaunchIntentForPackage("com.openai.chatgpt")
            val chatGptIntent = installedChatGpt ?: openIntent
            val chatGpt = PendingIntent.getActivity(context, 3, chatGptIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val openDescription = context.getString(
                if (installedChatGpt != null) R.string.widget_open_chatgpt else R.string.widget_open_app)
            val chatGptTargets = when (size) {
                WidgetSize.WIDE -> listOf(R.id.widget_open_chatgpt)
                WidgetSize.LARGE -> listOf(R.id.widget_icon_gpt, R.id.widget_static_text_1)
                WidgetSize.COMPACT -> if (singleWeekly) listOf(R.id.widget_weekly_value)
                    else listOf(R.id.widget_primary_value, R.id.widget_weekly_value)
            }
            for (target in chatGptTargets) {
                views.setOnClickPendingIntent(target, chatGpt)
                val prefix = when (target) {
                    R.id.widget_primary_value -> "5h 可用 ${percentage(windows[0]?.remaining)}，"
                    R.id.widget_weekly_value -> "Weekly 可用 ${percentage(windows[1]?.remaining)}，"
                    else -> ""
                }
                views.setContentDescription(target, prefix + openDescription)
            }
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.widget_refresh, if (configured) refresh else open)
            WidgetLoadingPresentation.apply(context, views, displaySize, refreshing, style)
            return views
        }

        private fun applyGlassStyle(context: Context, views: RemoteViews, size: WidgetSize) {
            views.setInt(R.id.widget_root, "setBackgroundResource",
                if (size == WidgetSize.COMPACT) R.drawable.widget_compact_glass_background else R.drawable.widget_glass_background)
            views.setViewVisibility(R.id.widget_glass_blur, View.VISIBLE)
            val muted = context.getColor(R.color.widget_glass_muted)
            for (id in intArrayOf(R.id.widget_status, R.id.widget_primary_countdown, R.id.widget_weekly_countdown,
                R.id.widget_updated_at, R.id.widget_static_muted_1, R.id.widget_static_muted_2, R.id.widget_static_muted_3)) {
                views.setTextColor(id, muted)
            }
            val text = context.getColor(R.color.widget_glass_text)
            for (id in intArrayOf(R.id.widget_static_text_1, R.id.widget_static_text_2, R.id.widget_static_text_3)) {
                views.setTextColor(id, text)
            }
            views.setTextColor(R.id.widget_static_primary_1, context.getColor(R.color.widget_glass_primary))
            views.setTextColor(R.id.widget_static_weekly_1, context.getColor(R.color.widget_glass_weekly))
            val divider = context.getColor(R.color.widget_glass_divider)
            views.setInt(R.id.widget_divider_1, "setBackgroundColor", divider)
            views.setInt(R.id.widget_divider_2, "setBackgroundColor", divider)
            views.setImageViewResource(R.id.widget_icon_clock, R.drawable.ic_widget_glass_clock)
            views.setImageViewResource(R.id.widget_icon_calendar, R.drawable.ic_widget_glass_calendar)
            views.setImageViewResource(R.id.widget_icon_gpt, R.drawable.ic_widget_glass_gpt)
            views.setImageViewResource(R.id.widget_refresh_icon, R.drawable.ic_widget_glass_refresh)
        }

        private fun widgetQuotaColor(context: Context, value: Double?, normalColor: Int, style: WidgetStyle): Int {
            if (style == WidgetStyle.STANDARD) return quotaColor(context, value, normalColor)
            return when {
                value == null -> normalColor
                value <= 15.0 -> context.getColor(R.color.widget_glass_critical)
                value <= 40.0 -> context.getColor(R.color.widget_glass_warning)
                else -> normalColor
            }
        }

        // Scale the night-mode percentage with its ring. A separate full-column TextView
        // fits the column width rather than the circle's inner diameter on short widgets.
        internal fun quotaRing(context: Context, value: Double?, weekly: Boolean, widgetStyle: WidgetStyle = WidgetStyle.STANDARD): Bitmap {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 20f
                strokeCap = Paint.Cap.ROUND
                color = context.getColor(if (widgetStyle == WidgetStyle.GLASS) R.color.widget_glass_track else R.color.widget_track)
            }
            val bounds = RectF(14f, 14f, 242f, 242f)
            canvas.drawOval(bounds, paint)
            if (value != null && value > 0) {
                val colorResource = if (widgetStyle == WidgetStyle.GLASS) {
                    if (weekly) R.color.widget_glass_weekly else R.color.widget_glass_primary
                } else if (weekly) R.color.widget_weekly else R.color.widget_primary
                paint.color = widgetQuotaColor(context, value, context.getColor(colorResource), widgetStyle)
                canvas.drawArc(bounds, -90f, (value.coerceIn(0.0, 100.0) * 3.6).toFloat(), false, paint)
            }
            val label = percentage(value)
            paint.apply {
                style = Paint.Style.FILL
                color = widgetQuotaColor(context, value,
                    context.getColor(if (widgetStyle == WidgetStyle.GLASS) R.color.widget_glass_text else R.color.widget_text), widgetStyle)
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

        // Bitmap colors survive RemoteViews on Android 8+, including older launchers.
        internal fun quotaProgress(context: Context, value: Double?, color: Int, widgetStyle: WidgetStyle = WidgetStyle.STANDARD): Bitmap {
            val bitmap = Bitmap.createBitmap(1000, 20, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = context.getColor(if (widgetStyle == WidgetStyle.GLASS) R.color.widget_glass_track else R.color.widget_track)
            }
            val bounds = RectF(0f, 0f, 1000f, 20f)
            canvas.drawRoundRect(bounds, 10f, 10f, paint)
            val fraction = (value ?: 0.0).coerceIn(0.0, 100.0) / 100.0
            if (fraction > 0) {
                canvas.save()
                canvas.clipRect(0f, 0f, (1000 * fraction).toFloat(), 20f)
                paint.color = color
                canvas.drawRoundRect(bounds, 10f, 10f, paint)
                canvas.restore()
            }
            return bitmap
        }

    }
}

class GlassUsageWidget : UsageWidget()
