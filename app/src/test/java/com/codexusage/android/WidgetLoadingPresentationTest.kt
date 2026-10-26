package com.codexusage.android

import android.app.Activity
import android.animation.AnimatorSet
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Parcel
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RemoteViews
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

    private fun parcel(remote: RemoteViews): RemoteViews {
        val parcel = Parcel.obtain()
        return try {
            remote.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
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
