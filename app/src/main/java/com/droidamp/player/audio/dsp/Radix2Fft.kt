package com.droidamp.player.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Kotlin iterative Cooley-Tukey Radix-2 FFT with precomputed bit-reversal, twiddle, and
 * Hann window lookup tables. Zero heap allocations during transform execution.
 */
class Radix2Fft(
  val size: Int = DEFAULT_FFT_SIZE,
) {
  init {
    require(size >= 2 && (size and (size - 1)) == 0) {
      "Radix2Fft size must be a power of 2 >= 2 (got $size)."
    }
  }

  private val log2Size: Int = Integer.numberOfTrailingZeros(size)
  private val bitReversed: IntArray =
    IntArray(size) { i ->
      var x = i
      var rev = 0
      for (b in 0 until log2Size) {
        rev = (rev shl 1) or (x and 1)
        x = x ushr 1
      }
      rev
    }
  private val halfSize: Int = size ushr 1
  private val cosTable: FloatArray =
    FloatArray(halfSize) { k -> cos(2.0 * PI * k / size.toDouble()).toFloat() }
  private val sinTable: FloatArray =
    FloatArray(halfSize) { k -> (-sin(2.0 * PI * k / size.toDouble())).toFloat() }
  private val hannWindow: FloatArray =
    FloatArray(size) { n ->
      (0.5 * (1.0 - cos(2.0 * PI * n / (size - 1).toDouble()))).toFloat()
    }
  private val windowSum: Float = hannWindow.sum().coerceAtLeast(1e-6f)
  private val singleSidedScale: Float = 2.0f / windowSum
  private val dcScale: Float = 1.0f / windowSum

  fun applyHannWindow(
    input: FloatArray,
    outReal: FloatArray,
    outImag: FloatArray,
  ) {
    require(input.size >= size && outReal.size >= size && outImag.size >= size) {
      "Buffer length must be at least $size."
    }
    for (i in 0 until size) {
      outReal[i] = input[i] * hannWindow[i]
      outImag[i] = 0f
    }
  }

  fun fft(
    real: FloatArray,
    imag: FloatArray,
  ) {
    require(real.size >= size && imag.size >= size) {
      "FFT arrays must have length >= $size."
    }

    for (i in 0 until size) {
      val rev = bitReversed[i]
      if (i < rev) {
        val tmpRe = real[i]
        real[i] = real[rev]
        real[rev] = tmpRe
        val tmpIm = imag[i]
        imag[i] = imag[rev]
        imag[rev] = tmpIm
      }
    }

    var len = 2
    while (len <= size) {
      val halfLen = len ushr 1
      val step = size / len
      var i = 0
      while (i < size) {
        var twiddleIdx = 0
        for (j in 0 until halfLen) {
          val wRe = cosTable[twiddleIdx]
          val wIm = sinTable[twiddleIdx]
          val evenIdx = i + j
          val oddIdx = evenIdx + halfLen
          val oddRe = real[oddIdx]
          val oddIm = imag[oddIdx]

          val tRe = wRe * oddRe - wIm * oddIm
          val tIm = wRe * oddIm + wIm * oddRe
          val uRe = real[evenIdx]
          val uIm = imag[evenIdx]

          real[evenIdx] = uRe + tRe
          imag[evenIdx] = uIm + tIm
          real[oddIdx] = uRe - tRe
          imag[oddIdx] = uIm - tIm

          twiddleIdx += step
        }
        i += len
      }
      len = len shl 1
    }
  }

  fun computeSpectrumMagnitudes(
    timeSamples: FloatArray,
    scratchReal: FloatArray,
    scratchImag: FloatArray,
    outMagnitudes: FloatArray,
  ) {
    require(outMagnitudes.size >= halfSize) {
      "outMagnitudes length must be at least $halfSize."
    }
    applyHannWindow(timeSamples, scratchReal, scratchImag)
    fft(scratchReal, scratchImag)

    outMagnitudes[0] = abs(scratchReal[0]) * dcScale
    for (k in 1 until halfSize) {
      val re = scratchReal[k]
      val im = scratchImag[k]
      outMagnitudes[k] = sqrt(re * re + im * im) * singleSidedScale
    }
  }

  companion object {
    const val DEFAULT_FFT_SIZE = 1024
  }
}
