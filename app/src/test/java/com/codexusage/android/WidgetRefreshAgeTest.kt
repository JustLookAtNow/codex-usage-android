package com.codexusage.android

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetRefreshAgeTest {
    private lateinit var context: Context

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        UsageRepository(context).clear()
        WidgetRefreshState.clear(context)
        WidgetCountdown.cancel(context)
    }

    @Test fun ageUsesRequestedMinuteBoundariesAndCapsAtOneHour() {
        val updatedAt = 1800000000000L
        for ((seconds, expected) in listOf(
            0L to "刚刚", 59L to "刚刚", 60L to "2m", 119L to "2m",
            120L to "2m", 121L to "3m", 3481L to "59m", 3540L to "59m",
            3599L to "59m", 3600L to "1h前", 86401L to "1h前"
        )) assertEquals("elapsed=$seconds", expected, widgetRefreshAge(updatedAt, updatedAt / 1000 + seconds))
        assertEquals("", widgetRefreshAge(0, updatedAt / 1000))
    }

    @Test fun allLayoutsKeepSuccessfulAgeAndQuotaAfterFailure() {
        val manager = AppWidgetManager.getInstance(context)
        val widgets = shadowOf(manager)
        val updatedAt = System.currentTimeMillis() - 90000
        val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
        val ids = WidgetStyle.entries.associateWith { style ->
            val provider = if (style == WidgetStyle.GLASS) GlassUsageWidget::class.java else UsageWidget::class.java
            widgets.createWidget(provider, R.layout.usage_widget)
        }
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().putString("mode", "test").commit()
        for ((style, id) in ids) {
            for (size in WidgetSize.entries) for (single in listOf(false, true)) {
                manager.updateAppWidgetOptions(id, Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size.width.toInt())
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, size.height.toInt())
                })
                val windows = listOfNotNull(
                    UsageWindow("5h", 75.0, null, 18000, false).takeUnless { single },
                    UsageWindow("Weekly", 50.0, null, 604800, false))
                prefs.edit().putString("body", UsageParser.displayJson(Usage(null, windows)))
                    .putLong("updated_at", updatedAt).remove("error").commit()
                for (failed in listOf(false, true)) {
                    if (failed) prefs.edit().putString("error", "网络连接失败").commit()
                    UsageWidget.updateAll(context)
                    val view = widgets.getViewFor(id)
                    val status = view.findViewById<TextView>(R.id.widget_status)
                    assertEquals("2m", status.text.toString())
                    assertEquals(View.VISIBLE, status.visibility)
                    assertFalse(status.text.contains("缓存"))
                    assertEquals("50%", view.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
                    assertEquals(updatedAt, UsageRepository(context).updatedAt)
                    if (single && size != WidgetSize.COMPACT) assertEquals("更新于 ${localTime(updatedAt / 1000)}",
                        view.findViewById<TextView>(R.id.widget_updated_at).text.toString())
                    val icon = shadowOf(view.findViewById<ImageView>(R.id.widget_refresh_icon).drawable).createdFromResId
                    assertEquals(if (failed) R.drawable.ic_widget_refresh_warning
                        else if (style == WidgetStyle.GLASS) R.drawable.ic_widget_glass_refresh
                        else R.drawable.ic_widget_refresh, icon)
                    if (failed) assertTrue(status.contentDescription.toString().contains("网络连接失败"))
                }
            }
        }
    }

    @Test fun localTicksRenderAgeWithoutRecoveryDeadlineOrNetworkAndStopAtOneHour() {
        val widgets = shadowOf(AppWidgetManager.getInstance(context))
        val id = widgets.createWidget(UsageWidget::class.java, R.layout.usage_widget)
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().putString("mode", "test").commit()
        val updatedAt = System.currentTimeMillis()
        val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("body", UsageParser.displayJson(Usage(null,
                listOf(UsageWindow("Weekly", 50.0, updatedAt / 1000 - 1, 604800, false)))))
            .putLong("updated_at", updatedAt).putString("error", "网络连接失败").commit()
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        fun tick() = UsageWidget().onReceive(context,
            Intent(context, UsageWidget::class.java).setAction(WidgetCountdown.ACTION_TICK))
        // WorkManager is not initialized; local ticks must not enqueue requests.
        tick()
        assertEquals("刚刚", widgets.getViewFor(id).findViewById<TextView>(R.id.widget_status).text.toString())
        assertEquals(1, alarms.scheduledAlarms.size)
        // Seed older successful data rather than wait for wall-clock time in the test.
        val minuteOld = System.currentTimeMillis() - 90000
        prefs.edit().putLong("updated_at", minuteOld).commit()
        tick()
        assertEquals("2m", widgets.getViewFor(id).findViewById<TextView>(R.id.widget_status).text.toString())
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(minuteOld, UsageRepository(context).updatedAt)
        val hourOld = System.currentTimeMillis() - 3600000
        prefs.edit().putLong("updated_at", hourOld).commit()
        tick()
        assertEquals("1h前", widgets.getViewFor(id).findViewById<TextView>(R.id.widget_status).text.toString())
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals(hourOld, UsageRepository(context).updatedAt)
    }

    @Test fun noDataRetainsWaitingAndFailureStates() {
        val widgets = shadowOf(AppWidgetManager.getInstance(context))
        val id = widgets.createWidget(UsageWidget::class.java, R.layout.usage_widget)
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().putString("mode", "test").commit()
        UsageWidget.updateAll(context)
        assertEquals("等待查询", widgets.getViewFor(id).findViewById<TextView>(R.id.widget_status).text.toString())
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().putString("error", "网络连接失败").commit()
        UsageWidget.updateAll(context)
        assertEquals("查询失败", widgets.getViewFor(id).findViewById<TextView>(R.id.widget_status).text.toString())
    }
}
