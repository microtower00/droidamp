package com.droidamp.player.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.droidamp.player.domain.model.VisualizerFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class VisualizerTapProcessorTest {
  @Test
  fun rejectsUnsupportedFormatsAndPassesPcmThroughBitExact() {
    val tap = VisualizerTapProcessor()
    assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
      tap.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_8BIT))
    }

    DspTestHelpers.configureAndFlush(tap, 44_100, 2, C.ENCODING_PCM_16BIT)
    assertTrue(tap.isActive)

    val pcm16 = DspTestHelpers.generateSinePcm16(44_100, 1_000.0, 0.05, amplitude = 0.6, channelCount = 2)
    val out16 = DspTestHelpers.processPcm16(tap, pcm16, chunkFrames = 512, channelCount = 2)
    assertArrayEquals(pcm16, out16)

    DspTestHelpers.configureAndFlush(tap, 44_100, 1, C.ENCODING_PCM_FLOAT)
    val pcmFloat = DspTestHelpers.generateSineFloat(44_100, 440.0, 0.05, amplitude = 0.5, channelCount = 1)
    val outFloat = DspTestHelpers.processPcmFloat(tap, pcmFloat, chunkFrames = 512, channelCount = 1)
    assertArrayEquals(pcmFloat, outFloat, 0f)
  }

  @Test
  fun discriminatesLogSpectrumBandsProducesWaveformAndDecaysToSilentWhenPaused() {
    val tap = VisualizerTapProcessor()
    DspTestHelpers.configureAndFlush(tap, 44_100, 1, C.ENCODING_PCM_16BIT)

    val peakBands = ArrayList<Int>()
    for (freq in listOf(440.0, 1_000.0, 10_000.0)) {
      tap.flush(AudioProcessor.StreamMetadata.DEFAULT)
      tap.resetVisualizer()
      val sine = DspTestHelpers.generateSinePcm16(44_100, freq, 0.05, amplitude = 0.8)
      DspTestHelpers.processPcm16(tap, sine, chunkFrames = 512, channelCount = 1)

      val frame = tap.readVisualizerFrame(isPlaying = true)
      assertEquals(VisualizerFrame.SPECTRUM_BAR_COUNT, frame.spectrumBars.size)
      assertEquals(VisualizerFrame.SPECTRUM_BAR_COUNT, frame.peakDots.size)
      assertEquals(VisualizerFrame.WAVEFORM_SAMPLE_COUNT, frame.waveform.size)

      for (b in frame.spectrumBars) assertTrue(b in 0f..1f)
      for (p in frame.peakDots) assertTrue(p in 0f..1f)
      for (w in frame.waveform) assertTrue(w in -1f..1f)
      assertTrue(frame.waveform.any { it > 0.2f } && frame.waveform.any { it < -0.2f })

      val maxBarIdx = frame.spectrumBars.indices.maxByOrNull { frame.spectrumBars[it] } ?: -1
      peakBands.add(maxBarIdx)
    }

    assertTrue(
      "Expected strictly increasing peak bands for 440Hz/1kHz/10kHz, got $peakBands",
      peakBands[0] < peakBands[1] && peakBands[1] < peakBands[2],
    )

    // Peak dots hold above decaying bars when playing silence
    tap.flush(AudioProcessor.StreamMetadata.DEFAULT)
    val silentPcm = ShortArray(1024)
    DspTestHelpers.processPcm16(tap, silentPcm, chunkFrames = 512, channelCount = 1)
    val falling = tap.readVisualizerFrame(isPlaying = true)
    assertTrue(falling.peakDots[peakBands[2]] > falling.spectrumBars[peakBands[2]])

    // When !isPlaying, decays smoothly and eventually reaches VisualizerFrame.SILENT
    var settled = falling
    for (step in 0 until 60) {
      settled = tap.readVisualizerFrame(isPlaying = false)
      assertArrayEquals(FloatArray(VisualizerFrame.WAVEFORM_SAMPLE_COUNT), settled.waveform, 0f)
    }
    assertEquals(VisualizerFrame.SILENT, settled)
  }
}
