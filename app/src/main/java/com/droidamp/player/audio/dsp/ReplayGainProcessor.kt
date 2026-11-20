package com.droidamp.player.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.ReplayGainMode
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class ReplayGainProcessor : BaseAudioProcessor() {
  private val configLock = Any()
  private var mode: ReplayGainMode = ReplayGainMode.OFF
  private var preampDb: Float = 0f
  private var metadata: ReplayGainMetadata = ReplayGainMetadata.EMPTY

  @Volatile
  private var effectiveGain: Float = 1.0f

  fun setConfig(
    mode: ReplayGainMode,
    preampDb: Float,
  ) {
    synchronized(configLock) {
      this.mode = mode
      this.preampDb =
        if (preampDb.isFinite()) {
          preampDb.coerceIn(
            DspSettings.MIN_REPLAY_GAIN_PREAMP_DB,
            DspSettings.MAX_REPLAY_GAIN_PREAMP_DB,
          )
        } else {
          0f
        }
      recomputeEffectiveGainLocked()
    }
  }

  fun setTrackMetadata(metadata: ReplayGainMetadata) {
    synchronized(configLock) {
      this.metadata = metadata
      recomputeEffectiveGainLocked()
    }
  }

  fun clearTrackMetadata() {
    setTrackMetadata(ReplayGainMetadata.EMPTY)
  }

  fun currentEffectiveGain(): Float = effectiveGain

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
    return inputAudioFormat
  }

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    // Stateless gain stage: no delay lines to clear.
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val remaining = inputBuffer.remaining()
    if (remaining <= 0) return

    val gain = effectiveGain
    val encoding = inputAudioFormat.encoding

    if (gain == 1.0f) {
      copyPassThrough(inputBuffer, remaining, encoding)
      return
    }

    when (encoding) {
      C.ENCODING_PCM_16BIT -> {
        val sampleCount = remaining / 2
        if (sampleCount <= 0) return
        val out = replaceOutputBuffer(sampleCount * 2)
        for (i in 0 until sampleCount) {
          val sample = inputBuffer.getShort()
          val scaled =
            (sample * gain)
              .roundToInt()
              .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
              .toShort()
          out.putShort(scaled)
        }
        out.flip()
      }
      C.ENCODING_PCM_FLOAT -> {
        val sampleCount = remaining / 4
        if (sampleCount <= 0) return
        val out = replaceOutputBuffer(sampleCount * 4)
        for (i in 0 until sampleCount) {
          val sample = inputBuffer.getFloat()
          val scaled = (sample * gain).coerceIn(-1.0f, 1.0f)
          out.putFloat(scaled)
        }
        out.flip()
      }
    }
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

  private fun recomputeEffectiveGainLocked() {
    if (mode == ReplayGainMode.OFF) {
      effectiveGain = 1.0f
      return
    }

    val (selectedGainDb, selectedPeak) =
      when (mode) {
        ReplayGainMode.TRACK -> {
          val gainDb = metadata.trackGainDb ?: metadata.albumGainDb
          val peak =
            when {
              metadata.trackGainDb != null -> metadata.trackPeak
              metadata.albumGainDb != null -> metadata.albumPeak
              else -> metadata.trackPeak ?: metadata.albumPeak
            }
          gainDb to peak
        }
        ReplayGainMode.ALBUM -> {
          val gainDb = metadata.albumGainDb ?: metadata.trackGainDb
          val peak =
            when {
              metadata.albumGainDb != null -> metadata.albumPeak
              metadata.trackGainDb != null -> metadata.trackPeak
              else -> metadata.albumPeak ?: metadata.trackPeak
            }
          gainDb to peak
        }
        ReplayGainMode.OFF -> null to null
      }

    val totalDb = (selectedGainDb ?: 0f) + preampDb
    var gain = 10.0.pow(totalDb / 20.0).toFloat()
    if (!gain.isFinite() || gain < 0f) {
      gain = 1.0f
    }

    if (selectedPeak != null && selectedPeak.isFinite() && selectedPeak > 0f) {
      val maxSafeGain = 1.0f / selectedPeak
      if (gain > maxSafeGain) {
        gain = maxSafeGain
      }
    }

    effectiveGain = gain
  }
}
