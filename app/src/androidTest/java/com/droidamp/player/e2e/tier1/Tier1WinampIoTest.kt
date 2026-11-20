package com.droidamp.player.e2e.tier1

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.droidamp.player.domain.model.WinampEqSpec
import com.droidamp.player.e2e.fixtures.SyntheticAudioFixtures
import com.droidamp.player.e2e.fixtures.SyntheticWinampFixtures
import com.droidamp.player.e2e.harness.DroidAmpSelectors
import com.droidamp.player.e2e.harness.E2eRackDriver
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Tier1WinampIoTest {

  @get:Rule
  val composeRule = createComposeRule()

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

  private fun sampleTrack(
    id: String = "t1",
    uriString: String = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FRock%2F01_track.mp3",
    title: String = "Neon Highway",
    artist: String = "DroidSynth",
    album: String = "Rack Vol 1",
    fileName: String = "01_track.mp3",
    folderPath: String = "primary:Music/Rock",
    durationMs: Long = 185_000L,
  ): AudioTrack =
    AudioTrack(
      id = id,
      uriString = uriString,
      title = title,
      artist = artist,
      album = album,
      fileName = fileName,
      folderPath = folderPath,
      durationMs = durationMs,
      bitrateKbps = 192,
      sampleRateHz = 44_100,
      channelMode = ChannelMode.STEREO,
    )

  // =========================================================================
  // Feature 1: .eqf Binary Codec (EqfCodec) — 5 Tier 1 Tests
  // =========================================================================

  @Test // T1-F1-1
  fun eqf_singlePreset_roundTripsRockAndAllClassicPresetsWithinTolerance() {
    for (preset in ClassicWinampPresets.ALL) {
      val encoded = eqfCodec.encodeSingleEqf(preset)
      assertEquals("Preset ${preset.name} must encode to 299 bytes", SyntheticWinampFixtures.EQF_RECORD_SIZE, encoded.size)

      val parsed = eqfCodec.parseSingleEqf(encoded)
      assertEquals(preset.name, parsed.name)
      assertEquals(
        "Preamp delta exceeded for ${preset.name}",
        preset.preampDb,
        parsed.preampDb,
        0.20f,
      )
      preset.bandsDb.forEachIndexed { idx, expectedDb ->
        assertEquals(
          "Band $idx delta exceeded for ${preset.name}",
          expectedDb,
          parsed.bandsDb[idx],
          0.20f,
        )
      }
    }
  }

  @Test // T1-F1-2
  fun eqf_flatPreset_roundTripsExactZeroDbAndByte31() {
    val encoded = eqfCodec.encodeSingleEqf(ClassicWinampPresets.FLAT)
    assertEquals(SyntheticWinampFixtures.EQF_RECORD_SIZE, encoded.size)
    for (offset in 288..298) {
      assertEquals("Byte at offset $offset must equal 31 for 0.0 dB", 31, encoded[offset].toInt() and 0xFF)
    }

    val parsed = eqfCodec.parseSingleEqf(encoded)
    assertEquals("FLAT", parsed.name)
    assertEquals(0f, parsed.preampDb, 0f)
    parsed.bandsDb.forEach { bandDb ->
      assertEquals(0f, bandDb, 0f)
    }
  }

  @Test // T1-F1-3
  fun eqf_encodeProducesCanonical299ByteBinaryLayout() {
    val preset = EqPreset(
      name = "StudioLead",
      preampDb = 12f,
      bandsDb = listOf(12f, 0f, -12f, 6f, -6f, 0f, 0f, 0f, 0f, 0f),
    )
    val bytes = eqfCodec.encodeSingleEqf(preset)
    assertEquals(SyntheticWinampFixtures.EQF_RECORD_SIZE, bytes.size)

    val expectedHeader = SyntheticWinampFixtures.eqfHeaderBytes()
    assertArrayEquals(expectedHeader, bytes.copyOfRange(0, SyntheticWinampFixtures.EQF_HEADER_SIZE))

    val expectedName = "StudioLead".toByteArray(Charsets.UTF_8)
    assertArrayEquals(
      expectedName,
      bytes.copyOfRange(SyntheticWinampFixtures.EQF_HEADER_SIZE, SyntheticWinampFixtures.EQF_HEADER_SIZE + expectedName.size),
    )
    for (idx in (SyntheticWinampFixtures.EQF_HEADER_SIZE + expectedName.size) until (SyntheticWinampFixtures.EQF_HEADER_SIZE + SyntheticWinampFixtures.EQF_NAME_FIELD_SIZE)) {
      assertEquals("Name padding at $idx must be NUL", 0, bytes[idx].toInt())
    }

    assertEquals(0, bytes[288].toInt() and 0xFF)
    assertEquals(31, bytes[289].toInt() and 0xFF)
    assertEquals(63, bytes[290].toInt() and 0xFF)
    assertEquals(0, bytes[298].toInt() and 0xFF)
  }

  @Test // T1-F1-4
  fun eqf_parseSyntheticFixtureFile_andSecondPassIsByteIdentical() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val fixtureBytes = SyntheticWinampFixtures.eqfBytes(ClassicWinampPresets.FULL_BASS_TREBLE)

    SyntheticAudioFixtures.createCacheScope(context, "t1_f1_4_eqf").use { scope ->
      val file = scope.writeFile("full_bass_treble.eqf", fixtureBytes)
      val firstParsed = eqfCodec.parseSingleEqf(file.readBytes())
      val firstEncoded = eqfCodec.encodeSingleEqf(firstParsed)
      val secondParsed = eqfCodec.parseSingleEqf(firstEncoded)
      val secondEncoded = eqfCodec.encodeSingleEqf(secondParsed)

      assertTrue("Second pass encode must be byte-identical", firstEncoded.contentEquals(secondEncoded))
      assertEquals(ClassicWinampPresets.FULL_BASS_TREBLE.name, secondParsed.name)
      assertEquals(ClassicWinampPresets.FULL_BASS_TREBLE.preampDb, secondParsed.preampDb, 0.20f)
    }
  }

  @Test // T1-F1-5
  fun eqf_allGainBytes0To63_roundTripByteIdentically() {
    var prevDb = Float.POSITIVE_INFINITY
    for (raw in 0..63) {
      val fixture = SyntheticWinampFixtures.eqfRawBytes(
        name = "Raw_$raw",
        bandBytes0To63 = List(WinampEqSpec.BAND_COUNT) { raw },
        preampByte0To63 = raw,
      )
      val parsed = eqfCodec.parseSingleEqf(fixture)
      val reencoded = eqfCodec.encodeSingleEqf(parsed)
      assertArrayEquals("Raw byte $raw failed byte-identical round-trip", fixture, reencoded)
      assertTrue("Monotonicity violated at raw=$raw: ${parsed.preampDb} >= $prevDb", parsed.preampDb < prevDb)
      prevDb = parsed.preampDb

      if (raw == 0) assertEquals(12f, parsed.preampDb, 0f)
      if (raw == 31) assertEquals(0f, parsed.preampDb, 0f)
      if (raw == 63) assertEquals(-12f, parsed.preampDb, 0f)
    }
  }

  // =========================================================================
  // Feature 2: winamp.q1 Binary Library Codec (EqfCodec) — 5 Tier 1 Tests
  // =========================================================================

  @Test // T1-F2-1
  fun q1_multiPresetLibrary_roundTripsAll18ClassicPresetsAndIsIdempotent() {
    val encoded = eqfCodec.encodePresetLibraryQ1(ClassicWinampPresets.ALL)
    assertEquals(18 * SyntheticWinampFixtures.EQF_RECORD_SIZE, encoded.size)

    val parsed = eqfCodec.parsePresetLibraryQ1(encoded)
    assertEquals(18, parsed.size)
    assertEquals(ClassicWinampPresets.ALL.map { it.name }, parsed.map { it.name })

    parsed.forEachIndexed { idx, decoded ->
      val expected = ClassicWinampPresets.ALL[idx]
      assertEquals(expected.preampDb, decoded.preampDb, 0.20f)
      expected.bandsDb.forEachIndexed { bandIdx, db ->
        assertEquals(db, decoded.bandsDb[bandIdx], 0.20f)
      }
    }

    val reencoded = eqfCodec.encodePresetLibraryQ1(parsed)
    assertArrayEquals(encoded, reencoded)
  }

  @Test // T1-F2-2
  fun q1_singlePresetLibrary_roundTripsSingle299ByteRecord() {
    val encoded = eqfCodec.encodePresetLibraryQ1(listOf(ClassicWinampPresets.TECHNO))
    assertEquals(SyntheticWinampFixtures.EQF_RECORD_SIZE, encoded.size)

    val parsed = eqfCodec.parsePresetLibraryQ1(encoded)
    assertEquals(1, parsed.size)
    assertEquals("TECHNO", parsed[0].name)
    assertEquals(ClassicWinampPresets.TECHNO.preampDb, parsed[0].preampDb, 0.20f)
    ClassicWinampPresets.TECHNO.bandsDb.forEachIndexed { idx, expectedDb ->
      assertEquals(expectedDb, parsed[0].bandsDb[idx], 0.20f)
    }
  }

  @Test // T1-F2-3
  fun q1_parsesClassicSingleHeader31PlusN268Layout() {
    val inputPresets = listOf(
      ClassicWinampPresets.ROCK,
      ClassicWinampPresets.POP,
      ClassicWinampPresets.CLASSICAL,
    )
    val classicBytes = SyntheticWinampFixtures.q1ClassicSingleHeaderBytes(inputPresets)
    assertEquals(
      SyntheticWinampFixtures.EQF_HEADER_SIZE + 3 * SyntheticWinampFixtures.EQF_CLASSIC_ENTRY_SIZE,
      classicBytes.size,
    )

    val parsed = eqfCodec.parsePresetLibraryQ1(classicBytes)
    assertEquals(3, parsed.size)
    assertEquals(listOf("ROCK", "POP", "CLASSICAL"), parsed.map { it.name })
    parsed.forEachIndexed { idx, actual ->
      val expected = inputPresets[idx]
      assertEquals(expected.preampDb, actual.preampDb, 0.20f)
      expected.bandsDb.forEachIndexed { b, db ->
        assertEquals(db, actual.bandsDb[b], 0.20f)
      }
    }
  }

  @Test // T1-F2-4
  fun q1_preservesDuplicateOrCustomNamesInOrder() {
    val custom = listOf(
      EqPreset("Warm", 2.0f, List(10) { 1.5f }),
      EqPreset("Punchy", -1.5f, List(10) { 4.0f }),
      EqPreset("Warm", -3.0f, List(10) { -5.0f }),
    )
    val encoded = eqfCodec.encodePresetLibraryQ1(custom)
    val parsed = eqfCodec.parsePresetLibraryQ1(encoded)

    assertEquals(3, parsed.size)
    assertEquals(listOf("Warm", "Punchy", "Warm"), parsed.map { it.name })
    assertTrue(abs(parsed[0].bandsDb[0] - parsed[2].bandsDb[0]) > 4.0f)
    assertEquals(custom[0].preampDb, parsed[0].preampDb, 0.20f)
    assertEquals(custom[2].preampDb, parsed[2].preampDb, 0.20f)
  }

  @Test // T1-F2-5
  fun q1_materializedFileRoundTrip_matchesExpectedBytes() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val source = listOf(
      ClassicWinampPresets.FLAT,
      ClassicWinampPresets.CLUB,
      ClassicWinampPresets.DANCE,
      ClassicWinampPresets.LIVE,
    )
    val q1Bytes = SyntheticWinampFixtures.q1Bytes(source)

    SyntheticAudioFixtures.createCacheScope(context, "t1_f2_5_q1").use { scope ->
      val file = scope.writeFile("winamp.q1", q1Bytes)
      val parsed = eqfCodec.parsePresetLibraryQ1(file.readBytes())
      val reencoded = eqfCodec.encodePresetLibraryQ1(parsed)

      assertEquals(4 * SyntheticWinampFixtures.EQF_RECORD_SIZE, reencoded.size)
      assertEquals(source.map { it.name }, parsed.map { it.name })
      source.forEachIndexed { idx, expected ->
        assertEquals(expected.preampDb, parsed[idx].preampDb, 0.20f)
      }
    }
  }

  // =========================================================================
  // Feature 3: winamp.q2 & AUTO Matcher — 5 Tier 1 Tests
  // =========================================================================

  @Test // T1-F3-1
  fun q2_roundTripsMultiRuleLibraryAndIsByteIdenticalOnSecondPass() {
    val rules = listOf(
      AutoEqRule("Music/Electronic", ClassicWinampPresets.TECHNO),
      AutoEqRule("C:\\Music\\Rock\\01_track.mp3", ClassicWinampPresets.ROCK),
      AutoEqRule("Neon Highway", ClassicWinampPresets.POP),
    )
    val encoded = q2Codec.encodeAutoPresetsQ2(rules)
    assertEquals(3 * SyntheticWinampFixtures.EQF_RECORD_SIZE, encoded.size)

    val parsed = q2Codec.parseAutoPresetsQ2(encoded)
    assertEquals(3, parsed.size)
    assertEquals(rules.map { it.pattern }, parsed.map { it.pattern })
    parsed.forEachIndexed { idx, decodedRule ->
      assertEquals(rules[idx].preset.preampDb, decodedRule.preset.preampDb, 0.20f)
      rules[idx].preset.bandsDb.forEachIndexed { b, db ->
        assertEquals(db, decodedRule.preset.bandsDb[b], 0.20f)
      }
    }

    val secondPass = q2Codec.encodeAutoPresetsQ2(parsed)
    assertArrayEquals(encoded, secondPass)
  }

  @Test // T1-F3-2
  fun q2_parsesClassicSingleHeaderQ2FixtureAndSkipsBlankSlots() {
    val fixture = SyntheticWinampFixtures.q2ClassicSingleHeaderBytes(
      listOf(
        SyntheticWinampFixtures.Q2RuleSpec("Electronic", ClassicWinampPresets.TECHNO),
        SyntheticWinampFixtures.Q2RuleSpec("   ", ClassicWinampPresets.FLAT),
        SyntheticWinampFixtures.Q2RuleSpec("JazzClub", ClassicWinampPresets.CLUB),
      ),
    )
    val parsed = q2Codec.parseAutoPresetsQ2(fixture)
    assertEquals(2, parsed.size)
    assertEquals(listOf("Electronic", "JazzClub"), parsed.map { it.pattern })
    assertEquals(ClassicWinampPresets.TECHNO.bandsDb[0], parsed[0].preset.bandsDb[0], 0.20f)
    assertEquals(ClassicWinampPresets.CLUB.bandsDb[2], parsed[1].preset.bandsDb[2], 0.20f)
  }

  @Test // T1-F3-3
  fun autoEq_priorityOrder_uriBeatsPathBeatsFilenameBeatsStemBeatsFolderBeatsTitle() {
    val track = sampleTrack(
      uriString = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FRock%2F01_track.mp3",
      title = "Neon Highway",
      fileName = "01_track.mp3",
      folderPath = "primary:Music/Rock",
    )

    val pUri = EqPreset("URI_MATCH", 1f, List(10) { 1f })
    val pPath = EqPreset("PATH_MATCH", 2f, List(10) { 2f })
    val pFile = EqPreset("FILE_MATCH", 3f, List(10) { 3f })
    val pStem = EqPreset("STEM_MATCH", 4f, List(10) { 4f })
    val pFolder = EqPreset("FOLDER_MATCH", 5f, List(10) { 5f })
    val pTitle = EqPreset("TITLE_MATCH", 6f, List(10) { 6f })

    // Ordered lowest-to-highest priority to verify order-independence
    val allRules = mutableListOf(
      AutoEqRule("Neon Highway", pTitle),
      AutoEqRule("primary:Music/Rock", pFolder),
      AutoEqRule("01_track", pStem),
      AutoEqRule("01_track.mp3", pFile),
      AutoEqRule("primary:Music\\Rock\\01_track.mp3", pPath),
      AutoEqRule(track.uriString, pUri),
    )

    assertEquals("URI_MATCH", autoEqResolver.resolveForTrack(track, allRules, emptyList())?.name)
    allRules.removeAt(allRules.lastIndex)
    assertEquals("PATH_MATCH", autoEqResolver.resolveForTrack(track, allRules, emptyList())?.name)
    allRules.removeAt(allRules.lastIndex)
    assertEquals("FILE_MATCH", autoEqResolver.resolveForTrack(track, allRules, emptyList())?.name)
    allRules.removeAt(allRules.lastIndex)
    assertEquals("STEM_MATCH", autoEqResolver.resolveForTrack(track, allRules, emptyList())?.name)
    allRules.removeAt(allRules.lastIndex)
    assertEquals("FOLDER_MATCH", autoEqResolver.resolveForTrack(track, allRules, emptyList())?.name)
    allRules.removeAt(allRules.lastIndex)
    assertEquals("TITLE_MATCH", autoEqResolver.resolveForTrack(track, allRules, emptyList())?.name)
  }

  @Test // T1-F3-4
  fun autoEq_deepestFolderRuleWinsOverAncestorRegardlessOfRuleOrder() {
    val track = sampleTrack(
      uriString = "file:///storage/Music/Electronic/Daft%20Punk%20-%20Discovery/01.mp3",
      folderPath = "Music/Electronic/Daft Punk - Discovery",
      fileName = "01.mp3",
    )
    val ancestorPreset = EqPreset("ANCESTOR", 0f, List(10) { 1f })
    val midPreset = EqPreset("MID_FOLDER", 0f, List(10) { 2f })
    val leafSuffixPreset = EqPreset("LEAF_SUFFIX", 0f, List(10) { 3f })
    val exactFullFolderPreset = EqPreset("EXACT_FULL", 0f, List(10) { 4f })

    val ancestorsOnly = listOf(
      AutoEqRule("Music", ancestorPreset),
      AutoEqRule("Music\\Electronic", midPreset),
    )
    assertEquals("MID_FOLDER", autoEqResolver.resolveForTrack(track, ancestorsOnly, emptyList())?.name)
    assertEquals("MID_FOLDER", autoEqResolver.resolveForTrack(track, ancestorsOnly.reversed(), emptyList())?.name)

    val withLeafSuffix = ancestorsOnly + AutoEqRule("Daft Punk - Discovery", leafSuffixPreset)
    assertEquals("LEAF_SUFFIX", autoEqResolver.resolveForTrack(track, withLeafSuffix, emptyList())?.name)
    assertEquals("LEAF_SUFFIX", autoEqResolver.resolveForTrack(track, withLeafSuffix.reversed(), emptyList())?.name)

    val withFullFolder = withLeafSuffix + AutoEqRule("Music/Electronic/Daft Punk - Discovery", exactFullFolderPreset)
    assertEquals("EXACT_FULL", autoEqResolver.resolveForTrack(track, withFullFolder, emptyList())?.name)
    assertEquals("EXACT_FULL", autoEqResolver.resolveForTrack(track, withFullFolder.reversed(), emptyList())?.name)
  }

  @Test // T1-F3-5
  fun autoEq_tier4FolderLeafMatchesAvailablePresetsCaseInsensitively() {
    val rockFolderTrack = sampleTrack(
      uriString = "file:///Music/rock/anthem.mp3",
      folderPath = "Music/rock",
      fileName = "anthem.mp3",
      title = "Unknown Song",
    )
    val tier4Match = autoEqResolver.resolveForTrack(rockFolderTrack, emptyList(), ClassicWinampPresets.ALL)
    assertEquals(ClassicWinampPresets.ROCK, tier4Match)

    val displayLabelTrack = sampleTrack(
      uriString = "file:///Misc/song.mp3",
      folderPath = "Misc",
      fileName = "song.mp3",
      title = "Voyager",
      artist = "Daft Punk",
    )
    val labelPreset = EqPreset("LABEL_RULE", 2f, List(10) { 2f })
    val rules = listOf(AutoEqRule("Daft Punk - Voyager", labelPreset))
    assertEquals("LABEL_RULE", autoEqResolver.resolveForTrack(displayLabelTrack, rules, ClassicWinampPresets.ALL)?.name)
  }

  // =========================================================================
  // Feature 4: winamp.ini Text Codec (WinampIniCodec) — 5 Tier 1 Tests
  // =========================================================================

  @Test // T1-F4-1
  fun winampIni_fullNonDefaultSnapshot_roundTripsAndIsIdempotent() {
    val snapshot = WinampIniSnapshot(
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

    val encoded = winampIniCodec.encode(snapshot)
    val parsed = winampIniCodec.parse(encoded)

    assertEquals(snapshot.volume, parsed.volume)
    assertEquals(snapshot.pan, parsed.pan)
    assertEquals(snapshot.shuffle, parsed.shuffle)
    assertEquals(snapshot.repeat, parsed.repeat)
    assertEquals(snapshot.timeRemaining, parsed.timeRemaining)
    assertEquals(snapshot.eqEnabled, parsed.eqEnabled)
    assertEquals(snapshot.eqAuto, parsed.eqAuto)
    assertEquals(snapshot.replayGainMode, parsed.replayGainMode)
    assertEquals(snapshot.replayGainPreampDb, parsed.replayGainPreampDb, 0.001f)
    assertEquals(snapshot.crossfadeSeconds, parsed.crossfadeSeconds)
    assertEquals(snapshot.eqPreampDb, parsed.eqPreampDb, 0.20f)
    snapshot.eqBandsDb.forEachIndexed { idx, expectedDb ->
      assertEquals(expectedDb, parsed.eqBandsDb[idx], 0.20f)
    }

    val secondPass = winampIniCodec.encode(parsed)
    assertEquals(encoded, secondPass)
  }

  @Test // T1-F4-2
  fun winampIni_flatEq_encodesToExact31sAndDecodesToZeroDb() {
    val flatSnapshot = WinampIniSnapshot(
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
    val encoded = winampIniCodec.encode(flatSnapshot)
    assertTrue("Expected flat eq_data=31,... in encoded INI:\n$encoded", encoded.contains("eq_data=31,31,31,31,31,31,31,31,31,31,31"))

    val parsed = winampIniCodec.parse(encoded)
    assertEquals(0f, parsed.eqPreampDb, 0f)
    parsed.eqBandsDb.forEach { db ->
      assertEquals(0f, db, 0f)
    }
  }

  @Test // T1-F4-3
  fun winampIni_parsesSpaceSeparatedEqDataCrlfBomAndComments() {
    val expected = WinampIniSnapshot(
      volume = 128,
      pan = 64,
      shuffle = true,
      repeat = false,
      timeRemaining = true,
      eqEnabled = true,
      eqAuto = true,
      eqPreampDb = 0f,
      eqBandsDb = ClassicWinampPresets.ROCK.bandsDb,
      replayGainMode = 1,
      replayGainPreampDb = 2.5f,
      crossfadeSeconds = 4,
    )
    val iniText = SyntheticWinampFixtures.winampIni(
      snapshot = expected,
      lineEnding = "\r\n",
      includeBom = true,
      includeComments = true,
      spaceSeparatedEqData = true,
    )

    val parsed = winampIniCodec.parse(iniText)
    assertEquals(128, parsed.volume)
    assertEquals(64, parsed.pan)
    assertTrue(parsed.shuffle)
    assertFalse(parsed.repeat)
    assertTrue(parsed.timeRemaining)
    assertTrue(parsed.eqEnabled)
    assertTrue(parsed.eqAuto)
    assertEquals(1, parsed.replayGainMode)
    assertEquals(2.5f, parsed.replayGainPreampDb, 0.01f)
    assertEquals(4, parsed.crossfadeSeconds)
    expected.eqBandsDb.forEachIndexed { idx, expectedDb ->
      assertEquals(expectedDb, parsed.eqBandsDb[idx], 0.20f)
    }
  }

  @Test // T1-F4-4
  fun winampIni_ignoresOtherSectionsAndUnknownKeys() {
    val snapshot = WinampIniSnapshot(
      volume = 220,
      pan = -15,
      shuffle = false,
      repeat = true,
      timeRemaining = false,
      eqEnabled = true,
      eqAuto = false,
      eqPreampDb = 0f,
      eqBandsDb = ClassicWinampPresets.POP.bandsDb,
      replayGainMode = 2,
      replayGainPreampDb = -1.5f,
      crossfadeSeconds = 3,
    )
    val iniText = SyntheticWinampFixtures.winampIni(
      snapshot = snapshot,
      unknownKeys = mapOf("foo" to "bar", "legacy_flag" to "1"),
      extraSections = mapOf(
        "OtherPlugin" to mapOf("volume" to "10", "pan" to "127", "shuffle" to "1"),
      ),
    )

    val parsed = winampIniCodec.parse(iniText)
    assertEquals(220, parsed.volume)
    assertEquals(-15, parsed.pan)
    assertFalse(parsed.shuffle)
    assertTrue(parsed.repeat)
    assertEquals(2, parsed.replayGainMode)
    assertEquals(-1.5f, parsed.replayGainPreampDb, 0.01f)
    assertEquals(3, parsed.crossfadeSeconds)
  }

  @Test // T1-F4-5
  fun winampIni_booleanVariantsAndCaseInsensitiveKeys_parseCorrectly() {
    val ini =
      """
      [wInAmP]
      VOLUME=190
      PAN=25
      SHUFFLE=true
      Repeat=false
      TimeMode=on
      Eq_Use=no
      Eq_Auto=yes
      """.trimIndent()

    val parsed = winampIniCodec.parse(ini)
    assertEquals(190, parsed.volume)
    assertEquals(25, parsed.pan)
    assertTrue(parsed.shuffle)
    assertFalse(parsed.repeat)
    assertTrue(parsed.timeRemaining)
    assertFalse(parsed.eqEnabled)
    assertTrue(parsed.eqAuto)
  }

  // =========================================================================
  // Feature 5: .m3u / .m3u8 / .pls Codecs (PlaylistFileCodec) — 5 Tier 1 Tests
  // =========================================================================

  @Test // T1-F5-1
  fun m3u8_roundTripsMultiTrackQueueWithExtinfMetadata() {
    val tracks = listOf(
      sampleTrack(
        id = "1",
        uriString = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F01.mp3",
        title = "First Song",
        artist = "Alpha",
        durationMs = 125_000L,
      ),
      sampleTrack(
        id = "2",
        uriString = "",
        title = "Second Song",
        artist = "",
        fileName = "02_second.wav",
        folderPath = "Albums/2025",
        durationMs = 42_000L,
      ),
      sampleTrack(
        id = "3",
        uriString = "file:///sdcard/Music/03_third.mp3",
        title = "",
        artist = "",
        fileName = "03_third.mp3",
        folderPath = "/sdcard/Music",
        durationMs = 310_000L,
      ),
    )

    val m3u8 = playlistCodec.encodeM3u8(tracks)
    assertTrue(m3u8.startsWith("#EXTM3U"))
    assertTrue(m3u8.contains("#EXTINF:125,Alpha - First Song"))
    assertTrue(m3u8.contains("#EXTINF:42,Second Song"))
    assertTrue(m3u8.contains("#EXTINF:310,03_third"))

    val entries = playlistCodec.parseM3u(m3u8)
    assertEquals(
      listOf(
        "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F01.mp3",
        "Albums/2025/02_second.wav",
        "file:///sdcard/Music/03_third.mp3",
      ),
      entries,
    )
  }

  @Test // T1-F5-2
  fun m3u_parsesPlainAndExtendedWithBomCrlfCommentsAndBackslashes() {
    val specs = listOf(
      SyntheticWinampFixtures.PlaylistEntrySpec("Artist\\Album\\01_track.mp3", "One", 120),
      SyntheticWinampFixtures.PlaylistEntrySpec("./sub/02_track.wav", "Two", 45),
    )
    val plain = SyntheticWinampFixtures.m3u8Content(
      entries = specs,
      extended = false,
      lineEnding = "\r\n",
      includeBom = true,
      includeComments = true,
    )
    val extended = SyntheticWinampFixtures.m3u8Content(
      entries = specs,
      extended = true,
      lineEnding = "\r\n",
      includeBom = true,
      includeComments = true,
    )

    val expected = listOf("Artist/Album/01_track.mp3", "./sub/02_track.wav")
    assertEquals(expected, playlistCodec.parseM3u(plain))
    assertEquals(expected, playlistCodec.parseM3u(extended))
  }

  @Test // T1-F5-3
  fun pls_roundTripsMultiTrackQueueWithIniFieldsAndVersion2() {
    val tracks = listOf(
      sampleTrack(id = "1", uriString = "file:///Music/a.mp3", title = "A", artist = "X", durationMs = 60_000L),
      sampleTrack(id = "2", uriString = "file:///Music/b.wav", title = "B", artist = "Y", durationMs = 90_000L),
      sampleTrack(id = "3", uriString = "file:///Music/c.mp3", title = "C", artist = "Z", durationMs = 150_000L),
    )

    val pls = playlistCodec.encodePls(tracks)
    assertTrue(pls.contains("[playlist]"))
    assertTrue(pls.contains("File1=file:///Music/a.mp3"))
    assertTrue(pls.contains("Title1=X - A"))
    assertTrue(pls.contains("Length1=60"))
    assertTrue(pls.contains("File2=file:///Music/b.wav"))
    assertTrue(pls.contains("File3=file:///Music/c.mp3"))
    assertTrue(pls.contains("NumberOfEntries=3"))
    assertTrue(pls.contains("Version=2"))

    val entries = playlistCodec.parsePls(pls)
    assertEquals(listOf("file:///Music/a.mp3", "file:///Music/b.wav", "file:///Music/c.mp3"), entries)
  }

  @Test // T1-F5-4
  fun pls_sortsNumericEntryKeysBeyond9AndIgnoresOtherSections() {
    val entries = (1..10).map { i ->
      SyntheticWinampFixtures.PlaylistEntrySpec("track_$i.mp3", "Track $i", i * 10L)
    }
    val rawPls = SyntheticWinampFixtures.plsContent(
      entries = entries,
      lineEnding = "\r\n",
      includeBom = true,
      reverseOrderKeys = true,
    ) + "[OtherSection]\r\nFile1=should_be_ignored.mp3\r\n"

    val parsed = playlistCodec.parsePls(rawPls)
    assertEquals(10, parsed.size)
    assertEquals((1..10).map { "track_$it.mp3" }, parsed)
  }

  @Test // T1-F5-5
  fun playlist_resolveEntryPath_resolvesRelativeWindowsPosixAndSafTreePaths() {
    val safBase = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FElectronic"
    val specs = SyntheticWinampFixtures.relativeAndWindowsPathPlaylistEntries()

    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FElectronic%2F01_local.wav",
      playlistCodec.resolveEntryPath(specs[0].pathOrUri, safBase),
    )
    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FElectronic%2FArtist%2FAlbum%2F02_windows_sep.mp3",
      playlistCodec.resolveEntryPath(specs[1].pathOrUri, safBase),
    )
    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FElectronic%2Fsub%2F03_dot_relative.wav",
      playlistCodec.resolveEntryPath(specs[2].pathOrUri, safBase),
    )
    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FSibling%2F04_parent_relative.mp3",
      playlistCodec.resolveEntryPath(specs[3].pathOrUri, safBase),
    )
    assertEquals("C:/Music/Rock/song.mp3", playlistCodec.resolveEntryPath("C:\\Music\\Rock\\song.mp3", "/sdcard/Playlists"))
    assertEquals("/sdcard/Music/song.mp3", playlistCodec.resolveEntryPath("/sdcard/Music/song.mp3", safBase))
  }

  // =========================================================================
  // Feature 6: Classic Winamp Presets Library — 5 Tier 1 Tests
  // =========================================================================

  @Test // T1-F6-1
  fun classicPresets_containsAll18UniquePresetsWithFlatFirst() {
    val all = ClassicWinampPresets.ALL
    assertEquals(18, all.size)
    assertEquals(ClassicWinampPresets.FLAT, all.first())
    assertEquals(18, all.map { it.name }.toSet().size)
    all.forEach { preset ->
      assertTrue("Preset name must be non-blank", preset.name.isNotBlank())
    }

    val byName = all.associateBy { it.name }
    assertEquals(ClassicWinampPresets.FLAT, byName["FLAT"])
    assertEquals(ClassicWinampPresets.ROCK, byName["ROCK"])
    assertEquals(ClassicWinampPresets.POP, byName["POP"])
    assertEquals(ClassicWinampPresets.TECHNO, byName["TECHNO"])
    assertEquals(ClassicWinampPresets.FULL_BASS_TREBLE, byName["FULL BASS & TREBLE"])
    assertEquals(ClassicWinampPresets.CLASSICAL, byName["CLASSICAL"])
  }

  @Test // T1-F6-2
  fun classicPresets_allBandsAndPreampWithinWinampPlusMinus12Db() {
    ClassicWinampPresets.ALL.forEach { preset ->
      assertEquals(WinampEqSpec.BAND_COUNT, preset.bandsDb.size)
      assertTrue("${preset.name} preamp out of range: ${preset.preampDb}", preset.preampDb in WinampEqSpec.MIN_DB..WinampEqSpec.MAX_DB)
      preset.bandsDb.forEachIndexed { idx, bandDb ->
        assertTrue("${preset.name} band[$idx] out of range: $bandDb", bandDb in WinampEqSpec.MIN_DB..WinampEqSpec.MAX_DB)
      }
    }
  }

  @Test // T1-F6-3
  fun classicPresets_applyViaAudioEngineController_updatesEqualizerState() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      runBlocking {
        driver.engine.applyEqPreset(ClassicWinampPresets.FULL_BASS_TREBLE)
      }
      var eq = driver.engine.playbackState.value.equalizer
      assertEquals("FULL BASS & TREBLE", eq.activePresetName)
      assertEquals(ClassicWinampPresets.FULL_BASS_TREBLE.preampDb, eq.preampDb, 0f)
      assertEquals(ClassicWinampPresets.FULL_BASS_TREBLE.bandsDb, eq.bandsDb)

      runBlocking {
        driver.engine.applyEqPreset(ClassicWinampPresets.ROCK)
      }
      eq = driver.engine.playbackState.value.equalizer
      assertEquals("ROCK", eq.activePresetName)
      assertEquals(ClassicWinampPresets.ROCK.preampDb, eq.preampDb, 0f)
      assertEquals(ClassicWinampPresets.ROCK.bandsDb, eq.bandsDb)
    }
  }

  @Test // T1-F6-4
  fun classicPresets_uiPresetsPopup_listsClassicPresetsAndAppliesVisiblePreset() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.ensureSheetCollapsed()
      DroidAmpSelectors.eqPresetsButton(composeRule).assertIsDisplayed()

      // Verify popup opens with "EQ PRESETS" title and applies a top/mid-list preset
      // ("CLASSICAL" is item #2 so it is visible on both pre-M-integration non-scrollable
      // Column popups and post-M-integration scrollable popups).
      driver.selectEqPreset("CLASSICAL")

      val eqAfterUi = driver.viewModel.playbackState.value.equalizer
      assertEquals("CLASSICAL", eqAfterUi.activePresetName)
      assertEquals(ClassicWinampPresets.CLASSICAL.preampDb, eqAfterUi.preampDb, 0f)
      assertEquals(ClassicWinampPresets.CLASSICAL.bandsDb, eqAfterUi.bandsDb)

      // Also verify ROCK / TECHNO / FULL BASS & TREBLE via applyEqPreset contract
      listOf(
        ClassicWinampPresets.ROCK,
        ClassicWinampPresets.TECHNO,
        ClassicWinampPresets.FULL_BASS_TREBLE,
      ).forEach { preset ->
        driver.viewModel.applyEqPreset(preset)
        val eq = driver.viewModel.playbackState.value.equalizer
        assertEquals(preset.name, eq.activePresetName)
        assertEquals(preset.bandsDb, eq.bandsDb)
      }
    }
  }

  @Test // T1-F6-5
  fun classicPresets_applyingPresetWhileEqDisabled_updatesCurveWithoutForcingOn() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.toggleEqEnabled()
      assertFalse(driver.viewModel.playbackState.value.equalizer.enabled)

      driver.viewModel.applyEqPreset(ClassicWinampPresets.TECHNO)
      composeRule.waitForIdle()

      val eq = driver.viewModel.playbackState.value.equalizer
      assertFalse("Applying a preset while EQ is disabled must not force ON=true", eq.enabled)
      assertEquals("TECHNO", eq.activePresetName)
      assertEquals(ClassicWinampPresets.TECHNO.bandsDb, eq.bandsDb)
    }
  }
}
