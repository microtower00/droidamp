package com.droidamp.player.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.droidamp.player.domain.model.ReplayGainMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

@OptIn(UnstableApi::class)
class ReplayGainProcessorTest {
  @Test
  fun rejectsUnsupportedAudioFormatsAndStaysActiveOnValidPcm() {
    val processor = ReplayGainProcessor()
    assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
      processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_8BIT))
    }

    val fmt16 = DspTestHelpers.configureAndFlush(processor, 44_100, 2, C.ENCODING_PCM_16BIT)
    assertEquals(C.ENCODING_PCM_16BIT, fmt16.encoding)
    assertTrue(processor.isActive)
  }

  @Test
  fun offModePassesAudioThroughBitExact() {
    val processor = ReplayGainProcessor()
    processor.setTrackMetadata(ReplayGainMetadata(trackGainDb = -10f, trackPeak = 0.9f))
    processor.setConfig(ReplayGainMode.OFF, preampDb = 6f)
    DspTestHelpers.configureAndFlush(processor, 44_100, 1, C.ENCODING_PCM_16BIT)

    val input = shortArrayOf(0, 1234, -5678, 32767, -32768)
    val output = DspTestHelpers.processPcm16(processor, input, chunkFrames = 5, channelCount = 1)
    assertArrayEquals(input, output)
  }

  @Test
  fun selectsTrackVsAlbumWithFallbackAndAppliesPreamp() {
    val processor = ReplayGainProcessor()
    processor.setTrackMetadata(
      ReplayGainMetadata(
        trackGainDb = -6.0206f, // ~0.5x
        trackPeak = 0.8f,
        albumGainDb = -12.0412f, // ~0.25x
        albumPeak = 0.95f,
      ),
    )

    processor.setConfig(ReplayGainMode.TRACK, preampDb = 0f)
    DspTestHelpers.configureAndFlush(processor, 44_100, 1, C.ENCODING_PCM_FLOAT)
    val floatIn = floatArrayOf(0.8f, -0.4f)
    val trackOut = DspTestHelpers.processPcmFloat(processor, floatIn, chunkFrames = 2, channelCount = 1)
    assertEquals(0.4f, trackOut[0], 1e-3f)
    assertEquals(-0.2f, trackOut[1], 1e-3f)

    processor.setConfig(ReplayGainMode.ALBUM, preampDb = 6.0206f) // -12 + 6 = -6 dB (~0.5x)
    val albumOut = DspTestHelpers.processPcmFloat(processor, floatIn, chunkFrames = 2, channelCount = 1)
    assertEquals(0.4f, albumOut[0], 1e-3f)
    assertEquals(-0.2f, albumOut[1], 1e-3f)

    // Missing album gain falls back to track gain
    processor.setTrackMetadata(ReplayGainMetadata(trackGainDb = -6.0206f, trackPeak = 0.5f))
    processor.setConfig(ReplayGainMode.ALBUM, preampDb = 0f)
    val fallbackOut = DspTestHelpers.processPcmFloat(processor, floatIn, chunkFrames = 2, channelCount = 1)
    assertEquals(0.4f, fallbackOut[0], 1e-3f)
  }

  @Test
  fun enforcesPeakClippingProtectionAndZeroPeakGuard() {
    val processor = ReplayGainProcessor()
    // +12 dB (~3.98x) with peak = 0.5 -> must cap effective gain at 1 / 0.5 = 2.0x
    processor.setTrackMetadata(ReplayGainMetadata(trackGainDb = 6f, trackPeak = 0.5f))
    processor.setConfig(ReplayGainMode.TRACK, preampDb = 6f)
    assertTrue(processor.currentEffectiveGain() * 0.5f <= 1.0f + 1e-6f)
    assertEquals(2.0f, processor.currentEffectiveGain(), 1e-5f)

    DspTestHelpers.configureAndFlush(processor, 44_100, 1, C.ENCODING_PCM_16BIT)
    val input = shortArrayOf(10_000, -10_000)
    val output = DspTestHelpers.processPcm16(processor, input, chunkFrames = 2, channelCount = 1)
    assertEquals(20_000.toShort(), output[0])
    assertEquals((-20_000).toShort(), output[1])

    // Zero peak must not produce NaN/Infinity; saturation clamping protects PCM_16BIT when peak missing
    processor.setTrackMetadata(ReplayGainMetadata(trackGainDb = 12f, trackPeak = 0f))
    val expectedGain = 10.0.pow((12.0 + 6.0) / 20.0).toFloat()
    assertEquals(expectedGain, processor.currentEffectiveGain(), 1e-3f)

    val hotInput = shortArrayOf(25_000, -25_000)
    val clamped = DspTestHelpers.processPcm16(processor, hotInput, chunkFrames = 2, channelCount = 1)
    assertEquals(Short.MAX_VALUE, clamped[0])
    assertEquals(Short.MIN_VALUE, clamped[1])
  }
}
