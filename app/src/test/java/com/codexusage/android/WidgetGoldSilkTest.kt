package com.codexusage.android

import android.animation.AnimatorSet
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Parcel
import android.view.LayoutInflater
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetGoldSilkTest {
    private val now = 1800000000L

    private fun themed(context: Context, night: Boolean): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        })

    private fun remote(context: Context, style: WidgetStyle, size: WidgetSize, single: Boolean,
        primary: Double?, weekly: Double?): RemoteViews {
        val windows = listOf(
            UsageWindow("5h", primary, now + 17400, 18000, false).takeUnless { single },
            UsageWindow("Weekly", weekly, now + 313200, 604800, false))
        val rings = windows.mapIndexed { index, window ->
            UsageWidget.quotaRing(context, window?.remaining, index == 1, style)
        }
        val sent = UsageWidget.createViews(context, size, windows, rings, now, "11m", "", true,
            style = style, resetCount = 3)
        val parcel = Parcel.obtain()
        return try {
            sent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private fun measure(context: Context, view: View, width: Int, size: WidgetSize) {
        val density = context.resources.displayMetrics.density
        val height = if (size == WidgetSize.LARGE) 168 else 80
        view.measure(View.MeasureSpec.makeMeasureSpec((width * density).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).roundToInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun draw(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun prepare(view: View, index: Int): AnimatedVectorDrawable {
        val host = view.findViewById<FrameLayout>(if (index == 0) R.id.widget_primary_particles else R.id.widget_weekly_particles)
        return (host.findViewById<ProgressBar>(R.id.widget_progress_particles).indeterminateDrawable as AnimatedVectorDrawable).also {
            ReflectionHelpers.callInstanceMethod<Any>(it, "forceAnimationOnUI")
        }
    }

    private fun seek(animated: AnimatedVectorDrawable, millis: Long) {
        val backend = ReflectionHelpers.getField<Any>(animated, "mAnimatorSet")
        ReflectionHelpers.getField<AnimatorSet>(backend, "mSet").currentPlayTime = millis
    }

    @Test fun fullQuotaSwitchesIndependentlyWithoutResidualEffectsOrLayoutMovement() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            for (night in listOf(false, true)) {
                val context = themed(controller.get(), night)
                val host = FrameLayout(context)
                controller.get().setContentView(host)
                for (style in WidgetStyle.entries) for (size in WidgetSize.entries) for (single in listOf(false, true)) {
                    var view: View? = null
                    val bounds = mutableMapOf<Int, Rect>()
                    val transitions = listOf(100.0 to 61.0, 61.0 to 100.0, 100.0 to 100.0,
                        99.99 to 99.99, 100.0 to 100.0, 0.0 to null, null to 0.0, 25.0 to 9.0)
                    for ((primary, weekly) in transitions) {
                        val sent = remote(context, style, size, single, primary, weekly)
                        if (view == null) {
                            view = sent.apply(context, host)
                            host.addView(view)
                        } else sent.reapply(context, view)
                        measure(context, view!!, if (size == WidgetSize.COMPACT) 180 else 360, size)
                        for (index in if (single) 1..1 else 0..1) {
                            val value = if (index == 0) primary else weekly
                            val label = "$style $size night=$night single=$single index=$index value=$value"
                            val textId = if (index == 0) R.id.widget_primary_value else R.id.widget_weekly_value
                            val text = view.findViewById<TextView>(textId)
                            assertEquals(label, View.VISIBLE, text.visibility)
                            assertEquals(label, percentage(value), text.text.toString())
                            if (value == 100.0) assertEquals(label, context.getColor(R.color.widget_quota_full), text.currentTextColor)
                            else assertNotEquals(label, context.getColor(R.color.widget_quota_full), text.currentTextColor)
                            if (size == WidgetSize.COMPACT) continue
                            val progressId = if (index == 0) R.id.widget_primary_progress_container else R.id.widget_weekly_progress_container
                            val progress = view.findViewById<View>(progressId)
                            val rect = Rect(progress.left, progress.top, progress.right, progress.bottom)
                            bounds[progressId]?.let { assertEquals("$label: progress moved", it, rect) }
                            bounds[progressId] = rect
                            assertEquals(label, View.VISIBLE, progress.visibility)
                            val particles = view.findViewById<FrameLayout>(if (index == 0) R.id.widget_primary_particles else R.id.widget_weekly_particles)
                            val mask = view.findViewById<ImageView>(if (index == 0) R.id.widget_primary_particle_mask else R.id.widget_weekly_particle_mask)
                            if (value == null || value == 0.0) {
                                assertEquals(label, 0, particles.childCount)
                                assertNull(label, mask.drawable)
                            } else {
                                assertEquals(label, 1, particles.childCount)
                                if (value == 100.0) {
                                    assertEquals(label, progress.height, particles.height)
                                    assertEquals(label, View.GONE, mask.visibility)
                                    assertNull(label, mask.drawable)
                                } else {
                                    assertTrue(label, particles.height < progress.height)
                                    assertEquals(label, View.VISIBLE, mask.visibility)
                                }
                            }
                        }
                        if (!single && size != WidgetSize.COMPACT && primary == 100.0 && weekly == 100.0) {
                            val first = (view.findViewById<ImageView>(R.id.widget_primary_progress).drawable as BitmapDrawable).bitmap
                            val second = (view.findViewById<ImageView>(R.id.widget_weekly_progress).drawable as BitmapDrawable).bitmap
                            assertTrue("Both full windows must share the same material", first.sameAs(second))
                        }
                    }
                    host.removeView(view)
                }
            }
        }
    }

    @Test fun fullSilkMovesAcrossWidthsAndStopsWhenQuotaChanges() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            for (night in listOf(false, true)) {
                val context = themed(controller.get(), night)
                val host = FrameLayout(context)
                controller.get().setContentView(host)
                for (style in WidgetStyle.entries) for (size in listOf(WidgetSize.WIDE, WidgetSize.LARGE)) for (single in listOf(false, true)) {
                    val view = remote(context, style, size, single, 100.0, 100.0).apply(context, host)
                    host.addView(view)
                    val index = if (single) 1 else 0
                    val animated = prepare(view, index)
                    for (width in listOf(240, 360, 480)) {
                        measure(context, view, width, size)
                        val container = view.findViewById<View>(if (single) R.id.widget_weekly_progress_container else R.id.widget_primary_progress_container)
                        val first = draw(container)
                        assertTrue(animated.isRunning)
                        seek(animated, 2400)
                        val moved = draw(container)
                        assertFalse("Gold silk did not move: $style $size night=$night single=$single width=$width", first.sameAs(moved))
                        // A blur may leave an imperceptible alpha fringe, but must
                        // fade out before the number or countdown's row.
                        val upper = (0 until container.width).maxOf { Color.alpha(moved.getPixel(it, 0)) }
                        val lower = (0 until container.width).maxOf { Color.alpha(moved.getPixel(it, moved.height - 1)) }
                        assertTrue("Smoke reaches edge: $style $size width=$width alpha=$upper/$lower", maxOf(upper, lower) <= 12)
                        seek(animated, 0)
                    }
                    remote(context, style, size, single, 0.0, null).reapply(context, view)
                    assertFalse("Detached silk must stop", animated.isRunning)
                    host.removeView(view)
                }
            }
        }
    }

    @Test fun fullTextAndStaticSmokeRemainVisibleInBothThemes() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            for (night in listOf(false, true)) {
                val context = themed(controller.get(), night)
                val full = context.getColor(R.color.widget_quota_full)
                assertTrue(ColorUtils.calculateContrast(full, context.getColor(R.color.widget_surface)) >= 4.5)
                for (style in WidgetStyle.entries) for (size in listOf(WidgetSize.WIDE, WidgetSize.LARGE)) for (single in listOf(false, true)) {
                    val bitmap = UsageWidget.quotaProgress(context, 100.0, full, style, size, single)
                    val track = WidgetParticleProgress.trackBounds(bitmap.height, style, size, single)
                    val ordinary = UsageWidget.quotaProgress(context, 99.99, full, style, size, single)
                    var smoke = 0
                    for (x in 20 until bitmap.width - 20) for (y in 1 until track.top.toInt()) {
                        if (Color.alpha(bitmap.getPixel(x, y)) > 10) smoke++
                        assertEquals(0, Color.alpha(ordinary.getPixel(x, y)))
                    }
                    assertTrue("Static full bar must include smoke: $style $size", smoke > 100)
                }
            }
        }
    }

    @Test fun pickerPreviewShowsMatchingFullAndWeeklyBarsInBothThemes() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            for (night in listOf(false, true)) {
                val context = themed(controller.get(), night)
                val view = LayoutInflater.from(context).inflate(R.layout.usage_widget_glass_preview, FrameLayout(context), false)
                measure(context, view, 360, WidgetSize.LARGE)
                assertEquals("100%", view.findViewById<TextView>(R.id.widget_primary_value).text.toString())
                assertEquals(context.getColor(R.color.widget_quota_full), view.findViewById<TextView>(R.id.widget_primary_value).currentTextColor)
                assertEquals("61%", view.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
                for (id in listOf(R.id.widget_primary_progress, R.id.widget_weekly_progress)) {
                    assertNotNull(view.findViewById<ImageView>(id).drawable)
                    assertEquals(View.VISIBLE, view.findViewById<View>(id).visibility)
                }
                for (id in listOf(R.id.widget_primary_ring, R.id.widget_weekly_ring)) {
                    assertEquals(View.GONE, view.findViewById<View>(id).visibility)
                }
                val output = File("build/reports/widget-gold-silk").apply { mkdirs() }
                draw(view).save(File(output, "picker-night-$night.png"))
            }
        }
    }

    @Test fun renderNativeLightDarkComparisonAndMotionFrames() {
        val output = File("build/reports/widget-gold-silk").apply { mkdirs() }
        val board = Bitmap.createBitmap(1504, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        canvas.drawColor(Color.rgb(226, 232, 240))
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 49, 66); textSize = 24f }
        canvas.drawText("B / GOLD SILK  -  NATIVE ANDROID RENDER", 24f, 36f, label)
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            for (night in listOf(false, true)) {
                val context = themed(controller.get(), night)
                val host = FrameLayout(context)
                controller.get().setContentView(host)
                val x = if (night) 760 else 24
                canvas.drawText(if (night) "DARK" else "LIGHT", x.toFloat(), 76f, label)
                val cases = listOf(100.0 to 61.0, 61.0 to 100.0, 100.0 to 100.0)
                for (style in WidgetStyle.entries) for (size in listOf(WidgetSize.WIDE, WidgetSize.LARGE)) {
                    for ((case, quotas) in cases.withIndex()) {
                        val view = remote(context, style, size, false, quotas.first, quotas.second).apply(context, host)
                        host.addView(view)
                        measure(context, view, 360, size)
                        val animations = (0..1).map { prepare(view, it) }
                        draw(view)
                        animations.forEach { seek(it, 1200) }
                        val shot = draw(view)
                        shot.save(File(output, "$style-$size-night-$night-case-$case.png"))
                        if (style == WidgetStyle.GLASS && size == WidgetSize.WIDE) {
                            val y = 116 + case * 220
                            canvas.drawText("5h ${quotas.first.toInt()}% / Weekly ${quotas.second.toInt()}%", x.toFloat(), (y - 12).toFloat(), label)
                            val background = Paint().apply { color = Color.parseColor(if (night) "#182536" else "#D3E0F1") }
                            canvas.drawRect(x.toFloat(), y.toFloat(), x + 720f, y + 160f, background)
                            canvas.drawBitmap(shot, x.toFloat(), y.toFloat(), null)
                            if (case == 2) repeat(20) { frame ->
                                animations.forEach { seek(it, frame * 250L) }
                                draw(view).save(File(output, "${if (night) "dark" else "light"}-frame-${frame.toString().padStart(2, '0')}.png"))
                            }
                        }
                        host.removeView(view)
                    }
                    val weekly = remote(context, style, size, true, null, 100.0).apply(context, host)
                    host.addView(weekly)
                    measure(context, weekly, 360, size)
                    draw(weekly).save(File(output, "$style-$size-night-$night-weekly-only.png"))
                    if (style == WidgetStyle.GLASS && size == WidgetSize.WIDE) {
                        canvas.drawText("Weekly-only 100%", x.toFloat(), 764f, label)
                        val background = Paint().apply { color = Color.parseColor(if (night) "#182536" else "#D3E0F1") }
                        canvas.drawRect(x.toFloat(), 780f, x + 720f, 940f, background)
                        canvas.drawBitmap(draw(weekly), x.toFloat(), 780f, null)
                    }
                    host.removeView(weekly)
                }
            }
        }
        board.save(File(output, "light-dark-comparison.png"))
    }

    private fun Bitmap.save(file: File) {
        file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
