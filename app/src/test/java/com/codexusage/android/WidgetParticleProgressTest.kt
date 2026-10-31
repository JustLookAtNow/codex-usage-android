package com.codexusage.android

import android.animation.AnimatorSet
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Parcel
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RemoteViews
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
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetParticleProgressTest {
    private val now = 1800000000L

    private fun remote(context: Context, style: WidgetStyle, size: WidgetSize, single: Boolean,
        value: Double?): RemoteViews {
        val windows = listOf(
            UsageWindow("5h", value, now + 3600, 18000, false).takeUnless { single },
            UsageWindow("Weekly", value, now + 86400, 604800, false))
        val rings = windows.mapIndexed { index, window ->
            UsageWidget.quotaRing(context, window?.remaining, index == 1, style)
        }
        val remote = UsageWidget.createViews(context, size, windows, rings, now, "刚刚", "", true, style = style)
        val parcel = Parcel.obtain()
        return try {
            remote.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private fun measure(context: Context, view: View, widthDp: Int, heightDp: Int) {
        val density = context.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((heightDp * density).roundToInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun draw(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    // Offscreen native canvases have no RenderThread or vsync. Use the same XML
    // animator with the framework UI backend and explicitly advance its clock.
    private fun prepare(spinner: ProgressBar): AnimatedVectorDrawable =
        (spinner.indeterminateDrawable as AnimatedVectorDrawable).also {
            ReflectionHelpers.callInstanceMethod<Any>(it, "forceAnimationOnUI")
        }

    private fun seek(animated: AnimatedVectorDrawable, millis: Long) {
        val backend = ReflectionHelpers.getField<Any>(animated, "mAnimatorSet")
        ReflectionHelpers.getField<AnimatorSet>(backend, "mSet").currentPlayTime = millis
    }

    @Test fun particlesMoveWithinQuotaAtEveryWidthAndStopWhenQuotaIsCleared() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val context = controller.get()
            val host = FrameLayout(context)
            context.setContentView(host)
            for (style in WidgetStyle.entries) for (size in listOf(WidgetSize.WIDE, WidgetSize.LARGE)) {
                for (single in listOf(false, true)) {
                    val view = remote(context, style, size, single, 51.0).apply(context, host)
                    host.addView(view)
                    val containerId = if (single) R.id.widget_weekly_progress_container else R.id.widget_primary_progress_container
                    val particlesId = if (single) R.id.widget_weekly_particles else R.id.widget_primary_particles
                    val maskId = if (single) R.id.widget_weekly_particle_mask else R.id.widget_primary_particle_mask
                    val container = view.findViewById<View>(containerId)
                    val particleHost = view.findViewById<FrameLayout>(particlesId)
                    val animated = prepare(particleHost.findViewById(R.id.widget_progress_particles))
                    for (width in listOf(240, 360, 480)) {
                        measure(context, view, width, if (size == WidgetSize.LARGE) 168 else 80)
                        val first = draw(container)
                        assertTrue("Launcher drawing must start the particle animation", animated.isRunning)
                        seek(animated, 1600)
                        val moved = draw(container)
                        assertFalse("Particles must visibly move: $style $size single=$single width=$width",
                            pixels(first).contentEquals(pixels(moved)))
                        for (x in (container.width * 0.51f).toInt() + 2 until container.width) {
                            for (y in 0 until container.height) {
                                assertEquals("Particles leaked into unfilled quota at $x,$y ($style $size $single $width)",
                                    first.getPixel(x, y), moved.getPixel(x, y))
                            }
                        }
                        seek(animated, 0)
                    }
                    // Both unknown and zero quota remove the animation on the same
                    // launcher view tree. A subsequent recovery adds exactly one.
                    for (value in listOf(null, 0.0)) {
                        remote(context, style, size, single, value).reapply(context, view)
                        assertEquals(0, particleHost.childCount)
                        assertFalse("Removing particles must stop their animator", animated.isRunning)
                        assertNull(view.findViewById<ImageView>(maskId).drawable)
                        remote(context, style, size, single, 100.0).reapply(context, view)
                        assertEquals(1, particleHost.childCount)
                    }
                    host.removeView(view)
                }
            }
        }
    }

    @Test fun compactAndNightRingLayoutsDoNotCreateBarParticleAnimators() {
        val context = RuntimeEnvironment.getApplication()
        for (night in listOf(false, true)) {
            val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            })
            for (style in WidgetStyle.entries) for (size in WidgetSize.entries) for (single in listOf(false, true)) {
                if (!night && size != WidgetSize.COMPACT) continue
                val view = remote(themed, style, size, single, 90.0).apply(themed, FrameLayout(themed))
                assertNull(view.findViewById<View>(R.id.widget_progress_particles))
                for (id in listOf(R.id.widget_primary_progress_container, R.id.widget_weekly_progress_container)) {
                    view.findViewById<View>(id)?.let { assertEquals(View.GONE, it.visibility) }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "notnight-xhdpi")
    fun renderNativeParticlePreviewAndVerifyMotion() {
        val output = File("build/reports/widget-particles").apply { mkdirs() }
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val context = controller.get()
            val host = FrameLayout(context)
            context.setContentView(host)
            for (style in WidgetStyle.entries) for (size in listOf(WidgetSize.WIDE, WidgetSize.LARGE)) {
                val view = remote(context, style, size, false, 86.0).apply(context, host)
                host.addView(view)
                measure(context, view, 360, if (size == WidgetSize.LARGE) 168 else 80)
                val animations = listOf(R.id.widget_primary_particles, R.id.widget_weekly_particles).map {
                    prepare(view.findViewById<FrameLayout>(it).findViewById(R.id.widget_progress_particles))
                }
                val first = draw(view)
                first.save(File(output, "$style-$size.png"))
                if (style == WidgetStyle.GLASS && size == WidgetSize.LARGE) {
                    repeat(30) { frame ->
                        animations.forEach { seek(it, frame * 160L) }
                        draw(view).save(File(output, "frame-${frame.toString().padStart(2, '0')}.png"))
                    }
                    assertFalse(pixels(first).contentEquals(pixels(draw(view))))
                }
                host.removeView(view)
            }
        }
    }

    private fun Bitmap.save(file: File) {
        file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
