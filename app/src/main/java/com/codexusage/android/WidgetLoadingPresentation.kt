package com.codexusage.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import kotlin.math.roundToInt

internal object WidgetLoadingPresentation {
    fun apply(context: Context, views: RemoteViews, size: SizeF, refreshing: Boolean) {
        // Explicitly reset every state: launchers normally reapply the same RemoteViews tree.
        views.setViewVisibility(R.id.widget_content, View.VISIBLE)
        views.setViewVisibility(R.id.widget_loading_overlay, View.GONE)
        views.setImageViewResource(R.id.widget_loading_overlay, 0)
        views.setViewVisibility(R.id.widget_refresh_icon, View.VISIBLE)
        views.removeAllViews(R.id.widget_refresh_animation)
        if (!refreshing) return

        val bitmap = snapshot(context, views, size)
        blur(bitmap)
        views.setImageViewBitmap(R.id.widget_loading_overlay, bitmap)
        views.setViewVisibility(R.id.widget_loading_overlay, View.VISIBLE)
        views.setViewVisibility(R.id.widget_content, View.INVISIBLE)
        views.setViewVisibility(R.id.widget_refresh_icon, View.INVISIBLE)
        // ProgressBar starts its animated vector on the first draw, including on reapply.
        // Removing the view when finished also stops the drawable's animation.
        views.addView(R.id.widget_refresh_animation,
            RemoteViews(context.packageName, R.layout.widget_refresh_spinner))
    }

    internal fun snapshot(context: Context, views: RemoteViews, size: SizeF): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (size.width * density).roundToInt().coerceAtLeast(1)
        val height = (size.height * density).roundToInt().coerceAtLeast(1)
        val root = views.apply(context, FrameLayout(context))
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        // One pixel per dp bounds processing and IPC cost while keeping a 3dp blur
        // consistent across display densities. Draw the real layout, including autosizing.
        val bitmap = Bitmap.createBitmap(size.width.roundToInt().coerceAtLeast(1),
            size.height.roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(bitmap.width.toFloat() / width, bitmap.height.toFloat() / height)
        root.findViewById<View>(R.id.widget_content).draw(canvas)
        return bitmap
    }

    // Three separable box passes approximate a Gaussian blur of about 3dp.
    // Premultiply channels before averaging so transparent edges retain their colors.
    internal fun blur(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val scratch = IntArray(pixels.size)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            pixels[i] = (a shl 24) or ((((p ushr 16) and 255) * a / 255) shl 16) or
                ((((p ushr 8) and 255) * a / 255) shl 8) or ((p and 255) * a / 255)
        }
        repeat(3) {
            boxPass(pixels, scratch, width, height, true)
            boxPass(scratch, pixels, width, height, false)
        }
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            pixels[i] = if (a == 0) 0 else (a shl 24) or
                ((((p ushr 16) and 255) * 255 / a).coerceAtMost(255) shl 16) or
                ((((p ushr 8) and 255) * 255 / a).coerceAtMost(255) shl 8) or
                ((p and 255) * 255 / a).coerceAtMost(255)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun boxPass(source: IntArray, target: IntArray, width: Int, height: Int, horizontal: Boolean) {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val step = if (horizontal) 1 else width
        val radius = 3
        val count = radius * 2 + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * width else line
            var alpha = 0; var red = 0; var green = 0; var blue = 0
            fun accumulate(position: Int, sign: Int) {
                val p = source[base + position.coerceIn(0, length - 1) * step]
                alpha += (p ushr 24) * sign
                red += ((p ushr 16) and 255) * sign
                green += ((p ushr 8) and 255) * sign
                blue += (p and 255) * sign
            }
            for (offset in -radius..radius) accumulate(offset, 1)
            for (position in 0 until length) {
                target[base + position * step] = ((alpha / count) shl 24) or
                    ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
                accumulate(position - radius, -1)
                accumulate(position + radius + 1, 1)
            }
        }
    }
}
