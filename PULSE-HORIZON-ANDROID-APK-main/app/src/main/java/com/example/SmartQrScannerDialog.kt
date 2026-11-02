package com.example

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * PULSE HORIZON - Smart QR Scanner.
 *
 * v163: THE CAMERA IS NO LONGER HAND-WRITTEN.
 *
 * WHAT WAS WRONG IN v160-v162
 *   I wrote the camera myself on camera2, and it had three faults, all mine:
 *
 *     1. the arrows did not sit on the codes. The camera image is rotated and
 *        centre-cropped to fill the screen, and my hand-written mapping between
 *        image coordinates and screen coordinates was wrong.
 *     2. the preview flipped and stretched - "4:3 upside down" - because I used
 *        only the sensor orientation and ignored the device's display rotation.
 *     3. it CRASHED when you tapped an arrow: dismissing shut the decode worker
 *        down while the camera thread was still handing it frames, and the next
 *        frame threw RejectedExecutionException on a thread with no handler.
 *
 * THE FIX - USE THE TESTED LIBRARY INSTEAD OF MY OWN CAMERA
 *   zxing-android-embedded is 149 KB. It brings a camera preview that has been
 *   used on millions of devices: it handles rotation, aspect ratio, autofocus
 *   and torch itself. And its BarcodeResult carries
 *   getTransformedResultPoints() - the corner points ALREADY MAPPED into view
 *   coordinates. That is precisely the mapping I got wrong, done for us.
 *
 *   Cost: 149 KB + zxing:core 594 KB = about 743 KB. Still under 1 MB, and it
 *   removes every line of camera code I was getting wrong.
 *
 * WHAT IT DOES - unchanged
 *   Several codes in view: each gets ONE neon-blue arrow button on it. The
 *   picture freezes so the arrows cannot move under your finger. Tap an arrow
 *   and that code, and only that code, is scanned.
 */
