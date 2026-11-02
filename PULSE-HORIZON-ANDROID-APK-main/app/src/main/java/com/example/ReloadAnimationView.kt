package com.example

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * A lightweight, zero-dependency reload animation view.
 * Displays a smooth floating circular badge with a rotating reload arrow indicator.
 * Provides real-time pull-to-refresh rotation and continuous spinning during hard reload.
 */
class ReloadAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1E293B") // Dark slate background
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.8f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#06B6D4") // Cyan accent
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#06B6D4")
    }

    private val arcRect = RectF()
    private val arrowPath = Path()
    private var pullFraction = 0f
    private var isSpinning = false
    private var spinAngle = 0f
    private var spinAnimator: ValueAnimator? = null

    init {
        visibility = GONE
        elevation = 6f * density
    }

    fun setThemeColors(dark: Boolean) {
        bgPaint.color = if (dark) Color.parseColor("#1E293B") else Color.WHITE
        val accent = if (dark) Color.parseColor("#22D3EE") else Color.parseColor("#0284C7")
        ringPaint.color = accent
        arrowPaint.color = accent
        invalidate()
    }

    fun setPullFraction(fraction: Float) {
        if (isSpinning) return
        pullFraction = fraction.coerceIn(0f, 1f)
        if (pullFraction <= 0.05f) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        alpha = pullFraction.coerceIn(0.2f, 1f)
        val scale = 0.5f + (pullFraction * 0.5f)
        scaleX = scale
        scaleY = scale
        val maxOffset = 54f * density
        translationY = (pullFraction * maxOffset) - (16f * density)
        invalidate()
    }

    fun startSpinning() {
        if (isSpinning) return
        isSpinning = true
        visibility = VISIBLE
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        val restingY = 36f * density
        animate().translationY(restingY).setDuration(150).start()

        spinAnimator?.cancel()
        spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 700L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopSpinning() {
        if (!isSpinning && visibility != VISIBLE) return
        isSpinning = false
        spinAnimator?.cancel()
        spinAnimator = null
        animate()
            .scaleX(0f)
            .scaleY(0f)
            .alpha(0f)
            .translationY(-25f * density)
            .setDuration(220)
            .withEndAction {
                visibility = GONE
                pullFraction = 0f
                spinAngle = 0f
                scaleX = 1f
                scaleY = 1f
            }
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (Math.min(w, h) / 2f) - (3f * density)

        // Draw circular shadow badge
        canvas.drawCircle(cx, cy, radius, bgPaint)

        val strokeR = radius * 0.58f
        arcRect.set(cx - strokeR, cy - strokeR, cx + strokeR, cy + strokeR)

        canvas.save()
        if (isSpinning) {
            canvas.rotate(spinAngle, cx, cy)
            canvas.drawArc(arcRect, 0f, 280f, false, ringPaint)
            drawArrowHead(canvas, cx, cy, strokeR, 280f)
        } else {
            val sweep = (pullFraction * 290f).coerceAtLeast(10f)
            val startAngle = -90f + (pullFraction * 90f)
            canvas.drawArc(arcRect, startAngle, sweep, false, ringPaint)
            if (sweep > 25f) {
                drawArrowHead(canvas, cx, cy, strokeR, startAngle + sweep)
            }
        }
        canvas.restore()
    }

    private fun drawArrowHead(canvas: Canvas, cx: Float, cy: Float, r: Float, angleDeg: Float) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val tipX = cx + r * Math.cos(rad).toFloat()
        val tipY = cy + r * Math.sin(rad).toFloat()

        canvas.save()
        canvas.translate(tipX, tipY)
        canvas.rotate(angleDeg + 90f)

        val arrowSize = 3.8f * density
        arrowPath.reset()
        arrowPath.moveTo(0f, -arrowSize * 1.5f)
        arrowPath.lineTo(-arrowSize, arrowSize * 0.8f)
        arrowPath.lineTo(arrowSize, arrowSize * 0.8f)
        arrowPath.close()

        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()
    }
}
