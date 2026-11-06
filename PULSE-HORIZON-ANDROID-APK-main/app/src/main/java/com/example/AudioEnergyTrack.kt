package com.example

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * REAL-TIME ENERGY TRACK  (exact, not predicted)
 * ------------------------------------------------------------
 * The old BeatTimeline GUESSED a tempo and then fired "waves" on the guessed
 * grid, so anything off-grid (fills, syncopation, tempo changes, soft intros)
 * was wrong. This does no guessing: for every 512-frame hop of the decoded
 * PCM it measures the real bass / mid / treble energy and stores it against
 * its frame position. The UI then asks "what is the energy at the frame the
 * speaker is playing RIGHT NOW?" - so the glow is locked to the audio.
 *
 * Written by the decoder thread, read by the UI thread. Lock-free: a ring of
 * floats plus one volatile counter.
 */
class AudioEnergyTrack(private val channels: Int, sampleRate: Int) {

    private val n = 1024
    private val hop = 512
    private val bins = n / 2
    private val cap = 4096                     // ~47 s of history, playhead is <1 s behind

    private val ring = FloatArray(cap)
    @Volatile private var hopsWritten = 0L

    private val win = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val twC = FloatArray(n / 2) { cos(-2.0 * PI * it / n).toFloat() }
    private val twS = FloatArray(n / 2) { sin(-2.0 * PI * it / n).toFloat() }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    private val pending = FloatArray(n)
    private var pendingCount = 0

    // band edges in bins
    private val hz = sampleRate.toFloat() / n
    private val bassEnd = max(3, (160f / hz).toInt())
    private val midEnd = max(bassEnd + 1, (2500f / hz).toInt())
    private val trebEnd = min(bins, max(midEnd + 1, (9000f / hz).toInt()))

    // Short-term (~1 s) adaptive range per band, so the glow always shows the
    // LOCAL dynamics: beats pop, gaps between beats dip - even in loud, dense music.
    private val floor = FloatArray(3)
    private val peak = FloatArray(3) { 1.5f }
    private val prevV = FloatArray(3)
    private val fluxPeak = FloatArray(3) { 0.3f }

    /** PCM16 little-endian, interleaved, exactly what was written to the AudioTrack. */
    fun feed(pcm: ByteArray, offset: Int, length: Int) {
        val ch = if (channels < 1) 1 else channels
        val frameBytes = ch * 2
        var i = offset
        val end = offset + length
        while (i + frameBytes <= end) {
            var sum = 0f
            var c = 0
            while (c < ch) {
                val lo = pcm[i + c * 2].toInt() and 0xFF
                val hi = pcm[i + c * 2 + 1].toInt()
                sum += ((hi shl 8) or lo).toShort() / 32768f
                c++
            }
            pending[pendingCount++] = sum / ch
            i += frameBytes
            if (pendingCount == n) {
                analyse()
                System.arraycopy(pending, hop, pending, 0, n - hop)
                pendingCount = n - hop
            }
        }
    }

    private fun analyse() {
        for (k in 0 until n) { re[rev[k]] = pending[k] * win[k] }
        java.util.Arrays.fill(im, 0f)
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var s = 0
            while (s < n) {
                var k = 0
                var t = 0
                while (k < half) {
                    val a = s + k
                    val b = a + half
                    val tr = re[b] * twC[t] - im[b] * twS[t]
                    val ti = re[b] * twS[t] + im[b] * twC[t]
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    k++; t += step
                }
                s += size
            }
            size *= 2
        }
        val scale = 4f / n   // Hann-window amplitude correction
        val e = FloatArray(3)
        band(1, bassEnd, scale, e, 0)
        band(bassEnd, midEnd, scale, e, 1)
        band(midEnd, trebEnd, scale, e, 2)

        var lvl = 0f
        val w = floatArrayOf(0.55f, 0.30f, 0.15f)
        for (b in 0 until 3) {
            val v = ln(1f + e[b] * 250f)                       // log loudness
            // floor: drops instantly, rises slowly (~3 s)
            floor[b] += (v - floor[b]) * (if (v < floor[b]) 1f else 0.004f)
            // peak: rises instantly, relaxes toward the floor (~1 s)
            peak[b] = max(v, peak[b] - (peak[b] - floor[b]) * 0.012f)
            val range = max(peak[b] - floor[b], 0.7f)          // never amplify silence
            val env = ((v - floor[b]) / range).coerceIn(0f, 1f)
            // onset (spectral flux): the attack of a kick / snare / hit
            val flux = max(0f, v - prevV[b])
            prevV[b] = v
            fluxPeak[b] = max(flux, fluxPeak[b] * 0.985f).coerceAtLeast(0.15f)
            val onset = (flux / fluxPeak[b]).coerceIn(0f, 1f)
            lvl += w[b] * min(1f, env.pow(1.6f) + 0.45f * onset)
        }
        val out = lvl.coerceIn(0f, 1f)
        ring[(hopsWritten % cap).toInt()] = out
        hopsWritten++
    }

    private fun band(from: Int, to: Int, scale: Float, out: FloatArray, idx: Int) {
        var s = 0f
        for (k in from until to) {
            val m = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
            s += m * m
        }
        out[idx] = sqrt(s / max(1, to - from))
    }

    /** Energy 0..1 at an exact playback position, in frames. */
    fun levelAt(framePos: Long): Float {
        val written = hopsWritten
        if (written <= 1L || framePos < 0L) return 0f
        val exact = (framePos - n / 2).toFloat() / hop
        if (exact < 0f) return 0f
        val h0 = exact.toLong()
        if (h0 >= written - 1) return ring[((written - 1) % cap).toInt()]
        if (h0 < written - cap + 1) return 0f
        val f = exact - h0
        val a = ring[(h0 % cap).toInt()]
        val b = ring[((h0 + 1) % cap).toInt()]
        return a + (b - a) * f
    }
}
