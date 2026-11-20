package com.droidamp.player.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
internal object DspTestHelpers {
  fun configureAndFlush(
    processor: AudioProcessor,
    sampleRateHz: Int = 44_100,
    channelCount: Int = 2,
    encoding: Int = C.ENCODING_PCM_16BIT,
  ): AudioProcessor.AudioFormat {
    val configured = processor.configure(AudioProcessor.AudioFormat(sampleRateHz, channelCount, encoding))
    processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
    return configured
  }

  fun processPcm16(
    processor: AudioProcessor,
    samples: ShortArray,
    chunkFrames: Int = 512,
    channelCount: Int = 2,
    byteOrder: ByteOrder = ByteOrder.nativeOrder(),
  ): ShortArray {
    val samplesPerChunk = chunkFrames * channelCount
    val out = ArrayList<Short>(samples.size)
    var offset = 0
    while (offset < samples.size) {
      val count = minOf(samplesPerChunk, samples.size - offset)
      val buf = ByteBuffer.allocateDirect(count * 2).order(byteOrder)
      for (i in 0 until count) {
        buf.putShort(samples[offset + i])
      }
      buf.flip()
      processor.queueInput(buf)
      val processed = processor.output
      while (processed.remaining() >= 2) {
        out.add(processed.getShort())
      }
      offset += count
    }
    return out.toShortArray()
  }

  fun processPcmFloat(
    processor: AudioProcessor,
    samples: FloatArray,
    chunkFrames: Int = 512,
    channelCount: Int = 2,
    byteOrder: ByteOrder = ByteOrder.nativeOrder(),
  ): FloatArray {
    val samplesPerChunk = chunkFrames * channelCount
    val out = ArrayList<Float>(samples.size)
    var offset = 0
    while (offset < samples.size) {
      val count = minOf(samplesPerChunk, samples.size - offset)
      val buf = ByteBuffer.allocateDirect(count * 4).order(byteOrder)
      for (i in 0 until count) {
        buf.putFloat(samples[offset + i])
      }
      buf.flip()
      processor.queueInput(buf)
      val processed = processor.output
      while (processed.remaining() >= 4) {
        out.add(processed.getFloat())
      }
      offset += count
    }
    return out.toFloatArray()
  }

  fun generateSinePcm16(
    sampleRateHz: Int,
    freqHz: Double,
    durationSec: Double,
    amplitude: Double = 0.2,
    channelCount: Int = 1,
  ): ShortArray {
    val frames = (sampleRateHz * durationSec).roundToInt()
    val result = ShortArray(frames * channelCount)
    for (frame in 0 until frames) {
      val sample =
        (sin(2.0 * PI * freqHz * frame / sampleRateHz) * amplitude * 32767.0)
          .roundToInt()
          .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
          .toShort()
      for (ch in 0 until channelCount) {
        result[frame * channelCount + ch] = sample
      }
    }
    return result
  }

  fun generateSineFloat(
    sampleRateHz: Int,
    freqHz: Double,
    durationSec: Double,
    amplitude: Double = 0.2,
    channelCount: Int = 1,
  ): FloatArray {
    val frames = (sampleRateHz * durationSec).roundToInt()
    val result = FloatArray(frames * channelCount)
    for (frame in 0 until frames) {
      val sample = (sin(2.0 * PI * freqHz * frame / sampleRateHz) * amplitude).toFloat()
      for (ch in 0 until channelCount) {
        result[frame * channelCount + ch] = sample
      }
    }
    return result
  }

  fun rmsDbChange(
    input: ShortArray,
    output: ShortArray,
    startSample: Int,
    endSample: Int = input.size,
    step: Int = 1,
  ): Double {
    var inSumSq = 0.0
    var outSumSq = 0.0
    var count = 0
    var i = startSample
    while (i < endSample) {
      val inVal = input[i].toDouble()
      val outVal = output[i].toDouble()
      inSumSq += inVal * inVal
      outSumSq += outVal * outVal
      count++
      i += step
    }
    val inRms = sqrt(inSumSq / count.coerceAtLeast(1))
    val outRms = sqrt(outSumSq / count.coerceAtLeast(1))
    return 20.0 * log10((outRms + 1e-12) / (inRms + 1e-12))
  }

  fun rmsFloat(
    samples: FloatArray,
    startFrame: Int = 0,
    endFrame: Int = samples.size,
    channelCount: Int = 1,
    channelIndex: Int = 0,
  ): Double {
    var sumSq = 0.0
    var count = 0
    for (frame in startFrame until endFrame) {
      val s = samples[frame * channelCount + channelIndex].toDouble()
      sumSq += s * s
      count++
    }
    return sqrt(sumSq / count.coerceAtLeast(1))
  }

  fun rmsShort(
    samples: ShortArray,
    startFrame: Int = 0,
    endFrame: Int = samples.size,
    channelCount: Int = 1,
    channelIndex: Int = 0,
  ): Double {
    var sumSq = 0.0
    var count = 0
    for (frame in startFrame until endFrame) {
      val s = samples[frame * channelCount + channelIndex].toDouble()
      sumSq += s * s
      count++
    }
    return sqrt(sumSq / count.coerceAtLeast(1))
  }
}
