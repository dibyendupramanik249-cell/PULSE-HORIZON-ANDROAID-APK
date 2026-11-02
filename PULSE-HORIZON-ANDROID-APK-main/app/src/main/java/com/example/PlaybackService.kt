package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Background-music keep-alive and permanent MediaSession notification service.
 *
 * Implements:
 *  - Official MediaSession with transport controls (Play, Pause, Skip, Seek).
 *  - Standard Notification.MediaStyle with session token and live position scrubber bar.
 *  - Permanent, persistent notification that does not throttle or get suppressed by Android OS.
 *  - Stays active in low-power idle mode when paused in background so the user can resume anytime.
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "pulse_horizon_playback"
        const val NOTIFICATION_ID = 4711

        const val ACTION_PLAY_PAUSE = "com.pulsehorizon.browser.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "com.pulsehorizon.browser.ACTION_PREV"
        const val ACTION_NEXT = "com.pulsehorizon.browser.ACTION_NEXT"

        @Volatile var instance: PlaybackService? = null

        // In-process callback to MainActivity for transport controls
        var mediaCallback: MediaControlCallback? = null
    }

    interface MediaControlCallback {
        fun onPlay()
        fun onPause()
        fun onSkipNext()
        fun onSkipPrev()
        fun onSeekTo(posMs: Long)
    }

    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isRunning = false
    @Volatile private var isPlaying = true
    @Volatile private var currentBeatLevel = 0f
    @Volatile private var currentPositionMs = 0L
    @Volatile private var currentDurationMs = 0L
    @Volatile private var mediaTitle = "Pulse Horizon"
    @Volatile private var mediaArtist = "Audio is playing"

    // Small art badge for media metadata
    private val artWidth = 64
    private val artHeight = 64
    private val artBitmap = Bitmap.createBitmap(artWidth, artHeight, Bitmap.Config.ARGB_8888)
    private val artCanvas = Canvas(artBitmap)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val artRect = RectF()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        createChannel()
        setupMediaSession()
        renderArt()
        showNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_PLAY_PAUSE -> {
                if (isPlaying) {
                    mediaSession?.controller?.transportControls?.pause()
                } else {
                    mediaSession?.controller?.transportControls?.play()
                }
            }
            ACTION_PREV -> {
                mediaSession?.controller?.transportControls?.skipToPrevious()
            }
            ACTION_NEXT -> {
                mediaSession?.controller?.transportControls?.skipToNext()
            }
        }
        showNotification()
        return START_STICKY
    }

    fun showNotification() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun setupMediaSession() {
        try {
            val session = MediaSession(this, "PulseHorizonSession").apply {
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        isPlaying = true
                        mediaCallback?.onPlay()
                        syncPlaybackState()
                        updateNotification()
                    }

                    override fun onPause() {
                        isPlaying = false
                        mediaCallback?.onPause()
                        syncPlaybackState()
                        updateNotification()
                    }

                    override fun onSkipToNext() {
                        mediaCallback?.onSkipNext()
                    }

                    override fun onSkipToPrevious() {
                        mediaCallback?.onSkipPrev()
                    }

                    override fun onSeekTo(pos: Long) {
                        currentPositionMs = pos
                        mediaCallback?.onSeekTo(pos)
                        syncPlaybackState()
                    }
                })
                isActive = true
            }
            mediaSession = session
            syncPlaybackState()
            syncMetadata()
        } catch (_: Exception) {}
    }

    private fun syncPlaybackState() {
        val session = mediaSession ?: return
        try {
            val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            val speed = if (isPlaying) 1.0f else 0.0f
            val actions = PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SEEK_TO

            val pbState = PlaybackState.Builder()
                .setActions(actions)
                .setState(state, currentPositionMs, speed, SystemClock.elapsedRealtime())
                .build()

            session.setPlaybackState(pbState)
        } catch (_: Exception) {}
    }

    private fun syncMetadata() {
        val session = mediaSession ?: return
        try {
            val meta = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, mediaTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, mediaArtist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, currentDurationMs)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artBitmap)
                .build()
            session.setMetadata(meta)
        } catch (_: Exception) {}
    }

    fun updateBeatLevel(beat: Float) {
        currentBeatLevel = beat.coerceIn(0f, 1f)
    }

    fun updatePlaybackState(playing: Boolean, posMs: Long, durMs: Long, title: String) {
        val stateChanged = (isPlaying != playing)
        val titleChanged = (title.isNotBlank() && title != mediaTitle)
        isPlaying = playing
        currentPositionMs = posMs
        currentDurationMs = durMs
        if (title.isNotBlank()) mediaTitle = title

        syncPlaybackState()
        syncMetadata()

        if (stateChanged || titleChanged) {
            updateNotification()
        }
    }

    private fun renderArt() {
        artCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        artPaint.color = Color.parseColor("#7000FF") // Neon Purple
        artRect.set(0f, 0f, artWidth.toFloat(), artHeight.toFloat())
        artCanvas.drawRoundRect(artRect, 16f, 16f, artPaint)
        artPaint.color = Color.parseColor("#00F0FF") // Cyan accent
        artRect.set(12f, 12f, (artWidth - 12).toFloat(), (artHeight - 12).toFloat())
        artCanvas.drawRoundRect(artRect, 10f, 10f, artPaint)
    }

    private fun updateNotification() {
        if (!isRunning) return
        try {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr?.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Background music",
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.description = "Shown while audio plays in the background"
                ch.setShowBadge(false)
                mgr?.createNotificationChannel(ch)
            }
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, open, pFlags)

        // A3 FIX: tapping a MEDIA notification is routed through the
        // MediaSession, not just the notification. The content intent was
        // already correct, but the session had no activity set - so the system
        // fell back to its own behaviour and you landed in audio settings
        // instead of the browser. Give the session the same intent.
        try { mediaSession?.setSessionActivity(pi) } catch (_: Exception) {}

        val prevPi = getServicePendingIntent(ACTION_PREV, 101)
        val playPausePi = getServicePendingIntent(ACTION_PLAY_PAUSE, 102)
        val nextPi = getServicePendingIntent(ACTION_NEXT, 103)

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        b.setContentTitle(mediaTitle)
            .setContentText(if (isPlaying) mediaArtist else "Paused")
            .setSmallIcon(R.drawable.ic_media_play)
            .setLargeIcon(artBitmap)
            .setContentIntent(pi)
            .setOngoing(isPlaying)

        val session = mediaSession
        if (session != null) {
            val mediaStyle = Notification.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
            b.setStyle(mediaStyle)
        }

        b.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_media_prev),
                "Previous",
                prevPi
            ).build()
        )
        b.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(
                    this,
                    if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
                ),
                if (isPlaying) "Pause" else "Play",
                playPausePi
            ).build()
        )
        b.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_media_next),
                "Next",
                nextPi
            ).build()
        )

        return b.build()
    }

    private fun getServicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Exception) {}
        mediaSession = null
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }
}
