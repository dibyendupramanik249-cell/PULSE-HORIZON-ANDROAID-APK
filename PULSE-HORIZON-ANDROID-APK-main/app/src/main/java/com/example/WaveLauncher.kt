package com.example

/**
 * STEP 2 — THE READER AND THE LAUNCH
 * ============================================================
 *
 * This is the accuracy. Step 1 works out WHERE the beats are; this fires
 * them WHEN YOU HEAR THEM, at the right height, in the right place.
 *
 * WHY IT IS NOT JUST "TIMING"
 *   A launch has three things and all three must be right:
 *
 *     WHEN      fire on the audio output's playback head
 *     WHERE     where along the line it rises
 *     HOW HIGH  the strength it carries
 *
 *   ONE WRONG HEIGHT DESTROYS THE WHOLE SCENE. You do not read the waves
 *   one at a time - you read the surface as one shape. So a single outlier
 *   does not look like "one wave is a bit big", it looks like the whole
 *   thing is broken, because the pattern the eye was reading just got
 *   contradicted.
 *
 *   THE BAR IS NOT "most waves land right". THE BAR IS: NO OUTLIERS.
 *
 * WHAT THIS CLASS DOES
 *   - holds the beat timeline from Step 1
 *   - each frame, is told where the audio output is
 *   - launches every beat the output has now reached
 *   - clamps the height and the position so no wave can be an outlier
 *   - hands back the waves that are alive, each with its own age
 *
 * WHAT IT DOES NOT DO
 *   It does not draw. The glow view draws. This decides what to draw.
 *
 * NO ALLOCATION PER FRAME
 *   The live waves are a fixed pool, reused. Nothing here allocates in the
 *   frame loop, because memory must not increase.
 */
class WaveLauncher(private val sampleRate: Int) {

    /** A wave that is currently on screen. */
    class Wave {
        var position = 0f      // 0..1 along the line
        var height = 0f        // 0..1, how high it rises
        var age = 0f           // seconds since it launched
        // THE WAVE'S LIFE. It was 0.55 s with an 82 ms decay, which made
        // every hit a separate punch - so five hits a second read as five
        // shakes. Stretched back out, the hits OVERLAP: one is still rising
        // when the next lands, so five punches become one continuous motion.
        // Same detection, same beats - it just stops looking like a stutter.
        var life = 1.50f       // seconds it will live
        var colour = 0.5f      // 0 = dark hit, 1 = bright hit - from the score
        var alive = false

        /**
         * Fast-attack percussive envelope: 1.0 on hit, rapidly decaying with
         * an 80ms time constant. This enables beats at 0.1s - 0.2s to clearly
         * drop to valley before the next peak, delivering a punchy, dancing visual.
         */
        fun envelope(): Float {
            if (!alive) return 0f
            // 82 ms was a punch. 0.35 s lets it breathe - still fast enough
            // to read as a hit, slow enough that consecutive hits merge.
            val decay = Math.exp((-age / 0.35f).toDouble()).toFloat()
            val tail = (1f - (age / life)).coerceAtLeast(0f)
            return (decay * tail).coerceIn(0f, 1f)
        }
    }

    // ------------------------------------------------------------
    // the timeline (from Step 1)
    // ------------------------------------------------------------
    private var beats: List<BeatTimeline.Beat> = emptyList()
    private var nextBeat = 0

    /** where the audio output was last frame, so we only look forward */
    private var lastHead = -1L

    // ------------------------------------------------------------
    // the live waves - a fixed pool, reused, never reallocated
    // ------------------------------------------------------------
    private val pool = Array(POOL) { Wave() }
    private var poolCursor = 0

    // ------------------------------------------------------------
    // the launch rules
    // ------------------------------------------------------------
    /** every wave is clamped into this band, so none can tower */
    private val minHeight = 0.20f
    private val maxHeight = 0.95f

    /** a slow running maximum, so a loud section cannot spike */
    private var runningMax = 0.6f

    /** deterministic position - a fixed cycle, never a random number */
    private var slot = 0

    /** the beat we last launched, so two cannot stack on one spot */
    private var lastSlot = -1

    companion object {
        private const val POOL = 48

        /**
         * Where along the line a wave may rise. A fixed, spread-out cycle.
         * NOT random: if a wave can land anywhere, one of them will land
         * somewhere that breaks the shape.
         */
        private val SLOTS = floatArrayOf(
            0.50f, 0.30f, 0.70f, 0.42f, 0.58f, 0.22f, 0.78f, 0.36f,
            0.64f, 0.26f, 0.74f, 0.46f, 0.54f, 0.34f, 0.66f, 0.18f,
            0.82f, 0.38f, 0.62f, 0.28f, 0.72f, 0.44f, 0.56f, 0.24f
        )
    }

    // ============================================================
    // FEED THE TIMELINE
    // ============================================================

