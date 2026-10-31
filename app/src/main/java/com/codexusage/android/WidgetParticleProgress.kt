package com.codexusage.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils

internal object WidgetParticleProgress {
    // Preserve the existing transparent insets so the particle layers do not move
    // the percentage or the recovery countdown.
    private data class Metrics(val height: Int, val top: Int, val bar: Int)

    private fun metrics(style: WidgetStyle, size: WidgetSize, singleWeekly: Boolean): Metrics {
        val bar = if (size == WidgetSize.LARGE && !singleWeekly) 9 else 5
        if (style == WidgetStyle.STANDARD) return Metrics(bar, 0, bar)
        return when {
            singleWeekly && size == WidgetSize.LARGE -> Metrics(10, 0, bar)
            singleWeekly -> Metrics(9, 3, bar)
            size == WidgetSize.LARGE -> Metrics(15, 3, bar)
            else -> Metrics(12, 3, bar)
        }
    }

    fun trackBounds(height: Int, style: WidgetStyle, size: WidgetSize, singleWeekly: Boolean): RectF {
        val metrics = metrics(style, size, singleWeekly)
        return RectF(0f, metrics.top * height.toFloat() / metrics.height, 1000f,
            (metrics.top + metrics.bar) * height.toFloat() / metrics.height)
    }

    private fun fillPath(track: RectF, value: Double?): Path {
        val end = ((value ?: 0.0).coerceIn(0.0, 100.0) * 10).toFloat()
        val radius = minOf(track.height() / 2f, end / 2f)
        return Path().apply {
            if (end > 0f) addRoundRect(RectF(0f, track.top, end, track.bottom), radius, radius, Path.Direction.CW)
        }
    }

    fun fillColor(context: Context, value: Double, quotaColor: Int): Int = when {
        value == 100.0 -> context.getColor(R.color.widget_progress_gold)
        value > 40.0 -> context.getColor(R.color.widget_progress_green)
        else -> ColorUtils.blendARGB(quotaColor, Color.WHITE, 0.36f)
    }

    fun particleColor(context: Context, value: Double): Int = when {
        value <= 15.0 -> context.getColor(R.color.widget_particle_ruby)
        value <= 40.0 || value == 100.0 -> context.getColor(R.color.widget_particle_amber)
        else -> Color.WHITE
    }

    fun tintParticles(context: Context, particles: RemoteViews, viewId: Int, value: Double) {
        particles.setColorStateList(viewId, "setIndeterminateTintList",
            ColorStateList.valueOf(particleColor(context, value)))
    }

    fun render(context: Context, value: Double?, color: Int, style: WidgetStyle, size: WidgetSize,
        singleWeekly: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(1000, if (style == WidgetStyle.GLASS) 32 else 20, Bitmap.Config.ARGB_8888)
        val track = trackBounds(bitmap.height, style, size, singleWeekly)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = context.getColor(if (style == WidgetStyle.GLASS) R.color.widget_glass_track else R.color.widget_track)
        }
        canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint)
        if (value == null || value <= 0.0) return bitmap
        val end = (value.coerceAtMost(100.0) * 10).toFloat()
        canvas.save()
        canvas.clipPath(fillPath(track, value))
        val fill = fillColor(context, value, color)
        // The same soft mint material runs from start to finish. A very gentle
        // vertical gradient gives depth without creating a white endpoint flare.
        paint.shader = LinearGradient(0f, track.top, 0f, track.bottom,
            ColorUtils.blendARGB(fill, Color.WHITE, 0.1f), fill, Shader.TileMode.CLAMP)
        canvas.drawRect(track, paint)
        paint.shader = null
        // A few quiet flecks also give launchers that pause animations a useful frame.
        for (index in 0 until 20) {
            val x = 28f + index * 49f
            if (x >= end - 12f) break
            val y = track.top + track.height() * (0.25f + (index * 37 % 53) / 100f)
            val radiusY = track.height() * (if (index % 4 == 0) 0.09f else 0.065f)
            paint.color = ColorUtils.setAlphaComponent(particleColor(context, value), if (index % 3 == 0) 150 else 80)
            canvas.drawOval(x - 3.5f, y - radiusY, x + 3.5f, y + radiusY, paint)
        }
        canvas.restore()
        return bitmap
    }

    private fun mask(context: Context, value: Double?, style: WidgetStyle, size: WidgetSize,
        singleWeekly: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(1000, if (style == WidgetStyle.GLASS) 32 else 20, Bitmap.Config.ARGB_8888)
        val track = trackBounds(bitmap.height, style, size, singleWeekly)
        val path = Path().apply { addRoundRect(track, track.height() / 2f, track.height() / 2f, Path.Direction.CW) }
        path.op(fillPath(track, value), Path.Op.DIFFERENCE)
        Canvas(bitmap).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(if (style == WidgetStyle.GLASS) R.color.widget_glass_track else R.color.widget_track)
        })
        return bitmap
    }

    fun apply(context: Context, views: RemoteViews, index: Int, value: Double?, style: WidgetStyle,
        size: WidgetSize, singleWeekly: Boolean, visible: Boolean) {
        val container = if (index == 0) R.id.widget_primary_progress_container else R.id.widget_weekly_progress_container
        val particles = if (index == 0) R.id.widget_primary_particles else R.id.widget_weekly_particles
        val maskId = if (index == 0) R.id.widget_primary_particle_mask else R.id.widget_weekly_particle_mask
        val metrics = metrics(style, size, singleWeekly)
        views.setViewVisibility(container, if (visible) View.VISIBLE else View.GONE)
        if (style == WidgetStyle.GLASS) {
            views.setViewLayoutHeight(container, metrics.height.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(container, RemoteViews.MARGIN_TOP, 0f, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(container, RemoteViews.MARGIN_BOTTOM, 0f, TypedValue.COMPLEX_UNIT_DIP)
        }
        // Always clear children on reapply: expired/unknown quota and hidden night
        // bars must stop animating, and refreshes must not accumulate animators.
        views.removeAllViews(particles)
        val animated = visible && value != null && value > 0.0
        views.setViewVisibility(maskId, if (animated) View.VISIBLE else View.GONE)
        if (animated) {
            views.setViewLayoutHeight(particles, metrics.bar.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(particles, RemoteViews.MARGIN_TOP, metrics.top.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            val animation = RemoteViews(context.packageName, R.layout.widget_progress_particles)
            tintParticles(context, animation, R.id.widget_progress_particles, value!!)
            views.addView(particles, animation)
            // This bitmap masks the unfilled track. It scales with the bar, so
            // particles stay inside the true quota at every launcher width.
            views.setImageViewBitmap(maskId, mask(context, value, style, size, singleWeekly))
        } else {
            views.setImageViewResource(maskId, 0)
        }
    }
}
