package com.droidamp.player.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class Radix2FftTest {
  @Test
  fun validatesPowerOfTwoSize() {
    assertThrows(IllegalArgumentException::class.java) { Radix2Fft(1000) }
    assertThrows(IllegalArgumentException::class.java) { Radix2Fft(1) }
  }

  @Test
  fun computesExactUnwindowedDftBinIdentity() {
    val n = 64
    val fft = Radix2Fft(n)
    val kTarget = 7
    val real = FloatArray(n) { i -> cos(2.0 * PI * kTarget * i / n).toFloat() }
    val imag = FloatArray(n)

    fft.fft(real, imag)

    for (k in 0 until n) {
      val mag = sqrt(real[k] * real[k] + imag[k] * imag[k])
      if (k == kTarget || k == n - kTarget) {
        assertEquals(n / 2f, mag, 1e-3f)
      } else {
        assertEquals("Unexpected leakage at bin $k", 0f, mag, 1e-3f)
      }
    }
  }

  @Test
  fun identifiesSyntheticSineFrequenciesWithHannWindow() {
    val n = 1024
    val sampleRate = 44_100
    val fft = Radix2Fft(n)
    val scratchReal = FloatArray(n)
    val scratchImag = FloatArray(n)
    val magnitudes = FloatArray(n / 2)

    val testFreqs = listOf(440.0, 1_000.0, 10_000.0)
    for (freq in testFreqs) {
      val samples = FloatArray(n) { i -> sin(2.0 * PI * freq * i / sampleRate).toFloat() }
      fft.computeSpectrumMagnitudes(samples, scratchReal, scratchImag, magnitudes)

      var peakBin = 0
      var peakMag = -1f
      for (k in magnitudes.indices) {
        if (magnitudes[k] > peakMag) {
          peakMag = magnitudes[k]
          peakBin = k
        }
      }
      val expectedBin = (freq * n / sampleRate).roundToInt()
      assertTrueDelta(expectedBin, peakBin, 1)
      assertEquals(1.0f, peakMag, 0.25f)
    }

    // Zero input -> zero spectrum
    fft.computeSpectrumMagnitudes(FloatArray(n), scratchReal, scratchImag, magnitudes)
    for (m in magnitudes) {
      assertEquals(0f, m, 0f)
    }
  }

  private fun assertTrueDelta(
    expected: Int,
    actual: Int,
    tolerance: Int,
  ) {
    val diff = kotlin.math.abs(expected - actual)
    if (diff > tolerance) {
      throw AssertionError("Expected bin $expected +/- $tolerance, got $actual")
    }
  }
}
