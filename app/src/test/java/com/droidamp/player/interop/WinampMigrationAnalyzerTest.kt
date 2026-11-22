package com.droidamp.player.interop

import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WinampMigrationAnalyzerTest {

  @Test
  fun analyzeIni_reportsMatchedKeysAndCountsMalformedLinesDefensively() {
    val ini =
      """
      [OtherSection]
      ignored=99

      [Winamp]
      ; classic fields
      volume=190
      pan=bad_int
      shuffle=1
      repeat=0
      eq_use=true
      unknown_key=42
      broken line without equals
      rg_mode=2
      crossfade=5
      """.trimIndent()

    val res = WinampMigrationAnalyzer.analyzeIni(ini)
    assertTrue(res.hasWinampSection)
    assertEquals(listOf("volume", "shuffle", "repeat", "eq_use", "rg_mode", "crossfade"), res.matchedKeys)
    assertEquals(3, res.skippedLines)
    assertEquals(190, res.snapshot.volume)
    assertEquals(true, res.snapshot.shuffle)
    assertEquals(false, res.snapshot.repeat)
    assertEquals(2, res.snapshot.replayGainMode)
    assertEquals(5, res.snapshot.crossfadeSeconds)
  }

  @Test
  fun analyzeQ1AndQ2_salvageValidRecordsWhenTrailingBytesTruncated() {
    val eqfCodec = BinaryEqfCodec()
    val q1Bytes =
      eqfCodec.encodePresetLibraryQ1(
        listOf(
          ClassicWinampPresets.ROCK,
          EqPreset("Club", 2f, List(10) { 1f }),
        ),
      )
    val truncatedQ1 = q1Bytes + byteArrayOf(1, 2, 3, 4, 5) // corrupt trailing partial record
    val q1Res = WinampMigrationAnalyzer.analyzeQ1(truncatedQ1)
    assertEquals(2, q1Res.presets.size)
    assertEquals(1, q1Res.skippedBlocks)

    val q2Codec = WinampQ2Codec()
    val q2Bytes =
      q2Codec.encodeAutoPresetsQ2(
        listOf(
          AutoEqRule("Music/Rock", ClassicWinampPresets.ROCK),
          AutoEqRule("*.flac", ClassicWinampPresets.TECHNO),
        ),
      )
    val truncatedQ2 = q2Bytes.copyOf(q2Bytes.size - 12) // truncate 2nd record
    val q2Res = WinampMigrationAnalyzer.analyzeQ2(truncatedQ2)
    assertEquals(1, q2Res.rules.size)
    assertEquals("Music/Rock", q2Res.rules[0].pattern)
    assertEquals(1, q2Res.skippedBlocks)
  }
}
