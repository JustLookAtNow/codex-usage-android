package com.codexusage.android

import android.animation.AnimatorSet
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Parcel
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

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

    private fun measure(context: Context, view: View, width: Int, height: Int) {
        val density = context.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((width * density).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).roundToInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun draw(view: View, background: Int = Color.TRANSPARENT): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.drawColor(background)
            view.draw(canvas)
        }

    private fun prepare(spinner: ProgressBar) = (spinner.indeterminateDrawable as AnimatedVectorDrawable).also {
        ReflectionHelpers.callInstanceMethod<Any>(it, "forceAnimationOnUI")
    }

    private fun seek(animated: AnimatedVectorDrawable, time: Long) {
        val backend = ReflectionHelpers.getField<Any>(animated, "mAnimatorSet")
        ReflectionHelpers.getField<AnimatorSet>(backend, "mSet").currentPlayTime = time
    }

    @Test fun orbitingParticlesStayInsideQuotaWhenResizedAndStopAfterQuotaIsCleared() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val context = controller.get()
            val host = FrameLayout(context)
            context.setContentView(host)
            for (style in WidgetStyle.entries) for (size in listOf(WidgetSize.WIDE, WidgetSize.LARGE)) {
                for (single in listOf(false, true)) {
                    val view = remote(context, style, size, single, 51.0).apply(context, host)
                    host.addView(view)
                    val particlesId = if (single) R.id.widget_weekly_ring_particles else R.id.widget_primary_ring_particles
                    val ringId = if (single) R.id.widget_weekly_ring else R.id.widget_primary_ring
                    val maskId = if (single) R.id.widget_weekly_ring_mask else R.id.widget_primary_ring_mask
                    val particleHost = view.findViewById<FrameLayout>(particlesId)
                    val animated = prepare(particleHost.findViewById(R.id.widget_ring_particles))
                    val frame = view.findViewById<View>(ringId).parent as View
                    for (width in listOf(240, 360, 480)) {
                        measure(context, view, width, if (size == WidgetSize.LARGE) 168 else 80)
                        val first = draw(frame)
                        assertTrue(animated.isRunning)
                        seek(animated, 1600)
                        val moved = draw(frame)
                        val scale = 256f / minOf(frame.width, frame.height)
                        var changes = 0
                        var visibleChanges = 0
                        for (y in 0 until frame.height) for (x in 0 until frame.width) {
                            if (first.getPixel(x, y) == moved.getPixel(x, y)) continue
                            changes++
                            val before = first.getPixel(x, y)
                            val after = moved.getPixel(x, y)
                            if (maxOf(kotlin.math.abs(Color.red(after) - Color.red(before)),
                                kotlin.math.abs(Color.green(after) - Color.green(before)),
                                kotlin.math.abs(Color.blue(after) - Color.blue(before))) >= 32) visibleChanges++
                            val dx = (x + 0.5f - frame.width / 2f) * scale
                            val dy = (y + 0.5f - frame.height / 2f) * scale
                            val radius = hypot(dx, dy)
                            val angle = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 450) % 360
                            assertTrue("Particle moved outside the ring: $style $size $single $width ($x,$y)", radius in 91f..137f)
                            assertTrue("Particle entered unfilled quota: $style $size $single $width angle=$angle",
                                angle <= 51 * 3.6 + 14 || angle >= 346)
                        }
                        assertTrue("Ring particles must visibly move: $style $size $single $width", changes > 0)
                        assertTrue("Small rings need clearly visible moving highlights: $style $size $single $width ($visibleChanges)",
                            visibleChanges >= 3)
                        seek(animated, 0)
                    }
                    for (value in listOf(null, 0.0)) {
                        remote(context, style, size, single, value).reapply(context, view)
                        assertEquals(0, particleHost.childCount)
                        assertFalse(animated.isRunning)
                        assertNull(view.findViewById<ImageView>(maskId).drawable)
                        remote(context, style, size, single, 100.0).reapply(context, view)
                        assertEquals(1, particleHost.childCount)
                    }
                    host.removeView(view)
                }
            }
        }
    }

    private fun assertHue(expected: Int, actual: Int) {
        val expectedHsl = FloatArray(3).also { ColorUtils.colorToHSL(expected, it) }
        val actualHsl = FloatArray(3).also { ColorUtils.colorToHSL(actual, it) }
        assertTrue("Wrong quota hue: expected=$expected actual=$actual",
            kotlin.math.abs(expectedHsl[0] - actualHsl[0]) < 5f && actualHsl[1] > 0.15f)
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    @Test
    @Config(qualifiers = "night-xhdpi")
    fun amberAndRedRingsKeepVisibleContrastingParticlesEvenAtTinyPositiveQuotas() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val context = controller.get()
            val host = FrameLayout(context)
            context.setContentView(host)
            for (style in WidgetStyle.entries) for (size in listOf(WidgetSize.WIDE, WidgetSize.LARGE)) {
                for (single in listOf(false, true)) {
                    var view: View? = null
                    for (value in listOf(100.0, 40.0, 25.0, 15.0, 9.0, 1.0, 0.1)) {
                        val remote = remote(context, style, size, single, value)
                        if (view == null) {
                            view = remote.apply(context, host)
                            host.addView(view)
                        } else remote.reapply(context, view)
                        measure(context, view!!, 360, if (size == WidgetSize.LARGE) 168 else 80)
                        val particlesId = if (single) R.id.widget_weekly_ring_particles else R.id.widget_primary_ring_particles
                        val spinner = view.findViewById<FrameLayout>(particlesId).findViewById<ProgressBar>(R.id.widget_ring_particles)
                        assertEquals(context.getColor(when {
                            value == 100.0 -> R.color.widget_particle_gold
                            value <= 15.0 -> R.color.widget_particle_ruby
                            else -> R.color.widget_particle_amber
                        }),
                            spinner.indeterminateTintList!!.defaultColor)
                        val animated = prepare(spinner)
                        val ringId = if (single) R.id.widget_weekly_ring else R.id.widget_primary_ring
                        val frame = view.findViewById<View>(ringId).parent as View
                        val first = pixels(draw(frame))
                        assertTrue(animated.isRunning)
                        var peakChange = 0
                        for (time in listOf(300L, 600L, 900L, 1200L, 1600L)) {
                            seek(animated, time)
                            val moved = pixels(draw(frame))
                            for (index in first.indices) {
                                peakChange = maxOf(peakChange,
                                    kotlin.math.abs(Color.red(first[index]) - Color.red(moved[index])),
                                    kotlin.math.abs(Color.green(first[index]) - Color.green(moved[index])),
                                    kotlin.math.abs(Color.blue(first[index]) - Color.blue(moved[index])))
                            }
                        }
                        assertTrue("Particles must remain visibly animated at $value%: $style $size single=$single peak=$peakChange",
                            peakChange >= 32)
                    }
                    host.removeView(view)
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "night-xhdpi")
    fun renderNativeGreenAmberRedAndGoldParticlePreview() {
        val output = File("build/reports/widget-ring-colors").apply { mkdirs() }
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val context = controller.get()
            val host = FrameLayout(context)
            context.setContentView(host)
            val rowHeight = (80 * context.resources.displayMetrics.density).roundToInt()
            val gap = (12 * context.resources.displayMetrics.density).roundToInt()
            for ((row, quotas) in listOf(86.0 to 25.0, 9.0 to 100.0).withIndex()) {
                val view = remote(context, WidgetStyle.GLASS, WidgetSize.WIDE, false, quotas.first, quotas.second)
                    .apply(context, host)
                host.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, rowHeight).apply {
                    topMargin = row * (rowHeight + gap)
                })
            }
            measure(context, host, 360, 172)
            val animations = (0 until host.childCount).flatMap { row ->
                listOf(R.id.widget_primary_ring_particles, R.id.widget_weekly_ring_particles).map { id ->
                    prepare(host.getChildAt(row).findViewById<FrameLayout>(id).findViewById(R.id.widget_ring_particles))
                }
            }
            fun snapshot() = draw(host, Color.rgb(17, 23, 28))
            snapshot().save(File(output, "quota-colors.png"))
            repeat(30) { frame ->
                animations.forEach { seek(it, frame * 160L) }
                snapshot().save(File(output, "frame-${frame.toString().padStart(2, '0')}.png"))
            }
        }
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
                        val y = (0 until bar.height).maxBy { Color.alpha(bar.getPixel(100, it)) }
                        assertHue(expected, bar.getPixel(100, y))
                    }
                }
            }
        }
    }

    @Test fun lightAndCompactWidgetsDoNotStartRingAnimators() {
        val context = RuntimeEnvironment.getApplication()
        for (night in listOf(false, true)) {
            val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            })
            for (style in WidgetStyle.entries) for (size in WidgetSize.entries) for (single in listOf(false, true)) {
                if (night && size != WidgetSize.COMPACT) continue
                val view = remote(themed, style, size, single, 86.0).apply(themed, FrameLayout(themed))
                assertNull(view.findViewById<View>(R.id.widget_ring_particles))
            }
        }
    }

    @Test
    @Config(qualifiers = "night-xhdpi")
    fun renderNativeNightRingsAndFullGoldPreview() {
        val output = File("build/reports/widget-rings").apply { mkdirs() }
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val context = controller.get()
            val host = FrameLayout(context)
            context.setContentView(host)
            for (style in WidgetStyle.entries) for (size in listOf(WidgetSize.WIDE, WidgetSize.LARGE)) {
                val view = remote(context, style, size, false, 86.0, 100.0).apply(context, host)
                host.addView(view)
                measure(context, view, 360, if (size == WidgetSize.LARGE) 168 else 80)
                val animations = listOf(R.id.widget_primary_ring_particles, R.id.widget_weekly_ring_particles).map {
                    prepare(view.findViewById<FrameLayout>(it).findViewById(R.id.widget_ring_particles))
                }
                fun snapshot() = draw(view, Color.rgb(17, 23, 28))
                snapshot().save(File(output, "$style-$size.png"))
                if (style == WidgetStyle.GLASS && size == WidgetSize.LARGE) {
                    repeat(30) { frame ->
                        animations.forEach { seek(it, frame * 160L) }
                        snapshot().save(File(output, "frame-${frame.toString().padStart(2, '0')}.png"))
                    }
                }
                host.removeView(view)
            }
        }
    }

    private fun Bitmap.save(file: File) {
        file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
