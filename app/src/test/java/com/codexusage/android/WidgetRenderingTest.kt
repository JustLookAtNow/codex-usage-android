package com.codexusage.android

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
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
                val rings = windows.mapIndexed { index, window -> UsageWidget.quotaRing(window.remaining, index == 1) }
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
                    assertNull(view.findViewById<ProgressBar>(R.id.widget_primary_progress))
                    assertNull(view.findViewById<ImageView>(R.id.widget_primary_ring))
                } else {
                    val progress = view.findViewById<ProgressBar>(R.id.widget_primary_progress)
                    val ring = view.findViewById<ImageView>(R.id.widget_primary_ring)
                    assertEquals(510, progress.progress)
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
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun percentagePixelsStayInsideRingWhenScaledIncludingFullAndUnknownQuota() {
        for (value in listOf(null, 0.0, 1.0, 23.0, 86.0, 100.0)) {
            for (weekly in listOf(false, true)) {
                val source = UsageWidget.quotaRing(value, weekly)
                // Short widgets can reduce the circle to just 20px, even in a wide column.
                for (side in listOf(20, 32, 64, 128, 256)) {
                    val scaled = Bitmap.createScaledBitmap(source, side, side, true)
                    val center = side / 2.0
                    val innerRadius = side * (104.0 / 256)
                    var textPixels = 0
                    for (y in 0 until side) for (x in 0 until side) {
                        val pixel = scaled.getPixel(x, y)
                        if (Color.alpha(pixel) >= 128 && Color.red(pixel) >= 200 &&
                            Color.green(pixel) >= 200 && Color.blue(pixel) >= 200) {
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
    fun refreshImmediatelyShowsBlurredQuotaAndStatusInEverySizeAndTheme() {
        for (night in listOf(false, true)) {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            val themed = context.createConfigurationContext(config)
            val rings = windows.mapIndexed { index, window -> UsageWidget.quotaRing(window.remaining, index == 1, true) }
            assertFalse(rings[0].sameAs(UsageWidget.quotaRing(51.0, false)))
            for (size in WidgetSize.entries) {
                val remote = UsageWidget.createViews(themed, size, windows, rings, now, "刷新中", "正在刷新额度", true, true)
                val view = remote.apply(themed, FrameLayout(themed))
                assertEquals("刷新中", view.findViewById<TextView>(R.id.widget_status).text.toString())
                assertEquals(View.VISIBLE, view.findViewById<TextView>(R.id.widget_status).visibility)
                assertEquals("", view.findViewById<TextView>(R.id.widget_primary_value).text.toString())
                val loading = view.findViewById<TextView>(R.id.widget_primary_loading_value)
                assertEquals("51%", loading.text.toString())
                assertEquals(View.VISIBLE, loading.visibility)
                assertEquals(Color.TRANSPARENT, loading.currentTextColor)
                if (!night || size == WidgetSize.COMPACT) assertTrue(loading.shadowRadius > 0)
                assertEquals("2小时55分", view.findViewById<TextView>(R.id.widget_primary_countdown).text.toString())
            }
        }
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
            val rings = values.mapIndexed { index, value -> UsageWidget.quotaRing(value.remaining, index == 1) }
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
                assertNull(view.findViewById<ProgressBar>(R.id.widget_primary_progress))
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
        assertEquals("刷新中", widgets.getViewFor(id).findViewById<TextView>(R.id.widget_status).text.toString())
        val worker = TestListenableWorkerBuilder<RefreshWorker>(context)
            .setInputData(workDataOf(RefreshWorker.MANUAL_REFRESH to true)).build()
        worker.doWork()
        assertFalse(WidgetRefreshState.active(context))
        val restored = widgets.getViewFor(id)
        assertEquals("51%", restored.findViewById<TextView>(R.id.widget_primary_value).text.toString())
        assertEquals(View.GONE, restored.findViewById<TextView>(R.id.widget_primary_loading_value).visibility)
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

    @Test fun missingAndExpiredDeadlinesNeverInventQuotaAndBodyOpensApp() {
        val values = listOf(windows[0].copy(remaining = null, resetAt = null), windows[1].copy(resetAt = now - 1))
        val rings = values.mapIndexed { index, window -> UsageWidget.quotaRing(window.remaining, index == 1) }
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
