package com.droidamp.player.audio.dsp

import com.droidamp.player.domain.model.WinampEqSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

data class BiquadCoefficients(
  val b0: Double,
  val b1: Double,
  val b2: Double,
  val a1: Double,
  val a2: Double,
  val isBypassed: Boolean,
) {
  companion object {
    val UNITY =
      BiquadCoefficients(
        b0 = 1.0,
        b1 = 0.0,
        b2 = 0.0,
        a1 = 0.0,
        a2 = 0.0,
        isBypassed = true,
      )
  }
}

class BiquadState {
  var z1: Double = 0.0
  var z2: Double = 0.0

  fun process(
    x: Double,
    coeffs: BiquadCoefficients,
  ): Double {
    if (coeffs.isBypassed) return x
    val y = coeffs.b0 * x + z1
    var nz1 = coeffs.b1 * x - coeffs.a1 * y + z2
    var nz2 = coeffs.b2 * x - coeffs.a2 * y
    if (abs(nz1) < BiquadFilter.DENORMAL_THRESHOLD) nz1 = 0.0
    if (abs(nz2) < BiquadFilter.DENORMAL_THRESHOLD) nz2 = 0.0
    z1 = nz1
    z2 = nz2
    return y
  }

  fun reset() {
    z1 = 0.0
    z2 = 0.0
  }
}

object BiquadFilter {
  const val DEFAULT_Q = 1.4142135623730951 // sqrt(2.0), ~1 octave bandwidth
  const val NYQUIST_RATIO = 0.49
  const val MIN_ACTIVE_GAIN_DB = 1e-4f
  const val DENORMAL_THRESHOLD = 1e-18

  /**
   * Robert Bristow-Johnson Audio EQ Cookbook peaking EQ biquad coefficients.
   * Returns [BiquadCoefficients.UNITY] when [centerFreqHz] exceeds the Nyquist guard
   * (`sampleRateHz * 0.49`) or gain is effectively zero.
   */
  fun peakingEq(
    sampleRateHz: Int,
    centerFreqHz: Double,
    gainDb: Float,
    q: Double = DEFAULT_Q,
  ): BiquadCoefficients {
    if (
      sampleRateHz <= 0 ||
      centerFreqHz <= 0.0 ||
      centerFreqHz >= sampleRateHz * NYQUIST_RATIO ||
      q <= 0.0 ||
      !gainDb.isFinite() ||
      abs(gainDb) < MIN_ACTIVE_GAIN_DB
    ) {
      return BiquadCoefficients.UNITY
    }

    val clampedDb = WinampEqSpec.clampDb(gainDb).toDouble()
    val a = 10.0.pow(clampedDb / 40.0)
    val w0 = 2.0 * PI * centerFreqHz / sampleRateHz.toDouble()
    val cosW0 = cos(w0)
    val alpha = sin(w0) / (2.0 * q)

    val b0 = 1.0 + alpha * a
    val b1 = -2.0 * cosW0
    val b2 = 1.0 - alpha * a
    val a0 = 1.0 + alpha / a
    val a1 = -2.0 * cosW0
    val a2 = 1.0 - alpha / a

    val invA0 = 1.0 / a0
    return BiquadCoefficients(
      b0 = b0 * invA0,
      b1 = b1 * invA0,
      b2 = b2 * invA0,
      a1 = a1 * invA0,
      a2 = a2 * invA0,
      isBypassed = false,
    )
  }
}
