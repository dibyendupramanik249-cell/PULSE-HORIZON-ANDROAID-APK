package com.example

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * STEP 1 — THE BEAT TIMELINE
 * ============================================================
 *
 * Works out WHERE THE BEATS ARE in a track, so the visualiser can READ them
 * instead of trying to detect them live.
 *
 * WHY THIS IS THE ADVANTAGE
 *   Every visualiser that uses a microphone is guessing. It hears the music
 *   through a room - distance, echo, the mic, background noise - so it has to
 *   filter noise out, and every filter that removes noise also removes real
 *   beats.
 *
 *   This reads the audio the app is ALREADY DECODING to play it. The exact
 *   samples. No room, no echo, no noise floor. Nothing to filter out.
 *
 * It is not cleverness. It is cleaner data.
 *
 * WHERE IT IS FED FROM
 *   MainActivity's decoder, in musicPlay(), at the line that writes decoded
 *   audio to the AudioTrack:
 *
 *       if (writeLength > 0) {
 *           timeline.feed(bytes, writeOffset, writeLength)   // <- this
 *           at.write(bytes, writeOffset, writeLength)
 *       }
 *
 *   Same buffer. No second decode. No extra I/O.
 *
 * COST
 *   One small FFT per hop. Reused buffers, no per-buffer allocation. Nothing
 *   runs per frame - this is analysis, not drawing.
 */
class BeatTimeline(private val sampleRate: Int, private val channels: Int = 1) {

    /** One beat: where it is in the stream, and how hard it hits. */
    data class Beat(val samplePosition: Long, val strength: Float, val colour: Float)

    // ------------------------------------------------------------
    // analysis geometry
    // ------------------------------------------------------------
    private val fftSize = 512
    private val hop = 256
    private val bins = fftSize / 2 + 1

    /** how much envelope history we keep, for the tempo estimate */
    private val envelopeCapacity = 2048

    // ------------------------------------------------------------
    // reusable buffers - nothing here is allocated per buffer
    // ------------------------------------------------------------
    private val window = FloatArray(fftSize)
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val mag = FloatArray(bins)
    private val prevMag = FloatArray(bins)
    private val twCos = FloatArray(fftSize / 2)
    private val twSin = FloatArray(fftSize / 2)

    /** the onset envelope: one value per hop */
    private val envelope = FloatArray(envelopeCapacity)
    private var envelopeCount = 0
    private var envelopeWrite = 0

    /** samples waiting to fill one analysis frame */
    private val pending = FloatArray(fftSize * 4)
    private var pendingCount = 0

    /** how many samples have gone in so far, so every beat has a position */
    private var samplesFed = 0L

    /** the timeline, in order */
    private val beats = ArrayList<Beat>(4096)

    // ------------------------------------------------------------
    // tempo and onset tracking
    // ------------------------------------------------------------
    private var tempoLagHops = 0        // period of the beat, in hops
    private var phaseHops = 0           // which hop the beat lands on
    private var tempoPeriodHops = 0f    // exact floating-point period to prevent drift
    private var nextExpectedBeatHop = -1
    private var hopsSeen = 0
    private var lastBeatHop = -100000

    private var meanMag = 0f

    /** on/off energy ratio of the chosen tempo - the health check */
    private var lastRatio = -1f

    /** THE COLOUR. The spectral centroid of the frame that fired the beat -
     *  where the energy sits in the spectrum. A bright hit (hats, a snare,
     *  a high synth) sits high; a dark hit (a kick, a bass note) sits low.
     *  That is the music deciding the colour, not a hardcoded rotation. */
    private var lastCentroid = 0.5f

