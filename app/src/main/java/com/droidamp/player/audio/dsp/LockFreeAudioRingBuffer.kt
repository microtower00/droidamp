package com.droidamp.player.audio.dsp

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free, zero-allocation single-producer single-consumer (SPSC) overwrite-oldest ring buffer.
 *
 * Uses two monotonic cursors ([pendingEnd] written before storing samples, [committedEnd] written
 * after storing samples) plus a [generation] counter for [clear] and a [readerFence] barrier on
 * the reader thread so concurrent reads never return torn snapshots.
 */
class LockFreeAudioRingBuffer(
  val capacity: Int = DEFAULT_CAPACITY,
) {
  init {
    require(capacity >= 64 && (capacity and (capacity - 1)) == 0) {
      "LockFreeAudioRingBuffer capacity must be a power of 2 >= 64 (got $capacity)."
    }
  }

  private val ring = FloatArray(capacity)
  private val mask = capacity - 1
  private var localWritePos: Long = 0L

  private val pendingEnd = AtomicLong(0L)
  private val committedEnd = AtomicLong(0L)
  private val generation = AtomicLong(0L)
  private val readerFence = AtomicInteger(0)

  fun writeMono(
    samples: FloatArray,
    offset: Int = 0,
    length: Int = samples.size - offset,
  ) {
    if (length <= 0) return
    require(offset >= 0 && offset + length <= samples.size) {
      "Invalid writeMono slice: offset=$offset, length=$length, size=${samples.size}"
    }

    val skip = (length - capacity).coerceAtLeast(0)
    val count = length - skip
    val srcStart = offset + skip

    val start = localWritePos
    val nextEnd = start + count
    pendingEnd.set(nextEnd)

    for (i in 0 until count) {
      ring[(start + i).toInt() and mask] = samples[srcStart + i]
    }

    localWritePos = nextEnd
    committedEnd.set(nextEnd)
  }

  fun writeInterleavedPcm16(
    buffer: ByteBuffer,
    startOffset: Int,
    byteCount: Int,
    channelCount: Int,
  ) {
    if (channelCount <= 0 || byteCount <= 0) return
    val bytesPerFrame = channelCount * 2
    val totalFrames = byteCount / bytesPerFrame
    if (totalFrames <= 0) return

    val skipFrames = (totalFrames - capacity).coerceAtLeast(0)
    val framesToWrite = totalFrames - skipFrames
    val byteBase = startOffset + skipFrames * bytesPerFrame

    val start = localWritePos
    val nextEnd = start + framesToWrite
    pendingEnd.set(nextEnd)

    val invScale = 1.0f / (channelCount * 32768.0f)
    var pos = byteBase
    for (frame in 0 until framesToWrite) {
      var sum = 0f
      for (ch in 0 until channelCount) {
        sum += buffer.getShort(pos).toFloat()
        pos += 2
      }
      ring[(start + frame).toInt() and mask] = sum * invScale
    }

    localWritePos = nextEnd
    committedEnd.set(nextEnd)
  }

  fun writeInterleavedPcmFloat(
    buffer: ByteBuffer,
    startOffset: Int,
    byteCount: Int,
    channelCount: Int,
  ) {
    if (channelCount <= 0 || byteCount <= 0) return
    val bytesPerFrame = channelCount * 4
    val totalFrames = byteCount / bytesPerFrame
    if (totalFrames <= 0) return

    val skipFrames = (totalFrames - capacity).coerceAtLeast(0)
    val framesToWrite = totalFrames - skipFrames
    val byteBase = startOffset + skipFrames * bytesPerFrame

    val start = localWritePos
    val nextEnd = start + framesToWrite
    pendingEnd.set(nextEnd)

    val invChannels = 1.0f / channelCount.toFloat()
    var pos = byteBase
    for (frame in 0 until framesToWrite) {
      var sum = 0f
      for (ch in 0 until channelCount) {
        sum += buffer.getFloat(pos)
        pos += 4
      }
      ring[(start + frame).toInt() and mask] = sum * invChannels
    }

    localWritePos = nextEnd
    committedEnd.set(nextEnd)
  }

  fun clear() {
    val g = generation.get()
    generation.set(g + 1L) // odd = clear in progress
    pendingEnd.set(0L)
    committedEnd.set(0L)
    localWritePos = 0L
    ring.fill(0f)
    generation.set(g + 2L) // even = clear complete
  }

  fun latestWritePosition(): Long = committedEnd.get()

  /**
   * Copies up to [dest.size] samples ending at [endSamplePos] (clamped to the currently retained
   * window in the ring buffer) into the tail of [dest], zero-padding leading entries if fewer
   * than [dest.size] samples are available.
   */
  fun readWindowEndingAt(
    endSamplePos: Long,
    dest: FloatArray,
    maxRetries: Int = DEFAULT_MAX_RETRIES,
  ): Int {
    if (dest.isEmpty()) return 0

    for (attempt in 0 until maxRetries) {
      val genBefore = generation.get()
      if ((genBefore and 1L) != 0L) continue

      val latestEnd = committedEnd.get()
      if (latestEnd <= 0L) {
        readerFence.getAndIncrement()
        if (generation.get() == genBefore && committedEnd.get() == 0L) {
          dest.fill(0f)
          return 0
        }
        continue
      }

      val oldestRetained = (latestEnd - capacity.toLong()).coerceAtLeast(0L)
      val clampedEnd = endSamplePos.coerceIn(oldestRetained, latestEnd)
      val available = minOf(dest.size.toLong(), clampedEnd - oldestRetained).toInt().coerceAtLeast(0)
      if (available <= 0) {
        return readLatest(dest, maxRetries = 1)
      }
      val readStart = clampedEnd - available
      val leadingZeros = dest.size - available
      if (leadingZeros > 0) {
        dest.fill(0f, 0, leadingZeros)
      }
      for (i in 0 until available) {
        dest[leadingZeros + i] = ring[(readStart + i).toInt() and mask]
      }

      readerFence.getAndIncrement()
      val latestPending = pendingEnd.get()
      val genAfter = generation.get()
      if (genBefore == genAfter && (latestPending - readStart) <= capacity.toLong()) {
        return available
      }
    }

    return -1
  }

  /**
   * Copies the latest up to [dest.size] samples into the tail of [dest] (zero-padding leading
   * entries when fewer than [dest.size] samples are available).
   *
   * Returns the number of valid samples copied (`0` if empty), or `-1` if [maxRetries] was
   * exhausted under extreme write contention.
   */
  fun readLatest(
    dest: FloatArray,
    maxRetries: Int = DEFAULT_MAX_RETRIES,
  ): Int {
    if (dest.isEmpty()) return 0

    for (attempt in 0 until maxRetries) {
      val genBefore = generation.get()
      if ((genBefore and 1L) != 0L) continue

      val end = committedEnd.get()
      if (end <= 0L) {
        readerFence.getAndIncrement()
        if (generation.get() == genBefore && committedEnd.get() == 0L) {
          dest.fill(0f)
          return 0
        }
        continue
      }

      val available = minOf(dest.size, minOf(end, capacity.toLong()).toInt())
      val readStart = end - available
      val leadingZeros = dest.size - available
      if (leadingZeros > 0) {
        dest.fill(0f, 0, leadingZeros)
      }
      for (i in 0 until available) {
        dest[leadingZeros + i] = ring[(readStart + i).toInt() and mask]
      }

      readerFence.getAndIncrement()
      val latestPending = pendingEnd.get()
      val genAfter = generation.get()
      if (genBefore == genAfter && (latestPending - readStart) <= capacity.toLong()) {
        return available
      }
    }

    return -1
  }

  companion object {
    const val DEFAULT_CAPACITY = 32768
    const val DEFAULT_MAX_RETRIES = 16
  }
}
