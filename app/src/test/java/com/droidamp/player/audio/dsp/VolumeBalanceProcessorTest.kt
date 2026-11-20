package com.droidamp.player.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.droidamp.player.domain.model.PlaybackState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class VolumeBalanceProcessorTest {
  @Test
  fun logarithmicTaperIsStrictlyMonotonicWithExactEndpoints() {
    val g0 = VolumeBalanceProcessor.volumeToLinearGain(0)
    val g64 = VolumeBalanceProcessor.volumeToLinearGain(64)
    val g128 = VolumeBalanceProcessor.volumeToLinearGain(128)
    val g204 = VolumeBalanceProcessor.volumeToLinearGain(PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME)
    val g255 = VolumeBalanceProcessor.volumeToLinearGain(255)

    assertEquals(0f, g0, 1e-7f)
    assertEquals(1f, g255, 1e-7f)
    assertTrue(g0 < g64 && g64 < g128 && g128 < g204 && g204 < g255)
    assertTrue("50% slider should be logarithmic (< 0.25 linear), was $g128", g128 < 0.25f)
    assertTrue("Cold launch 204 should attenuate below unity, was $g204", g204 in 0.30f..0.50f)
  }

  @Test
  fun fullVolumeCenterBalancePassesThroughBitExactAndZeroMutes() {
    val processor = VolumeBalanceProcessor()
    assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
      processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_8BIT))
    }

    DspTestHelpers.configureAndFlush(processor, 44_100, 2, C.ENCODING_PCM_16BIT)
    val stereoIn = shortArrayOf(10_000, -10_000, 20_000, -20_000)

    processor.setVolume(255)
    processor.setBalance(0)
    assertArrayEquals(stereoIn, DspTestHelpers.processPcm16(processor, stereoIn, 2, 2))

    processor.setVolume(0)
    assertArrayEquals(shortArrayOf(0, 0, 0, 0), DspTestHelpers.processPcm16(processor, stereoIn, 2, 2))
  }

  @Test
  fun stereoBalancePansLeftAndRightWhileMonoRemainsAudibleAtExtremes() {
    val processor = VolumeBalanceProcessor()
    processor.setVolume(255)

    // Stereo PCM_16BIT: balance = -127 mutes right channel only
    DspTestHelpers.configureAndFlush(processor, 44_100, 2, C.ENCODING_PCM_16BIT)
    val stereoIn = shortArrayOf(12_000, 12_000, -12_000, -12_000)
    processor.setBalance(-127)
    val fullLeft = DspTestHelpers.processPcm16(processor, stereoIn, 2, 2)
    assertEquals(12_000.toShort(), fullLeft[0])
    assertEquals(0.toShort(), fullLeft[1])
    assertEquals((-12_000).toShort(), fullLeft[2])
    assertEquals(0.toShort(), fullLeft[3])

    // Balance = +127 mutes left channel only
    processor.setBalance(127)
    val fullRight = DspTestHelpers.processPcm16(processor, stereoIn, 2, 2)
    assertEquals(0.toShort(), fullRight[0])
    assertEquals(12_000.toShort(), fullRight[1])

    // Mono safety: channelCount = 1 must NOT mute at -127 or +127
    DspTestHelpers.configureAndFlush(processor, 44_100, 1, C.ENCODING_PCM_FLOAT)
    val monoIn = floatArrayOf(0.75f, -0.5f)
    processor.setBalance(-127)
    assertArrayEquals(monoIn, DspTestHelpers.processPcmFloat(processor, monoIn, 2, 1), 1e-6f)
    processor.setBalance(127)
    assertArrayEquals(monoIn, DspTestHelpers.processPcmFloat(processor, monoIn, 2, 1), 1e-6f)
  }
}
