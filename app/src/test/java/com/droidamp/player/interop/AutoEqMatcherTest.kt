package com.droidamp.player.interop

import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoEqMatcherTest {
  private val matcher = AutoEqMatcher()

  private val sampleTrack =
    AudioTrack(
      id = "t1",
      uriString = "content://com.android.externalstorage.documents/tree/primary:Music/Electronic/Daft Punk - Discovery/01_one_more_time.flac",
      title = "One More Time",
      artist = "Daft Punk",
      album = "Discovery",
      fileName = "01_one_more_time.flac",
      folderPath = "Music/Electronic/Daft Punk - Discovery",
      durationMs = 320_000L,
    )

  @Test
  fun resolveForTrack_tier1ExactUriPathFilenameStemOverridesFolderAndTitle() {
    val uriPreset = customPreset("URI_MATCH")
    val fullPathPreset = customPreset("FULL_PATH")
    val fileNamePreset = customPreset("FILENAME")
    val stemPreset = customPreset("STEM")
    val folderPreset = customPreset("FOLDER")
    val titlePreset = customPreset("TITLE")

    // Exact URI beats everything else regardless of list order
    val allRules =
      listOf(
        AutoEqRule("One More Time", titlePreset),
        AutoEqRule("Music/Electronic/Daft Punk - Discovery", folderPreset),
        AutoEqRule("01_one_more_time", stemPreset),
        AutoEqRule("01_one_more_time.flac", fileNamePreset),
        AutoEqRule("Music\\Electronic\\Daft Punk - Discovery\\01_one_more_time.flac", fullPathPreset),
        AutoEqRule(sampleTrack.uriString, uriPreset),
      )
    assertEquals(uriPreset, matcher.resolveForTrack(sampleTrack, allRules, ClassicWinampPresets.ALL))

    // Full path (with Windows backslashes & mixed case) beats filename, stem, folder, title
    val withoutUri = allRules.dropLast(1)
    assertEquals(fullPathPreset, matcher.resolveForTrack(sampleTrack, withoutUri, ClassicWinampPresets.ALL))

    // Filename beats stem, folder, title
    val withoutFullPath = withoutUri.dropLast(1)
    assertEquals(fileNamePreset, matcher.resolveForTrack(sampleTrack, withoutFullPath, ClassicWinampPresets.ALL))

    // Stem beats folder, title
    val withoutFileName = withoutFullPath.dropLast(1)
    assertEquals(stemPreset, matcher.resolveForTrack(sampleTrack, withoutFileName, ClassicWinampPresets.ALL))

    // Folder beats title
    val withoutStem = withoutFileName.dropLast(1)
    assertEquals(folderPreset, matcher.resolveForTrack(sampleTrack, withoutStem, ClassicWinampPresets.ALL))

    // Title matches when no path/folder rule matches
    val onlyTitle = listOf(AutoEqRule("one more time", titlePreset))
    assertEquals(titlePreset, matcher.resolveForTrack(sampleTrack, onlyTitle, ClassicWinampPresets.ALL))
  }

  @Test
  fun resolveForTrack_tier2DeepestFolderRuleWinsRegardlessOfRuleOrder() {
    val rootPreset = customPreset("ROOT")
    val parentPreset = customPreset("PARENT")
    val leafPreset = customPreset("LEAF")
    val exactFolderPreset = customPreset("EXACT_FOLDER")

    val rules =
      listOf(
        AutoEqRule("Music", rootPreset),
        AutoEqRule("Music\\Electronic", parentPreset),
        AutoEqRule("Daft Punk - Discovery", leafPreset),
      )
    // Leaf suffix beats ancestor prefix
    assertEquals(leafPreset, matcher.resolveForTrack(sampleTrack, rules, ClassicWinampPresets.ALL))

    // Exact full folder beats leaf suffix
    val withExact = rules + AutoEqRule("music/electronic/daft punk - discovery", exactFolderPreset)
    assertEquals(exactFolderPreset, matcher.resolveForTrack(sampleTrack, withExact, ClassicWinampPresets.ALL))

    // Deeper ancestor prefix beats shallower ancestor prefix
    val ancestorsOnly =
      listOf(
        AutoEqRule("Music", rootPreset),
        AutoEqRule("Music/Electronic", parentPreset),
      )
    assertEquals(parentPreset, matcher.resolveForTrack(sampleTrack, ancestorsOnly, ClassicWinampPresets.ALL))
  }

  @Test
  fun resolveForTrack_tier3MatchesDisplayLabel() {
    val labelPreset = customPreset("LABEL")
    val rules = listOf(AutoEqRule("daft punk - one more time", labelPreset))
    assertEquals(labelPreset, matcher.resolveForTrack(sampleTrack, rules, ClassicWinampPresets.ALL))
  }

  @Test
  fun resolveForTrack_tier4MatchesExactFolderLeafInAvailablePresetsExcludingFlatAndCustom() {
    val rockTrack =
      sampleTrack.copy(
        folderPath = "Music/Rock",
        fileName = "track.mp3",
        title = "Unknown",
        artist = "Band",
      )
    assertEquals(
      ClassicWinampPresets.ROCK,
      matcher.resolveForTrack(rockTrack, emptyList(), ClassicWinampPresets.ALL),
    )

    // FLAT and CUSTOM folder names must NOT trigger fallback
    val flatFolderTrack = rockTrack.copy(folderPath = "Music/FLAT")
    assertNull(matcher.resolveForTrack(flatFolderTrack, emptyList(), ClassicWinampPresets.ALL))

    val customFolderTrack = rockTrack.copy(folderPath = "Music/CUSTOM")
    val withCustom = ClassicWinampPresets.ALL + customPreset("CUSTOM")
    assertNull(matcher.resolveForTrack(customFolderTrack, emptyList(), withCustom))

    // Partial/substring title must NOT falsely match ROCK preset
    val rockWithYouTrack =
      rockTrack.copy(
        folderPath = "Music/PopBallads",
        title = "Rock With You",
      )
    assertNull(matcher.resolveForTrack(rockWithYouTrack, emptyList(), ClassicWinampPresets.ALL))
  }

  @Test
  fun resolveForTrack_ignoresBlankPatternsAndReturnsNullWhenUnmatched() {
    val unmatched =
      sampleTrack.copy(
        folderPath = "Music/Audiobooks",
        title = "Chapter 1",
        artist = "Narrator",
      )
    val rules = listOf(AutoEqRule("   ", ClassicWinampPresets.ROCK))
    assertNull(matcher.resolveForTrack(unmatched, rules, ClassicWinampPresets.ALL))
  }

  @Test
  fun resolveForTrack_handlesWindowsDoubleBackslashesWithoutSchemeConfusion() {
    val winPreset = customPreset("WIN_EXACT")
    val winTrack =
      sampleTrack.copy(
        uriString = "",
        folderPath = """C:\Music\\Rock///""",
        fileName = "01_Song.MP3",
        title = "Song",
        artist = "Band",
      )
    val rules = listOf(AutoEqRule("""c:\\music\rock\\01_song.mp3""", winPreset))
    assertEquals(winPreset, matcher.resolveForTrack(winTrack, rules, ClassicWinampPresets.ALL))
  }

  @Test
  fun resolveForTrack_tier3MatchesTitlesAndDisplayLabelsContainingBackslashesOrDoubleSlashes() {
    val backslashPreset = customPreset("ACDC")
    val slashPreset = customPreset("DOUBLE_SLASH")
    val displayPreset = customPreset("DISPLAY_BACKSLASH")

    val backslashTrack =
      sampleTrack.copy(
        uriString = "",
        folderPath = "Music/Hard",
        fileName = "01.mp3",
        title = """AC\DC""",
        artist = "",
      )
    assertEquals(
      backslashPreset,
      matcher.resolveForTrack(
        backslashTrack,
        listOf(AutoEqRule("""AC\DC""", backslashPreset)),
        ClassicWinampPresets.ALL,
      ),
    )

    val doubleSlashTrack =
      sampleTrack.copy(
        uriString = "",
        folderPath = "Music/Hard",
        fileName = "02.mp3",
        title = "Track // 1",
        artist = "",
      )
    assertEquals(
      slashPreset,
      matcher.resolveForTrack(
        doubleSlashTrack,
        listOf(AutoEqRule("Track // 1", slashPreset)),
        ClassicWinampPresets.ALL,
      ),
    )

    val displayLabelTrack =
      sampleTrack.copy(
        uriString = "",
        folderPath = "Music/Hard",
        fileName = "03.mp3",
        title = "Back in Black",
        artist = """AC\DC""",
      )
    assertEquals(
      displayPreset,
      matcher.resolveForTrack(
        displayLabelTrack,
        listOf(AutoEqRule("""AC\DC - Back in Black""", displayPreset)),
        ClassicWinampPresets.ALL,
      ),
    )
  }

  @Test
  fun resolveForTrack_tier1bExactAndLongerPartialPathBeatShorterSuffixRegardlessOfOrder() {
    val shortPartial = customPreset("SHORT_PARTIAL")
    val exactFull = customPreset("EXACT_FULL")
    val track =
      sampleTrack.copy(
        uriString = "file:///Music/Rock/song.mp3",
        folderPath = "Music/Rock",
        fileName = "song.mp3",
        title = "Song",
        artist = "Band",
      )

    val partialFirstRules =
      listOf(
        AutoEqRule("Rock/song.mp3", shortPartial),
        AutoEqRule("Music/Rock/song.mp3", exactFull),
      )
    assertEquals(exactFull, matcher.resolveForTrack(track, partialFirstRules, ClassicWinampPresets.ALL))

    // Even when neither rule matches the deeper full path, longer partial suffix wins regardless of order
    val deepTrack =
      track.copy(
        uriString = "file:///Library/Music/Rock/song.mp3",
        folderPath = "Library/Music/Rock",
      )
    assertEquals(exactFull, matcher.resolveForTrack(deepTrack, partialFirstRules, ClassicWinampPresets.ALL))
    assertEquals(
      exactFull,
      matcher.resolveForTrack(deepTrack, partialFirstRules.reversed(), ClassicWinampPresets.ALL),
    )
  }

  @Test
  fun resolveForTrack_tier1bMatchesPartialRelativePathOnUrlEncodedSafUris() {
    val safPartialPreset = customPreset("SAF_PARTIAL")
    val safTrack =
      sampleTrack.copy(
        uriString =
          "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FRock%2Fsong.mp3",
        folderPath = "Music/Rock",
        fileName = "song.mp3",
        title = "Unknown",
        artist = "Band",
      )
    val rules = listOf(AutoEqRule("Rock/song.mp3", safPartialPreset))
    assertEquals(safPartialPreset, matcher.resolveForTrack(safTrack, rules, ClassicWinampPresets.ALL))

    // Also works even when folderPath is empty and path is only inside the URL-encoded SAF URI
    val safUriOnlyTrack = safTrack.copy(folderPath = "")
    assertEquals(
      safPartialPreset,
      matcher.resolveForTrack(safUriOnlyTrack, rules, ClassicWinampPresets.ALL),
    )
  }

  private fun customPreset(name: String): EqPreset =
    EqPreset(name, 0f, List(10) { 1.5f })
}
