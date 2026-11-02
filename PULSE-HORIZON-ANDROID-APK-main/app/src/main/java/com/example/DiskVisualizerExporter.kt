package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SweepGradient
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Universal Off-screen Visualizer Exporter.
 * Encodes full-song visualizer animation into standard 720x720 MP4 video (H.264 + AAC).
 * Guaranteed compatibility across all Android devices (API 26+) with zero native dependencies.
 */
class DiskVisualizerExporter(private val context: Context) {

    fun export(
        audioUri: Uri,
        mode: DiskVisualizerView.Mode,
        customColor: Int?,
        vibrationScale: Float,
        logoBitmap: Bitmap?,
        onProgress: (Int) -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            var pfd: ParcelFileDescriptor? = null
            var tempPcmFile: File? = null
            var videoEncoder: MediaCodec? = null
            var audioEncoder: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var outFile: File? = null

            try {
                // 1. Open audio source safely
                pfd = if (audioUri.scheme == "content" || audioUri.scheme == "file") {
                    try {
                        context.contentResolver.openFileDescriptor(audioUri, "r")
                    } catch (e: Exception) {
                        null
                    }
                } else null

                val audioExtractor = MediaExtractor()
                try {
                    if (pfd != null) {
                        audioExtractor.setDataSource(pfd.fileDescriptor)
                    } else {
                        audioExtractor.setDataSource(context, audioUri, null)
                    }
                } catch (e: Exception) {
                    throw IllegalStateException("Cannot read audio file: ${e.message}")
                }

                var audioTrackIdx = -1
                var inputAudioFormat: MediaFormat? = null
                for (i in 0 until audioExtractor.trackCount) {
                    val fmt = audioExtractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIdx = i
                        inputAudioFormat = fmt
                        break
                    }
                }

                if (audioTrackIdx < 0 || inputAudioFormat == null) {
                    audioExtractor.release()
                    throw IllegalStateException("No valid audio track found in file")
                }

                audioExtractor.selectTrack(audioTrackIdx)
                val durationUs = if (inputAudioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    inputAudioFormat.getLong(MediaFormat.KEY_DURATION)
                } else {
                    60_000_000L
                }

                val inputSampleRate = if (inputAudioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    inputAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else 44100

                val inputChannels = if (inputAudioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    inputAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else 2

                // Setup Beat Timeline for sample-accurate analysis
                val beatTimeline = BeatTimeline(inputSampleRate, inputChannels)

                // 2. PASS 1: Decode entire audio stream to PCM file & compute BeatTimeline
                postProgress(onProgress, 2)
                tempPcmFile = File.createTempFile("pulse_temp_pcm_", ".raw", context.cacheDir)
                val pcmOut = FileOutputStream(tempPcmFile)

                val audioDecoder = MediaCodec.createDecoderByType(
                    inputAudioFormat.getString(MediaFormat.KEY_MIME)!!
                )
                audioDecoder.configure(inputAudioFormat, null, null, 0)
                audioDecoder.start()

                val decoderInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var decodedPcmBytes = 0L

                while (!outputDone) {
                    if (!inputDone) {
                        val inIx = audioDecoder.dequeueInputBuffer(10000)
                        if (inIx >= 0) {
                            val inBuf = audioDecoder.getInputBuffer(inIx)
                            val sz = if (inBuf != null) audioExtractor.readSampleData(inBuf, 0) else -1
                            if (sz < 0) {
                                audioDecoder.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                audioDecoder.queueInputBuffer(inIx, 0, sz, audioExtractor.sampleTime, 0)
                                audioExtractor.advance()
                            }
                        }
                    }

                    val outIx = audioDecoder.dequeueOutputBuffer(decoderInfo, 10000)
                    if (outIx >= 0) {
                        if (decoderInfo.size > 0) {
                            val outBuf = audioDecoder.getOutputBuffer(outIx)
                            if (outBuf != null) {
                                outBuf.position(decoderInfo.offset)
                                outBuf.limit(decoderInfo.offset + decoderInfo.size)
                                val pcmChunk = ByteArray(decoderInfo.size)
                                outBuf.get(pcmChunk)
                                pcmOut.write(pcmChunk)
                                decodedPcmBytes += pcmChunk.size
                                beatTimeline.feed(pcmChunk, 0, pcmChunk.size)
                            }
                        }
                        audioDecoder.releaseOutputBuffer(outIx, false)
                        if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }

                pcmOut.flush()
                pcmOut.close()
                audioDecoder.stop()
                audioDecoder.release()
                audioExtractor.release()

                postProgress(onProgress, 12)

                // 3. Setup Video & AAC Encoders + MediaMuxer
                val width = 720
                val height = 720
                val fps = 30
                val bitRate = 3_500_000

                // Total frames based on decoded audio or duration
                val bytesPerSec = inputSampleRate * inputChannels * 2
                val actualDurationSec = if (bytesPerSec > 0 && decodedPcmBytes > 0) {
                    decodedPcmBytes.toDouble() / bytesPerSec
                } else {
                    durationUs / 1_000_000.0
                }
                val totalFrames = maxOf(1, (actualDurationSec * fps).toInt())

                val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                    ?: context.filesDir
                val file = File(outDir, "pulse_visualizer_${System.currentTimeMillis()}.mp4")
                outFile = file

                val mux = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = mux

                // Select video color format (NV12 or I420)
                val videoCodecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                val videoCodecName = videoCodecList.findEncoderForFormat(
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                ) ?: "OMX.google.h264.encoder"

                val videoCodecInfo = videoCodecList.codecInfos.find { it.name == videoCodecName }
                val (colorFormat, isSemiPlanar) = if (videoCodecInfo != null) {
                    selectColorFormat(videoCodecInfo, MediaFormat.MIMETYPE_VIDEO_AVC)
                } else {
                    Pair(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, true)
                }

                val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }

                val vEnc = MediaCodec.createByCodecName(videoCodecName)
                videoEncoder = vEnc
                vEnc.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                vEnc.start()

                // AAC Audio Encoder (44100/48000 Hz, stereo/mono, 128 kbps)
                val aacFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, inputSampleRate, inputChannels.coerceIn(1, 2)
                ).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                }
                val aEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                audioEncoder = aEnc
                aEnc.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                aEnc.start()

                // 4. Wave Launcher for Frame-Accurate Dynamics
                val launcher = WaveLauncher(inputSampleRate)
                launcher.setTimeline(beatTimeline.all())

                val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(frameBitmap)
                val pixels = IntArray(width * height)
                val yuvBuffer = ByteArray(width * height * 3 / 2)

                // Visualizer Drawing Elements
                val cx = width / 2f
                val cy = height / 2f
                val diskRadius = width * 0.28f
                val baseThickness = 12f * (width / 360f)

                val diskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.parseColor("#0C0C12")
                }
                val diskInnerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = Color.parseColor("#222230")
                    strokeWidth = 2f
                }
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
                val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

                val edgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
                val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
                val edgeWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

                val colors = buildShaderColors(customColor)
                val positions = floatArrayOf(
                    0.00f, 0.04f, 0.08f, 0.12f, 0.16f, 0.20f, 0.24f, 0.28f, 0.32f, 0.36f, 0.40f, 1.00f
                )
                val sweepShader = SweepGradient(cx, cy, colors, positions)
                strokePaint.shader = sweepShader
                glowPaint.shader = sweepShader
                wavePaint.shader = sweepShader
                edgeStrokePaint.shader = sweepShader
                edgeGlowPaint.shader = sweepShader
                edgeWavePaint.shader = sweepShader

                val matrix = Matrix()
                val logoClipPath = Path()
                val WAVE_N = 220
                val xs = FloatArray(WAVE_N)
                val ys = FloatArray(WAVE_N)
                val oxs = FloatArray(WAVE_N)
                val oys = FloatArray(WAVE_N)
                val wavePath = Path()
                val linePath = Path()

                val edgeXs = FloatArray(WAVE_N)
                val edgeYs = FloatArray(WAVE_N)
                val edgeIxs = FloatArray(WAVE_N)
                val edgeIys = FloatArray(WAVE_N)
                val edgeWavePath = Path()

                var travelAngle = 0f
                var wavePhase = 0f
                var tremorPhase = 0f
                var windLevel = 0f
                var lastBeat = 0f
                var gust = 0f
                var beatLevel = 0f
                val timeScale = 1.0f // 30 fps reference = 1.0f

                var muxerVideoTrack = -1
                var muxerAudioTrack = -1
                var muxerStarted = false

                val videoBufInfo = MediaCodec.BufferInfo()
                val audioBufInfo = MediaCodec.BufferInfo()

                fun drainEncoders(endOfStream: Boolean) {
                    // Drain Video
                    while (true) {
                        val status = vEnc.dequeueOutputBuffer(videoBufInfo, 2000)
                        if (status == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (muxerVideoTrack < 0) {
                                muxerVideoTrack = mux.addTrack(vEnc.outputFormat)
                                if (muxerAudioTrack >= 0 && !muxerStarted) {
                                    mux.start()
                                    muxerStarted = true
                                }
                            }
                        } else if (status >= 0) {
                            val encoded = vEnc.getOutputBuffer(status)
                            if (encoded != null && videoBufInfo.size > 0 && muxerStarted) {
                                encoded.position(videoBufInfo.offset)
                                encoded.limit(videoBufInfo.offset + videoBufInfo.size)
                                mux.writeSampleData(muxerVideoTrack, encoded, videoBufInfo)
                            }
                            vEnc.releaseOutputBuffer(status, false)
                            if (videoBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }

                    // Drain Audio
                    while (true) {
                        val status = aEnc.dequeueOutputBuffer(audioBufInfo, 2000)
                        if (status == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (muxerAudioTrack < 0) {
                                muxerAudioTrack = mux.addTrack(aEnc.outputFormat)
                                if (muxerVideoTrack >= 0 && !muxerStarted) {
                                    mux.start()
                                    muxerStarted = true
                                }
                            }
                        } else if (status >= 0) {
                            val encoded = aEnc.getOutputBuffer(status)
                            if (encoded != null && audioBufInfo.size > 0 && muxerStarted) {
                                encoded.position(audioBufInfo.offset)
                                encoded.limit(audioBufInfo.offset + audioBufInfo.size)
                                mux.writeSampleData(muxerAudioTrack, encoded, audioBufInfo)
                            }
                            aEnc.releaseOutputBuffer(status, false)
                            if (audioBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }

                // 5. Stream PCM into AAC Encoder alongside Video Frames
                val pcmIn = FileInputStream(tempPcmFile)
                val pcmChunkBuf = ByteArray(4096)
                var pcmBytesFed = 0L
                var audioFedDone = false

                for (f in 0 until totalFrames) {
                    val frameTimeSec = f.toDouble() / fps
                    val frameSamplePos = (frameTimeSec * inputSampleRate).toLong()

                    // Advance wave dynamics with true beat analysis
                    launcher.advance(frameSamplePos, 1.0f / fps)
                    val fromScore = launcher.level()
                    beatLevel = if (fromScore > beatLevel) fromScore
                                else beatLevel * 0.55f
                    val pulse = (0.30f + 0.70f * beatLevel).coerceIn(0.20f, 1f)

                    travelAngle = (travelAngle + 3.2f * timeScale) % 360f
                    matrix.setRotate(travelAngle, cx, cy)
                    sweepShader.setLocalMatrix(matrix)

                    wavePhase += (0.045f + 0.11f * beatLevel) * timeScale
                    tremorPhase = (tremorPhase + 2.073f * timeScale) % 6.2831855f

                    val riseDelta = (beatLevel - lastBeat) / timeScale
                    if (riseDelta > 0.06f) {
                        gust = (gust + riseDelta * 2.4f).coerceAtMost(1.3f)
                        if (tremorPhase > 1.0f) tremorPhase = 0f
                    }
                    lastBeat = beatLevel
                    gust *= 0.82f
                    windLevel = beatLevel

                    val thickness = baseThickness * (0.48f + 1.15f * beatLevel)
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

                    // Render Frame to Bitmap
                    canvas.drawColor(Color.parseColor("#09090D"))

                    // Mode 2: Outer Edge Glow
                    if (mode == DiskVisualizerView.Mode.DISK_AND_EDGE) {
                        drawOuterEdge(
                            canvas, cx, cy, width.toFloat(), height.toFloat(),
                            baseThickness, windLevel, gust, vibrationScale, tremorPhase, wavePhase,
                            edgeStrokePaint, edgeGlowPaint, edgeWavePaint,
                            edgeXs, edgeYs, edgeIxs, edgeIys, edgeWavePath, WAVE_N
                        )
                    }

                    // Disk Visualizer
                    drawCenterDisk(
                        canvas, cx, cy, diskRadius, baseThickness, windLevel, gust, vibrationScale,
                        tremorPhase, wavePhase, travelAngle, logoBitmap,
                        diskPaint, diskInnerRingPaint, strokePaint, glowPaint, wavePaint, logoPaint,
                        xs, ys, oxs, oys, wavePath, linePath, logoClipPath, WAVE_N
                    )

                    // Convert frame to YUV and queue to Video Encoder
                    frameBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    encodeYUV420(pixels, yuvBuffer, width, height, isSemiPlanar)

                    val vInIx = vEnc.dequeueInputBuffer(10000)
                    if (vInIx >= 0) {
                        val inBuf = vEnc.getInputBuffer(vInIx)
                        if (inBuf != null) {
                            inBuf.clear()
                            inBuf.put(yuvBuffer)
                            val ptsUs = (f * 1_000_000L) / fps
                            val flags = if (f == totalFrames - 1) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            vEnc.queueInputBuffer(vInIx, 0, yuvBuffer.size, ptsUs, flags)
                        }
                    }

                    // Feed corresponding chunk of audio to AAC Encoder
                    val targetPcmBytes = ((f + 1).toDouble() / fps * bytesPerSec).toLong()
                    while (!audioFedDone && pcmBytesFed < targetPcmBytes) {
                        val toRead = minOf(pcmChunkBuf.size.toLong(), targetPcmBytes - pcmBytesFed).toInt()
                        val bytesRead = pcmIn.read(pcmChunkBuf, 0, toRead)
                        if (bytesRead <= 0) {
                            audioFedDone = true
                            val aInIx = aEnc.dequeueInputBuffer(10000)
                            if (aInIx >= 0) {
                                aEnc.queueInputBuffer(aInIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                            break
                        }
                        val aInIx = aEnc.dequeueInputBuffer(10000)
                        if (aInIx >= 0) {
                            val inBuf = aEnc.getInputBuffer(aInIx)
                            if (inBuf != null) {
                                inBuf.clear()
                                inBuf.put(pcmChunkBuf, 0, bytesRead)
                                val ptsUs = (pcmBytesFed * 1_000_000L) / bytesPerSec
                                aEnc.queueInputBuffer(aInIx, 0, bytesRead, ptsUs, 0)
                                pcmBytesFed += bytesRead
                            }
                        }
                    }

                    drainEncoders(false)

                    if (f % 15 == 0) {
                        val pct = 15 + ((f * 83) / totalFrames)
                        postProgress(onProgress, pct)
                    }
                }

                // Finish Audio Stream if needed
                if (!audioFedDone) {
                    val aInIx = aEnc.dequeueInputBuffer(10000)
                    if (aInIx >= 0) {
                        aEnc.queueInputBuffer(aInIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }

                // Final flush of encoders
                var drainLoops = 0
                while (drainLoops++ < 100) {
                    drainEncoders(true)
                    Thread.sleep(10)
                }

                pcmIn.close()
                frameBitmap.recycle()

                try { vEnc.stop() } catch (_: Exception) {}
                try { vEnc.release() } catch (_: Exception) {}
                try { aEnc.stop() } catch (_: Exception) {}
                try { aEnc.release() } catch (_: Exception) {}
                try { mux.stop() } catch (_: Exception) {}
                try { mux.release() } catch (_: Exception) {}

                postProgress(onProgress, 100)
                Handler(Looper.getMainLooper()).post {
                    onComplete(file)
                }

            } catch (e: Exception) {
                Log.e("DiskExporter", "Visualizer export failed", e)
                try { videoEncoder?.stop() } catch (_: Exception) {}
                try { videoEncoder?.release() } catch (_: Exception) {}
                try { audioEncoder?.stop() } catch (_: Exception) {}
                try { audioEncoder?.release() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
                outFile?.delete()
                Handler(Looper.getMainLooper()).post {
                    onError(e.message ?: "Export failed")
                }
            } finally {
                try { pfd?.close() } catch (_: Exception) {}
                try { tempPcmFile?.delete() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun postProgress(cb: (Int) -> Unit, pct: Int) {
        Handler(Looper.getMainLooper()).post { cb(pct.coerceIn(0, 100)) }
    }

    private fun buildShaderColors(customColor: Int?): IntArray {
        return if (customColor != null) {
            val c = customColor
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            intArrayOf(
                Color.TRANSPARENT, Color.TRANSPARENT,
                Color.argb(0, r, g, b), Color.argb(255, r, g, b),
                Color.argb(220, (r * 0.8f).toInt(), (g * 0.8f).toInt(), (b * 0.8f).toInt()),
                Color.argb(255, (r * 1.1f).coerceAtMost(255f).toInt(), (g * 1.1f).coerceAtMost(255f).toInt(), (b * 1.1f).coerceAtMost(255f).toInt()),
                Color.argb(180, r, g, b), Color.argb(255, r, g, b),
                Color.argb(240, (r * 0.9f).toInt(), (g * 0.9f).toInt(), (b * 0.9f).toInt()),
                Color.argb(255, r, g, b), Color.argb(0, r, g, b),
                Color.TRANSPARENT
            )
        } else {
            intArrayOf(
                Color.TRANSPARENT, Color.TRANSPARENT,
                Color.argb(0, 255, 0, 80), Color.argb(255, 255, 0, 80),
                Color.argb(255, 255, 120, 0), Color.argb(255, 255, 230, 0),
                Color.argb(255, 0, 255, 120), Color.argb(255, 0, 220, 255),
                Color.argb(255, 60, 80, 255), Color.argb(255, 200, 0, 255),
                Color.argb(0, 200, 0, 255), Color.TRANSPARENT
            )
        }
    }

    private fun selectColorFormat(codecInfo: MediaCodecInfo, mimeType: String): Pair<Int, Boolean> {
        val capabilities = codecInfo.getCapabilitiesForType(mimeType)
        for (f in capabilities.colorFormats) {
            if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                return Pair(f, true)
            }
        }
        for (f in capabilities.colorFormats) {
            if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                return Pair(f, false)
            }
        }
        return Pair(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, true)
    }

    private fun encodeYUV420(
        argb: IntArray,
        yuv: ByteArray,
        width: Int,
        height: Int,
        isSemiPlanar: Boolean
    ) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4

        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[index++]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val uByte = u.coerceIn(0, 255).toByte()
                    val vByte = v.coerceIn(0, 255).toByte()
                    if (isSemiPlanar) {
                        yuv[uvIndex++] = uByte
                        yuv[uvIndex++] = vByte
                    } else {
                        yuv[uIndex++] = uByte
                        yuv[vIndex++] = vByte
                    }
                }
            }
        }
    }

    private fun drawCenterDisk(
        canvas: Canvas, cx: Float, cy: Float, diskRadius: Float, baseThickness: Float,
        windLevel: Float, gust: Float, vibrationScale: Float, tremorPhase: Float, wavePhase: Float,
        travelAngle: Float, logoBitmap: Bitmap?,
        diskPaint: Paint, diskInnerRingPaint: Paint, strokePaint: Paint, glowPaint: Paint, wavePaint: Paint, logoPaint: Paint,
        xs: FloatArray, ys: FloatArray, oxs: FloatArray, oys: FloatArray,
        wavePath: Path, linePath: Path, logoClipPath: Path, waveN: Int
    ) {
        val amp = baseThickness * (0.35f + 2.4f * (windLevel + gust))
        val tremor = baseThickness * 0.90f * vibrationScale * windLevel * sin(tremorPhase.toDouble()).toFloat()

        var i = 0
        while (i < waveN) {
            val u = i.toFloat() / waveN
            val th = u * 6.2831855f
            val c = cos(th.toDouble()).toFloat()
            val sn = sin(th.toDouble()).toFloat()

            xs[i] = cx + diskRadius * c
            ys[i] = cy + diskRadius * sn

            val s1 = sin((wavePhase + u * 12.566f).toDouble()).toFloat()
            val s2 = sin((wavePhase * 1.7f + u * 25.0f + 1.3f).toDouble()).toFloat()
            val rSurface = amp * (0.55f + 0.45f * (s1 * s1 * s1)) * (0.60f + 0.40f * (s2 * s2 * s2))
            oxs[i] = cx + (diskRadius + rSurface + tremor) * c
            oys[i] = cy + (diskRadius + rSurface + tremor) * sn
            i++
        }

        wavePath.reset()
        wavePath.moveTo(xs[0], ys[0])
        i = 1
        while (i < waveN) { wavePath.lineTo(xs[i], ys[i]); i++ }
        i = waveN - 1
        while (i >= 0) { wavePath.lineTo(oxs[i], oys[i]); i-- }
        wavePath.close()
        canvas.drawPath(wavePath, wavePaint)

        linePath.reset()
        linePath.moveTo(xs[0], ys[0])
        i = 1
        while (i < waveN) { linePath.lineTo(xs[i], ys[i]); i++ }
        linePath.close()
        canvas.drawPath(linePath, glowPaint)
        canvas.drawPath(linePath, strokePaint)

        canvas.drawCircle(cx, cy, diskRadius - 1f, diskPaint)
        canvas.drawCircle(cx, cy, diskRadius * 0.82f, diskInnerRingPaint)
        canvas.drawCircle(cx, cy, diskRadius * 0.64f, diskInnerRingPaint)

        val logoR = diskRadius * 0.42f
        if (logoBitmap != null && !logoBitmap.isRecycled) {
            canvas.save()
            logoClipPath.reset()
            logoClipPath.addCircle(cx, cy, logoR, Path.Direction.CW)
            canvas.clipPath(logoClipPath)
            canvas.rotate(travelAngle, cx, cy)
            val scale = (logoR * 2f) / maxOf(logoBitmap.width, logoBitmap.height).toFloat()
            val drawW = logoBitmap.width * scale
            val drawH = logoBitmap.height * scale
            canvas.drawBitmap(logoBitmap, cx - drawW / 2f, cy - drawH / 2f, logoPaint)
            canvas.restore()
            canvas.drawCircle(cx, cy, logoR, strokePaint)
        } else {
            canvas.save()
            canvas.rotate(travelAngle, cx, cy)
            canvas.drawCircle(cx, cy, logoR * 0.5f, diskInnerRingPaint)
            canvas.drawCircle(cx, cy, 6f, strokePaint)
            canvas.restore()
        }
    }

    private fun drawOuterEdge(
        canvas: Canvas, cx: Float, cy: Float, width: Float, height: Float,
        baseThickness: Float, windLevel: Float, gust: Float, vibrationScale: Float,
        tremorPhase: Float, wavePhase: Float,
        edgeStrokePaint: Paint, edgeGlowPaint: Paint, edgeWavePaint: Paint,
        edgeXs: FloatArray, edgeYs: FloatArray, edgeIxs: FloatArray, edgeIys: FloatArray,
        edgeWavePath: Path, waveN: Int
    ) {
        val inset = edgeGlowPaint.strokeWidth / 2f
        val a = width / 2f - inset
        val b = height / 2f - inset
        if (a <= 1f || b <= 1f) return
        val ex = 2.0 / 6.0

        val amp = baseThickness * (0.35f + 2.4f * (windLevel + gust))
        val tremor = baseThickness * 0.90f * vibrationScale * windLevel * sin(tremorPhase.toDouble()).toFloat()

        var i = 0
        while (i < waveN) {
            val u = i.toFloat() / waveN
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
            val rise = amp * (0.55f + 0.45f * (s1 * s1 * s1)) * (0.60f + 0.40f * (s2 * s2 * s2))

            edgeIxs[i] = px - nx * (rise + tremor)
            edgeIys[i] = py - ny * (rise + tremor)
            i++
        }

        edgeWavePath.reset()
        edgeWavePath.moveTo(edgeXs[0], edgeYs[0])
        i = 1
        while (i < waveN) { edgeWavePath.lineTo(edgeXs[i], edgeYs[i]); i++ }
        i = waveN - 1
        while (i >= 0) { edgeWavePath.lineTo(edgeIxs[i], edgeIys[i]); i-- }
        edgeWavePath.close()
        canvas.drawPath(edgeWavePath, edgeWavePaint)

        val edgeLine = Path()
        edgeLine.moveTo(edgeXs[0], edgeYs[0])
        i = 1
        while (i < waveN) { edgeLine.lineTo(edgeXs[i], edgeYs[i]); i++ }
        edgeLine.close()
        canvas.drawPath(edgeLine, edgeGlowPaint)
        canvas.drawPath(edgeLine, edgeStrokePaint)
    }
}