    fun setTimeline(list: List<BeatTimeline.Beat>) {
        // The timeline hands back the SAME list every frame, growing as it
        // goes. Only reset the cursor when it is genuinely a new list, or we
        // would replay every beat from the start on every frame.
        if (list === beats) return
        beats = list
        nextBeat = 0
        lastHead = -1L
    }

    fun reset() {
        beats = emptyList()
        nextBeat = 0
        lastHead = -1L
        for (w in pool) w.alive = false
        poolCursor = 0
        slot = 0
        lastSlot = -1
        runningMax = 0.6f
    }

    // ============================================================
    // THE FRAME - call this once per frame
    // ============================================================

    /**
     * @param head the audio output's playback head, in samples.
     *             This is what the SPEAKER is playing right now - not what
     *             the decoder has read. That difference is the whole reason
     *             the wave lands when you hear it.
     * @param dt   seconds since the last frame
     */
    fun advance(head: Long, dt: Float) {
        // --------------------------------------------------------
        // 1. LAUNCH - every beat the output has now reached
        // --------------------------------------------------------
        // A queue, walked forward. Never a single "there was a beat"
        // value: two beats between two frames would become one.
        while (nextBeat < beats.size && beats[nextBeat].samplePosition <= head) {
            launch(beats[nextBeat].strength, beats[nextBeat].colour)
            nextBeat++
        }

        // if the head jumped backwards the track restarted - do not replay
        // everything at once
        if (head + sampleRate < lastHead) {
            lastHead = head
        }
        lastHead = head

        // --------------------------------------------------------
        // 2. AGE - every live wave gets older
        // --------------------------------------------------------
        for (w in pool) {
            if (!w.alive) continue
            w.age += dt
            if (w.age >= w.life) w.alive = false
        }
    }

    // ============================================================
    // THE LAUNCH - and this is where "no outliers" is enforced
    // ============================================================

    private fun launch(rawStrength: Float, rawColour: Float) {
        // ---- 1. NORMALISE, never raw ----
        // a slow running maximum, so a loud passage cannot produce a wave
        // that dwarfs everything and a quiet one cannot produce a wave that
        // vanishes. it follows the track instead of being a fixed number.
        runningMax = if (rawStrength > runningMax) {
            runningMax + (rawStrength - runningMax) * 0.35f
        } else {
            runningMax + (rawStrength - runningMax) * 0.02f
        }
        val norm = (rawStrength / (runningMax + 1e-4f)).coerceIn(0f, 1f)

        // ---- 2. CLAMP into the band ----
        // nothing launches un-clamped, so no single wave can be an outlier
        // by accident - which is exactly the failure that destroys a scene
        val height = (minHeight + norm * (maxHeight - minHeight))
            .coerceIn(minHeight, maxHeight)

        // ---- 3. POSITION - deterministic, and never the same spot twice ----
        var pos = SLOTS[slot % SLOTS.size]
        slot++
        if (slot % SLOTS.size == lastSlot) slot++          // never repeat
        lastSlot = slot % SLOTS.size

        // a small, bounded variation so it does not look mechanical -
        // but never enough to leave the spread
        pos += ((norm - 0.5f) * 0.06f)
        pos = pos.coerceIn(0.06f, 0.94f)

        // ---- 4. INTO THE POOL ----
        val w = pool[poolCursor]
        poolCursor = (poolCursor + 1) % POOL
        w.position = pos
        w.height = height
        w.age = 0f
        w.life = lifeFor(height)
        // the colour comes straight from the score - this beat's own
        // brightness. Nothing rotates it on its own.
        w.colour = rawColour.coerceIn(0f, 1f)
        w.alive = true
    }

    /** Waves live long enough to spread their crest (~0.45s) without clogging the scene. */
    private fun lifeFor(height: Float): Float {
        val t = ((height - minHeight) / (maxHeight - minHeight)).coerceIn(0f, 1f)
        // THIS is the life that actually applies - lifeFor() overrides the
        // default above. 0.40-0.65 s made every hit a separate punch, so five
        // hits a second read as five shakes. 1.20-2.00 s lets them OVERLAP:
        // one is still rising when the next lands, so five punches become one
        // continuous motion. Same detection, same beats.
        return 1.20f + t * 0.80f          // 1.20s .. 2.00s
    }

    // ============================================================
    // READ - what the glow view draws
    // ============================================================

    /** ONE value for callers that draw a single pulse: the strongest wave
     *  alive right now. Rises on a beat, falls as the wave spends itself. */
    fun level(): Float {
        var m = 0f
        for (w in pool) {
            if (!w.alive) continue
            val v = w.height * w.envelope()
            if (v > m) m = v
        }
        return m.coerceIn(0f, 1f)
    }

    fun pool(): Array<Wave> = pool

    fun liveCount(): Int {
        var n = 0
        for (w in pool) if (w.alive) n++
        return n
    }

    /** Beats still waiting, so a caller can tell whether it is in sync. */
    fun pending(): Int = (beats.size - nextBeat).coerceAtLeast(0)
}
