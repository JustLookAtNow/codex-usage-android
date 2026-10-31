package com.codexusage.android

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
class ResetCreditWidgetTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val now = 1800000000L
    private fun credit(remaining: Long?) = ResetCredit("one", null, "available", null, remaining?.let { now + it }, null, null)

    @Before fun setup() {
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        UsageRepository(context).clear()
        WidgetCountdown.cancel(context)
    }

    private fun bounds(root: View, target: View): RectF {
        var x = 0f
        var y = 0f
        var current = target
        while (current !== root) {
            x += current.x
            y += current.y
            current = current.parent as View
        }
        return RectF(x, y, x + target.width, y + target.height)
    }

    @Test fun countsFitWithoutCoveringControlsAndUseCorrectColorAcrossAllLayouts() {
        val weekly = UsageWindow("Weekly", 90.0, now + 86400, 604800, false)
        val five = UsageWindow("5h", 51.0, now + 3600, 18000, false)
        for (night in listOf(false, true)) for (single in listOf(false, true)) for (style in WidgetStyle.entries) {
            val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            })
            val windows = listOf(five.takeUnless { single }, weekly)
            val rings = windows.mapIndexed { index, window -> UsageWidget.quotaRing(themed, window?.remaining, index == 1, style) }
            for ((size, dimensions) in listOf(WidgetSize.WIDE to listOf(240 to 64, 360 to 80),
                WidgetSize.LARGE to listOf(240 to 140, 360 to 168))) {
                for ((width, height) in dimensions) for ((rows, expected) in listOf(
                    listOf(credit(80 * 3600)) to ResetExpiryLevel.NORMAL,
                    listOf(credit(48 * 3600)) to ResetExpiryLevel.WARNING,
                    listOf(credit(48 * 3600), credit(3600)) to ResetExpiryLevel.CRITICAL
                )) {
                    val view = UsageWidget.createViews(themed, size, windows, rings, now, "1h前", "旧数据", true,
                        updatedAt = (now - 3600) * 1000, style = style, resetCredits = ResetCredits(rows.size, null, rows))
                        .apply(themed, FrameLayout(themed)) as ViewGroup
                    val density = themed.resources.displayMetrics.density
                    view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                    val count = view.findViewById<TextView>(R.id.widget_reset_count)
                    val countBounds = bounds(view, count)
                    val case = "night=$night single=$single style=$style size=${width}x$height count=$countBounds"
                    assertEquals(resetCountLabel(rows.size), count.text.toString())
                    val normal = themed.getColor(if (style == WidgetStyle.GLASS) R.color.widget_glass_muted else R.color.widget_muted)
                    assertEquals(resetExpiryColor(themed, expected, normal, style == WidgetStyle.GLASS), count.currentTextColor)
                    assertTrue(case, countBounds.left >= view.width / 2)
                    assertTrue(case, countBounds.right <= view.width && countBounds.bottom <= view.height)
                    assertTrue(case, count.paint.measureText(count.text.toString()) <= countBounds.width())
                    for (id in listOf(R.id.widget_refresh, R.id.widget_status, R.id.widget_weekly_countdown,
                        R.id.widget_primary_countdown, R.id.widget_updated_at)) {
                        val other = view.findViewById<View>(id) ?: continue
                        if (other.visibility == View.VISIBLE) assertFalse("$case overlaps=$id ${bounds(view, other)}",
                            RectF.intersects(countBounds, bounds(view, other)))
                    }
                }
            }
            val compact = UsageWidget.createViews(themed, WidgetSize.COMPACT, windows, rings, now, "", "", true)
                .apply(themed, FrameLayout(themed))
            assertNull(compact.findViewById<View>(R.id.widget_reset_count))
        }
    }

    @Test fun unknownZeroAndClearedConfigurationResetTheLabelAndColor() {
        val windows = listOf<UsageWindow?>(null, UsageWindow("Weekly", 50.0, null, 604800, false))
        val rings = windows.mapIndexed { index, window -> UsageWidget.quotaRing(context, window?.remaining, index == 1) }
        val urgent = ResetCredits(2, null, listOf(credit(3600)))
        var view: View? = null
        for ((count, configured, expected) in listOf(Triple(2, true, "2 resets"), Triple(0, true, "0 resets"),
            Triple(null, true, "— resets"), Triple(2, false, "— resets"))) {
            val remote = UsageWidget.createViews(context, WidgetSize.WIDE, windows, rings, now, "", "", configured,
                resetCredits = urgent, resetCount = count)
            if (view == null) view = remote.apply(context, FrameLayout(context)) else remote.reapply(context, view)
            val label = view!!.findViewById<TextView>(R.id.widget_reset_count)
            assertEquals(expected, label.text.toString())
            assertEquals(context.getColor(if (count == 2 && configured) R.color.quota_critical else R.color.widget_muted), label.currentTextColor)
        }
    }

    @Test fun resetExpiryKeepsLocalTicksAliveWithoutUsageDataOrNetworkAndClearsOnRemoval() {
        val manager = AppWidgetManager.getInstance(context)
        val widgets = shadowOf(manager)
        val id = widgets.createWidget(UsageWidget::class.java, R.layout.usage_widget_wide)
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 64)
        })
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().putString("mode", "test").commit()
        val actualNow = System.currentTimeMillis() / 1000
        val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
        fun saveExpiry(remaining: Long) {
            val row = credit(null).copy(expiresAt = actualNow + remaining)
            prefs.edit().putInt("credits", 1)
                .putString("credits_body", ResetCreditsParser.displayJson(ResetCredits(1, null, listOf(row)))).commit()
        }
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        fun tick() = UsageWidget().onReceive(context, Intent(context, UsageWidget::class.java).setAction(WidgetCountdown.ACTION_TICK))
        saveExpiry(86401)
        tick()
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(context.getColor(R.color.quota_warning), widgets.getViewFor(id).findViewById<TextView>(R.id.widget_reset_count).currentTextColor)
        saveExpiry(86400)
        tick()
        assertEquals(context.getColor(R.color.quota_critical), widgets.getViewFor(id).findViewById<TextView>(R.id.widget_reset_count).currentTextColor)
        saveExpiry(-1)
        tick()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNull(UsageRepository(context).error)
        saveExpiry(3600)
        tick()
        CredentialStore(context).clear()
        UsageRepository(context).clear()
        tick()
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertEquals("— resets", widgets.getViewFor(id).findViewById<TextView>(R.id.widget_reset_count).text.toString())
    }
}
