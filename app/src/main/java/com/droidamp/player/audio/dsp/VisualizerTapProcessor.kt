package com.droidamp.player.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.droidamp.player.domain.model.VisualizerFrame
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

@OptIn(UnstableApi::class)
class VisualizerTapProcessor(
  val ringBuffer: LockFreeAudioRingBuffer = LockFreeAudioRingBuffer(),
  val fft: Radix2Fft = Radix2Fft(),
) : BaseAudioProcessor() {
  @Volatile
  private var activeSampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ

  // Reader-thread scratch buffers (never touched by the audio render thread).
  private val readerLock = Any()
  private val sampleWindow = FloatArray(fft.size)
  private val fftReal = FloatArray(fft.size)
  private val fftImag = FloatArray(fft.size)
  private val magnitudes = FloatArray(fft.size / 2)
  private val smoothedBars = FloatArray(VisualizerFrame.SPECTRUM_BAR_COUNT)
  private val peakDots = FloatArray(VisualizerFrame.SPECTRUM_BAR_COUNT)

  override fun onConfigure(
    inputAudioFormat: AudioProcessor.AudioFormat,
  ): AudioProcessor.AudioFormat {
    val valid =
      inputAudioFormat.sampleRate > 0 &&
        inputAudioFormat.channelCount > 0 &&
        (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
          inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
    if (!valid) {
      throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }
    activeSampleRateHz = inputAudioFormat.sampleRate
    return inputAudioFormat
  }

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    activeSampleRateHz =
      if (inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET && inputAudioFormat.sampleRate > 0) {
        inputAudioFormat.sampleRate
      } else {
        DEFAULT_SAMPLE_RATE_HZ
      }
    ringBuffer.clear()
  }

  override fun onReset() {
    ringBuffer.clear()
    activeSampleRateHz = DEFAULT_SAMPLE_RATE_HZ
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val remaining = inputBuffer.remaining()
    if (remaining <= 0) return

    val format = inputAudioFormat
    val startPos = inputBuffer.position()
    when (format.encoding) {
      C.ENCODING_PCM_16BIT -> {
        ringBuffer.writeInterleavedPcm16(
          buffer = inputBuffer,
          startOffset = startPos,
          byteCount = remaining,
          channelCount = format.channelCount,
        )
      }
      C.ENCODING_PCM_FLOAT -> {
        ringBuffer.writeInterleavedPcmFloat(
          buffer = inputBuffer,
          startOffset = startPos,
          byteCount = remaining,
          channelCount = format.channelCount,
        )
      }
    }

    val out = replaceOutputBuffer(remaining)
    if (inputBuffer.order() == out.order()) {
      out.put(inputBuffer)
      out.flip()
      return
    }
    when (format.encoding) {
      C.ENCODING_PCM_16BIT -> {
        val sampleCount = remaining / 2
        for (i in 0 until sampleCount) {
          out.putShort(inputBuffer.getShort())
        }
      }
      C.ENCODING_PCM_FLOAT -> {
        val sampleCount = remaining / 4
        for (i in 0 until sampleCount) {
          out.putFloat(inputBuffer.getFloat())
        }
      }
    }
    out.flip()
  }

  fun readVisualizerFrame(isPlaying: Boolean): VisualizerFrame =
    synchronized(readerLock) {
      if (!isPlaying) {
        return decayToSilentLocked()
      }

      val available = ringBuffer.readLatest(sampleWindow)
      if (available == 0) {
        return decayToSilentLocked()
      }
      if (available < 0) {
        // Extreme write contention: hold previous bars/peaks without zeroing.
        return if (isAllZeroLocked()) {
          VisualizerFrame.SILENT
        } else {
          VisualizerFrame(
            spectrumBars = smoothedBars.copyOf(),
            peakDots = peakDots.copyOf(),
            waveform = FloatArray(VisualizerFrame.WAVEFORM_SAMPLE_COUNT),
          )
        }
      }

      fft.computeSpectrumMagnitudes(sampleWindow, fftReal, fftImag, magnitudes)

      val sampleRate = activeSampleRateHz.coerceAtLeast(8000)
      val maxFreq = minOf(MAX_VISUALIZER_FREQ_HZ, sampleRate * 0.48f)
      val minFreq = minOf(MIN_VISUALIZER_FREQ_HZ, maxFreq * 0.25f)
      val ratio = maxFreq / minFreq
      val binHz = sampleRate.toFloat() / fft.size.toFloat()
      val maxBin = magnitudes.size - 1

      for (band in 0 until VisualizerFrame.SPECTRUM_BAR_COUNT) {
        val fLow = minFreq * ratio.pow(band.toFloat() / VisualizerFrame.SPECTRUM_BAR_COUNT)
        val fHigh = minFreq * ratio.pow((band + 1).toFloat() / VisualizerFrame.SPECTRUM_BAR_COUNT)
        val lowBin = floor(fLow / binHz).toInt().coerceIn(1, maxBin)
        val highBin = ceil(fHigh / binHz).toInt().coerceIn(lowBin, maxBin)

        var bandMag = 0f
        for (bin in lowBin..highBin) {
          if (magnitudes[bin] > bandMag) {
            bandMag = magnitudes[bin]
          }
        }

        val db = 20f * log10(bandMag.coerceAtLeast(MAGNITUDE_FLOOR))
        val target = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
        val decayed = (smoothedBars[band] - ACTIVE_BAR_DECAY).coerceAtLeast(0f)
        smoothedBars[band] = maxOf(target, decayed)
        peakDots[band] = maxOf(smoothedBars[band], (peakDots[band] - ACTIVE_PEAK_DECAY).coerceAtLeast(0f))
      }

      val waveform = FloatArray(VisualizerFrame.WAVEFORM_SAMPLE_COUNT)
      val waveCount = minOf(available, WAVEFORM_WINDOW_SAMPLES)
      val tailStart = sampleWindow.size - waveCount
      for (i in 0 until VisualizerFrame.WAVEFORM_SAMPLE_COUNT) {
        val offset =
          if (waveCount <= 1) {
            0
          } else {
            (i * (waveCount - 1)) / (VisualizerFrame.WAVEFORM_SAMPLE_COUNT - 1)
          }
        waveform[i] = sampleWindow[tailStart + offset].coerceIn(-1f, 1f)
      }

      return VisualizerFrame(
        spectrumBars = smoothedBars.copyOf(),
        peakDots = peakDots.copyOf(),
        waveform = waveform,
      )
    }

  fun resetVisualizer() {
    synchronized(readerLock) {
      smoothedBars.fill(0f)
      peakDots.fill(0f)
    }
  }

  private fun decayToSilentLocked(): VisualizerFrame {
    var anyNonZero = false
    for (i in 0 until VisualizerFrame.SPECTRUM_BAR_COUNT) {
      val nextBar = (smoothedBars[i] - PAUSED_BAR_DECAY).coerceAtLeast(0f)
      val nextPeak = (peakDots[i] - PAUSED_PEAK_DECAY).coerceAtLeast(0f)
      smoothedBars[i] = nextBar
      peakDots[i] = nextPeak
      if (nextBar > 0f || nextPeak > 0f) {
        anyNonZero = true
      }
    }
    return if (!anyNonZero) {
      VisualizerFrame.SILENT
    } else {
      VisualizerFrame(
        spectrumBars = smoothedBars.copyOf(),
        peakDots = peakDots.copyOf(),
        waveform = FloatArray(VisualizerFrame.WAVEFORM_SAMPLE_COUNT),
      )
    }
  }

  private fun isAllZeroLocked(): Boolean {
    for (i in 0 until VisualizerFrame.SPECTRUM_BAR_COUNT) {
      if (smoothedBars[i] > 0f || peakDots[i] > 0f) return false
    }
    return true
  }

  companion object {
    const val DEFAULT_SAMPLE_RATE_HZ = 44_100
    private const val MIN_VISUALIZER_FREQ_HZ = 50f
    private const val MAX_VISUALIZER_FREQ_HZ = 16_000f
    private const val MIN_DB = -60f
    private const val MAX_DB = 0f
    private const val MAGNITUDE_FLOOR = 1e-6f
    private const val ACTIVE_BAR_DECAY = 0.08f
    private const val ACTIVE_PEAK_DECAY = 0.025f
    private const val PAUSED_BAR_DECAY = 0.08f
    private const val PAUSED_PEAK_DECAY = 0.03f
    private const val WAVEFORM_WINDOW_SAMPLES = 256
  }
}
