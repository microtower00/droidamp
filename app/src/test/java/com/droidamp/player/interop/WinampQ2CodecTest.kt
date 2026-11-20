package com.droidamp.player.interop

import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WinampQ2CodecTest {
  private val codec = WinampQ2Codec()

  @Test
  fun q2Codec_roundTripsEmptySingleAndMultiRuleLibraries() {
    val emptyBytes = codec.encodeAutoPresetsQ2(emptyList())
    assertEquals(0, emptyBytes.size)
    assertEquals(emptyList<AutoEqRule>(), codec.parseAutoPresetsQ2(emptyBytes))

    val rules =
      listOf(
        AutoEqRule("Music/Electronic/Daft Punk", ClassicWinampPresets.TECHNO),
        AutoEqRule("Music\\Rock\\01_la_woman.mp3", ClassicWinampPresets.ROCK),
        AutoEqRule("L.A. Woman", ClassicWinampPresets.FULL_BASS_TREBLE),
      )
    val encoded = codec.encodeAutoPresetsQ2(rules)
    assertEquals(rules.size * 299, encoded.size)

    val decoded = codec.parseAutoPresetsQ2(encoded)
    assertEquals(rules.size, decoded.size)
    rules.forEachIndexed { idx, original ->
      val actual = decoded[idx]
      assertEquals(original.pattern, actual.pattern)
      assertEquals(original.pattern, actual.preset.name)
      assertTrue(abs(actual.preset.preampDb - original.preset.preampDb) <= 0.20f)
      original.preset.bandsDb.forEachIndexed { bandIdx, expectedDb ->
        assertTrue(abs(actual.preset.bandsDb[bandIdx] - expectedDb) <= 0.20f)
      }
    }

    // 2nd pass byte-identical round-trip
    assertArrayEquals(encoded, codec.encodeAutoPresetsQ2(decoded))
  }

  @Test
  fun parseAutoPresetsQ2_supportsClassicSingleHeaderLayoutAndSkipsBlankSlots() {
    val out = ByteArray(31 + 2 * 268)
    WinampEqBinaryContainer.HEADER_BYTES.copyInto(out)

    // Entry 0: valid rule
    "Music/Jazz".toByteArray(Charsets.UTF_8).copyInto(out, destinationOffset = 31)
    for (i in 0 until 11) out[31 + 257 + i] = 31.toByte()

    // Entry 1: blank padded slot
    for (i in 0 until 11) out[31 + 268 + 257 + i] = 31.toByte()

    val parsed = codec.parseAutoPresetsQ2(out)
    assertEquals(1, parsed.size)
    assertEquals("Music/Jazz", parsed[0].pattern)
    assertEquals(0.0f, parsed[0].preset.preampDb, 0.0f)
  }

  @Test(expected = IllegalArgumentException::class)
  fun encodeAutoPresetsQ2_rejectsBlankPattern() {
    codec.encodeAutoPresetsQ2(listOf(AutoEqRule("   ", ClassicWinampPresets.FLAT)))
  }

  @Test(expected = IllegalArgumentException::class)
  fun parseAutoPresetsQ2_rejectsInvalidHeader() {
    val invalid = ByteArray(299)
    codec.parseAutoPresetsQ2(invalid)
  }

  @Test(expected = IllegalArgumentException::class)
  fun parseAutoPresetsQ2_rejectsOutOfRangeGainByte() {
    val encoded = codec.encodeAutoPresetsQ2(listOf(AutoEqRule("track.mp3", ClassicWinampPresets.POP)))
    encoded[292] = 99.toByte()
    codec.parseAutoPresetsQ2(encoded)
  }

  @Test
  fun parseAutoPresetsQ2_withEqPresetDirectlyPreservesPrecision() {
    val rule = AutoEqRule("custom_stem", EqPreset("ignored", -12f, List(10) { 12f }))
    val parsed = codec.parseAutoPresetsQ2(codec.encodeAutoPresetsQ2(listOf(rule))).single()
    assertEquals("custom_stem", parsed.pattern)
    assertEquals(-12f, parsed.preset.preampDb, 0f)
    parsed.preset.bandsDb.forEach { assertEquals(12f, it, 0f) }
  }
}