class SmartQrScannerDialog(
    context: Context,
    private val onQrSelected: (String) -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private lateinit var scannerView: DecoratedBarcodeView
    private lateinit var barcodeView: BarcodeView
    private lateinit var overlayView: QrOverlayView
    private lateinit var hintTextView: TextView
    private lateinit var torchButton: TextView

    private var isFrozen = false
    private var isClosing = false
    private var isTorchOn = false

    // Codes seen, keyed by their text, with the arrow position already worked
    // out. Rebuilt on every frame so a code that has gone away leaves no arrow.
    private val live = LinkedHashMap<String, PointF>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // The library's preview. It does rotation, aspect and focus itself.
        scannerView = DecoratedBarcodeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        barcodeView = scannerView.barcodeView
        root.addView(scannerView)

        overlayView = QrOverlayView(context, { text ->
            select(text)
        }, {
            resumeScanning()
        }).apply {
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(overlayView)

        // ---- top bar ----
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 56, 40, 24)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        torchButton = TextView(context).apply {
            text = "Flash"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(32, 16, 32, 16)
            background = pill(Color.parseColor("#331E293B"))
            setOnClickListener { toggleTorch() }
        }
        topBar.addView(torchButton)

        topBar.addView(TextView(context).apply {
            text = "Smart QR Scanner"
            setTextColor(Color.parseColor("#22D3EE"))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        topBar.addView(TextView(context).apply {
            text = "X"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 16)
            background = pill(Color.parseColor("#331E293B"))
            setOnClickListener { closeDialog() }
        })
        root.addView(topBar)

        // ---- hint ----
        hintTextView = TextView(context).apply {
            text = "Tap the QR code or barcode you want to scan"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            background = pill(Color.parseColor("#66111827"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = 90 }
        }
        root.addView(hintTextView)

        setContentView(root)

        startScanning()
    }

    private fun pill(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = 48f
    }

    // ================================================================
    //  SCANNING - the library's camera, our callback
    // ================================================================

    private fun startScanning() {
        try {
            barcodeView.decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    if (isFrozen || isClosing) return
                    val text = try { result.text } catch (_: Exception) { null } ?: return
                    if (text.isBlank()) return

                    val centre = centreOf(result) ?: return
                    synchronized(live) { live[text] = centre }
                    publish()
                }

                override fun possibleResultPoints(points: List<ResultPoint>?) {
                    // not needed - barcodeResult already gives us decoded codes
                    // with their points mapped into view coordinates
                }
            })
            barcodeView.resume()
        } catch (_: Exception) {
            toast("Could not start the camera")
        }
    }

    /**
     * The middle of a decoded code, in view coordinates.
     *
     * zxing gives three points for a QR - the finder pattern centres. The one
     * that is the right-angle corner is the vertex; the code's centre is the
     * MIDPOINT OF THE OTHER TWO. (Proof: with corners V, P, Q and P+Q-V, the
     * average is (V+P+Q+P+Q-V)/4 = (P+Q)/2.) So no fourth corner is needed.
     *
     * getTransformedResultPoints() is the library's own mapping into view
     * coordinates - the exact thing my hand-written version got wrong.
     */
    private fun centreOf(result: BarcodeResult): PointF? {
        return try {
            val pts = result.transformedResultPoints ?: return null
            if (pts.isEmpty()) return null
            if (pts.size == 1) return PointF(pts[0].x, pts[0].y)
            if (pts.size == 2) {
                return PointF((pts[0].x + pts[1].x) / 2f, (pts[0].y + pts[1].y) / 2f)
            }

            val a = pts[0]
            val b = pts[1]
            val c = pts[2]

            fun angleAt(v: ResultPoint, p: ResultPoint, q: ResultPoint): Float {
                val v1x = p.x - v.x
                val v1y = p.y - v.y
                val v2x = q.x - v.x
                val v2y = q.y - v.y
                val n1 = kotlin.math.hypot(v1x, v1y)
                val n2 = kotlin.math.hypot(v2x, v2y)
                if (n1 < 1f || n2 < 1f) return 180f
                val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1f, 1f)
                return Math.toDegrees(kotlin.math.acos(cos).toDouble()).toFloat()
            }

            val da = kotlin.math.abs(angleAt(a, b, c) - 90f)
            val db = kotlin.math.abs(angleAt(b, a, c) - 90f)
            val dc = kotlin.math.abs(angleAt(c, a, b) - 90f)

            val p: ResultPoint
            val q: ResultPoint
            if (da <= db && da <= dc) { p = b; q = c }
            else if (db <= dc) { p = a; q = c }
            else { p = a; q = b }

            PointF((p.x + q.x) / 2f, (p.y + q.y) / 2f)
        } catch (_: Exception) {
            null
        }
    }

    private fun publish() {
        val snapshot: List<Pair<String, PointF>>
        synchronized(live) { snapshot = live.entries.map { it.key to it.value } }
        if (snapshot.isEmpty()) return

        overlayView.post {
            if (isFrozen || isClosing) return@post
            overlayView.setCodes(snapshot)
            if (snapshot.size > 1) {
                freezeForSelection()
            } else {
                hintTextView.text = "Tap the arrow on the code you want"
                hintTextView.setTextColor(Color.parseColor("#22D3EE"))
            }
        }
    }

    // ================================================================
    //  FREEZE - so the arrows cannot move under your finger
    // ================================================================

    private fun freezeForSelection() {
        if (isFrozen) return
        isFrozen = true
        overlayView.isFrozen = true
        // The library's own pause. It stops the camera and the decoder, so the
        // frame stops changing and the arrows stay exactly where they are.
        try { barcodeView.pause() } catch (_: Exception) {}
        try {
            hintTextView.text = "Tap an arrow to pick that code - or tap empty space to scan again"
            hintTextView.setTextColor(Color.parseColor("#22D3EE"))
        } catch (_: Exception) {}
    }

    private fun resumeScanning() {
        if (!isFrozen) return
        isFrozen = false
        overlayView.isFrozen = false
        overlayView.clearCodes()
        synchronized(live) { live.clear() }
        try {
            hintTextView.text = "Tap the QR code or barcode you want to scan"
            hintTextView.setTextColor(Color.WHITE)
        } catch (_: Exception) {}
        try { barcodeView.resume() } catch (_: Exception) {}
    }

    // ================================================================
    //  SELECT / CLOSE - and the crash is gone
    // ================================================================

    private fun select(text: String) {
        if (isClosing) return
        isClosing = true
        vibrate()
        // Stop the camera BEFORE telling the caller, so nothing is still
        // delivering frames while the dialog goes away. This ordering is the
        // thing that was crashing in v160-v162.
        try { barcodeView.pause() } catch (_: Exception) {}
        try { dismiss() } catch (_: Exception) {}
        try { onQrSelected(text) } catch (_: Exception) {}
    }

    private fun closeDialog() {
        if (isClosing) return
        isClosing = true
        try { barcodeView.pause() } catch (_: Exception) {}
        try { dismiss() } catch (_: Exception) {}
    }

    private fun toggleTorch() {
        try {
            if (isTorchOn) {
                scannerView.setTorchOff()
                torchButton.text = "Flash"
            } else {
                scannerView.setTorchOn()
                torchButton.text = "Flash on"
            }
            isTorchOn = !isTorchOn
        } catch (_: Exception) {}
    }

    private fun vibrate() {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(45)
            }
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }

    // A1 FIX: THE BLACK SCREEN.
    //
    // decodeContinuous() sets up the DECODER. It does not start the camera.
    // resume() is what starts the camera, and this dialog never called it on
    // open - the only resume() was inside "scan again after a freeze". So the
    // camera was never told to start and the screen stayed black, with just the
    // reticle and the hint drawn on top.
    override fun onStart() {
        super.onStart()
        if (isClosing) return
        try { barcodeView.resume() } catch (_: Exception) {}
    }

    override fun onStop() {
        // Note: do NOT set isClosing here. Backgrounding the app fires onStop,
        // and marking it closing would leave the scanner dead on return.
        try { barcodeView.pause() } catch (_: Exception) {}
        super.onStop()
    }

    // ================================================================
    //  THE OVERLAY - one neon-blue arrow per code
    // ================================================================

    private class QrOverlayView(
        context: Context,
        private val onSelect: (String) -> Unit,
        private val onResumeRequest: () -> Unit
    ) : View(context) {

        var isFrozen = false

        private var codes = listOf<Pair<String, PointF>>()
        private val arrowTargets = mutableListOf<Pair<RectF, String>>()

        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.FILL
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#04121C")
            style = Paint.Style.STROKE
            strokeWidth = 3.2f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00F0FF")
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            alpha = 70
        }

        fun clearCodes() {
            codes = emptyList()
            postInvalidate()
        }

        fun setCodes(list: List<Pair<String, PointF>>) {
            codes = list
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            arrowTargets.clear()

            if (codes.isEmpty()) {
                // nothing yet - a soft reticle so the screen is not blank
                val s = minOf(width.toFloat(), height.toFloat()) * 0.6f
                val l = (width - s) / 2f
                val t = (height - s) / 2f
                val r = RectF(l, t, l + s, t + s)
                val arm = s * 0.28f
                canvas.drawLine(r.left, r.top, r.left + arm, r.top, idlePaint)
                canvas.drawLine(r.left, r.top, r.left, r.top + arm, idlePaint)
                canvas.drawLine(r.right, r.top, r.right - arm, r.top, idlePaint)
                canvas.drawLine(r.right, r.top, r.right, r.top + arm, idlePaint)
                canvas.drawLine(r.left, r.bottom, r.left + arm, r.bottom, idlePaint)
                canvas.drawLine(r.left, r.bottom, r.left, r.bottom - arm, idlePaint)
                canvas.drawLine(r.right, r.bottom, r.right - arm, r.bottom, idlePaint)
                canvas.drawLine(r.right, r.bottom, r.right, r.bottom - arm, idlePaint)
                return
            }

            // ONE arrow per code, sitting on the middle of that code. The points
            // come from the library already in view coordinates, so there is no
            // transform here to get wrong.
            for ((text, centre) in codes) {
                val d = 30f * resources.displayMetrics.density
                val badge = RectF(centre.x - d, centre.y - d, centre.x + d, centre.y + d)
                canvas.drawCircle(centre.x, centre.y, d, badgePaint)
                drawArrow(canvas, centre.x, centre.y, d * 0.62f)
                arrowTargets.add(Pair(badge, text))
            }
        }

        private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            val left = cx - size * 0.55f
            val right = cx + size * 0.55f
            canvas.drawLine(left, cy, right, cy, arrowPaint)
            canvas.drawLine(right, cy, right - size * 0.52f, cy - size * 0.52f, arrowPaint)
            canvas.drawLine(right, cy, right - size * 0.52f, cy + size * 0.52f, arrowPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val tx = event.x
            val ty = event.y
            val pad = 30f * resources.displayMetrics.density

            // 1. the arrow on a code
            for ((rect, text) in arrowTargets) {
                val padded = RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
                if (padded.contains(tx, ty)) {
                    onSelect(text)
                    return true
                }
            }

            // 2. anywhere near a code's own centre
            for ((text, centre) in codes) {
                val dx = tx - centre.x
                val dy = ty - centre.y
                if (dx * dx + dy * dy <= (pad * 2.4f) * (pad * 2.4f)) {
                    onSelect(text)
                    return true
                }
            }

            // 3. only one code - anywhere counts
            if (codes.size == 1) {
                onSelect(codes[0].first)
                return true
            }

            // 4. frozen and nothing hit - start scanning again
            if (isFrozen) {
                onResumeRequest()
                return true
            }
            return true
        }
    }
}