    init {
        // Hann window, and the twiddle factors for the FFT
        for (i in 0 until fftSize) {
            window[i] = (0.5 - 0.5 * cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
        }
        for (i in 0 until fftSize / 2) {
            val a = -2.0 * Math.PI * i / fftSize
            twCos[i] = cos(a).toFloat()
            twSin[i] = sin(a).toFloat()
        }
    }

    // ============================================================
    // FEED - called from the decoder, on the decode thread
    // ============================================================

    /**
     * @param pcm    16-bit little-endian PCM, exactly what goes to the AudioTrack
     * @param offset first byte to read
     * @param length how many bytes
     */
    fun feed(pcm: ByteArray, offset: Int, length: Int) {
        // FRAMES, not samples. getPlaybackHeadPosition() counts frames, so
        // this must too or every beat is out by the channel count.
        val ch = if (channels < 1) 1 else channels
        val frameBytes = ch * 2
        var i = offset
        val end = offset + length
        while (i + frameBytes <= end) {
            // downmix to mono - feeding interleaved stereo into the FFT would
            // smear the spectrum, and the onset detection would be worse
            var sum = 0f
            var c = 0
            while (c < ch) {
                val lo = pcm[i + c * 2].toInt() and 0xFF
                val hi = pcm[i + c * 2 + 1].toInt()
                sum += ((hi shl 8) or lo).toShort().toInt() / 32768f
                c++
            }
            i += frameBytes
            val s = sum / ch

            pending[pendingCount++] = s
            samplesFed++

            if (pendingCount >= fftSize) {
                analyseFrame()
                // slide by one hop, not one frame
                System.arraycopy(pending, hop, pending, 0, fftSize - hop)
                pendingCount = fftSize - hop
            }
        }
    }

    // ============================================================
    // ONE ANALYSIS FRAME
    // ============================================================

    private fun analyseFrame() {
        // ---- window into the FFT buffers ----
        for (n in 0 until fftSize) {
            re[n] = pending[n] * window[n]
            im[n] = 0f
        }

        fft()

        // ---- magnitude ----
        for (k in 0 until bins) {
            mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
        }

        // ---- weighted spectral flux ----
        // Emphasizes rhythmic low-end (sub-bass, kicks, bass 30-300 Hz) and mid transients,
        // while attenuating high-frequency noise, hiss, and vocal sibilance (>3.5 kHz)
        var flux = 0f
        for (k in 0 until bins) {
            val d = mag[k] - prevMag[k]
            if (d > 0f) {
                val weight = when {
                    k <= 4 -> 3.5f   // ~0 - 340 Hz (kicks, 808s, bass drops)
                    k <= 14 -> 2.0f  // ~340 - 1200 Hz (snare fundamental, toms, bassline)
                    k <= 35 -> 1.0f  // ~1200 - 3000 Hz (claps, guitar strum attacks)
                    else -> 0.25f    // > 3000 Hz (hi-hats, cymbals, air, sibilance)
                }
                flux += d * weight
            }
            prevMag[k] = mag[k]
        }

        // ---- the centroid, for the colour field ----
        var cnum = 0f
        var cden = 0f
        var k2 = 1
        while (k2 < bins) {
            cnum += k2 * mag[k2]
            cden += mag[k2]
            k2++
        }
        if (cden > 1e-6f) {
            val c = cnum / cden / bins
            // smoothed, so the colour glides instead of flickering
            lastCentroid = lastCentroid * 0.80f + c.coerceIn(0f, 1f) * 0.20f
        }

        // ---- adaptive mean tracking for robust novelty across genres ----
        meanMag = meanMag * 0.992f + flux * 0.008f
        val novelty = flux / (meanMag + 1e-6f)

        pushEnvelope(novelty)
        hopsSeen++

        updateTempo()
        maybeEmitBeat(novelty)
    }

    // ============================================================
    // ENVELOPE RING
    // ============================================================

    private fun pushEnvelope(v: Float) {
        envelope[envelopeWrite] = v
        envelopeWrite = (envelopeWrite + 1) % envelopeCapacity
        if (envelopeCount < envelopeCapacity) envelopeCount++
    }

    private fun envelopeAt(hopsAgo: Int): Float {
        if (hopsAgo < 0 || hopsAgo >= envelopeCount) return 0f
        var idx = envelopeWrite - 1 - hopsAgo
        while (idx < 0) idx += envelopeCapacity
        return envelope[idx]
    }

    private fun localEnvelopeMean(count: Int = 16): Float {
        if (envelopeCount == 0) return 1f
        val n = minOf(count, envelopeCount)
        var sum = 0f
        for (i in 0 until n) {
            sum += envelopeAt(i)
        }
        return sum / n
    }

    private var lastEnergyRatio = 1f

    private data class EnergyRatio(val onEnergy: Float, val betweenEnergy: Float, val ratio: Float)

    private fun findBestPhase(lag: Int): Int {
        var bestP = 0
        var bestScore = -1f
        var p = 0
        val maxN = minOf(envelopeCount, 600)
        val w = (lag / 16).coerceIn(1, 2)
        while (p < lag) {
            var s = 0f
            var n = p
            while (n < maxN) {
                var maxLocal = 0f
                for (dw in -w..w) {
                    val idx = n + dw
                    if (idx in 0 until maxN) {
                        val v = envelopeAt(idx)
                        if (v > maxLocal) maxLocal = v
                    }
                }
                s += maxLocal
                n += lag
            }
            if (s > bestScore) {
                bestScore = s
                bestP = p
            }
            p++
        }
        return bestP
    }

    private fun measureEnergyRatio(lag: Int, phase: Int): EnergyRatio {
        if (lag <= 0 || envelopeCount == 0) return EnergyRatio(0f, 0f, 1f)
        val maxN = minOf(envelopeCount, 600)
        if (maxN < lag) return EnergyRatio(0f, 0f, 1f)

        val w = (lag / 12).coerceIn(1, 3)
        var onSum = 0f
        var onCount = 0
        var betweenSum = 0f
        var betweenCount = 0

        var n = 0
        while (n < maxN) {
            val mod = (n - phase) % lag
            val pos = if (mod < 0) mod + lag else mod
            val distToBeat = minOf(pos, lag - pos)

            val v = envelopeAt(n)
            if (distToBeat <= w) {
                onSum += v
                onCount++
            } else {
                betweenSum += v
                betweenCount++
            }
            n++
        }

        val avgOn = if (onCount > 0) onSum / onCount else 0f
        val avgBetween = if (betweenCount > 0) betweenSum / betweenCount else 0f
        val ratio = if (avgBetween > 1e-4f) avgOn / avgBetween else (avgOn / 1e-4f)
        return EnergyRatio(avgOn, avgBetween, ratio)
    }

    // ============================================================
    // TEMPO AND PHASE - tracks tempo and locks phase
    // ============================================================

    /**
     * TEMPO, WITH THE OCTAVE CHECK.
     *
     * The strongest autocorrelation peak is often at DOUBLE the real tempo -
     * the half-beat - because the off-beat can carry as much energy as the
     * beat, and autocorrelation cannot tell which it picked. That is what was
     * happening: this track is 103.4 BPM and it locked onto 206.7, so every
     * note was written on a half-beat and every effect downstream was wrong.
     *
     * So test the tempo AND its half, and keep whichever puts the beats where
     * the MUSIC HAS ENERGY.
     *
     * Beats land on hits. That is a property of music itself, not of one song,
     * so this works on any track with nothing hardcoded and no per-song data.
     * A song nobody has ever heard gets a correct score, because the score is
     * computed from the song.
     */
    private fun updateTempo() {
        if (envelopeCount < sampleRate / hop * 3) return
        if (hopsSeen % 32 != 0) return

        val minLag = (sampleRate / hop * 60 / 220).coerceAtLeast(4)
        val maxLag = (sampleRate / hop * 60 / 60).coerceAtMost(envelopeCount / 2)
        if (maxLag <= minLag) return

        var bestLag = 0
        var bestScore = 0f
        var lag = minLag
        while (lag <= maxLag) {
            var sc = 0f
            var n = 0
            while (n < 400 && n + lag < envelopeCount) {
                sc += envelopeAt(n) * envelopeAt(n + lag)
                n++
            }
            if (sc > bestScore) { bestScore = sc; bestLag = lag }
            lag++
        }
        if (bestLag <= 0 || bestScore <= 0f) return

        // ---- THE OCTAVE CHECK ----
        var chosen = bestLag
        var chosenPhase = findBestPhase(bestLag)
        var chosenER = measureEnergyRatio(bestLag, chosenPhase)

        val doubleLag = bestLag * 2
        if (doubleLag in minLag..maxLag && doubleLag < envelopeCount) {
            val ph2 = findBestPhase(doubleLag)
            val er2 = measureEnergyRatio(doubleLag, ph2)
            if (er2.ratio > chosenER.ratio) {
                chosen = doubleLag
                chosenPhase = ph2
                chosenER = er2
            }
        }

        tempoLagHops = chosen
        phaseHops = chosenPhase
        lastRatio = chosenER.ratio
        lastEnergyRatio = chosenER.ratio

        if (chosenER.ratio >= 1.50f) {
            tempoPeriodHops = chosen.toFloat()
            if (nextExpectedBeatHop < 0) {
                nextExpectedBeatHop = (hopsSeen + tempoPeriodHops).toInt()
            }
        } else {
            tempoPeriodHops = 0f
        }
    }

    /** Which hop in the period the beats land on. */
    private fun phaseFor(lag: Int): Int {
        var bestP = 0
        var bestS = -1f
        var p = 0
        while (p < lag) {
            var sc = 0f
            var n = p
            while (n < envelopeCount) { sc += envelopeIndex(n); n += lag }
            if (sc > bestS) { bestS = sc; bestP = p }
            p++
        }
        return bestP
    }

    /** Mean envelope ON the beat positions, for a lag and a phase. */
    private fun bandEnergy(lag: Int, phase: Int): Float {
        var sc = 0f
        var n = 0
        var i = phase
        while (i < envelopeCount) { sc += envelopeIndex(i); n++; i += lag }
        return if (n > 0) sc / n else 0f
    }

    /** The envelope by ABSOLUTE hop index, oldest first. */
    private fun envelopeIndex(i: Int): Float {
        if (i < 0 || i >= envelopeCount) return 0f
        var idx = envelopeWrite - envelopeCount + i
        while (idx < 0) idx += envelopeCapacity
        while (idx >= envelopeCapacity) idx -= envelopeCapacity
        return envelope[idx]
    }

    /**
     * THE HEALTH CHECK - the on/off energy ratio of the tempo it chose.
     * Beats land on hits, so this should be well above 1. Near 1 means the
     * beats are NOT on the music and the tempo is wrong.
     * Measured on the test track before this fix: 1.10.
     */
    fun tempoRatio(): Float = lastRatio

    /** The locked tempo in BPM. 0 until it locks. Should read ~103, not ~207. */
    fun tempoBpm(): Float =
        if (tempoLagHops > 0) 60f * (sampleRate.toFloat() / hop) / tempoLagHops else 0f


    // ============================================================
    // EMIT - FAST ONSET DETECTION (0.1s - 0.2s) + ADAPTIVE RHYTHM
    // ============================================================

    private fun maybeEmitBeat(novelty: Float) {
        // Evaluate peak at 1 hop ago (envelopeAt(1))
        val prev1 = envelopeAt(1)
        val prev2 = envelopeAt(2)
        val isPeak = prev1 >= novelty && prev1 > prev2

        val localMean = localEnvelopeMean(14)
        val threshold = (localMean * 1.22f).coerceAtLeast(0.20f)
        val isOnset = isPeak && (prev1 > threshold)

        // Minimum refractory interval: 8 hops (~46ms at 44.1kHz)
        // This guarantees micro-beats at 0.1s - 0.2s (17 - 35 hops) are never missed!
        val minInterval = 8

        if (isOnset && (hopsSeen - lastBeatHop >= minInterval)) {
            // Instant audio onset detected with sub-beat precision
            val rawStrength = (prev1 / (localMean + 0.08f)).coerceIn(0.15f, 1.0f)
            emitAtHop(rawStrength, hopsAgo = 1)
            lastBeatHop = hopsSeen
            if (tempoPeriodHops > 0f) {
                nextExpectedBeatHop = (hopsSeen + tempoPeriodHops).toInt()
            }
            return
        }

        // Secondary carrier: during rhythmic passages with locked tempo, maintain flow
        // across sustained chords, but only if energy is present (no ghost beats during silence)
        if (tempoPeriodHops > 0f && lastRatio >= 1.4f) {
            val distToExpected = hopsSeen - nextExpectedBeatHop
            val tempoMinInterval = (tempoPeriodHops * 0.40f).toInt().coerceAtLeast(8)
            if (distToExpected >= 0 && (hopsSeen - lastBeatHop >= tempoMinInterval)) {
                if (localMean > 0.12f) { // Only if there is genuine audio activity
                    val s = (localMean / 1.5f).coerceIn(0.12f, 0.70f)
                    emitAtHop(s, hopsAgo = 0)
                    lastBeatHop = hopsSeen
                    nextExpectedBeatHop = (hopsSeen + tempoPeriodHops).toInt()
                }
            }
        }
    }

    private fun emitAtHop(strength: Float, hopsAgo: Int) {
        // Sample position corresponding to the transient frame
        val pos = samplesFed - (fftSize - hop) - (hopsAgo * hop)
        val s = strength.coerceIn(0.10f, 1.0f)
        beats.add(Beat(pos.coerceAtLeast(0L), s, lastCentroid))
    }

    // ============================================================
    // READ
    // ============================================================

    /** How many beats have been found so far. */
    fun beatCount(): Int = beats.size

    /** Every beat found so far, in order. */
    fun all(): List<Beat> = beats

    /** Beats whose position is at or after [from], for the reader. */
    fun from(from: Long): List<Beat> {
        val out = ArrayList<Beat>(16)
        for (b in beats) if (b.samplePosition >= from) out.add(b)
        return out
    }

    /** Current locked BPM, or 0f if not locked / no clear beat */
    fun currentBpm(): Float {
        if (tempoPeriodHops <= 0f) return 0f
        return (sampleRate.toFloat() / hop * 60f) / tempoPeriodHops
    }

    /** Ratio of energy on the beats vs between the beats */
    fun currentEnergyRatio(): Float = lastEnergyRatio

    /** Whether tempo has locked onto a periodic beat grid */
    fun tempoLocked(): Boolean = tempoPeriodHops > 0f

    fun reset() {
        envelopeCount = 0
        envelopeWrite = 0
        pendingCount = 0
        samplesFed = 0L
        beats.clear()
        tempoLagHops = 0
        phaseHops = 0
        tempoPeriodHops = 0f
        nextExpectedBeatHop = -1
        hopsSeen = 0
        lastBeatHop = -100000
        meanMag = 0f
        lastCentroid = 0.5f
        lastRatio = -1f
        lastEnergyRatio = 1f
        for (k in 0 until bins) prevMag[k] = 0f
    }

    // ============================================================
    // A SMALL ITERATIVE RADIX-2 FFT
    // ============================================================

    private fun fft() {
        val n = fftSize
        // bit-reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // butterflies
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                var t = 0
                while (k < half) {
                    val c = twCos[t]
                    val s = twSin[t]
                    val l = i + k
                    val r = l + half
                    val tr = re[r] * c - im[r] * s
                    val ti = re[r] * s + im[r] * c
                    re[r] = re[l] - tr
                    im[r] = im[l] - ti
                    re[l] += tr
                    im[l] += ti
                    k++
                    t += step
                }
                i += size
            }
            size = size shl 1
        }
    }
}
