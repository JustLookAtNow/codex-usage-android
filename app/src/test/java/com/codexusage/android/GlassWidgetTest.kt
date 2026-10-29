package com.codexusage.android

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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

    @Test fun lightGlassQuotaNumbersAndProgressUseMatchingColors() {
        val themed = themed(false)
        for (size in WidgetSize.entries) for (singleWeekly in listOf(false, true)) {
            for ((value, colorId) in listOf(
                51.0 to R.color.widget_glass_primary,
                40.0 to R.color.widget_glass_warning,
                15.0 to R.color.widget_glass_critical
            )) {
                val values = if (singleWeekly) listOf(null, windows[1].copy(remaining = value))
                    else windows.map { it.copy(remaining = value) }
                val view = glassViews(themed, size, values).apply(themed, FrameLayout(themed))
                val color = themed.getColor(colorId)
                for (index in if (singleWeekly) 1..1 else 0..1) {
                    val valueId = if (index == 0) R.id.widget_primary_value else R.id.widget_weekly_value
                    assertEquals("$size singleWeekly=$singleWeekly value=$value index=$index",
                        color, view.findViewById<TextView>(valueId).currentTextColor)
                    if (size != WidgetSize.COMPACT) {
                        val progressId = if (index == 0) R.id.widget_primary_progress else R.id.widget_weekly_progress
                        val bitmap = (view.findViewById<ImageView>(progressId).drawable as BitmapDrawable).bitmap
                        assertTrue((0 until bitmap.height).any { bitmap.getPixel(10, it) == color })
                        assertTrue((0 until bitmap.height).any { bitmap.getPixel(980, it) == themed.getColor(R.color.widget_glass_track) })
                        val headX = (value * 10).toInt() - 2
                        assertTrue(
                            "White progress head must contrast with track by at least 3:1: $size singleWeekly=$singleWeekly value=$value index=$index",
                            (0 until bitmap.height).any {
                                ColorUtils.calculateContrast(
                                    bitmap.getPixel(headX, it),
                                    themed.getColor(R.color.widget_glass_track)
                                ) >= 3.0
                            }
                        )
                    }
                }
            }
        }
    }

    @Test fun lowQuotaGlowTracksDisplayedRemainingAcrossSizesAndThemes() {
        for (night in listOf(false, true)) {
            val themed = themed(night)
            for (size in WidgetSize.entries) {
                val cases = listOf(
                    listOf(windows[0].copy(remaining = 10.0), windows[1]) to View.VISIBLE,
                    listOf(windows[0].copy(remaining = 10.0001), windows[1]) to View.GONE,
                    listOf(windows[0], windows[1].copy(remaining = 0.0)) to View.VISIBLE,
                    listOf(windows[0].copy(remaining = null), windows[1]) to View.GONE,
                    listOf(null, windows[1].copy(remaining = 10.0)) to View.VISIBLE,
                    listOf(null, windows[1].copy(remaining = 10.0001)) to View.GONE,
                    listOf(null, null) to View.GONE
                )
                for ((values, expected) in cases) {
                    val view = glassViews(themed, size, values).apply(themed, FrameLayout(themed))
                    assertEquals("size=$size night=$night values=$values", expected,
                        view.findViewById<View>(R.id.widget_low_quota_glow).visibility)
                }
                val low = listOf(windows[0].copy(remaining = 1.0), windows[1])
                val rings = low.mapIndexed { index, window -> UsageWidget.quotaRing(themed, window.remaining, index == 1) }
                val standard = UsageWidget.createViews(themed, size, low, rings, now, "", "", true)
                    .apply(themed, FrameLayout(themed))
                assertEquals(View.GONE, standard.findViewById<View>(R.id.widget_low_quota_glow).visibility)
                val unconfigured = UsageWidget.createViews(themed, size, low, rings, now, "", "", false,
                    style = WidgetStyle.GLASS).apply(themed, FrameLayout(themed))
                assertEquals(View.GONE, unconfigured.findViewById<View>(R.id.widget_low_quota_glow).visibility)

                val view = glassViews(themed, size, low).apply(themed, FrameLayout(themed))
                glassViews(themed, size, windows).reapply(themed, view)
                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_low_quota_glow).visibility)
                glassViews(themed, size, low).reapply(themed, view)
                assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_low_quota_glow).visibility)
            }
        }
    }

    @Test fun lowQuotaGlowIsRedAndLimitedToBottomEdge() {
        for (night in listOf(false, true)) for (size in WidgetSize.entries) {
            val themed = themed(night)
            val values = listOf(windows[0].copy(remaining = 9.0), windows[1])
            val view = glassViews(themed, size, values).apply(themed, FrameLayout(themed))
            val drawable = view.findViewById<View>(R.id.widget_low_quota_glow).background
            val bitmap = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            assertEquals(0, Color.alpha(bitmap.getPixel(150, 10)))
            val bottom = bitmap.getPixel(150, 98)
            assertTrue("size=$size night=$night: bottom glow must be red", Color.alpha(bottom) > 0 &&
                Color.red(bottom) > Color.green(bottom) && Color.red(bottom) > Color.blue(bottom))
            val above = 99 - kotlin.math.ceil(themed.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            assertTrue("size=$size night=$night: bottom glow should not have a sharp rim",
                kotlin.math.abs(Color.alpha(bitmap.getPixel(150, 99)) - Color.alpha(bitmap.getPixel(150, above))) <= 20)
        }
    }

    @Test fun glassProgressHasGlowAndHighlightOnlyForKnownPositiveQuota() {
        val themed = themed(false)
        val color = themed.getColor(R.color.widget_glass_primary)
        val progress = UsageWidget.quotaProgress(themed, 50.0, color, WidgetStyle.GLASS, WidgetSize.WIDE)
        assertTrue("The satin highlight should have a restrained haze beside the bar",
            Color.alpha(progress.getPixel(468, 7)) in 1..24)
        assertEquals("The haze should fade before reaching the outer margin", 0,
            Color.alpha(progress.getPixel(468, 1)))
        val centerY = ((3f + 2f) * progress.height / 12f).toInt()
        assertEquals("Most of the fill should retain its quota color", color,
            progress.getPixel(350, centerY))
        val tip = progress.getPixel(498, centerY)
        assertTrue("The progress tip should be nearly white",
            Color.red(tip) >= 230 && Color.green(tip) >= 230 && Color.blue(tip) >= 230)
        val fade = listOf(350, 410, 470).map { ColorUtils.calculateLuminance(progress.getPixel(it, centerY)) }
        assertTrue("The highlight should brighten gradually over a longer segment", fade[0] < fade[1] && fade[1] < fade[2])
        for (value in listOf(9.0, 50.0, 86.0)) {
            val bitmap = UsageWidget.quotaProgress(themed, value, color, WidgetStyle.GLASS, WidgetSize.WIDE)
            val end = (value * 10).toInt()
            for (y in listOf(0, 1, 2, 26, 27, 31)) for (x in end until bitmap.width) {
                assertEquals("glow exceeds $value% at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
            assertEquals(themed.getColor(R.color.widget_glass_track), bitmap.getPixel(end + 10, centerY))
        }
        for (value in listOf(null, 0.0)) {
            val empty = UsageWidget.quotaProgress(themed, value, color, WidgetStyle.GLASS, WidgetSize.WIDE)
            assertEquals(0, Color.alpha(empty.getPixel(500, 0)))
        }
        val dark = themed(true)
        val ring = UsageWidget.quotaRing(dark, 50.0, false, WidgetStyle.GLASS)
        assertTrue("The dark ring endpoint should have a soft glow", Color.alpha(ring.getPixel(150, 218)) > 0)
        val ringTip = ring.getPixel(130, 240)
        assertTrue("The dark ring tip should be nearly white",
            Color.red(ringTip) >= 230 && Color.green(ringTip) >= 230 && Color.blue(ringTip) >= 230)
        val lowRing = UsageWidget.quotaRing(dark, 9.0, false, WidgetStyle.GLASS)
        val ringStart = lowRing.getPixel(125, 14)
        assertTrue("The dark ring should retain its quota color at the start",
            Color.alpha(ringStart) >= 240 && Color.green(ringStart) < 200 && Color.blue(ringStart) < 200)
    }

    @Test fun glassProgressGlowKeepsBarAndCountdownPositions() {
        val themed = themed(false)
        val density = themed.resources.displayMetrics.density
        for (size in listOf(WidgetSize.WIDE, WidgetSize.LARGE)) for (singleWeekly in listOf(false, true)) {
            val values: List<UsageWindow?> = if (singleWeekly) weeklyOnly else windows
            fun render(style: WidgetStyle, widthDp: Int, heightDp: Int): View {
                val rings = values.mapIndexed { index, window ->
                    UsageWidget.quotaRing(themed, window?.remaining, index == 1, style)
                }
                val view = UsageWidget.createViews(themed, size, values, rings, now, "", "", true, style = style)
                    .apply(themed, FrameLayout(themed))
                view.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((heightDp * density).toInt(), View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                return view
            }
            fun yInRoot(view: View): Int {
                var y = view.top
                var parent = view.parent
                while (parent is View && parent.id != R.id.widget_root) {
                    y += parent.top
                    parent = parent.parent
                }
                return y
            }
            for ((width, height) in if (size == WidgetSize.WIDE) listOf(240 to 64, 320 to 80)
                else listOf(240 to 140, 320 to 160)) {
                val standard = render(WidgetStyle.STANDARD, width, height)
                val glass = render(WidgetStyle.GLASS, width, height)
                val id = if (singleWeekly) R.id.widget_weekly_progress else R.id.widget_primary_progress
                val standardBar = standard.findViewById<View>(id)
                val glassBar = glass.findViewById<View>(id)
                val standardParams = standardBar.layoutParams as ViewGroup.MarginLayoutParams
                val glassParams = glassBar.layoutParams as ViewGroup.MarginLayoutParams
                assertEquals(standardBar.height + standardParams.topMargin + standardParams.bottomMargin,
                    glassBar.height + glassParams.topMargin + glassParams.bottomMargin)
                val topInset = if (singleWeekly && size == WidgetSize.LARGE) 0 else (3 * density).toInt()
                assertTrue("bar shifted: $size singleWeekly=$singleWeekly ${width}x$height",
                    kotlin.math.abs(yInRoot(standardBar) - (yInRoot(glassBar) + topInset)) <= 1)
                val countdown = if (singleWeekly) R.id.widget_weekly_countdown else R.id.widget_primary_countdown
                assertEquals("countdown shifted: $size singleWeekly=$singleWeekly ${width}x$height",
                    yInRoot(standard.findViewById(countdown)), yInRoot(glass.findViewById(countdown)))
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
                        // Brighter light-theme accents trade some contrast over black
                        // wallpaper for recognizable green, orange, and red colors.
                        val accent = textColor != R.color.widget_glass_text && textColor != R.color.widget_glass_muted
                        val minimumContrast = when {
                            !night && wallpaper == Color.BLACK && accent -> 2.0
                            night && textColor == R.color.widget_glass_critical -> 3.0
                            else -> 4.5
                        }
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
