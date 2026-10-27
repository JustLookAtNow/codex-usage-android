package com.codexusage.android

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeeklyWidgetTest {
    private lateinit var context: Context
    private val now = java.time.Instant.parse("2026-10-25T02:29:00Z").epochSecond
    private val updatedAt = (now - 300) * 1000
    private val weekly = UsageWindow("Weekly", 86.0, now + 6 * 86400 + 6 * 3600, 604800, false)
    private val five = UsageWindow("5h", 23.0, now + 3300, 18000, false)

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        UsageRepository(context).clear()
        WidgetRefreshState.clear(context)
        WidgetCountdown.cancel(context)
    }

    private fun themed(night: Boolean): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        })

    private fun remote(themed: Context, size: WidgetSize, window: UsageWindow = weekly,
                       refreshing: Boolean = false, status: String = "", nowSeconds: Long = now): RemoteViews {
        val windows = listOf(null, window)
        val rings = windows.mapIndexed { index, value -> UsageWidget.quotaRing(themed, value?.remaining, index == 1) }
        return UsageWidget.createViews(themed, size, windows, rings, nowSeconds, status, "", true, refreshing, updatedAt)
    }

    private fun measure(view: View, themed: Context, width: Int, height: Int) {
        val density = themed.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun visibleText(view: View): List<TextView> = if (view.visibility != View.VISIBLE) emptyList() else when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { visibleText(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun weeklyContentFitsMinimumAndNormalDimensionsInBothThemes() {
        for (night in listOf(false, true)) for (size in WidgetSize.entries) {
            val themed = themed(night)
            val dimensions = when (size) {
                WidgetSize.COMPACT -> listOf(140 to 72, 180 to 80)
                WidgetSize.WIDE -> listOf(240 to 72, 360 to 80)
                WidgetSize.LARGE -> listOf(240 to 140, 360 to 168)
            }
            for ((width, height) in dimensions) for (value in listOf(null, 0.0, 15.0, 40.0, 86.0, 100.0)) {
                val view = remote(themed, size, weekly.copy(remaining = value)).apply(themed, FrameLayout(themed))
                measure(view, themed, width, height)
                assertNull(view.findViewById<View>(R.id.widget_primary_value))
                assertFalse(view.contentDescription.toString().contains("5h"))
                val texts = visibleText(view)
                assertTrue(texts.any { it.text.toString() == "CODEX" })
                assertTrue(texts.any { it.text.toString() == "本周剩余" })
                val timestamp = view.findViewById<TextView>(R.id.widget_updated_at)
                if (size == WidgetSize.COMPACT) {
                    assertNull(timestamp)
                    assertFalse(texts.any { it.text.contains("更新于") })
                    assertNull(view.findViewById<View>(R.id.widget_weekly_ring))
                } else {
                    assertEquals("更新于 ${localTime(updatedAt / 1000)}", timestamp.text.toString())
                    assertEquals(if (night) View.VISIBLE else View.GONE, view.findViewById<View>(R.id.widget_weekly_ring).visibility)
                    assertEquals(if (night) View.GONE else View.VISIBLE, view.findViewById<View>(R.id.widget_weekly_progress).visibility)
                }
                for (text in texts) {
                    val label = "$size $width x $height night=$night text=${text.text}"
                    assertTrue("$label has no space", text.width > 0 && text.height > 0)
                    assertTrue("$label clipped horizontally", text.paint.measureText(text.text.toString()) <= text.width - text.paddingLeft - text.paddingRight + 1)
                    assertTrue("$label clipped vertically", text.layout.height <= text.height - text.paddingTop - text.paddingBottom + 1)
                }
                val refresh = view.findViewById<View>(R.id.widget_refresh)
                assertTrue(refresh.width >= 48 * themed.resources.displayMetrics.density)
                assertTrue(refresh.height >= 48 * themed.resources.displayMetrics.density)
            }
        }
    }

    @Test fun refreshParcelAndReapplyKeepColorsAndSuccessfulTimestamp() {
        for (night in listOf(false, true)) for (size in WidgetSize.entries) {
            val themed = themed(night)
            var view: View? = null
            for (value in listOf(15.0, 40.0, 86.0, null)) for (refreshing in listOf(true, false)) {
                val sent = remote(themed, size, weekly.copy(remaining = value), refreshing, "缓存 · !")
                val parcel = Parcel.obtain()
                val received = try {
                    sent.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    RemoteViews.CREATOR.createFromParcel(parcel)
                } finally { parcel.recycle() }
                if (view == null) view = received.apply(themed, FrameLayout(themed)) else received.reapply(themed, view)
                val text = view!!.findViewById<TextView>(R.id.widget_weekly_value)
                assertEquals(percentage(value), text.text.toString())
                assertEquals(quotaColor(themed, value, themed.getColor(R.color.widget_weekly)), text.currentTextColor)
                val loader = view.findViewById<ImageView>(R.id.widget_loading_overlay)
                assertEquals(View.GONE, loader.visibility)
                assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_content).visibility)
                assertEquals("缓存 · !", view.findViewById<TextView>(R.id.widget_status).text.toString())
                if (size != WidgetSize.COMPACT) assertEquals("更新于 ${localTime(updatedAt / 1000)}", view.findViewById<TextView>(R.id.widget_updated_at).text.toString())
            }
            val nextTick = remote(themed, size, nowSeconds = now + 60).apply(themed, FrameLayout(themed))
            if (size != WidgetSize.COMPACT) assertEquals("更新于 ${localTime(updatedAt / 1000)}", nextTick.findViewById<TextView>(R.id.widget_updated_at).text.toString())
        }
    }

    @Test fun missingAndExpiredRecoveryTimesDoNotInventQuota() {
        for (size in WidgetSize.entries) {
            val missing = remote(context, size, weekly.copy(remaining = null, resetAt = null)).apply(context, FrameLayout(context))
            assertEquals("—", missing.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
            assertEquals(if (size == WidgetSize.LARGE) "—" else "恢复时间未提供", missing.findViewById<TextView>(R.id.widget_weekly_countdown).text.toString())
            val expired = remote(context, size, weekly.copy(remaining = 15.0, resetAt = now - 1)).apply(context, FrameLayout(context))
            assertEquals("15%", expired.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
            assertEquals(if (size == WidgetSize.LARGE) "0分钟" else "0分钟后恢复", expired.findViewById<TextView>(R.id.widget_weekly_countdown).text.toString())
        }
    }

    @Test fun existingWidgetSwitchesBothWaysAcrossSizesAndKeepsCachedModeAfterFailure() {
        val manager = AppWidgetManager.getInstance(context)
        val widgets = shadowOf(manager)
        val id = widgets.createWidget(UsageWidget::class.java, R.layout.usage_widget)
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().putString("mode", "test").commit()
        for (size in WidgetSize.entries) {
            manager.updateAppWidgetOptions(id, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size.width.toInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, size.height.toInt())
            })
            for (single in listOf(false, true, false, true)) {
                context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit()
                    .putString("body", UsageParser.displayJson(Usage(null, if (single) listOf(weekly) else listOf(five, weekly))))
                    .putLong("updated_at", updatedAt).remove("error").commit()
                UsageWidget.updateAll(context)
                var view = widgets.getViewFor(id)
                assertEquals(single, view.findViewById<View>(R.id.widget_primary_value) == null)
                assertEquals("86%", view.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
                if (single) {
                    context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().putString("error", "测试网络失败").commit()
                    UsageWidget.updateAll(context)
                    view = widgets.getViewFor(id)
                    assertNull(view.findViewById<View>(R.id.widget_primary_value))
                    assertTrue(view.findViewById<TextView>(R.id.widget_status).text.contains("缓存"))
                    assertEquals(updatedAt, UsageRepository(context).updatedAt)
                }
            }
        }
        CredentialStore(context).clear()
        UsageRepository(context).clear()
        UsageWidget.updateAll(context)
        assertEquals("—", widgets.getViewFor(id).findViewById<TextView>(R.id.widget_primary_value).text.toString())
    }

    @Test fun exportNativeWeeklyPreviews() {
        val output = File("build/reports/widget-weekly").apply { mkdirs() }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
        val board = Bitmap.createBitmap(1600, 1240, Bitmap.Config.ARGB_8888)
        val boardCanvas = Canvas(board).apply { scale(2f, 2f) }
        for (night in listOf(false, true)) {
            val themed = themed(night)
            val left = if (night) 400f else 0f
            paint.color = Color.parseColor(if (night) "#283237" else "#E7EEEB")
            boardCanvas.drawRect(left, 0f, left + 400f, 620f, paint)
            paint.color = themed.getColor(R.color.widget_text)
            paint.textSize = 16f
            boardCanvas.drawText(if (night) "DARK" else "LIGHT", left + 20, 40f, paint)
            for (size in WidgetSize.entries) {
                val width = if (size == WidgetSize.COMPACT) 180 else 360
                val height = if (size == WidgetSize.LARGE) 168 else 80
                val view = remote(themed, size).apply(themed, FrameLayout(themed))
                measure(view, themed, width, height)
                val y = when (size) { WidgetSize.COMPACT -> 94f; WidgetSize.WIDE -> 246f; WidgetSize.LARGE -> 398f }
                val x = left + (400 - width) / 2f
                paint.textSize = 12f
                boardCanvas.drawText(when (size) { WidgetSize.COMPACT -> "2 × 1"; WidgetSize.WIDE -> "4 × 1"; WidgetSize.LARGE -> "4 × 2" }, left + 20, y - 16, paint)
                boardCanvas.save()
                boardCanvas.translate(x, y)
                val density = themed.resources.displayMetrics.density
                boardCanvas.scale(1f / density, 1f / density)
                view.draw(boardCanvas)
                boardCanvas.restore()
                val shot = Bitmap.createBitmap((width + 40) * 2, (height + 60) * 2, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(shot).apply { scale(2f, 2f) }
                canvas.drawColor(Color.parseColor(if (night) "#283237" else "#E7EEEB"))
                paint.textSize = 12f
                canvas.drawText(if (night) "DARK" else "LIGHT", 20f, 24f, paint)
                canvas.translate(20f, 40f)
                canvas.scale(1f / density, 1f / density)
                view.draw(canvas)
                File(output, "$size-night-$night.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        File(output, "weekly-only-widgets.png").outputStream().use { board.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
