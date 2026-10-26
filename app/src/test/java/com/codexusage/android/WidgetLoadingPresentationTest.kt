package com.codexusage.android

import android.app.Activity
import android.animation.AnimatorSet
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Parcel
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView
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
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetLoadingPresentationTest {
    private val now = 1800000000L

    private fun remote(context: Context, dimensions: SizeF, weeklyOnly: Boolean, refreshing: Boolean): RemoteViews {
        val windows = listOf(
            UsageWindow("5h", 100.0, now + 3600, 18000, false).takeUnless { weeklyOnly },
            UsageWindow("Weekly", 86.0, now + 86400, 604800, false))
        val rings = windows.mapIndexed { index, window -> UsageWidget.quotaRing(context, window?.remaining, index == 1) }
        return UsageWidget.createViews(context, widgetSize(dimensions.width.toInt(), dimensions.height.toInt()),
            windows, rings, now, "", "正在刷新额度".takeIf { refreshing }.orEmpty(), true,
            refreshing, displaySize = dimensions)
    }

    private fun measure(context: Context, view: View, size: SizeF) {
        val density = context.resources.displayMetrics.density
        val width = (size.width * density).roundToInt()
        val height = (size.height * density).roundToInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun bounds(root: ViewGroup): Map<Int, Rect> {
        val ids = listOf(R.id.widget_primary_value, R.id.widget_weekly_value,
            R.id.widget_primary_countdown, R.id.widget_weekly_countdown,
            R.id.widget_refresh, R.id.widget_refresh_icon, R.id.widget_updated_at)
        return ids.mapNotNull { id -> root.findViewById<View>(id)?.let { view ->
            val rect = Rect(0, 0, view.width, view.height)
            root.offsetDescendantRectToMyCoords(view, rect)
            id to rect
        } }.toMap()
    }

    private fun parcel(remote: RemoteViews): RemoteViews {
        val parcel = Parcel.obtain()
        return try {
            remote.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private fun parcel(bitmap: Bitmap): Bitmap {
        val parcel = Parcel.obtain()
        return try {
            bitmap.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Bitmap.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    @Test fun refreshAndRecoveryPreserveGeometryAndBlurAllContentAtActualSizes() {
        val application = RuntimeEnvironment.getApplication()
        for (night in listOf(false, true)) for (weeklyOnly in listOf(false, true)) {
            val context = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            })
            for (size in listOf(SizeF(140f, 72f), SizeF(180f, 80f), SizeF(240f, 72f),
                SizeF(360f, 80f), SizeF(240f, 140f), SizeF(360f, 168f))) {
                val normal = remote(context, size, weeklyOnly, false)
                val view = normal.apply(context, FrameLayout(context)) as ViewGroup
                measure(context, view, size)
                val before = bounds(view)
                val textSize = view.findViewById<TextView>(R.id.widget_weekly_value).textSize
                val source = WidgetLoadingPresentation.snapshot(context, normal, size)
                // Changing the separate refresh icon must not affect the content snapshot.
                normal.setImageViewBitmap(R.id.widget_refresh_icon,
                    Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
                assertArrayEquals("Refresh button excluded: night=$night weeklyOnly=$weeklyOnly size=$size",
                    pixels(source), pixels(WidgetLoadingPresentation.snapshot(context, normal, size)))
                parcel(remote(context, size, weeklyOnly, true)).reapply(context, view)
                measure(context, view, size)
                assertEquals(before, bounds(view))
                assertEquals(textSize, view.findViewById<TextView>(R.id.widget_weekly_value).textSize)
                assertEquals("86%", view.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
                assertEquals(View.INVISIBLE, view.findViewById<View>(R.id.widget_content).visibility)
                val overlay = view.findViewById<ImageView>(R.id.widget_loading_overlay)
                assertEquals(View.VISIBLE, overlay.visibility)
                val blurred = (overlay.drawable as BitmapDrawable).bitmap
                assertEquals(size.width.roundToInt(), blurred.width)
                assertEquals(size.height.roundToInt(), blurred.height)
                assertFalse("Content must visibly change during refresh",
                    pixels(source).contentEquals(pixels(blurred)))
                WidgetLoadingPresentation.blur(source)
                // Both images must cross the same native Bitmap parcel boundary. Compare
                // visible ARGB pixels rather than the native bitmap's storage/configuration.
                val expected = parcel(source)
                assertArrayEquals("Whole-content blur: night=$night weeklyOnly=$weeklyOnly size=$size",
                    pixels(expected), pixels(blurred))
                val spinner = view.findViewById<ProgressBar>(R.id.widget_refresh_spinner)
                assertNotNull(spinner)
                val icon = view.findViewById<View>(R.id.widget_refresh_icon)
                assertEquals(icon.width, spinner.width)
                assertEquals(icon.height, spinner.height)
                assertEquals(spinner.width, spinner.height)
                val spinnerBounds = Rect(0, 0, spinner.width, spinner.height)
                view.offsetDescendantRectToMyCoords(spinner, spinnerBounds)
                assertEquals(before.getValue(R.id.widget_refresh_icon), spinnerBounds)
                assertEquals(View.INVISIBLE, view.findViewById<View>(R.id.widget_refresh_icon).visibility)
                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_status).visibility)
                parcel(remote(context, size, weeklyOnly, false)).reapply(context, view)
                measure(context, view, size)
                assertEquals(before, bounds(view))
                assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_content).visibility)
                assertEquals(View.GONE, overlay.visibility)
                assertNull(overlay.drawable)
                assertNull(view.findViewById<View>(R.id.widget_refresh_spinner))
                assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_refresh_icon).visibility)
            }
        }
    }

    @Test fun spinnerStartsOnFirstDrawMovesWithin100msAndStopsAfterApplyAndReapply() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val context = controller.get()
            val size = SizeF(360f, 168f)
            val host = FrameLayout(context)
            controller.get().setContentView(host)
            val view = parcel(remote(context, size, false, true)).apply(context, host)
            host.addView(view)
            fun drawIcon(spinner: ProgressBar): Bitmap {
                val bitmap = Bitmap.createBitmap(spinner.width, spinner.height, Bitmap.Config.ARGB_8888)
                spinner.draw(Canvas(bitmap))
                return bitmap
            }
            repeat(2) {
                measure(context, view, size)
                val spinner = view.findViewById<ProgressBar>(R.id.widget_refresh_spinner)
                val animated = spinner.indeterminateDrawable as AnimatedVectorDrawable
                // The offscreen test canvas has no RenderThread. Select the framework's
                // UI animation backend without starting it; ProgressBar must do that on draw.
                ReflectionHelpers.callInstanceMethod<Any>(animated, "forceAnimationOnUI")
                val first = drawIcon(spinner)
                assertTrue("Vector animation must start on the first draw", animated.isRunning)
                // Offscreen rendering has no display-vsync clock. Advance the real XML
                // animator to 100ms without starting it or changing its target/property.
                val backend = ReflectionHelpers.getField<Any>(animated, "mAnimatorSet")
                ReflectionHelpers.getField<AnimatorSet>(backend, "mSet").currentPlayTime = 100
                val rotated = drawIcon(spinner)
                assertFalse("Refresh icon must move before the old one-second flip delay",
                    pixels(first).contentEquals(pixels(rotated)))
                val output = File("build/reports/widget-refresh").apply { mkdirs() }
                File(output, "first-frame-$it.png").outputStream().use { first.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(output, "after-100ms-$it.png").outputStream().use { rotated.compress(Bitmap.CompressFormat.PNG, 100, it) }
                parcel(remote(context, size, false, false)).reapply(context, view)
                assertNull(spinner.parent)
                assertFalse("Removing the loading view must stop rotation", animated.isRunning)
                assertNull(view.findViewById<View>(R.id.widget_refresh_spinner))
                parcel(remote(context, size, false, true)).reapply(context, view)
            }
        }
    }

    @Test fun transparentBlurKeepsColorAndSpreadsAcrossBothAxes() {
        val bitmap = Bitmap.createBitmap(41, 41, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.TRANSPARENT)
        bitmap.setPixel(20, 20, Color.RED)
        WidgetLoadingPresentation.blur(bitmap)
        assertTrue(Color.alpha(bitmap.getPixel(20, 20)) in 1..254)
        for ((x, y) in listOf(19 to 20, 20 to 19)) {
            val pixel = bitmap.getPixel(x, y)
            assertTrue(Color.alpha(pixel) > 0)
            assertEquals(255, Color.red(pixel))
            assertEquals(0, Color.green(pixel))
            assertEquals(0, Color.blue(pixel))
        }
    }
}
