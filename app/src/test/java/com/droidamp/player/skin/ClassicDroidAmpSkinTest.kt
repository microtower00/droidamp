package com.droidamp.player.skin

import com.droidamp.player.domain.model.WinampEqSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicDroidAmpSkinTest {

  @Test
  fun visualizerPalette_matchesExact24EntryWinampViscolorContract() {
    val vis = ClassicDroidAmpSkin.visualizer
    assertEquals(VisualizerPalette.VISCOLOR_ENTRY_COUNT, vis.colors.size)
    assertEquals(vis.colors[0], vis.background)
    assertEquals(vis.colors[23], vis.peakCap)
    assertEquals(vis.colors[17], vis.spectrumColorForLevel(0f))
    assertEquals(vis.colors[2], vis.spectrumColorForLevel(1f))
  }

  @Test
  fun winampEqSpec_hasAuthenticTenCenterFrequencies() {
    assertEquals(10, WinampEqSpec.BAND_FREQUENCIES_HZ.size)
    assertEquals(60, WinampEqSpec.BAND_FREQUENCIES_HZ.first())
    assertEquals(16_000, WinampEqSpec.BAND_FREQUENCIES_HZ.last())
    assertEquals(10, WinampEqSpec.BAND_LABELS.size)
    assertEquals(10, WinampEqSpec.BAND_READOUT_LABELS.size)
  }

  @Test
  fun typography_enforcesMobileReadabilityFloor() {
    val typo = ClassicDroidAmpSkin.typography
    assertTrue(typo.microLabel.fontSize.value >= 9f)
    assertTrue(typo.buttonLabel.fontSize.value >= 11f)
    assertTrue(typo.trackRow.fontSize.value >= 11f)
  }
}
