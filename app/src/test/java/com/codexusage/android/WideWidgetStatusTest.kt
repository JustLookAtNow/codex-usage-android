package com.codexusage.android

import android.content.res.Configuration
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WideWidgetStatusTest {
    @Test fun cacheStatusAppearsBelowRefreshIconForBothLayoutsAndStyles() {
        val context = RuntimeEnvironment.getApplication()
        val now = 1800000000L
        val weekly = UsageWindow("Weekly", 90.0, now + 86400, 604800, false)
        val five = UsageWindow("5h", 51.0, now + 3600, 18000, false)
        for (night in listOf(false, true)) for (single in listOf(false, true)) {
            for (style in WidgetStyle.entries) for ((width, height) in listOf(240 to 72, 360 to 80)) {
                val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                })
                val windows = listOf(five.takeUnless { single }, weekly)
                val rings = windows.mapIndexed { index, window ->
                    UsageWidget.quotaRing(themed, window?.remaining, index == 1, style)
                }
                val view = UsageWidget.createViews(themed, WidgetSize.WIDE, windows, rings, now,
                    "缓存 · !", "旧数据", true, style = style, failed = true)
                    .apply(themed, FrameLayout(themed)) as ViewGroup
                val density = themed.resources.displayMetrics.density
                view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                val status = view.findViewById<TextView>(R.id.widget_status)
                val icon = view.findViewById<View>(R.id.widget_refresh_icon)
                fun bounds(target: View): RectF {
                    var x = 0f
                    var y = 0f
                    var current = target
                    while (current !== view) {
                        x += current.x
                        y += current.y
                        current = current.parent as View
                    }
                    return RectF(x, y, x + target.width, y + target.height)
                }
                val statusBounds = bounds(status)
                val iconBounds = bounds(icon)
                val case = "night=$night single=$single style=$style size=${width}x$height " +
                    "status=$statusBounds icon=$iconBounds"
                assertEquals("缓存 · !", status.text.toString())
                assertEquals(View.VISIBLE, status.visibility)
                assertTrue(case, statusBounds.top >= iconBounds.bottom)
                assertTrue(case, kotlin.math.abs(iconBounds.centerX() - statusBounds.centerX()) <= 1f)
                assertTrue(case, statusBounds.bottom <= view.height)
                assertTrue(case, status.paint.measureText(status.text.toString()) <= statusBounds.width())
            }
        }
    }
}
