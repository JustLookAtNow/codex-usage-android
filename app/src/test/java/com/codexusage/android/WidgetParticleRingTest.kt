package com.codexusage.android

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Parcel
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetParticleRingTest {
    private val now = 1800000000L

    private fun remote(context: Context, style: WidgetStyle, size: WidgetSize, single: Boolean,
        value: Double?, weekly: Double? = value): RemoteViews {
        val windows = listOf(
            UsageWindow("5h", value, now + 3600, 18000, false).takeUnless { single },
            UsageWindow("Weekly", weekly, now + 86400, 604800, false))
        val rings = windows.mapIndexed { index, window -> UsageWidget.quotaRing(context, window?.remaining, index == 1, style) }
        val remote = UsageWidget.createViews(context, size, windows, rings, now, "刚刚", "", true, style = style)
        val parcel = Parcel.obtain()
        return try {
            remote.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private fun assertHue(expected: Int, actual: Int) {
        val expectedHsl = FloatArray(3).also { ColorUtils.colorToHSL(expected, it) }
        val actualHsl = FloatArray(3).also { ColorUtils.colorToHSL(actual, it) }
        assertTrue("Wrong quota hue: expected=$expected actual=$actual",
            kotlin.math.abs(expectedHsl[0] - actualHsl[0]) < 5f && actualHsl[1] > 0.15f)
    }

    @Test fun actualFullQuotaIsGoldAndReturnsToGreenBelow100InEveryWidgetLayout() {
        val context = RuntimeEnvironment.getApplication()
        for (night in listOf(false, true)) {
            val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            })
            for (style in WidgetStyle.entries) for (size in WidgetSize.entries) for (single in listOf(false, true)) {
                var view: View? = null
                for (value in listOf(99.49, 100.0, 99.99, 100.0)) {
                    val remote = remote(themed, style, size, single, value)
                    if (view == null) view = remote.apply(themed, FrameLayout(themed)) else remote.reapply(themed, view)
                    for (index in if (single) 1..1 else 0..1) {
                        val text = view!!.findViewById<TextView>(if (index == 0) R.id.widget_primary_value else R.id.widget_weekly_value)
                        val full = themed.getColor(R.color.widget_quota_full)
                        if (value == 100.0) assertEquals(full, text.currentTextColor)
                        else assertNotEquals("Rounding must not trigger the full quota color", full, text.currentTextColor)
                        if (size == WidgetSize.COMPACT) continue
                        val expected = themed.getColor(if (value == 100.0) R.color.widget_progress_gold else R.color.widget_progress_green)
                        val ring = (view.findViewById<ImageView>(if (index == 0) R.id.widget_primary_ring else R.id.widget_weekly_ring)
                            .drawable as BitmapDrawable).bitmap
                        assertHue(expected, ring.getPixel(128, 14))
                        val bar = (view.findViewById<ImageView>(if (index == 0) R.id.widget_primary_progress else R.id.widget_weekly_progress)
                            .drawable as BitmapDrawable).bitmap
                        val y = bar.height / 2
                        assertHue(expected, bar.getPixel((bar.width * 0.1f).toInt(), y))
                    }
                }
            }
        }
    }

    @Test fun bothThemesKeepLegacyRingLayersHiddenAndEmptyOnReapply() {
        val context = RuntimeEnvironment.getApplication()
        for (night in listOf(false, true)) {
            val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            })
            for (style in WidgetStyle.entries) for (size in WidgetSize.entries) for (single in listOf(false, true)) {
                var view: View? = null
                for (value in listOf(100.0, 51.0, 0.0, null)) {
                    val sent = remote(themed, style, size, single, value)
                    if (view == null) view = sent.apply(themed, FrameLayout(themed)) else sent.reapply(themed, view)
                    assertNull(view!!.findViewById<View>(R.id.widget_ring_particles))
                    if (size == WidgetSize.COMPACT) continue
                    for (index in if (single) 1..1 else 0..1) {
                        val ring = if (index == 0) R.id.widget_primary_ring else R.id.widget_weekly_ring
                        val particles = if (index == 0) R.id.widget_primary_ring_particles else R.id.widget_weekly_ring_particles
                        val mask = if (index == 0) R.id.widget_primary_ring_mask else R.id.widget_weekly_ring_mask
                        assertEquals(View.GONE, view.findViewById<View>(ring).visibility)
                        assertEquals(0, view.findViewById<FrameLayout>(particles).childCount)
                        assertEquals(View.GONE, view.findViewById<View>(particles).visibility)
                        assertNull(view.findViewById<ImageView>(mask).drawable)
                    }
                }
            }
        }
    }
}
