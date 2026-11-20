package com.droidamp.player.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class BiquadFilterTest {
  @Test
  fun zeroGainAndNyquistViolationReturnUnityBypass() {
    assertTrue(BiquadFilter.peakingEq(44_100, 1_000.0, 0f).isBypassed)
    assertTrue(BiquadFilter.peakingEq(44_100, 1_000.0, Float.NaN).isBypassed)
    assertTrue(BiquadFilter.peakingEq(22_050, 12_000.0, 12f).isBypassed)
    assertTrue(BiquadFilter.peakingEq(22_050, 16_000.0, 12f).isBypassed)
    assertTrue(BiquadFilter.peakingEq(32_000, 16_000.0, 12f).isBypassed)
    assertFalse(BiquadFilter.peakingEq(44_100, 16_000.0, 12f).isBypassed)
  }

  @Test
  fun peakingEqBoostsAndCutsAtCenterFrequency() {
    val sampleRate = 48_000
    val freq = 1_000.0
    val boostCoeffs = BiquadFilter.peakingEq(sampleRate, freq, 6f)
    val cutCoeffs = BiquadFilter.peakingEq(sampleRate, freq, -6f)

    val boostState = BiquadState()
    val cutState = BiquadState()
    var inSq = 0.0
    var boostSq = 0.0
    var cutSq = 0.0
    val totalSamples = 14_400
    val settle = 4_800

    for (n in 0 until totalSamples) {
      val x = sin(2.0 * PI * freq * n / sampleRate)
      val yBoost = boostState.process(x, boostCoeffs)
      val yCut = cutState.process(x, cutCoeffs)
      if (n >= settle) {
        inSq += x * x
        boostSq += yBoost * yBoost
        cutSq += yCut * yCut
      }
    }

    val boostGain = sqrt(boostSq / inSq)
    val cutGain = sqrt(cutSq / inSq)
    // +6 dB = 1.995x, -6 dB = 0.501x
    assertEquals(1.995, boostGain, 0.05)
    assertEquals(0.501, cutGain, 0.03)
  }
}
