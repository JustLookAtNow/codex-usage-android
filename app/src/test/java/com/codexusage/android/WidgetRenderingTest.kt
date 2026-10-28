package com.codexusage.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Build
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.RemoteViews
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetRenderingTest {
    private lateinit var context: Context
    private val now = 1800000000L
    private val windows = listOf(
        UsageWindow("5h", 51.0, now + 10500, 18000, false),
        UsageWindow("Weekly", 90.0, now + 6 * 86400 + 8 * 3600, 604800, false)
    )

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        UsageRepository(context).clear()
        WidgetRefreshState.clear(context)
        WidgetCountdown.cancel(context)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun allSizesInflateThroughRemoteViewsInBothThemes() {
        for (night in listOf(false, true)) {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            val themed = context.createConfigurationContext(config)
            for (size in WidgetSize.entries) {
                val rings = windows.mapIndexed { index, window -> UsageWidget.quotaRing(themed, window.remaining, index == 1) }
                val remote = UsageWidget.createViews(themed, size, windows, rings, now, "", "", true)
                val view = remote.apply(themed, FrameLayout(themed))
                val width = if (size == WidgetSize.COMPACT) 180 else 360
                val height = if (size == WidgetSize.LARGE) 168 else 80
                val density = themed.resources.displayMetrics.density
                view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                assertEquals("51%", view.findViewById<TextView>(R.id.widget_primary_value).text.toString())
                assertEquals("90%", view.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
                assertEquals("2小时55分", view.findViewById<TextView>(R.id.widget_primary_countdown).text.toString())
                assertEquals("6天8小时", view.findViewById<TextView>(R.id.widget_weekly_countdown).text.toString())
                assertEquals(View.GONE, view.findViewById<TextView>(R.id.widget_status).visibility)
                if (size == WidgetSize.COMPACT) {
                    assertNull(view.findViewById<ImageView>(R.id.widget_primary_progress))
                    assertNull(view.findViewById<ImageView>(R.id.widget_primary_ring))
                } else {
                    val progress = view.findViewById<ImageView>(R.id.widget_primary_progress)
                    val ring = view.findViewById<ImageView>(R.id.widget_primary_ring)
                    val pixels = (progress.drawable as BitmapDrawable).bitmap
                    assertEquals(themed.getColor(R.color.widget_primary), pixels.getPixel(509, 10))
                    assertEquals(themed.getColor(R.color.widget_track), pixels.getPixel(510, 10))
                    assertEquals(if (night) View.GONE else View.VISIBLE, progress.visibility)
                    assertEquals(if (night) View.VISIBLE else View.GONE, ring.visibility)
                    assertEquals("5h 可用 51%", ring.contentDescription.toString())
                }
                for (id in listOf(R.id.widget_primary_value, R.id.widget_weekly_value,
                    R.id.widget_primary_countdown, R.id.widget_weekly_countdown)) {
                    val text = view.findViewById<TextView>(id)
                    if (night && size != WidgetSize.COMPACT && id in listOf(R.id.widget_primary_value, R.id.widget_weekly_value)) {
                        // Night mode renders the percentage into the same image as the ring.
                        assertEquals(View.GONE, text.visibility)
                        continue
                    }
                    assertTrue("$size night=$night text has no width", text.width > 0)
                    assertTrue("$size night=$night text has no height", text.height > 0)
                    assertTrue("$size night=$night text is clipped horizontally",
                        text.paint.measureText(text.text.toString()) <= text.width - text.paddingLeft - text.paddingRight + 1)
                    assertTrue("$size night=$night text=${text.text} layout=${text.layout.height} height=${text.height} size=${text.textSize} is clipped vertically",
                        text.layout.height <= text.height - text.paddingTop - text.paddingBottom + 1)
                    assertFalse(text.text.contains("剩余"))
                    assertFalse(text.text.contains("后恢复"))
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "night")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun percentagePixelsStayInsideRingWhenScaledIncludingFullAndUnknownQuota() {
        for (value in listOf(null, 0.0, 1.0, 23.0, 86.0, 100.0)) {
            for (weekly in listOf(false, true)) {
                val source = UsageWidget.quotaRing(context, value, weekly)
                // Short widgets can reduce the circle to just 20px, even in a wide column.
                for (side in listOf(20, 32, 64, 128, 256)) {
                    val scaled = Bitmap.createScaledBitmap(source, side, side, true)
                    val center = side / 2.0
                    val innerRadius = side * (104.0 / 256)
                    var textPixels = 0
                    for (y in 0 until side) for (x in 0 until side) {
                        val pixel = scaled.getPixel(x, y)
                        val textColor = quotaColor(context, value, context.getColor(R.color.widget_text))
                        // Warning text and its arc share a color; sample the central label
                        // box for those values while retaining the full white-pixel check.
                        val warning = value != null && value <= 40
                        val inLabel = x >= side * 0.22 && x < side * 0.78 && y >= side * 0.31 && y < side * 0.69
                        val matches = kotlin.math.abs(Color.red(pixel) - Color.red(textColor)) < 25 &&
                            kotlin.math.abs(Color.green(pixel) - Color.green(textColor)) < 25 &&
                            kotlin.math.abs(Color.blue(pixel) - Color.blue(textColor)) < 25
                        if (Color.alpha(pixel) >= 128 && matches && (!warning || inLabel)) {
                            textPixels++
                            val dx = x + 0.5 - center
                            val dy = y + 0.5 - center
                            assertTrue("$value%, weekly=$weekly, side=$side: percentage touches ring",
                                dx * dx + dy * dy < innerRadius * innerRadius)
                        }
                    }
                    assertTrue("$value%, side=$side: percentage is missing", textPixels > 0)
                }
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun refreshKeepsContentVisibleInEverySizeAndTheme() {
        for (night in listOf(false, true)) {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            val themed = context.createConfigurationContext(config)
            val rings = windows.mapIndexed { index, window -> UsageWidget.quotaRing(themed, window.remaining, index == 1) }
            assertTrue(rings[0].sameAs(UsageWidget.quotaRing(themed, 51.0, false)))
            for (size in WidgetSize.entries) {
                val remote = UsageWidget.createViews(themed, size, windows, rings, now, "", "正在刷新额度", true, true)
                val view = remote.apply(themed, FrameLayout(themed))
                assertEquals("", view.findViewById<TextView>(R.id.widget_status).text.toString())
                assertEquals(View.GONE, view.findViewById<TextView>(R.id.widget_status).visibility)
                assertEquals("51%", view.findViewById<TextView>(R.id.widget_primary_value).text.toString())
                val loading = view.findViewById<ImageView>(R.id.widget_loading_overlay)
                assertEquals(View.GONE, loading.visibility)
                assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_content).visibility)
                assertNotNull(view.findViewById<View>(R.id.widget_refresh_spinner))
                assertEquals("2小时55分", view.findViewById<TextView>(R.id.widget_primary_countdown).text.toString())
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun quotaColorsSurviveRemoteViewsParcelRefreshAndRecoveryInEverySizeAndTheme() {
        for (night in listOf(false, true)) {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            val themed = context.createConfigurationContext(config)
            for (size in WidgetSize.entries) {
                var view: View? = null
                for ((primary, weekly) in listOf(15.0 to 40.0, 15.0001 to 40.0001, 0.0 to null, 100.0 to 90.0)) {
                    val values = listOf(windows[0].copy(remaining = primary), windows[1].copy(remaining = weekly))
                    for (refreshing in listOf(true, false)) {
                        val rings = values.mapIndexed { index, window -> UsageWidget.quotaRing(themed, window.remaining, index == 1) }
                        val remote = UsageWidget.createViews(themed, size, values, rings, now, "", "", true, refreshing)
                        val parcel = Parcel.obtain()
                        val received = try {
                            remote.writeToParcel(parcel, 0)
                            parcel.setDataPosition(0)
                            RemoteViews.CREATOR.createFromParcel(parcel)
                        } finally { parcel.recycle() }
                        if (view == null) view = received.apply(themed, FrameLayout(themed)) else received.reapply(themed, view)
                        for (index in 0..1) {
                            val value = values[index].remaining
                            val normal = themed.getColor(if (size == WidgetSize.COMPACT) R.color.widget_text
                                else if (index == 0) R.color.widget_primary else R.color.widget_weekly)
                            val color = quotaColor(themed, value, normal)
                            val text = view!!.findViewById<TextView>(if (index == 0) R.id.widget_primary_value else R.id.widget_weekly_value)
                            assertEquals(color, text.currentTextColor)
                            assertEquals(percentage(value), text.text.toString())
                            val loading = view.findViewById<ImageView>(R.id.widget_loading_overlay)
                            assertEquals(View.GONE, loading.visibility)
                            assertEquals(View.VISIBLE,
                                view.findViewById<View>(R.id.widget_content).visibility)
                            if (size != WidgetSize.COMPACT) {
                                val progress = view.findViewById<ImageView>(if (index == 0) R.id.widget_primary_progress else R.id.widget_weekly_progress)
                                val pixels = (progress.drawable as BitmapDrawable).bitmap
                                val track = themed.getColor(R.color.widget_track)
                                assertEquals(if ((value ?: 0.0) >= 10.1) color else track, pixels.getPixel(100, 10))
                                assertEquals(if (value == 100.0) color else track, pixels.getPixel(980, 10))
                                val ring = view.findViewById<ImageView>(if (index == 0) R.id.widget_primary_ring else R.id.widget_weekly_ring)
                                val ringPixels = (ring.drawable as BitmapDrawable).bitmap
                                val labelColor = quotaColor(themed, value, themed.getColor(R.color.widget_text))
                                assertTrue("Ring label must use the current color", hasColor(ringPixels, labelColor, 56, 78, 200, 178))
                                if (!refreshing) assertEquals(if ((value ?: 0.0) > 0) color else track, ringPixels.getPixel(128, 14))
                            }
                        }
                        if (primary == 15.0 && weekly == 40.0) {
                            val density = themed.resources.displayMetrics.density
                            val width = ((if (size == WidgetSize.COMPACT) 140 else 360) * density).toInt()
                            val height = ((if (size == WidgetSize.LARGE) 168 else if (size == WidgetSize.COMPACT) 72 else 80) * density).toInt()
                            view!!.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                            view.layout(0, 0, width, height)
                            val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            view.draw(Canvas(rendered))
                            if (!refreshing) for (resource in listOf(R.color.quota_warning, R.color.quota_critical)) {
                                assertTrue("$size night=$night: warning color missing from rendered widget",
                                    hasColor(rendered, themed.getColor(resource), 0, 0, width, height))
                            }
                            val output = File("build/reports/widget-colors").apply { mkdirs() }
                            File(output, "${Build.VERSION.SDK_INT}-$size-night-$night-refreshing-$refreshing.png")
                                .outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                    }
                }
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun lowQuotaChangesFromYellowToRedAcrossWidgetStylesAndLayouts() {
        for (night in listOf(false, true)) {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            val themed = context.createConfigurationContext(config)
            for (style in WidgetStyle.entries) for (size in WidgetSize.entries) for (weeklyOnly in listOf(false, true)) {
                var view: View? = null
                val transitions = listOf(
                    (25.0 to false) to (25.0 to false),
                    (0.0 to true) to (25.0 to false),
                    (25.0 to false) to (1.0 to true),
                    (2.0 to true) to (9.0 to true),
                    (10.0 to true) to (10.0 to true),
                    (25.0 to false) to (25.0 to false)
                )
                for ((primary, weekly) in transitions) {
                    val usage = Usage(null, if (weeklyOnly) listOf(windows[1].copy(remaining = weekly.first))
                        else listOf(windows[0].copy(remaining = primary.first), windows[1].copy(remaining = weekly.first)))
                    val values = widgetWindows(UsageParser.parse(UsageParser.displayJson(usage)))
                    val rings = values.mapIndexed { index, window ->
                        UsageWidget.quotaRing(themed, window?.remaining, index == 1, style)
                    }
                    val sent = UsageWidget.createViews(themed, size, values, rings, now, "", "", true,
                        style = style)
                    val parcel = Parcel.obtain()
                    val received = try {
                        sent.writeToParcel(parcel, 0)
                        parcel.setDataPosition(0)
                        RemoteViews.CREATOR.createFromParcel(parcel)
                    } finally { parcel.recycle() }
                    if (view == null) view = received.apply(themed, FrameLayout(themed)) else received.reapply(themed, view)
                    for (index in if (weeklyOnly) 1..1 else 0..1) {
                        val (value, critical) = if (index == 0) primary else weekly
                        val expected = themed.getColor(when (style) {
                            WidgetStyle.STANDARD -> if (critical) R.color.quota_critical else R.color.quota_warning
                            WidgetStyle.GLASS -> if (critical) R.color.widget_glass_critical else R.color.widget_glass_warning
                        })
                        val valueId = if (index == 0) R.id.widget_primary_value else R.id.widget_weekly_value
                        val text = view!!.findViewById<TextView>(valueId)
                        val label = "style=$style size=$size night=$night weeklyOnly=$weeklyOnly value=$value index=$index"
                        assertEquals(label, "${value.toInt()}%", text.text.toString())
                        assertEquals(label, expected, text.currentTextColor)
                        if (night && size != WidgetSize.COMPACT) {
                            val ringId = if (index == 0) R.id.widget_primary_ring else R.id.widget_weekly_ring
                            val bitmap = (view.findViewById<ImageView>(ringId).drawable as BitmapDrawable).bitmap
                            assertTrue("$label: ring label has the wrong color", hasColor(bitmap, expected, 56, 78, 200, 178))
                        }
                    }
                }
            }
        }
    }

    private fun hasColor(bitmap: Bitmap, color: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) >= 32 && kotlin.math.abs(Color.red(pixel) - Color.red(color)) < 12 &&
                kotlin.math.abs(Color.green(pixel) - Color.green(color)) < 12 &&
                kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) < 12) return true
        }
        return false
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactWatermarkLayoutFitsFullQuotaAndCountdownAtMinimumAndNormalSizes() {
        for (night in listOf(false, true)) {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            val themed = context.createConfigurationContext(config)
            val values = windows.map { it.copy(remaining = 100.0) }
            val rings = values.mapIndexed { index, value -> UsageWidget.quotaRing(context, value.remaining, index == 1) }
            for ((width, height) in listOf(140 to 72, 180 to 80)) {
                val view = UsageWidget.createViews(themed, WidgetSize.COMPACT, values, rings, now, "", "", true)
                    .apply(themed, FrameLayout(themed)) as ViewGroup
                val density = themed.resources.displayMetrics.density
                view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                val watermark = view.findViewById<ImageView>(R.id.widget_watermark)
                assertTrue(watermark.width > 0 && watermark.height > 0)
                // Check rendered pixels, not ImageView tint metadata: the launcher can
                // discard the tint even when the configured ColorStateList is correct.
                assertNull(watermark.imageTintList)
                val watermarkPixels = Bitmap.createBitmap(watermark.width, watermark.height, Bitmap.Config.ARGB_8888)
                watermark.draw(Canvas(watermarkPixels))
                var maximumAlpha = 0
                for (y in 0 until watermarkPixels.height) for (x in 0 until watermarkPixels.width) {
                    maximumAlpha = maxOf(maximumAlpha, Color.alpha(watermarkPixels.getPixel(x, y)))
                }
                assertEquals("Watermark must remain translucent after drawing", if (night) 18 else 15, maximumAlpha)
                assertNull(view.findViewById<ImageView>(R.id.widget_primary_progress))
                assertNull(view.findViewById<ImageView>(R.id.widget_primary_ring))
                fun bounds(id: Int): Rect {
                    val target = view.findViewById<View>(id)
                    return Rect().also { target.getDrawingRect(it); view.offsetDescendantRectToMyCoords(target, it) }
                }
                val watermarkBounds = bounds(R.id.widget_watermark)
                assertEquals("Watermark must be centered in the left half", view.width / 4f, watermarkBounds.exactCenterX(), 1f)
                assertEquals("Watermark must be vertically centered", view.height / 2f, watermarkBounds.exactCenterY(), 1f)
                assertTrue(watermarkBounds.left >= 0 && watermarkBounds.top >= 0)
                assertTrue(watermarkBounds.right <= view.width / 2 && watermarkBounds.bottom <= view.height)
                val firstValue = bounds(R.id.widget_primary_value)
                val secondValue = bounds(R.id.widget_weekly_value)
                val firstTime = bounds(R.id.widget_primary_countdown)
                val secondTime = bounds(R.id.widget_weekly_countdown)
                assertEquals(firstValue.left, secondValue.left)
                assertEquals(firstTime.right, secondTime.right)
                assertTrue(firstValue.right < firstTime.left)
                assertTrue(firstValue.bottom < secondValue.top)
                for (id in listOf(R.id.widget_primary_value, R.id.widget_weekly_value,
                    R.id.widget_primary_countdown, R.id.widget_weekly_countdown)) {
                    val text = view.findViewById<TextView>(id)
                    assertEquals(View.VISIBLE, text.visibility)
                    assertTrue("$width x $height night=$night ${text.text} overflows horizontally",
                        text.paint.measureText(text.text.toString()) <= text.width + 1)
                    assertTrue("$width x $height night=$night ${text.text} overflows vertically",
                        text.layout.height <= text.height + 1)
                }
                fun labels(parent: View): List<String> = when (parent) {
                    is TextView -> listOf(parent.text.toString())
                    is ViewGroup -> (0 until parent.childCount).flatMap { labels(parent.getChildAt(it)) }
                    else -> emptyList()
                }
                assertFalse(labels(view).any { it == "CODEX" || it == "5h" || it == "Weekly" })
            }
        }
    }

    @Test fun failedManualRefreshRestoresCachedValuesAndClearsFeedback() {
        val widgets = shadowOf(AppWidgetManager.getInstance(context))
        val id = widgets.createWidget(UsageWidget::class.java, R.layout.usage_widget)
        // Unsupported fake mode fails before loading any credential or opening a connection.
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().putString("mode", "test").commit()
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit()
            .putString("body", UsageParser.displayJson(Usage(null, windows))).commit()
        WidgetRefreshState.start(context)
        UsageWidget.updateAll(context)
        assertTrue(WidgetRefreshState.active(context))
        assertFalse(widgets.getViewFor(id).findViewById<TextView>(R.id.widget_status).text.contains("刷新中"))
        assertEquals(View.VISIBLE, widgets.getViewFor(id).findViewById<View>(R.id.widget_content).visibility)
        assertNotNull(widgets.getViewFor(id).findViewById<View>(R.id.widget_refresh_spinner))
        val worker = TestListenableWorkerBuilder<RefreshWorker>(context)
            .setInputData(workDataOf(RefreshWorker.MANUAL_REFRESH to true)).build()
        worker.doWork()
        assertFalse(WidgetRefreshState.active(context))
        val restored = widgets.getViewFor(id)
        assertEquals("51%", restored.findViewById<TextView>(R.id.widget_primary_value).text.toString())
        assertEquals(View.GONE, restored.findViewById<ImageView>(R.id.widget_loading_overlay).visibility)
        assertTrue(restored.findViewById<TextView>(R.id.widget_status).text.contains("缓存"))
    }

    @Test fun interruptedRefreshExpiresAndClearingConfigurationRemovesFeedback() {
        WidgetRefreshState.start(context)
        assertTrue(WidgetRefreshState.active(context))
        context.getSharedPreferences("widget_refresh", Context.MODE_PRIVATE).edit()
            .putLong("until", System.currentTimeMillis() - 1).commit()
        assertFalse(WidgetRefreshState.active(context))
        WidgetRefreshState.start(context)
        WidgetRefreshState.clear(context)
        assertFalse(WidgetRefreshState.active(context))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun refreshUsesLauncherDimensionsAndRemovesFeedbackAfterExpiryAndConfigurationClear() {
        val manager = AppWidgetManager.getInstance(context)
        val widgets = shadowOf(manager)
        val id = widgets.createWidget(UsageWidget::class.java, R.layout.usage_widget)
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().putString("mode", "test").commit()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 180)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 80)
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                arrayListOf(android.util.SizeF(180f, 80f)))
        }
        manager.updateAppWidgetOptions(id, options)
        WidgetRefreshState.start(context)
        UsageWidget.updateAll(context)
        val loading = widgets.getViewFor(id).findViewById<ImageView>(R.id.widget_loading_overlay)
        assertEquals(View.GONE, loading.visibility)
        assertEquals(View.VISIBLE, widgets.getViewFor(id).findViewById<View>(R.id.widget_content).visibility)
        assertNotNull(widgets.getViewFor(id).findViewById<View>(R.id.widget_refresh_spinner))
        assertFalse(widgets.getViewFor(id).findViewById<TextView>(R.id.widget_status).text.contains("刷新中"))

        context.getSharedPreferences("widget_refresh", Context.MODE_PRIVATE).edit()
            .putLong("until", System.currentTimeMillis() - 1).commit()
        UsageWidget.updateAll(context)
        assertEquals(View.GONE, widgets.getViewFor(id).findViewById<View>(R.id.widget_loading_overlay).visibility)
        assertNull(widgets.getViewFor(id).findViewById<View>(R.id.widget_refresh_spinner))
        WidgetRefreshState.start(context)
        CredentialStore(context).clear()
        UsageWidget.updateAll(context)
        val unconfigured = widgets.getViewFor(id)
        assertEquals(View.VISIBLE, unconfigured.findViewById<View>(R.id.widget_content).visibility)
        assertEquals(View.GONE, unconfigured.findViewById<View>(R.id.widget_loading_overlay).visibility)
        assertNull(unconfigured.findViewById<View>(R.id.widget_refresh_spinner))
        assertEquals("请配置", unconfigured.findViewById<TextView>(R.id.widget_status).text.toString())
    }

    @Test fun widgetLaunchReusesHomeEvenWhenOldPendingIntentStillExists() {
        // An installed widget may still hold the previous action-less PendingIntent.
        val stale = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val app = shadowOf(RuntimeEnvironment.getApplication())
        for (size in WidgetSize.entries) for (single in listOf(false, true)) {
            val values: List<UsageWindow?> = if (single) listOf(null, windows[1]) else windows
            val rings = values.mapIndexed { index, value -> UsageWidget.quotaRing(context, value?.remaining, index == 1) }
            val view = UsageWidget.createViews(context, size, values, rings, now, "", "", true)
                .apply(context, FrameLayout(context))
            repeat(2) {
                assertTrue(view.findViewById<View>(R.id.widget_root).performClick())
                val launched = app.nextStartedActivity
                assertEquals(MainActivity::class.java.name, launched.component!!.className)
                assertEquals(Intent.ACTION_MAIN, launched.action)
                assertTrue(launched.hasCategory(Intent.CATEGORY_LAUNCHER))
                assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                assertTrue(launched.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
                assertTrue(launched.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
                assertEquals(0, launched.flags and (Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            }
        }
        // Replacing the widget's launch action must not cancel its other size variants.
        assertFalse(shadowOf(stale).isCanceled)
        assertNull(shadowOf(stale).savedIntent.action)
        val blank = listOf<UsageWindow?>(null, null)
        val rings = blank.mapIndexed { index, _ -> UsageWidget.quotaRing(context, null, index == 1) }
        val unconfigured = UsageWidget.createViews(context, WidgetSize.COMPACT, blank, rings, now, "请配置", "", false)
            .apply(context, FrameLayout(context))
        unconfigured.findViewById<View>(R.id.widget_refresh).performClick()
        val launched = app.nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, launched.action)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test fun chatGptIconOpensInstalledAppOrFallsBackAcrossWidgetLayouts() {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        val packageManager = shadowOf(context.packageManager)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage("com.openai.chatgpt")
        for (installed in listOf(false, true)) {
            if (installed) {
                val activity = ActivityInfo().apply { packageName = "com.openai.chatgpt"; name = "com.openai.chatgpt.MainActivity" }
                packageManager.addResolveInfoForIntent(launcher, ResolveInfo().apply { activityInfo = activity })
            }
            for (style in WidgetStyle.entries) for (size in WidgetSize.entries) for (single in listOf(false, true)) {
                val values: List<UsageWindow?> = if (single) listOf(null, windows[1]) else windows
                val rings = values.mapIndexed { index, value -> UsageWidget.quotaRing(context, value?.remaining, index == 1, style) }
                val view = UsageWidget.createViews(context, size, values, rings, now, "", "", true, style = style)
                    .apply(context, FrameLayout(context))
                val targets = when (size) {
                    WidgetSize.WIDE -> listOf(R.id.widget_open_chatgpt)
                    WidgetSize.LARGE -> listOf(R.id.widget_icon_gpt, R.id.widget_static_text_1)
                    WidgetSize.COMPACT -> if (single) listOf(R.id.widget_weekly_value)
                        else listOf(R.id.widget_primary_value, R.id.widget_weekly_value)
                }
                for (id in targets) {
                    val target = view.findViewById<View>(id)
                    assertTrue(target.contentDescription.toString().endsWith(if (installed) "打开 ChatGPT" else "打开 Codex 余量"))
                    assertTrue(target.performClick())
                    val launched = app.nextStartedActivity
                    if (installed) assertEquals("com.openai.chatgpt", launched.component?.packageName)
                    else assertEquals(MainActivity::class.java.name, launched.component?.className)
                }
            }
        }
    }

    @Test fun chatGptTouchAreaReachesDividerWithoutOverlappingRefresh() {
        for ((size, single) in listOf(WidgetSize.WIDE to false, WidgetSize.WIDE to true)) {
            val values: List<UsageWindow?> = if (single) listOf(null, windows[1]) else windows
            val rings = values.mapIndexed { index, value -> UsageWidget.quotaRing(context, value?.remaining, index == 1) }
            val view = UsageWidget.createViews(context, size, values, rings, now, "", "", true)
                .apply(context, FrameLayout(context)) as ViewGroup
            val density = context.resources.displayMetrics.density
            val width = ((if (size == WidgetSize.COMPACT) 180 else 360) * density).toInt()
            val height = (80 * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            fun bounds(id: Int): Rect {
                val target = view.findViewById<View>(id)
                return Rect().also { target.getDrawingRect(it); view.offsetDescendantRectToMyCoords(target, it) }
            }
            val target = bounds(R.id.widget_open_chatgpt)
            val divider = bounds(R.id.widget_divider_1)
            val refresh = bounds(R.id.widget_refresh)
            assertEquals(0, target.left)
            assertEquals(0, target.top)
            assertEquals(height, target.bottom)
            assertEquals(divider.left, target.right)
            assertTrue(target.right <= refresh.left)
        }
    }

    @Test fun missingAndExpiredDeadlinesNeverInventQuotaAndBodyOpensApp() {
        val values = listOf(windows[0].copy(remaining = null, resetAt = null), windows[1].copy(resetAt = now - 1))
        val rings = values.mapIndexed { index, window -> UsageWidget.quotaRing(context, window.remaining, index == 1) }
        val views = UsageWidget.createViews(context, WidgetSize.LARGE, values, rings, now, "", "", true)
        val view = views.apply(context, FrameLayout(context))
        assertEquals("—", view.findViewById<TextView>(R.id.widget_primary_value).text.toString())
        assertEquals("—", view.findViewById<TextView>(R.id.widget_primary_countdown).text.toString())
        assertEquals("90%", view.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
        assertEquals("0分钟", view.findViewById<TextView>(R.id.widget_weekly_countdown).text.toString())
        view.findViewById<View>(R.id.widget_root).performClick()
        assertEquals(MainActivity::class.java.name, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.component!!.className)
    }

    @Test fun countdownTickDoesNotEnqueueNetworkWorkAndCancelsWhenCleared() {
        val widgets = shadowOf(AppWidgetManager.getInstance(context))
        widgets.createWidget(UsageWidget::class.java, R.layout.usage_widget)
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().putString("mode", "test").commit()
        val deadline = System.currentTimeMillis() / 1000 + 3600
        val usage = Usage(null, windows.map { it.copy(resetAt = deadline) })
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().putString("body", UsageParser.displayJson(usage)).commit()
        // WorkManager is deliberately not initialized: a tick must only render cached data.
        UsageWidget().onReceive(context, Intent(context, UsageWidget::class.java).setAction(WidgetCountdown.ACTION_TICK))
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(AlarmManager.ELAPSED_REALTIME, alarms.scheduledAlarms.single().type)
        // Replacement uses the same PendingIntent, so repeated ticks never create multiple timers.
        UsageWidget.updateAll(context)
        assertEquals(1, alarms.scheduledAlarms.size)
        CredentialStore(context).clear()
        UsageRepository(context).clear()
        UsageWidget.updateAll(context)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun resizeRerendersWithoutQueryingAndLastWidgetRemovalStopsTicks() {
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).createWidget(UsageWidget::class.java, R.layout.usage_widget)
        UsageWidget().onAppWidgetOptionsChanged(context, manager, 1, Bundle())
        WidgetCountdown.schedule(context, true)
        assertFalse(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        UsageWidget().onDisabled(context)
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }
}
