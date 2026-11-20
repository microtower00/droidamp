package com.droidamp.player.e2e.tier2

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidamp.player.domain.contract.AutoEqPresetResolver
import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.contract.EqfCodec
import com.droidamp.player.domain.contract.PlaylistFileCodec
import com.droidamp.player.domain.contract.Q2Codec
import com.droidamp.player.domain.contract.WinampIniCodec
import com.droidamp.player.domain.contract.WinampIniSnapshot
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.WinampEqSpec
import com.droidamp.player.e2e.fixtures.SyntheticWinampFixtures
import com.droidamp.player.ui.rack.FakeAudioEngineController
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Tier2WinampBoundaryTest {

  private val eqfCodec: EqfCodec by lazy {
    loadContract("com.droidamp.player.interop.BinaryEqfCodec", EqfCodec::class.java)
  }

  private val q2Codec: Q2Codec by lazy {
    loadContract("com.droidamp.player.interop.WinampQ2Codec", Q2Codec::class.java)
  }

  private val autoEqResolver: AutoEqPresetResolver by lazy {
    loadContract("com.droidamp.player.interop.AutoEqMatcher", AutoEqPresetResolver::class.java)
  }

  private val winampIniCodec: WinampIniCodec by lazy {
    loadContract("com.droidamp.player.interop.WinampIniParser", WinampIniCodec::class.java)
  }

  private val playlistCodec: PlaylistFileCodec by lazy {
    loadContract("com.droidamp.player.interop.WinampPlaylistCodec", PlaylistFileCodec::class.java)
  }

  private fun <T : Any> loadContract(implClassName: String, contractClass: Class<T>): T {
    val clazz = Class.forName(implClassName)
    val instance =
      runCatching { clazz.getField("INSTANCE").get(null) }
        .getOrElse { clazz.getDeclaredConstructor().newInstance() }
    return contractClass.cast(instance)
      ?: error("$implClassName does not implement ${contractClass.name}")
  }

  private fun assertThrowsIllegalArgument(block: () -> Unit) {
    try {
      block()
      fail("Expected IllegalArgumentException")
    } catch (_: IllegalArgumentException) {
      // expected
    }
  }

  private fun sampleTrack(
    id: String = "t1",
    uriString: String = "",
    title: String = "Track",
    artist: String = "Artist",
    fileName: String = "track.mp3",
    folderPath: String = "Music",
  ): AudioTrack =
    AudioTrack(
      id = id,
      uriString = uriString,
      title = title,
      artist = artist,
      album = "Album",
      fileName = fileName,
      folderPath = folderPath,
      durationMs = 120_000L,
      bitrateKbps = 192,
      sampleRateHz = 44_100,
      channelMode = ChannelMode.STEREO,
    )

  // =========================================================================
  // Feature 1: .eqf Binary Codec Boundaries — 5 Tier 2 Tests
  // =========================================================================

  @Test // T2-F1-1
  fun eqf_rejectsTruncatedEmptyAndOversizedByteArrays() {
    assertThrowsIllegalArgument { eqfCodec.parseSingleEqf(ByteArray(0)) }
    assertThrowsIllegalArgument { eqfCodec.parseSingleEqf(SyntheticWinampFixtures.truncatedEqf(298)) }
    assertThrowsIllegalArgument { eqfCodec.parseSingleEqf(SyntheticWinampFixtures.oversizedEqf(1)) }
  }

  @Test // T2-F1-2
  fun eqf_rejectsCorruptedHeaderSignature() {
    assertThrowsIllegalArgument { eqfCodec.parseSingleEqf(SyntheticWinampFixtures.badHeaderEqf()) }

    val flipped = SyntheticWinampFixtures.eqfBytes(ClassicWinampPresets.ROCK)
    flipped[30] = (flipped[30].toInt() xor 0x01).toByte()
    assertThrowsIllegalArgument { eqfCodec.parseSingleEqf(flipped) }
  }

  @Test // T2-F1-3
  fun eqf_rejectsOutOfRangeBandAndPreampBytes64To255() {
    assertThrowsIllegalArgument { eqfCodec.parseSingleEqf(SyntheticWinampFixtures.outOfRangeBandEqf(0, 64)) }
    assertThrowsIllegalArgument { eqfCodec.parseSingleEqf(SyntheticWinampFixtures.outOfRangeBandEqf(9, 128)) }
    assertThrowsIllegalArgument { eqfCodec.parseSingleEqf(SyntheticWinampFixtures.outOfRangePreampEqf(255)) }
  }

  @Test // T2-F1-4
  fun eqf_clampsExtremeAndNanDbValuesAndPreservesNulTerminatedUtf8Name() {
    val longUtf8Name = "É".repeat(160) // 320 UTF-8 bytes > 256 limit
    val extremePreset = EqPreset(
      name = longUtf8Name,
      preampDb = Float.NaN,
      bandsDb = listOf(
        50f,
        -50f,
        Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY,
        Float.NaN,
        12.5f,
        -12.5f,
        0f,
        12f,
        -12f,
      ),
    )

    val encoded = eqfCodec.encodeSingleEqf(extremePreset)
    assertEquals(SyntheticWinampFixtures.EQF_RECORD_SIZE, encoded.size)
    assertEquals("Byte 287 must remain NUL terminator", 0, encoded[287].toInt())

    val parsed = eqfCodec.parseSingleEqf(encoded)
    assertTrue("Parsed UTF-8 name must be non-empty", parsed.name.isNotEmpty())
    assertTrue("Parsed UTF-8 byte length must be <= 256", parsed.name.toByteArray(Charsets.UTF_8).size <= 256)
    assertFalse("Truncated UTF-8 name must not contain replacement char", parsed.name.contains('\uFFFD'))
    assertEquals(0f, parsed.preampDb, 0f)
    assertEquals(12f, parsed.bandsDb[0], 0f)
    assertEquals(-12f, parsed.bandsDb[1], 0f)
    assertEquals(12f, parsed.bandsDb[2], 0f)
    assertEquals(-12f, parsed.bandsDb[3], 0f)
    assertEquals(0f, parsed.bandsDb[4], 0f)
  }

  @Test // T2-F1-5
  fun eqf_parsesEmbeddedNulTerminatorAndTrimsWhitespaceInName() {
    val raw = SyntheticWinampFixtures.eqfRawBytes(
      name = "placeholder",
      bandBytes0To63 = List(10) { 31 },
      preampByte0To63 = 31,
    )
    // Overwrite name field (31..287) with "  Rock\u0000Garbage  "
    for (i in SyntheticWinampFixtures.EQF_HEADER_SIZE until (SyntheticWinampFixtures.EQF_HEADER_SIZE + SyntheticWinampFixtures.EQF_NAME_FIELD_SIZE)) {
      raw[i] = 0
    }
    val payload = "  Rock\u0000Garbage  ".toByteArray(Charsets.UTF_8)
    payload.copyInto(raw, destinationOffset = SyntheticWinampFixtures.EQF_HEADER_SIZE)

    val parsed = eqfCodec.parseSingleEqf(raw)
    assertEquals("Rock", parsed.name)
  }

  // =========================================================================
  // Feature 2: winamp.q1 Binary Library Boundaries — 5 Tier 2 Tests
  // =========================================================================

  @Test // T2-F2-1
  fun q1_handlesEmptyZeroBytesAndHeaderOnly31Bytes() {
    val encodedEmpty = eqfCodec.encodePresetLibraryQ1(emptyList())
    assertEquals(0, encodedEmpty.size)
    assertEquals(emptyList<EqPreset>(), eqfCodec.parsePresetLibraryQ1(ByteArray(0)))
    assertEquals(emptyList<EqPreset>(), eqfCodec.parsePresetLibraryQ1(SyntheticWinampFixtures.eqfHeaderBytes()))
  }

  @Test // T2-F2-2
  fun q1_disambiguates80431ByteBoundary_269ConcatenatedVs300Classic() {
    val concatPresets = (1..269).map { i -> EqPreset("C_$i", 0f, List(10) { 0f }) }
    val classicPresets = (1..300).map { i -> EqPreset("S_$i", 0f, List(10) { 0f }) }

    val concatBytes = SyntheticWinampFixtures.q1Bytes(concatPresets)
    val classicBytes = SyntheticWinampFixtures.q1ClassicSingleHeaderBytes(classicPresets)
    assertEquals(80_431, concatBytes.size)
    assertEquals(80_431, classicBytes.size)

    val parsedConcat = eqfCodec.parsePresetLibraryQ1(concatBytes)
    val parsedClassic = eqfCodec.parsePresetLibraryQ1(classicBytes)
    assertEquals(269, parsedConcat.size)
    assertEquals(300, parsedClassic.size)
    assertEquals("C_1", parsedConcat.first().name)
    assertEquals("C_269", parsedConcat.last().name)
    assertEquals("S_1", parsedClassic.first().name)
    assertEquals("S_300", parsedClassic.last().name)
  }

  @Test // T2-F2-3
  fun q1_rejectsInvalidSizesAndShortSubHeaderInputs() {
    assertThrowsIllegalArgument { eqfCodec.parsePresetLibraryQ1(ByteArray(30)) }
    assertThrowsIllegalArgument { eqfCodec.parsePresetLibraryQ1(SyntheticWinampFixtures.q1InvalidSizeBytes(extraBytes = 7)) }

    val badHeader = SyntheticWinampFixtures.q1Bytes(listOf(ClassicWinampPresets.FLAT))
    badHeader[0] = 'X'.code.toByte()
    assertThrowsIllegalArgument { eqfCodec.parsePresetLibraryQ1(badHeader) }
  }

  @Test // T2-F2-4
  fun q1_rejectsCorruptedSecondRecordHeaderInConcatenatedStream() {
    val twoRecords = SyntheticWinampFixtures.q1Bytes(listOf(ClassicWinampPresets.FLAT, ClassicWinampPresets.ROCK))
    twoRecords[SyntheticWinampFixtures.EQF_RECORD_SIZE + 1] = 'Z'.code.toByte()
    assertThrowsIllegalArgument { eqfCodec.parsePresetLibraryQ1(twoRecords) }
  }

  @Test // T2-F2-5
  fun q1_rejectsOutOfRangeGainByteInLateRecord() {
    val threeRecords = SyntheticWinampFixtures.q1Bytes(
      listOf(ClassicWinampPresets.FLAT, ClassicWinampPresets.ROCK, ClassicWinampPresets.POP),
    )
    // Record index 2 preamp byte is the last byte of the 3-record stream
    threeRecords[threeRecords.lastIndex] = 64.toByte()
    assertThrowsIllegalArgument { eqfCodec.parsePresetLibraryQ1(threeRecords) }
  }

  // =========================================================================
  // Feature 3: winamp.q2 & AUTO Matcher Boundaries — 5 Tier 2 Tests
  // =========================================================================

  @Test // T2-F3-1
  fun q2_handlesEmptyAndHeaderOnlyAndRejectsBlankEncodePattern() {
    assertEquals(0, q2Codec.encodeAutoPresetsQ2(emptyList()).size)
    assertEquals(emptyList<AutoEqRule>(), q2Codec.parseAutoPresetsQ2(ByteArray(0)))
    assertEquals(emptyList<AutoEqRule>(), q2Codec.parseAutoPresetsQ2(SyntheticWinampFixtures.eqfHeaderBytes()))

    assertThrowsIllegalArgument {
      q2Codec.encodeAutoPresetsQ2(listOf(AutoEqRule("   ", ClassicWinampPresets.FLAT)))
    }
  }

  @Test // T2-F3-2
  fun q2_rejectsCorruptHeaderInvalidSizeAndOutOfRangeGainByte() {
    assertThrowsIllegalArgument { q2Codec.parseAutoPresetsQ2(ByteArray(SyntheticWinampFixtures.EQF_RECORD_SIZE)) }
    assertThrowsIllegalArgument { q2Codec.parseAutoPresetsQ2(SyntheticWinampFixtures.q1InvalidSizeBytes()) }

    val badQ2 = SyntheticWinampFixtures.q2Bytes(
      listOf(SyntheticWinampFixtures.Q2RuleSpec("Electronic", ClassicWinampPresets.TECHNO)),
    )
    badQ2[SyntheticWinampFixtures.EQF_HEADER_SIZE + SyntheticWinampFixtures.EQF_NAME_FIELD_SIZE] = 99.toByte()
    assertThrowsIllegalArgument { q2Codec.parseAutoPresetsQ2(badQ2) }
  }

  @Test // T2-F3-3
  fun autoEq_ignoresBlankPatternsAndAvoidsSubstringTitleFalsePositives() {
    val track = sampleTrack(
      uriString = "file:///Music/PopBallads/track.mp3",
      title = "Rock With You",
      artist = "Michael",
      fileName = "track.mp3",
      folderPath = "Music/PopBallads",
    )
    val rules = listOf(
      AutoEqRule("   ", ClassicWinampPresets.FLAT),
      AutoEqRule("ROCK", ClassicWinampPresets.ROCK),
    )
    assertNull(
      "Rule 'ROCK' must not falsely match title 'Rock With You' or folder 'PopBallads'",
      autoEqResolver.resolveForTrack(track, rules, ClassicWinampPresets.ALL),
    )
  }

  @Test // T2-F3-4
  fun autoEq_blocksFlatAndCustomFolderLeafFallbackAndHandlesUrlEncodedSafUris() {
    val flatTrack = sampleTrack(folderPath = "Music/FLAT", fileName = "a.mp3", title = "A")
    val customTrack = sampleTrack(folderPath = "Music/CUSTOM", fileName = "b.mp3", title = "B")
    val customPreset = EqPreset("CUSTOM", 3f, List(10) { 3f })
    val presetsWithCustom = ClassicWinampPresets.ALL + customPreset

    assertNull(autoEqResolver.resolveForTrack(flatTrack, emptyList(), presetsWithCustom))
    assertNull(autoEqResolver.resolveForTrack(customTrack, emptyList(), presetsWithCustom))

    val safEncodedTrack = sampleTrack(
      uriString = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FRock%2Fsong.mp3",
      folderPath = "",
      fileName = "song.mp3",
      title = "Untitled",
    )
    val safRules = listOf(AutoEqRule("Rock/song.mp3", ClassicWinampPresets.ROCK))
    assertEquals(
      ClassicWinampPresets.ROCK,
      autoEqResolver.resolveForTrack(safEncodedTrack, safRules, emptyList()),
    )
  }

  @Test // T2-F3-5
  fun autoEq_preservesLiteralBackslashesAndDoubleSlashesInTitles_andWindowsDoubleSlashes() {
    val acdcTrack = sampleTrack(
      uriString = "file:///Unknown/01.mp3",
      folderPath = "Unknown",
      fileName = "01.mp3",
      title = "AC\\DC",
      artist = "",
    )
    val doubleSlashTitleTrack = sampleTrack(
      uriString = "file:///Unknown/02.mp3",
      folderPath = "Unknown",
      fileName = "02.mp3",
      title = "Track // 1",
      artist = "",
    )
    val winDoubleSlashTrack = sampleTrack(
      uriString = "file:///C:/Music/Rock/03.mp3",
      folderPath = "C:\\Music\\\\Rock///",
      fileName = "03.mp3",
      title = "Song",
      artist = "Band",
    )

    val pAcDc = EqPreset("ACDC", 1f, List(10) { 1f })
    val pDouble = EqPreset("DOUBLE", 2f, List(10) { 2f })
    val pWin = EqPreset("WIN", 3f, List(10) { 3f })

    assertEquals(
      "ACDC",
      autoEqResolver.resolveForTrack(acdcTrack, listOf(AutoEqRule("AC\\DC", pAcDc)), emptyList())?.name,
    )
    assertEquals(
      "DOUBLE",
      autoEqResolver.resolveForTrack(doubleSlashTitleTrack, listOf(AutoEqRule("Track // 1", pDouble)), emptyList())?.name,
    )
    assertEquals(
      "WIN",
      autoEqResolver.resolveForTrack(winDoubleSlashTrack, listOf(AutoEqRule("C:/Music/Rock", pWin)), emptyList())?.name,
    )
  }

  // =========================================================================
  // Feature 4: winamp.ini Text Codec Boundaries — 5 Tier 2 Tests
  // =========================================================================

  @Test // T2-F4-1
  fun winampIni_emptyOrMissingSection_returnsExactSafeDefaults() {
    val emptyParsed = winampIniCodec.parse("")
    val otherSectionParsed = winampIniCodec.parse("[Other]\nvolume=10\npan=127\n")

    listOf(emptyParsed, otherSectionParsed).forEach { parsed ->
      assertEquals(204, parsed.volume)
      assertEquals(0, parsed.pan)
      assertFalse(parsed.shuffle)
      assertFalse(parsed.repeat)
      assertFalse(parsed.timeRemaining)
      assertTrue(parsed.eqEnabled)
      assertFalse(parsed.eqAuto)
      assertEquals(0f, parsed.eqPreampDb, 0f)
      assertEquals(List(10) { 0f }, parsed.eqBandsDb)
      assertEquals(0, parsed.replayGainMode)
      assertEquals(0f, parsed.replayGainPreampDb, 0f)
      assertEquals(0, parsed.crossfadeSeconds)
    }
  }

  @Test // T2-F4-2
  fun winampIni_clampsAllOutOfRangeFieldsOnParseAndEncode() {
    val parsed = winampIniCodec.parse(SyntheticWinampFixtures.outOfRangeWinampIni())
    assertEquals(255, parsed.volume)
    assertEquals(-127, parsed.pan)
    assertTrue(parsed.shuffle)
    assertFalse(parsed.repeat)
    assertEquals(2, parsed.replayGainMode)
    assertEquals(12f, parsed.replayGainPreampDb, 0.001f)
    assertEquals(12, parsed.crossfadeSeconds)
    assertEquals(12f, parsed.eqBandsDb[1], 0f)
    assertEquals(-12f, parsed.eqBandsDb[2], 0f)
    assertEquals(-12f, parsed.eqPreampDb, 0f)

    val extremeSnapshot = WinampIniSnapshot(
      volume = -50,
      pan = 999,
      shuffle = false,
      repeat = false,
      timeRemaining = false,
      eqEnabled = true,
      eqAuto = false,
      eqPreampDb = -99f,
      eqBandsDb = listOf(99f, -99f, 0f), // short list (< 10)
      replayGainMode = -4,
      replayGainPreampDb = -40f,
      crossfadeSeconds = 99,
    )
    val reParsed = winampIniCodec.parse(winampIniCodec.encode(extremeSnapshot))
    assertEquals(0, reParsed.volume)
    assertEquals(127, reParsed.pan)
    assertEquals(0, reParsed.replayGainMode)
    assertEquals(-12f, reParsed.replayGainPreampDb, 0.001f)
    assertEquals(12, reParsed.crossfadeSeconds)
    assertEquals(10, reParsed.eqBandsDb.size)
    assertEquals(12f, reParsed.eqBandsDb[0], 0f)
    assertEquals(-12f, reParsed.eqBandsDb[1], 0f)
    assertEquals(0f, reParsed.eqBandsDb[9], 0f)
    assertEquals(-12f, reParsed.eqPreampDb, 0f)
  }

  @Test // T2-F4-3
  fun winampIni_eqDataDisambiguatesOutOfRangeBytesFromSignedDbFloats() {
    val oorByteIni = "[Winamp]\neq_data=-999,31,31,31,31,31,31,31,31,31,31\n"
    val oorParsed = winampIniCodec.parse(oorByteIni)
    assertEquals(12f, oorParsed.eqBandsDb[0], 0f)

    val plusSignedZeroIni = "[Winamp]\neq_data=+31,31,31,31,31,31,31,31,31,31,31\n"
    val plusSignedParsed = winampIniCodec.parse(plusSignedZeroIni)
    assertEquals(0f, plusSignedParsed.eqBandsDb[0], 0f)

    val signedDbListIni = "[Winamp]\neq_data=-3,0,3,6,12,-12,0,0,0,0,-2\n"
    val signedDbParsed = winampIniCodec.parse(signedDbListIni)
    assertEquals(-3f, signedDbParsed.eqBandsDb[0], 0.01f)
    assertEquals(12f, signedDbParsed.eqBandsDb[4], 0.01f)
    assertEquals(-12f, signedDbParsed.eqBandsDb[5], 0.01f)
    assertEquals(-2f, signedDbParsed.eqPreampDb, 0.01f)

    val floatDbIni = "[Winamp]\neq_data=1.5,-1.5,20.0,-20.0,0,0,0,0,0,0,3.5\n"
    val floatParsed = winampIniCodec.parse(floatDbIni)
    assertEquals(1.5f, floatParsed.eqBandsDb[0], 0.01f)
    assertEquals(-1.5f, floatParsed.eqBandsDb[1], 0.01f)
    assertEquals(12f, floatParsed.eqBandsDb[2], 0.01f)
    assertEquals(-12f, floatParsed.eqBandsDb[3], 0.01f)
    assertEquals(3.5f, floatParsed.eqPreampDb, 0.01f)
  }

  @Test // T2-F4-4
  fun winampIni_shortOrMalformedEqDataFallsBackToFlatZeroDb() {
    val shortEq = winampIniCodec.parse("[Winamp]\neq_data=10,20,30\n")
    val nonNumericEq = winampIniCodec.parse("[Winamp]\neq_data=abc,def,31,31,31,31,31,31,31,31,31\n")

    listOf(shortEq, nonNumericEq).forEach { parsed ->
      assertEquals(0f, parsed.eqPreampDb, 0f)
      assertEquals(List(10) { 0f }, parsed.eqBandsDb)
    }
  }

  @Test // T2-F4-5
  fun winampIni_enforcesLocaleUsDecimalPointAndStripsInlineNoSpaceComments() {
    val previousLocale = Locale.getDefault()
    try {
      Locale.setDefault(Locale.ITALY)
      val snapshot = WinampIniSnapshot(
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
        replayGainPreampDb = -4.25f,
        crossfadeSeconds = 2,
      )
      val encoded = winampIniCodec.encode(snapshot)
      assertTrue("Expected '.' decimal point under Locale.ITALY:\n$encoded", encoded.contains("rg_preamp=-4.25"))
      assertFalse(encoded.contains("rg_preamp=-4,25"))

      val withInlineComments =
        """
        [Winamp];header comment
        volume=150;no-space inline comment
        pan=-20#hash inline comment
        rg_preamp=-4.25;inline
        """.trimIndent()
      val parsed = winampIniCodec.parse(withInlineComments)
      assertEquals(150, parsed.volume)
      assertEquals(-20, parsed.pan)
      assertEquals(-4.25f, parsed.replayGainPreampDb, 0.01f)
    } finally {
      Locale.setDefault(previousLocale)
    }
  }

  // =========================================================================
  // Feature 5: .m3u / .m3u8 / .pls Boundaries — 5 Tier 2 Tests
  // =========================================================================

  @Test // T2-F5-1
  fun playlist_emptyQueuesEncodeValidHeadersAndParseToEmpty() {
    val m3u8 = playlistCodec.encodeM3u8(emptyList())
    assertEquals("#EXTM3U\n", m3u8)
    assertEquals(emptyList<String>(), playlistCodec.parseM3u(m3u8))
    assertEquals(emptyList<String>(), playlistCodec.parseM3u(""))

    val pls = playlistCodec.encodePls(emptyList())
    assertTrue(pls.contains("[playlist]"))
    assertTrue(pls.contains("NumberOfEntries=0"))
    assertTrue(pls.contains("Version=2"))
    assertEquals(emptyList<String>(), playlistCodec.parsePls(pls))
    assertEquals(emptyList<String>(), playlistCodec.parsePls(""))
  }

  @Test // T2-F5-2
  fun playlist_stripsCrLfInjectionInTrackTitlesAndPaths() {
    val maliciousTracks = listOf(
      sampleTrack(
        id = "1",
        uriString = "file:///Music/good1.mp3\nFile3=evil.mp3",
        title = "Legit\r\n#EXTINF:0,Injected",
        artist = "Artist",
      ),
      sampleTrack(
        id = "2",
        uriString = "file:///Music/good2.mp3",
        title = "Clean",
        artist = "Artist\r\nTitle3=Fake",
      ),
    )

    val m3u8 = playlistCodec.encodeM3u8(maliciousTracks)
    val parsedM3u8 = playlistCodec.parseM3u(m3u8)
    assertEquals(2, parsedM3u8.size)
    assertFalse(parsedM3u8.any { it == "evil.mp3" })
    assertTrue(parsedM3u8.none { it.contains('\r') || it.contains('\n') })
    assertTrue(m3u8.lines().none { it.trimStart().startsWith("#EXTINF:0,Injected") })

    val pls = playlistCodec.encodePls(maliciousTracks)
    val parsedPls = playlistCodec.parsePls(pls)
    assertEquals(2, parsedPls.size)
    assertFalse(parsedPls.any { it == "evil.mp3" })
    assertTrue(parsedPls.none { it.contains('\r') || it.contains('\n') })
    assertTrue(pls.lines().none { it.trimStart().startsWith("File3=") })
    assertTrue(pls.lines().none { it.trimStart().startsWith("Title3=") })
  }

  @Test // T2-F5-3
  fun playlist_resolveEntryPath_clampsDotDotTraversalAtSafWindowsAndPosixRoots() {
    val safColonBase = "content://com.android.externalstorage.documents/tree/primary:Music/document/primary:Music/Sub"
    val safEncodedBase = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FSub"
    val safEncodedColonNoSlashBase = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic"

    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary:Music/document/primary:Music/etc/passwd",
      playlistCodec.resolveEntryPath("../../../../etc/passwd", safColonBase),
    )
    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fetc%2Fpasswd",
      playlistCodec.resolveEntryPath("../../../../etc/passwd", safEncodedBase),
    )
    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fetc%2Fpasswd",
      playlistCodec.resolveEntryPath("../../../../etc/passwd", safEncodedColonNoSlashBase),
    )
    assertEquals("file:///etc/passwd", playlistCodec.resolveEntryPath("../../../../etc/passwd", "file:///Music"))
    assertEquals("/etc/passwd", playlistCodec.resolveEntryPath("../../../../etc/passwd", "/Music"))
    assertEquals("C:/etc/passwd", playlistCodec.resolveEntryPath("../../../../etc/passwd", "C:\\Music"))
  }

  @Test // T2-F5-4
  fun playlist_resolveEntryPath_preservesLeadingDotDotOnEmptyBaseAndHandlesBlankEntry() {
    assertEquals("../Other/a.mp3", playlistCodec.resolveEntryPath("../Other/./a.mp3", ""))
    assertEquals("", playlistCodec.resolveEntryPath("   ", "/sdcard/Music"))
  }

  @Test // T2-F5-5
  fun pls_ignoresFile0NegativeIndicesBlankValuesAndMismatchedDeclaredCount() {
    val raw =
      """
      [playlist]
      File0=bad_zero.mp3
      File-1=bad_negative.mp3
      File1=   
      File2=ok.mp3
      NumberOfEntries=99
      Version=2
      """.trimIndent()

    val parsed = playlistCodec.parsePls(raw)
    assertEquals(listOf("ok.mp3"), parsed)
  }

  // =========================================================================
  // Feature 6: Classic Winamp Presets Boundaries — 5 Tier 2 Tests
  // =========================================================================

  @Test // T2-F6-1
  fun classicPresets_constructorRejectsWrongBandCount9Or11() {
    assertThrowsIllegalArgument { EqPreset("Bad9", 0f, List(9) { 0f }) }
    assertThrowsIllegalArgument { EqPreset("Bad11", 0f, List(11) { 0f }) }
  }

  @Test // T2-F6-2
  fun classicPresets_equalizerStateRejectsWrongBandCount9Or11() {
    assertThrowsIllegalArgument { EqualizerState(bandsDb = List(9) { 0f }) }
    assertThrowsIllegalArgument { EqualizerState(bandsDb = List(11) { 0f }) }
  }

  @Test // T2-F6-3
  fun classicPresets_outOfRangePresetClampedByAudioEngineController() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    try {
      val engine = FakeAudioEngineController(scope = scope, enableSimulationTicker = false)
      runBlocking {
        engine.applyEqPreset(EqPreset("Hot", 30f, List(10) { -25f }))
      }
      val eq = engine.playbackState.value.equalizer
      assertEquals("Hot", eq.activePresetName)
      assertEquals(12f, eq.preampDb, 0f)
      eq.bandsDb.forEach { db ->
        assertEquals(-12f, db, 0f)
      }
    } finally {
      scope.cancel()
    }
  }

  @Test // T2-F6-4
  fun classicPresets_all18NamesAreAllCapsAndDistinctCaseInsensitively() {
    val all = ClassicWinampPresets.ALL
    assertEquals(18, all.size)
    all.forEach { preset ->
      assertEquals(preset.name.uppercase(Locale.ROOT), preset.name)
    }
    assertEquals(18, all.map { it.name.uppercase(Locale.ROOT) }.toSet().size)
  }

  @Test // T2-F6-5
  fun classicPresets_fullBassFullTrebleAndFullBassTrebleHaveNegative1DbPreamp() {
    assertEquals(-1.0f, ClassicWinampPresets.FULL_BASS.preampDb, 0f)
    assertEquals(-1.0f, ClassicWinampPresets.FULL_TREBLE.preampDb, 0f)
    assertEquals(-1.0f, ClassicWinampPresets.FULL_BASS_TREBLE.preampDb, 0f)
    assertEquals(
      listOf(5.8f, 5.8f, 5.8f, 3.4f, 1.0f, -2.4f, -4.8f, -6.2f, -6.8f, -6.8f),
      ClassicWinampPresets.FULL_BASS.bandsDb,
    )
    assertEquals(
      listOf(-5.8f, -5.8f, -5.8f, -2.4f, 1.4f, 6.8f, 9.6f, 9.6f, 9.6f, 10.2f),
      ClassicWinampPresets.FULL_TREBLE.bandsDb,
    )
    assertEquals(
      listOf(4.4f, 3.6f, 0f, -4.4f, -2.8f, 1.0f, 5.0f, 6.8f, 7.4f, 7.4f),
      ClassicWinampPresets.FULL_BASS_TREBLE.bandsDb,
    )
  }
}
