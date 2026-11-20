package com.droidamp.player.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.WinampEqSpec
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class WinampTenBandEqProcessor : BaseAudioProcessor() {
  private class EqSnapshot(
    val enabled: Boolean,
    val preampLinear: Double,
    val bandActive: BooleanArray,
    val b0: DoubleArray,
    val b1: DoubleArray,
    val b2: DoubleArray,
    val a1: DoubleArray,
    val a2: DoubleArray,
    val isUnity: Boolean,
  ) {
    companion object {
      val BYPASS =
        EqSnapshot(
          enabled = false,
          preampLinear = 1.0,
          bandActive = BooleanArray(WinampEqSpec.BAND_COUNT),
          b0 = DoubleArray(WinampEqSpec.BAND_COUNT) { 1.0 },
          b1 = DoubleArray(WinampEqSpec.BAND_COUNT),
          b2 = DoubleArray(WinampEqSpec.BAND_COUNT),
          a1 = DoubleArray(WinampEqSpec.BAND_COUNT),
          a2 = DoubleArray(WinampEqSpec.BAND_COUNT),
          isUnity = true,
        )
    }
  }

  private val coeffLock = Any()
  private var latestState: EqualizerState = EqualizerState()
  private var configuredSampleRateHz: Int = 0

  @Volatile
  private var activeSnapshot: EqSnapshot = EqSnapshot.BYPASS

  // Owned exclusively by the audio render thread (or lifecycle methods when pipeline is idle).
  private var z1: DoubleArray = DoubleArray(0)
  private var z2: DoubleArray = DoubleArray(0)
  private var wasBypassed: Boolean = true

  fun updateEqualizerState(state: EqualizerState) {
    synchronized(coeffLock) {
      latestState = state
      if (configuredSampleRateHz > 0) {
        activeSnapshot = buildSnapshot(state, configuredSampleRateHz)
      }
    }
  }

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

    val stateSize = inputAudioFormat.channelCount * WinampEqSpec.BAND_COUNT
    if (z1.size != stateSize) {
      z1 = DoubleArray(stateSize)
      z2 = DoubleArray(stateSize)
    } else {
      z1.fill(0.0)
      z2.fill(0.0)
    }
    wasBypassed = true

    synchronized(coeffLock) {
      configuredSampleRateHz = inputAudioFormat.sampleRate
      activeSnapshot = buildSnapshot(latestState, inputAudioFormat.sampleRate)
    }

    return inputAudioFormat
  }

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    z1.fill(0.0)
    z2.fill(0.0)
    wasBypassed = true
  }

  override fun onReset() {
    z1 = DoubleArray(0)
    z2 = DoubleArray(0)
    wasBypassed = true
    synchronized(coeffLock) {
      configuredSampleRateHz = 0
      activeSnapshot = EqSnapshot.BYPASS
    }
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val remaining = inputBuffer.remaining()
    if (remaining <= 0) return

    val snapshot = activeSnapshot
    val encoding = inputAudioFormat.encoding
    val channels = inputAudioFormat.channelCount

    if (!snapshot.enabled || snapshot.isUnity) {
      wasBypassed = true
      copyPassThrough(inputBuffer, remaining, encoding)
      return
    }

    if (wasBypassed) {
      z1.fill(0.0)
      z2.fill(0.0)
      wasBypassed = false
    }

    when (encoding) {
      C.ENCODING_PCM_16BIT -> {
        val bytesPerFrame = channels * 2
        val frameCount = remaining / bytesPerFrame
        if (frameCount <= 0) return
        val out = replaceOutputBuffer(frameCount * bytesPerFrame)
        for (frame in 0 until frameCount) {
          for (ch in 0 until channels) {
            val rawSample = inputBuffer.getShort()
            val normalized = rawSample.toDouble() * INV_PCM_16_SCALE
            val processed = processChannelSample(normalized, ch, snapshot)
            val quantized =
              (processed * PCM_16_SCALE)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            out.putShort(quantized)
          }
        }
        out.flip()
      }
      C.ENCODING_PCM_FLOAT -> {
        val bytesPerFrame = channels * 4
        val frameCount = remaining / bytesPerFrame
        if (frameCount <= 0) return
        val out = replaceOutputBuffer(frameCount * bytesPerFrame)
        for (frame in 0 until frameCount) {
          for (ch in 0 until channels) {
            val rawSample = inputBuffer.getFloat().toDouble()
            val processed = processChannelSample(rawSample, ch, snapshot)
            out.putFloat(processed.coerceIn(-1.0, 1.0).toFloat())
          }
        }
        out.flip()
      }
    }
  }

  private fun processChannelSample(
    inputSample: Double,
    channelIndex: Int,
    snapshot: EqSnapshot,
  ): Double {
    var x = inputSample * snapshot.preampLinear
    val stateOffset = channelIndex * WinampEqSpec.BAND_COUNT
    val bandActive = snapshot.bandActive
    val b0 = snapshot.b0
    val b1 = snapshot.b1
    val b2 = snapshot.b2
    val a1 = snapshot.a1
    val a2 = snapshot.a2

    for (band in 0 until WinampEqSpec.BAND_COUNT) {
      if (!bandActive[band]) continue
      val idx = stateOffset + band
      val curZ1 = z1[idx]
      val curZ2 = z2[idx]
      val y = b0[band] * x + curZ1
      var nz1 = b1[band] * x - a1[band] * y + curZ2
      var nz2 = b2[band] * x - a2[band] * y
      if (abs(nz1) < BiquadFilter.DENORMAL_THRESHOLD) nz1 = 0.0
      if (abs(nz2) < BiquadFilter.DENORMAL_THRESHOLD) nz2 = 0.0
      z1[idx] = nz1
      z2[idx] = nz2
      x = y
    }
    return x
  }

  private fun copyPassThrough(
    inputBuffer: ByteBuffer,
    remaining: Int,
    encoding: Int,
  ) {
    val out = replaceOutputBuffer(remaining)
    if (inputBuffer.order() == out.order()) {
      out.put(inputBuffer)
      out.flip()
      return
    }
    when (encoding) {
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

  private fun buildSnapshot(
    state: EqualizerState,
    sampleRateHz: Int,
  ): EqSnapshot {
    if (!state.enabled) {
      return EqSnapshot.BYPASS
    }

    val safePreampDb =
      if (state.preampDb.isFinite()) WinampEqSpec.clampDb(state.preampDb) else 0f
    val preampUnity = abs(safePreampDb) < BiquadFilter.MIN_ACTIVE_GAIN_DB
    val preampLinear = if (preampUnity) 1.0 else 10.0.pow(safePreampDb.toDouble() / 20.0)

    val bandActive = BooleanArray(WinampEqSpec.BAND_COUNT)
    val b0 = DoubleArray(WinampEqSpec.BAND_COUNT) { 1.0 }
    val b1 = DoubleArray(WinampEqSpec.BAND_COUNT)
    val b2 = DoubleArray(WinampEqSpec.BAND_COUNT)
    val a1 = DoubleArray(WinampEqSpec.BAND_COUNT)
    val a2 = DoubleArray(WinampEqSpec.BAND_COUNT)

    var anyBandActive = false
    for (band in 0 until WinampEqSpec.BAND_COUNT) {
      val freqHz = WinampEqSpec.BAND_FREQUENCIES_HZ[band].toDouble()
      val gainDb = state.bandsDb.getOrElse(band) { 0f }
      val coeffs = BiquadFilter.peakingEq(sampleRateHz, freqHz, gainDb)
      if (!coeffs.isBypassed) {
        anyBandActive = true
        bandActive[band] = true
        b0[band] = coeffs.b0
        b1[band] = coeffs.b1
        b2[band] = coeffs.b2
        a1[band] = coeffs.a1
        a2[band] = coeffs.a2
      }
    }

    val isUnity = preampUnity && !anyBandActive
    return EqSnapshot(
      enabled = true,
      preampLinear = preampLinear,
      bandActive = bandActive,
      b0 = b0,
      b1 = b1,
      b2 = b2,
      a1 = a1,
      a2 = a2,
      isUnity = isUnity,
    )
  }

  companion object {
    private const val PCM_16_SCALE = 32768.0
    private const val INV_PCM_16_SCALE = 1.0 / PCM_16_SCALE
  }
}
