package com.codexusage.android

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.sin

internal fun resetAnimationUsage(before: Usage?, target: Usage, progress: Float): Usage =
    target.copy(windows = target.windows.map { window ->
        val start = before?.windows?.firstOrNull {
            it.label == window.label && it.seconds == window.seconds && it.additional == window.additional
        }?.remaining
        window.copy(remaining = start?.let { it + (100.0 - it) * progress.coerceIn(0f, 1f) } ?: 100.0)
    })

/** One short, non-interactive overlay; no bitmaps, network assets or animation dependency. */
internal class ResetSuccessEffect(private val root: FrameLayout) {
    private var animator: ValueAnimator? = null
    private var overlay: ResetFireworksView? = null
    val running: Boolean get() = animator != null

    fun start(before: Usage?, target: Usage?, frame: (Usage) -> Unit, finished: () -> Unit) {
        stop()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finished()
            return
        }
        val fireworks = ResetFireworksView(root.context)
        overlay = fireworks
        root.addView(fireworks, FrameLayout.LayoutParams(-1, -1))
        var lastQuotaProgress = -1f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            interpolator = LinearInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                fireworks.progress = progress
                val quotaProgress = (progress * 2f).coerceAtMost(1f)
                if (target != null && quotaProgress != lastQuotaProgress) {
                    lastQuotaProgress = quotaProgress
                    frame(resetAnimationUsage(before, target, quotaProgress))
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.removeView(fireworks)
                    overlay = null
                    animator = null
                    finished()
                }
            })
            start()
        }
    }

    fun stop() {
        animator?.removeAllListeners()
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
        overlay?.let(root::removeView)
        overlay = null
    }
}

internal class ResetFireworksView(context: Context) : View(context) {
    var progress = 0f
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val colors = intArrayOf(0xFFFFCB66.toInt(), 0xFF59DDB9.toInt(), 0xFFFF87AF.toInt(),
        0xFF80BFFF.toInt(), 0xFFD7A0FF.toInt())

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        // Staggered bursts in the upper half keep the quota card readable.
        repeat(5) { burst ->
            val time = ((progress - burst * 0.085f) / 0.65f)
            if (time <= 0f || time >= 1f) return@repeat
            val centerX = width * (0.16f + burst * 0.17f)
            val centerY = height * (0.18f + (burst % 3) * 0.09f)
            val radius = (1f - (1f - time) * (1f - time)) * 88f * density
            paint.color = colors[burst]
            paint.alpha = ((1f - time) * 255).toInt()
            paint.strokeWidth = 2.5f * density
            repeat(24) { particle ->
                val angle = particle * Math.PI * 2 / 24 + burst * 0.3
                val dx = cos(angle).toFloat()
                val dy = sin(angle).toFloat()
                val fall = time * time * 32f * density
                val x = centerX + dx * radius
                val y = centerY + dy * radius + fall
                canvas.drawLine(x - dx * 7f * density, y - dy * 7f * density, x, y, paint)
                paint.color = Color.WHITE
                canvas.drawCircle(x, y, density, paint)
                paint.color = colors[burst]
                paint.alpha = ((1f - time) * 255).toInt()
            }
        }
    }
}
