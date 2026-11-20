package com.droidamp.player.interop

import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.WinampEqSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WinampPresetLibraryTest {
  @Test
  fun classicWinampPresets_preservesAllSixM1PresetsAndExpandsToEighteen() {
    assertEquals(18, ClassicWinampPresets.ALL.size)
    assertEquals(ClassicWinampPresets.FLAT, ClassicWinampPresets.ALL.first())

    // Verify exact M1 preset constants, names, preamps, and bands are intact
    assertEquals("FLAT", ClassicWinampPresets.FLAT.name)
    assertEquals(0f, ClassicWinampPresets.FLAT.preampDb, 0f)
    assertEquals(List(10) { 0f }, ClassicWinampPresets.FLAT.bandsDb)

    assertEquals("ROCK", ClassicWinampPresets.ROCK.name)
    assertEquals(0f, ClassicWinampPresets.ROCK.preampDb, 0f)
    assertEquals(
      listOf(4.8f, 3.0f, -3.4f, -4.8f, -2.0f, 2.4f, 5.4f, 6.8f, 6.8f, 6.8f),
      ClassicWinampPresets.ROCK.bandsDb,
    )

    assertEquals("POP", ClassicWinampPresets.POP.name)
    assertEquals(0f, ClassicWinampPresets.POP.preampDb, 0f)
    assertEquals(
      listOf(-1.2f, 2.8f, 4.4f, 4.8f, 3.2f, -0.8f, -1.6f, -1.6f, -1.2f, -1.2f),
      ClassicWinampPresets.POP.bandsDb,
    )

    assertEquals("TECHNO", ClassicWinampPresets.TECHNO.name)
    assertEquals(0f, ClassicWinampPresets.TECHNO.preampDb, 0f)
    assertEquals(
      listOf(4.8f, 3.6f, 0f, -3.4f, -3.0f, 0f, 4.8f, 6.0f, 6.0f, 5.4f),
      ClassicWinampPresets.TECHNO.bandsDb,
    )

    assertEquals("FULL BASS & TREBLE", ClassicWinampPresets.FULL_BASS_TREBLE.name)
    assertEquals(-1.0f, ClassicWinampPresets.FULL_BASS_TREBLE.preampDb, 0f)
    assertEquals(
      listOf(4.4f, 3.6f, 0f, -4.4f, -2.8f, 1.0f, 5.0f, 6.8f, 7.4f, 7.4f),
      ClassicWinampPresets.FULL_BASS_TREBLE.bandsDb,
    )

    assertEquals("CLASSICAL", ClassicWinampPresets.CLASSICAL.name)
    assertEquals(0f, ClassicWinampPresets.CLASSICAL.preampDb, 0f)
    assertEquals(
      listOf(0f, 0f, 0f, 0f, 0f, 0f, -4.4f, -4.4f, -4.4f, -5.8f),
      ClassicWinampPresets.CLASSICAL.bandsDb,
    )

    // Verify all 18 presets have unique names and valid +-12 dB ranges
    val uniqueNames = ClassicWinampPresets.ALL.map { it.name }.toSet()
    assertEquals(18, uniqueNames.size)

    for (preset in ClassicWinampPresets.ALL) {
      assertTrue(preset.name.isNotBlank())
      assertTrue(preset.preampDb in WinampEqSpec.MIN_DB..WinampEqSpec.MAX_DB)
      assertEquals(WinampEqSpec.BAND_COUNT, preset.bandsDb.size)
      preset.bandsDb.forEach { gain ->
        assertTrue(
          "Gain $gain out of bounds in ${preset.name}",
          gain in WinampEqSpec.MIN_DB..WinampEqSpec.MAX_DB,
        )
      }
    }
  }

  @Test
  fun winampPresetLibrary_findByNameAndMergePresetsWorkCaseInsensitively() {
    assertEquals(ClassicWinampPresets.ALL, WinampPresetLibrary.builtInPresets)
    assertEquals(ClassicWinampPresets.SOFT_ROCK, WinampPresetLibrary.findByName("  soft rock "))
    assertNull(WinampPresetLibrary.findByName("nonexistent"))
    assertNull(WinampPresetLibrary.findByName("   "))

    val custom = EqPreset("Late Night", -1.5f, List(10) { 2.0f })
    val duplicateRock = EqPreset("rock", 3.0f, List(10) { 3.0f })

    assertEquals(custom, WinampPresetLibrary.findByName("LATE NIGHT", listOf(custom)))

    val merged = WinampPresetLibrary.mergePresets(listOf(duplicateRock, custom))
    assertEquals(19, merged.size)
    assertEquals(ClassicWinampPresets.FLAT, merged.first())
    assertEquals(ClassicWinampPresets.ROCK, merged.first { it.name == "ROCK" })
    assertNotNull(merged.firstOrNull { it.name == "Late Night" })
  }
}
