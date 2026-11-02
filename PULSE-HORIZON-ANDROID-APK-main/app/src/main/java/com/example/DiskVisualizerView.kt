package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DISK VISUALISER
 * ===============
 * Mode 1: A round disk in the centre of the screen with waves rippling around its rim.
 * Mode 2: Disk in centre + Edge glow around the screen.
 *
 * All animations strictly scale every per-tick amount by timeScale (dt / 0.033).
 */
class DiskVisualizerView(context: Context) : View(context) {

    enum class Mode {
        DISK,           // Mode 1: Centre disk only
        DISK_AND_EDGE   // Mode 2: Centre disk + outer screen edge
    }

    var mode: Mode = Mode.DISK
        set(value) {
            field = value
            invalidate()
        }

    // Default rainbow colors matching EdgeGlowView
    private val rainbowColors = intArrayOf(
        Color.TRANSPARENT,
        Color.TRANSPARENT,
        Color.argb(0, 255, 0, 80),
        Color.argb(255, 255, 0, 80),     // Neon Red/Pink
        Color.argb(255, 255, 120, 0),    // Orange
        Color.argb(255, 255, 230, 0),    // Yellow
        Color.argb(255, 0, 255, 120),    // Green
        Color.argb(255, 0, 220, 255),    // Cyan
        Color.argb(255, 60, 80, 255),    // Blue
        Color.argb(255, 200, 0, 255),    // Purple/Magenta
        Color.argb(0, 200, 0, 255),      // Fade out
        Color.TRANSPARENT
    )

    private val positions = floatArrayOf(
        0.00f, 0.04f, 0.08f, 0.12f, 0.16f, 0.20f, 0.24f, 0.28f, 0.32f, 0.36f, 0.40f, 1.00f
    )

    // Colour control: null = default rainbow; Int = custom single color picked by user
    var customColor: Int? = null
        set(value) {
            field = value
            rebuildShaders()
            invalidate()
        }

    // Vibration slider multiplier: default 1.0f (0.0f to 2.5f)
    var vibrationMultiplier: Float = 1.0f

    // Logo in the centre of the disk, rotates with the disk
    var logoBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var timeScale: Float = 1.0f

