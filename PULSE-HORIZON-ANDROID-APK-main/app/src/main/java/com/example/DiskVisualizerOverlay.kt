package com.example

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Full-screen overlay container for Disk Visualiser with live controls.
 */
class DiskVisualizerOverlay(
    private val activity: Activity,
    private val onPickSong: () -> Unit,
    private val onPickLogo: () -> Unit,
    private val onExportMp4: () -> Unit,
    private val onClose: () -> Unit
) : FrameLayout(activity) {

    val visualizerView = DiskVisualizerView(activity)

    private val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private var hapticStrength = 0.0f // 0f = OFF by default

    // Preset colours for quick palette: null = rainbow
    private val colorPresets = listOf(
        Pair("Rainbow", null),
        Pair("Red", Color.parseColor("#FF1744")),
        Pair("Orange", Color.parseColor("#FF6D00")),
        Pair("Yellow", Color.parseColor("#FFD600")),
        Pair("Green", Color.parseColor("#00E676")),
        Pair("Cyan", Color.parseColor("#00E5FF")),
        Pair("Blue", Color.parseColor("#2979FF")),
        Pair("Purple", Color.parseColor("#D500F9")),
        Pair("White", Color.parseColor("#FFFFFF"))
    )
    private var currentColorIndex = 0

    init {
        setBackgroundColor(Color.parseColor("#09090D"))
        isClickable = true
        isFocusable = true

        // 1. Visualizer View in center
        addView(
            visualizerView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        // Haptics wiring: pulses on beat if hapticStrength > 0
        visualizerView.onBeatHitListener = { beat ->
            if (hapticStrength > 0f && vibrator != null && vibrator.hasVibrator()) {
                val durMs = 30L
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val amp = (beat * 255f * hapticStrength).toInt().coerceIn(1, 255)
                    try {
                        if (vibrator.hasAmplitudeControl()) {
                            vibrator.vibrate(VibrationEffect.createOneShot(durMs, amp))
                        } else {
                            vibrator.vibrate(
                                VibrationEffect.createOneShot(durMs, VibrationEffect.DEFAULT_AMPLITUDE)
                            )
                        }
                    } catch (_: Exception) {}
                } else {
                    @Suppress("DEPRECATION")
                    try { vibrator.vibrate(durMs) } catch (_: Exception) {}
                }
            }
        }

        // 2. Top bar
        setupTopBar()

        // 3. Bottom live controls bar
        setupBottomControls()
    }

    private fun setupTopBar() {
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 48, 32, 16)
            setBackgroundColor(Color.parseColor("#99000000"))
        }

        // Back / Close button
        val closeBtn = Button(activity).apply {
            text = "← Back"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onClose() }
        }
        topBar.addView(closeBtn)

        val spacer = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        topBar.addView(spacer)

        // Mode Switcher (Mode 1: Disk | Mode 2: Disk + Edge)
        val modeBtn = Button(activity).apply {
            text = "Mode: Disk"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#2A2A3A"), 16f)
            setOnClickListener {
                if (visualizerView.mode == DiskVisualizerView.Mode.DISK) {
                    visualizerView.mode = DiskVisualizerView.Mode.DISK_AND_EDGE
                    text = "Mode: Disk + Edge"
                } else {
                    visualizerView.mode = DiskVisualizerView.Mode.DISK
                    text = "Mode: Disk"
                }
            }
        }
        topBar.addView(modeBtn)

        // Logo Button
        val logoBtn = Button(activity).apply {
            text = "Logo 🖼"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#2A2A3A"), 16f)
            setOnClickListener { onPickLogo() }
        }
        val logoLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 16 }
        topBar.addView(logoBtn, logoLp)

        // Export MP4 Button
        val exportBtn = Button(activity).apply {
            text = "Export 🎬"
            textSize = 13f
            setTextColor(Color.CYAN)
            background = createRoundedDrawable(Color.parseColor("#1B2A3A"), 16f)
            setOnClickListener { onExportMp4() }
        }
        val exportLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 16 }
        topBar.addView(exportBtn, exportLp)

        val topLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
        }
        addView(topBar, topLp)
    }

    private fun setupBottomControls() {
        val bottomContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
            background = createRoundedTopDrawable(Color.parseColor("#E6101018"), 24f)
        }

        // Live controls row: [ Colour ● ] [ Vibration ▬▬▬ ] [ Haptics ▬▬ ]
        val controlsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 1. Colour button
        val colorBtn = Button(activity).apply {
            text = "Colour ●"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#2A2A38"), 16f)
            setOnClickListener {
                currentColorIndex = (currentColorIndex + 1) % colorPresets.size
                val preset = colorPresets[currentColorIndex]
                visualizerView.customColor = preset.second
                text = "${preset.first} ●"
                if (preset.second != null) {
                    setTextColor(preset.second!!)
                } else {
                    setTextColor(Color.WHITE)
                }
            }
        }
        controlsRow.addView(colorBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 2. Vibration Slider
        val vibLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 20
                rightMargin = 12
            }
        }
        val vibLabel = TextView(activity).apply {
            text = "Vibration: 100%"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        val vibBar = SeekBar(activity).apply {
            max = 200 // 0% to 200%
            progress = 100 // default 100%
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
                    val scale = p / 100f
                    visualizerView.vibrationMultiplier = scale
                    vibLabel.text = "Vibration: $p%"
                }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        }
        vibLayout.addView(vibLabel)
        vibLayout.addView(vibBar)
        controlsRow.addView(vibLayout)

        // 3. Haptics Slider
        val hapLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 12
            }
        }
        val hapLabel = TextView(activity).apply {
            text = "Haptics: OFF"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        val hapBar = SeekBar(activity).apply {
            max = 100
            progress = 0 // OFF by default
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
                    hapticStrength = p / 100f
                    hapLabel.text = if (p == 0) "Haptics: OFF" else "Haptics: $p%"
                }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        }
        hapLayout.addView(hapLabel)
        hapLayout.addView(hapBar)
        controlsRow.addView(hapLayout)

        bottomContainer.addView(controlsRow)

        // Change Song (+) button row
        val changeSongRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        val pickSongBtn = Button(activity).apply {
            text = "+ Pick a song from phone"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#333348"), 14f)
            setOnClickListener { onPickSong() }
        }
        changeSongRow.addView(pickSongBtn)
        bottomContainer.addView(changeSongRow)

        val bottomLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        }
        addView(bottomContainer, bottomLp)
    }

    fun setLogoFromUri(uri: Uri) {
        try {
            val stream = activity.contentResolver.openInputStream(uri)
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(stream, null, opts)
            stream?.close()

            // Decode bounded to max 512x512 to preserve zero-leak memory requirements
            val reqSize = 512
            var inSample = 1
            if (opts.outHeight > reqSize || opts.outWidth > reqSize) {
                val halfH = opts.outHeight / 2
                val halfW = opts.outWidth / 2
                while ((halfH / inSample) >= reqSize && (halfW / inSample) >= reqSize) {
                    inSample *= 2
                }
            }
            val decOpts = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val stream2 = activity.contentResolver.openInputStream(uri)
            val bmp = BitmapFactory.decodeStream(stream2, null, decOpts)
            stream2?.close()
            visualizerView.logoBitmap = bmp
            Toast.makeText(activity, "Disk logo updated", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Failed to load logo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createRoundedDrawable(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * activity.resources.displayMetrics.density
        }
    }

    private fun createRoundedTopDrawable(color: Int, radiusDp: Float): GradientDrawable {
        val r = radiusDp * activity.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }
}
