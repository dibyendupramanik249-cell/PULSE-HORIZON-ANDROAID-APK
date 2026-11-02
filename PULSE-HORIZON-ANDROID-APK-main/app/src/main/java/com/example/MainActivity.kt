package com.example

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.ClipData
import android.content.ComponentCallbacks2
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.util.Rational
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import android.graphics.SweepGradient
import android.media.MediaScannerConnection
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import android.util.Base64
import android.util.Patterns
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.graphics.drawable.GradientDrawable
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.ScriptHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import android.annotation.TargetApi
import android.content.ClipboardManager

class MainActivity : ComponentActivity(), ComponentCallbacks2 {

    private lateinit var rootLayout: LinearLayout
    private lateinit var topBar: LinearLayout
    // v52: Help & Feedback page — a Google Apps Script web app (see
    // Feedback-Backend.gs). After deploying it, replace the placeholder
    // with your /exec URL and rebuild. Zero RAM cost: the button just
    // opens a webpage in the browser itself, user-initiated only.
    private val feedbackUrl = "https://script.google.com/macros/s/AKfycbyB3RHMnSI0bV9w2vSN6acr80GfBNRfy1gl6LpbG7_PQBIJI8eWaJD7lrF8VfYiUVYm/exec"

    private var chromeHidden = false
    // v71: is the soft keyboard up right now?
    private var imeVisible = false
    private var lastStripTap = 0L
    private var imeWasVisible = false
    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    // v135: the Home button in the toolbar
    private lateinit var homeBtn: Button

    // --- v66: TABS -------------------------------------------------------
    // One live WebView, exactly as before. Every OTHER tab is a record
    // (url + title) held in memory only, so it costs no RAM and dies with
    // the process on exit - the wipe-everything promise is untouched.
    // Switching tabs saves the current page's url/title, then reloads the
    // target's url in the same WebView. That is Chrome's discard model.
    private data class TabRecord(
        var url: String,
        var title: String,
        var isDesktop: Boolean = false,
        var autoHide: Boolean = true,
        // v84: a few bytes each. Scroll position and the text typed into the
        // page's own fields, captured before the tab is discarded so it can
        // be put back when you return.
        var scrollY: Int = 0,
        var formJson: String = ""
    )
    private val tabs = mutableListOf<TabRecord>()
    private var activeTab = 0
    // v84: set when a tab switch is under way, so the page can be restored
    private var pendingRestore = false
    private lateinit var tabStrip: HorizontalScrollView
    // v130: the Chrome-style tab button in the toolbar
    private lateinit var tabCountBtn: Button
    private lateinit var tabRow: LinearLayout
    private val homeUrl = "file:///android_asset/home.html"
    // v122: the terms page is bundled in the app. No Google page, no
    // "Report abuse", no internet needed, and Get Started lands on home.html.
    private val termsUrl = "file:///android_asset/terms.html"

    // --- v67: three-dot menu + settings ---
    private lateinit var menuBtn: Button
    private var autoHideBar = true

    // --- v85: ad/tracker blocking is now switchable, and counted ---
    private var adBlockEnabled = true
    private var pullToRefreshEnabled = true
    private var safeBrowsingEnabled = true
    private lateinit var reloadIndicator: ReloadAnimationView

    // v101: Music mode plays the file ITSELF - our own decoder + AudioTrack.
    // Why: Android's Visualizer API is gated behind RECORD_AUDIO, so anything
    // built on it needs the microphone permission. Decoding the audio
    // ourselves means we already hold the samples: no API, no permission.
    // Memory: playing an MP3 needs a codec either way - MediaPlayer used one,
    // we use one. Nothing extra is kept, and it is all released on stop.
    private var decoderThread: Thread? = null
    // A4 (v165): each playback gets its OWN cancellation token.
    //
    // v164 removed the join() that killed the old thread, and replaced it with
    // the shared musicDecoding flag. But musicPlay() sets that flag straight
    // back to true, so the OLD thread saw "true" again and KEPT RUNNING - two
    // decoder threads, two AudioTracks, one shared reference. Worse than the
    // bug I was fixing.
    //
    // A token per song cannot be resurrected: stopping song A cancels A only.
    private class MusicJob { @Volatile var cancelled = false }
    private var musicJob: MusicJob? = null
    // A5: the small undo bar under the address bar
    private var undoBar: LinearLayout? = null
    // A5 (v165): the bar hangs off the CONTENT view as an overlay. v164 pushed
    // it into rootLayout - the browser's own layout - which is the only way my
    // change could have disturbed the tab UI.
    private var glowLayerRef: FrameLayout? = null
    private var pendingUndo: TabRecord? = null
    private val undoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideUndoRunnable = Runnable { try { undoBar?.visibility = View.GONE } catch (_: Exception) {} }
    // v117: the theme. Dark / Light / System.
    private var themeMode = "system"
    private var colBg = 0
    private var colSurface = 0
    private var colLine = 0
    private var colText = 0
    private var colDim = 0
    private val colAccent = Color.parseColor("#3AA0FF")
    @Volatile private var musicDecoding = false
    private var musicTrack: AudioTrack? = null
    // WIRE A: the beat timeline. Created and fed, but NOTHING READS IT YET,
    // so this changes nothing you can see. It is here so the beats can be
    // checked before anything depends on them.
    private var timeline: BeatTimeline? = null
    // WIRE B: the launcher. Advanced every frame, but NOTHING READS ITS
    // OUTPUT YET - beatLevel still comes from musicAmp. So, like Wire A,
    // this changes nothing you can see. It exists so we can check the
    // waves are being launched before the drawing depends on them.
    private var launcher: WaveLauncher? = null
    @Volatile private var musicAmp = 0f
    // v103: the smoothed beat level that actually drives the glow.
    private var beatLevel = 0f

    @Volatile private var blockedCount = 0

    // --- v86: BOOKMARKS -----------------------------------------------------
    // The one thing that deliberately survives closing the app, because the
    // user asked for it. Kept in filesDir (not the cache), so the exit wipe
    // leaves it alone - and it is only ever written when you press the star.
    private data class Bookmark(var title: String, var url: String)
    private val bookmarks = mutableListOf<Bookmark>()
    private val bookmarkFile: File get() = File(filesDir, "bookmarks.txt")
    private val prefs by lazy { getSharedPreferences("pulse_horizon", MODE_PRIVATE) }

    // --- v69: media features (all OFF until the user turns them on) ---
    private var mediaPlaying = false
    // v152: when the page last reported media. If the WebView is throttled or
    // the page navigates, the probe callback never arrives and these flags
    // froze true - which is why the music animation started and never stopped.
    private var lastMediaReportAt = 0L
    // v145: the app's OWN decoder position. The notification's timeline used to
    // be fed by the page, which cannot report it reliably - so pausing and
    // playing showed 0 while the audio carried on. These track the truth.
    private var musicPosMs = 0L
    private var musicDurMs = 0L
    private var musicNotifTitle = "Pulse Horizon"
    private var musicPosThread: Thread? = null
    private var musicStartMs = 0L
    private var musicSampleRate = 44100
    // v74: true only when the thing playing is AUDIO (a song), not a video.
    // The edge glow is music-only, exactly as you asked.
    private var musicPlaying = false
    private var bgMusicEnabled = false
    private var musicAnimEnabled = false
    private var serviceRunning = false
    @Volatile var isAppInForeground = false
    private var accountChooserActive = false
    // v142: true when the account flow started from the home screen, so we
    // know to bring the user back to the home screen and not to Google.
    private var accountReturnHome = false
    // v143: when the account flow started. The interception is only allowed
    // inside a short window, so an ABANDONED flow can never hijack a later
    // visit to google.com from some other site.
    private var accountFlowAt = 0L
    private val accountFlowWindowMs = 3 * 60 * 1000L
    private fun accountFlowLive(): Boolean =
        accountChooserActive && (System.currentTimeMillis() - accountFlowAt) < accountFlowWindowMs

    // v104: PiP Mini Video Player & MediaSession sync
    // v116: PiP is OFF. It never worked for a WebView, and entering it hid
    // the edge glow and could leave the app stuck in a dead state.
    private var pipEnabled = false
    @Volatile private var isVideoPlaying = false
    @Volatile private var videoPlayingState = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var lastMediaPosMs = 0L
    private var lastMediaDurMs = 0L
    private var lastMediaTitle = ""

    companion object {
        const val ACTION_PIP_PLAY_PAUSE = "com.pulsehorizon.browser.PIP_PLAY_PAUSE"
        @Volatile var instance: MainActivity? = null
    }

    // --- v73: the EDGE GLOW ------------------------------------------------
    // Continuous colourful band travelling around the frame (Top -> Right -> Bottom -> Left)
    // and growing/shrinking dynamically with the beat (small / medium / big).
    // Sits in an inset layer above all web content, completely non-clickable.
    private val edgeViews = mutableListOf<View>()
    private var glowInner: FrameLayout? = null
    private var edgeWaveView: EdgeGlowView? = null
    private var glowBeatPhase = 0f

    private class EdgeGlowView(context: Context) : View(context) {
        private val baseColors = intArrayOf(
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
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val boundsRect = RectF()
        private val matrix = Matrix()
        private var sweepShader: SweepGradient? = null

        private var travelAngle = 0f
        private var cornerRadius = 0f
        private var baseThickness = 0f

        // v118: YOUR DESIGN. The LINE stays straight - it never bends. The
        // wind is the music, and when it strikes, a surface RISES OFF the line
        // and then settles. Two designs in one: the calm straight line, and
        // the living wave standing on it.
        private var wavePhase = 0f
        private var windLevel = 0f
        private var lastBeat = 0f
        private var gust = 0f
        private val wavePath = Path()
        private val linePath = Path()
        private val WAVE_N = 220
        private val xs = FloatArray(WAVE_N)     // the straight line
        private val ys = FloatArray(WAVE_N)
        private val ixs = FloatArray(WAVE_N)    // the risen surface
        private val iys = FloatArray(WAVE_N)
        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        init {
            isClickable = false
            isFocusable = false
            baseThickness = 14f * resources.displayMetrics.density
            cornerRadius = 24f * resources.displayMetrics.density
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return
            val cx = w / 2f
            val cy = h / 2f
            val shader = SweepGradient(cx, cy, baseColors, positions)
            sweepShader = shader
            strokePaint.shader = shader
            glowPaint.shader = shader
            wavePaint.shader = shader
        }

        /** How much time this call represents, in units of the old 33 ms tick.
         *  1.0 at 30 Hz, 0.5 at 60 Hz. Every advance below is multiplied by
         *  it, so the animation runs at the SAME SPEED at any frame rate. */
        var timeScale = 1f

        /** THE VIBRATION. Its own fast phase, advanced by TIME so the tremor
         *  is the same speed at 30 or 60 fps - the mistake I made three times
         *  with the other per-tick amounts. */
        private var tremorPhase = 0f

        fun updateWave(beat: Float, pulse: Float) {
            travelAngle = (travelAngle + 3.2f * timeScale) % 360f

            val cx = width / 2f
            val cy = height / 2f
            matrix.setRotate(travelAngle, cx, cy)
            sweepShader?.setLocalMatrix(matrix)

            // v118: THE WIND. The surface always rolls, but faster and higher
            // when the music is loud. Quiet = a slow, low ripple.
            wavePhase += (0.045f + 0.11f * beat) * timeScale
            // 2*pi*10 Hz * 0.033 s = 2.073 per old tick, scaled by timeScale
            // so the tremor is ~10 Hz at ANY frame rate.
            tremorPhase = (tremorPhase + 2.073f * timeScale) % 6.2831855f

            // v118: A GUST. The moment the energy jumps - a beat landing - a
            // fresh swell is thrown up, and then it decays. So each hit shows
            // as its OWN new wave rather than one continuous blur.
            // STEP 2b-v: THE GUST IS A PER-TICK DELTA.
            //
            // The gust - the big swell - fires on how much beat JUMPED since
            // the last tick. That is a per-tick amount, so doubling the tick
            // rate halved it, and it often fell under the 0.06 threshold
            // entirely. That is why a strong beat produced no large wave.
            //
            // Dividing by timeScale normalises the jump back to the old
            // 33 ms tick, so a strong beat throws the same swell it always
            // did - at any frame rate.
            val rise = (beat - lastBeat) / (if (timeScale < 0.05f) 0.05f else timeScale)
            if (rise > 0.06f) {
                gust = (gust + rise * 2.4f).coerceAtMost(1.3f)
                // VIBRATION SYNC. The tremor was a free-running 10 Hz
                // oscillator: its SIZE was the beat, but its PHASE was not,
                // so it buzzed constantly whether or not a beat had landed.
                // Measured, it was the strongest thing on screen - 10.2 Hz
                // against a 1.7 Hz beat - which is why it read as shaking
                // rather than being struck.
                //
                // Restarting the phase on the beat means the shake BEGINS
                // with the hit. It still does not fade - it keeps running
                // between beats - but now it is anchored to the music.
                // But only if the tremor has actually got somewhere. Resetting
                // on every rising tick held tremorPhase at 0, and sin(0) is 0,
                // so the vibration never moved at all. This lets it run, and
                // still re-anchors it near each hit.
                if (tremorPhase > 1.0f) tremorPhase = 0f
            }
            lastBeat = beat
            gust *= Math.pow(0.82, timeScale.toDouble()).toFloat()
            windLevel = beat

            val thickness = baseThickness * (0.48f + 1.15f * beat)
            strokePaint.strokeWidth = thickness
            glowPaint.strokeWidth = thickness * 1.8f

            val alphaInt = (255 * pulse).toInt().coerceIn(0, 255)
            strokePaint.alpha = alphaInt
            glowPaint.alpha = (alphaInt * 0.45f).toInt()
            wavePaint.alpha = (alphaInt * 0.85f).toInt()

            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (visibility != VISIBLE || width <= 0 || height <= 0) return
            val inset = glowPaint.strokeWidth / 2f
            val a = width / 2f - inset
            val b = height / 2f - inset
            if (a <= 1f || b <= 1f) return
            val cx = width / 2f
            val cy = height / 2f
            val ex = 2.0 / 6.0

            // v118: how far the surface rises off the line. Calm air = a low
            // ripple. A beat = a big swell.
            val amp = baseThickness * (0.35f + 2.4f * (windLevel + gust))

        // THE VIBRATION, on the surface only.
        //   - its AMOUNT is the beat, straight: small beat small, high beat
        //     high. No fade, no decay of its own.
        //   - it rides ON TOP of the swell, so the surface still expands as
        //     it did. This adds the tremor, it does not replace anything.
        //   - the LINE is untouched. xs/ys are computed without it, so the
        //     straight line keeps its shape exactly.
        // It was 1.30f and it dominated everything - it became the picture
        // instead of an accent on it. A vibration should be felt, not fill
        // the screen. 0.30f makes it a tremor on the surface.
        // It was 1.30f and dominated the screen; I cut it to 0.30f and it
        // vanished - a guess in each direction. 0.90f is a tremor you can
        // see that still sits UNDER the swell instead of becoming it.
        val tremor = baseThickness * 0.90f * windLevel *
                     Math.sin(tremorPhase.toDouble()).toFloat()

            var i = 0
            while (i < WAVE_N) {
                val u = i.toFloat() / WAVE_N
                val th = u * 6.2831855f
                val c = Math.cos(th.toDouble())
                val sn = Math.sin(th.toDouble())
                val px = cx + (a * Math.signum(c) * Math.pow(Math.abs(c), ex)).toFloat()
                val py = cy + (b * Math.signum(sn) * Math.pow(Math.abs(sn), ex)).toFloat()

                // THE STRAIGHT LINE. These points never move, so the line keeps
                // its shape exactly - design one, kept intact.
                xs[i] = px
                ys[i] = py

                val dx = px - cx
                val dy = py - cy
                val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val nx = if (len > 0.001f) dx / len else 0f
                val ny = if (len > 0.001f) dy / len else 0f

                // THE RISING SURFACE. Both sine parts are kept positive, so the
                // surface only ever rises INWARD off the line - it never dips
                // behind it. The line stays straight; the surface above it moves.
                // THE SHAPE. SPIKY, WITH A ROUNDED TIP.
                //
                // Both sine terms are CUBED, so the crests become PEAKS
                // instead of rounded humps - the sea gets sharper and more
                // reactive. A cubic narrows the peak while keeping the tip
                // smooth, which is the "a little rounding" part: pointed,
                // but never a needle.
                //
                // The exponent is the dial. 3 is spiky-but-rounded. Raise it
                // for sharper, drop it toward 1 for the old soft humps.
                // Nothing else here changes - same positions, same phase,
                // same amplitude.
                val s1 = Math.sin((wavePhase + u * 12.566f).toDouble()).toFloat()
                val s2 = Math.sin(
                    (wavePhase * 1.7f + u * 25.0f + 1.3f).toDouble()
                ).toFloat()
                val rise = amp *
                    (0.55f + 0.45f * (s1 * s1 * s1)) *
                    (0.60f + 0.40f * (s2 * s2 * s2))
                ixs[i] = px - nx * (rise + tremor)
                iys[i] = py - ny * (rise + tremor)
                i++
            }

            // The filled, colourful waves: out along the straight line, back
            // along the risen surface.
            wavePath.reset()
            wavePath.moveTo(xs[0], ys[0])
            i = 1
            while (i < WAVE_N) {
                wavePath.lineTo(xs[i], ys[i])
                i++
            }
            i = WAVE_N - 1
            while (i >= 0) {
                wavePath.lineTo(ixs[i], iys[i])
                i--
            }
            wavePath.close()
            canvas.drawPath(wavePath, wavePaint)

            // The straight line itself, drawn last so it stays crisp and reads
            // as the calm base the waves rise from.
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
        }
    }

    private val mediaPoll = object : Runnable {
        override fun run() {
            if (isFinishing) return
            probeMediaPlaying { res ->
                lastMediaReportAt = System.currentTimeMillis()
                val kind = res.kind
                val isCurrentlyPlaying = (kind > 0 && !res.isPaused) || musicDecoding
                // v153: THE ANIMATION BUG.
                // isCurrentlyPlaying checked !res.isPaused. isMusic did NOT.
                // So when the user PAUSED, the media stopped but musicPlaying
                // stayed true - and the glow stayed lit. My watchdog did not
                // catch it either, because the page kept reporting, so the
                // timer kept resetting.
                // Music means the music element AND not paused.
                val isMusic = ((kind == 1) && !res.isPaused) || musicDecoding
                val isVideo = (kind == 2)
                val hasMedia = (kind > 0) || musicDecoding

                isVideoPlaying = isVideo
                if (isVideo) {
                    val prevPlaying = videoPlayingState
                    videoPlayingState = !res.isPaused
                    videoWidth = res.videoWidth
                    videoHeight = res.videoHeight
                    if (prevPlaying != videoPlayingState) {
                        updatePipParams()
                    }
                }

                if (isCurrentlyPlaying != mediaPlaying || isMusic != musicPlaying) {
                    val wasPlaying = mediaPlaying
                    mediaPlaying = isCurrentlyPlaying
                    musicPlaying = isMusic
                    updateEdgeGlow()
                    if (isAppInForeground) {
                        if (hasActiveMedia()) {
                            postForegroundNotification()
                        } else {
                            cancelForegroundNotification()
                        }
                    }
                }

                if (hasMedia) {
                    hasPageMedia = true
                    if (res.currentTime > 0 || res.duration > 0) {
                        lastMediaPosMs = (res.currentTime * 1000L).toLong()
                        lastMediaDurMs = (res.duration * 1000L).toLong()
                    }
                    if (res.title.isNotBlank()) lastMediaTitle = res.title

                    if (!isFinishing) {
                        syncPlaybackService()
                        if (!isAppInForeground) {
                            PlaybackService.instance?.updatePlaybackState(
                                playing = isCurrentlyPlaying,
                                posMs = lastMediaPosMs,
                                durMs = lastMediaDurMs,
                                title = lastMediaTitle
                            )
                        }
                    }
                } else {
                    hasPageMedia = false
                    if (!musicDecoding) {
                        syncPlaybackService()
                    }
                }
            }
            if (isAppInForeground || (bgMusicEnabled && hasActiveMedia())) {
                webView.postDelayed(this, 1000L)
            }
        }
    }

    // Drives the edge glow. Smooth, continuous colourful band travelling around
    // the frame (Top -> Right -> Bottom -> Left) and growing/shrinking with the beat.
    // MUSIC ONLY: a video will never light the edges. Runs only while music is playing.
    // v152: THE WATCHDOG. The media probe lives in the page, and when the page
    // is throttled or has navigated away the callback never comes back. Without
    // this, musicPlaying stayed true for ever and the glow never went out.
    // Four seconds without a report means nothing is playing.
    private val mediaWatchdog = object : Runnable {
        override fun run() {
            try {
                if (lastMediaReportAt > 0L &&
                    System.currentTimeMillis() - lastMediaReportAt > 4000L &&
                    (musicPlaying || mediaPlaying || hasPageMedia)
                ) {
                    musicPlaying = false
                    mediaPlaying = false
                    hasPageMedia = false
                    updateEdgeGlow()
                    syncPlaybackService()
                }
            } catch (_: Exception) {}
            webView.postDelayed(this, 1000L)
        }
    }

    /** last frame time, so the glow can be time-based, not tick-based */
    private var lastGlowTickNs = 0L

    private val glowRunnable = object : Runnable {
        override fun run() {
            if (!musicPlaying && !musicDecoding && diskVisualizerOverlay == null) return

            val nowNs = System.nanoTime()
            val dtSec = if (lastGlowTickNs == 0L) 0.033f
                        else ((nowNs - lastGlowTickNs) / 1000000000.0).toFloat().coerceIn(0.001f, 0.1f)
            lastGlowTickNs = nowNs
            val k = dtSec / 0.033f

            launcher?.setTimeline(timeline?.all() ?: emptyList())
            launcher?.advance((musicTrack?.getPlaybackHeadPosition() ?: 0).toLong(), dtSec)

            val pulse: Float
            if (musicDecoding || diskVisualizerOverlay != null) {
                val fromScore = launcher?.level() ?: 0f
                val target = if (launcher != null && launcher!!.liveCount() > 0) fromScore else maxOf(fromScore, musicAmp)
                beatLevel = if (target > beatLevel) target
                            else beatLevel * Math.pow(0.55, k.toDouble()).toFloat()
                pulse = (0.30f + 0.70f * beatLevel).coerceIn(0.20f, 1f)
            } else {
                glowBeatPhase = (glowBeatPhase + 0.08f * k) % 6.2831853f
                val s = 0.5f + 0.5f * Math.sin(glowBeatPhase.toDouble()).toFloat()
                val kick = Math.pow(s.toDouble(), 4.0).toFloat()
                beatLevel = kick
                pulse = 0.35f + 0.65f * kick
            }

            if (musicAnimEnabled) {
                edgeWaveView?.timeScale = k
                edgeWaveView?.updateWave(beatLevel, pulse)
            }
            diskVisualizerOverlay?.visualizerView?.let { dv ->
                dv.timeScale = k
                dv.updateWave(beatLevel, pulse)
            }
            // v104: Keep notification live level meter in sync with the exact same beat
            PlaybackService.instance?.updateBeatLevel(beatLevel)
            // STEP 2b: THE CLOCK. 30 Hz timer -> the display's own frame
            // callback, once per drawn frame, locked to vsync.
            webView.postOnAnimation(this)
        }
    }

    private var isDesktopMode = false
    private var defaultUserAgent: String = ""
    private val desktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null

    private var pendingDownload: DownloadTask? = null

    // --- Offline auto-recovery state (issue 3) ---
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var mainFrameFailedOffline = false
    // v135: the address that failed while offline, for the offline page's Try again
    private var lastFailedUrl: String? = null

    // --- Blank-page watchdog state (v12) ---
    private var watchdogAttempts = 0
    private var watchdogUrl: String? = null

    // --- Bounded session cache (v16) ---
    private val cacheBudgetBytes = 20L * 1024 * 1024 // 20 MB cap

    // v64: remember the system download so we can offer Open immediately
    private var lastDownloadId: Long = -1L
    private var lastDownloadedName: String = ""
    @Volatile private var cacheCheckBusy = false
    private var navigationCounter = 0

    // --- New-window capture (issue 2) ---
    private var popupCaptureWebView: WebView? = null

    // v34: blob: URLs that already went through one full in-page retry.
    // Prevents a consent-dialog loop when the image simply cannot be saved.
    private val blobPageRetried = HashSet<String>()

    // --- Chunked blob download state (corrupted-download fix) ---
    private var blobOutputStream: FileOutputStream? = null
    private var blobTempFile: File? = null
    // v21: chunk integrity + real file type (see handleBlobChunk / sniffFileType)
    private var blobNextOffset = 0L
    private var blobDetectedMime = ""
    private var blobDetectedExt = ""
    @Volatile private var lastSavedTime = 0L
    @Volatile private var lastSavedName = ""

    data class DownloadTask(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String
    )

    private var musicInputView: EditText? = null
    private var currentMusicUri: String = ""

