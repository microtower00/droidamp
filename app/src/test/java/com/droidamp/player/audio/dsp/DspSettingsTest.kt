package com.droidamp.player.audio.dsp

import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.ReplayGainMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspSettingsTest {
  @Test
  fun defaultsMatchSpec() {
    val settings = DspSettings()
    assertTrue(settings.gaplessEnabled)
    assertEquals(0, settings.crossfadeSeconds)
    assertEquals(ReplayGainMode.OFF, settings.replayGainMode)
    assertEquals(0f, settings.replayGainPreampDb, 1e-6f)
  }

  @Test
  fun sanitizedClampsOutOfRangeAndNonFiniteValues() {
    val dirty =
      DspSettings(
        gaplessEnabled = false,
        crossfadeSeconds = 99,
        replayGainMode = ReplayGainMode.ALBUM,
        replayGainPreampDb = Float.NaN,
      ).sanitized()
    assertEquals(12, dirty.crossfadeSeconds)
    assertEquals(0f, dirty.replayGainPreampDb, 1e-6f)

    val low =
      DspSettings(
        crossfadeSeconds = -5,
        replayGainPreampDb = -40f,
      ).sanitized()
    assertEquals(0, low.crossfadeSeconds)
    assertEquals(-15f, low.replayGainPreampDb, 1e-6f)

    val high =
      DspSettings(
        crossfadeSeconds = 7,
        replayGainPreampDb = 30f,
      ).sanitized()
    assertEquals(7, high.crossfadeSeconds)
    assertEquals(15f, high.replayGainPreampDb, 1e-6f)
  }
}
