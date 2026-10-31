package com.codexusage.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
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

internal fun widgetRefreshAge(updatedAt: Long, now: Long): String {
    if (updatedAt <= 0) return ""
    val age = (now - updatedAt / 1000).coerceAtLeast(0)
    return when {
        age < 60 -> "刚刚"
        age >= 3600 -> "1h前"
        else -> "${((age + 59) / 60).coerceIn(2, 59)}m"
    }
}

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
            val usage = repository.displayUsage().takeIf { configured }
            val resetCredits = repository.resetCredits().takeIf { configured }
            val resetCount = repository.credits.takeIf { configured }
            val windows = widgetWindows(usage)
            val refreshing = configured && WidgetRefreshState.active(context)
            val now = System.currentTimeMillis() / 1000
            val status = when {
                !configured -> "请配置"
                usage != null -> widgetRefreshAge(repository.updatedAt, now)
                repository.error != null -> "查询失败"
                else -> "等待查询"
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
                        now, status, detail, configured, refreshing, repository.updatedAt, style,
                        failed = configured && repository.error != null, resetCredits = resetCredits, resetCount = resetCount)
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
                (refreshing || windows.any { (it?.resetAt ?: 0) > now } ||
                    ((resetCount ?: 0) > 0 && resetCredits?.credits.orEmpty().any { it.status == "available" && (it.expiresAt ?: 0) > now }) ||
                    (usage != null && repository.updatedAt > 0 && now - repository.updatedAt / 1000 < 3600)))
        }

        internal fun createViews(
            context: Context, size: WidgetSize, windows: List<UsageWindow?>, rings: List<Bitmap>,
            now: Long, status: String, detail: String, configured: Boolean, refreshing: Boolean = false,
            updatedAt: Long = 0, style: WidgetStyle = WidgetStyle.STANDARD, failed: Boolean = false,
            resetCredits: ResetCredits? = null, resetCount: Int? = resetCredits?.availableCount
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
            if (style == WidgetStyle.GLASS) {
                applyGlassStyle(context, views, size)
                val lowQuota = configured && windows.any { window -> window?.remaining?.let { it <= 10.0 } == true }
                views.setViewVisibility(R.id.widget_low_quota_glow, if (lowQuota) View.VISIBLE else View.GONE)
            }
            views.setImageViewResource(R.id.widget_refresh_icon, when {
                failed -> R.drawable.ic_widget_refresh_warning
                style == WidgetStyle.GLASS -> R.drawable.ic_widget_glass_refresh
                else -> R.drawable.ic_widget_refresh
            })
            views.setContentDescription(R.id.widget_refresh, context.getString(
                if (failed) R.string.widget_refresh_failed_description else R.string.widget_refresh_description))
            val description = mutableListOf("CODEX")
            if (size != WidgetSize.COMPACT) {
                val count = resetCount.takeIf { configured }
                val level = if (configured && (count ?: 0) > 0) resetCredits.expiryLevel(now) else ResetExpiryLevel.NORMAL
                val label = resetCountLabel(count)
                val normalColor = context.getColor(if (style == WidgetStyle.GLASS) R.color.widget_glass_muted else R.color.widget_muted)
                views.setTextViewText(R.id.widget_reset_count, label)
                views.setTextColor(R.id.widget_reset_count,
                    resetExpiryColor(context, level, normalColor, style == WidgetStyle.GLASS))
                val expiryDescription = when (level) {
                    ResetExpiryLevel.CRITICAL -> "，有重置点将在24小时内到期"
                    ResetExpiryLevel.WARNING -> "，有重置点将在72小时内到期"
                    ResetExpiryLevel.NORMAL -> ""
                }
                val resetDescription = "可用重置点 ${count?.toString() ?: "未知"}$expiryDescription"
                views.setContentDescription(R.id.widget_reset_count, resetDescription)
                description += resetDescription
            }
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            for (index in 0..1) {
                if (singleWeekly && index == 0) continue
                val value = windows[index]?.remaining
                val label = if (index == 0) "5h" else "Weekly"
                val percent = percentage(value)
                val countdown = recoveryCountdown(windows[index]?.resetAt, now)
                val normalColor = if (style == WidgetStyle.GLASS) context.getColor(
                    if (singleWeekly) R.color.widget_glass_weekly
                    else if (size == WidgetSize.COMPACT && (night || value == null)) R.color.widget_glass_text
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
                    views.setImageViewBitmap(progressId, quotaProgress(context, value, color, style, size, singleWeekly))
                    views.setContentDescription(progressId, "$label 可用 $percent")
                    WidgetParticleProgress.apply(context, views, index, value, style, size, singleWeekly, !night)
                    val ring = if (size == WidgetSize.WIDE) quotaRing(context, value, index == 1, style, size) else rings[index]
                    views.setImageViewBitmap(if (index == 0) R.id.widget_primary_ring else R.id.widget_weekly_ring, ring)
                    views.setContentDescription(if (index == 0) R.id.widget_primary_ring else R.id.widget_weekly_ring, "$label 可用 $percent")
                    WidgetParticleRing.apply(context, views, index, value, style, size, night)
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
            WidgetLoadingPresentation.apply(context, views, refreshing, style)
            return views
        }

        private fun applyGlassStyle(context: Context, views: RemoteViews, size: WidgetSize) {
            views.setInt(R.id.widget_root, "setBackgroundResource",
                if (size == WidgetSize.COMPACT) R.drawable.widget_compact_glass_background else R.drawable.widget_glass_background)
            views.setViewVisibility(R.id.widget_glass_blur, View.VISIBLE)
            views.setInt(R.id.widget_low_quota_glow, "setBackgroundResource",
                if (size == WidgetSize.LARGE) R.drawable.widget_low_quota_glow else R.drawable.widget_low_quota_glow_compact)
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

        internal fun widgetQuotaColor(context: Context, value: Double?, normalColor: Int, style: WidgetStyle): Int {
            if (value == 100.0) return context.getColor(R.color.widget_quota_full)
            if (style == WidgetStyle.STANDARD) return quotaColor(context, value, normalColor)
            return when {
                value == null -> normalColor
                value <= 15.0 -> context.getColor(R.color.widget_glass_critical)
                value <= 40.0 -> context.getColor(R.color.widget_glass_warning)
                else -> normalColor
            }
        }

        // The night percentage shares the ring bitmap so both scale together.
        internal fun quotaRing(context: Context, value: Double?, weekly: Boolean,
            widgetStyle: WidgetStyle = WidgetStyle.STANDARD, size: WidgetSize = WidgetSize.LARGE): Bitmap =
            WidgetParticleRing.render(context, value, weekly, widgetStyle, size)

        // Bitmap colors survive RemoteViews on Android 8+, including older launchers.
        internal fun quotaProgress(context: Context, value: Double?, color: Int, widgetStyle: WidgetStyle = WidgetStyle.STANDARD,
            size: WidgetSize = WidgetSize.LARGE, singleWeekly: Boolean = false): Bitmap =
            WidgetParticleProgress.render(context, value, color, widgetStyle, size, singleWeekly)

    }
}

class GlassUsageWidget : UsageWidget()