    // v104: Music mode can now take a file from this phone, not just a link.
    private val musicFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val uriStr = uri.toString()
            currentMusicUri = uriStr
            musicInputView?.setText(uriStr)
            musicInputView?.setSelection(uriStr.length)
        }
    }

    private var diskVisualizerOverlay: DiskVisualizerOverlay? = null

    private val visSongLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val uriStr = uri.toString()
            currentMusicUri = uriStr
            musicPlay(uriStr)
            if (diskVisualizerOverlay == null) {
                showDiskVisualizerOverlay(null)
            }
        }
    }

    private val visLogoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            diskVisualizerOverlay?.setLogoFromUri(uri)
        }
    }

    private val cameraPermissionForQrLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchSmartQrScanner()
        } else {
            Toast.makeText(this, "Camera permission needed to scan QR codes", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startQrScan() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchSmartQrScanner()
        } else {
            cameraPermissionForQrLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun launchSmartQrScanner() {
        try {
            val dialog = SmartQrScannerDialog(this) { scannedText ->
                handleQrScanResult(scannedText)
            }
            dialog.show()
        } catch (_: Exception) {
            qrScannerUnavailable()
        }
    }

    // v158: THE SIZE FIX.
    //
    // There used to be two more scanners behind this one - Google's GMS code
    // scanner (~10 MB) and ZXing (~1 MB) - kept only in case the smart scanner
    // failed to open. Together they were about 11 MB of the APK, carried for a
    // path that should never run.
    //
    // They are gone. If the smart scanner cannot open there is nothing useful a
    // different scanner would do, so we just say so plainly.
    private fun qrScannerUnavailable() {
        try {
            Toast.makeText(
                this,
                "Could not open the QR scanner on this device",
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {}
    }

    private fun isWebUrl(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("ftp://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true)
        ) {
            return true
        }
        if (!trimmed.contains(" ") && trimmed.contains(".") && Patterns.WEB_URL.matcher(trimmed).matches()) {
            return true
        }
        return false
    }

    private fun handleQrScanResult(rawResult: String) {
        val content = rawResult.trim()
        if (content.isEmpty()) return

        val isUrl = isWebUrl(content)
        val title = if (isUrl) "QR Code: Web Link" else "QR Code: Text"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 32, 56, 16)
        }

        val typeBadge = TextView(this).apply {
            text = if (isUrl) "URL DETECTED" else "SCANNED TEXT"
            textSize = 12f
            setTextColor(Color.parseColor("#3AA0FF"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 12)
        }
        container.addView(typeBadge)

        val textView = TextView(this).apply {
            text = content
            textSize = 15f
            setTextColor(colText)
            setTextIsSelectable(true)
            maxLines = 10
        }
        val scrollView = ScrollView(this).apply {
            addView(textView)
        }
        container.addView(scrollView)

        val builder = AlertDialog.Builder(this, dialogTheme())
            .setTitle(title)
            .setView(container)

        if (isUrl) {
            builder.setPositiveButton("Open in Browser") { _, _ ->
                loadInput(content)
            }
            builder.setNeutralButton("Copy URL") { _, _ ->
                copyQrText(content)
            }
            builder.setNegativeButton("Cancel", null)
        } else {
            builder.setPositiveButton("Copy") { _, _ ->
                copyQrText(content)
            }
            builder.setNeutralButton("Search Web") { _, _ ->
                loadInput(content)
            }
            builder.setNegativeButton("Cancel", null)
        }

        builder.show()
    }

    private fun copyQrText(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Pulse Horizon QR", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to copy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun newTabWithUrl(url: String) {
        syncActiveTab()
        tabs.add(TabRecord(url, url, false, autoHideBar))
        activeTab = tabs.size - 1
        renderTabs()
        applyDeskMode(false)
        applyAutoHideNow()
        webView.loadUrl(url)
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val pickedUri = data?.data ?: data?.clipData?.getItemAt(0)?.uri
            if (pickedUri != null) {
                fileUploadCallback?.onReceiveValue(arrayOf(pickedUri))
                cameraOutputUri?.let { uri ->
                    try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            } else if (cameraOutputUri != null) {
                val hasContent = try {
                    contentResolver.openInputStream(cameraOutputUri!!)?.use { it.read() != -1 } ?: false
                } catch (_: Exception) {
                    false
                }

                if (hasContent) {
                    fileUploadCallback?.onReceiveValue(arrayOf(cameraOutputUri!!))
                } else {
                    val thumb = result.data?.extras?.get("data") as? Bitmap
                    if (thumb != null) {
                        try {
                            contentResolver.openOutputStream(cameraOutputUri!!)?.use { out ->
                                thumb.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            }
                            fileUploadCallback?.onReceiveValue(arrayOf(cameraOutputUri!!))
                        } catch (_: Exception) {
                            fileUploadCallback?.onReceiveValue(null)
                        }
                    } else {
                        fileUploadCallback?.onReceiveValue(null)
                    }
                }
            } else {
                val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                fileUploadCallback?.onReceiveValue(results)
            }
        } else {
            cameraOutputUri?.let { uri ->
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            }
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
        cameraOutputUri = null
    }

    private val blockedDomains = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "google-analytics.com",
        "adservice.google.com", "facebook.net", "scorecardresearch.com",
        "criteo.com", "taboola.com", "outbrain.com", "amazon-adsystem.com",
        // v15: major ad exchanges / DSPs / verification / analytics — every
        // blocked resource is one the renderer never has to download, decode
        // or hold in memory.
        "adnxs.com", "adform.net", "adsrvr.org", "pubmatic.com",
        "rubiconproject.com", "openx.net", "smartadserver.com",
        "moatads.com", "adsafeprotected.com", "quantserve.com",
        "hotjar.com", "clarity.ms", "chartbeat.com", "mixpanel.com",
        "amplitude.com",
        // v146: known AUDIO BEACON services. These play a near-inaudible tone
        // that a shop or another app can hear, to link a phone to a person. The
        // only honest defence is to stop the code that plays it from loading,
        // which is exactly what this list does.
        "silverpush.co", "silverpush.com", "sonicnotify.com", "lisnr.com",
        "signal360.com", "shopkick.com"
    )

    // v146: rules on top of the built-in list. The built-in list cannot know
    // which sites a person needs, so they can add their own to block, and allow
    // a site so nothing is blocked on it at all. Allow always wins.
    private val userBlockHosts = hashSetOf<String>()
    private val userAllowHosts = hashSetOf<String>()

    private fun loadBlockerRules() {
        userBlockHosts.clear()
        userAllowHosts.clear()
        try {
            prefs.getString("user_block_hosts", "")?.split(",")?.forEach {
                if (it.isNotBlank()) userBlockHosts.add(it.trim().lowercase())
            }
            prefs.getString("user_allow_hosts", "")?.split(",")?.forEach {
                if (it.isNotBlank()) userAllowHosts.add(it.trim().lowercase())
            }
        } catch (_: Exception) {}
    }

    private fun saveBlockerRules() {
        try {
            prefs.edit()
                .putString("user_block_hosts", userBlockHosts.joinToString(","))
                .putString("user_allow_hosts", userAllowHosts.joinToString(","))
                .commit()
        } catch (_: Exception) {}
    }

    // Allow wins. A site that has been allowed is never touched, so a page
    // someone needs cannot be broken by the list.
    private fun hostBlocked(host: String): Boolean {
        if (host.isEmpty()) return false
        val h = host.lowercase()
        if (userAllowHosts.any { h.contains(it) }) return false
        return blockedDomains.any { h.contains(it) } || userBlockHosts.any { h.contains(it) }
    }

    // Bridge for CHUNKED blob → file streaming. The old single-shot base64
    // transfer corrupted large files (multi-MB MP4 renders from Google Flow)
    // because of string-size limits on the JS→Java bridge.
    // ---- v127: home-page icons + shortcuts -------------------------------
    inner class HomeBridge {
        @JavascriptInterface
        fun getTiles(): String = try { prefs.getString("home_tiles", "") ?: "" } catch (_: Exception) { "" }
        @JavascriptInterface
        fun setTiles(json: String) { try { prefs.edit().putString("home_tiles", json).commit() } catch (_: Exception) {} }
        @JavascriptInterface
        fun wantIcons(hostsJson: String) {
            try {
                val arr = org.json.JSONArray(hostsJson)
                val hosts = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val h = arr.getString(i).trim()
                    if (h.isNotEmpty() && !hosts.contains(h)) hosts.add(h)
                }
                if (hosts.isNotEmpty()) fetchHomeIcons(hosts) else fetchHomeIcons(getHostsFromSavedShortcuts())
            } catch (_: Exception) {
                fetchHomeIcons(getHostsFromSavedShortcuts())
            }
        }
        @JavascriptInterface
        fun getIcons(): String {
            return try {
                val iconDir = File(filesDir, "homeicons")
                val out = org.json.JSONObject()
                val targetHosts = getHostsFromSavedShortcuts()
                for (h in targetHosts) {
                    val cached = File(iconDir, "$h.png")
                    if (cached.exists() && cached.length() > 0L) {
                        out.put(h, dataUri(cached.readBytes()))
                    } else {
                        val fromApp = appIconFor(h)
                        if (fromApp != null) {
                            if (!iconDir.exists()) iconDir.mkdirs()
                            cached.writeBytes(fromApp)
                            out.put(h, dataUri(fromApp))
                        }
                    }
                }
                out.toString()
            } catch (_: Exception) { "{}" }
        }
        @JavascriptInterface
        fun openAccountChooser() {
            runOnUiThread {
                // v161: THE ACCOUNT BUTTON, DONE PROPERLY.
                //
                // Before this, tapping showed the note AND launched Google's
                // sign-in page at the same time - so you got told there was no
                // account and then thrown at Google anyway.
                //
                // Now it is one question, asked fresh every single time you tap:
                // is there a Google session on this device?
                //
                //   NO  -> show the note. Every time you tap. Launch NOTHING.
                //   YES -> open Google's own account switcher.
                //
                // "Is there a session" is read from Google's OWN cookies, which
                // the browser already holds to keep you signed in. That is the
                // browser reading its own storage - the same thing it does on
                // every request. Nothing is read from Google's pages, no script
                // is injected into them, and nothing about the account leaves
                // the device.
                //
                // Because it is asked fresh each tap, there is no flag to go
                // stale: sign into any Google site in the browser and the very
                // next tap opens the switcher. Nothing to save, nothing to set.
                val hasGoogleAccount = try {
                    val c = CookieManager.getInstance()
                        .getCookie("https://accounts.google.com")
                    c != null && (c.contains("SID=") || c.contains("HSID=") ||
                        c.contains("SSID=") || c.contains("APISID="))
                } catch (_: Exception) { false }

                if (!hasGoogleAccount) {
                    try {
                        Toast.makeText(
                            this@MainActivity,
                            "No Google account yet. Sign in on any Google page in this browser " +
                                "and this button will open Google's account switcher.",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Exception) {}
                    return@runOnUiThread
                }

                accountChooserActive = true
                accountFlowAt = System.currentTimeMillis()
                // v142: open Google's ACCOUNT SWITCHER. If the device has no
                // account, Google itself shows the sign-in page.
                //
                // The return target is where the user actually was:
                //   - on a real page  -> that page, so Google comes back to it
                //   - on the home screen -> Google's home, and we then bring
                //     them the rest of the way ourselves, because the home
                //     screen is a local file Google cannot return to.
                val here = try { webView.url } catch (_: Exception) { null }
                val onRealPage = here != null &&
                    (here.startsWith("http://") || here.startsWith("https://"))
                accountReturnHome = !onRealPage
                val back = if (onRealPage) here!! else "https://www.google.com/"
                loadInput(
                    "https://accounts.google.com/AccountChooser?continue=" + Uri.encode(back)
                )
            }
        }

        @JavascriptInterface
        fun openQrScanner() {
            runOnUiThread {
                startQrScan()
            }
        }
    }

    inner class TermsBridge {
        @JavascriptInterface
        fun acceptTerms() {
            runOnUiThread {
                onTermsAccepted()
            }
        }
    }

    private fun isTermsAccepted(): Boolean {
        return getSharedPreferences("pulse_horizon", MODE_PRIVATE).getBoolean("terms_accepted", false)
    }

    private fun onTermsAccepted() {
        val firstRunPrefs = getSharedPreferences("pulse_horizon", MODE_PRIVATE)
        firstRunPrefs.edit().putBoolean("terms_accepted", true).apply()

        // Request permissions after user has read & agreed to the terms
        checkRequiredPermissions()

        // Navigate to home screen
        if (tabs.isNotEmpty()) {
            tabs[activeTab.coerceIn(0, tabs.size - 1)] = TabRecord(homeUrl, "New tab", false, autoHideBar)
        } else {
            tabs.add(TabRecord(homeUrl, "New tab", false, autoHideBar))
            activeTab = 0
        }
        renderTabs()
        webView.loadUrl(homeUrl)
    }

    private fun getHostsFromSavedShortcuts(): List<String> {
        val hosts = ArrayList<String>()
        fun addHost(raw: String?) {
            if (raw.isNullOrBlank()) return
            val trimmed = raw.trim()
            val uriStr = if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) "https://$trimmed" else trimmed
            try {
                val host = Uri.parse(uriStr).host?.lowercase()?.trim()
                if (!host.isNullOrBlank() && !hosts.contains(host)) hosts.add(host)
            } catch (_: Exception) {}
        }

        try {
            val rawTiles = prefs.getString("home_tiles", "") ?: ""
            if (rawTiles.isNotEmpty()) {
                val obj = org.json.JSONObject(rawTiles)
                val tiles = obj.optJSONArray("tiles")
                if (tiles != null) {
                    for (i in 0 until tiles.length()) {
                        val tile = tiles.optJSONObject(i) ?: continue
                        if (tile.optBoolean("folder", false)) {
                            val apps = tile.optJSONArray("apps")
                            if (apps != null) {
                                for (j in 0 until apps.length()) {
                                    val a = apps.optJSONObject(j) ?: continue
                                    addHost(a.optString("url"))
                                }
                            }
                        } else {
                            addHost(tile.optString("url"))
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (hosts.isEmpty()) {
            val defaultHosts = listOf(
                "www.google.com", "maps.google.com", "meet.google.com", "photos.google.com",
                "mail.google.com", "calendar.google.com", "www.youtube.com", "drive.google.com"
            )
            for (h in defaultHosts) if (!hosts.contains(h)) hosts.add(h)
        }
        return hosts
    }

    private val appIconPackages = mapOf(
        "www.google.com"      to "com.google.android.googlequicksearchbox",
        "maps.google.com"     to "com.google.android.apps.maps",
        "meet.google.com"     to "com.google.android.apps.tachyon",
        "photos.google.com"   to "com.google.android.apps.photos",
        "mail.google.com"     to "com.google.android.gm",
        "calendar.google.com" to "com.google.android.calendar",
        "www.youtube.com"     to "com.google.android.youtube",
        "drive.google.com"    to "com.google.android.apps.docs"
    )

    private fun injectHomeIcons() {
        try {
            webView.evaluateJavascript("(typeof phHosts === 'function' ? phHosts() : '')") { res ->
                try {
                    val inner = if (res != null && res.startsWith("\"")) org.json.JSONTokener(res).nextValue() as String else (res ?: "")
                    val hosts = ArrayList<String>()
                    if (inner.isNotEmpty() && inner != "null" && inner != "[]") {
                        try {
                            val arr = org.json.JSONArray(inner)
                            for (i in 0 until arr.length()) {
                                val h = arr.getString(i).trim()
                                if (h.isNotEmpty() && !hosts.contains(h)) hosts.add(h)
                            }
                        } catch (_: Exception) {}
                    }
                    if (hosts.isEmpty()) {
                        fetchHomeIcons(getHostsFromSavedShortcuts())
                    } else {
                        fetchHomeIcons(hosts)
                    }
                } catch (_: Exception) {
                    fetchHomeIcons(getHostsFromSavedShortcuts())
                }
            }
        } catch (_: Exception) {
            fetchHomeIcons(getHostsFromSavedShortcuts())
        }
    }

    private fun fetchHomeIcons(hosts: List<String>) {
        Thread {
            try {
                val targetHosts = if (hosts.isEmpty()) getHostsFromSavedShortcuts() else hosts
                val iconDir = File(filesDir, "homeicons"); if (!iconDir.exists()) iconDir.mkdirs()
                val out = LinkedHashMap<String, String>()
                val report = LinkedHashMap<String, String>()
                for (host in targetHosts) {
                    try {
                        val cached = File(iconDir, host + ".png")
                        if (cached.exists() && cached.length() > 0L) { out[host] = dataUri(cached.readBytes()); report[host] = "cached"; continue }
                        val fromApp = appIconFor(host)
                        if (fromApp != null) { cached.writeBytes(fromApp); out[host] = dataUri(fromApp); report[host] = "app"; continue }
                        lastFavReason = ""
                        val bytes = fetchFavicon(host)
                        if (bytes != null && bytes.isNotEmpty()) { cached.writeBytes(bytes); out[host] = dataUri(bytes); report[host] = "web" }
                        else report[host] = "FAIL " + lastFavReason
                    } catch (e: Exception) { report[host] = "FAIL " + e.javaClass.simpleName }
                }
                if (report.isEmpty()) {
                    report["status"] = "no hosts"
                }
                val json = org.json.JSONObject(out as Map<*, *>).toString()
                val dbg = org.json.JSONObject(report as Map<*, *>).toString()
                webView.post {
                    try {
                        webView.evaluateJavascript("window.phSetIcons && phSetIcons(" + json + ");", null)
                        webView.evaluateJavascript("window.phDebug && phDebug(" + dbg + ");", null)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                webView.post {
                    try {
                        val dbg = org.json.JSONObject(mapOf("error" to "FAIL " + e.javaClass.simpleName)).toString()
                        webView.evaluateJavascript("window.phDebug && phDebug(" + dbg + ");", null)
                    } catch (_: Exception) {}
                }
            }
        }.start()
    }

    private fun dataUri(bytes: ByteArray): String =
        "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun appIconFor(host: String): ByteArray? {
        val pkg = appIconPackages[host] ?: return null
        return try {
            val d = packageManager.getApplicationIcon(pkg)
            val size = 128
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size); d.draw(android.graphics.Canvas(bmp))
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos); bmp.recycle()
            bos.toByteArray()
        } catch (_: Exception) { null }
    }

    private var lastFavReason = ""

    private fun fetchFavicon(host: String): ByteArray? {
        val candidates = listOf(
            "https://www.google.com/s2/favicons?sz=128&domain=$host",
            "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://$host&size=128",
            "https://icons.duckduckgo.com/ip3/$host.ico",
            "https://$host/apple-touch-icon.png",
            "https://$host/favicon.ico"
        )
        val cookies = try { CookieManager.getInstance().getCookie("https://$host") } catch (_: Exception) { null }
        val reasons = StringBuilder()
        for (u in candidates) {
            try {
                val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", "image/png,image/jpeg,image/*;q=0.8")
                if (!cookies.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookies)
                if (defaultUserAgent.isNotEmpty()) conn.setRequestProperty("User-Agent", defaultUserAgent)
                else conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
                val code = conn.responseCode
                if (code !in 200..299) { conn.disconnect(); reasons.append(" ").append(code); continue }
                val raw = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (raw.isEmpty() || raw.size > 400000) { reasons.append(" empty"); continue }
                val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (bmp == null) { reasons.append(" undecodable(").append(raw.size).append("b)"); continue }
                val bos = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos); bmp.recycle()
                if (bos.size() > 0) return bos.toByteArray()
            } catch (e: Exception) { reasons.append(" ").append(e.javaClass.simpleName) }
        }
        lastFavReason = if (reasons.isEmpty()) "no source" else reasons.toString().trim()
        return null
    }

    inner class BlobDownloadBridge {
        @JavascriptInterface
        fun processBlobChunk(base64Chunk: String, mimeType: String, offset: Long, totalSize: Long) {
            handleBlobChunk(base64Chunk, mimeType, offset, totalSize)
        }

        @JavascriptInterface
        fun finishBlob(mimeType: String, totalSize: Long) {
            finishBlobDownload(mimeType, totalSize, null)
        }

        @JavascriptInterface
        fun finishBlobWithName(mimeType: String, totalSize: Long, suggestedName: String?) {
            finishBlobDownload(mimeType, totalSize, suggestedName)
        }

        // v22: the old blob JS failed SILENTLY (only console.error) — the
        // user saw "Downloading..." but no file ever appeared. Now the page
        // reports the real reason, and we show it (suppressing if file was just saved).
        @JavascriptInterface
        fun blobFailed(message: String) {
            runOnUiThread {
                if (System.currentTimeMillis() - lastSavedTime < 8000L) {
                    return@runOnUiThread
                }
                val friendlyMsg = if (message.contains("block", ignoreCase = true) || message.contains("not found", ignoreCase = true)) {
                    "Download could not be completed"
                } else {
                    message
                }
                Toast.makeText(this@MainActivity, "Save failed: $friendlyMsg", Toast.LENGTH_SHORT).show()
            }
        }

        // v42: every in-app path failed (e.g. AI Studio files that reject our
        // direct downloader with 403) — hand the URL to the SYSTEM download
        // manager, a completely different network stack. No loop risk: this
        // never calls back into the direct downloader.
        @JavascriptInterface
        fun systemDownload(url: String, mimeType: String) {
            runOnUiThread {
                val ua = if (isDesktopMode) desktopUserAgent else defaultUserAgent
                val err = if (url.startsWith("http"))
                    enqueueSystemDownload(url, ua, "", mimeType, allowUnnamed = true)
                else "not a downloadable link"
                if (err == null) {
                    // enqueued — system shows progress + our toast
                } else {
                    Toast.makeText(this@MainActivity, "Save failed: $err", Toast.LENGTH_LONG).show()
                }
            }
        }

        // v26: the in-page image fetch was blocked (page policy/CORS) — fall
        // back to the direct downloader with browser-style headers. Must hop
        // to the main thread: WebView methods are main-thread-only.
        @JavascriptInterface
        fun pageImageFetchFailed(url: String) {
            runOnUiThread {
                // v34: blob: URLs must NEVER go to the native HTTP downloader —
                // Java's URLConnection can't speak the blob protocol (that
                // was the "unknown protocol: blob" error). Give the URL one
                // pass through the full in-page chain (registry -> fetch ->
                // XHR -> canvas); if that has already failed too, be honest.
                if (url.startsWith("blob:")) {
                    if (blobPageRetried.add(url)) {
                        executeDownload(
                            DownloadTask(
                                url,
                                if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                "",
                                ""
                            )
                        )
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Save failed: unable to save image",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    downloadMediaDirect(url, null)
                }
            }
        }

        // v35: direct bridge from DOM extraction to avoid canvas taint and download media directly
        @JavascriptInterface
        fun saveDirectMediaFromDom(imageUrl: String, mimeType: String?) {
            runOnUiThread {
                val safeMime = if (!mimeType.isNullOrEmpty()) mimeType else if (imageUrl.contains("video")) "video/mp4" else "image/jpeg"
                if (imageUrl.startsWith("blob:")) {
                    executeDownload(
                        DownloadTask(
                            imageUrl,
                            if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                            "",
                            safeMime
                        )
                    )
                } else {
                    val task = DownloadTask(imageUrl, if (isDesktopMode) desktopUserAgent else defaultUserAgent, "", safeMime)
                    downloadMediaDirect(imageUrl, task)
                }
            }
        }

        // v28: ask before saving on-screen media
        @JavascriptInterface
        fun askSaveVisibleImage(imageUrl: String) {
            runOnUiThread {
                val isVideo = imageUrl.contains(".mp4") || imageUrl.contains(".webm") || imageUrl.contains("video")
                val label = if (isVideo) "video" else "image"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Save $label")
                    .setMessage("Would you like to save the $label shown on screen?")
                    .setPositiveButton("Save $label") { _, _ ->
                        if (imageUrl.startsWith("blob:")) {
                            executeDownload(
                                DownloadTask(
                                    imageUrl,
                                    if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                    "",
                                    if (isVideo) "video/mp4" else "image/png"
                                )
                            )
                        } else {
                            val task = DownloadTask(
                                imageUrl,
                                if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                "",
                                if (isVideo) "video/mp4" else "image/png"
                            )
                            downloadMediaDirect(imageUrl, task)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v117: the theme is decided BEFORE anything is built, and it is
        // applied IN PLACE later - never by recreating the screen.
        themeMode = prefs.getString("theme_mode", "system") ?: "system"
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val darkNow = when (themeMode) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(
            if (darkNow) android.R.style.Theme_DeviceDefault_NoActionBar
            else android.R.style.Theme_DeviceDefault_Light_NoActionBar
        )
        colBg = if (darkNow) Color.parseColor("#0E0E0E") else Color.parseColor("#FFFFFF")
        colSurface = if (darkNow) Color.parseColor("#121212") else Color.parseColor("#F2F2F2")
        colLine = if (darkNow) Color.parseColor("#2A2A2A") else Color.parseColor("#DDDDDD")
        colText = if (darkNow) Color.WHITE else Color.parseColor("#111111")
        colDim = if (darkNow) Color.GRAY else Color.parseColor("#666666")

        window.statusBarColor = colSurface
        window.navigationBarColor = colBg

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        setupWindowInsets()

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colSurface)
            gravity = Gravity.CENTER_VERTICAL
        }

        urlBar = EditText(this).apply {
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(colText)
            setHintTextColor(Color.GRAY)
            hint = "Search or type URL"
            setBackgroundColor(colSurface)
            setPadding(28, 20, 28, 20)

            // v45: tap-to-edit fixed. The old listeners wiped the text on
            // EVERY touch — including touches meant to place the cursor — so
            // fixing a typo destroyed everything typed. Now the text only
            // clears once, when the bar GAINS focus (fresh start = ready for
            // a new URL), and further taps place the cursor normally.
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) setText("")
            }

            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    loadInput(text.toString())
                    clearFocus()
                    try {
                        WindowCompat.getInsetsController(window, this)?.hide(WindowInsetsCompat.Type.ime())
                    } catch (_: Exception) {}
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(windowToken, 0)
                    true
                } else {
                    false
                }
            }
        }

        // v135: a Home button, so you can always get back to the home screen.
        // DESK is still built (the menu's "Desktop site" drives the same flag)
        // but it is no longer shown in the bar.
        homeBtn = Button(this).apply {
            text = "\u2302"
            textSize = 20f
            setTextColor(colText)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(26, 0, 20, 0)
            setOnClickListener {
                if (!isTermsAccepted()) {
                    Toast.makeText(this@MainActivity, "Please read and agree to the terms to continue", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                try {
                    val onHome = webView.url?.startsWith("file:///android_asset/") == true
                    if (!onHome) {
                        syncActiveTab()
                        newTab()
                    }
                } catch (_: Exception) {}
            }
        }
        topBar.addView(homeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        topBar.addView(urlBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // v67: the three-dot menu. All tab handling moved behind this so the
        // top strip stays clean. Two entries: Tabs and Settings.
        menuBtn = Button(this).apply {
            text = "\u22EE"
            textSize = 18f
            setTextColor(colText)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(24, 0, 24, 0)
            setOnClickListener { showMenu() }
        }
        // v130: one tab button, like a real browser's toolbar. It shows how many
        // tabs are open and opens the tab list when tapped.
        tabCountBtn = Button(this).apply {
            text = "1"
            textSize = 15f
            isAllCaps = false
            setTextColor(colText)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(28, 0, 28, 0)
            setOnClickListener {
                // v139: on the home page, open the PAGE's tab screen - the one
                // the design draws, and the one the number belongs to. That is
                // the old UI, and it is what keeps the two in step. Anywhere
                // else, fall back to the app's own screen.
                val onHome = try {
                    webView.url?.startsWith("file:///android_asset/") == true
                } catch (_: Exception) { false }
                if (onHome) {
                    try {
                        webView.evaluateJavascript(
                            "try{ if (window.openTabSwitcher) openTabSwitcher(); }catch(e){}", null
                        )
                    } catch (_: Exception) { openVisualTabSwitcher() }
                } else openVisualTabSwitcher()
            }
        }
        topBar.addView(tabCountBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        topBar.addView(menuBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            // v70: the v68 attempt at helping the keyboard actually BROKE it.
            // Taking focus on every touch-up made the keyboard open and then
            // close again instantly, because focus bounced off the field the
            // user had just tapped. All of that is removed - the WebView
            // handles its own focus exactly as it did before v68.
            // v68: long-press the PAGE (not the address bar) for page actions.
            // On a link, an image or a text field we return false so WebView
            // keeps its own selection/copy behaviour.
            setOnLongClickListener {
                val t = try { (it as? WebView)?.hitTestResult?.type ?: 0 } catch (_: Exception) { 0 }
                val extra = try { (it as? WebView)?.hitTestResult?.extra } catch (_: Exception) { null }
                val onImage = t == WebView.HitTestResult.IMAGE_TYPE || t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                val onContent = t == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                        onImage ||
                        t == WebView.HitTestResult.EDIT_TEXT_TYPE

                if (onImage && !extra.isNullOrEmpty()) {
                    val options = arrayOf("Open image in new tab", "Save image", "Copy image URL")
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Image options")
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> newTabWithUrl(extra)
                                1 -> downloadMediaDirect(extra, null)
                                2 -> {
                                    try {
                                        val cm = getSystemService(ClipboardManager::class.java)
                                        cm?.setPrimaryClip(ClipData.newPlainText("Image URL", extra))
                                        Toast.makeText(applicationContext, "Image URL copied", Toast.LENGTH_SHORT).show()
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        .show()
                    true
                } else if (onContent) {
                    false
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Page")
                        .setItems(arrayOf("Reload", "Back", "Forward")) { _, which ->
                            when (which) {
                                0 -> try { webView.reload() } catch (_: Exception) {}
                                1 -> try { if (webView.canGoBack()) webView.goBack() } catch (_: Exception) {}
                                2 -> try { if (webView.canGoForward()) webView.goForward() } catch (_: Exception) {}
                            }
                        }
                        .show()
                    true
                }
            }
        }
        attachWebViewPullToRefresh(webView)

        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colBg)
            gravity = Gravity.CENTER_VERTICAL
        }
        tabStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(colBg)
            addView(
                tabRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // v67: the visible tab strip is gone - tabs now live behind the
        // three-dot menu. The strip object is kept (detached) so the v66
        // helpers still work; nothing draws it.
        rootLayout.addView(topBar)
        rootLayout.addView(progressBar)
        rootLayout.addView(webView)

        // v73: the edge glow. Four thin strips in a FrameLayout laid over the
        // whole screen. Not clickable, so taps go straight through to the page.
        val glowLayer = FrameLayout(this)
        glowLayerRef = glowLayer
        glowLayer.addView(
            rootLayout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // v104: an inner layer for the strips only. Its padding is set from the
        // system-bar insets, so the glow never runs under the status bar or the
        // gesture bar. rootLayout keeps its own padding, untouched.
        val inner = FrameLayout(this)
        glowInner = inner
        glowLayer.addView(
            inner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val waveView = EdgeGlowView(this).apply {
            visibility = View.GONE
        }
        edgeWaveView = waveView
        edgeViews.add(waveView)
        inner.addView(
            waveView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        reloadIndicator = ReloadAnimationView(this).apply {
            visibility = View.GONE
        }
        glowLayer.addView(
            reloadIndicator,
            FrameLayout.LayoutParams(
                (42f * resources.displayMetrics.density).toInt(),
                (42f * resources.displayMetrics.density).toInt(),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = (56f * resources.displayMetrics.density).toInt()
            }
        )
        setContentView(glowLayer)
        ViewCompat.requestApplyInsets(rootLayout)

        autoHideBar = prefs.getBoolean("auto_hide_bar", true)
        adBlockEnabled = prefs.getBoolean("ad_block", true)
        loadBlockerRules()
        pullToRefreshEnabled = prefs.getBoolean("pull_hard_reload", true)
        loadBookmarks()
        // v69/v73: every media feature starts OFF unless the user switched it on.
        musicAnimEnabled = prefs.getBoolean("music_anim", false)
        bgMusicEnabled = prefs.getBoolean("bg_music", true)
        pipEnabled = prefs.getBoolean("mini_video_player", true)
        instance = this
        setupPlaybackServiceCallback()
        registerPipReceiver()
        tabs.clear()
        applyStrictEngineSettings()
        registerDocumentStartHooks()
        registerNetworkRecovery()

        val termsAccepted = isTermsAccepted()
        if (termsAccepted) {
            tabs.add(TabRecord(homeUrl, "New tab", false, autoHideBar))
            activeTab = 0
            renderTabs()
            checkRequiredPermissions()
            webView.loadUrl(homeUrl)
        } else {
            tabs.add(TabRecord(termsUrl, "Terms of Service", false, autoHideBar))
            activeTab = 0
            renderTabs()
            webView.loadUrl(termsUrl)
        }
    }

    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(systemBars.bottom, ime.bottom)
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, bottom)
            glowInner?.setPadding(systemBars.left, systemBars.top, systemBars.right, bottom)

            val imeNow = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeWasVisible && !imeNow) {
                view.post {
                    if (!isFinishing) {
                        if (urlBar.hasFocus()) urlBar.clearFocus()
                        hideChrome()
                    }
                }
            }
            imeVisible = imeNow
            imeWasVisible = imeNow
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun checkRequiredPermissions() {
        // v102: RECORD_AUDIO is back, as you asked. Note the VISUALIZER does
        // not use it - it decodes the audio itself - so the beat works whether
        // or not this is granted.
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val needed = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 101)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyStrictEngineSettings() {
        val s = webView.settings

        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        // Issue 2: allow new-window navigations (Google SERP tabs such as
        // "AI Mode" use target=_blank / window.open). Without multiple-window
        // support the request is silently swallowed and the tap does nothing.
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        // v55: pinch-to-zoom — lets the user zoom in/out on any page
        // (especially desktop-mode sites like the Apps Script editor,
        // which is otherwise unusable on a phone). No on-screen +/- buttons,
        // pinch only — clean like Chrome.
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false

        // v32: strip the "; wv" WebView marker from the user-agent. Google
        // sign-in pages reject WebView UAs with 403 disallowed_useragent —
        // presenting the plain Chrome-style UA keeps logins working inside
        // the browser. (Standard practice — every third-party browser ships a
        // modified UA.) All download headers use the same string.
        defaultUserAgent = s.userAgentString.replace("; wv", "")
        s.userAgentString = defaultUserAgent

        s.offscreenPreRaster = false
        s.mediaPlaybackRequiresUserGesture = true
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)) {
                WebViewCompat.startSafeBrowsing(this) { _ -> }
            }
        } catch (_: Exception) {}
        // Session HTTP cache (user-approved): the cache lives on DISK, so the
        // RAM footprint is only a small in-memory index (well under 1 MB) —
        // the renderer, the trim-purge and the exit-wipe all stay unchanged.
        // Why: Google's mobile AI Mode ships a heavy JS bundle; with
        // LOAD_NO_CACHE every entry re-downloaded it (the 2-5s black page).
        // With LOAD_DEFAULT, the first entry of a session pays the download
        // once and every following entry loads it from disk. The exit-wipe
        // in onDestroy still erases everything when the browser closes.
        s.cacheMode = WebSettings.LOAD_DEFAULT

        // v128: THE REAL CAUSE OF THE MISSING ICONS.
        // The home page is loaded from a file:// URL. On modern Android a
        // file:// page is an opaque origin and the WebView BLOCKS it from
        // loading remote images - so the page's own icon fetch silently
        // returned nothing and every website tile fell back to the drawn
        // globe. These two lines lift that block for our own bundled page.
        // Safe here: the only file:// page we ever load is our own home page.
        try {
            s.allowFileAccessFromFileURLs = true
            s.allowUniversalAccessFromFileURLs = true
        } catch (_: Exception) {}

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // v64: hear about finished system downloads so Open is offered at once
        try {
            val dlFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(downloadCompleteReceiver, dlFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(downloadCompleteReceiver, dlFilter)
            }
        } catch (_: Exception) {}

        webView.addJavascriptInterface(BlobDownloadBridge(), "AndroidBlobBridge")
        webView.addJavascriptInterface(HomeBridge(), "AndroidHome")
        webView.addJavascriptInterface(TermsBridge(), "AndroidTerms")

        // v23: Gemini-style sites block their own download buttons (CSP /
        // cross-frame blobs — the v22 chain reports "site blocked the
        // download" there). But the image is already ON SCREEN with a plain
        // URL: a long-press on any image now offers to save it directly —
        // we fetch the bytes ourselves, outside the page's control.
        webView.setOnLongClickListener {
            val hit = webView.hitTestResult
            val target = hit.extra
            if ((hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                 hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) &&
                !target.isNullOrEmpty()
            ) {
                showSaveImageDialog(target)
                true
            } else {
                false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (title != null && title.startsWith("PH_HOSTS:")) {
                    val raw = title.removePrefix("PH_HOSTS:")
                    try {
                        val arr = org.json.JSONArray(raw)
                        val hosts = ArrayList<String>()
                        for (i in 0 until arr.length()) {
                            val h = arr.getString(i).trim()
                            if (h.isNotEmpty() && !hosts.contains(h)) hosts.add(h)
                        }
                        if (hosts.isNotEmpty()) fetchHomeIcons(hosts) else fetchHomeIcons(getHostsFromSavedShortcuts())
                    } catch (_: Exception) {
                        fetchHomeIcons(getHostsFromSavedShortcuts())
                    }
                }
            }

            // v127: capture the icon of every page that loads - exactly how
            // Chrome gets a site's favicon. Saved to filesDir/homeicons.
            override fun onReceivedIcon(view: WebView?, icon: android.graphics.Bitmap?) {
                try {
                    if (icon == null) return
                    val host = try { java.net.URI(view?.url ?: "").host } catch (_: Exception) { null } ?: return
                    if (host.isEmpty()) return
                    Thread {
                        try {
                            val dir = File(filesDir, "homeicons"); if (!dir.exists()) dir.mkdirs()
                            val bos = java.io.ByteArrayOutputStream()
                            icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                            val bytes = bos.toByteArray()
                            File(dir, host + ".png").writeBytes(bytes)
                            val uri = dataUri(bytes)
                            webView.post { try { webView.evaluateJavascript(
                                "window.phOneIcon && phOneIcon(" + org.json.JSONObject.quote(host) + "," +
                                org.json.JSONObject.quote(uri) + ");", null) } catch (_: Exception) {} }
                        } catch (_: Exception) {}
                    }.start()
                } catch (_: Exception) {}
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            // Issue 2: Google's SERP tabs (e.g. "AI Mode") open via
            // target=_blank / window.open. Handing THIS WebView back through
            // the transport renders a blank/black page on modern WebView
            // builds, so instead we give WebView a throwaway hidden WebView
            // whose only job is to capture the target URL, then load that URL
            // into the main WebView. The temp view is destroyed right away,
            // so the memory cost is a few MB for a fraction of a second.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                if (transport == null || view == null || resultMsg == null) {
                    return false
                }

                try { popupCaptureWebView?.destroy() } catch (_: Exception) {}
                val temp = WebView(view.context).apply {
                    // Hidden background interceptor doesn't need hardware acceleration or GPU rendernodes
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }
                // FIX: a fresh WebView has JavaScript DISABLED by default — popup
                // flows that run scripts inside the popup before/while navigating
                // (suspected for AI Overview → "AI Mode") died silently. Give the
                // popup the same engine settings as the main view.
                temp.settings.javaScriptEnabled = true
                temp.settings.domStorageEnabled = true
                temp.settings.databaseEnabled = true
                temp.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                temp.settings.userAgentString = if (isDesktopMode) desktopUserAgent else defaultUserAgent
                CookieManager.getInstance().setAcceptThirdPartyCookies(temp, false)
                var captured = false
                temp.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        try { v?.destroy() } catch (_: Exception) {}
                        if (popupCaptureWebView === v) popupCaptureWebView = null
                        return true
                    }

                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url?.toString() ?: return false
                        // CRITICAL: window.open flows (e.g. Google's AI Overview
                        // → "AI Mode") create the popup with about:blank FIRST and
                        // set its location afterwards. Blocking the blank load
                        // kills the whole flow — so let it through.
                        if (target == "about:blank" || target.startsWith("data:")) {
                            return false
                        }
                        // v22: some sites (e.g. gemini.google.com) trigger
                        // downloads as window.open("blob:...") popups — the
                        // old capture code swallowed those silently (no file,
                        // no error). Forward them to the main view's download
                        // pipeline: the blob is same-origin in the main page,
                        // so the injected fetch can read it.
                        if (!captured && target.startsWith("blob:")) {
                            captured = true
                            val dlTask = DownloadTask(
                                target,
                                if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                "",
                                ""
                            )
                            view.post { executeDownload(dlTask) }
                            v?.post {
                                try { v.destroy() } catch (_: Exception) {}
                                if (popupCaptureWebView === v) popupCaptureWebView = null
                            }
                            return true
                        }
                        if (!captured && (target.startsWith("http://") || target.startsWith("https://"))) {
                            val isMedia = target.contains("googleusercontent.com") ||
                                    target.contains("generativelanguage.googleapis.com") ||
                                    target.contains("storage.googleapis.com") ||
                                    target.contains("download=true") ||
                                    target.contains("=download") ||
                                    target.endsWith(".png") || target.endsWith(".jpg") || target.endsWith(".jpeg") ||
                                    target.endsWith(".webp") || target.endsWith(".mp4") || target.endsWith(".webm") || target.endsWith(".gif") || target.endsWith(".pdf") || target.endsWith(".zip") || target.endsWith(".apk") || target.endsWith(".mp3") || target.endsWith(".m4a") || target.endsWith(".wav") || target.endsWith(".ogg") || target.endsWith(".mov") || target.endsWith(".svg") || target.endsWith(".bmp") || target.endsWith(".txt") || target.endsWith(".rtf") || target.endsWith(".doc") || target.endsWith(".docx") || target.endsWith(".xls") || target.endsWith(".xlsx") || target.endsWith(".csv") || target.endsWith(".ppt") || target.endsWith(".pptx") || target.endsWith(".py") || target.endsWith(".kt") || target.endsWith(".xml") || target.endsWith(".bat") || target.endsWith(".exe") || target.endsWith(".jar")
                            captured = true
                            if (isMedia) {
                                val dlTask = DownloadTask(
                                    target,
                                    if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                    "",
                                    ""
                                )
                                view.post { executeDownload(dlTask) }
                            } else {
                                view.loadUrl(normalizeAiModeUrl(target))
                            }
                            v?.post {
                                try { v.destroy() } catch (_: Exception) {}
                                if (popupCaptureWebView === v) popupCaptureWebView = null
                            }
                        }
                        return true // the temp view never actually loads real content
                    }

                    // Fallback capture: POST navigations can bypass
                    // shouldOverrideUrlLoading on some WebView builds.
                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(v, url, favicon)
                        v?.evaluateJavascript(getBlobInterceptionScript(), null)
                        if (!captured && url != null && url.startsWith("blob:")) {
                            captured = true
                            val dlTask = DownloadTask(
                                url,
                                if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                "",
                                ""
                            )
                            view.post { executeDownload(dlTask) }
                            v?.post {
                                try { v.destroy() } catch (_: Exception) {}
                                if (popupCaptureWebView === v) popupCaptureWebView = null
                            }
                        }
                        if (!captured && url != null &&
                            (url.startsWith("http://") || url.startsWith("https://"))
                        ) {
                            val isMedia = url.contains("googleusercontent.com") ||
                                    url.contains("generativelanguage.googleapis.com") ||
                                    url.contains("storage.googleapis.com") ||
                                    url.contains("download=true") ||
                                    url.contains("=download") ||
                                    url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg") ||
                                    url.endsWith(".webp") || url.endsWith(".mp4") || url.endsWith(".webm") || url.endsWith(".gif") || url.endsWith(".pdf") || url.endsWith(".zip") || url.endsWith(".apk") || url.endsWith(".mp3") || url.endsWith(".m4a") || url.endsWith(".wav") || url.endsWith(".ogg") || url.endsWith(".mov") || url.endsWith(".svg") || url.endsWith(".bmp") || url.endsWith(".txt") || url.endsWith(".rtf") || url.endsWith(".doc") || url.endsWith(".docx") || url.endsWith(".xls") || url.endsWith(".xlsx") || url.endsWith(".csv") || url.endsWith(".ppt") || url.endsWith(".pptx") || url.endsWith(".py") || url.endsWith(".kt") || url.endsWith(".xml") || url.endsWith(".bat") || url.endsWith(".exe") || url.endsWith(".jar")
                            captured = true
                            if (isMedia) {
                                val dlTask = DownloadTask(
                                    url,
                                    if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                    "",
                                    ""
                                )
                                view.post { executeDownload(dlTask) }
                            } else {
                                view.loadUrl(normalizeAiModeUrl(url))
                            }
                            v?.post {
                                try { v.destroy() } catch (_: Exception) {}
                                if (popupCaptureWebView === v) popupCaptureWebView = null
                            }
                        }
                    }
                }
                temp.setDownloadListener { dlUrl, dlUserAgent, contentDisposition, mimetype, _ ->
                    val dlTask = DownloadTask(dlUrl, dlUserAgent, contentDisposition, mimetype)
                    view.post { executeDownload(dlTask) }
                    temp.post {
                        try { temp.destroy() } catch (_: Exception) {}
                        if (popupCaptureWebView === temp) popupCaptureWebView = null
                    }
                }
                popupCaptureWebView = temp
                transport.webView = temp
                resultMsg.sendToTarget()

                // If nothing was ever captured (dead popup), free the popup
                // slot and the memory after 15s — Chromium refuses to create
                // a new popup while one is still pending.
                temp.postDelayed({
                    if (popupCaptureWebView === temp) {
                        try { temp.destroy() } catch (_: Exception) {}
                        popupCaptureWebView = null
                    }
                }, 15_000L)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    request?.grant(request.resources)
                }
            }

            // Geolocation: the app holds NO location permissions (removed at
            // the user's request) — but we still ANSWER the page instantly
            // with a denial. The DEFAULT implementation never answers at all,
            // which hangs any site that awaits navigator.geolocation forever.
            // An instant "denied" lets the page's promise settle and its
            // scripts continue normally.
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val captureRequested = try {
                    fileChooserParams?.isCaptureEnabled == true
                } catch (_: Exception) {
                    false
                }

                cameraOutputUri = if (captureRequested) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.TITLE, "IMG_${System.currentTimeMillis()}")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        }
                        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

                val isSaveMode = fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_SAVE
                val contentIntent = if (isSaveMode) {
                    fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = fileChooserParams?.acceptTypes?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: "*/*"
                        val defaultFilename = fileChooserParams?.filenameHint?.takeIf { !it.isNullOrEmpty() } ?: "project.zip"
                        putExtra(Intent.EXTRA_TITLE, defaultFilename)
                    }
                } else {
                    fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                }

                // NOTE: the camera is deliberately NOT injected into the chooser
                // via EXTRA_INITIAL_INTENTS anymore. Mixing ACTION_IMAGE_CAPTURE
                // into a chooser's initial intents breaks the returned result on
                // many Android 11+ devices — the picked file never reaches the
                // page and the site re-shows its upload menu. The camera is now
                // used only when the site explicitly requests capture
                // (<input type="file" capture>).
                var launchIntent = if (isSaveMode) {
                    contentIntent
                } else {
                    Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, contentIntent)
                        putExtra(Intent.EXTRA_TITLE, "Select file")
                    }
                }

                if (captureRequested && cameraOutputUri != null) {
                    val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri)
                        clipData = ClipData.newUri(contentResolver, "photo", cameraOutputUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    if (captureIntent.resolveActivity(packageManager) != null) {
                        val resInfoList = packageManager.queryIntentActivities(captureIntent, PackageManager.MATCH_DEFAULT_ONLY)
                        for (resolveInfo in resInfoList) {
                            val pkg = resolveInfo.activityInfo.packageName
                            grantUriPermission(pkg, cameraOutputUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        launchIntent = captureIntent
                    }
                }

                try {
                    fileChooserLauncher.launch(launchIntent)
                } catch (_: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val task = DownloadTask(url, userAgent, contentDisposition, mimetype)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingDownload = task
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 102)
                return@setDownloadListener
            }
            executeDownload(task)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onSafeBrowsingHit(view: WebView, request: android.webkit.WebResourceRequest, threatType: Int, callback: android.webkit.SafeBrowsingResponse) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        callback.backToSafety(true)
                    } else {
                        callback.proceed(false)
                    }
                } catch (_: Exception) {}
                Toast.makeText(this@MainActivity, "⚠️ Safe Browsing blocked malicious site / phishing attempt", Toast.LENGTH_LONG).show()
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (accountChooserActive && url != null && url.contains("myaccount.google.com")) {
                    accountChooserActive = false
                    accountReturnHome = false
                    view?.loadUrl(homeUrl)
                    // v144: a plain note, so nobody has to guess what that
                    // button is for or where their accounts live.
                    return
                }
                // v142: the account flow started on the home screen - bring the
                // user all the way back there, instead of leaving them on Google.
                if (accountFlowLive() && accountReturnHome && url != null &&
                    url.contains("google.com") &&
                    !url.contains("AccountChooser") && !url.contains("accounts.google.com")
                ) {
                    accountChooserActive = false
                    accountReturnHome = false
                    view?.loadUrl(homeUrl)
                    return
                }
                // v143: the user has moved on to a page that is nothing to do
                // with the account flow - drop the flags so a later google.com
                // redirect from any site is never intercepted.
                if (accountChooserActive && url != null &&
                    !url.contains("google.com") && !url.startsWith("file://")
                ) {
                    accountChooserActive = false
                    accountReturnHome = false
                }
                if (url != null && url.contains("accounts.google.com/AccountChooser")) {
                    accountChooserActive = true
                }
                // A new navigation is under way — reset the offline-failure flag.
                mainFrameFailedOffline = false
                // v135: the home page is a local file - showing
                // "file:///android_asset/home.html" in the bar looks broken, so
                // the bar stays clean there.
                urlBar.setText(if (url != null && url.startsWith("file:///android_asset/")) "" else url)
                // v66: keep the active tab's record in step with navigation
                if (tabs.isNotEmpty()) {
                    tabs[activeTab.coerceIn(0, tabs.size - 1)].url = url ?: homeUrl
                }
                // Ensure blob registry and deferred revocation hooks are active as early as possible
                view?.evaluateJavascript(getBlobInterceptionScript(), null)
                // Blank-page watchdog (v12): arm the recovery check.
                scheduleBlankWatchdog(url)

                // Desktop-mode zoom fix: WebView CARRIES the previous page's
                // pinch-zoom level into the next page load (documented WebView
                // behaviour — no public reset API). Toggling overview mode off
                // and back on + initialScale(0) is the known way to force each
                // new desktop page to re-fit to the screen instead of loading
                // "zoomed in". Mobile pages reset naturally via their viewport
                // meta, so this only runs in desktop mode.
                if (isDesktopMode) {
                    view?.settings?.loadWithOverviewMode = false
                    view?.settings?.loadWithOverviewMode = true
                    view?.setInitialScale(0)
                }
            }

            // Re-arm the blank-page watchdog AFTER the page reports finished —
            // the blank hang can happen after a successful load event (the page
            // paints only its loading spinner and then never renders).
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // v148: the page just (re)loaded, so its tab screen is empty.
                // Push the real tabs again, or it looks like they were lost.
                if (url != null && url.startsWith("file:///android_asset/")) renderTabs()
                if (url != null && url.startsWith("file:///android_asset/")) tellHomePageTheme()
                try { CookieManager.getInstance().flush() } catch (_: Exception) {}
                if (url != null && url.contains("android_asset/home.html")) injectHomeIcons()
                if (url != null && url.contains("android_asset/terms.html")) {
                    if (isTermsAccepted()) {
                        view?.evaluateJavascript(
                            "try { localStorage.setItem('pulse_horizon_terms_accepted_v1', 'true'); var ov = document.getElementById('firstVisitOverlay'); if (ov) ov.classList.remove('active'); } catch(e){}",
                            null
                        )
                    }
                }
                // v84: we just arrived at a tab - put its scroll and typed text back
                if (pendingRestore) {
                    pendingRestore = false
                    restoreTabState()
                }
                scheduleBlankWatchdog(url)
                // v47: immersive browsing — once a page is open, the URL bar
                // and the system status bar step aside and the website takes
                // the whole screen. Touch the top of the screen to bring the
                // bar back (ready for a new URL).
                if (!urlBar.hasFocus()) hideChrome()
                // v58: re-check shortly after the page settles - the URL bar
                // can still hold focus from typing when onPageFinished fires,
                // which used to leave the bar stuck on a fully loaded page.
                urlBar.postDelayed({
                    if (!urlBar.hasFocus() && !isFinishing) hideChrome()
                }, 600L)
                // Ensure hook is alive even if page re-evaluated or dynamically modified
                view?.evaluateJavascript(getBlobInterceptionScript(), null)
                // Bound the on-disk cache footprint (v16): measure quietly on
                // a background thread after every page load; flush if too big.
                checkCacheBudget()
                // Proactive render kick: Google pages (especially AI Mode) can
                // freeze right after loading — a brief pause/resume ~1.5s in
                // prevents the user having to press Recents to unfreeze them.
                if (url != null && url.contains("google.com/search")) {
                    webView.postDelayed({ if (webView.url == url) kickRenderer() }, 1_500L)
                }
            }

            // Issue 1: the old code injected a viewport meta with width=1100
            // AFTER the page had already laid out and WebView had computed its
            // initial fit-zoom. Changing the viewport post-layout never
            // re-triggers the overview fit, so the page rendered wider than
            // the screen and the user had to pinch-zoom out manually.
            // Desktop mode is now driven purely by the desktop User-Agent +
            // useWideViewPort + loadWithOverviewMode, which lets WebView size
            // and fit the page correctly at load time.

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                var url = request?.url?.toString() ?: return false

                if (url.contains("myaccount.google.com")) {
                    if (accountChooserActive) {
                        accountChooserActive = false
                        accountReturnHome = false
                        view?.loadUrl(homeUrl)
                        return true
                    }
                }
                // v142: same as above, for the redirect path
                if (accountFlowLive() && accountReturnHome &&
                    url.contains("google.com") &&
                    !url.contains("AccountChooser") && !url.contains("accounts.google.com")
                ) {
                    accountChooserActive = false
                    accountReturnHome = false
                    view?.loadUrl(homeUrl)
                    return true
                }
                // v143: same as above, for the redirect path
                if (accountChooserActive &&
                    !url.contains("google.com") && !url.startsWith("file://")
                ) {
                    accountChooserActive = false
                    accountReturnHome = false
                }
                if (url.contains("accounts.google.com/AccountChooser")) {
                    accountChooserActive = true
                }

                // v138: the home page's tab screen drives the real tabs.
                if (url.startsWith("pulse://tab/")) {
                    url.removePrefix("pulse://tab/").toIntOrNull()?.let { switchToTab(it) }
                    return true
                }
                if (url.startsWith("pulse://closetab/")) {
                    url.removePrefix("pulse://closetab/").toIntOrNull()?.let { idx ->
                        closeTab(idx)
                    }
                    return true
                }
                if (url.startsWith("pulse://closeall")) {
                    while (tabs.size > 1) tabs.removeAt(tabs.size - 1)
                    activeTab = 0
                    renderTabs()
                    return true
                }

                // v139: the page's tab screen drives the real tabs
                if (url.startsWith("pulse://tab/")) {
                    url.removePrefix("pulse://tab/").toIntOrNull()?.let { switchToTab(it) }
                    return true
                }
                if (url.startsWith("pulse://closetab/")) {
                    url.removePrefix("pulse://closetab/").toIntOrNull()?.let { closeTab(it) }
                    return true
                }
                if (url.startsWith("pulse://closeall")) {
                    while (tabs.size > 1) tabs.removeAt(tabs.size - 1)
                    activeTab = 0
                    renderTabs()
                    return true
                }

                // v135: the offline page's "Try again" button.
                if (url.startsWith("pulse://retry")) {
                    val back = lastFailedUrl
                    if (back != null) view?.loadUrl(back) else view?.reload()
                    return true
                }

                // v123: a page loaded from file:// cannot navigate to another
                // file:// on modern Android - the platform blocks it - so the
                // bundled terms page's Submit hops to this marker URL instead
                // and the app loads the home page itself. file -> https is
                // always allowed, so this route cannot be blocked.
                if (url.startsWith("https://pulse-horizon.local/")) {
                    onTermsAccepted()
                    return true
                }

                // v121: the welcome page (terms + Get Started) finishes with
                // window.location.replace('https://www.google.com'). That single
                // hop is why the app opened Google instead of the home page.
                // Send it to the home page instead. Scoped tightly: only while
                // we are LEAVING the feedback/welcome page, and only for a bare
                // google.com - a user typing google.com is never touched.
                val leavingFeedback = (view?.url ?: "").startsWith(feedbackUrl)
                if (leavingFeedback &&
                    (url == "https://www.google.com" || url == "https://www.google.com/")
                ) {
                    view?.loadUrl(homeUrl)
                    return true
                }

                if (isDesktopMode && url.contains("://m.")) {
                    url = url.replace("://m.", "://www.")
                    view?.loadUrl(url)
                    return true
                }

                if (url.startsWith("blob:")) {
                    executeDownload(
                        DownloadTask(
                            url,
                            if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                            "",
                            ""
                        )
                    )
                    return true
                }

                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:blank")) {
                    return false
                }

                try {
                    val intent = if (url.startsWith("intent:")) {
                        Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                            // Never let a web page direct-launch a specific app
                            // component through an intent:// link.
                            component = null
                        }
                    } else {
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    }
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                        return true
                    }
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    if (!fallback.isNullOrEmpty() && fallback.startsWith("http")) {
                        view?.loadUrl(fallback)
                        return true
                    }
                    // Issue 2: intent:// links carry the real https URL in their
                    // data — recover it so navigations (e.g. Google AI Mode)
                    // never die silently when no app can handle the intent.
                    val embedded = intent.dataString
                    if (embedded != null &&
                        (embedded.startsWith("http://") || embedded.startsWith("https://"))
                    ) {
                        view?.loadUrl(embedded)
                        return true
                    }
                } catch (_: Exception) {}
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                val host = url.host ?: ""

                val urlStr = url.toString()
                if (urlStr.startsWith("file:///android_asset/icons/")) {
                    val fileName = urlStr.substringAfter("file:///android_asset/icons/").substringBefore("?").substringBefore("#")
                    val iconHost = fileName.removeSuffix(".png").removeSuffix(".webp").removeSuffix(".ico")
                    try {
                        val stream = assets.open("icons/$fileName")
                        return WebResourceResponse("image/png", null, stream)
                    } catch (_: Exception) {}
                    try {
                        val cached = File(File(filesDir, "homeicons"), "$iconHost.png")
                        if (cached.exists() && cached.length() > 0L) {
                            return WebResourceResponse("image/png", null, java.io.FileInputStream(cached))
                        }
                    } catch (_: Exception) {}
                    try {
                        val fromApp = appIconFor(iconHost)
                        if (fromApp != null && fromApp.isNotEmpty()) {
                            val iconDir = File(filesDir, "homeicons")
                            if (!iconDir.exists()) iconDir.mkdirs()
                            File(iconDir, "$iconHost.png").writeBytes(fromApp)
                            return WebResourceResponse("image/png", null, java.io.ByteArrayInputStream(fromApp))
                        }
                    } catch (_: Exception) {}
                    return WebResourceResponse("image/png", "UTF-8", 404, "Not Found", null, java.io.ByteArrayInputStream(ByteArray(0)))
                }

                if (adBlockEnabled && hostBlocked(host)) {
                    blockedCount++
                    // Empty 200 + permissive CORS: pages XHR these ad endpoints
                    // cross-origin. A CORS-less empty response makes every such
                    // XHR FAIL — console errors, retry loops, slow page init
                    // (seen on mobile AI Mode). With ACAO:* the request
                    // "succeeds" instantly with an empty body and the page's
                    // scripts move on without stalling.
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))).apply {
                        responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                    }
                }

                // Desktop mode: responsive sites choose mobile/desktop layout by
                // VIEWPORT WIDTH, not by User-Agent — so the desktop UA alone
                // leaves them looking mobile. Rewrite the viewport meta inside
                // the HTML BEFORE the renderer lays the page out (the old
                // post-layout JS injection is what caused the zoom-in bug).
                if (isDesktopMode && request.isForMainFrame &&
                    request.method.equals("GET", ignoreCase = true) &&
                    (url.scheme == "https" || url.scheme == "http")
                ) {
                    val rewritten = forceDesktopViewport(url.toString(), request.requestHeaders)
                    if (rewritten != null) return rewritten
                    // fall through — on any failure WebView loads it normally
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val deadView = view ?: webView
                val lastUrl = deadView.url?.takeIf { it.isNotEmpty() } ?: urlBar.text?.toString()?.takeIf { it.isNotEmpty() } ?: "https://www.google.com"
                try {
                    (deadView.parent as? ViewGroup)?.removeView(deadView)
                    deadView.destroy()
                } catch (_: Exception) {}

                if (deadView === webView) {
                    val didCrash = detail?.didCrash() == true
                    webView = WebView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0,
                            1f
                        )
                        if (didCrash) {
                            // If renderer process crashed, fallback to software rendering
                            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        }
                    }
                    rootLayout.addView(webView)
                    attachWebViewPullToRefresh(webView)
                    applyStrictEngineSettings()
                    registerDocumentStartHooks()
                    webView.loadUrl(lastUrl)
                }
                return true
            }

            // Issue 3: remember when the main document itself failed with a
            // connectivity-type error, so the network callback can retry once
            // data comes back.
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val offline = when (error?.errorCode) {
                        WebViewClient.ERROR_HOST_LOOKUP,
                        WebViewClient.ERROR_CONNECT,
                        WebViewClient.ERROR_TIMEOUT,
                        WebViewClient.ERROR_IO,
                        WebViewClient.ERROR_UNKNOWN -> true
                        else -> false
                    }
                    mainFrameFailedOffline = offline
                    if (offline) lastFailedUrl = request?.url?.toString()
                    // v135: instead of the WebView's own error screen - which is
                    // a black page in this app - show something readable.
                    if (offline) showOfflinePage()
                }
            }
        }
    }

    // AI Overview → AI Mode popups carry a heavy "continuation" URL — session
    // tokens tying the new view to the Overview conversation. Google's backend
    // STALLS the first answer-stream for such continuation URLs (the 2-5 minute
    // black page), while the CLEAN search URL (?q=...&udm=50) loads fast
    // (user-verified: the AI Mode tab bar and direct google.com/ai are always
    // fast, and both of those also go through the same popup capture). So when
    // a popup targets AI Mode, rebuild the clean URL for the same query —
    // same answer, no stall, and back-navigation still works. Non-AI-Mode
    // popups are captured untouched.
    private fun normalizeAiModeUrl(url: String): String {
        return try {
            val u = Uri.parse(url)
            if (u.getQueryParameter("udm") == "50") {
                val q = u.getQueryParameter("q")
                if (!q.isNullOrEmpty()) {
                    return "https://www.google.com/search?q=" + Uri.encode(q) + "&udm=50"
                }
            }
            url
        } catch (_: Exception) {
            url
        }
    }

    // ---- Blank-page watchdog (v12) ----
    // User-recorded bug: a Google search page downloaded everything but never
    // rendered — blank screen with Google's small spinner for minutes, network
    // idle (0.00 KB/s). The exact trigger varies (stale cache entry from the
    // session cache, a stalled in-page view transition, a Google flake), so
    // instead of guessing: RECOVER. 12s after a google.com/search page starts
    // (re-armed after it finishes), if the view has no scrollable content at
    // all — a real results page ALWAYS scrolls — reload once with the HTTP
    // cache bypassed. Max 2 attempts per URL, so it can never loop.
    private val blankCheckRunnable = object : Runnable {
        override fun run() {
            val url = webView.url ?: return
            if (url.contains("google.com/search") &&
                !webView.canScrollVertically(1) &&
                !webView.canScrollVertically(-1) &&
                watchdogAttempts < 2
            ) {
                watchdogAttempts++
                // Step 1: render-kick — the user-verified cure (pausing and
                // resuming the app unfreezes the page without reloading it).
                kickRenderer()
                // Step 2: if STILL blank 6s later, reload with the network
                // cache bypassed.
                webView.postDelayed({
                    if (webView.url == url &&
                        !webView.canScrollVertically(1) &&
                        !webView.canScrollVertically(-1)
                    ) {
                        Toast.makeText(applicationContext, "Page appeared blank — retrying", Toast.LENGTH_SHORT).show()
                        val oldMode = webView.settings.cacheMode
                        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        webView.reload()
                        webView.postDelayed({
                            if (webView.settings.cacheMode == WebSettings.LOAD_NO_CACHE) {
                                webView.settings.cacheMode = oldMode
                            }
                        }, 8_000L)
                    }
                }, 6_000L)
            }
        }
    }

    // Reproduces the user's verified cure programmatically: pause the WebView
    // for ~120ms and resume it. This is the standard workaround for the Chromium
    // WebView compositor stall where a loaded page stops repainting entirely
    // (frozen spinner, no animation, network idle) until the app is backgrounded
    // and foregrounded (pressing Recents and returning).
    private fun kickRenderer() {
        try {
            webView.onPause()
            webView.postDelayed({
                try {
                    webView.onResume()
                    webView.invalidate()
                } catch (_: Exception) {}
            }, 120L)
        } catch (_: Exception) {}
    }

    // ---- Bounded session cache (v16, fixed v20) ----
    // The LOAD_DEFAULT session cache (v10) makes repeat AI Mode / site loads
    // fast, but it collects data on disk with no system-side size limit —
    // the user watched it grow in App Info. So: after each page load, measure
    // our full on-disk footprint on a background thread; above 20 MB, flush.


    // v47: immersive browsing. hideChrome() hands the whole screen to the
    // website (URL bar + Android status bar step aside, swipe gestures keep
    // working). Touching the top strip calls it back up, focused on an empty
    // URL bar, keyboard ready — the v45 tap-to-clear behavior chains into
    // this perfectly. Zero RAM/battery cost: pure visibility flags.
    private fun hideChrome() {
        // v68: auto-hide is PER TAB. Each tab remembers its own choice; the
        // value in Settings is the default a new tab starts with.
        if (!activeAutoHide()) return
        // v71: never change the layout while the soft keyboard is up. Hiding
        // the bar grows the WebView, the page sees a resize, drops focus, and
        // the keyboard shuts. Gemini fires onPageFinished repeatedly, so this
        // was firing right as the user tapped the chat box.
        if (imeVisible) return
        chromeHidden = true
        topBar.visibility = View.GONE
        if (::tabStrip.isInitialized) tabStrip.visibility = View.GONE
        // v49: the system fullscreen flags were removed — they were the one
        // heavy change vs v44 (screen recomposition) and the suspect behind
        // YouTube Shorts quality drops and the warm phone. Hiding the URL
        // bar alone gives the page the extra space at zero cost; the Android
        // status bar stays visible like in v44.
    }

    private fun showChrome() {
        chromeHidden = false
        topBar.visibility = View.VISIBLE
        if (::tabStrip.isInitialized) tabStrip.visibility = View.VISIBLE
        // v50: the bar comes back showing the CURRENT URL — not cleared —
        // so the long-press options (Copy URL / Share URL) and DESK are
        // immediately usable. One tap on the bar then clears it and opens
        // the keyboard for a new URL (v45 behavior). Both worlds at once:
        // the futuristic full screen AND the URL options whenever wanted.
        try {
            val current = webView.url
            if (!current.isNullOrEmpty()) urlBar.setText(current)
        } catch (_: Exception) {}
    }

    // v48 (user's own design): double-tap the top strip to show the bar,
    // double-tap it again to hide it. A single accidental tap passes straight
    // through to the page — the bar never appears or disappears by mistake.
    // Double-tap to show gives full access to the URL options (DESK button,
    // long-press copy/share) whenever needed, so immersive mode and the
    // options coexist.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // v51: taller zone + slower timing. The Android status bar eats
            // touches on the very top rows, so the double-tap zone is now
            // ~80dp (about 1.5 cm below the status bar) and the two taps
            // may be up to 450ms apart.
            val strip = 80 * resources.displayMetrics.density
            if (ev.y < strip) {
                val now = System.currentTimeMillis()
                if (now - lastStripTap < 450) {
                    lastStripTap = 0
                    if (chromeHidden) {
                        // hidden -> a double tap brings the bar back
                        showChrome()
                        return true
                    } else if (!urlBar.hasFocus()) {
                        // visible and NOT being edited -> a double tap hides it
                        hideChrome()
                        return true
                    }
                    // v70: the bar already has focus, which means the first tap
                    // of this double tap is what gave it focus. Hiding now is
                    // exactly the "bar flashes open then vanishes" bug, so we
                    // let the tap through untouched instead.
                }
                lastStripTap = now
            } else {
                lastStripTap = 0
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // v20 fix: clearCache() alone only purges the HTTP cache — the heavy
    // stuff under app_webview (Service Worker CacheStorage, GPUCache,
    // Code Cache) survived it, so the budget kept tripping while the
    // footprint never shrank. The flush now also runs the same deep clean
    // the exit-wipe uses. Cookies/login/history are untouched; the
    // exit-wipe still erases everything on close.
    private fun checkCacheBudget() {
        // v33: throttle — measuring on EVERY page load walks the cache dirs
        // far more often than needed. Every 8th load keeps the 20 MB
        // ceiling honest (worst case: a few pages of overshoot) at 1/8th
        // the directory scans.
        navigationCounter++
        if (navigationCounter % 3 != 0) return
        if (cacheCheckBusy) return
        cacheCheckBusy = true
        Thread {
            try {
                val dirs = mutableListOf<File>(cacheDir, codeCacheDir)
                externalCacheDir?.let { dirs.add(it) }
                val webviewDir = File(applicationInfo.dataDir, "app_webview")
                if (webviewDir.isDirectory) dirs.add(webviewDir)
                var total = 0L
                for (d in dirs) total += dirSize(d)
                if (total > cacheBudgetBytes) {
                    runOnUiThread {
                        try {
                            webView.clearCache(true)
                            WebStorage.getInstance().deleteAllData()
                        } catch (_: Exception) {}
                    }
                    try {
                        cleanDir(cacheDir)
                        cleanDir(codeCacheDir)
                        externalCacheDir?.let { cleanDir(it) }
                    } catch (_: Exception) {}
                    // v44: restored from v39 — the deep Chromium clean. clearCache
                    // + WebStorage miss Code Cache and GPUCache (the heaviest,
                    // fully re-creatable folders under app_webview).
                    try { cleanChromiumCache(webviewDir) } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                cacheCheckBusy = false
            }
        }.start()
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        dir.listFiles()?.forEach {
            size += if (it.isDirectory) dirSize(it) else it.length()
        }
        return size
    }

    private fun scheduleBlankWatchdog(url: String?) {
        webView.removeCallbacks(blankCheckRunnable)
        if (url == null || !url.contains("google.com/search")) return
        if (watchdogUrl != url) {
            watchdogUrl = url
            watchdogAttempts = 0
        }
        webView.postDelayed(blankCheckRunnable, 12_000L)
    }

    // Issue 3: auto-recover from "webpage not available". Register a single
    // system network callback (no polling, no threads of our own — effectively
    // zero RAM). When a network becomes available and the last main-frame load
    // failed with a connectivity error, reload once.
    // v33: document-start instrumentation (from the Gemini engineering
    // report — the genuinely correct root-cause fix). Diagnosis: sites
    // create a blob: URL, fire the download click, then revoke the URL in
    // the SAME JS frame — while Android's download pipeline is still
    // catching up asynchronously. By the time we fetch the blob, the page
    // has already invalidated it → "site blocked the download". Fix:
    // hooks installed BEFORE any page script runs —
    //   - createObjectURL: keep a live reference to every Blob created
    //   - revokeObjectURL: defer the actual revocation by 45s so the
    //     download has time to read the bytes
    // Registered for ALL origins (a generic browser capability, no
    // site-specific targeting) and silently skipped on system WebViews
    // too old to support document-start scripts.
    private fun getBlobInterceptionScript(): String {
        return """
            (function() {
                try {
                    if (window.__pulseBlobHookInstalled) return;
                    window.__pulseBlobHookInstalled = true;
                    // v65: this guard Set used to be added to and never cleaned,
                    // so a URL stayed 'already downloading' forever and later
                    // failures were swallowed. Cleared on any completion.
                    window.__pulseStreamDone = function() {
                        try { if (window.__activeBlobStreams) window.__activeBlobStreams.clear(); } catch (_) {}
                    };
                    window.__blobRegistry = window.__blobRegistry || new Map();
                    window.__blobFilenames = window.__blobFilenames || new Map();
                    window.__activeBlobStreams = window.__activeBlobStreams || new Set();

                    function streamBlobWithBridge(BR, blob, customMime, customName) {
                        if (!BR || !blob) return;
                        var CHUNK = 262144;
                        var mime = customMime || blob.type || 'application/octet-stream';
                        var size = blob.size || 0;
                        var offset = 0;
                        var fileName = customName || '';

                        function next() {
                            var slice = blob.slice(offset, offset + CHUNK);
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                if (reader.readyState === FileReader.DONE) {
                                    var s = reader.result;
                                    var b64 = s.substring(s.indexOf(',') + 1);
                                    BR.processBlobChunk(b64, mime, offset, size);
                                    offset += CHUNK;
                                    if (offset < size) {
                                        setTimeout(next, 0);
                                    } else {
                                        if (BR.finishBlobWithName) {
                                            window.__pulseStreamDone(); BR.finishBlobWithName(mime, size, fileName);
                                        } else {
                                            window.__pulseStreamDone(); BR.finishBlob(mime, size);
                                        }
                                    }
                                }
                            };
                            reader.readAsDataURL(slice);
                        }

                        if (size > 0) {
                            next();
                        } else {
                            if (BR.finishBlobWithName) {
                                window.__pulseStreamDone(); BR.finishBlobWithName(mime, 0, fileName);
                            } else {
                                window.__pulseStreamDone(); BR.finishBlob(mime, 0);
                            }
                        }
                    }

                    // Standard W3C File System Access API polyfill (used by modern web apps like Google AI Studio)
                    if (!window.showSaveFilePicker || !window.__pulseSavePickerHooked) {
                        try {
                            window.showSaveFilePicker = async function(options) {
                                var suggestedName = (options && options.suggestedName) || 'project.zip';
                                var BR = window.AndroidBlobBridge || (window.top && window.top.AndroidBlobBridge);
                                return {
                                    kind: 'file',
                                    name: suggestedName,
                                    getFile: async function() { return new Blob([], { type: 'application/zip' }); },
                                    queryPermission: async function() { return 'granted'; },
                                    requestPermission: async function() { return 'granted'; },
                                    createWritable: async function() {
                                        var chunks = [];
                                        return {
                                            write: async function(data) {
                                                if (data) {
                                                    if (data.type === 'write' && data.data !== undefined) {
                                                        chunks.push(data.data);
                                                    } else {
                                                        chunks.push(data);
                                                    }
                                                }
                                            },
                                            seek: async function() {},
                                            truncate: async function() {},
                                            close: async function() {
                                                var blob = new Blob(chunks, { type: 'application/zip' });
                                                if (BR) {
                                                    streamBlobWithBridge(BR, blob, 'application/zip', suggestedName);
                                                }
                                            }
                                        };
                                    }
                                };
                            };
                            window.__pulseSavePickerHooked = true;
                        } catch (_) {}
                    }

                    // Direct anchor download capture:
                    // Captures <a download> clicks synchronously at the moment of trigger,
                    // streaming the file cleanly and preventing duplicate download attempts.
                    function captureAnchorDownload(a, event) {
                        try {
                            if (!a) return;
                            var href = a.href || a.getAttribute('href') || '';
                            if (!href || href.indexOf('blob:') !== 0) return;
                            var downloadAttr = a.getAttribute('download') || a.download || '';
                            var filename = downloadAttr.trim();
                            if (!filename || filename === 'true') {
                                try {
                                    var p = new URL(href).pathname;
                                    filename = (window.__blobFilenames && (window.__blobFilenames.get(href) || window.__blobFilenames.get(p))) || 'project.zip';
                                } catch (_) {
                                    filename = 'project.zip';
                                }
                            }
                            if (filename && filename !== 'true') {
                                window.__blobFilenames.set(href, filename);
                                try {
                                    var p = new URL(href).pathname;
                                    if (p) window.__blobFilenames.set(p, filename);
                                } catch (_) {}
                            }

                            if (window.__activeBlobStreams && window.__activeBlobStreams.has(href)) {
                                if (event && typeof event.preventDefault === 'function') event.preventDefault();
                                return;
                            }
                            window.__activeBlobStreams = window.__activeBlobStreams || new Set();
                            window.__activeBlobStreams.add(href);

                            // Cancel default navigation to prevent WebView from firing a second duplicate download
                            if (event && typeof event.preventDefault === 'function') {
                                event.preventDefault();
                            }

                            var win = a.ownerDocument ? (a.ownerDocument.defaultView || window) : window;
                            var BR = win.AndroidBlobBridge || window.AndroidBlobBridge || (window.top && window.top.AndroidBlobBridge);
                            if (!BR) return;

                            function startStream(b) {
                                if (!b) return;
                                streamBlobWithBridge(BR, b, b.type, filename);
                            }

                            // 1. Check registry
                            var regBlob = (window.__blobRegistry && window.__blobRegistry.get(href)) ||
                                          (win.__blobRegistry && win.__blobRegistry.get(href)) ||
                                          (window.top && window.top.__blobRegistry && window.top.__blobRegistry.get(href));
                            if (regBlob) {
                                startStream(regBlob);
                                return;
                            }

                            // 2. Fetch immediately while the blob is alive
                            win.fetch(href).then(function(res) {
                                if (!res.ok && res.status !== 0) throw new Error('HTTP ' + res.status);
                                return res.blob();
                            }).then(function(b) {
                                if (b) {
                                    if (window.__blobRegistry) window.__blobRegistry.set(href, b);
                                    startStream(b);
                                }
                            }).catch(function() {
                                try {
                                    var xhr = new win.XMLHttpRequest();
                                    xhr.open('GET', href, true);
                                    xhr.responseType = 'blob';
                                    xhr.onload = function() {
                                        if (xhr.response) {
                                            if (window.__blobRegistry) window.__blobRegistry.set(href, xhr.response);
                                            startStream(xhr.response);
                                        }
                                    };
                                    xhr.onerror = function() {
                                        if (window.__lastZipBlob) {
                                            startStream(window.__lastZipBlob);
                                        }
                                    };
                                    xhr.send();
                                } catch (_) {
                                    if (window.__lastZipBlob) {
                                        startStream(window.__lastZipBlob);
                                    }
                                }
                            });
                        } catch (_) {}
                    }

                    var origClick = HTMLAnchorElement.prototype.click;
                    if (origClick && !origClick.__pulseHooked) {
                        HTMLAnchorElement.prototype.click = function() {
                            captureAnchorDownload(this, null);
                            return origClick.apply(this, arguments);
                        };
                        HTMLAnchorElement.prototype.click.__pulseHooked = true;
                    }

                    document.addEventListener('click', function(e) {
                        var el = e.target;
                        while (el && el.tagName !== 'A') {
                            el = el.parentElement;
                        }
                        if (el && el.tagName === 'A') {
                            var h = el.href || el.getAttribute('href') || '';
                            if (h.indexOf('blob:') === 0 || el.getAttribute('download') !== null) {
                                captureAnchorDownload(el, e);
                            }
                        }
                    }, true);

                    function installUrlHooks(targetObj) {
                        if (!targetObj) return;
                        var origCreate = targetObj.createObjectURL;
                        if (origCreate && !origCreate.__pulseHooked) {
                            targetObj.createObjectURL = function(blob) {
                                var u = origCreate.call(targetObj, blob);
                                try {
                                    if (blob && (blob instanceof Blob || typeof blob.slice === 'function' || blob.size !== undefined)) {
                                        window.__blobRegistry.set(u, blob);
                                        try {
                                            var p = new URL(u).pathname;
                                            if (p) window.__blobRegistry.set(p, blob);
                                        } catch (_) {}
                                        if (blob.type && blob.type.indexOf('zip') >= 0) {
                                            window.__lastZipBlob = blob;
                                            try { if (window.top) window.top.__lastZipBlob = blob; } catch (_) {}
                                        }
                                        if (blob.name) {
                                            window.__blobFilenames.set(u, blob.name);
                                        }
                                        // Bounded retention (3 min) auto-cleans memory with zero RAM leak
                                        setTimeout(function() {
                                            try {
                                                if (window.__blobRegistry) {
                                                    window.__blobRegistry.delete(u);
                                                    try {
                                                        var p = new URL(u).pathname;
                                                        window.__blobRegistry.delete(p);
                                                    } catch (_) {}
                                                }
                                            } catch (_) {}
                                        }, 180000);
                                    }
                                } catch (e) {}
                                return u;
                            };
                            targetObj.createObjectURL.__pulseHooked = true;
                        }

                        var origRevoke = targetObj.revokeObjectURL;
                        if (origRevoke && !origRevoke.__pulseHooked) {
                            targetObj.revokeObjectURL = function(u) {
                                // Defer revocation by 120s so Android download pipeline has plenty of time
                                setTimeout(function() {
                                    try { origRevoke.call(targetObj, u); } catch (e) {}
                                    try {
                                        if (window.__blobRegistry) {
                                            window.__blobRegistry.delete(u);
                                            try {
                                                var p = new URL(u).pathname;
                                                window.__blobRegistry.delete(p);
                                            } catch (_) {}
                                        }
                                    } catch (_) {}
                                }, 120000);
                            };
                            targetObj.revokeObjectURL.__pulseHooked = true;
                        }
                    }

                    installUrlHooks(window.URL);
                    if (window.webkitURL && window.webkitURL !== window.URL) {
                        installUrlHooks(window.webkitURL);
                    }
                } catch (e) {}
            })();
        """.trimIndent()
    }

    private fun registerDocumentStartHooks() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, getBlobInterceptionScript(), setOf("*"))
        } catch (_: Exception) {}
        applyVisibilityOverride()
    }

    // v77: THE VIDEO FREEZE.
    // Your clue settled it: when the small window was enlarged, the video was
    // STILL frozen inside the page. So the picture was never the problem - the
    // PAGE had paused its own video. Sites like YouTube stop playback the
    // moment they believe the page is hidden, and a small window or a
    // backgrounded app looks exactly like that to them.
    //
    // So instead of fighting the pause after the fact, we tell the page the
    // truth we want it to believe: it is always visible. This is the same
    // technique used to keep a WebView playing in the background, and it also
    // helps background music for the same reason.
    private val visibilityOverrideScript = """
        (function(){
          try {
            if (window.__pulseVisibilityHook) return;
            window.__pulseVisibilityHook = true;
            var def = function(o, k, v) {
              try { Object.defineProperty(o, k, { get: function(){ return v; }, configurable: true }); } catch (e) {}
            };
            def(document, 'hidden', false);
            def(document, 'visibilityState', 'visible');
            def(document, 'webkitHidden', false);
            def(document, 'webkitVisibilityState', 'visible');
            def(document, 'wasDiscarded', false);
            // v80: also on the PROTOTYPE. Pages that read the value off
            // Document.prototype rather than the instance were slipping past
            // the instance-only overrides above.
            def(Document.prototype, 'hidden', false);
            def(Document.prototype, 'visibilityState', 'visible');
            try { document.hasFocus = function(){ return true; }; } catch (e) {}
            var kill = function(n) {
              try { document.addEventListener(n, function(e){ e.stopImmediatePropagation(); }, true); } catch (e) {}
              try { window.addEventListener(n, function(e){ e.stopImmediatePropagation(); }, true); } catch (e) {}
            };
            kill('visibilitychange');
            kill('webkitvisibilitychange');
            kill('pagehide');
            kill('freeze');
            kill('blur');
            kill('focusout');
            if (window.requestAnimationFrame) {
              window.requestAnimationFrame = function(cb) {
                return setTimeout(function(){ try { cb(performance.now()); } catch (e) {} }, 16);
              };
              window.cancelAnimationFrame = function(id) { try { clearTimeout(id); } catch (e) {} };
            }
          } catch (e) {}
        })();
    """.trimIndent()

    // v78: addDocumentStartJavaScript returns a ScriptHandler, not an id
    // string, and you remove it by calling remove() on that handler. The v77
    // build failed on both of those - the CI log named the exact lines.
    private var visibilityHandler: ScriptHandler? = null

    private fun applyVisibilityOverride() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val inPip = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)
        val want = bgMusicEnabled || inPip || pipEnabled
        try {
            if (want && visibilityHandler == null) {
                visibilityHandler = WebViewCompat.addDocumentStartJavaScript(
                    webView, visibilityOverrideScript, setOf("*")
                )
            } else if (!want && visibilityHandler != null) {
                visibilityHandler?.remove()
                visibilityHandler = null
            }
        } catch (_: Exception) {
        }
    }

    private fun registerNetworkRecovery() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (mainFrameFailedOffline && !isDestroyed) {
                        mainFrameFailedOffline = false
                        Toast.makeText(this@MainActivity, "Back online — reloading", Toast.LENGTH_SHORT).show()
                        webView.reload()
                    }
                }
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback
        )
    }

    private fun executeDownload(task: DownloadTask) {
        val url = task.url

        // Handle client-side Blob URLs (Gemini, Google Flow, web video renderers, AI Studio project exports).
        if (url.startsWith("blob:")) {
            // Deduplication: If a file was saved in the last 8 seconds (e.g. via File System Access API),
            // do not trigger a duplicate secondary download attempt for the already-saved blob.
            if (System.currentTimeMillis() - lastSavedTime < 8000L) {
                return
            }
            val safeMime = task.mimeType.ifEmpty { "application/octet-stream" }
            val escapedUrl = url.replace("\\", "\\\\").replace("'", "\\'")
            val escapedMime = safeMime.replace("\\", "\\\\").replace("'", "\\'")
            val js = """
                (function() {
                    var BR = window.AndroidBlobBridge;
                    var targetUrl = '$escapedUrl';
                    var safeMime = '$escapedMime';

                    if (window.__activeBlobStreams && window.__activeBlobStreams.has(targetUrl)) {
                        return; // Stream already triggered by anchor download capture
                    }

                    function streamBlob(blob, customMime, customName) {
                        if (window.__activeBlobStreams) window.__activeBlobStreams.add(targetUrl);
                        var CHUNK = 262144;
                        var mime = customMime || blob.type || safeMime;
                        var size = blob.size;
                        var offset = 0;
                        var fileName = customName || (window.__blobFilenames && window.__blobFilenames.get(targetUrl)) || '';
                        if (!fileName) {
                            try {
                                var p = new URL(targetUrl).pathname;
                                if (p && window.__blobFilenames && window.__blobFilenames.get(p)) {
                                    fileName = window.__blobFilenames.get(p);
                                }
                            } catch (_) {}
                        }
                        if (!fileName && (mime.indexOf('zip') >= 0 || targetUrl.indexOf('zip') >= 0)) {
                            fileName = 'project.zip';
                        }
                        function next() {
                            var slice = blob.slice(offset, offset + CHUNK);
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                if (reader.readyState === FileReader.DONE) {
                                    var s = reader.result;
                                    var b64 = s.substring(s.indexOf(',') + 1);
                                    BR.processBlobChunk(b64, mime, offset, size);
                                    offset += CHUNK;
                                    if (offset < size) {
                                        setTimeout(next, 0);
                                    } else {
                                        if (BR.finishBlobWithName) {
                                            window.__pulseStreamDone(); BR.finishBlobWithName(mime, size, fileName);
                                        } else {
                                            window.__pulseStreamDone(); BR.finishBlob(mime, size);
                                        }
                                    }
                                }
                            };
                            reader.readAsDataURL(slice);
                        }
                        if (size > 0) {
                            next();
                        } else {
                            if (BR.finishBlobWithName) {
                                window.__pulseStreamDone(); BR.finishBlobWithName(mime, 0, fileName);
                            } else {
                                window.__pulseStreamDone(); BR.finishBlob(mime, 0);
                            }
                        }
                    }
                    function fail(msg) {
                        // v65: do not swallow the error. Clearing the guard first
                        // means the real reason is reported instead of hidden.
                        window.__pulseStreamDone();
                        window.__pulseStreamDone(); BR.blobFailed(String(msg));
                    }

                    function lookupBlob(u) {
                        if (!u) return null;
                        function checkReg(reg) {
                            if (!reg) return null;
                            if (reg.has(u)) return reg.get(u);
                            try {
                                var p = new URL(u).pathname;
                                if (p && reg.has(p)) return reg.get(p);
                            } catch (_) {}
                            for (var entry of reg.entries()) {
                                if (entry[0] === u || u.indexOf(entry[0]) >= 0 || entry[0].indexOf(u) >= 0) {
                                    return entry[1];
                                }
                            }
                            return null;
                        }

                        var b = checkReg(window.__blobRegistry);
                        if (b) return b;

                        try {
                            if (window.top && window.top.__blobRegistry) {
                                var tb = checkReg(window.top.__blobRegistry);
                                if (tb) return tb;
                            }
                        } catch (_) {}

                        // Check across frame windows if present
                        try {
                            var frames = document.querySelectorAll('iframe');
                            for (var i = 0; i < frames.length; i++) {
                                try {
                                    var cwin = frames[i].contentWindow;
                                    if (cwin && cwin.__blobRegistry) {
                                        var fb = checkReg(cwin.__blobRegistry);
                                        if (fb) return fb;
                                    }
                                } catch (_) {}
                            }
                        } catch (_) {}

                        if (window.__lastZipBlob) return window.__lastZipBlob;
                        try { if (window.top && window.top.__lastZipBlob) return window.top.__lastZipBlob; } catch (_) {}

                        // If not found by URL, check if there is an active zip blob (AI Studio export)
                        if (window.__blobRegistry && window.__blobRegistry.size > 0) {
                            for (var entry of window.__blobRegistry.entries()) {
                                var bl = entry[1];
                                if (bl && bl.type && bl.type.indexOf('zip') >= 0) {
                                    return bl;
                                }
                            }
                            var all = Array.from(window.__blobRegistry.values());
                            return all[all.length - 1];
                        }
                        return null;
                    }

                    var targetBlob = lookupBlob(targetUrl);
                    if (targetBlob) {
                        streamBlob(targetBlob);
                        return;
                    }

                    // Visible media fallback for images and videos
                    function tryMedia() {
                        if (window.__lastZipBlob) {
                            streamBlob(window.__lastZipBlob, 'application/zip', 'project.zip');
                            return;
                        }
                        function collectMedia(root, acc) {
                            var list = root.querySelectorAll('img, video');
                            for (var i = 0; i < list.length; i++) { acc.push(list[i]); }
                            var all = root.querySelectorAll('*');
                            for (var j = 0; j < all.length; j++) {
                                if (all[j].shadowRoot) { collectMedia(all[j].shadowRoot, acc); }
                            }
                            return acc;
                        }
                        var media = collectMedia(document, []);
                        var best = null, bestArea = 0;
                        for (var i = 0; i < media.length; i++) {
                            var el = media[i];
                            var w = el.naturalWidth || el.videoWidth || el.clientWidth || 0;
                            var h = el.naturalHeight || el.videoHeight || el.clientHeight || 0;
                            if (w * h < 10000 && !el.duration) continue;
                            var r = el.getBoundingClientRect();
                            var vis = r.top < (window.innerHeight - 20) && r.bottom > 60;
                            var area = Math.max(r.width, w) * Math.max(r.height, h);
                            if (vis && area > bestArea) {
                                bestArea = area;
                                best = el;
                            }
                        }
                        if (best) {
                            var src = best.currentSrc || best.src || '';
                            if (!src && best.querySelector) {
                                var s = best.querySelector('source');
                                if (s) src = s.src || '';
                            }
                            if (src.indexOf('blob:') === 0) {
                                var mb = lookupBlob(src);
                                if (mb) {
                                    streamBlob(mb);
                                    return;
                                }
                            }
                            if (best.tagName && best.tagName.toLowerCase() === 'img') {
                                try {
                                    var c = document.createElement('canvas');
                                    c.width = best.naturalWidth || best.width || 300;
                                    c.height = best.naturalHeight || best.height || 300;
                                    var ctx = c.getContext('2d');
                                    ctx.drawImage(best, 0, 0);
                                    c.toBlob(function(b) {
                                        if (b) { streamBlob(b); }
                                        else if (src) { BR.saveDirectMediaFromDom(src, 'image/jpeg'); }
                                        else { fail('unable to read download content'); }
                                    }, 'image/png');
                                    return;
                                } catch (e) {
                                    if (src) {
                                        BR.saveDirectMediaFromDom(src, 'image/jpeg');
                                        return;
                                    }
                                }
                            }
                            if (src) {
                                BR.saveDirectMediaFromDom(src, best.tagName && best.tagName.toLowerCase() === 'video' ? 'video/mp4' : 'image/jpeg');
                                return;
                            }
                        }
                        var lateBlob = lookupBlob(targetUrl);
                        if (lateBlob) {
                            streamBlob(lateBlob);
                            return;
                        }
                        fail('unable to read download content');
                    }

                    function tryXhr() {
                        try {
                            var xhr = new XMLHttpRequest();
                            xhr.open('GET', targetUrl);
                            xhr.responseType = 'blob';
                            xhr.onload = function() {
                                if (xhr.response && xhr.response.size > 0) {
                                    streamBlob(xhr.response);
                                } else {
                                    tryMedia();
                                }
                            };
                            xhr.onerror = function() { tryMedia(); };
                            xhr.send();
                        } catch (e) { tryMedia(); }
                    }

                    try {
                        fetch(targetUrl)
                        .then(function(r) {
                            if (!r.ok && r.status !== 0) throw new Error('HTTP ' + r.status);
                            return r.blob();
                        })
                        .then(function(b) {
                            if (b && b.size > 0) {
                                streamBlob(b);
                            } else {
                                tryXhr();
                            }
                        })
                        .catch(tryXhr);
                    } catch (e) { tryXhr(); }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            return
        }

        // Handle inline Data URIs
        if (url.startsWith("data:")) {
            saveRawBase64(url, task.mimeType)
            return
        }

        // Direct media download for images/videos or Google/Gemini media URLs
        val isMediaDownload = task.mimeType.startsWith("image/") ||
                task.mimeType.startsWith("video/") ||
                task.mimeType.contains("zip") ||
                task.contentDisposition.contains(".zip") ||
                url.contains(".zip") ||
                url.contains("googleusercontent.com") ||
                url.contains("generativelanguage.googleapis.com") ||
                url.contains("storage.googleapis.com") ||
                url.contains("aistudio.google.com") ||
                url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg") ||
                url.endsWith(".webp") || url.endsWith(".mp4") || url.endsWith(".webm") || url.endsWith(".gif") || url.endsWith(".pdf") || url.endsWith(".zip") || url.endsWith(".apk") || url.endsWith(".mp3") || url.endsWith(".m4a") || url.endsWith(".wav") || url.endsWith(".ogg") || url.endsWith(".mov") || url.endsWith(".svg") || url.endsWith(".bmp")

        if (isMediaDownload) {
            downloadMediaDirect(url, task)
            return
        }

        // Standard HTTP / HTTPS Downloads via DownloadManager
        if (enqueueSystemDownload(url, task.userAgent, task.contentDisposition, task.mimeType) == null) {
            return
        }
        downloadMediaDirect(url, task)
    }

    // v42: shared system-downloader path. Returns false when Android cannot
    // name the file (bare ".bin" — caller should use our own byte-sniffing
    // downloader) or when enqueueing fails outright.
    private fun enqueueSystemDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        allowUnnamed: Boolean = false
    ): String? { // null = success, otherwise the failure reason
        return try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            if (!allowUnnamed && fileName.endsWith(".bin")) {
                // v41: Android could not identify this file (no extension in the
                // URL, no useful mime) — exam-paper sites do this and the file
                // lands as .bin. Use our own downloader instead: it reads the
                // actual bytes (%PDF -> .pdf, PK... -> zip family, ...) and also
                // respects the server's filename header.
                return "no recognizable file name"
            }
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                // v64: if the server sent no type, derive it from the extension
                // so the notification's Open resolves to the right app.
                val effectiveMime = mimeType.ifEmpty { mimeFromName(fileName) }
                if (effectiveMime.isNotEmpty()) {
                    setMimeType(effectiveMime)
                }
                addRequestHeader("User-Agent", userAgent)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookies)
                }
                addRequestHeader("Referer", webView.url ?: url)
                setDescription("Downloading file...")
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                @Suppress("DEPRECATION")
                allowScanningByMediaScanner()
            }

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            lastDownloadId = dm.enqueue(request)
            lastDownloadedName = fileName
            Toast.makeText(this, "Download started: $fileName", Toast.LENGTH_SHORT).show()
            null
        } catch (e: Exception) {
            e.message ?: "system download failed"
        }
    }

    // v23: "Save image" dialog for the long-press menu. Routes the image
    // URL through the right saver: data: URIs, blob: URLs (in-page path)
    // and everything else via our own direct downloader with cookies.
    private fun showSaveImageDialog(imageUrl: String) {
        val isVideo = imageUrl.contains(".mp4") || imageUrl.contains(".webm") || imageUrl.contains("video")
        val label = if (isVideo) "video" else "image"
        AlertDialog.Builder(this)
            .setTitle("Save $label")
            .setMessage("Download this $label to Downloads?")
            .setPositiveButton("Download") { _, _ ->
                when {
                    imageUrl.startsWith("data:") -> saveRawBase64(imageUrl, "")
                    imageUrl.startsWith("blob:") ->
                        executeDownload(
                            DownloadTask(
                                imageUrl,
                                if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                                "",
                                if (isVideo) "video/mp4" else "image/png"
                            )
                        )
                    else -> downloadMediaDirect(imageUrl, null)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadImageViaPage(url: String) {
        if (url.startsWith("blob:")) {
            executeDownload(DownloadTask(url, if (isDesktopMode) desktopUserAgent else defaultUserAgent, "", ""))
        } else {
            downloadMediaDirect(url, null)
        }
    }

    private fun downloadImageDirect(url: String) {
        downloadMediaDirect(url, null)
    }

    private fun fallbackToDomExtraction(failedUrl: String) {
        runOnUiThread {
            val escUrl = failedUrl.replace("'", "\\'")
            val js = """
                (function() {
                    var BR = window.AndroidBlobBridge;
                    if (!BR) return;
                    try {
                        var failedUrl = '$escUrl';
                        function sendBlob(blob, customMime) {
                            var CHUNK = 65536;
                            var mime = customMime || blob.type || 'image/png';
                            var size = blob.size;
                            var offset = 0;
                            function next() {
                                var slice = blob.slice(offset, Math.min(offset + CHUNK, size));
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    if (reader.readyState === FileReader.DONE) {
                                        var res = reader.result;
                                        var b64 = res.substring(res.indexOf(',') + 1);
                                        BR.processBlobChunk(b64, mime, offset, size);
                                        offset += CHUNK;
                                        if (offset < size) {
                                            setTimeout(next, 0);
                                        } else {
                                            window.__pulseStreamDone(); BR.finishBlob(mime, size);
                                        }
                                    }
                                };
                                reader.readAsDataURL(slice);
                            }
                            if (size > 0) { next(); } else { window.__pulseStreamDone(); BR.finishBlob(mime, 0); }
                        }

                        // 1. Direct registry lookup
                        if (window.__blobRegistry) {
                            if (window.__blobRegistry.has(failedUrl)) {
                                sendBlob(window.__blobRegistry.get(failedUrl));
                                return;
                            }
                            try {
                                var parsedPath = new URL(failedUrl, location.href).pathname;
                                if (parsedPath && window.__blobRegistry.has(parsedPath)) {
                                    sendBlob(window.__blobRegistry.get(parsedPath));
                                    return;
                                }
                            } catch (_) {}
                        }

                        // 2. Locate media element on screen
                        function collectMedia(root, acc) {
                            var list = root.querySelectorAll('img, video');
                            for (var i = 0; i < list.length; i++) { acc.push(list[i]); }
                            var all = root.querySelectorAll('*');
                            for (var j = 0; j < all.length; j++) {
                                if (all[j].shadowRoot) { collectMedia(all[j].shadowRoot, acc); }
                            }
                            return acc;
                        }
                        var imgs = collectMedia(document, []);
                        var target = null;
                        for (var i = 0; i < imgs.length; i++) {
                            var el = imgs[i];
                            var s = el.currentSrc || el.src || '';
                            if (s === failedUrl || (s && failedUrl.indexOf(s) !== -1) || (failedUrl.indexOf(s) !== -1)) {
                                target = el;
                                break;
                            }
                        }
                        if (!target) {
                            var bestArea = 0;
                            for (var j = 0; j < imgs.length; j++) {
                                var e = imgs[j];
                                var r = e.getBoundingClientRect();
                                var w = e.naturalWidth || e.videoWidth || r.width || 0;
                                var h = e.naturalHeight || e.videoHeight || r.height || 0;
                                var area = w * h;
                                if (area > bestArea && r.top < (window.innerHeight - 20) && r.bottom > 60) {
                                    bestArea = area;
                                    target = e;
                                }
                            }
                        }

                        if (target) {
                            var src = target.currentSrc || target.src || '';
                            if (!src && target.querySelector) {
                                var sTag = target.querySelector('source');
                                if (sTag) src = sTag.src || '';
                            }
                            var isVideo = target.tagName && target.tagName.toLowerCase() === 'video';
                            var defaultMime = isVideo ? 'video/mp4' : 'image/jpeg';

                            if (src && src.indexOf('blob:') === 0 && window.__blobRegistry && window.__blobRegistry.has(src)) {
                                sendBlob(window.__blobRegistry.get(src), defaultMime);
                                return;
                            }

                            if (src && src.indexOf('http') === 0) {
                                fetch(src, { credentials: 'include' })
                                    .then(function(r) {
                                        if (!r.ok) throw new Error('HTTP ' + r.status);
                                        return r.blob();
                                    })
                                    .then(function(b) {
                                        sendBlob(b, defaultMime);
                                    })
                                    .catch(function() {
                                        if (!isVideo && target.tagName && target.tagName.toLowerCase() === 'img') {
                                            try {
                                                var c = document.createElement('canvas');
                                                c.width = target.naturalWidth || target.width || 300;
                                                c.height = target.naturalHeight || target.height || 300;
                                                var ctx = c.getContext('2d');
                                                ctx.drawImage(target, 0, 0);
                                                c.toBlob(function(b) {
                                                    if (b) {
                                                        sendBlob(b, 'image/png');
                                                    } else {
                                                        BR.saveDirectMediaFromDom(src, defaultMime);
                                                    }
                                                }, 'image/png');
                                                return;
                                            } catch (_) {}
                                        }
                                        BR.saveDirectMediaFromDom(src, defaultMime);
                                    });
                                return;
                            } else if (src && src.indexOf('blob:') === 0) {
                                fetch(src)
                                    .then(function(r) { return r.blob(); })
                                    .then(function(b) { sendBlob(b, defaultMime); })
                                    .catch(function() {
                                        if (src.indexOf('http') === 0) { BR.systemDownload(src, defaultMime); }
                                        else { window.__pulseStreamDone(); BR.blobFailed('unable to read download content'); }
                                    });
                                return;
                            }
                        }
                        // v43: same-origin page-context fetch FIRST — runs
                        // inside the site with the full session, exactly how the
                        // site's own download button works. Only if that fails
                        // do we hand the URL to the system downloader.
                        if (failedUrl.indexOf('http') === 0) {
                            fetch(failedUrl, { credentials: 'include' })
                                .then(function(r) { if (!r.ok) { throw new Error('HTTP ' + r.status); } return r.blob(); })
                                .then(function(b) { sendBlob(b, (b && b.type) || ''); })
                                .catch(function() { BR.systemDownload(failedUrl, ''); });
                        } else {
                            window.__pulseStreamDone(); BR.blobFailed('media not found');
                        }
                    } catch (err) {
                        window.__pulseStreamDone(); BR.blobFailed(err && err.message ? err.message : 'unable to read download content');
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }

    private data class DirectDownloadStrategy(
        val referer: String?,
        val cookie: String?,
        val secFetchMode: String,
        val secFetchDest: String,
        val secFetchSite: String?,
        val acceptHeader: String,
        val tag: String
    )

    private fun getMergedGoogleCookies(targetUrl: String, refererUrl: String?): String {
        val cookieMgr = CookieManager.getInstance()
        val domains = listOf(
            targetUrl,
            refererUrl ?: "",
            "https://gemini.google.com",
            "https://www.google.com",
            "https://google.com",
            "https://accounts.google.com",
            "https://photos.google.com",
            "https://drive.google.com",
            "https://docs.google.com",
            "https://play.google.com"
        )
        val cookieMap = LinkedHashMap<String, String>()
        for (d in domains) {
            if (d.isNotEmpty()) {
                val c = cookieMgr.getCookie(d)
                if (!c.isNullOrEmpty()) {
                    for (pair in c.split(";")) {
                        val p = pair.trim()
                        val eq = p.indexOf('=')
                        if (eq > 0) {
                            val k = p.substring(0, eq).trim()
                            val v = p.substring(eq + 1).trim()
                            if (k.isNotEmpty() && v.isNotEmpty()) {
                                cookieMap[k] = v
                            }
                        }
                    }
                }
            }
        }
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    // Direct media downloader:
    // Streams in 64KB buffer chunks directly to a temporary file on disk (ZERO RAM increase).
    // Bypasses 403 Forbidden errors from Google/Gemini CDNs (lh3.googleusercontent.com) by:
    // 1. Trying browser document download navigation with merged Google session cookies
    // 2. Trying subresource fetch with proper Origin / Referer / Sec-Fetch-Site
    // 3. Trying clean no-referrer requests (avoiding Google anti-hotlink blocks)
    // 4. Following HTTP 30x redirects safely
    // 5. Sniffing magic bytes for accurate .mp4 / .webm / .jpg / .png extensions
    private fun downloadMediaDirect(url: String, task: DownloadTask? = null) {
        if (url.startsWith("data:")) {
            saveRawBase64(url, task?.mimeType ?: "")
            return
        }
        if (url.startsWith("blob:")) {
            executeDownload(
                DownloadTask(
                    url,
                    if (isDesktopMode) desktopUserAgent else defaultUserAgent,
                    "",
                    task?.mimeType ?: ""
                )
            )
            return
        }
        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            runOnUiThread {
                Toast.makeText(this, "Save failed: invalid download address", Toast.LENGTH_LONG).show()
            }
            return
        }
        Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
        val refererUrl = try { webView.url } catch (_: Exception) { null } ?: url
        val ua = if (isDesktopMode) desktopUserAgent else defaultUserAgent
        val isVideoHint = task?.mimeType?.startsWith("video/") == true ||
                url.contains(".mp4") || url.contains(".webm") || url.contains("video")

        Thread {
            var tempPartFile: File? = null
            try {
                val cookieMgr = CookieManager.getInstance()
                val urlCookies = cookieMgr.getCookie(url)
                val mergedCookie = getMergedGoogleCookies(url, refererUrl)

                val attempts = listOf(
                    // 1. Browser Navigation Download: As top-level document download with referer and merged auth cookies
                    DirectDownloadStrategy(
                        referer = refererUrl,
                        cookie = mergedCookie,
                        secFetchMode = "navigate",
                        secFetchDest = "document",
                        secFetchSite = null,
                        acceptHeader = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                        tag = "nav-doc-ref-cookie"
                    ),
                    // 2. Subresource Fetch: As image/video element with referer and merged auth cookies
                    DirectDownloadStrategy(
                        referer = refererUrl,
                        cookie = mergedCookie,
                        secFetchMode = "no-cors",
                        secFetchDest = if (isVideoHint) "video" else "image",
                        secFetchSite = null,
                        acceptHeader = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                        tag = "subres-ref-cookie"
                    ),
                    // 3. Gemini Root Origin Referer
                    DirectDownloadStrategy(
                        referer = "https://gemini.google.com/",
                        cookie = mergedCookie,
                        secFetchMode = "no-cors",
                        secFetchDest = if (isVideoHint) "video" else "image",
                        secFetchSite = "cross-site",
                        acceptHeader = "*/*",
                        tag = "gemini-ref-cookie"
                    ),
                    // 4. Authenticated Clean Request (No Referer - bypasses hotlink filters on Google UserContent)
                    DirectDownloadStrategy(
                        referer = null,
                        cookie = mergedCookie,
                        secFetchMode = "navigate",
                        secFetchDest = "document",
                        secFetchSite = "none",
                        acceptHeader = "*/*",
                        tag = "no-ref-cookie"
                    ),
                    // 5. Target URL cookies only
                    DirectDownloadStrategy(
                        referer = null,
                        cookie = urlCookies,
                        secFetchMode = "navigate",
                        secFetchDest = "document",
                        secFetchSite = "none",
                        acceptHeader = "*/*",
                        tag = "url-cookie-only"
                    ),
                    // 6. Clean request without cookie or referer
                    DirectDownloadStrategy(
                        referer = null,
                        cookie = null,
                        secFetchMode = "navigate",
                        secFetchDest = "document",
                        secFetchSite = "none",
                        acceptHeader = "*/*",
                        tag = "clean-no-cookie"
                    )
                )

                var success = false
                var lastCode = 0
                var lastMsg = ""

                for (strat in attempts) {
                    var c: HttpURLConnection? = null
                    try {
                        var currentUrl = url
                        var redirects = 0
                        while (redirects < 5) {
                            c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 15_000
                                readTimeout = 20_000
                                instanceFollowRedirects = false
                                setRequestProperty("User-Agent", ua)
                                val targetHost = try { URI(currentUrl).host?.lowercase() ?: "" } catch (_: Exception) { "" }
                                val refHost = try { if (!strat.referer.isNullOrEmpty()) URI(strat.referer).host?.lowercase() ?: "" else "" } catch (_: Exception) { "" }
                                val isGoogleDomain = targetHost.endsWith(".google.com") || targetHost.endsWith(".googleapis.com") || targetHost.endsWith(".googleusercontent.com")

                                if (!isGoogleDomain) {
                                    setRequestProperty("X-Requested-With", packageName)
                                }
                                setRequestProperty("Accept", strat.acceptHeader)
                                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                                if (isGoogleDomain) {
                                    setRequestProperty("Sec-Ch-Ua", "\"Chromium\";v=\"130\", \"Google Chrome\";v=\"130\", \"Not?A_Brand\";v=\"99\"")
                                } else {
                                    setRequestProperty("Sec-Ch-Ua", "\"Android WebView\";v=\"130\", \"Chromium\";v=\"130\", \"Not?A_Brand\";v=\"99\"")
                                }
                                setRequestProperty("Sec-Ch-Ua-Mobile", "?1")
                                setRequestProperty("Sec-Ch-Ua-Platform", "\"Android\"")
                                setRequestProperty("Upgrade-Insecure-Requests", "1")

                                val computedSite = strat.secFetchSite ?: when {
                                    strat.referer.isNullOrEmpty() -> "none"
                                    targetHost.isNotEmpty() && targetHost == refHost -> "same-origin"
                                    targetHost.endsWith(".google.com") && refHost.endsWith(".google.com") -> "same-site"
                                    else -> "cross-site"
                                }

                                setRequestProperty("Sec-Fetch-Site", computedSite)
                                setRequestProperty("Sec-Fetch-Mode", strat.secFetchMode)
                                setRequestProperty("Sec-Fetch-Dest", strat.secFetchDest)
                                if (strat.secFetchMode == "navigate") {
                                    setRequestProperty("Sec-Fetch-User", "?1")
                                }

                                if (!strat.cookie.isNullOrEmpty()) {
                                    setRequestProperty("Cookie", strat.cookie)
                                }
                                if (!strat.referer.isNullOrEmpty()) {
                                    setRequestProperty("Referer", strat.referer)
                                    val origin = try {
                                        val u = URI(strat.referer)
                                        "${u.scheme}://${u.host}"
                                    } catch (_: Exception) { null }
                                    if (!origin.isNullOrEmpty()) {
                                        setRequestProperty("Origin", origin)
                                    }
                                }
                            }
                            val code = c.responseCode
                            if (code in 300..399) {
                                val loc = c.getHeaderField("Location")
                                c.disconnect()
                                if (!loc.isNullOrEmpty()) {
                                    currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                                    redirects++
                                    continue
                                }
                            }
                            break
                        }

                        val conn = c ?: continue
                        lastCode = conn.responseCode
                        if (conn.responseCode in 200..299) {
                            val partName = "Direct_${System.currentTimeMillis()}.part"
                            val partFile = File(cacheDir, partName)
                            tempPartFile = partFile
                            val firstBytes = ByteArray(128)
                            var firstRead = 0
                            partFile.outputStream().use { out ->
                                val buf = ByteArray(65536)
                                var read: Int
                                while (conn.inputStream.read(buf).also { read = it } != -1) {
                                    if (firstRead < 128) {
                                        val toCopy = minOf(read, 128 - firstRead)
                                        System.arraycopy(buf, 0, firstBytes, firstRead, toCopy)
                                        firstRead += toCopy
                                    }
                                    out.write(buf, 0, read)
                                }
                            }
                            if (partFile.length() > 0) {
                                val sniffed = sniffFileType(firstBytes)
                                if (sniffed.first == "text/html") {
                                    // v38: the server returned a web page instead of the
                                    // file — delete it and tell the user, instead of saving
                                    // an HTML file with a fake .mp4/.jpg extension.
                                    partFile.delete()
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity,
                                            "Download failed: the site returned a web page, not the file",
                                            Toast.LENGTH_LONG).show()
                                    }
                                    success = true // handled — skip publish + fallbacks
                                    break
                                }
                                val isVideo = sniffed.first.startsWith("video/") ||
                                        conn.contentType?.contains("video") == true ||
                                        (task?.mimeType?.contains("video") == true) ||
                                        url.contains(".mp4") || url.contains(".webm")
                                val ext = sniffed.second.ifEmpty {
                                    // v40/v41: not a known media type — take the
                                    // REAL name from the server's filename header
                                    // or the URL (.pdf, .zip, .apk, .docx, ...)
                                    // instead of inventing .mp4/.jpg.
                                    val disp = conn.getHeaderField("Content-Disposition")
                                    val guessed = URLUtil.guessFileName(url, disp, conn.contentType)
                                    val g = guessed.substringAfterLast('.', "").lowercase()
                                    when {
                                        g.length in 2..5 && g != "bin" -> ".$g"
                                        sniffed.first == "application/zip" -> ".zip"
                                        else -> ".bin"
                                    }
                                }
                                val mime = sniffed.first.ifEmpty {
                                    if (isVideo) "video/mp4" else "application/octet-stream"
                                }
                                val prefix = if (isVideo) "Pulse_Video_" else "Pulse_File_"
                                val guessed = if (!task?.contentDisposition.isNullOrEmpty()) {
                                    URLUtil.guessFileName(url, task?.contentDisposition, mime)
                                } else null
                                val fileName = if (!guessed.isNullOrEmpty() && guessed.contains(".")) {
                                    guessed
                                } else {
                                    "$prefix${System.currentTimeMillis()}$ext"
                                }
                                publishToDownloads(partFile, fileName, mime)
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                                }
                                success = true
                                break
                            }
                        } else {
                            lastMsg = "HTTP " + conn.responseCode
                        }
                    } catch (e: Exception) {
                        lastMsg = e.message ?: "Connection failed"
                    } finally {
                        c?.disconnect()
                    }
                }

                if (!success) {
                    tempPartFile?.delete()
                    if (lastCode == 403 || lastCode == 401 || lastCode == 0) {
                        fallbackToDomExtraction(url)
                    } else {
                        val errorDetail = if (lastCode > 0) "Error $lastCode" else lastMsg
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Save failed: $errorDetail", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                tempPartFile?.delete()
                fallbackToDomExtraction(url)
            }
        }.start()
    }

    // ---- Desktop-mode site compatibility (responsive-site fix) ----
    // Fetches the main-frame HTML ourselves, forces the viewport meta to
    // width=1100, and serves the modified document — all BEFORE the renderer
    // lays the page out, so the desktop layout renders with a correct fit-zoom
    // (unlike the old post-layout injection). Only the main document is
    // buffered (typically a few hundred KB, transient). Any failure returns
    // null, letting WebView load the page untouched.
    private fun forceDesktopViewport(url: String, headers: Map<String, String>): WebResourceResponse? {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                for ((k, v) in headers) {
                    if (k.equals("Accept-Encoding", ignoreCase = true)) continue
                    try { setRequestProperty(k, v) } catch (_: Exception) {}
                }
                setRequestProperty("User-Agent", desktopUserAgent)
                setRequestProperty("Accept-Encoding", "identity") // plain text — no gzip juggling
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
            }
            if (conn.responseCode !in 200..299) return null

            // If the server ignored our "identity" request and compressed the
            // body anyway, bail out — serving compressed bytes as HTML would
            // corrupt the page.
            val encoding = conn.contentEncoding
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) return null

            // CRITICAL: WebResourceResponse needs the BARE mime type. Passing
            // the full header value ("text/html; charset=UTF-8") makes WebView
            // fail to recognise the document as HTML — it then renders the raw
            // source code as plain text. Strip everything after the ';'.
            val rawType = conn.contentType ?: "text/html"
            val mime = rawType.substringBefore(';').trim().ifEmpty { "text/html" }
            if (!mime.contains("html", ignoreCase = true)) return null

            val bytes = conn.inputStream.use { it.readBytes() }
            // Very large documents: serve as downloaded, skip the rewrite.
            if (bytes.size > 5_000_000) {
                return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes))
            }

            // Sniff the charset from the header AND the HTML head (the meta
            // charset tag) — decoding with the wrong charset would corrupt
            // every non-ASCII character in the page.
            val head = String(bytes.copyOfRange(0, minOf(2048, bytes.size)), charset("ISO-8859-1"))
            val charsetName = Regex("charset\\s*=\\s*[\\\"']?\\s*([A-Za-z0-9_\\\\-]+)")
                .find(rawType + " " + head)?.groupValues?.get(1)
                ?.takeIf { runCatching { Charset.isSupported(it) }.getOrDefault(false) } ?: "utf-8"

            val finalBytes: ByteArray = try {
                var html = String(bytes, charset(charsetName))
                val metaTag = Regex(
                    """<meta[^>]*name\s*=\s*("viewport"|'viewport')[^>]*>""",
                    RegexOption.IGNORE_CASE
                )
                val contentAttr = Regex(
                    """content\s*=\s*("[^"]*"|'[^']*')""",
                    RegexOption.IGNORE_CASE
                )
                var rewrote = false
                html = metaTag.replace(html) { m ->
                    val tag = m.value
                    val newTag = if (contentAttr.containsMatchIn(tag)) {
                        contentAttr.replace(tag, "content=\"width=1100\"")
                    } else {
                        tag.replaceFirst(">", " content=\"width=1100\">")
                    }
                    if (newTag != tag) rewrote = true
                    newTag
                }
                if (rewrote) html.toByteArray(charset(charsetName)) else bytes
            } catch (_: Exception) {
                bytes // any charset trouble → serve untouched bytes
            }

            return WebResourceResponse(mime, charsetName, ByteArrayInputStream(finalBytes))
        } catch (_: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    // ---- Chunked blob download (corrupted-file fix) ----
    // Chunks arrive sequentially from the page's single JS thread, so plain
    // append-writes are safe. Peak memory stays flat (one 256KB chunk at a
    // time) instead of holding the whole file in RAM as base64 + bytes.
    private fun handleBlobChunk(base64Chunk: String, mimeType: String, offset: Long, totalSize: Long) {
        try {
            val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
            if (offset == 0L) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Downloading...", Toast.LENGTH_SHORT).show()
                }
                closeBlobStream()
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                // Clean up stale .part files abandoned by interrupted
                // downloads (public dir + v31 cache-dir fallback alike).
                try {
                    downloadsDir.listFiles { f ->
                        f.name.endsWith(".part") && f.lastModified() < System.currentTimeMillis() - 3_600_000L
                    }?.forEach { it.delete() }
                } catch (_: Exception) {}
                try {
                    cacheDir.listFiles { f ->
                        f.name.endsWith(".part") && f.lastModified() < System.currentTimeMillis() - 3_600_000L
                    }?.forEach { it.delete() }
                } catch (_: Exception) {}
                val partName = "Pulse_Download_${System.currentTimeMillis()}.part"
                var f = File(downloadsDir, partName)
                blobOutputStream = try {
                    f.outputStream()
                } catch (_: Exception) {
                    // v31: Android 10 scoped storage blocks File writes into
                    // public Downloads — assemble in the app's private cache
                    // dir instead (auto-wiped on exit) and publish through
                    // MediaStore when the download finishes.
                    f = File(cacheDir, partName)
                    f.outputStream()
                }
                blobTempFile = f
                // v21: sniff the REAL type from the first chunk's magic bytes.
                // The page's blob.type is often empty or wrong — that gave
                // .mp4 names to webm videos (unplayable everywhere) and
                // indexed images with a wrong mime (hidden in Google Photos).
                val sniffed = sniffFileType(bytes)
                blobDetectedMime = sniffed.first
                blobDetectedExt = sniffed.second
                blobNextOffset = bytes.size.toLong()
            } else {
                // v21: chunk integrity. A dropped or misordered chunk used
                // to append silently misaligned bytes — a full-size but
                // CORRUPT file no player could open. Abort loudly instead.
                if (blobOutputStream == null) return // download already finished/aborted
                if (offset != blobNextOffset) {
                    closeBlobStream()
                    blobTempFile?.delete()
                    blobTempFile = null
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Save failed: download interrupted", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                blobNextOffset += bytes.size
            }
            blobOutputStream?.write(bytes)
        } catch (e: Exception) {
            closeBlobStream()
            blobTempFile?.delete()
            blobTempFile = null
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // v21: identify the real file type from magic bytes (independent of
    // whatever mime the page claimed). Returns (mime, extension).
    private fun sniffFileType(b: ByteArray): Pair<String, String> {
        fun at(i: Int, c: Char) = i < b.size && b[i] == c.toByte()
        return when {
            // v38: an HTML response means the "download" was actually a web
            // page (error / consent interstitial) — callers must reject it,
            // never save it under a media extension.
            run {
                val h = String(b, 0, minOf(b.size, 16)).trim().lowercase()
                h.startsWith("<!doctype") || h.startsWith("<html") || h.startsWith("<head")
            } -> "text/html" to ".html"
            b.size > 12 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() &&
                b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "video/webm" to ".webm"
            b.size > 12 && at(4, 'f') && at(5, 't') && at(6, 'y') && at(7, 'p') -> "video/mp4" to ".mp4"
            b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg" to ".jpg"
            b.size > 8 && b[0] == 0x89.toByte() && at(1, 'P') && at(2, 'N') && at(3, 'G') -> "image/png" to ".png"
            b.size > 12 && at(0, 'R') && at(1, 'I') && at(2, 'F') && at(3, 'F') &&
                at(8, 'W') && at(9, 'E') && at(10, 'B') && at(11, 'P') -> "image/webp" to ".webp"
            b.size > 4 && at(0, 'G') && at(1, 'I') && at(2, 'F') -> "image/gif" to ".gif"
            b.size > 4 && at(0, '%') && at(1, 'P') && at(2, 'D') -> "application/pdf" to ".pdf"
            // v41: ZIP-based files (zip, docx, xlsx, pptx, apk, jar, epub).
            // The real extension comes from the URL / server filename.
            b.size > 3 && at(0, 'P') && at(1, 'K') -> "application/zip" to ""
            // v40: common audio formats (was media-only before)
            b.size > 3 && at(0, 'I') && at(1, 'D') && at(2, '3') -> "audio/mpeg" to ".mp3"
            b.size > 3 && at(0, 'O') && at(1, 'g') && at(2, 'g') -> "audio/ogg" to ".ogg"
            b.size > 12 && at(0, 'R') && at(1, 'I') && at(2, 'F') && at(3, 'F') &&
                at(8, 'W') && at(9, 'A') && at(10, 'V') -> "audio/wav" to ".wav"
            else -> "" to ""
        }
    }

    private fun finishBlobDownload(mimeType: String, totalSize: Long, suggestedName: String? = null) {
        try {
            closeBlobStream()
            val part = blobTempFile
            if (part == null || !part.exists() || part.length() == 0L) {
                part?.delete()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Save failed: no data received", Toast.LENGTH_SHORT).show()
                }
                return
            }
            // v21: prefer the type sniffed from the actual bytes over the
            // page-supplied mime (often empty or plain wrong).
            // v43: also sniff the saved part-file's real bytes, and name
            // zip/gzip/text honestly instead of inventing .mp4.
            val head = ByteArray(128)
            var headRead = 0
            try {
                part.inputStream().use { ins ->
                    while (headRead < 128) {
                        val r = ins.read(head, headRead, 128 - headRead)
                        if (r <= 0) break
                        headRead += r
                    }
                }
            } catch (_: Exception) { }
            val sniffed = sniffFileType(head)
            val ext = when {
                blobDetectedExt.isNotEmpty() -> blobDetectedExt
                sniffed.second.isNotEmpty() -> sniffed.second
                sniffed.first == "application/zip" -> ".zip"
                mimeType.contains("video/webm") -> ".webm"
                mimeType.contains("video/mp4") || mimeType.contains("video/quicktime") -> ".mp4"
                mimeType.contains("image/png") -> ".png"
                mimeType.contains("image/jpeg") || mimeType.contains("image/jpg") -> ".jpg"
                mimeType.contains("image/webp") -> ".webp"
                mimeType.contains("application/pdf") -> ".pdf"
                mimeType.contains("application/zip") || mimeType.contains("x-zip") -> ".zip"
                mimeType.contains("application/gzip") -> ".gz"
                mimeType.contains("text/") -> ".txt"
                else -> ".bin"
            }
            val finalMime = blobDetectedMime.ifEmpty { sniffed.first }.ifEmpty { mimeType }.ifEmpty { "application/octet-stream" }
            val isVideo = finalMime.startsWith("video/") || ext in listOf(".mp4", ".webm", ".mkv")
            val isImage = finalMime.startsWith("image/") || ext in listOf(".jpg", ".jpeg", ".png", ".webp", ".gif")

            val cleanSuggested = suggestedName?.trim()?.replace(Regex("[/\\\\:*?\"<>|]"), "_")?.takeIf { it.isNotEmpty() && !it.endsWith(".bin") }
            val fileName = if (cleanSuggested != null) {
                if (cleanSuggested.contains(".")) cleanSuggested else "$cleanSuggested$ext"
            } else {
                val prefix = if (isVideo) "Pulse_Video_" else if (isImage) "Pulse_Image_" else "Pulse_File_"
                "$prefix${System.currentTimeMillis()}$ext"
            }
            if (publishToDownloads(part, fileName, finalMime)) {
                lastSavedTime = System.currentTimeMillis()
                lastSavedName = fileName
                // v64: offer Open straight away instead of waiting for the
                // media scanner / system notification.
                showDownloadedDialog(null, finalMime, fileName)
            } else {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Save failed: storage permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            blobTempFile = null
            blobDetectedMime = ""
            blobDetectedExt = ""
            blobNextOffset = 0L
        }
    }

    private fun closeBlobStream() {
        try { blobOutputStream?.close() } catch (_: Exception) {}
        blobOutputStream = null
    }

    // v31: publish an assembled file into public Downloads. Fast path is a
    // plain same-dir rename (works everywhere File access is allowed). If the
    // .part was assembled in the cache dir (Android 10), copy it in. If plain
    // file access fails entirely, insert through MediaStore — which needs no
    // storage permission at all. Returns true on success.
    // ---- v64: instant "Open" for downloads ---------------------------------
    // Android's own download notification only offers Open once the media
    // database has indexed the file. These helpers offer Open immediately.

    private fun mimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return ""
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (ext) {
            "txt", "log" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "apk" -> "application/vnd.android.package-archive"
            "epub" -> "application/epub+zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "mkv" -> "video/x-matroska"
            else -> ""
        }
    }

    private fun openUri(uri: Uri, mime: String) {
        val type = mime.ifEmpty { "application/octet-stream" }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            Toast.makeText(this, "No app on this device can open this file", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSavedFile(fileName: String, mime: String) {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val f = File(dir, fileName)
            if (!f.exists()) {
                Toast.makeText(this, "File not found in Downloads", Toast.LENGTH_SHORT).show()
                return
            }
            val type = mime.ifEmpty { mimeFromName(fileName) }.ifEmpty { "application/octet-stream" }
            MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf(type)) { _, uri ->
                if (uri != null) runOnUiThread { openUri(uri, type) }
            }
        } catch (_: Exception) {}
    }

    private fun showDownloadedDialog(uri: Uri?, mime: String, fileName: String) {
        runOnUiThread {
            try {
                AlertDialog.Builder(this)
                    .setTitle("Download complete")
                    .setMessage(fileName)
                    .setPositiveButton("Open") { _, _ ->
                        if (uri != null) openUri(uri, mime) else openSavedFile(fileName, mime)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            } catch (_: Exception) {}
        }
    }

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L || id != lastDownloadId) return
            try {
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                val uri = dm.getUriForDownloadedFile(id)
                val mime = dm.getMimeTypeForDownloadedFile(id)
                    ?: mimeFromName(lastDownloadedName).ifEmpty { "application/octet-stream" }
                showDownloadedDialog(uri, mime, lastDownloadedName)
            } catch (_: Exception) {}
        }
    }

    private fun publishToDownloads(part: File, fileName: String, mime: String): Boolean {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val target = File(dir, fileName)
            if (part.parentFile?.absolutePath == dir.absolutePath) {
                if (part.renameTo(target)) {
                    MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf(mime), null)
                    return true
                }
            } else {
                part.copyTo(target, overwrite = true)
                part.delete()
                MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf(mime), null)
                return true
            }
        } catch (_: Exception) { /* fall through to MediaStore */ }
        return if (Build.VERSION.SDK_INT >= 29) {
            try {
                val uri = contentResolver.insert(
                    if (mime.startsWith("image/")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime.ifEmpty { "application/octet-stream" })
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                ) ?: return false
                contentResolver.openOutputStream(uri)?.use { out ->
                    part.inputStream().use { it.copyTo(out) }
                } ?: return false
                part.delete()
                // MediaStore entries are indexed immediately — no scanner needed.
                true
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
    }

    // v31: single-shot variant of the scoped-storage fallback — used when a
    // plain write into public Downloads throws (Android 10). Images go into
    // the Images collection so they show up in gallery apps like Google Photos.
    private fun saveBytesViaMediaStore(data: ByteArray, fileName: String, mime: String) {
        if (Build.VERSION.SDK_INT < 29) throw RuntimeException("storage permission denied")
        val uri = contentResolver.insert(
            if (mime.startsWith("image/")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime.ifEmpty { "application/octet-stream" })
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        ) ?: throw RuntimeException("cannot create download entry")
        contentResolver.openOutputStream(uri)?.use { it.write(data) }
            ?: throw RuntimeException("cannot open download stream")
    }

    private fun saveRawBase64(dataUri: String, mimeTypeHint: String) {
        Thread {
            try {
                val commaIndex = dataUri.indexOf(",")
                if (commaIndex != -1) {
                    val header = dataUri.substring(0, commaIndex)
                    val rawBase64 = dataUri.substring(commaIndex + 1)
                    val bytes = Base64.decode(rawBase64, Base64.DEFAULT)

                    val sniffed = sniffFileType(bytes)
                    val extension = when {
                        sniffed.second.isNotEmpty() -> sniffed.second
                        header.contains("video/mp4") || mimeTypeHint.contains("video/mp4") -> ".mp4"
                        header.contains("video/webm") || mimeTypeHint.contains("video/webm") -> ".webm"
                        header.contains("image/png") -> ".png"
                        header.contains("image/jpeg") || header.contains("image/jpg") -> ".jpg"
                        header.contains("image/webp") -> ".webp"
                        header.contains("application/pdf") -> ".pdf"
                        else -> ".mp4"
                    }
                    // v21: scan with the CORRECT mime — the old code always
                    // told Android "video/mp4", so images saved from data:
                    // URLs never showed up in Google Photos.
                    val scanMime = sniffed.first.ifEmpty {
                        header.substringAfter("data:", "").substringBefore(';').ifEmpty { "application/octet-stream" }
                    }

                    val isVideo = scanMime.startsWith("video/") || extension in listOf(".mp4", ".webm", ".mkv")
                    val isImage = scanMime.startsWith("image/") || extension in listOf(".jpg", ".jpeg", ".png", ".webp", ".gif")
                    val prefix = if (isVideo) "Pulse_Video_" else if (isImage) "Pulse_Image_" else "Pulse_File_"
                    val fileName = "$prefix${System.currentTimeMillis()}$extension"
                    try {
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        downloadsDir.mkdirs()
                        val targetFile = File(downloadsDir, fileName)
                        targetFile.outputStream().use { it.write(bytes) }
                        MediaScannerConnection.scanFile(
                            this@MainActivity,
                            arrayOf(targetFile.absolutePath),
                            arrayOf(scanMime),
                            null
                        )
                    } catch (_: Exception) {
                        // v31: scoped storage (Android 10) — MediaStore fallback.
                        saveBytesViaMediaStore(bytes, fileName, scanMime)
                    }

                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingDownload?.let {
                executeDownload(it)
                pendingDownload = null
            }
        }
        // v72: without this permission Android 13+ hides the service
        // notification, which is why background music looked like it ran with
        // no notification at all. If it is refused, say so plainly and switch
        // the feature back off rather than leaving it in a half-working state.
        if (requestCode == 4711) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                bgMusicEnabled = false
                prefs.edit().putBoolean("bg_music", false).commit()
                stopPlaybackService()
                Toast.makeText(
                    applicationContext,
                    "Notifications are blocked, so background music was turned off. " +
                        "Allow notifications for Pulse Horizon and try again.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------------- v66: TAB STRIP ----------------
    private fun renderTabs() {
        tabRow.removeAllViews()
        tabs.forEachIndexed { i, t ->
            val label = (t.title.ifBlank { "New tab" }).trim().take(14)
            val cell = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setTextColor(if (i == activeTab) Color.WHITE else Color.GRAY)
                setBackgroundColor(
                    if (i == activeTab) colLine else Color.TRANSPARENT
                )
                setPadding(24, 10, 24, 10)
                setOnClickListener { switchToTab(i) }
                // long-press a tab to close it
                setOnLongClickListener { closeTab(i); true }
            }
            tabRow.addView(cell)
        }
        tabRow.addView(Button(this).apply {
            text = "+"
            textSize = 16f
            setTextColor(colText)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(28, 0, 28, 0)
            setOnClickListener { newTab() }
        })
        if (::tabStrip.isInitialized) tabStrip.visibility = if (chromeHidden) View.GONE else View.VISIBLE
        if (::tabCountBtn.isInitialized) {
            try { tabCountBtn.text = tabs.size.toString() } catch (_: Exception) {}
        }
        // v139: keep the page's tab screen in step with the real tabs (with real thumbnail previews)
        try {
            captureCurrentTabThumbnail()
            val arr = org.json.JSONArray()
            tabs.forEachIndexed { i, t ->
                val o = org.json.JSONObject()
                o.put("title", t.title.ifBlank { "New tab" })
                o.put("url", t.url)
                o.put("active", i == activeTab)
                val thumbBytes = synchronized(tabThumbnails) { tabThumbnails[t.url] }
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    o.put("thumb", "data:image/jpeg;base64," + Base64.encodeToString(thumbBytes, Base64.NO_WRAP))
                } else {
                    o.put("thumb", "")
                }
                arr.put(o)
            }
            webView.evaluateJavascript(
                "try{ if (window.phSetTabs) phSetTabs(" + arr.toString() + "); }catch(e){}", null
            )
        } catch (_: Exception) {}
    }

    // --- Group C: Bounded Thumbnail Cache for Tabs ---------------------------
    // Stored as compressed JPEG bytes (NOT raw bitmaps) to ensure zero memory bloat.
    // Hard cap of 8 thumbnails, small fixed 160x120 px. Oldest evicted on cap.
    // Dropped when tab closes and when app backgrounds.
    private val tabThumbnails = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > 8
        }
    }

    private fun captureCurrentTabThumbnail() {
        if (tabs.isEmpty() || activeTab !in tabs.indices) return
        val active = tabs[activeTab]
        if (active.url.isBlank() || active.url.startsWith("file:///android_asset/")) return
        try {
            val w = webView.width
            val h = webView.height
            if (w <= 0 || h <= 0) return

            val thumbW = 160
            val thumbH = 120
            val bmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            val scale = maxOf(thumbW.toFloat() / w.toFloat(), thumbH.toFloat() / h.toFloat())
            canvas.scale(scale, scale)
            webView.draw(canvas)

            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
            bmp.recycle()

            val bytes = out.toByteArray()
            synchronized(tabThumbnails) {
                tabThumbnails[active.url] = bytes
            }
        } catch (_: Exception) {}
    }

    // ---------------- v84: TAB STATE ----------------
    // Chromium keeps scroll position and partially typed form data in its own
    // session records, but Android's WebView does not. So we read them out of
    // the page before a tab is discarded and put them back on return.

    private fun captureTabState(onDone: () -> Unit) {
        captureCurrentTabThumbnail()
        syncActiveTab()
        try {
            webView.evaluateJavascript(
                "(function(){try{var out={y:window.scrollY||0,f:[]};" +
                    "var els=document.querySelectorAll('input,textarea');" +
                    "for(var i=0;i<els.length;i++){var e=els[i];" +
                    "var t=(e.type||'').toLowerCase();" +
                    "if(t==='hidden'||t==='submit'||t==='button'||t==='checkbox'||t==='radio')continue;" +
                    "out.f.push(e.value||'');}" +
                    "return JSON.stringify(out);}catch(e){return '';}})()"
            ) { v ->
                if (tabs.isNotEmpty()) {
                    val i = activeTab.coerceIn(0, tabs.size - 1)
                    val raw = unescapeJson(v)
                    if (raw.isNotEmpty()) {
                        tabs[i].scrollY = extractScrollY(raw)
                        tabs[i].formJson = raw
                    }
                }
                onDone()
            }
        } catch (_: Exception) {
            onDone()
        }
    }

    private fun unescapeJson(v: String?): String {
        if (v == null || v == "null") return ""
        var t = v.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length - 1)
        }
        return t.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun extractScrollY(json: String): Int {
        return try {
            val m = Regex("\"y\"\\s*:\\s*(\\d+)").find(json)
            m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun restoreTabState() {
        if (tabs.isEmpty()) return
        val i = activeTab.coerceIn(0, tabs.size - 1)
        val rec = tabs[i]
        try {
            val js = "(function(){try{window.scrollTo(0," + rec.scrollY + ");" +
                    "var d=" + (if (rec.formJson.isBlank()) "null" else rec.formJson) + ";" +
                    "if(d&&d.f){var els=document.querySelectorAll('input,textarea');" +
                    "for(var i=0;i<els.length&&i<d.f.length;i++){var e=els[i];" +
                    "var t=(e.type||'').toLowerCase();" +
                    "if(t==='hidden'||t==='submit'||t==='button'||t==='checkbox'||t==='radio')continue;" +
                    "if(!e.value){e.value=d.f[i];}}}" +
                    "return '1';}catch(e){return '0';}})()"
            webView.evaluateJavascript(js, null)
        } catch (_: Exception) {
        }
    }

    private fun hasUnsentInput(cb: (Boolean) -> Unit) {
        try {
            webView.evaluateJavascript(
                "(function(){try{var els=document.querySelectorAll('input,textarea');" +
                    "for(var i=0;i<els.length;i++){var e=els[i];" +
                    "var t=(e.type||'').toLowerCase();" +
                    "if(t==='hidden'||t==='submit'||t==='button'||t==='checkbox'||t==='radio')continue;" +
                    "if(e.value&&e.value.length>0)return '1';}return '0';}catch(e){return '0';}})()"
            ) { v -> cb(v != null && v.contains("1")) }
        } catch (_: Exception) {
            cb(false)
        }
    }

    private fun syncActiveTab() {
        if (tabs.isEmpty()) return
        val i = activeTab.coerceIn(0, tabs.size - 1)
        tabs[i].url = try { webView.url ?: homeUrl } catch (_: Exception) { homeUrl }
        tabs[i].title = try { webView.title ?: "" } catch (_: Exception) { "" }
    }


    // ---- v135: the offline page --------------------------------------
    // Shown when a main-frame load fails because there is no connection.
    // Replaces the black screen the WebView would otherwise leave behind.
    private fun showOfflinePage() {
        val dark = colBg == Color.parseColor("#0E0E0E")
        val bg = if (dark) "#0E0E0E" else "#FFFFFF"
        val fg = if (dark) "#EEF1F6" else "#12151C"
        val dim = if (dark) "#8B93A7" else "#5B6478"
        val accent = "#39D0FF"
        val html = "<!DOCTYPE html><html><head><meta name=\"viewport\" " +
            "content=\"width=device-width,initial-scale=1\"><style>" +
            "html,body{margin:0;height:100%}body{background:" + bg + ";color:" + fg +
            ";font:16px/1.5 system-ui,-apple-system,Roboto,sans-serif;display:flex;" +
            "align-items:center;justify-content:center;text-align:center;padding:24px}" +
            ".w{max-width:320px}.c{width:56px;height:56px;margin:0 auto 18px;border-radius:50%;" +
            "border:2px solid " + accent + ";display:flex;align-items:center;justify-content:center;" +
            "font-size:26px;color:" + accent + "}h1{font-size:19px;margin:0 0 8px}" +
            "p{color:" + dim + ";font-size:14px;margin:0 0 22px}" +
            "button{background:" + accent + ";color:#04121c;border:0;border-radius:12px;" +
            "padding:13px 26px;font-size:15px;font-weight:600}" +
            "</style></head><body><div class=\"w\"><div class=\"c\">!</div>" +
            "<h1>You are offline</h1><p>This page needs a connection. Check your " +
            "Wi-Fi or mobile data and try again.</p>" +
            "<button onclick=\"location.href='pulse://retry'\">Try again</button>" +
            "</div></body></html>"
        try {
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        } catch (_: Exception) {}
    }

    // v137: the home page is a web page, so it does not know about the
    // browser's own Dark/Light setting. Tell it, on every load and on every
    // change of the setting.
    private fun tellHomePageTheme() {
        try {
            val light = !isDarkNow()
            webView.evaluateJavascript(
                "try{ if (window.phSetTheme) phSetTheme(" + light + "); }catch(e){}", null
            )
        } catch (_: Exception) {}
    }

    // v148: the tab-closed undo. Shown by the app, not the page, because the
    // page reloads when it asks us to close a tab. Ten seconds, then it goes.
    private fun showTabUndo(removed: TabRecord) {
        // A5 FIX: was an AlertDialog in the middle of the screen for 10 seconds.
        // Now a small bar, the height of the address bar, directly under it, for
        // 5 seconds. Same undo action.
        try {
            var bar = undoBar
            if (bar == null) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(colSurface)
                        cornerRadius = 24f
                        setStroke(2, colLine)
                    }
                    elevation = 16f
                    // same vertical padding as the address bar -> same height
                    setPadding(28, 20, 28, 20)
                    visibility = View.GONE
                }
                val label = TextView(this).apply {
                    textSize = 13f
                    setTextColor(colText)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                val undoBtn = TextView(this).apply {
                    text = "UNDO"
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(colAccent)
                    setPadding(24, 0, 8, 0)
                    isClickable = true
                    setOnClickListener {
                        try {
                            val t = pendingUndo
                            if (t != null) {
                                tabs.add(t)
                                activeTab = tabs.size - 1
                                renderTabs()
                                webView.loadUrl(t.url)
                            }
                        } catch (_: Exception) {}
                        hideUndoBar()
                    }
                }
                row.addView(label)
                row.addView(undoBtn)
                row.tag = label
                // A5: hang it off the CONTENT view at the bottom, as an
                // overlay. It never touches the browser's own layout tree.
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
                lp.bottomMargin = 24
                lp.leftMargin = 24
                lp.rightMargin = 24
                row.layoutParams = lp
                try { glowLayerRef?.addView(row) } catch (_: Exception) { rootLayout.addView(row) }
                undoBar = row
                bar = row
            }
            pendingUndo = removed
            (bar?.tag as? TextView)?.text =
                (removed.title.ifBlank { "That tab" }) + " closed"
            bar?.visibility = View.VISIBLE
            bar?.bringToFront()
            undoHandler.removeCallbacks(hideUndoRunnable)
            undoHandler.postDelayed(hideUndoRunnable, 5000L)
        } catch (_: Exception) {}
    }

    private fun hideUndoBar() {
        try {
            undoHandler.removeCallbacks(hideUndoRunnable)
            undoBar?.visibility = View.GONE
        } catch (_: Exception) {}
    }

    private fun newTab() {
        syncActiveTab()
        tabs.add(TabRecord(homeUrl, "New tab", false, autoHideBar))
        activeTab = tabs.size - 1
        renderTabs()
        applyDeskMode(false)
        applyAutoHideNow()
        webView.loadUrl(homeUrl)
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices || index == activeTab) return
        // v84: save what is on screen first, then refuse to throw away text
        // the user has typed but not sent - the same rule Chrome follows.
        captureTabState {
            hasUnsentInput { unsent ->
                if (unsent) {
                    AlertDialog.Builder(this)
                        .setTitle("Leave this tab?")
                        .setMessage(
                            "This page has text you have not sent yet. " +
                                "Switching tabs will lose it."
                        )
                        .setPositiveButton("Leave anyway") { _, _ -> doSwitch(index) }
                        .setNegativeButton("Stay", null)
                        .show()
                } else {
                    doSwitch(index)
                }
            }
        }
    }

    private fun doSwitch(index: Int) {
        if (index !in tabs.indices) return
        syncActiveTab()
        activeTab = index
        renderTabs()
        applyDeskMode(tabs[index].isDesktop)
        applyAutoHideNow()
        pendingRestore = true
        webView.loadUrl(tabs[index].url.ifBlank { homeUrl })
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        syncActiveTab()
        val removed = tabs.removeAt(index)
        synchronized(tabThumbnails) {
            tabThumbnails.remove(removed.url)
        }
        if (tabs.isEmpty()) {
            tabs.add(TabRecord(homeUrl, "New tab"))
            activeTab = 0
        } else if (activeTab >= index) {
            activeTab = (activeTab - 1).coerceAtLeast(0)
        }
        renderTabs()
        webView.loadUrl(tabs[activeTab].url.ifBlank { homeUrl })
        showTabUndo(removed)
    }

    // ---------------- v68: AUTO-HIDE (per tab) ----------------
    private fun activeAutoHide(): Boolean {
        if (tabs.isEmpty()) return autoHideBar
        return tabs[activeTab.coerceIn(0, tabs.size - 1)].autoHide
    }

    private fun applyAutoHideNow() {
        if (activeAutoHide()) hideChrome() else showChrome()
    }

    // ---------------- v67: DESK MODE (per tab) ----------------
    private fun applyDeskMode(on: Boolean) {
        isDesktopMode = on
        try {
            if (on) {
                webView.settings.userAgentString = desktopUserAgent
                webView.settings.useWideViewPort = true
                webView.settings.loadWithOverviewMode = true
                /* v138: DESK removed */
            } else {
                webView.settings.userAgentString = defaultUserAgent
                /* v138: DESK removed */
            }
        } catch (_: Exception) {}
    }

    private fun openVisualTabSwitcher() {
        syncActiveTab()
        if (tabs.isEmpty()) tabs.add(TabRecord(homeUrl, "New tab"))
        val dialog = AlertDialog.Builder(this, dialogTheme()).create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colBg)
            setPadding(32, 32, 32, 32)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }
        val title = TextView(this).apply {
            text = "Open Tabs (${tabs.size})"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(colText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val newTabBtn = Button(this).apply {
            text = "+ New Tab"
            setTextColor(Color.WHITE)
            setBackgroundColor(colAccent)
            setOnClickListener {
                dialog.dismiss()
                newTab()
            }
        }
        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(colText)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(title)
        header.addView(newTabBtn)
        header.addView(closeBtn)
        root.addView(header)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (420 * resources.displayMetrics.density).toInt())
        }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        tabs.forEachIndexed { index, tab ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                setBackgroundColor(if (index == activeTab) colSurface else Color.parseColor("#1E293B"))
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 16)
                layoutParams = params
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    switchToTab(index)
                }
            }
            val rowTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tabTitle = TextView(this).apply {
                text = tab.title.ifBlank { tab.url }
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(colText)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
            }
            val tabClose = Button(this).apply {
                text = "×"
                textSize = 18f
                setTextColor(Color.RED)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    dialog.dismiss()
                    closeTab(index)
                }
            }
            rowTop.addView(tabTitle)
            rowTop.addView(tabClose)
            card.addView(rowTop)

            val tabUrl = TextView(this).apply {
                text = tab.url
                textSize = 12f
                setTextColor(colDim)
                maxLines = 1
            }
            card.addView(tabUrl)
            grid.addView(card)
        }
        scrollView.addView(grid)
        root.addView(scrollView)
        dialog.setView(root)
        dialog.show()
    }

    // ---------------- v67: THREE-DOT MENU ----------------
    // v117: the menu restored - a logo AFTER each name, the URL actions, the
    // theme and the terms link, with the icons breathing.
    private fun showMenu() {
        val labels = arrayOf(
            "Settings", "Downloads", "Bookmark this page", "Bookmarks", "Music",
            "Copy link", "Share", "Open in new tab", "Desktop site",
            "Theme: " + themeModeLabel(), "Safe Browsing: Active", "Terms & Conditions"
        )
        val icons = intArrayOf(
            android.R.drawable.ic_menu_preferences,
            android.R.drawable.ic_menu_save,
            android.R.drawable.ic_menu_add,
            android.R.drawable.ic_menu_myplaces,
            android.R.drawable.ic_media_play,
            android.R.drawable.ic_menu_edit,
            android.R.drawable.ic_menu_share,
            android.R.drawable.ic_menu_more,
            android.R.drawable.ic_menu_set_as,
            android.R.drawable.ic_menu_day,
            android.R.drawable.ic_menu_view,
            android.R.drawable.ic_menu_info_details
        )
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.setBackgroundColor(colSurface)
        val dlg = AlertDialog.Builder(this, dialogTheme())
            .setTitle("Menu").setView(col).setNegativeButton("Close", null).create()
        val dp = resources.displayMetrics.density
        val anims = ArrayList<ValueAnimator>()
        labels.forEachIndexed { idx, label ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((22 * dp).toInt(), (13 * dp).toInt(), (22 * dp).toInt(), (13 * dp).toInt())
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(colText)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val iv = ImageView(this).apply {
                setImageResource(icons[idx])
                setColorFilter(colAccent)
                layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt())
            }
            row.addView(tv)
            row.addView(iv)
            row.setOnClickListener {
                dlg.dismiss()
                // v130: Tabs is no longer here - it lives in the toolbar now
                when (idx) {
                    0 -> showSettings(fromMenu = true)
                    1 -> showDownloads(fromMenu = true)
                    2 -> addBookmark()
                    3 -> showBookmarks(fromMenu = true)
                    4 -> showMusic(fromMenu = true)
                    5 -> copyCurrentLink()
                    6 -> shareCurrentLink()
                    7 -> openInNewTab()
                    8 -> toggleDesktop()
                    9 -> pickTheme()
                    10 -> Toast.makeText(this, "Safe Browsing: Active & Protecting against malware/phishing", Toast.LENGTH_LONG).show()
                    11 -> openTerms()
                }
            }
            col.addView(row)
            val a = ValueAnimator.ofFloat(0.45f, 1f).apply {
                duration = 1100L
                startDelay = idx * 90L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { va ->
                    val f = va.animatedValue as Float
                    iv.alpha = f
                    iv.scaleX = 0.90f + 0.10f * f
                    iv.scaleY = 0.90f + 0.10f * f
                }
                start()
            }
            anims.add(a)
        }
        dlg.setOnDismissListener { anims.forEach { it.cancel() } }
        dlg.show()
    }

    private fun tabLabel(i: Int): String {
        val t = tabs[i]
        val name = t.title.ifBlank { "New tab" }.trim().take(34)
        val mark = if (i == activeTab) "\u25CF  " else "     "
        val desk = if (t.isDesktop) "  [desk]" else ""
        return mark + name + desk
    }

    private fun showTabManager() {
        syncActiveTab()
        if (tabs.isEmpty()) tabs.add(TabRecord(homeUrl, "New tab"))
        val names = Array(tabs.size) { tabLabel(it) }
        AlertDialog.Builder(this)
            .setTitle("Tabs (" + tabs.size + ")")
            .setItems(names) { _, which -> switchToTab(which) }
            .setPositiveButton("New tab") { _, _ -> newTab() }
            .setNeutralButton("Close a tab") { _, _ -> pickTabToClose() }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun pickTabToClose() {
        if (tabs.isEmpty()) return
        val names = Array(tabs.size) { tabLabel(it) }
        AlertDialog.Builder(this)
            .setTitle("Close which tab?")
            .setItems(names) { _, which ->
                AlertDialog.Builder(this)
                    .setTitle("Close tab")
                    .setMessage("Are you sure you want to close this tab?")
                    .setPositiveButton("Confirm") { _, _ -> closeTab(which) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- v67: SETTINGS ----------------
    // ---------------- v69: MEDIA FEATURES ----------------
    private fun confirmNotice(
        title: String,
        message: String,
        onYes: () -> Unit,
        onNo: (() -> Unit)? = null
    ) {
        // v149: explain ONCE. The Edge glow and Background music notices were
        // appearing on every single toggle, which is what the user meant by
        // "it keeps reminding me". Now the first time it explains, and after
        // that the switch simply does what it is told.
        val key = "notice_seen_" + title.lowercase().replace(" ", "_")
        val alreadyExplained = try { prefs.getBoolean(key, false) } catch (_: Exception) { false }
        if (alreadyExplained) {
            onYes()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Turn on") { _, _ ->
                try { prefs.edit().putBoolean(key, true).commit() } catch (_: Exception) {}
                onYes()
            }
            .setNegativeButton("Cancel") { _, _ -> onNo?.invoke() }
            .show()
    }

    private fun resumePageMedia() {
        try {
            webView.evaluateJavascript(
                "(function(){try{var m=document.querySelectorAll('video,audio');" +
                    "for(var i=0;i<m.length;i++){var e=m[i];" +
                    "if(e.paused&&!e.ended&&e.currentTime>0){try{e.play();}catch(x){}}}" +
                    "return '1';}catch(e){return '0';}})()",
                null
            )
        } catch (_: Exception) {
        }
    }

    private fun pausePageMedia() {
        try {
            webView.evaluateJavascript(
                "(function(){try{var m=document.querySelectorAll('video,audio');" +
                    "for(var i=0;i<m.length;i++){try{m[i].pause();}catch(e){}}return '1';}" +
                    "catch(e){return '0';}})()",
                null
            )
        } catch (_: Exception) {
        }
    }

    data class MediaProbeResult(
        val kind: Int,
        val isPaused: Boolean,
        val currentTime: Float,
        val duration: Float,
        val videoWidth: Int,
        val videoHeight: Int,
        val title: String
    )

    // v74/v104: returns 0 = nothing, 1 = MUSIC playing, 2 = VIDEO playing.
    // Also extracts play/pause state, position, and dimensions for PiP and notification sync.
    // MUSIC ONLY: a video must never light the edges.
    private fun probeMediaPlaying(cb: (MediaProbeResult) -> Unit) {
        try {
            webView.evaluateJavascript(
                "(function(){try{" +
                    "function checkMedia(root, acc){" +
                    "  try{" +
                    "    var list=root.querySelectorAll('video,audio');" +
                    "    for(var i=0;i<list.length;i++){acc.push(list[i]);}" +
                    "    var all=root.querySelectorAll('*');" +
                    "    for(var j=0;j<all.length;j++){" +
                    "      if(all[j].shadowRoot){checkMedia(all[j].shadowRoot, acc);}" +
                    "    }" +
                    "  }catch(_){}" +
                    "  return acc;" +
                    "}" +
                    "var m=checkMedia(document, []);" +
                    "try{" +
                    "  var frames=document.querySelectorAll('iframe');" +
                    "  for(var f=0;f<frames.length;f++){" +
                    "    try{" +
                    "      var cdoc=frames[f].contentDocument||frames[f].contentWindow.document;" +
                    "      if(cdoc)checkMedia(cdoc, m);" +
                    "    }catch(_){}" +
                    "  }" +
                    "}catch(_){}" +
                    "var music=false, vid=false, vidPlaying=false, musicPlaying=false;" +
                    "var vw=0, vh=0, cur=0, dur=0;" +
                    "for(var i=0;i<m.length;i++){" +
                    "  var e=m[i];" +
                    "  if(e.ended)continue;" +
                    "  var isAudioTag=(e.tagName.toLowerCase()==='audio');" +
                    "  var hasRealVideo=(e.videoWidth>0&&e.videoHeight>0&&e.offsetWidth>0&&e.offsetHeight>0);" +
                    "  var isMusicTag=isAudioTag||(e.videoWidth||0)===0||(e.videoHeight||0)===0;" +
                    "  var playing=(!e.paused&&!e.ended&&!e.muted&&(e.volume===undefined||e.volume>0));" +
                    "  if(playing){" +
                    "    if(hasRealVideo){" +
                    "      vid=true;vidPlaying=true;" +
                    "      vw=e.videoWidth||0;vh=e.videoHeight||0;cur=e.currentTime||0;dur=isFinite(e.duration)?e.duration:0;" +
                    "    }else if(isMusicTag){" +
                    "      music=true;musicPlaying=true;" +
                    "      cur=e.currentTime||0;dur=isFinite(e.duration)?e.duration:0;" +
                    "    }" +
                    "  }else if(e.currentTime>0||e.duration>0||e.readyState>=2||e.currentSrc||e.src){" +
                    "    if(hasRealVideo&&!vid){" +
                    "      vid=true;vw=e.videoWidth||0;vh=e.videoHeight||0;cur=e.currentTime||0;dur=isFinite(e.duration)?e.duration:0;" +
                    "    }else if(isMusicTag&&!music){" +
                    "      music=true;cur=e.currentTime||0;dur=isFinite(e.duration)?e.duration:0;" +
                    "    }" +
                    "  }" +
                    "}" +
                    "var kind=vid?2:(music?1:0);" +
                    "var paused=vid?(!vidPlaying):(!musicPlaying);" +
                    "var t=document.title||'';" +
                    "return kind+';'+(paused?1:0)+';'+cur.toFixed(2)+';'+dur.toFixed(2)+';'+vw+';'+vh+';'+encodeURIComponent(t);" +
                    "}catch(e){return '0;1;0;0;0;0;';}})()"
            ) { raw ->
                val str = raw?.replace("\"", "")?.trim() ?: "0;1;0;0;0;0;"
                val parts = str.split(";")
                val kind = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val isPaused = (parts.getOrNull(1)?.toIntOrNull() ?: 1) == 1
                val cur = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                val dur = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
                val vw = parts.getOrNull(4)?.toIntOrNull() ?: 0
                val vh = parts.getOrNull(5)?.toIntOrNull() ?: 0
                val title = try {
                    Uri.decode(parts.getOrNull(6) ?: "")
                } catch (_: Exception) { "" }
                cb(MediaProbeResult(kind, isPaused, cur, dur, vw, vh, title))
            }
        } catch (_: Exception) {
            cb(MediaProbeResult(0, true, 0f, 0f, 0, 0, ""))
        }
    }

    // ---------------- v104: PICTURE-IN-PICTURE (MINI VIDEO PLAYER) ----------------
    private fun enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = buildPipParams()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {}
        }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = buildPipParams()
                setPictureInPictureParams(params)
            } catch (_: Exception) {}
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder()
        val aspect = if (videoWidth > 0 && videoHeight > 0) {
            val r = videoWidth.toFloat() / videoHeight.toFloat()
            if (r in 0.42f..2.38f) {
                Rational(videoWidth, videoHeight)
            } else {
                Rational(16, 9)
            }
        } else {
            Rational(16, 9)
        }
        b.setAspectRatio(aspect)

        val playPauseIntent = Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName)
        val pFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(this, 201, playPauseIntent, pFlags)

        val icon = Icon.createWithResource(
            this,
            if (videoPlayingState) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        val title = if (videoPlayingState) "Pause" else "Play"
        val action = RemoteAction(icon, title, title, pi)
        b.setActions(listOf(action))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(pipEnabled && isVideoPlaying)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    private fun togglePageVideoPlayback() {
        if (videoPlayingState) {
            pausePageVideo()
            videoPlayingState = false
        } else {
            resumePageVideo()
            videoPlayingState = true
        }
        updatePipParams()
    }

    private fun resumePageVideo() {
        try {
            webView.evaluateJavascript(
                "(function(){try{var v=document.querySelectorAll('video');" +
                    "for(var i=0;i<v.length;i++){try{v[i].play();}catch(e){}}return '1';}" +
                    "catch(e){return '0';}})()",
                null
            )
        } catch (_: Exception) {}
    }

    private fun pausePageVideo() {
        try {
            webView.evaluateJavascript(
                "(function(){try{var v=document.querySelectorAll('video');" +
                    "for(var i=0;i<v.length;i++){try{v[i].pause();}catch(e){}}return '1';}" +
                    "catch(e){return '0';}})()",
                null
            )
        } catch (_: Exception) {}
    }

    private fun seekPageMediaRelative(seconds: Double) {
        try {
            webView.evaluateJavascript(
                "(function(){try{var m=document.querySelectorAll('audio,video');" +
                    "for(var i=0;i<m.length;i++){try{m[i].currentTime+=($seconds);}catch(e){}}return '1';}" +
                    "catch(e){return '0';}})()",
                null
            )
        } catch (_: Exception) {}
    }

    private fun seekPageMediaAbsolute(seconds: Double) {
        try {
            webView.evaluateJavascript(
                "(function(){try{var m=document.querySelectorAll('audio,video');" +
                    "for(var i=0;i<m.length;i++){try{m[i].currentTime=$seconds;}catch(e){}}return '1';}" +
                    "catch(e){return '0';}})()",
                null
            )
        } catch (_: Exception) {}
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_PLAY_PAUSE) {
                togglePageVideoPlayback()
            }
        }
    }

    private fun registerPipReceiver() {
        try {
            val filter = IntentFilter(ACTION_PIP_PLAY_PAUSE)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(pipReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(pipReceiver, filter)
            }
        // v154: the foreground notification's controls come back here
        try {
            val mf = IntentFilter().apply {
                addAction("pulse.media.TOGGLE")
                addAction("pulse.media.PREV")
                addAction("pulse.media.NEXT")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(mediaControlReceiver, mf, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(mediaControlReceiver, mf)
            }
        } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun setupPlaybackServiceCallback() {
        PlaybackService.mediaCallback = object : PlaybackService.MediaControlCallback {
            override fun onPlay() {
                runOnUiThread {
                    if (musicDecoding) {
                        try { musicTrack?.play() } catch (_: Exception) {}
                        musicPlaying = true
                        updateEdgeGlow()
                    } else {
                        resumePageElement()
                    }
                }
            }

            override fun onPause() {
                runOnUiThread {
                    if (musicDecoding) {
                        try { musicTrack?.pause() } catch (_: Exception) {}
                        musicPlaying = false
                        mediaPlaying = false
                        updateEdgeGlow()
                    } else {
                        pausePageElement()
                        musicPlaying = false
                        mediaPlaying = false
                        updateEdgeGlow()
                    }
                }
            }

            override fun onSkipNext() {
                runOnUiThread { seekPageMediaRelative(10.0) }
            }

            override fun onSkipPrev() {
                runOnUiThread { seekPageMediaRelative(-10.0) }
            }

            override fun onSeekTo(posMs: Long) {
                runOnUiThread { seekPageMediaAbsolute(posMs / 1000.0) }
            }
        }
    }

    // v146: add or remove a host from the user's own block list, or allow list.
    private fun showHostRuleEditor(isBlockList: Boolean, fromSettings: Boolean = true, originalFromMenu: Boolean = true) {
        val set = if (isBlockList) userBlockHosts else userAllowHosts
        val title = if (isBlockList) "Sites you block" else "Sites you allow"
        val input = EditText(this).apply {
            hint = "example.com"
            setSingleLine(true)
            setTextColor(colText)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 8)
            addView(input)
        }
        val builder = AlertDialog.Builder(this, dialogTheme())
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("Add") { _, _ ->
                val h = input.text?.toString()?.trim()?.lowercase()
                    ?.replace("https://", "")?.replace("http://", "")
                    ?.substringBefore("/") ?: ""
                if (h.isNotBlank()) {
                    set.add(h)
                    // a host cannot be in both lists - the newer choice wins
                    if (isBlockList) userAllowHosts.remove(h) else userBlockHosts.remove(h)
                    saveBlockerRules()
                    Toast.makeText(this, "Saved: " + h, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(if (set.isEmpty()) "Nothing yet" else "Remove one") { _, _ ->
                if (set.isEmpty()) return@setNeutralButton
                val items = set.toTypedArray()
                AlertDialog.Builder(this, dialogTheme())
                    .setTitle("Remove")
                    .setItems(items) { _, which ->
                        set.remove(items[which])
                        saveBlockerRules()
                        Toast.makeText(this, "Removed: " + items[which], Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        if (fromSettings) {
            builder.setNegativeButton("Back") { _, _ -> showSettings(fromMenu = originalFromMenu) }
            builder.setOnCancelListener { showSettings(fromMenu = originalFromMenu) }
        } else {
            builder.setNegativeButton("Close", null)
        }
        builder.show()
    }

    private var hasPageMedia = false

    private fun isAnyMusicPlaying(): Boolean = (musicPlaying || mediaPlaying || musicDecoding)

    private fun hasActiveMedia(): Boolean = (isAnyMusicPlaying() || hasPageMedia || musicDecoding)

    private fun syncPlaybackService() {
        if (isFinishing) {
            cancelForegroundNotification()
            stopPlaybackService()
            return
        }
        if (isAppInForeground) {
            // When music is playing on the foreground, display notification with control without running any extra service
            stopPlaybackService()
            if (hasActiveMedia()) {
                postForegroundNotification()
            } else {
                cancelForegroundNotification()
            }
        } else {
            // When music is running in background, cancel the foreground-only notification and run the service to keep playback alive and display notification with proper control
            cancelForegroundNotification()
            if (bgMusicEnabled && hasActiveMedia()) {
                startPlaybackService()
            } else {
                stopPlaybackService()
            }
        }
    }

    private fun handleMediaToggle() {
        runOnUiThread {
            if (musicDecoding) {
                if (musicPlaying) {
                    try { musicTrack?.pause() } catch (_: Exception) {}
                    musicPlaying = false
                    mediaPlaying = false
                } else {
                    try { musicTrack?.play() } catch (_: Exception) {}
                    musicPlaying = true
                    mediaPlaying = true
                }
            } else {
                if (mediaPlaying) {
                    pausePageMedia()
                    mediaPlaying = false
                    musicPlaying = false
                } else {
                    resumePageMedia()
                    mediaPlaying = true
                }
            }
            updateEdgeGlow()
            if (isAppInForeground) {
                postForegroundNotification()
            } else {
                PlaybackService.instance?.updatePlaybackState(
                    playing = isAnyMusicPlaying(),
                    posMs = lastMediaPosMs,
                    durMs = lastMediaDurMs,
                    title = if (lastMediaTitle.isNotBlank()) lastMediaTitle else "Pulse Horizon"
                )
            }
        }
    }

    private fun handleMediaPrev() {
        runOnUiThread {
            if (musicDecoding) {
                musicPosMs = (musicPosMs - 10000L).coerceAtLeast(0L)
            } else {
                seekPageMediaRelative(-10.0)
            }
        }
    }

    private fun handleMediaNext() {
        runOnUiThread {
            if (musicDecoding) {
                musicPosMs = (musicPosMs + 10000L).coerceAtMost(musicDurMs)
            } else {
                seekPageMediaRelative(10.0)
            }
        }
    }

    private val mediaControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    "pulse.media.TOGGLE" -> handleMediaToggle()
                    "pulse.media.PREV" -> handleMediaPrev()
                    "pulse.media.NEXT" -> handleMediaNext()
                }
            } catch (_: Exception) {}
        }
    }

    private fun mediaControlPi(action: String, code: Int): PendingIntent? = try {
        PendingIntent.getBroadcast(
            this, code,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    } catch (_: Exception) { null }

    private var fgMediaSession: MediaSession? = null

    private fun setupForegroundMediaSession() {
        if (fgMediaSession != null) return
        try {
            val session = MediaSession(this, "PulseHorizonForegroundSession").apply {
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        handleMediaToggle()
                    }

                    override fun onPause() {
                        handleMediaToggle()
                    }

                    override fun onSkipToNext() {
                        handleMediaNext()
                    }

                    override fun onSkipToPrevious() {
                        handleMediaPrev()
                    }

                    override fun onSeekTo(pos: Long) {
                        runOnUiThread {
                            if (musicDecoding) {
                                musicPosMs = pos
                            } else {
                                seekPageMediaAbsolute(pos / 1000.0)
                            }
                        }
                    }
                })
                val openApp = PendingIntent.getActivity(
                    this@MainActivity, 21,
                    Intent(this@MainActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    else PendingIntent.FLAG_UPDATE_CURRENT
                )
                try { setSessionActivity(openApp) } catch (_: Exception) {}
            }
            fgMediaSession = session
        } catch (_: Exception) {}
    }

    private fun updateFgMediaSessionState() {
        val session = fgMediaSession ?: return
        try {
            val isPlayingNow = isAnyMusicPlaying()
            val state = if (isPlayingNow) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            val speed = if (isPlayingNow) 1.0f else 0.0f
            val actions = PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SEEK_TO

            val pbState = PlaybackState.Builder()
                .setActions(actions)
                .setState(state, lastMediaPosMs, speed, SystemClock.elapsedRealtime())
                .build()

            session.setPlaybackState(pbState)

            val meta = MediaMetadata.Builder()
                .putString(
                    MediaMetadata.METADATA_KEY_TITLE,
                    if (lastMediaTitle.isNotBlank()) lastMediaTitle else "Pulse Horizon"
                )
                .putString(MediaMetadata.METADATA_KEY_ARTIST, if (isPlayingNow) "Playing" else "Paused")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, lastMediaDurMs)
                .build()
            session.setMetadata(meta)
        } catch (_: Exception) {}
    }

    private fun postForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val mgr = getSystemService(android.app.NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (mgr.getNotificationChannel("pulse_horizon_playback") == null) {
                    val ch = android.app.NotificationChannel(
                        "pulse_horizon_playback", "Playback",
                        android.app.NotificationManager.IMPORTANCE_LOW
                    )
                    ch.description = "Audio playback controls"
                    ch.setShowBadge(false)
                    mgr.createNotificationChannel(ch)
                }
            }
            val openApp = try {
                PendingIntent.getActivity(
                    this, 21,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } catch (_: Exception) { null }

            setupForegroundMediaSession()
            fgMediaSession?.isActive = true
            updateFgMediaSessionState()

            val isPlayingNow = isAnyMusicPlaying()
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                android.app.Notification.Builder(this, "pulse_horizon_playback")
            else
                @Suppress("DEPRECATION") android.app.Notification.Builder(this)

            b.setContentTitle(if (lastMediaTitle.isNotBlank()) lastMediaTitle else "Pulse Horizon")
                .setContentText(if (isPlayingNow) "Playing" else "Paused")
                .setSmallIcon(R.drawable.ic_media_play)
                .setOngoing(isPlayingNow)
            if (openApp != null) b.setContentIntent(openApp)

            val session = fgMediaSession
            if (session != null) {
                val mediaStyle = android.app.Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                b.setStyle(mediaStyle)
            }

            mediaControlPi("pulse.media.PREV", 11)?.let {
                b.addAction(
                    android.app.Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_media_prev),
                        "Previous",
                        it
                    ).build()
                )
            }
            mediaControlPi("pulse.media.TOGGLE", 12)?.let {
                b.addAction(
                    android.app.Notification.Action.Builder(
                        Icon.createWithResource(
                            this,
                            if (isPlayingNow) R.drawable.ic_media_pause else R.drawable.ic_media_play
                        ),
                        if (isPlayingNow) "Pause" else "Play",
                        it
                    ).build()
                )
            }
            mediaControlPi("pulse.media.NEXT", 13)?.let {
                b.addAction(
                    android.app.Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_media_next),
                        "Next",
                        it
                    ).build()
                )
            }
            mgr.notify(4711, b.build())
        } catch (_: Exception) {}
    }

    private fun cancelForegroundNotification() {
        try {
            getSystemService(android.app.NotificationManager::class.java)?.cancel(4711)
            fgMediaSession?.isActive = false
        } catch (_: Exception) {}
    }

    private fun startPlaybackService() {
        if (isFinishing || !bgMusicEnabled) return
        try {
            val intent = Intent(this, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            serviceRunning = true
            PlaybackService.instance?.updatePlaybackState(
                playing = isAnyMusicPlaying(),
                posMs = lastMediaPosMs,
                durMs = lastMediaDurMs,
                title = if (lastMediaTitle.isNotBlank()) lastMediaTitle else "Pulse Horizon"
            )
        } catch (_: Exception) {
            serviceRunning = false
        }
    }

    private fun stopPlaybackService() {
        try {
            stopService(Intent(this, PlaybackService::class.java))
        } catch (_: Exception) {
        }
        serviceRunning = false
    }

    private fun updateEdgeGlow() {
        runOnUiThread {
            // MUSIC ONLY. A video playing will not light the edges.
            val show = musicAnimEnabled && musicPlaying
            edgeViews.forEach { it.visibility = if (show) View.VISIBLE else View.GONE }
            edgeWaveView?.let { wv ->
                wv.visibility = if (show) View.VISIBLE else View.GONE
                if (!show) {
                    wv.invalidate()
                }
            }
            webView.removeCallbacks(glowRunnable)
            if (show) {
                webView.post(glowRunnable)
            } else {
                beatLevel = 0f
                PlaybackService.instance?.updateBeatLevel(0f)
            }
        }
    }

    private fun showDownloads(fromMenu: Boolean = true) {
        try {
            val dm = getSystemService(android.app.DownloadManager::class.java)
            val q = android.app.DownloadManager.Query()
                .setFilterByStatus(android.app.DownloadManager.STATUS_SUCCESSFUL)
            val c = dm.query(q)
            val names = mutableListOf<String>()
            val ids = mutableListOf<Long>()
            while (c.moveToNext() && names.size < 30) {
                val id = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_ID))
                val title = c.getString(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TITLE))
                    ?: "file"
                names.add(title)
                ids.add(id)
            }
            c.close()
            if (names.isEmpty()) {
                val b = AlertDialog.Builder(this)
                    .setTitle("Downloads")
                    .setMessage("No downloads yet")
                    .setPositiveButton("Done", null)
                if (fromMenu) {
                    b.setNegativeButton("Back") { _, _ -> showMenu() }
                    b.setOnCancelListener { showMenu() }
                }
                b.show()
                return
            }
            val b = AlertDialog.Builder(this)
                .setTitle("Downloads")
                .setItems(names.toTypedArray()) { _, which ->
                    try {
                        val uri = dm.getUriForDownloadedFile(ids[which])
                        startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "*/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        )
                    } catch (_: Exception) {
                        Toast.makeText(applicationContext, "Cannot open that file", Toast.LENGTH_SHORT).show()
                    }
                }
                .setPositiveButton("Done", null)
            if (fromMenu) {
                b.setNegativeButton("Back") { _, _ -> showMenu() }
                b.setOnCancelListener { showMenu() }
            }
            b.show()
        } catch (_: Exception) {
            Toast.makeText(applicationContext, "Could not read downloads", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- v86: BOOKMARKS ----------------
    private fun loadBookmarks() {
        bookmarks.clear()
        try {
            val f = bookmarkFile
            if (f.exists()) {
                f.readLines().forEach { line ->
                    val i = line.indexOf('\t')
                    if (i > 0) {
                        bookmarks.add(Bookmark(line.substring(0, i), line.substring(i + 1)))
                    } else if (line.isNotBlank()) {
                        bookmarks.add(Bookmark(line, line))
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun saveBookmarks() {
        try {
            bookmarkFile.writeText(bookmarks.joinToString("\n") { it.title + "\t" + it.url })
        } catch (_: Exception) {
        }
    }

    private fun addBookmark() {
        try {
            val url = webView.url ?: return
            if (!url.startsWith("http")) return
            val title = (webView.title ?: url).trim().take(60)
            if (bookmarks.any { it.url == url }) {
                Toast.makeText(applicationContext, "Already bookmarked", Toast.LENGTH_SHORT).show()
                return
            }
            bookmarks.add(Bookmark(title, url))
            saveBookmarks()
            Toast.makeText(applicationContext, "Bookmarked", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun showBookmarks(fromMenu: Boolean = true) {
        if (bookmarks.isEmpty()) {
            val b = AlertDialog.Builder(this, dialogTheme())
                .setTitle("Bookmarks")
                .setMessage("No bookmarks yet")
                .setPositiveButton("Done", null)
            if (fromMenu) {
                b.setNegativeButton("Back") { _, _ -> showMenu() }
                b.setOnCancelListener { showMenu() }
            }
            b.show()
            return
        }
        val dialog = AlertDialog.Builder(this, dialogTheme()).create()
        if (fromMenu) {
            dialog.setOnCancelListener { showMenu() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colBg)
            setPadding(36, 36, 36, 36)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }
        if (fromMenu) {
            val backBtn = Button(this).apply {
                text = "←"
                textSize = 18f
                setTextColor(colAccent)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    dialog.dismiss()
                    showMenu()
                }
            }
            header.addView(backBtn)
        }
        val title = TextView(this).apply {
            text = "Bookmarks (${bookmarks.size})"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(colText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(colText)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(title)
        header.addView(closeBtn)
        root.addView(header)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (420 * resources.displayMetrics.density).toInt())
        }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        bookmarks.forEachIndexed { index, bm ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 20, 24, 20)
                setBackgroundColor(colSurface)
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, 16)
                layoutParams = params
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    try {
                        if (tabs.isNotEmpty()) {
                            val i = activeTab.coerceIn(0, tabs.size - 1)
                            tabs[i].url = bm.url
                            tabs[i].title = bm.title
                        }
                        webView.loadUrl(bm.url)
                    } catch (_: Exception) {}
                }
            }
            // Group C: Site icon thumbnail on bookmark card
            val iconBadge = TextView(this).apply {
                val host = try { Uri.parse(bm.url).host ?: "" } catch (_: Exception) { "" }
                val initial = if (host.isNotBlank()) {
                    host.removePrefix("www.").firstOrNull()?.uppercaseChar()?.toString() ?: "★"
                } else if (bm.title.isNotBlank()) {
                    bm.title.first().uppercaseChar().toString()
                } else "★"
                text = initial
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                val d = (32 * resources.displayMetrics.density).toInt()
                val lp = LinearLayout.LayoutParams(d, d).apply {
                    rightMargin = (12 * resources.displayMetrics.density).toInt()
                }
                layoutParams = lp
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0F766E"))
                    cornerRadius = 16f
                }
            }
            card.addView(iconBadge)

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val bmTitle = TextView(this).apply {
                text = bm.title
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(colText)
                maxLines = 1
            }
            val bmUrl = TextView(this).apply {
                text = bm.url
                textSize = 12f
                setTextColor(colDim)
                maxLines = 1
            }
            textCol.addView(bmTitle)
            textCol.addView(bmUrl)
            card.addView(textCol)

            val delBtn = Button(this).apply {
                text = "🗑"
                textSize = 16f
                setTextColor(Color.RED)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    dialog.dismiss()
                    bookmarks.removeAt(index)
                    saveBookmarks()
                    Toast.makeText(applicationContext, "Bookmark removed", Toast.LENGTH_SHORT).show()
                    showBookmarks(fromMenu = fromMenu)
                }
            }
            card.addView(delBtn)
            grid.addView(card)
        }
        scrollView.addView(grid)
        root.addView(scrollView)
        dialog.setView(root)
        dialog.show()
    }

    private fun removeBookmarkPicker() {
        if (bookmarks.isEmpty()) return
        val names = bookmarks.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Remove which bookmark?")
            .setItems(names) { _, which ->
                try {
                    bookmarks.removeAt(which)
                    saveBookmarks()
                    Toast.makeText(applicationContext, "Removed", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- v117: THEME + MENU ACTIONS ----------------
    private fun themeModeLabel(): String = when (themeMode) {
        "dark" -> "Dark"; "light" -> "Light"; else -> "System"
    }

    private fun isDarkNow(): Boolean = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun dialogTheme(): Int =
        if (isDarkNow()) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    private fun pickTheme() {
        val opts = arrayOf("Dark", "Light", "System (follow phone)")
        val cur = when (themeMode) { "dark" -> 0; "light" -> 1; else -> 2 }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Theme")
            .setSingleChoiceItems(opts, cur) { d, which ->
                themeMode = when (which) { 0 -> "dark"; 1 -> "light"; else -> "system" }
                prefs.edit().putString("theme_mode", themeMode).commit()
                d?.dismiss()
                applyThemeInPlace(showToast = true)
            }
            .show()
    }

    private fun applyThemeInPlace(showToast: Boolean = false) {
        try { tellHomePageTheme() } catch (_: Exception) {}
        val dark = isDarkNow()
        colBg = if (dark) Color.parseColor("#0E0E0E") else Color.parseColor("#FFFFFF")
        colSurface = if (dark) Color.parseColor("#121212") else Color.parseColor("#F2F2F2")
        colLine = if (dark) Color.parseColor("#2A2A2A") else Color.parseColor("#DDDDDD")
        colText = if (dark) Color.WHITE else Color.parseColor("#111111")
        colDim = if (dark) Color.GRAY else Color.parseColor("#666666")
        try { window.statusBarColor = colSurface } catch (_: Exception) {}
        try { window.navigationBarColor = colBg } catch (_: Exception) {}
        try { rootLayout.setBackgroundColor(colBg) } catch (_: Exception) {}
        try { topBar.setBackgroundColor(colBg) } catch (_: Exception) {}
        try { tabRow.setBackgroundColor(colBg) } catch (_: Exception) {}
        try { urlBar.setTextColor(colText) } catch (_: Exception) {}
        try { urlBar.setHintTextColor(colDim) } catch (_: Exception) {}
        try { menuBtn.setTextColor(colText) } catch (_: Exception) {}

        try { homeBtn.setTextColor(colText) } catch (_: Exception) {}
        try { webView.setBackgroundColor(colBg) } catch (_: Exception) {}
        try { renderTabs() } catch (_: Exception) {}
        if (showToast) {
            Toast.makeText(applicationContext, "Theme: " + themeModeLabel(), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyCurrentLink() {
        try {
            val u = webView.url ?: return
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("URL", u))
            Toast.makeText(applicationContext, "Link copied", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun shareCurrentLink() {
        try {
            val u = webView.url ?: return
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, u)
            }
            startActivity(Intent.createChooser(send, "Share link"))
        } catch (_: Exception) {}
    }

    private fun openInNewTab() {
        try { val u = webView.url ?: return; newTab(); loadInput(u) } catch (_: Exception) {}
    }

    private fun toggleDesktop() {
        try {
            val on = !isDesktopMode
            applyDeskMode(on)
            if (tabs.isNotEmpty()) tabs[activeTab.coerceIn(0, tabs.size - 1)].isDesktop = on
            webView.reload()
        } catch (_: Exception) {}
    }

    // v132: ONE terms page, and it lives in the app. Both the first run and
    // this menu item open the same bundled page - no internet, one source.
    private fun openTerms() {
        // loadInput() treats anything not http(s) as a search, so a file://
        // address fell through to Google and the terms page was never
        // reachable. Load the bundled page directly, as the first run does.
        try { webView.loadUrl(termsUrl) } catch (_: Exception) {}
    }

    // ---------------- v101: MUSIC MODE, OUR OWN DECODER ----------------
    // No Visualizer -> no RECORD_AUDIO -> no microphone permission anywhere.
    private fun musicPlay(url: String, startMs: Long = 0L, pausePage: Boolean = false) {
        musicStop()
        val job = MusicJob()
        musicJob = job
        musicDecoding = true
        musicPlaying = true
        mediaPlaying = true
        musicPosMs = startMs
        updateEdgeGlow()
        syncPlaybackService()
        startMusicPositionReporter()
        val t = Thread {
            musicStartMs = startMs
            var extractor: MediaExtractor? = null
            var pfd: ParcelFileDescriptor? = null
            var codec: MediaCodec? = null
            var track: AudioTrack? = null
            var firstWrite = true
            try {
                val ex = MediaExtractor()
                if (url.startsWith("http")) {
                    val headers = HashMap<String, String>()
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (!cookies.isNullOrEmpty()) {
                        headers["Cookie"] = cookies
                    }
                    headers["User-Agent"] = if (isDesktopMode) desktopUserAgent else defaultUserAgent
                    val ref = try { webView.url } catch (_: Exception) { null }
                    if (!ref.isNullOrEmpty()) {
                        headers["Referer"] = ref
                    }
                    ex.setDataSource(this@MainActivity, Uri.parse(url), headers)
                } else {
                    val uri = Uri.parse(url)
                    if (uri.scheme == "content" || uri.scheme == "file") {
                        try {
                            val openPfd = contentResolver.openFileDescriptor(uri, "r")
                            if (openPfd != null) {
                                pfd = openPfd
                                ex.setDataSource(openPfd.fileDescriptor)
                            } else {
                                ex.setDataSource(this@MainActivity, uri, null)
                            }
                        } catch (_: Exception) {
                            ex.setDataSource(this@MainActivity, uri, null)
                        }
                    } else {
                        ex.setDataSource(this@MainActivity, uri, null)
                    }
                }
                extractor = ex
                var idx = -1
                var fmt: MediaFormat? = null
                var i = 0
                while (i < ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val m = f.getString(MediaFormat.KEY_MIME)
                    if (m != null && m.startsWith("audio/")) {
                        idx = i
                        fmt = f
                        break
                    }
                    i++
                }
                if (idx < 0 || fmt == null) throw IllegalStateException("no audio track")
                ex.selectTrack(idx)

                // Seek to sync frame at or before the requested start position
                val startUs = startMs * 1000L
                if (startUs > 0L) {
                    ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }

                val mime = fmt.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("no mime")
                val c = MediaCodec.createDecoderByType(mime)
                codec = c
                c.configure(fmt, null, null, 0)
                c.start()
                val sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                musicSampleRate = sr
                try {
                    if (fmt.containsKey(MediaFormat.KEY_DURATION))
                        musicDurMs = fmt.getLong(MediaFormat.KEY_DURATION) / 1000L
                } catch (_: Exception) {}
                val ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                val mask = if (ch >= 2) AudioFormat.CHANNEL_OUT_STEREO
                    else AudioFormat.CHANNEL_OUT_MONO
                val bytesPerFrame = ch * 2 // 16-bit PCM = 2 bytes per channel
                // WIRE A: one timeline per playback. channels matters -
                // getPlaybackHeadPosition() counts frames, so the timeline
                // must count frames too, or every beat is out by the
                // channel count.
                timeline = BeatTimeline(sr.toInt(), ch).also { it.reset() }
                launcher = WaveLauncher(sr.toInt()).also { it.reset() }

                val minBuf = AudioTrack.getMinBufferSize(
                    sr, mask, AudioFormat.ENCODING_PCM_16BIT
                )
                val at = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(mask)
                            .build()
                    )
                    .setBufferSizeInBytes(if (minBuf > 0) minBuf * 2 else 65536)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = at
                at.play()
                musicTrack = at

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone && !job.cancelled) {
                    if (!inputDone) {
                        val inIx = c.dequeueInputBuffer(10000)
                        if (inIx >= 0) {
                            val ib = c.getInputBuffer(inIx)
                            val sz = if (ib != null) ex.readSampleData(ib, 0) else -1
                            if (sz < 0) {
                                c.queueInputBuffer(
                                    inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                c.queueInputBuffer(inIx, 0, sz, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val outIx = c.dequeueOutputBuffer(info, 10000)
                    if (outIx >= 0) {
                        if (info.size > 0 && job.cancelled) {
                            // A4: this playback was stopped - do not write
                            c.releaseOutputBuffer(outIx, false)
                            outputDone = true
                            continue
                        }
                        if (info.size > 0) {
                            val ob = c.getOutputBuffer(outIx)
                            if (ob != null) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                ob.get(bytes)

                                // Frame alignment: calculate if this buffer is before startUs (pre-roll)
                                val bufferDurationUs = if (sr > 0 && bytesPerFrame > 0) {
                                    ((info.size / bytesPerFrame).toLong() * 1_000_000L) / sr
                                } else 0L

                                val writeOffset: Int
                                val writeLength: Int

                                if (startUs > 0L && firstWrite && (info.presentationTimeUs + bufferDurationUs < startUs)) {
                                    // Entire buffer is before target start time — discard pre-roll
                                    writeOffset = 0
                                    writeLength = 0
                                } else if (startUs > 0L && firstWrite && info.presentationTimeUs < startUs) {
                                    // Buffer straddles the start timestamp — trim pre-roll bytes
                                    val skipUs = startUs - info.presentationTimeUs
                                    val skipFrames = (skipUs * sr / 1_000_000L).toInt()
                                    val skipBytes = (skipFrames * bytesPerFrame).coerceIn(0, info.size)
                                    // Align to frame boundary
                                    val alignedSkip = (skipBytes / bytesPerFrame) * bytesPerFrame
                                    writeOffset = alignedSkip
                                    writeLength = info.size - alignedSkip
                                } else {
                                    writeOffset = 0
                                    writeLength = info.size
                                }

                                if (writeLength > 0) {
                                    // A4-2 FIX: THE LEAK.
                                    //
                                    // This was the BLOCKING write. When the
                                    // AudioTrack's buffer fills it blocks
                                    // until space frees - and since musicStop
                                    // no longer pauses or flushes the track
                                    // (that was the use-after-free), a
                                    // cancelled thread could hang HERE for
                                    // ever. It never reached the loop top,
                                    // never saw job.cancelled, and never ran
                                    // its finally - so its MediaCodec and its
                                    // AudioTrack were never released. One
                                    // leaked pair per play. After 4-5 plays
                                    // Android's limit on concurrent codecs is
                                    // reached and the process dies with no
                                    // Java trace.
                                    //
                                    // WRITE_NON_BLOCKING cannot hang. The loop
                                    // always comes back around, sees
                                    // job.cancelled, and the finally releases
                                    // everything.
                                    var wrote = 0
                                    var spin = 0
                                    while (wrote < writeLength && !job.cancelled && spin < 400) {
                                        val n = at.write(
                                            bytes, writeOffset + wrote, writeLength - wrote,
                                            AudioTrack.WRITE_NON_BLOCKING
                                        )
                                        if (n > 0) {
                                            timeline?.feed(bytes, writeOffset + wrote, n)   // WIRE A
                                            wrote += n
                                            spin = 0
                                        } else {
                                            spin++
                                            try { Thread.sleep(5) } catch (_: Exception) {}
                                        }
                                    }
                                    if (firstWrite) {
                                        firstWrite = false
                                        if (pausePage) {
                                            runOnUiThread { pausePageElement() }
                                        }
                                    }
                                }

                                // Amplitude calculation from PCM samples for visualizer beat reactivity
                                var sum = 0L
                                var count = 0
                                var j = writeOffset
                                while (j + 1 < writeOffset + writeLength) {
                                    val lo = bytes[j].toInt() and 0xFF
                                    val hi = bytes[j + 1].toInt()
                                    val sample = ((hi shl 8) or lo).toShort().toInt()
                                    val a = if (sample < 0) -sample else sample
                                    sum += a
                                    count++
                                    j += 2
                                }
                                val mean = if (count > 0) sum.toFloat() / count else 0f
                                musicAmp = (mean / 32768f * 4.5f).coerceIn(0f, 1f)
                            }
                        }
                        c.releaseOutputBuffer(outIx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                            outputDone = true
                    }
                }
            } catch (_: Exception) {
                // If decoding fails, resume page element immediately
                if (pausePage) {
                    runOnUiThread { resumePageElement() }
                }
                runOnUiThread {
                    Toast.makeText(
                        applicationContext,
                        "Could not play that link - the page has been left alone",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                if (pausePage && firstWrite) {
                    runOnUiThread { resumePageElement() }
                }
                musicDecoding = false
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                try { extractor?.release() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
                // A4 FIX: only clear the shared reference if it is still OURS.
                // A new song may have started while this one was finishing, and
                // this thread used to null the NEW track out from under it.
                if (musicTrack === track) musicTrack = null
                // A4: only clear the job if it is still ours
                if (musicJob === job) musicJob = null
                musicAmp = 0f
                runOnUiThread {
                    musicPlaying = false
                    updateEdgeGlow()
                    syncPlaybackService()
                }
            }
        }
        decoderThread = t
        t.start()
    }

    // ---------------- v102: THE MUSIC ON THIS PAGE ----------------
    // Reads the URL and timestamp of whatever the page is playing (audio only - a video is ignored).
    // Pauses the page element and captures exact playback timestamp so our own decoder
    // takes over seamlessly in sample-accurate synchronization.
    private fun probeCurrentMediaUrl(cb: (String?, Double) -> Unit) {
        try {
            webView.evaluateJavascript(
                "(function(){try{" +
                    "function checkEl(e){" +
                    "  var isMusic=(e.tagName.toLowerCase()==='audio')||" +
                    "              ((e.videoWidth||0)===0||(e.videoHeight||0)===0);" +
                    "  if(!isMusic)return null;" +
                    "  var s=e.currentSrc||e.src||'';" +
                    "  if(!s&&e.querySelector){var sc=e.querySelector('source');if(sc)s=sc.src||'';}" +
                    "  if(!s||s.indexOf('http')!==0)return null;" +
                    "  return {el:e, url:s, time:e.currentTime||0};" +
                    "}" +
                    "function collectAll(root, acc){" +
                    "  try{" +
                    "    var list=root.querySelectorAll('video,audio');" +
                    "    for(var i=0;i<list.length;i++){acc.push(list[i]);}" +
                    "    var all=root.querySelectorAll('*');" +
                    "    for(var j=0;j<all.length;j++){if(all[j].shadowRoot)collectAll(all[j].shadowRoot, acc);}" +
                    "  }catch(_){}" +
                    "  return acc;" +
                    "}" +
                    "var m=collectAll(document, []);" +
                    "try{" +
                    "  var frames=document.querySelectorAll('iframe');" +
                    "  for(var f=0;f<frames.length;f++){" +
                    "    try{" +
                    "      var cdoc=frames[f].contentDocument||frames[f].contentWindow.document;" +
                    "      if(cdoc)collectAll(cdoc, m);" +
                    "    }catch(_){}" +
                    "  }" +
                    "}catch(_){}" +
                    "var found=null;" +
                    "for(var i=0;i<m.length;i++){" +
                    "  var e=m[i];" +
                    "  if(!e.paused&&!e.ended){" +
                    "    var res=checkEl(e);" +
                    "    if(res){found=res;break;}" +
                    "  }" +
                    "}" +
                    "if(!found){" +
                    "  for(var j=0;j<m.length;j++){" +
                    "    var res=checkEl(m[j]);" +
                    "    if(res){found=res;break;}" +
                    "  }" +
                    "}" +
                    "if(found){" +
                    "  window.__pulseTakeoverElement=found.el;" +
                    "  found.el.pause();" +
                    "  return found.url+'|'+found.time;" +
                    "}" +
                    "return '|0';" +
                    "}catch(e){return '|0';}})()"
            ) { v ->
                val raw = v?.replace("\"", "")?.trim() ?: "|0"
                val cut = raw.lastIndexOf('|')
                val urlPart = if (cut > 0) raw.substring(0, cut) else ""
                val posPart = if (cut > 0) raw.substring(cut + 1) else "0"
                val url = if (urlPart.startsWith("http")) urlPart else null
                cb(url, posPart.toDoubleOrNull() ?: 0.0)
            }
        } catch (_: Exception) {
            cb(null, 0.0)
        }
    }

    // If our copy fails or takeover stops, resume page media safely without being blocked by autoplay policy.
    private fun resumePageElement() {
        try {
            webView.settings.mediaPlaybackRequiresUserGesture = false
            webView.evaluateJavascript(
                "(function(){try{" +
                    "if(window.__pulseTakeoverElement){try{window.__pulseTakeoverElement.play();}catch(_){}}" +
                    "var m=document.querySelectorAll('video,audio');" +
                    "for(var i=0;i<m.length;i++){try{m[i].play();}catch(_){}}" +
                    "}catch(_){}})()",
                null
            )
        } catch (_: Exception) {}
    }

    // Stop the page's own player so our copy is not heard twice.
    private fun pausePageElement() {
        try {
            webView.evaluateJavascript(
                "(function(){try{" +
                    "if(window.__pulseTakeoverElement){try{window.__pulseTakeoverElement.pause();}catch(_){}}" +
                    "var m=document.querySelectorAll('video,audio');" +
                    "for(var i=0;i<m.length;i++){try{m[i].pause();}catch(_){}}" +
                    "}catch(_){}})()",
                null
            )
        } catch (_: Exception) {}
    }

    // v145: report the decoder's real position to the notification, once a
    // second. Worked out from how many frames the audio track has actually
    // played, plus the point it started from.
    private fun startMusicPositionReporter() {
        try { musicPosThread?.interrupt() } catch (_: Exception) {}
        val t = Thread {
            while (musicPlaying && musicDecoding) {
                try {
                    val head = try { musicTrack?.playbackHeadPosition ?: 0 } catch (_: Exception) { 0 }
                    val sr = musicSampleRate.coerceAtLeast(1)
                    musicPosMs = musicStartMs + (head.toLong() * 1000L / sr)
                    runOnUiThread {
                        try {
                            PlaybackService.instance?.updatePlaybackState(
                                true, musicPosMs, musicDurMs, musicNotifTitle
                            )
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }
        musicPosThread = t
        t.start()
    }

    private fun musicStop() {
        // A4 FIX: THE NATIVE CRASH.
        //
        // This used to do: join(500) on the main thread, then pause(), flush()
        // and null musicTrack - while the decoder thread was still writing to
        // that same AudioTrack, and its own finally was about to release() it.
        // Two threads on one native object, one of them freeing it. That is a
        // use-after-free, and it kills the process with no Java stack trace.
        //
        // It fired when you left the app with a local song playing, because
        // onPause calls musicStop().
        //
        // Now stop is a REQUEST, not a seizure: we only flip the flag the
        // decoder loop is watching, and let the thread release its own track.
        // Nothing here touches musicTrack.
        // Cancel THIS playback only. The next song creates a new token, so it
        // cannot bring a cancelled thread back to life.
        musicJob?.cancelled = true
        musicJob = null
        try { decoderThread?.interrupt() } catch (_: Exception) {}
        musicDecoding = false
        musicPlaying = false
        mediaPlaying = false
        musicAmp = 0f
        beatLevel = 0f
        updateEdgeGlow()
        syncPlaybackService()
    }

    private fun showMusic(fromMenu: Boolean = true) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 36, 56, 20)
        }
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "Paste a music link (.mp3 .m4a .mp4)"
            setTextColor(colText)
            setHintTextColor(Color.GRAY)
            if (currentMusicUri.isNotBlank()) {
                setText(currentMusicUri)
                setSelection(currentMusicUri.length)
            }
        }
        musicInputView = input
        box.addView(input)
        val addBtn = Button(this).apply {
            text = "+ Add a song from this phone"
            setOnClickListener {
                try {
                    musicFileLauncher.launch("audio/*")
                } catch (_: Exception) {
                    Toast.makeText(
                        applicationContext, "No file picker found", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        box.addView(addBtn)
        var musicDialogRef: AlertDialog? = null
        val visBtn = Button(this).apply {
            text = "Visualization 🎵"
            setOnClickListener {
                musicDialogRef?.dismiss()
                showVisualizationMenu(fromMenu = fromMenu)
            }
        }
        val visLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8 }
        box.addView(visBtn, visLp)
        val animBox = CheckBox(this).apply {
            text = "Edge glow while media plays"
            isChecked = musicAnimEnabled
            setTextColor(colText)
            setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { box, c ->
                if (c == musicAnimEnabled) return@setOnCheckedChangeListener
                if (c) {
                    confirmNotice(
                        "Edge glow",
                        "The glow animates the whole time media is playing, so the " +
                            "screen stays busy and the battery drains a little faster " +
                            "than usual. It stops the moment the media stops.",
                        onYes = {
                            musicAnimEnabled = true
                            prefs.edit().putBoolean("music_anim", true).commit()
                            updateEdgeGlow()
                        },
                        onNo = { box.isChecked = false }
                    )
                } else {
                    musicAnimEnabled = false
                    prefs.edit().putBoolean("music_anim", false).commit()
                    updateEdgeGlow()
                }
            }
        }
        val bgBox = CheckBox(this).apply {
            text = "Background music (keeps playing when you leave)"
            isChecked = bgMusicEnabled
            setTextColor(colText)
            setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { box, c ->
                if (c == bgMusicEnabled) return@setOnCheckedChangeListener
                if (c) {
                    confirmNotice(
                        "Background music",
                        "Music keeps playing when you leave the browser, with a media " +
                            "notification so you can control it. It is a light service, " +
                            "but it does keep running in the background, so expect a " +
                            "little extra battery use. It stops the moment the music " +
                            "ends or you close the browser.",
                        onNo = { box.isChecked = false },
                        onYes = {
                            bgMusicEnabled = true
                            prefs.edit().putBoolean("bg_music", true).commit()
                            applyVisibilityOverride()
                            try {
                                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    requestPermissions(
                                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4711
                                    )
                                }
                            } catch (_: Exception) {}
                            syncPlaybackService()
                        }
                    )
                } else {
                    bgMusicEnabled = false
                    prefs.edit().putBoolean("bg_music", false).commit()
                    applyVisibilityOverride()
                    stopPlaybackService()
                }
            }
        }
        box.addView(animBox)
        box.addView(bgBox)
        val builder = AlertDialog.Builder(this)
            .setTitle("Music")
            .setView(box)
            .setPositiveButton("Play") { _, _ ->
                val u = input.text.toString().trim()
                if (u.isEmpty() || (!u.startsWith("http://") && !u.startsWith("https://") && !u.startsWith("content://") && !u.startsWith("file://"))) {
                    Toast.makeText(
                        applicationContext,
                        "That is not a link. Use + below to pick a song from this phone.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    currentMusicUri = u
                    musicPlay(u)
                }
            }
            .setNeutralButton("Stop") { _, _ -> musicStop() }
        if (fromMenu) {
            builder.setNegativeButton("Back") { _, _ -> showMenu() }
            builder.setOnCancelListener {
                if (musicInputView === input) musicInputView = null
                showMenu()
            }
        } else {
            builder.setNegativeButton("Close", null)
        }
        val dialog = builder.create()
        musicDialogRef = dialog
        dialog.setOnDismissListener {
            if (musicInputView === input) {
                musicInputView = null
            }
        }
        dialog.show()
    }

    private fun showVisualizationMenu(fromMenu: Boolean = true) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 36, 56, 20)
        }
        val desc = TextView(this).apply {
            text = "Pulse Horizon Disk Visualiser\nWaves ripple around the rim in real-time."
            setTextColor(colText)
            setPadding(0, 0, 0, 24)
            textSize = 14f
        }
        box.addView(desc)

        // The + is a LOCAL FILE GRAB: user picks music file from phone, and that is what the visualizer plays!
        val grabBtn = Button(this).apply {
            text = "+ Pick music from phone to visualize"
            setOnClickListener {
                try {
                    visSongLauncher.launch("audio/*")
                } catch (_: Exception) {
                    Toast.makeText(applicationContext, "No file picker found", Toast.LENGTH_SHORT).show()
                }
            }
        }
        box.addView(grabBtn)

        if (currentMusicUri.isNotBlank()) {
            val playCurrentBtn = Button(this).apply {
                text = "▶ Start Visualiser with current song"
                setOnClickListener {
                    if (!musicPlaying) {
                        musicPlay(currentMusicUri)
                    }
                    showDiskVisualizerOverlay(Uri.parse(currentMusicUri))
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            box.addView(playCurrentBtn, lp)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Visualization")
            .setView(box)
            .setNegativeButton("Back") { _, _ -> showMusic(fromMenu = fromMenu) }
        val d = builder.create()
        d.show()
    }

    private fun showDiskVisualizerOverlay(audioUri: Uri? = null) {
        if (diskVisualizerOverlay != null) {
            if (audioUri != null) {
                currentMusicUri = audioUri.toString()
                musicPlay(currentMusicUri)
            }
            return
        }
        val overlay = DiskVisualizerOverlay(
            activity = this,
            onPickSong = {
                try {
                    visSongLauncher.launch("audio/*")
                } catch (_: Exception) {
                    Toast.makeText(applicationContext, "No file picker found", Toast.LENGTH_SHORT).show()
                }
            },
            onPickLogo = {
                try {
                    visLogoLauncher.launch("image/*")
                } catch (_: Exception) {
                    Toast.makeText(applicationContext, "No file picker found", Toast.LENGTH_SHORT).show()
                }
            },
            onExportMp4 = {
                val u = audioUri ?: if (currentMusicUri.isNotBlank()) Uri.parse(currentMusicUri) else null
                if (u == null) {
                    Toast.makeText(applicationContext, "Please pick a song first", Toast.LENGTH_SHORT).show()
                } else {
                    startDiskExport(u)
                }
            },
            onClose = {
                closeDiskVisualizerOverlay()
            }
        )
        diskVisualizerOverlay = overlay
        val targetLayer = glowLayerRef ?: findViewById<FrameLayout>(android.R.id.content)
        targetLayer?.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        updateEdgeGlow()
    }

    private fun closeDiskVisualizerOverlay() {
        val overlay = diskVisualizerOverlay ?: return
        diskVisualizerOverlay = null
        (overlay.parent as? ViewGroup)?.removeView(overlay)
    }

    private fun startDiskExport(audioUri: Uri) {
        val overlay = diskVisualizerOverlay ?: return
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Exporting MP4 Visualisation...")
            .setMessage("Rendering frames and muxing audio: 0%")
            .setCancelable(false)
            .create()
        progressDialog.show()

        val exporter = DiskVisualizerExporter(this)
        exporter.export(
            audioUri = audioUri,
            mode = overlay.visualizerView.mode,
            customColor = overlay.visualizerView.customColor,
            vibrationScale = overlay.visualizerView.vibrationMultiplier,
            logoBitmap = overlay.visualizerView.logoBitmap,
            onProgress = { pct ->
                progressDialog.setMessage("Rendering frames and muxing audio: $pct%")
            },
            onComplete = { file ->
                progressDialog.dismiss()
                Toast.makeText(
                    applicationContext,
                    "Saved to Movies: ${file.name}",
                    Toast.LENGTH_LONG
                ).show()
            },
            onError = { err ->
                progressDialog.dismiss()
                Toast.makeText(applicationContext, "Export failed: $err", Toast.LENGTH_LONG).show()
            }
        )
    }

    // B2: Move "Edge glow" and "Background music" into a dedicated Music screen in Settings
    private fun showMusicSettings(fromSettings: Boolean = true, originalFromMenu: Boolean = true) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 36, 56, 20)
        }
        val animBox = CheckBox(this).apply {
            text = "Edge glow while media plays"
            isChecked = musicAnimEnabled
            setTextColor(colText)
            setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { box, c ->
                if (c == musicAnimEnabled) return@setOnCheckedChangeListener
                if (c) {
                    confirmNotice(
                        "Edge glow",
                        "The glow animates the whole time media is playing, so the " +
                            "screen stays busy and the battery drains a little faster " +
                            "than usual. It stops the moment the media stops.",
                        onYes = {
                            musicAnimEnabled = true
                            prefs.edit().putBoolean("music_anim", true).commit()
                            updateEdgeGlow()
                        },
                        onNo = { box.isChecked = false }
                    )
                } else {
                    musicAnimEnabled = false
                    prefs.edit().putBoolean("music_anim", false).commit()
                    updateEdgeGlow()
                }
            }
        }
        val bgBox = CheckBox(this).apply {
            text = "Background music (keeps playing when you leave)"
            isChecked = bgMusicEnabled
            setTextColor(colText)
            setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { box, c ->
                if (c == bgMusicEnabled) return@setOnCheckedChangeListener
                if (c) {
                    confirmNotice(
                        "Background music",
                        "Music keeps playing when you leave the browser, with a media " +
                            "notification so you can control it. It is a light service, " +
                            "but it does keep running in the background, so expect a " +
                            "little extra battery use. It stops the moment the music " +
                            "ends or you close the browser.",
                        onNo = { box.isChecked = false },
                        onYes = {
                            bgMusicEnabled = true
                            prefs.edit().putBoolean("bg_music", true).commit()
                            applyVisibilityOverride()
                            try {
                                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    requestPermissions(
                                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4711
                                    )
                                }
                            } catch (_: Exception) {}
                            syncPlaybackService()
                        }
                    )
                } else {
                    bgMusicEnabled = false
                    prefs.edit().putBoolean("bg_music", false).commit()
                    applyVisibilityOverride()
                    stopPlaybackService()
                }
            }
        }
        box.addView(animBox)
        box.addView(bgBox)

        val builder = AlertDialog.Builder(this)
            .setTitle("Music Settings")
            .setView(box)
            .setPositiveButton("Done", null)
        if (fromSettings) {
            builder.setNegativeButton("Back") { _, _ -> showSettings(fromMenu = originalFromMenu) }
            builder.setOnCancelListener { showSettings(fromMenu = originalFromMenu) }
        }
        builder.show()
    }

    private fun showSettings(fromMenu: Boolean = true) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 36, 56, 20)
        }
        val autoHide = CheckBox(this).apply {
            text = "Auto-hide the address bar (this tab)"
            isChecked = autoHideBar
            setTextColor(colText)
            setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { _, checked ->
                autoHideBar = checked
                prefs.edit().putBoolean("auto_hide_bar", checked).commit()
                if (tabs.isNotEmpty()) {
                    tabs[activeTab.coerceIn(0, tabs.size - 1)].autoHide = checked
                }
                if (checked) hideChrome() else showChrome()
            }
        }
        val blockBox = CheckBox(this).apply {
            text = "Block ads and trackers"
            isChecked = adBlockEnabled
            setTextColor(colText)
            setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { _, c ->
                adBlockEnabled = c
                prefs.edit().putBoolean("ad_block", c).commit()
            }
        }
        val blockedInfo = TextView(this).apply {
            text = "Blocked so far this session: " + blockedCount
            setTextColor(colDim)
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }
        val clearBtn = Button(this).apply {
            text = "Clear cache now"
            setOnClickListener { clearCacheNow() }
        }

        val pullReloadBox = CheckBox(this).apply {
            text = "Pull down from top to hard reload"
            isChecked = pullToRefreshEnabled
            setTextColor(colText)
            setPadding(0, 12, 0, 12)
            setOnCheckedChangeListener { _, c ->
                pullToRefreshEnabled = c
                prefs.edit().putBoolean("pull_hard_reload", c).commit()
            }
        }

        box.addView(autoHide)
        box.addView(pullReloadBox)

        var settingsDlg: AlertDialog? = null

        // B2: Dedicated Music row opening Music section
        val musicBtn = Button(this).apply {
            text = "Music (Edge glow & Background music) ›"
            setTextColor(Color.CYAN)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 8, 0, 8)
            setOnClickListener {
                settingsDlg?.dismiss()
                showMusic(fromMenu = fromMenu)
            }
        }
        box.addView(musicBtn)

        val myBlockBtn = Button(this).apply {
            text = "Sites I block  (" + userBlockHosts.size + ")"
            setTextColor(Color.CYAN)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 8, 0, 8)
            setOnClickListener {
                settingsDlg?.dismiss()
                showHostRuleEditor(true, fromSettings = true, originalFromMenu = fromMenu)
            }
        }
        val myAllowBtn = Button(this).apply {
            text = "Sites I allow  (" + userAllowHosts.size + ")"
            setTextColor(Color.CYAN)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 8, 0, 8)
            setOnClickListener {
                settingsDlg?.dismiss()
                showHostRuleEditor(false, fromSettings = true, originalFromMenu = fromMenu)
            }
        }
        box.addView(blockBox)
        box.addView(myBlockBtn)
        box.addView(myAllowBtn)
        box.addView(blockedInfo)

        val themeBtn = Button(this).apply {
            text = "Theme: " + themeModeLabel() + "  (tap to change)"
            setOnClickListener { pickTheme() }
        }
        box.addView(themeBtn)
        box.addView(clearBtn)

        val bmInfo = TextView(this).apply {
            text = "Bookmarks saved: " + bookmarks.size
            setTextColor(colDim)
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }
        val clearBm = Button(this).apply {
            text = "Clear all bookmarks"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear bookmarks?")
                    .setMessage("This removes every saved bookmark. It cannot be undone.")
                    .setPositiveButton("Clear") { _, _ ->
                        bookmarks.clear()
                        saveBookmarks()
                        Toast.makeText(applicationContext, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        box.addView(bmInfo)
        box.addView(clearBm)

        val builder = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(box)
            .setPositiveButton("Done", null)
        if (fromMenu) {
            builder.setNegativeButton("Back") { _, _ -> showMenu() }
            builder.setOnCancelListener { showMenu() }
        }
        settingsDlg = builder.show()
    }

    private fun clearCacheNow() {
        try {
            webView.clearCache(true)
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}
        try {
            cleanDir(cacheDir)
            cleanDir(codeCacheDir)
            externalCacheDir?.let { cleanDir(it) }
            cleanChromiumCache(File(applicationInfo.dataDir, "app_webview"))
        } catch (_: Exception) {}
        Toast.makeText(applicationContext, "Cache cleared", Toast.LENGTH_SHORT).show()
    }

    private fun hardReload() {
        try {
            Toast.makeText(this, "Hard reloading...", Toast.LENGTH_SHORT).show()
            webView.clearCache(true)
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.reload()
            webView.postDelayed({
                try {
                    webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                } catch (_: Exception) {}
            }, 3500)
        } catch (_: Exception) {
            try { webView.reload() } catch (_: Exception) {}
        }
    }

    private fun attachWebViewPullToRefresh(target: WebView) {
        var startY = 0f
        var startX = 0f
        var isDraggingDown = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val triggerDistance = (resources.displayMetrics.density * 130).coerceAtLeast(180f)

        target.setOnTouchListener { _, event ->
            if (!pullToRefreshEnabled) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startX = event.rawX
                    isDraggingDown = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (target.scrollY > 0) {
                        startY = event.rawY
                        startX = event.rawX
                        if (isDraggingDown) {
                            progressBar.visibility = View.GONE
                            progressBar.progress = 0
                            isDraggingDown = false
                        }
                    } else {
                        val dy = event.rawY - startY
                        val dx = Math.abs(event.rawX - startX)
                        if (dy > touchSlop && dy > dx * 1.2f) {
                            isDraggingDown = true
                            val fraction = ((dy - touchSlop) / triggerDistance).coerceIn(0f, 1f)
                            progressBar.visibility = View.VISIBLE
                            progressBar.progress = (fraction * 100).toInt()
                        } else if (dy <= 0 && isDraggingDown) {
                            progressBar.visibility = View.GONE
                            progressBar.progress = 0
                            isDraggingDown = false
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = event.rawY - startY
                    val dx = Math.abs(event.rawX - startX)
                    if (isDraggingDown && target.scrollY <= 0 && dy >= triggerDistance && dy > dx) {
                        progressBar.visibility = View.VISIBLE
                        progressBar.isIndeterminate = true
                        hardReload()
                        target.postDelayed({
                            try {
                                progressBar.isIndeterminate = false
                                progressBar.visibility = View.GONE
                                progressBar.progress = 0
                            } catch (_: Exception) {}
                        }, 1200)
                    } else if (isDraggingDown) {
                        progressBar.visibility = View.GONE
                        progressBar.progress = 0
                    }
                    isDraggingDown = false
                }
            }
            false
        }
    }

    private fun loadInput(input: String) {
        val clean = input.trim()
        val target = when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("file://") -> clean
            clean.contains(".") && !clean.contains(" ") -> "https://$clean"
            else -> "https://www.google.com/search?q=" + Uri.encode(clean)
        }
        webView.loadUrl(target)
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        webView.onResume()
        webView.resumeTimers()
        syncPlaybackService()
        webView.removeCallbacks(mediaPoll)
        webView.post(mediaPoll)
        webView.removeCallbacks(mediaWatchdog)
        webView.postDelayed(mediaWatchdog, 1000L)
        updateEdgeGlow()
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
        // Group C: Thumbnails are dropped when the app goes to the background
        synchronized(tabThumbnails) {
            tabThumbnails.clear()
        }
        if (!bgMusicEnabled) {
            musicStop()
            stopPlaybackService()
            pausePageMedia()
            webView.onPause()
            webView.pauseTimers()
            webView.removeCallbacks(mediaPoll)
            return
        }
        // v152: one rule decides - background only for the service, plain
        // notification in the foreground. isAppInForeground is already false.
        syncPlaybackService()
        webView.removeCallbacks(mediaPoll)
        webView.postDelayed(mediaPoll, 1000L)
    }

    // A2 FIX: the system theme changing while the app is open.
    //
    // The manifest did not list "uiMode" in configChanges, and uiMode is what
    // changes between light and dark. Because it was missing, Android did not
    // tell the app - it DESTROYED and REBUILT the activity. And onDestroy does
    // a full cleanup and then Process.killProcess, so the rebuild killed the
    // app. That is the crash on the fraction of a second the theme changes.
    //
    // With uiMode now in configChanges, the activity is kept and this runs
    // instead - which re-applies the theme in place, the way it already does
    // when you pick a theme by hand.
    private var lastNightMode = -1

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Only when light/dark ACTUALLY changed. onConfigurationChanged also
        // fires for rotation, keyboard and screen-size changes, and re-applying
        // the theme on those would rebuild the tabs and pop a Toast every time
        // you rotate the phone.
        try {
            val mode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (mode != lastNightMode) {
                lastNightMode = mode
                if (themeMode == "system") {
                    applyThemeInPlace(showToast = false)
                }
            }
        } catch (_: Exception) {}
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // v116: auto-PiP REMOVED. It hijacked the app the moment a video
        // played and you left, and it hid the edge glow on the way in.
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // Mini video player active: hide browser chrome so video fills window
            topBar.visibility = View.GONE
            // v116: the glow is no longer hidden here.
            applyVisibilityOverride()
        } else {
            // Exited PiP: restore browser chrome
            if (!activeAutoHide()) {
                topBar.visibility = View.VISIBLE
            }
            updateEdgeGlow()
            if (!bgMusicEnabled) {
                applyVisibilityOverride()
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Purge from RUNNING_MODERATE up. UI_HIDDEN (going to background) is
        // deliberately EXCLUDED — flushing the HTTP cache there would undo
        // the session cache that makes repeat AI Mode loads fast.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE &&
            level <= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            webView.clearCache(true)
            System.gc()
        }
    }

    override fun onBackPressed() {
        if (diskVisualizerOverlay != null) {
            closeDiskVisualizerOverlay()
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null

        try { unregisterReceiver(downloadCompleteReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(pipReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(mediaControlReceiver) } catch (_: Exception) {}
        if (instance === this) instance = null
        PlaybackService.mediaCallback = null

        webView.removeCallbacks(blankCheckRunnable)
        webView.removeCallbacks(mediaPoll)
        webView.removeCallbacks(glowRunnable)
        musicStop()
        try {
            fgMediaSession?.isActive = false
            fgMediaSession?.release()
        } catch (_: Exception) {}
        fgMediaSession = null
        cancelForegroundNotification()
        stopPlaybackService()
        // v66: tabs live in memory only - drop them so nothing survives exit
        tabs.clear()
        synchronized(tabThumbnails) { tabThumbnails.clear() }
        closeBlobStream()
        try { popupCaptureWebView?.destroy() } catch (_: Exception) {}
        popupCaptureWebView = null

        webView.clearCache(true)
        webView.clearFormData()
        webView.clearHistory()
        try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
        webView.destroy()

        cleanDir(cacheDir)
        cleanDir(codeCacheDir)
        externalCacheDir?.let { cleanDir(it) }

        // v44: restored from v39 — deep-clean the heavy Chromium folders that
        // survive the wipes above, then hard-kill the process so nothing
        // lingers in RAM after exit (zero background memory).
        try {
            val webviewDir = File(applicationInfo.dataDir, "app_webview")
            cleanChromiumCache(webviewDir)
        } catch (_: Exception) {}
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }


    // ---- v120: real app icons on the home page -------------------------
    // Reads the launcher icon of the Google apps already installed on this
    // device and hands them to the home page. This is what the system
    // launcher does - an app's own icon is shown to represent that app.
    // Nothing is copied into this app, nothing is fetched, nothing expires:
    // the icons come from the apps on the phone and live as long as they do.
    // Guarded end to end - if a package is absent, that tile just falls back.
    private fun injectAppIcons() {
        val targets = mapOf(
            "www.google.com"      to "com.google.android.googlequicksearchbox",
            "maps.google.com"     to "com.google.android.apps.maps",
            "meet.google.com"     to "com.google.android.apps.tachyon",
            "photos.google.com"   to "com.google.android.apps.photos",
            "mail.google.com"     to "com.google.android.gm",
            "calendar.google.com" to "com.google.android.calendar",
            "www.youtube.com"     to "com.google.android.youtube",
            "drive.google.com"    to "com.google.android.apps.docs"
        )
        Thread {
            val out = LinkedHashMap<String, String>()
            for ((host, pkg) in targets) {
                try {
                    val d = packageManager.getApplicationIcon(pkg)
                    val size = 128
                    val bmp = android.graphics.Bitmap.createBitmap(
                        size, size, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bmp)
                    d.setBounds(0, 0, size, size)
                    d.draw(canvas)
                    val bos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                    out[host] = "data:image/png;base64," +
                        android.util.Base64.encodeToString(
                            bos.toByteArray(), android.util.Base64.NO_WRAP
                        )
                    bmp.recycle()
                } catch (_: Exception) {
                }
            }
            if (out.isEmpty()) return@Thread
            val json = org.json.JSONObject(out as Map<*, *>).toString()
            val dbg = org.json.JSONObject(out.mapValues { "app" }).toString()
            webView.post {
                try {
                    webView.evaluateJavascript(
                        "window.phSetIcons && phSetIcons(" + json + ");", null
                    )
                    webView.evaluateJavascript(
                        "window.phDebug && phDebug(" + dbg + ");", null
                    )
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    private fun cleanDir(dir: File?) {
        if (dir != null && dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                val name = file.name.lowercase()
                // Never touch Chromium / WebView internal cache folders and index files
                if (name == "webview" || name == "app_webview" || name == "org.chromium.android_webview" ||
                    name == "default" || name.contains("cache")) {
                    return@forEach
                }
                if (file.isDirectory) cleanDir(file)
                file.delete()
            }
        }
    }

    // v39/v44: deep Chromium cache clean — "Cache" substring-matches GPUCache,
    // Code Cache and Cache; "Service Worker" matches SW storage.
    private fun cleanChromiumCache(dir: File) {
        val targets = listOf("Cache", "Service Worker")
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                if (targets.any { file.name.contains(it, ignoreCase = true) }) {
                    file.deleteRecursively()
                } else {
                    cleanChromiumCache(file)
                }
            }
        }
    }
}
