package com.droidamp.player.audio.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LockFreeAudioRingBufferTest {
  @Test
  fun emptyAndPartialReadsZeroPadLeadingSamplesAndClearResets() {
    val ring = LockFreeAudioRingBuffer(capacity = 64)
    val dest = FloatArray(8) { 99f }

    assertEquals(0, ring.readLatest(dest))
    assertArrayEquals(FloatArray(8), dest, 0f)

    ring.writeMono(floatArrayOf(0.25f, 0.5f, -0.75f))
    assertEquals(3, ring.readLatest(dest))
    assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.25f, 0.5f, -0.75f), dest, 1e-6f)

    ring.clear()
    assertEquals(0, ring.readLatest(dest))
    assertArrayEquals(FloatArray(8), dest, 0f)
  }

  @Test
  fun wrapAroundAndOversizedWritesRetainExactLatestSamples() {
    val ring = LockFreeAudioRingBuffer(capacity = 64)
    val firstBatch = FloatArray(50) { it.toFloat() }
    val secondBatch = FloatArray(40) { (50 + it).toFloat() }
    ring.writeMono(firstBatch)
    ring.writeMono(secondBatch)

    val dest = FloatArray(64)
    assertEquals(64, ring.readLatest(dest))
    val expected = FloatArray(64) { (26 + it).toFloat() }
    assertArrayEquals(expected, dest, 1e-6f)

    // Oversized single write (> capacity)
    val huge = FloatArray(200) { (1000 + it).toFloat() }
    ring.writeMono(huge)
    assertEquals(64, ring.readLatest(dest))
    val expectedHuge = FloatArray(64) { (1000 + 200 - 64 + it).toFloat() }
    assertArrayEquals(expectedHuge, dest, 1e-6f)
  }

  @Test
  fun interleavedPcm16AndPcmFloatDownmixStereoToMono() {
    val ring = LockFreeAudioRingBuffer(capacity = 64)

    val buf16 = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
    buf16.putShort(16384).putShort(0) // avg 0.25
    buf16.putShort(-32768).putShort(32767) // avg ~0.0
    buf16.flip()
    ring.writeInterleavedPcm16(buf16, startOffset = 0, byteCount = 8, channelCount = 2)

    val dest = FloatArray(2)
    assertEquals(2, ring.readLatest(dest))
    assertEquals(0.25f, dest[0], 1e-4f)
    assertEquals(0f, dest[1], 1e-4f)

    val bufFloat = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
    bufFloat.putFloat(0.6f).putFloat(0.2f)
    bufFloat.putFloat(-0.8f).putFloat(-0.4f)
    bufFloat.flip()
    ring.writeInterleavedPcmFloat(bufFloat, startOffset = 0, byteCount = 16, channelCount = 2)
    assertEquals(2, ring.readLatest(dest))
    assertEquals(0.4f, dest[0], 1e-6f)
    assertEquals(-0.6f, dest[1], 1e-6f)
  }

  @Test
  fun concurrentSingleProducerSingleConsumerProducesZeroTornSnapshots() {
    val ring = LockFreeAudioRingBuffer(capacity = 256)
    val running = AtomicBoolean(true)
    val failure = AtomicReference<Throwable?>(null)

    val writer =
      Thread {
        try {
          val chunk = FloatArray(32)
          var seq = 1
          for (iter in 0 until 20_000) {
            for (i in chunk.indices) {
              chunk[i] = seq++.toFloat()
            }
            ring.writeMono(chunk)
          }
        } catch (t: Throwable) {
          failure.compareAndSet(null, t)
        } finally {
          running.set(false)
        }
      }

    val reader =
      Thread {
        try {
          val snapshot = FloatArray(64)
          while (running.get()) {
            val count = ring.readLatest(snapshot)
            if (count > 1) {
              val startIdx = snapshot.size - count
              for (i in startIdx + 1 until snapshot.size) {
                val diff = snapshot[i] - snapshot[i - 1]
                if (diff != 1.0f) {
                  throw AssertionError(
                    "Torn snapshot detected at idx=$i: prev=${snapshot[i - 1]}, curr=${snapshot[i]}",
                  )
                }
              }
            }
          }
        } catch (t: Throwable) {
          failure.compareAndSet(null, t)
        }
      }

    writer.start()
    reader.start()
    writer.join(10_000)
    reader.join(10_000)

    failure.get()?.let { throw it }
    assertTrue(!writer.isAlive && !reader.isAlive)
  }
}
