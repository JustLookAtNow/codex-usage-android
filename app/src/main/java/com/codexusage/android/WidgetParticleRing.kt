package com.codexusage.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.sin

internal object WidgetParticleRing {
    private const val SIDE = 256
    private const val CENTER = 128f
    private fun stroke(size: WidgetSize) = if (size == WidgetSize.WIDE) 40f else 26f
    private fun radius(size: WidgetSize) = 127f - stroke(size) / 2f
    private fun bounds(size: WidgetSize): RectF {
        val radius = radius(size)
        return RectF(CENTER - radius, CENTER - radius, CENTER + radius, CENTER + radius)
    }

    private fun strokePath(value: Double?, size: WidgetSize): Path {
        val line = Path()
        if (value == null || value <= 0.0) return line
        if (value >= 100.0) line.addOval(bounds(size), Path.Direction.CW)
        else line.addArc(bounds(size), -90f, (value * 3.6).toFloat())
        return Path().also { fill ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke(size)
                strokeCap = Paint.Cap.ROUND
            }.getFillPath(line, fill)
        }
    }

    private fun trackColor(context: Context, style: WidgetStyle) =
        context.getColor(if (style == WidgetStyle.GLASS) R.color.widget_glass_track else R.color.widget_track)

    fun render(context: Context, value: Double?, weekly: Boolean, style: WidgetStyle, size: WidgetSize): Bitmap {
        val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor(context, style) }
        canvas.drawPath(strokePath(100.0, size), paint)
        if (value != null && value > 0.0) {
            val normal = context.getColor(if (style == WidgetStyle.GLASS) {
                if (weekly) R.color.widget_glass_weekly else R.color.widget_glass_primary
            } else if (weekly) R.color.widget_weekly else R.color.widget_primary)
            val quota = UsageWidget.widgetQuotaColor(context, value, normal, style)
            val fill = WidgetParticleProgress.fillColor(context, value, quota)
            val filledArc = strokePath(value, size)
            // Use the same material throughout the arc, including its rounded end.
            val bounds = bounds(size)
            paint.shader = LinearGradient(0f, bounds.top, 0f, bounds.bottom,
                ColorUtils.blendARGB(fill, Color.WHITE, 0.1f), fill, Shader.TileMode.CLAMP)
            canvas.drawPath(filledArc, paint)
            paint.shader = null
            canvas.save()
            canvas.clipPath(filledArc)
            val sweep = (value.coerceAtMost(100.0) * 3.6).toFloat()
            for (index in 0 until 24) {
                val degrees = 11f + index * 15f
                if (degrees >= sweep - 8f) break
                val angle = Math.toRadians((degrees - 90f).toDouble())
                val radius = radius(size) + (index % 3 - 1) * 3f
                paint.color = ColorUtils.setAlphaComponent(WidgetParticleProgress.particleColor(context, value),
                    if (index % 4 == 0) 110 else 70)
                canvas.drawCircle(CENTER + cos(angle).toFloat() * radius,
                    CENTER + sin(angle).toFloat() * radius, if (index % 4 == 0) 2.2f else 1.5f, paint)
            }
            if (sweep <= 19f) {
                // Tiny positive quotas still need a visible texture while the
                // launcher pauses animation or moving particles leave the arc.
                val angle = Math.toRadians((-90f + sweep / 2f).toDouble())
                paint.color = ColorUtils.setAlphaComponent(WidgetParticleProgress.particleColor(context, value), 140)
                canvas.drawCircle(CENTER + cos(angle).toFloat() * radius(size),
                    CENTER + sin(angle).toFloat() * radius(size), 2.2f, paint)
            }
            canvas.restore()
        }
        val label = percentage(value)
        paint.apply {
            color = UsageWidget.widgetQuotaColor(context, value,
                context.getColor(if (style == WidgetStyle.GLASS) R.color.widget_glass_text else R.color.widget_text), style)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = 80f
        }
        val textBounds = Rect()
        paint.getTextBounds(label, 0, label.length, textBounds)
        val labelWidth = if (size == WidgetSize.WIDE) 134f else 144f
        val labelHeight = if (size == WidgetSize.WIDE) 96f else 100f
        paint.textSize *= minOf(1f, labelWidth / maxOf(1f, paint.measureText(label)),
            labelHeight / maxOf(1, textBounds.height()).toFloat())
        paint.getTextBounds(label, 0, label.length, textBounds)
        if (value == null) canvas.drawRoundRect(92f, 120f, 164f, 136f, 8f, 8f, paint)
        else canvas.drawText(label, CENTER - textBounds.exactCenterX(), CENTER - textBounds.exactCenterY(), paint)
        return bitmap
    }

    private fun mask(context: Context, value: Double?, style: WidgetStyle, size: WidgetSize): Bitmap {
        val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val unfilled = strokePath(100.0, size).apply { op(strokePath(value, size), Path.Op.DIFFERENCE) }
        Canvas(bitmap).drawPath(unfilled, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor(context, style) })
        return bitmap
    }

    fun apply(context: Context, views: RemoteViews, index: Int, value: Double?, style: WidgetStyle,
        size: WidgetSize, visible: Boolean) {
        val particles = if (index == 0) R.id.widget_primary_ring_particles else R.id.widget_weekly_ring_particles
        val maskId = if (index == 0) R.id.widget_primary_ring_mask else R.id.widget_weekly_ring_mask
        views.removeAllViews(particles)
        val animated = visible && value != null && value > 0.0
        views.setViewVisibility(particles, if (animated) View.VISIBLE else View.GONE)
        views.setViewVisibility(maskId, if (animated) View.VISIBLE else View.GONE)
        if (animated) {
            val animation = RemoteViews(context.packageName,
                if (size == WidgetSize.WIDE) R.layout.widget_wide_ring_particles else R.layout.widget_ring_particles)
            WidgetParticleProgress.tintParticles(context, animation, R.id.widget_ring_particles, value!!)
            views.addView(particles, animation)
            // Both layers fit the same centered square as the ring ImageView.
            // The mask follows the true quota, so resizing never exposes stray dots.
            views.setImageViewBitmap(maskId, mask(context, value, style, size))
        } else views.setImageViewResource(maskId, 0)
    }
}
