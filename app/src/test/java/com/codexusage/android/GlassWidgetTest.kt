package com.codexusage.android

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAppWidgetManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassWidgetTest {
    private lateinit var context: Context
    private val now = 1800000000L
    private val windows = listOf(
        UsageWindow("5h", 51.0, now + 10500, 18000, false),
        UsageWindow("Weekly", 90.0, now + 6 * 86400 + 8 * 3600, 604800, false)
    )
    private val weeklyOnly = listOf(null, windows[1])

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().clear().commit()
        WidgetRefreshState.clear(context)
        WidgetCountdown.cancel(context)
    }

    private fun themed(night: Boolean): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
    )

    private fun glassViews(
        themed: Context,
        size: WidgetSize,
        values: List<UsageWindow?>,
        nowSeconds: Long = now
    ): RemoteViews {
        val rings = values.mapIndexed { index, window ->
            UsageWidget.quotaRing(themed, window?.remaining, index == 1, WidgetStyle.GLASS)
        }
        return UsageWidget.createViews(
            themed,
            size,
            values,
            rings,
            nowSeconds,
            "",
            "",
            true,
            false,
            0,
            SizeF(size.width, size.height),
            WidgetStyle.GLASS
        )
    }

    private fun assertGlassShell(view: View, themed: Context, size: WidgetSize, label: String) {
        assertNotNull("$label: glass background is missing", view.background)
        val blurTarget = view.findViewById<View>(R.id.widget_glass_blur)
        assertEquals("$label: ColorOS blur target is missing", "OplusBlurBg", blurTarget.tag)
        assertEquals("$label: ColorOS blur target is hidden", View.VISIBLE, blurTarget.visibility)
        for (colorId in listOf(
            R.color.widget_glass_background_top,
            R.color.widget_glass_background_center,
            R.color.widget_glass_background_bottom
        )) {
            val alpha = Color.alpha(themed.getColor(colorId))
            assertTrue("$label: ${themed.resources.getResourceEntryName(colorId)} alpha=$alpha",
                alpha in 1..254)
        }
        val density = themed.resources.displayMetrics.density
        view.measure(
            View.MeasureSpec.makeMeasureSpec((size.width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((size.height * density).toInt(), View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val rendered = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(rendered))
        val x = (3 * density).toInt().coerceIn(0, view.width - 1)
        val backgroundAlpha = Color.alpha(rendered.getPixel(x, view.height / 2))
        assertTrue("$label: applied glass background alpha=$backgroundAlpha", backgroundAlpha in 1..254)
        val refresh = view.findViewById<View>(R.id.widget_refresh)
        assertNotNull("$label: refresh button is missing", refresh)
        assertEquals(View.VISIBLE, refresh.visibility)
        assertEquals(themed.getString(R.string.widget_refresh_description), refresh.contentDescription.toString())
    }

    // AppWidgetManager.deleteAppWidgetId is hidden in SDK 36, and Robolectric 4.16.1
    // exposes no public deletion method. Remove the shadow's widget record directly.
    private fun deleteWidget(widgets: ShadowAppWidgetManager, id: Int) {
        val field = ShadowAppWidgetManager::class.java.getDeclaredField("widgetInfos").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        (field.get(widgets) as MutableMap<Int, Any>).remove(id)
    }

    @Test fun glassViewsShowEveryQuotaCountdownAndTranslucentShellInBothThemes() {
        for (night in listOf(false, true)) {
            val themed = themed(night)
            for (size in WidgetSize.entries) {
                val dual = glassViews(themed, size, windows).apply(themed, FrameLayout(themed))
                assertEquals("51%", dual.findViewById<TextView>(R.id.widget_primary_value).text.toString())
                assertEquals("90%", dual.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
                assertEquals("2小时55分", dual.findViewById<TextView>(R.id.widget_primary_countdown).text.toString())
                assertEquals("6天8小时", dual.findViewById<TextView>(R.id.widget_weekly_countdown).text.toString())
                assertGlassShell(dual, themed, size, "dual $size night=$night")

                val weekly = glassViews(themed, size, weeklyOnly).apply(themed, FrameLayout(themed))
                assertNull(weekly.findViewById<View>(R.id.widget_primary_value))
                assertEquals("90%", weekly.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
                assertEquals(
                    if (size == WidgetSize.LARGE) "6天8小时" else "6天8小时后恢复",
                    weekly.findViewById<TextView>(R.id.widget_weekly_countdown).text.toString()
                )
                assertGlassShell(weekly, themed, size, "weekly $size night=$night")
            }
        }
    }

    @Test fun glassWarningCriticalAndCountdownColorsFollowThemeAndSize() {
        for (night in listOf(false, true)) {
            val themed = themed(night)
            val critical = themed.getColor(R.color.widget_glass_critical)
            val warning = themed.getColor(R.color.widget_glass_warning)
            val muted = themed.getColor(R.color.widget_glass_muted)
            for (size in WidgetSize.entries) {
                for ((primaryValue, weeklyValue) in listOf(15.0 to 40.0, 40.0 to 15.0)) {
                    val values = listOf(
                        windows[0].copy(remaining = primaryValue),
                        windows[1].copy(remaining = weeklyValue)
                    )
                    val view = glassViews(themed, size, values).apply(themed, FrameLayout(themed))
                    assertEquals(
                        if (primaryValue <= 15.0) critical else warning,
                        view.findViewById<TextView>(R.id.widget_primary_value).currentTextColor
                    )
                    assertEquals(
                        if (weeklyValue <= 15.0) critical else warning,
                        view.findViewById<TextView>(R.id.widget_weekly_value).currentTextColor
                    )
                    for (id in listOf(R.id.widget_primary_countdown, R.id.widget_weekly_countdown)) {
                        assertEquals(
                            "countdown color $size night=$night values=$primaryValue/$weeklyValue",
                            muted,
                            view.findViewById<TextView>(id).currentTextColor
                        )
                    }
                }
            }
        }
    }

    @Test fun glassTextColorsKeepContrastOverBlackAndWhiteWallpapers() {
        val textColors = listOf(
            R.color.widget_glass_text,
            R.color.widget_glass_muted,
            R.color.widget_glass_primary,
            R.color.widget_glass_weekly,
            R.color.widget_glass_warning,
            R.color.widget_glass_critical
        )
        val backgrounds = listOf(
            R.color.widget_glass_background_top,
            R.color.widget_glass_background_center,
            R.color.widget_glass_background_bottom
        )
        for (night in listOf(false, true)) {
            val themed = themed(night)
            for (wallpaper in listOf(Color.BLACK, Color.WHITE)) {
                for (backgroundColor in backgrounds) {
                    val background = ColorUtils.compositeColors(
                        themed.getColor(backgroundColor),
                        wallpaper
                    )
                    for (textColor in textColors) {
                        val ratio = ColorUtils.calculateContrast(themed.getColor(textColor), background)
                        // The dark glass critical color favors a distinct red tint while
                        // retaining the same transparent background over light wallpaper.
                        val minimumContrast = if (night && textColor == R.color.widget_glass_critical) 3.0 else 4.5
                        assertTrue(
                            "night=$night wallpaper=${if (wallpaper == Color.BLACK) "black" else "white"} " +
                                "${themed.resources.getResourceEntryName(textColor)} over " +
                                "${themed.resources.getResourceEntryName(backgroundColor)} " +
                                "contrast=$ratio",
                            ratio >= minimumContrast
                        )
                    }
                }
            }
        }
    }

    @Test fun darkGlassCriticalColorHasAVisibleRedTint() {
        val color = themed(true).getColor(R.color.widget_glass_critical)
        assertTrue("Critical quota color must be visibly red", Color.red(color) - Color.green(color) >= 80)
        assertTrue("Critical quota color must be visibly red", Color.red(color) - Color.blue(color) >= 80)
    }

    @Test fun glassDividersAreSubtleAndLeaveStandardColorsUnchanged() {
        for (night in listOf(false, true)) {
            val themed = themed(night)
            for (size in WidgetSize.entries) for (values in listOf(windows, weeklyOnly)) {
                val standardRings = values.mapIndexed { index, window ->
                    UsageWidget.quotaRing(themed, window?.remaining, index == 1)
                }
                val standard = UsageWidget.createViews(themed, size, values, standardRings, now, "", "", true)
                    .apply(themed, FrameLayout(themed))
                val glass = glassViews(themed, size, values).apply(themed, FrameLayout(themed))
                for (id in listOf(R.id.widget_divider_1, R.id.widget_divider_2)) {
                    val standardDivider = standard.findViewById<View>(id)
                    val glassDivider = glass.findViewById<View>(id)
                    if (standardDivider == null) {
                        assertNull(glassDivider)
                        continue
                    }
                    assertNotNull(glassDivider)
                    assertEquals(themed.getColor(R.color.widget_divider), (standardDivider.background as ColorDrawable).color)
                    val glassColor = (glassDivider.background as ColorDrawable).color
                    assertEquals(themed.getColor(R.color.widget_glass_divider), glassColor)
                    assertTrue("Glass divider should be subtle", Color.alpha(glassColor) in 1..64)
                }
            }
        }
    }

    @Test fun updateAllRefreshesBothProvidersAndKeepsCountdownWhileGlassRemains() {
        val manager = AppWidgetManager.getInstance(context)
        val widgets = shadowOf(manager)
        val standardId = widgets.createWidget(UsageWidget::class.java, R.layout.usage_widget)
        val glassId = widgets.createWidget(GlassUsageWidget::class.java, R.layout.usage_widget_glass_preview)
        val deadline = System.currentTimeMillis() / 1000 + 3600
        val usage = Usage(null, listOf(
            UsageWindow("5h", 67.0, deadline, 18000, false),
            UsageWindow("Weekly", 42.0, deadline, 604800, false)
        ))
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit()
            .putString("mode", "test").commit()
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit()
            .putString("body", UsageParser.displayJson(usage))
            .putLong("updated_at", System.currentTimeMillis())
            .commit()

        UsageWidget.updateAll(context)
        for (id in listOf(standardId, glassId)) {
            val view = widgets.getViewFor(id)
            assertEquals("67%", view.findViewById<TextView>(R.id.widget_primary_value).text.toString())
            assertEquals("42%", view.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
        }
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(AlarmManager.ELAPSED_REALTIME, alarms.scheduledAlarms.single().type)

        deleteWidget(widgets, standardId)
        UsageWidget().onDeleted(context, intArrayOf(standardId))
        assertEquals("Glass countdown must survive deleting the standard widget", 1, alarms.scheduledAlarms.size)
        assertEquals(
            "42%",
            widgets.getViewFor(glassId).findViewById<TextView>(R.id.widget_weekly_value).text.toString()
        )

        deleteWidget(widgets, glassId)
        UsageWidget().onDeleted(context, intArrayOf(glassId))
        assertTrue("Countdown must stop after the last glass widget is deleted", alarms.scheduledAlarms.isEmpty())
    }
}
