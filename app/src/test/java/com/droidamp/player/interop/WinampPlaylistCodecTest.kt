package com.droidamp.player.interop

import com.droidamp.player.domain.model.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WinampPlaylistCodecTest {
  private val codec = WinampPlaylistCodec()

  private val tracks =
    listOf(
      AudioTrack(
        id = "1",
        uriString = "content://com.android.externalstorage.documents/tree/primary:Music/01_one_more_time.flac",
        title = "One More Time",
        artist = "Daft Punk",
        fileName = "01_one_more_time.flac",
        folderPath = "Music",
        durationMs = 320_500L,
      ),
      AudioTrack(
        id = "2",
        uriString = "",
        title = "L.A. Woman\r\n#EXTINF:0,Injected",
        artist = "The Doors",
        fileName = "02_la_woman.mp3",
        folderPath = "Music\\Classic Rock",
        durationMs = 469_000L,
      ),
    )

  @Test
  fun m3u8_roundTripsMultiTrackAndEmptyQueuesAndSanitizesTitleNewlines() {
    val emptyEncoded = codec.encodeM3u8(emptyList())
    assertTrue(emptyEncoded.startsWith("#EXTM3U"))
    assertEquals(emptyList<String>(), codec.parseM3u(emptyEncoded))

    val encoded = codec.encodeM3u8(tracks)
    assertTrue(encoded.contains("#EXTINF:320,Daft Punk - One More Time"))
    assertTrue(encoded.contains("#EXTINF:469,The Doors - L.A. Woman #EXTINF:0,Injected"))
    assertFalse(encoded.contains("\n#EXTINF:0,Injected\n"))

    val parsed = codec.parseM3u(encoded)
    assertEquals(
      listOf(
        "content://com.android.externalstorage.documents/tree/primary:Music/01_one_more_time.flac",
        "Music/Classic Rock/02_la_woman.mp3",
      ),
      parsed,
    )
  }

  @Test
  fun parseM3u_handlesBomCrlfCommentsAndWindowsBackslashes() {
    val raw =
      "\uFEFF" +
        "#EXTM3U\r\n" +
        "#EXTINF:215,Track One\r\n" +
        "  Rock\\01_track.mp3  \r\n" +
        "\r\n" +
        "# Arbitrary comment\r\n" +
        "../Jazz/02_blue.flac\n"

    assertEquals(
      listOf("Rock/01_track.mp3", "../Jazz/02_blue.flac"),
      codec.parseM3u(raw),
    )
  }

  @Test
  fun pls_roundTripsMultiTrackAndEmptyQueues() {
    val emptyEncoded = codec.encodePls(emptyList())
    assertTrue(emptyEncoded.contains("[playlist]"))
    assertTrue(emptyEncoded.contains("NumberOfEntries=0"))
    assertTrue(emptyEncoded.contains("Version=2"))
    assertEquals(emptyList<String>(), codec.parsePls(emptyEncoded))

    val encoded = codec.encodePls(tracks)
    assertTrue(encoded.contains("File1=content://com.android.externalstorage.documents/tree/primary:Music/01_one_more_time.flac"))
    assertTrue(encoded.contains("Title1=Daft Punk - One More Time"))
    assertTrue(encoded.contains("Length1=320"))
    assertTrue(encoded.contains("File2=Music/Classic Rock/02_la_woman.mp3"))
    assertTrue(encoded.contains("Title2=The Doors - L.A. Woman #EXTINF:0,Injected"))
    assertTrue(encoded.contains("NumberOfEntries=2"))
    assertTrue(encoded.contains("Version=2"))

    val parsed = codec.parsePls(encoded)
    assertEquals(
      listOf(
        "content://com.android.externalstorage.documents/tree/primary:Music/01_one_more_time.flac",
        "Music/Classic Rock/02_la_woman.mp3",
      ),
      parsed,
    )
  }

  @Test
  fun parsePls_sortsOutOfOrderAndNonContiguousIndicesAndIgnoresOtherSections() {
    val raw =
      "\uFEFF" +
        "; comment\r\n" +
        "[Other]\r\n" +
        "File1=should_be_ignored.mp3\r\n" +
        "[PLAYLIST]\r\n" +
        "file10 = Ten\\track10.mp3\r\n" +
        "FILE2 = Two\\track2.mp3\r\n" +
        "File1 = One\\track1.mp3\r\n" +
        "Title1 = One\r\n" +
        "Length1 = 100\r\n" +
        "NumberOfEntries = 99\r\n" +
        "Version = 2\r\n"

    assertEquals(
      listOf("One/track1.mp3", "Two/track2.mp3", "Ten/track10.mp3"),
      codec.parsePls(raw),
    )
  }

  @Test
  fun resolveEntryPath_handlesUrisAbsoluteWindowsPosixAndRelativeWithSafBase() {
    val safBase = "content://com.android.externalstorage.documents/tree/primary:Music/Electronic"

    // URI pass-through
    val uriEntry = "content://com.android.externalstorage.documents/document/primary:Music%2Fsong.mp3"
    assertEquals(uriEntry, codec.resolveEntryPath(uriEntry, safBase))
    assertEquals("file:///sdcard/Music/a.mp3", codec.resolveEntryPath("file:///sdcard/Music/a.mp3", safBase))

    // Windows drive absolute path
    assertEquals(
      "C:/Music/Rock/song.mp3",
      codec.resolveEntryPath("C:\\Music\\Pop\\..\\Rock\\.\\song.mp3", safBase),
    )

    // POSIX absolute path (clamping .. at root)
    assertEquals(
      "/storage/emulated/0/Music/song.mp3",
      codec.resolveEntryPath("/../storage/emulated/0/Music/./song.mp3", safBase),
    )

    // Relative against SAF base (including .. traversal)
    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary:Music/Electronic/Daft Punk/01.flac",
      codec.resolveEntryPath(".\\Daft Punk\\01.flac", safBase),
    )
    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary:Music/Rock/02.mp3",
      codec.resolveEntryPath("..\\Rock\\02.mp3", safBase),
    )

    // Relative against relative folder base
    assertEquals(
      "Music/Jazz/03.mp3",
      codec.resolveEntryPath("../Jazz/./03.mp3", "Music\\Rock"),
    )

    // Relative against empty base preserves leading ..
    assertEquals("../Other/04.mp3", codec.resolveEntryPath("../Other/./04.mp3", ""))
    assertEquals("", codec.resolveEntryPath("   ", safBase))
  }

  @Test
  fun resolveEntryPath_clampsSafTraversalAtTreeRootAndPreservesPercentEncodedSafUris() {
    val decodedTreeBase =
      "content://com.android.externalstorage.documents/tree/primary:Music/Electronic"
    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary:Music/etc/passwd",
      codec.resolveEntryPath("../../etc/passwd", decodedTreeBase),
    )
    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary:Music/etc/passwd",
      codec.resolveEntryPath("../../../../etc/passwd", decodedTreeBase),
    )

    val encodedTreeBase =
      "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FElectronic"
    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FOther%2F01.mp3",
      codec.resolveEntryPath("../Other/01.mp3", encodedTreeBase),
    )
    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary%3AMusic%2Fetc%2Fpasswd",
      codec.resolveEntryPath("../../../../etc/passwd", encodedTreeBase),
    )

    val encodedDocBase =
      "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FElectronic"
    assertEquals(
      "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FOther%2F01.mp3",
      codec.resolveEntryPath("../Other/01.mp3", encodedDocBase),
    )
  }

  @Test
  fun resolveEntryPath_andTrackEntryPreservePosixRootAndFileRootUris() {
    assertEquals("/a.mp3", codec.resolveEntryPath("a.mp3", "/"))
    assertEquals("/etc/passwd", codec.resolveEntryPath("../../../../etc/passwd", "/"))
    assertEquals("file:///song.mp3", codec.resolveEntryPath("song.mp3", "file:///"))
    assertEquals("file:///etc/passwd", codec.resolveEntryPath("../../etc/passwd", "file:///"))

    val rootTrack =
      AudioTrack(
        id = "root1",
        uriString = "",
        title = "Root Song",
        artist = "Artist",
        fileName = "song.mp3",
        folderPath = "/",
        durationMs = 120_000L,
      )
    assertEquals(listOf("/song.mp3"), codec.parseM3u(codec.encodeM3u8(listOf(rootTrack))))
    assertEquals(listOf("/song.mp3"), codec.parsePls(codec.encodePls(listOf(rootTrack))))
  }

  @Test
  fun encodeM3u8AndPls_stripNewlineInjectionFromTrackPaths() {
    val injectedTracks =
      listOf(
        AudioTrack(
          id = "inj1",
          uriString = "Music/a.mp3\r\nMusic/injected.mp3",
          title = "Track A",
          artist = "Artist",
          fileName = "a.mp3",
          folderPath = "Music",
          durationMs = 100_000L,
        ),
        AudioTrack(
          id = "inj2",
          uriString = "",
          title = "Track B",
          artist = "Artist",
          fileName = "b.mp3\nFile3=evil.mp3",
          folderPath = "Music\r\nRock",
          durationMs = 100_000L,
        ),
      )

    val m3uEntries = codec.parseM3u(codec.encodeM3u8(injectedTracks))
    assertEquals(2, m3uEntries.size)
    assertFalse(m3uEntries.any { it == "Music/injected.mp3" })

    val plsEntries = codec.parsePls(codec.encodePls(injectedTracks))
    assertEquals(2, plsEntries.size)
    assertFalse(plsEntries.any { it == "evil.mp3" })
  }
}
