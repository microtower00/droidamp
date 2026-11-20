package com.droidamp.player.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.WinampEqSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
class WinampTenBandEqProcessorTest {
  @Test
  fun rejectsUnsupportedFormatsAndSupportsLiveBypassToggle() {
    val eq = WinampTenBandEqProcessor()
    assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
      eq.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_8BIT))
    }

    DspTestHelpers.configureAndFlush(eq, 44_100, 1, C.ENCODING_PCM_16BIT)
    assertTrue(eq.isActive)

    val bands = MutableList(WinampEqSpec.BAND_COUNT) { 0f }.apply { this[4] = 12f } // 1 kHz +12 dB
    val input = DspTestHelpers.generateSinePcm16(44_100, 1_000.0, 0.25, amplitude = 0.2)

    // Disabled => bit-exact bypass
    eq.updateEqualizerState(EqualizerState(enabled = false, preampDb = 6f, bandsDb = bands))
    val bypassed = DspTestHelpers.processPcm16(eq, input, chunkFrames = 512, channelCount = 1)
    assertArrayEquals(input, bypassed)

    // Enabled mid-stream => ~+12 dB boost at 1 kHz
    eq.updateEqualizerState(EqualizerState(enabled = true, preampDb = 0f, bandsDb = bands))
    val boosted = DspTestHelpers.processPcm16(eq, input, chunkFrames = 512, channelCount = 1)
    val dbDelta = DspTestHelpers.rmsDbChange(input, boosted, startSample = 4_410)
    assertEquals(12.0, dbDelta, 0.5)
  }

  @Test
  fun verifiesAllTenBandsBoostAndCutAndOutOfBandIsolation() {
    val sampleRate = 44_100
    val eq = WinampTenBandEqProcessor()
    DspTestHelpers.configureAndFlush(eq, sampleRate, 1, C.ENCODING_PCM_16BIT)

    for (bandIndex in 0 until WinampEqSpec.BAND_COUNT) {
      val freqHz = WinampEqSpec.BAND_FREQUENCIES_HZ[bandIndex].toDouble()
      val input = DspTestHelpers.generateSinePcm16(sampleRate, freqHz, 0.3, amplitude = 0.15)
      val settle = (sampleRate * 0.1).toInt()

      val boostBands = MutableList(WinampEqSpec.BAND_COUNT) { 0f }.apply { this[bandIndex] = 12f }
      eq.updateEqualizerState(EqualizerState(enabled = true, bandsDb = boostBands))
      eq.flush(AudioProcessor.StreamMetadata.DEFAULT)
      val boosted = DspTestHelpers.processPcm16(eq, input, chunkFrames = 512, channelCount = 1)
      val boostDb = DspTestHelpers.rmsDbChange(input, boosted, startSample = settle)
      assertEquals("Band $freqHz Hz +12 dB mismatch", 12.0, boostDb, 0.6)

      val cutBands = MutableList(WinampEqSpec.BAND_COUNT) { 0f }.apply { this[bandIndex] = -12f }
      eq.updateEqualizerState(EqualizerState(enabled = true, bandsDb = cutBands))
      eq.flush(AudioProcessor.StreamMetadata.DEFAULT)
      val cut = DspTestHelpers.processPcm16(eq, input, chunkFrames = 512, channelCount = 1)
      val cutDb = DspTestHelpers.rmsDbChange(input, cut, startSample = settle)
      assertEquals("Band $freqHz Hz -12 dB mismatch", -12.0, cutDb, 0.6)
    }

    // Out-of-band check: boost 60 Hz by +12 dB, feed 10 kHz -> near 0 dB change
    val bassOnly = MutableList(WinampEqSpec.BAND_COUNT) { 0f }.apply { this[0] = 12f }
    eq.updateEqualizerState(EqualizerState(enabled = true, bandsDb = bassOnly))
    eq.flush(AudioProcessor.StreamMetadata.DEFAULT)
    val highTone = DspTestHelpers.generateSinePcm16(sampleRate, 10_000.0, 0.25, amplitude = 0.2)
    val outHigh = DspTestHelpers.processPcm16(eq, highTone, chunkFrames = 512, channelCount = 1)
    assertEquals(0.0, DspTestHelpers.rmsDbChange(highTone, outHigh, startSample = 4_410), 0.3)
  }

  @Test
  fun enforcesNyquistGuardAtLowSampleRatesAndMaintainsChannelIsolation() {
    // 22050 Hz: 12k, 14k, 16k exceed 22050 * 0.49 = 10804.5 Hz and must safely bypass
    val eq22k = WinampTenBandEqProcessor()
    DspTestHelpers.configureAndFlush(eq22k, 22_050, 2, C.ENCODING_PCM_FLOAT)
    val highBoost = MutableList(WinampEqSpec.BAND_COUNT) { 0f }.apply {
      this[7] = 12f
      this[8] = 12f
      this[9] = 12f
    }
    eq22k.updateEqualizerState(EqualizerState(enabled = true, bandsDb = highBoost))

    val floatIn = DspTestHelpers.generateSineFloat(22_050, 1_000.0, 0.1, amplitude = 0.5, channelCount = 2)
    val floatOut = DspTestHelpers.processPcmFloat(eq22k, floatIn, chunkFrames = 256, channelCount = 2)
    for (s in floatOut) {
      assertTrue(s.isFinite())
      assertTrue(abs(s) <= 1.0f)
    }

    // Stereo isolation: L = 100 Hz, R = 6000 Hz, boost 6000 Hz (+12 dB)
    val eqStereo = WinampTenBandEqProcessor()
    DspTestHelpers.configureAndFlush(eqStereo, 44_100, 2, C.ENCODING_PCM_FLOAT)
    val boost6k = MutableList(WinampEqSpec.BAND_COUNT) { 0f }.apply { this[6] = 12f }
    eqStereo.updateEqualizerState(EqualizerState(enabled = true, bandsDb = boost6k))

    val frames = 13_230
    val stereoIn = FloatArray(frames * 2)
    for (f in 0 until frames) {
      stereoIn[f * 2] = (sin(2.0 * PI * 100.0 * f / 44_100.0) * 0.2).toFloat()
      stereoIn[f * 2 + 1] = (sin(2.0 * PI * 6000.0 * f / 44_100.0) * 0.2).toFloat()
    }
    val stereoOut = DspTestHelpers.processPcmFloat(eqStereo, stereoIn, chunkFrames = 512, channelCount = 2)
    val settle = 4_410
    val inLeftRms = DspTestHelpers.rmsFloat(stereoIn, settle, frames, 2, 0)
    val outLeftRms = DspTestHelpers.rmsFloat(stereoOut, settle, frames, 2, 0)
    val inRightRms = DspTestHelpers.rmsFloat(stereoIn, settle, frames, 2, 1)
    val outRightRms = DspTestHelpers.rmsFloat(stereoOut, settle, frames, 2, 1)

    assertEquals(1.0, outLeftRms / inLeftRms, 0.05) // Left untouched
    assertEquals(3.98, outRightRms / inRightRms, 0.25) // Right boosted ~+12 dB (3.98x)
  }

  @Test
  fun preampAppliesGainAndClampsPcm16WithoutWrapAround() {
    val eq = WinampTenBandEqProcessor()
    DspTestHelpers.configureAndFlush(eq, 44_100, 1, C.ENCODING_PCM_16BIT)
    eq.updateEqualizerState(EqualizerState(enabled = true, preampDb = 12f))

    val hot = shortArrayOf(20_000, -20_000, 1_000, -1_000)
    val out = DspTestHelpers.processPcm16(eq, hot, chunkFrames = 4, channelCount = 1)
    assertEquals(Short.MAX_VALUE, out[0])
    assertEquals(Short.MIN_VALUE, out[1])
    assertTrue(out[2] > 3_500)
    assertTrue(out[3] < -3_500)
  }
}
