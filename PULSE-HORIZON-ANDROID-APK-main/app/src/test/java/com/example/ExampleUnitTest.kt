package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testWavePeaksOnBeat() {
    val launcher = WaveLauncher(44100)
    val beats = listOf(BeatTimeline.Beat(samplePosition = 1000L, strength = 0.8f, colour = 0.5f))
    launcher.setTimeline(beats)

    // Before beat reaches playback head
    launcher.advance(head = 500L, dt = 0.016f)
    assertEquals(0f, launcher.level(), 0.001f)

    // Beat reached playback head: wave launches right on the beat
    launcher.advance(head = 1000L, dt = 0.016f)
    val peakLevel = launcher.level()
    assertTrue("Wave must be at peak height on beat arrival, got $peakLevel", peakLevel > 0.5f)

    // Next frame: wave decays monotonically
    launcher.advance(head = 1000L + 705L, dt = 0.016f)
    val decayedLevel = launcher.level()
    assertTrue("Wave must decay after beat hit, got $decayedLevel vs $peakLevel", decayedLevel < peakLevel)
  }

  @Test
  fun testBeatTimelineDetectsBeatsFromPCM() {
    val sampleRate = 44100
    val channels = 1
    val timeline = BeatTimeline(sampleRate, channels)

    // Generate 3 seconds of PCM audio with distinct 120 BPM drum pulses (every 0.5s = 22050 frames)
    val durationSec = 3.5f
    val totalSamples = (durationSec * sampleRate).toInt()
    val pcmBytes = ByteArray(totalSamples * 2)

    val beatInterval = (sampleRate * 0.5).toInt()
    for (i in 0 until totalSamples) {
      val posInBeat = i % beatInterval
      val sampleVal: Short = if (posInBeat < 2000) {
        // Low-frequency sine burst representing kick (60 Hz)
        val t = posInBeat.toDouble() / sampleRate
        val env = 1.0 - (posInBeat.toDouble() / 2000.0)
        (Math.sin(2.0 * Math.PI * 60.0 * t) * 28000.0 * env).toInt().toShort()
      } else {
        0
      }
      val byteIdx = i * 2
      pcmBytes[byteIdx] = (sampleVal.toInt() and 0xFF).toByte()
      pcmBytes[byteIdx + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
    }

    // Feed in realistic buffer chunks (e.g. 4096 bytes)
    val chunkSize = 4096
    var offset = 0
    while (offset < pcmBytes.size) {
      val len = minOf(chunkSize, pcmBytes.size - offset)
      timeline.feed(pcmBytes, offset, len)
      offset += len
    }

    val beats = timeline.all()
    assertTrue("Timeline should detect beats from decoded PCM, found ${beats.size}", beats.isNotEmpty())
    // Verify detected beats carry positive strength and realistic sample position
    for (b in beats) {
      assertTrue("Beat strength must be > 0, got ${b.strength}", b.strength > 0f)
      assertTrue("Beat position must be non-negative, got ${b.samplePosition}", b.samplePosition >= 0)
    }
  }

  @Test
  fun testTempoLocksToTrueBpmNotDouble() {
    // Test track simulation: 103.4 BPM (beat every 0.580s) with 8th-note off-beats (every 0.290s)
    // The previous bug locked onto 206.7 BPM (2x tempo) with on/off ratio around 1.10.
    // The fix must verify energy ratio and lock onto ~103.4 BPM with ratio > 1.50.
    val sampleRate = 44100
    val timeline = BeatTimeline(sampleRate, 1)

    val durationSec = 6.0f
    val totalSamples = (durationSec * sampleRate).toInt()
    val pcmBytes = ByteArray(totalSamples * 2)

    val beatInterval = (sampleRate * 0.58027).toInt() // 103.4 BPM
    val halfInterval = beatInterval / 2

    for (i in 0 until totalSamples) {
      val posInBeat = i % beatInterval
      val posInHalf = i % halfInterval

      var sampleVal = 0.0
      // Beat 1 & 2: Primary kick / snare hit with heavy low-end (65 Hz)
      if (posInBeat < 2500) {
        val t = posInBeat.toDouble() / sampleRate
        val env = 1.0 - (posInBeat.toDouble() / 2500.0)
        sampleVal += Math.sin(2.0 * Math.PI * 65.0 * t) * 28000.0 * env
      }
      // 8th-note off-beat: mid-high frequency syncopated accent (hi-hat/synth at 2200 Hz), lower energy
      if (posInHalf < 1200 && posInBeat >= 2500) {
        val t = posInHalf.toDouble() / sampleRate
        val env = 1.0 - (posInHalf.toDouble() / 1200.0)
        sampleVal += Math.sin(2.0 * Math.PI * 2200.0 * t) * 9000.0 * env
      }

      val s = sampleVal.coerceIn(-32767.0, 32767.0).toInt().toShort()
      val byteIdx = i * 2
      pcmBytes[byteIdx] = (s.toInt() and 0xFF).toByte()
      pcmBytes[byteIdx + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
    }

    val chunkSize = 4096
    var offset = 0
    while (offset < pcmBytes.size) {
      val len = minOf(chunkSize, pcmBytes.size - offset)
      timeline.feed(pcmBytes, offset, len)
      offset += len
    }

    val bpm = timeline.currentBpm()
    val ratio = timeline.currentEnergyRatio()
    println("DIAGNOSTIC testTempoLocksToTrueBpmNotDouble: bpm=$bpm, ratio=$ratio")

    // Must lock to ~103 BPM, NOT ~207 BPM
    assertTrue("BPM must be around 103 BPM, got $bpm", bpm in 95f..115f)
    assertTrue("On/off energy ratio must be well above 1.10 (aiming > 1.50), got $ratio", ratio >= 1.50f)
  }

  @Test
  fun testAmbientTrackDoesNotInventBeat() {
    // Ambient music simulation: smooth slow-varying sine tones without percussive onsets
    val sampleRate = 44100
    val timeline = BeatTimeline(sampleRate, 1)

    val durationSec = 4.0f
    val totalSamples = (durationSec * sampleRate).toInt()
    val pcmBytes = ByteArray(totalSamples * 2)

    for (i in 0 until totalSamples) {
      val t = i.toDouble() / sampleRate
      // Gentle 220 Hz + 440 Hz pad with slow 0.5 Hz swell
      val swell = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * 0.5 * t)
      val sampleVal = (Math.sin(2.0 * Math.PI * 220.0 * t) + Math.sin(2.0 * Math.PI * 440.0 * t)) * 8000.0 * swell
      val s = sampleVal.coerceIn(-32767.0, 32767.0).toInt().toShort()
      val byteIdx = i * 2
      pcmBytes[byteIdx] = (s.toInt() and 0xFF).toByte()
      pcmBytes[byteIdx + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
    }

    val chunkSize = 4096
    var offset = 0
    while (offset < pcmBytes.size) {
      val len = minOf(chunkSize, pcmBytes.size - offset)
      timeline.feed(pcmBytes, offset, len)
      offset += len
    }

    val bpm = timeline.currentBpm()
    val ratio = timeline.currentEnergyRatio()
    println("DIAGNOSTIC testAmbientTrackDoesNotInventBeat: bpm=$bpm, ratio=$ratio, locked=${timeline.tempoLocked()}")

    // Must NOT invent a phantom beat: tempoLocked must be false
    assertFalse("Ambient track should not lock onto a phantom beat (bpm=$bpm, ratio=$ratio)", timeline.tempoLocked())
  }

  @Test
  fun testDiskVisualizerViewModeEnum() {
    // Mode 1: Disk
    val mode1 = DiskVisualizerView.Mode.DISK
    assertEquals("DISK", mode1.name)

    // Mode 2: Disk + Edge
    val mode2 = DiskVisualizerView.Mode.DISK_AND_EDGE
    assertEquals("DISK_AND_EDGE", mode2.name)
  }
}
