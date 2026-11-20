package com.droidamp.player.interop

import com.droidamp.player.domain.contract.WinampIniSnapshot
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.PlaybackState
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WinampIniParserTest {
  private val parser = WinampIniParser()

  @Test
  fun winampIni_roundTripsFullNonDefaultSnapshotAndIsIdempotent() {
    val snapshot =
      WinampIniSnapshot(
        volume = 178,
        pan = -42,
        shuffle = true,
        repeat = true,
        timeRemaining = true,
        eqEnabled = false,
        eqAuto = true,
        eqPreampDb = ClassicWinampPresets.FULL_BASS_TREBLE.preampDb,
        eqBandsDb = ClassicWinampPresets.FULL_BASS_TREBLE.bandsDb,
        replayGainMode = 2,
        replayGainPreampDb = -3.25f,
        crossfadeSeconds = 6,
      )

    val encoded = parser.encode(snapshot)
    val decoded = parser.parse(encoded)

    assertEquals(snapshot.volume, decoded.volume)
    assertEquals(snapshot.pan, decoded.pan)
    assertEquals(snapshot.shuffle, decoded.shuffle)
    assertEquals(snapshot.repeat, decoded.repeat)
    assertEquals(snapshot.timeRemaining, decoded.timeRemaining)
    assertEquals(snapshot.eqEnabled, decoded.eqEnabled)
    assertEquals(snapshot.eqAuto, decoded.eqAuto)
    assertEquals(snapshot.replayGainMode, decoded.replayGainMode)
    assertEquals(snapshot.replayGainPreampDb, decoded.replayGainPreampDb, 0.001f)
    assertEquals(snapshot.crossfadeSeconds, decoded.crossfadeSeconds)
    assertTrue(abs(decoded.eqPreampDb - snapshot.eqPreampDb) <= 0.20f)
    snapshot.eqBandsDb.forEachIndexed { idx, expectedDb ->
      assertTrue(abs(decoded.eqBandsDb[idx] - expectedDb) <= 0.20f)
    }

    // 2nd pass encoding is 100% identical
    assertEquals(encoded, parser.encode(decoded))
  }

  @Test
  fun winampIni_flatEqRoundTripsToExactZeroDb() {
    val flat =
      WinampIniSnapshot(
        volume = 204,
        pan = 0,
        shuffle = false,
        repeat = false,
        timeRemaining = false,
        eqEnabled = true,
        eqAuto = false,
        eqPreampDb = 0f,
        eqBandsDb = List(10) { 0f },
      )
    val encoded = parser.encode(flat)
    assertTrue(encoded.contains("eq_data=31,31,31,31,31,31,31,31,31,31,31"))

    val decoded = parser.parse(encoded)
    assertEquals(0.0f, decoded.eqPreampDb, 0.0f)
    decoded.eqBandsDb.forEach { assertEquals(0.0f, it, 0.0f) }
  }

  @Test
  fun winampIni_encodeAlwaysUsesDecimalPointEvenUnderNonUsLocale() {
    val previousLocale = Locale.getDefault()
    try {
      Locale.setDefault(Locale.ITALY)
      val snapshot =
        WinampIniSnapshot(
          volume = 204,
          pan = 0,
          shuffle = false,
          repeat = false,
          timeRemaining = false,
          eqEnabled = true,
          eqAuto = false,
          eqPreampDb = 0f,
          eqBandsDb = List(10) { 0f },
          replayGainMode = 1,
          replayGainPreampDb = -4.5f,
          crossfadeSeconds = 3,
        )
      val encoded = parser.encode(snapshot)
      assertTrue("Expected rg_preamp=-4.5 in:\n$encoded", encoded.contains("rg_preamp=-4.5"))
      assertFalse(encoded.contains("rg_preamp=-4,5"))
      assertEquals(-4.5f, parser.parse(encoded).replayGainPreampDb, 0.001f)
    } finally {
      Locale.setDefault(previousLocale)
    }
  }

  @Test
  fun winampIni_handlesBomCrlfCommentsCaseInsensitivityAndUnknownSections() {
    val ini =
      "\uFEFF" +
        "; Pre-section comment\r\n" +
        "volume=10\r\n" +
        "[OtherPlugin]\r\n" +
        "volume=99\r\n" +
        "[wInAmP]\r\n" +
        "# Comment line\r\n" +
        "; Another comment\r\n" +
        "VOLUME = 123\r\n" +
        "Pan = -50\r\n" +
        "Shuffle = true\r\n" +
        "Repeat = false\r\n" +
        "TimeMode = 1\r\n" +
        "Eq_Use = false\r\n" +
        "Eq_Auto = 1\r\n" +
        "eq_data = 0 31 63 31 31 31 31 31 31 31 31\r\n" +
        "rg_mode = 1\r\n" +
        "rg_preamp = +2.5\r\n" +
        "crossfade = 8\r\n" +
        "unknown_key = ignored\r\n" +
        "[out_ds]\r\n" +
        "crossfade = 0\r\n"

    val parsed = parser.parse(ini)
    assertEquals(123, parsed.volume)
    assertEquals(-50, parsed.pan)
    assertTrue(parsed.shuffle)
    assertFalse(parsed.repeat)
    assertTrue(parsed.timeRemaining)
    assertFalse(parsed.eqEnabled)
    assertTrue(parsed.eqAuto)
    assertEquals(12.0f, parsed.eqBandsDb[0], 0.0f)
    assertEquals(0.0f, parsed.eqBandsDb[1], 0.0f)
    assertEquals(-12.0f, parsed.eqBandsDb[2], 0.0f)
    assertEquals(0.0f, parsed.eqPreampDb, 0.0f)
    assertEquals(1, parsed.replayGainMode)
    assertEquals(2.5f, parsed.replayGainPreampDb, 0.001f)
    assertEquals(8, parsed.crossfadeSeconds)
  }

  @Test
  fun winampIni_clampsOutOfRangeValuesAndSupportsSignedDecimalEqDataFallback() {
    val ini =
      """
      [Winamp]
      volume=999
      pan=-500
      rg_mode=99
      rg_preamp=25.0
      crossfade=40
      eq_data=15.0,-15.0,4.5,0,0,0,0,0,0,0,-2.0
      """.trimIndent()

    val parsed = parser.parse(ini)
    assertEquals(255, parsed.volume)
    assertEquals(-127, parsed.pan)
    assertEquals(2, parsed.replayGainMode)
    assertEquals(12.0f, parsed.replayGainPreampDb, 0.0f)
    assertEquals(12, parsed.crossfadeSeconds)
    assertEquals(12.0f, parsed.eqBandsDb[0], 0.0f)
    assertEquals(-12.0f, parsed.eqBandsDb[1], 0.0f)
    assertEquals(4.5f, parsed.eqBandsDb[2], 0.001f)
    assertEquals(-2.0f, parsed.eqPreampDb, 0.001f)

    // Encode also clamps out-of-range snapshot values
    val reencoded =
      parser.parse(
        parser.encode(
          WinampIniSnapshot(
            volume = -10,
            pan = 500,
            shuffle = false,
            repeat = false,
            timeRemaining = false,
            eqEnabled = true,
            eqAuto = false,
            eqPreampDb = 0f,
            eqBandsDb = listOf(0f, 0f), // malformed short list padded safely
            replayGainMode = -5,
            replayGainPreampDb = -99f,
            crossfadeSeconds = 50,
          ),
        ),
      )
    assertEquals(0, reencoded.volume)
    assertEquals(127, reencoded.pan)
    assertEquals(0, reencoded.replayGainMode)
    assertEquals(-12.0f, reencoded.replayGainPreampDb, 0.0f)
    assertEquals(12, reencoded.crossfadeSeconds)
    assertEquals(10, reencoded.eqBandsDb.size)
  }

  @Test
  fun winampIni_missingSectionReturnsSafeDefaults() {
    val defaults = parser.parse("")
    assertEquals(PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME, defaults.volume)
    assertEquals(0, defaults.pan)
    assertFalse(defaults.shuffle)
    assertFalse(defaults.repeat)
    assertFalse(defaults.timeRemaining)
    assertTrue(defaults.eqEnabled)
    assertFalse(defaults.eqAuto)
    assertEquals(0f, defaults.eqPreampDb, 0f)
    assertEquals(List(10) { 0f }, defaults.eqBandsDb)
    assertEquals(0, defaults.replayGainMode)
    assertEquals(0f, defaults.replayGainPreampDb, 0f)
    assertEquals(0, defaults.crossfadeSeconds)
  }

  @Test
  fun winampIni_stripsInlineSemicolonAndHashCommentsOnHeadersAndValues() {
    val ini =
      """
      [Winamp] ; main section comment
      volume=111 ; inline semicolon
      pan=-20 # inline hash
      shuffle=1 ; enable shuffle
      repeat=0 # disable repeat
      rg_mode=2 # album mode
      rg_preamp=-1.5 ; preamp
      crossfade=5 # seconds
      eq_data=31,31,31,31,31,31,31,31,31,31,0;no-space comment
      [Other] # end Winamp section
      volume=255
      """.trimIndent()

    val parsed = parser.parse(ini)
    assertEquals(111, parsed.volume)
    assertEquals(-20, parsed.pan)
    assertTrue(parsed.shuffle)
    assertFalse(parsed.repeat)
    assertEquals(2, parsed.replayGainMode)
    assertEquals(-1.5f, parsed.replayGainPreampDb, 0.001f)
    assertEquals(5, parsed.crossfadeSeconds)
    parsed.eqBandsDb.forEach { assertEquals(0.0f, it, 0.0f) }
    assertEquals(12.0f, parsed.eqPreampDb, 0.0f)
  }

  @Test
  fun winampIni_parseEqDataDistinguishesOutOfRangeByteTokensFromSignedDbValues() {
    val byteWithNegativeOutOfRange =
      """
      [Winamp]
      eq_data=-999,31,31,31,31,31,31,31,31,31,31
      """.trimIndent()
    val parsedNegByte = parser.parse(byteWithNegativeOutOfRange)
    assertEquals(12.0f, parsedNegByte.eqBandsDb[0], 0.0f)
    for (i in 1 until 10) {
      assertEquals(0.0f, parsedNegByte.eqBandsDb[i], 0.0f)
    }
    assertEquals(0.0f, parsedNegByte.eqPreampDb, 0.0f)

    val byteWithPlusPrefix =
      """
      [Winamp]
      eq_data=+31,31,31,31,31,31,31,31,31,31,31
      """.trimIndent()
    val parsedPlusByte = parser.parse(byteWithPlusPrefix)
    parsedPlusByte.eqBandsDb.forEach { assertEquals(0.0f, it, 0.0f) }
    assertEquals(0.0f, parsedPlusByte.eqPreampDb, 0.0f)

    val signedIntegerDbList =
      """
      [Winamp]
      eq_data=-3,0,3,6,12,-12,0,0,0,0,-2
      """.trimIndent()
    val parsedSignedDb = parser.parse(signedIntegerDbList)
    assertEquals(-3.0f, parsedSignedDb.eqBandsDb[0], 0.0f)
    assertEquals(0.0f, parsedSignedDb.eqBandsDb[1], 0.0f)
    assertEquals(3.0f, parsedSignedDb.eqBandsDb[2], 0.0f)
    assertEquals(6.0f, parsedSignedDb.eqBandsDb[3], 0.0f)
    assertEquals(12.0f, parsedSignedDb.eqBandsDb[4], 0.0f)
    assertEquals(-12.0f, parsedSignedDb.eqBandsDb[5], 0.0f)
    assertEquals(-2.0f, parsedSignedDb.eqPreampDb, 0.0f)
  }
}
