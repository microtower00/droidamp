package com.droidamp.player.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.droidamp.player.domain.model.PlaybackState
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class VolumeBalanceProcessor : BaseAudioProcessor() {
  private val paramLock = Any()
  private var volume: Int = PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME
  private var balance: Int = 0

  // Single 64-bit packed stereo gain: high 32 bits = leftGain, low 32 bits = rightGain.
  // Because max(leftBalanceScale, rightBalanceScale) == 1f, masterGain == maxOf(leftGain, rightGain).
  @Volatile
  private var packedStereoGains: Long =
    packGains(
      leftGain = volumeToLinearGain(PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME),
      rightGain = volumeToLinearGain(PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME),
    )

  fun setVolume(volume0To255: Int) {
    synchronized(paramLock) {
      volume = volume0To255.coerceIn(PlaybackState.MIN_VOLUME, PlaybackState.MAX_VOLUME)
      publishGainsLocked()
    }
  }

  fun setBalance(balanceMinus127To127: Int) {
    synchronized(paramLock) {
      balance = balanceMinus127To127.coerceIn(PlaybackState.MIN_BALANCE, PlaybackState.MAX_BALANCE)
      publishGainsLocked()
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
    return inputAudioFormat
  }

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    // Stateless gain stage: no delay lines to clear.
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val remaining = inputBuffer.remaining()
    if (remaining <= 0) return

    val packed = packedStereoGains
    val leftGain = Float.fromBits((packed ushr 32).toInt())
    val rightGain = Float.fromBits(packed.toInt())
    val masterGain = maxOf(leftGain, rightGain)

    val encoding = inputAudioFormat.encoding
    val channels = inputAudioFormat.channelCount

    if (masterGain == 1.0f && (channels == 1 || (leftGain == 1.0f && rightGain == 1.0f))) {
      copyPassThrough(inputBuffer, remaining, encoding)
      return
    }

    when (encoding) {
      C.ENCODING_PCM_16BIT -> {
        val bytesPerFrame = channels * 2
        val frameCount = remaining / bytesPerFrame
        if (frameCount <= 0) return
        val out = replaceOutputBuffer(frameCount * bytesPerFrame)
        when (channels) {
          1 -> {
            // Mono-safe: never apply L/R balance attenuation to a single mono channel.
            for (frame in 0 until frameCount) {
              val s = inputBuffer.getShort()
              out.putShort(scalePcm16(s, masterGain))
            }
          }
          2 -> {
            for (frame in 0 until frameCount) {
              val l = inputBuffer.getShort()
              val r = inputBuffer.getShort()
              out.putShort(scalePcm16(l, leftGain))
              out.putShort(scalePcm16(r, rightGain))
            }
          }
          else -> {
            for (frame in 0 until frameCount) {
              for (ch in 0 until channels) {
                val gain =
                  when (ch) {
                    0 -> leftGain
                    1 -> rightGain
                    else -> masterGain
                  }
                out.putShort(scalePcm16(inputBuffer.getShort(), gain))
              }
            }
          }
        }
        out.flip()
      }
      C.ENCODING_PCM_FLOAT -> {
        val bytesPerFrame = channels * 4
        val frameCount = remaining / bytesPerFrame
        if (frameCount <= 0) return
        val out = replaceOutputBuffer(frameCount * bytesPerFrame)
        when (channels) {
          1 -> {
            for (frame in 0 until frameCount) {
              out.putFloat(inputBuffer.getFloat() * masterGain)
            }
          }
          2 -> {
            for (frame in 0 until frameCount) {
              out.putFloat(inputBuffer.getFloat() * leftGain)
              out.putFloat(inputBuffer.getFloat() * rightGain)
            }
          }
          else -> {
            for (frame in 0 until frameCount) {
              for (ch in 0 until channels) {
                val gain =
                  when (ch) {
                    0 -> leftGain
                    1 -> rightGain
                    else -> masterGain
                  }
                out.putFloat(inputBuffer.getFloat() * gain)
              }
            }
          }
        }
        out.flip()
      }
    }
  }

  private fun scalePcm16(
    sample: Short,
    gain: Float,
  ): Short =
    (sample * gain)
      .roundToInt()
      .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
      .toShort()

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

  private fun publishGainsLocked() {
    val masterGain = volumeToLinearGain(volume)
    val leftGain = masterGain * leftBalanceScale(balance)
    val rightGain = masterGain * rightBalanceScale(balance)
    packedStereoGains = packGains(leftGain, rightGain)
  }

  companion object {
    /**
     * 40 dB logarithmic volume taper for Winamp 0..255 internal DSP volume.
     * Exact 0.0 at 0 (mute) and exact 1.0 at 255 (unity).
     */
    fun volumeToLinearGain(volume0To255: Int): Float {
      val v = volume0To255.coerceIn(PlaybackState.MIN_VOLUME, PlaybackState.MAX_VOLUME)
      return when (v) {
        0 -> 0.0f
        PlaybackState.MAX_VOLUME -> 1.0f
        else -> ((100.0.pow(v / 255.0) - 1.0) / 99.0).toFloat()
      }
    }

    fun leftBalanceScale(balanceMinus127To127: Int): Float {
      val b = balanceMinus127To127.coerceIn(PlaybackState.MIN_BALANCE, PlaybackState.MAX_BALANCE)
      return if (b > 0) (127 - b) / 127f else 1.0f
    }

    fun rightBalanceScale(balanceMinus127To127: Int): Float {
      val b = balanceMinus127To127.coerceIn(PlaybackState.MIN_BALANCE, PlaybackState.MAX_BALANCE)
      return if (b < 0) (127 + b) / 127f else 1.0f
    }

    private fun packGains(
      leftGain: Float,
      rightGain: Float,
    ): Long {
      val leftBits = leftGain.toRawBits().toLong() shl 32
      val rightBits = rightGain.toRawBits().toLong() and 0xFFFF_FFFFL
      return leftBits or rightBits
    }
  }
}
