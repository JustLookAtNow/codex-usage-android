package com.codexusage.android

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.Parcel
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import android.widget.ViewFlipper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
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
                assertTrue(source.sameAs(WidgetLoadingPresentation.snapshot(context, normal, size)))
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
                assertFalse(source.sameAs(blurred))
                WidgetLoadingPresentation.blur(source)
                // Both images must cross the same native Bitmap parcel boundary. Compare
                // visible ARGB pixels rather than the native bitmap's storage/configuration.
                val expected = parcel(source)
                val expectedPixels = IntArray(expected.width * expected.height)
                val actualPixels = IntArray(blurred.width * blurred.height)
                expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
                blurred.getPixels(actualPixels, 0, blurred.width, 0, 0, blurred.width, blurred.height)
                assertArrayEquals("Whole-content blur: night=$night weeklyOnly=$weeklyOnly size=$size",
                    expectedPixels, actualPixels)
                assertNotNull(view.findViewById<ViewFlipper>(R.id.widget_refresh_spinner))
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

    @Test fun spinnerRunsInHostAfterApplyAndReapplyAndIsRemovedOnCompletion() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val context = controller.get()
            val size = SizeF(360f, 168f)
            val host = FrameLayout(context)
            controller.get().setContentView(host)
            val view = parcel(remote(context, size, false, true)).apply(context, host)
            host.addView(view)
            repeat(2) {
                val spinner = view.findViewById<ViewFlipper>(R.id.widget_refresh_spinner)
                assertTrue(spinner.isFlipping)
                assertEquals(1000L, spinner.inAnimation.duration)
                val first = spinner.displayedChild
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
                assertNotEquals(first, spinner.displayedChild)
                parcel(remote(context, size, false, false)).reapply(context, view)
                assertNull(spinner.parent)
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
