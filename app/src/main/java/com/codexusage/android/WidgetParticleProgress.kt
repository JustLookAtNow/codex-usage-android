package com.codexusage.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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
    // Reserve the same smoke space at every quota, so reaching 100% never moves text.
    private data class Metrics(val height: Int, val top: Int, val bar: Int)

    private fun metrics(size: WidgetSize, singleWeekly: Boolean): Metrics {
        val bar = if (size == WidgetSize.LARGE && !singleWeekly) 9 else 5
        val inset = if (bar == 9) 5 else 4
        return if (size == WidgetSize.WIDE) Metrics(12, 3, bar)
        else Metrics(bar + inset * 2, inset, bar)
    }

    fun trackBounds(height: Int, style: WidgetStyle, size: WidgetSize, singleWeekly: Boolean): RectF {
        val metrics = metrics(size, singleWeekly)
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
        value == 100.0 -> context.getColor(R.color.widget_particle_gold)
        value <= 15.0 -> context.getColor(R.color.widget_particle_ruby)
        value <= 40.0 -> context.getColor(R.color.widget_particle_amber)
        else -> Color.WHITE
    }

    fun tintParticles(context: Context, particles: RemoteViews, viewId: Int, value: Double) {
        particles.setColorStateList(viewId, "setIndeterminateTintList",
            ColorStateList.valueOf(particleColor(context, value)))
    }

    fun render(context: Context, value: Double?, color: Int, style: WidgetStyle, size: WidgetSize,
        singleWeekly: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(1000, 48, Bitmap.Config.ARGB_8888)
        val track = trackBounds(bitmap.height, style, size, singleWeekly)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = context.getColor(if (style == WidgetStyle.GLASS) R.color.widget_glass_track else R.color.widget_track)
        }
        if (value == 100.0) drawGoldHalo(context, canvas, track)
        canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint)
        if (value == null || value <= 0.0) return bitmap
        val end = (value.coerceAtMost(100.0) * 10).toFloat()
        canvas.save()
        canvas.clipPath(fillPath(track, value))
        val fill = fillColor(context, value, color)
        // A horizontal silk gradient keeps gold soft instead of cylindrical;
        // ordinary quotas retain their gentle mint material and particle texture.
        paint.shader = if (value == 100.0) LinearGradient(0f, 0f, 1000f, 0f,
            intArrayOf(fill, context.getColor(R.color.widget_gold_silk), fill,
                context.getColor(R.color.widget_gold_silk), fill),
            floatArrayOf(0f, 0.24f, 0.52f, 0.78f, 1f), Shader.TileMode.CLAMP)
        else LinearGradient(0f, track.top, 0f, track.bottom,
            ColorUtils.blendARGB(fill, Color.WHITE, 0.1f), fill, Shader.TileMode.CLAMP)
        canvas.drawRect(track, paint)
        paint.shader = null
        // One static accent complements two drifting large flecks in wide bars;
        // the remaining smaller specks vary in position, size, and opacity.
        val wideParticles = size == WidgetSize.WIDE
        for (index in 0 until if (value == 100.0) 0 else 20) {
            val scatter = (index * 73 + 19) % 101
            val x = 28f + index * 49f + if (wideParticles) (scatter % 23 - 11).toFloat() else 0f
            if (x >= end - 12f) break
            val y = track.top + track.height() * (0.25f + (index * 37 % 53) / 100f)
            val large = wideParticles && index == 4
            val radiusX = if (large) 7.5f else if (wideParticles) 3.5f + scatter % 3 else 3.5f
            val radiusY = track.height() * (if (wideParticles) {
                if (large) 0.15f else 0.065f + (scatter % 4) * 0.008f
            } else if (index % 4 == 0) 0.09f else 0.065f)
            val alpha = if (wideParticles) {
                if (large) 200 else 100 + scatter % 65
            } else if (index % 3 == 0) 150 else 80
            paint.color = ColorUtils.setAlphaComponent(particleColor(context, value), alpha)
            canvas.drawOval(x - radiusX, y - radiusY, x + radiusX, y + radiusY, paint)
        }
        canvas.restore()
        if (value == 100.0) drawGoldWisps(context, canvas, track, bitmap.height.toFloat())
        return bitmap
    }

    private fun drawGoldHalo(context: Context, canvas: Canvas, track: RectF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(context.getColor(R.color.widget_gold_glow), 85)
            maskFilter = BlurMaskFilter(track.height() * 0.36f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint)
    }

    private fun drawGoldWisps(context: Context, canvas: Canvas, track: RectF, height: Float) {
        // Long, shallow silk curves remain visible even when the launcher pauses animation.
        val top = track.top
        val bottom = track.bottom
        val paths = listOf(
            Path().apply {
                moveTo(12f, top + 2f)
                cubicTo(160f, top * 0.5f, 240f, top + top * 0.45f, 380f, top - top * 0.25f)
                cubicTo(530f, 5f, 600f, top * 0.5f, 730f, top + 1f)
                cubicTo(850f, top + top * 0.4f, 910f, 5f, 988f, top - 1f)
            },
            Path().apply {
                moveTo(12f, bottom - 1f)
                cubicTo(160f, bottom + 2f, 210f, height - 5f, 350f, bottom + 1f)
                cubicTo(490f, bottom - 3f, 590f, height - 5f, 720f, bottom + 1f)
                cubicTo(850f, bottom - 3f, 920f, bottom + 3f, 988f, bottom - 1f)
            }
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            shader = LinearGradient(0f, 0f, 1000f, 0f,
                intArrayOf(Color.TRANSPARENT,
                    ColorUtils.setAlphaComponent(context.getColor(R.color.widget_gold_silk), 110),
                    ColorUtils.setAlphaComponent(context.getColor(R.color.widget_gold_glow), 150),
                    ColorUtils.setAlphaComponent(context.getColor(R.color.widget_gold_silk), 110),
                    Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        }
        for (path in paths) {
            paint.strokeWidth = 2.4f
            paint.maskFilter = BlurMaskFilter(1.5f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(path, paint)
            paint.maskFilter = null
            paint.strokeWidth = 0.9f
            canvas.drawPath(path, paint)
        }
    }

    private fun mask(context: Context, value: Double?, style: WidgetStyle, size: WidgetSize,
        singleWeekly: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(1000, 48, Bitmap.Config.ARGB_8888)
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
        val metrics = metrics(size, singleWeekly)
        views.setViewVisibility(container, if (visible) View.VISIBLE else View.GONE)
        views.setViewLayoutHeight(container, metrics.height.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutMargin(container, RemoteViews.MARGIN_TOP, 0f, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutMargin(container, RemoteViews.MARGIN_BOTTOM, 0f, TypedValue.COMPLEX_UNIT_DIP)
        // Clear children on reapply so quota changes stop the previous effect
        // and refreshes never accumulate animators.
        views.removeAllViews(particles)
        val animated = visible && value != null && value > 0.0
        val full = value == 100.0
        views.setViewVisibility(maskId, if (animated && !full) View.VISIBLE else View.GONE)
        if (animated) {
            views.setViewLayoutHeight(particles, (if (full) metrics.height else metrics.bar).toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutMargin(particles, RemoteViews.MARGIN_TOP, (if (full) 0 else metrics.top).toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            val animation = RemoteViews(context.packageName,
                when {
                    full -> R.layout.widget_gold_progress_particles
                    size == WidgetSize.WIDE -> R.layout.widget_wide_progress_particles
                    else -> R.layout.widget_progress_particles
                })
            if (!full) tintParticles(context, animation, R.id.widget_progress_particles, value!!)
            views.addView(particles, animation)
            // This bitmap masks the unfilled track. It scales with the bar, so
            // particles stay inside the true quota at every launcher width.
            if (!full) views.setImageViewBitmap(maskId, mask(context, value, style, size, singleWeekly))
            else views.setImageViewResource(maskId, 0)
        } else {
            views.setImageViewResource(maskId, 0)
        }
    }
}