    // Painting tools
    private val diskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0C0C12") // Dark stage
    }
    private val diskInnerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#222230")
        strokeWidth = 2f
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Outer Edge glow paints for Mode 2
    private val edgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val edgeWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    private val matrix = Matrix()
    private val edgeMatrix = Matrix()
    private var diskSweepShader: SweepGradient? = null
    private var edgeSweepShader: SweepGradient? = null

    // Wave state variables
    private var travelAngle = 0f
    private var wavePhase = 0f
    private var tremorPhase = 0f
    private var windLevel = 0f
    private var lastBeat = 0f
    private var gust = 0f
    private var baseThickness = 0f

    // Fixed pre-allocated wave geometry buffers (NO per-frame allocation)
    private val WAVE_N = 220
    private val xs = FloatArray(WAVE_N)     // The calm circular rim
    private val ys = FloatArray(WAVE_N)
    private val oxs = FloatArray(WAVE_N)    // The risen surface
    private val oys = FloatArray(WAVE_N)

    // Outer screen edge geometry buffers for Mode 2
    private val edgeXs = FloatArray(WAVE_N)
    private val edgeYs = FloatArray(WAVE_N)
    private val edgeIxs = FloatArray(WAVE_N)
    private val edgeIys = FloatArray(WAVE_N)

    private val wavePath = Path()
    private val linePath = Path()
    private val edgeWavePath = Path()
    private val edgeLinePath = Path()
    private val logoClipPath = Path()
    private val logoMatrix = Matrix()

    // Callback on beat hit for haptics
    var onBeatHitListener: ((strength: Float) -> Unit)? = null

    init {
        baseThickness = 12f * resources.displayMetrics.density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = h / 2f

        val colors = if (customColor != null) {
            val c = customColor!!
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.argb(0, r, g, b),
                Color.argb(255, r, g, b),
                Color.argb(220, (r * 0.8f).toInt(), (g * 0.8f).toInt(), (b * 0.8f).toInt()),
                Color.argb(255, (r * 1.1f).coerceAtMost(255f).toInt(), (g * 1.1f).coerceAtMost(255f).toInt(), (b * 1.1f).coerceAtMost(255f).toInt()),
                Color.argb(180, r, g, b),
                Color.argb(255, r, g, b),
                Color.argb(240, (r * 0.9f).toInt(), (g * 0.9f).toInt(), (b * 0.9f).toInt()),
                Color.argb(255, r, g, b),
                Color.argb(0, r, g, b),
                Color.TRANSPARENT
            )
        } else {
            rainbowColors
        }

        val dShader = SweepGradient(cx, cy, colors, positions)
        diskSweepShader = dShader
        strokePaint.shader = dShader
        glowPaint.shader = dShader
        wavePaint.shader = dShader

        val eShader = SweepGradient(cx, cy, colors, positions)
        edgeSweepShader = eShader
        edgeStrokePaint.shader = eShader
        edgeGlowPaint.shader = eShader
        edgeWavePaint.shader = eShader
    }

    /**
     * Advance animation frame with beat and pulse.
     * Every per-tick advancement is scaled by timeScale (dt / 0.033).
     */
    fun updateWave(beat: Float, pulse: Float) {
        // Disk rotation: 3.2 deg per tick scaled by timeScale
        travelAngle = (travelAngle + 3.2f * timeScale) % 360f

        val cx = width / 2f
        val cy = height / 2f
        matrix.setRotate(travelAngle, cx, cy)
        diskSweepShader?.setLocalMatrix(matrix)

        if (mode == Mode.DISK_AND_EDGE) {
            edgeMatrix.setRotate(travelAngle, cx, cy)
            edgeSweepShader?.setLocalMatrix(edgeMatrix)
        }

        wavePhase += (0.045f + 0.11f * beat) * timeScale
        tremorPhase = (tremorPhase + 2.073f * timeScale) % 6.2831855f

        val rise = (beat - lastBeat) / (if (timeScale < 0.05f) 0.05f else timeScale)
        if (rise > 0.06f) {
            gust = (gust + rise * 2.4f).coerceAtMost(1.3f)
            if (tremorPhase > 1.0f) tremorPhase = 0f
            onBeatHitListener?.invoke(beat)
        }
        lastBeat = beat
        gust *= Math.pow(0.82, timeScale.toDouble()).toFloat()
        windLevel = beat

        val thickness = baseThickness * (0.48f + 1.15f * beat)
        strokePaint.strokeWidth = thickness
        glowPaint.strokeWidth = thickness * 1.8f
        edgeStrokePaint.strokeWidth = thickness
        edgeGlowPaint.strokeWidth = thickness * 1.8f

        val alphaInt = (255 * pulse).toInt().coerceIn(0, 255)
        strokePaint.alpha = alphaInt
        glowPaint.alpha = (alphaInt * 0.45f).toInt()
        wavePaint.alpha = (alphaInt * 0.85f).toInt()

        edgeStrokePaint.alpha = alphaInt
        edgeGlowPaint.alpha = (alphaInt * 0.45f).toInt()
        edgeWavePaint.alpha = (alphaInt * 0.85f).toInt()

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val cy = height / 2f

        // Draw deep dark background
        canvas.drawColor(Color.parseColor("#09090D"))

        // --- MODE 2: DRAW OUTER SCREEN EDGE GLOW FIRST (if enabled) ---
        if (mode == Mode.DISK_AND_EDGE) {
            drawOuterEdgeGlow(canvas, cx, cy)
        }

        // --- DRAW THE DISK IN THE CENTRE OF THE SCREEN ---
        drawDiskVisualizer(canvas, cx, cy)
    }

    private fun drawDiskVisualizer(canvas: Canvas, cx: Float, cy: Float) {
        val diskRadius = minOf(width, height) * 0.28f
        if (diskRadius <= 10f) return

        val amp = baseThickness * (0.35f + 2.4f * (windLevel + gust))
        val tremor = baseThickness * 0.90f * vibrationMultiplier * windLevel *
            sin(tremorPhase.toDouble()).toFloat()

        var i = 0
        while (i < WAVE_N) {
            val u = i.toFloat() / WAVE_N
            val th = u * 6.2831855f
            val c = cos(th.toDouble()).toFloat()
            val sn = sin(th.toDouble()).toFloat()

            // THE CALM DISK RIM (exact circle)
            val px = cx + diskRadius * c
            val py = cy + diskRadius * sn
            xs[i] = px
            ys[i] = py

            // THE RISING SURFACE AROUND THE RIM
            val s1 = sin((wavePhase + u * 12.566f).toDouble()).toFloat()
            val s2 = sin((wavePhase * 1.7f + u * 25.0f + 1.3f).toDouble()).toFloat()
            val rise = amp *
                (0.55f + 0.45f * (s1 * s1 * s1)) *
                (0.60f + 0.40f * (s2 * s2 * s2))

            // Waves swell outwards from the disk rim
            val totalRise = rise + tremor
            oxs[i] = cx + (diskRadius + totalRise) * c
            oys[i] = cy + (diskRadius + totalRise) * sn
            i++
        }

        // 1. Draw the filled wave rim
        wavePath.reset()
        wavePath.moveTo(xs[0], ys[0])
        i = 1
        while (i < WAVE_N) {
            wavePath.lineTo(xs[i], ys[i])
            i++
        }
        i = WAVE_N - 1
        while (i >= 0) {
            wavePath.lineTo(oxs[i], oys[i])
            i--
        }
        wavePath.close()
        canvas.drawPath(wavePath, wavePaint)

        // 2. Draw the calm base circular rim
        linePath.reset()
        linePath.moveTo(xs[0], ys[0])
        i = 1
        while (i < WAVE_N) {
            linePath.lineTo(xs[i], ys[i])
            i++
        }
        linePath.close()
        canvas.drawPath(linePath, glowPaint)
        canvas.drawPath(linePath, strokePaint)

        // 3. Draw the solid dark disk body
        canvas.drawCircle(cx, cy, diskRadius - 1f, diskPaint)

        // Subtle decorative concentric grooving (vinyl look)
        canvas.drawCircle(cx, cy, diskRadius * 0.82f, diskInnerRingPaint)
        canvas.drawCircle(cx, cy, diskRadius * 0.64f, diskInnerRingPaint)

        // 4. Draw Logo in the CENTRE of the disk (ROTATES WITH THE DISK)
        val logoR = diskRadius * 0.42f
        val bmp = logoBitmap
        if (bmp != null && !bmp.isRecycled) {
            canvas.save()
            logoClipPath.reset()
            logoClipPath.addCircle(cx, cy, logoR, Path.Direction.CW)
            canvas.clipPath(logoClipPath)

            // Rotate logo with the disk
            canvas.rotate(travelAngle, cx, cy)

            val scale = (logoR * 2f) / maxOf(bmp.width, bmp.height).toFloat()
            val drawW = bmp.width * scale
            val drawH = bmp.height * scale
            val left = cx - drawW / 2f
            val top = cy - drawH / 2f

            logoMatrix.reset()
            logoMatrix.postScale(scale, scale)
            logoMatrix.postTranslate(left, top)
            canvas.drawBitmap(bmp, left, top, logoPaint)

            canvas.restore()

            // Outer ring for logo frame
            canvas.drawCircle(cx, cy, logoR, strokePaint)
        } else {
            // Default sleek centre hub
            canvas.save()
            canvas.rotate(travelAngle, cx, cy)
            canvas.drawCircle(cx, cy, logoR * 0.5f, diskInnerRingPaint)
            canvas.drawCircle(cx, cy, 6f * resources.displayMetrics.density, strokePaint)
            canvas.restore()
        }
    }

    private fun drawOuterEdgeGlow(canvas: Canvas, cx: Float, cy: Float) {
        val inset = edgeGlowPaint.strokeWidth / 2f
        val a = width / 2f - inset
        val b = height / 2f - inset
        if (a <= 1f || b <= 1f) return
        val ex = 2.0 / 6.0

        val amp = baseThickness * (0.35f + 2.4f * (windLevel + gust))
        val tremor = baseThickness * 0.90f * vibrationMultiplier * windLevel *
            sin(tremorPhase.toDouble()).toFloat()

        var i = 0
        while (i < WAVE_N) {
            val u = i.toFloat() / WAVE_N
            val th = u * 6.2831855f
            val c = cos(th.toDouble())
            val sn = sin(th.toDouble())
            val px = cx + (a * Math.signum(c) * Math.pow(abs(c), ex)).toFloat()
            val py = cy + (b * Math.signum(sn) * Math.pow(abs(sn), ex)).toFloat()

            edgeXs[i] = px
            edgeYs[i] = py

            val dx = px - cx
            val dy = py - cy
            val len = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val nx = if (len > 0.001f) dx / len else 0f
            val ny = if (len > 0.001f) dy / len else 0f

            val s1 = sin((wavePhase + u * 12.566f).toDouble()).toFloat()
            val s2 = sin((wavePhase * 1.7f + u * 25.0f + 1.3f).toDouble()).toFloat()
            val rise = amp *
                (0.55f + 0.45f * (s1 * s1 * s1)) *
                (0.60f + 0.40f * (s2 * s2 * s2))

            edgeIxs[i] = px - nx * (rise + tremor)
            edgeIys[i] = py - ny * (rise + tremor)
            i++
        }

        edgeWavePath.reset()
        edgeWavePath.moveTo(edgeXs[0], edgeYs[0])
        i = 1
        while (i < WAVE_N) {
            edgeWavePath.lineTo(edgeXs[i], edgeYs[i])
            i++
        }
        i = WAVE_N - 1
        while (i >= 0) {
            edgeWavePath.lineTo(edgeIxs[i], edgeIys[i])
            i--
        }
        edgeWavePath.close()
        canvas.drawPath(edgeWavePath, edgeWavePaint)

        edgeLinePath.reset()
        edgeLinePath.moveTo(edgeXs[0], edgeYs[0])
        i = 1
        while (i < WAVE_N) {
            edgeLinePath.lineTo(edgeXs[i], edgeYs[i])
            i++
        }
        edgeLinePath.close()
        canvas.drawPath(edgeLinePath, edgeGlowPaint)
        canvas.drawPath(edgeLinePath, edgeStrokePaint)
    }
}
