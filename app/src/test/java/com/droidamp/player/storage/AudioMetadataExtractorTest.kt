package com.droidamp.player.storage

import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioMetadataExtractorTest {

  private class FakeProbe(
    private val handler: (String) -> ProbedAudioMetadata?,
  ) : AudioMetadataProbe {
    var invocations: Int = 0
      private set

    override fun probe(uriString: String): ProbedAudioMetadata? {
      invocations += 1
      return handler(uriString)
    }
  }

  @Test
  fun extractTrack_usesProbedMetadataAndTechnicalFields() = runTest {
    val probe =
      FakeProbe {
        ProbedAudioMetadata(
          title = "L.A. Woman",
          artist = "The Doors",
          album = "L.A. Woman",
          durationMs = 469_000L,
          bitrateKbps = 320,
          sampleRateHz = 44_100,
          channelMode = ChannelMode.STEREO,
        )
      }
    val extractor = AudioMetadataExtractor(probe)

    val track =
      extractor.extractTrack(
        id = "saf:1",
        uriString = "content://tree/primary:Music/la_woman.mp3",
        fileName = "The Doors - L.A. Woman.mp3",
        folderPath = "Music/Rock",
      )

    assertEquals("L.A. Woman", track.title)
    assertEquals("The Doors", track.artist)
    assertEquals("L.A. Woman", track.album)
    assertEquals(469_000L, track.durationMs)
    assertEquals(320, track.bitrateKbps)
    assertEquals(44_100, track.sampleRateHz)
    assertEquals(ChannelMode.STEREO, track.channelMode)
    assertEquals("Music/Rock", track.folderPath)
  }

  @Test
  fun extractTrack_sanitizesUnknownTagsAndParsesFilenameFallbacks() = runTest {
    val probe =
      FakeProbe {
        ProbedAudioMetadata(
          title = null,
          artist = "<unknown>",
          album = "<UNKNOWN>",
          durationMs = -50L,
          bitrateKbps = -128,
          sampleRateHz = -44100,
          channelMode = null,
        )
      }
    val extractor = AudioMetadataExtractor(probe)

    val artistTitle =
      extractor.extractTrack(
        id = "1",
        uriString = "file:///1.mp3",
        fileName = "The Doors - L.A. Woman.mp3",
        folderPath = "Music",
        fallbackArtist = "<unknown>",
        fallbackAlbum = "<unknown>",
        fallbackDurationMs = 123_000L,
      )
    assertEquals("The Doors", artistTitle.artist)
    assertEquals("L.A. Woman", artistTitle.title)
    assertEquals("", artistTitle.album)
    assertEquals(123_000L, artistTitle.durationMs)
    assertEquals(0, artistTitle.bitrateKbps)
    assertEquals(0, artistTitle.sampleRateHz)
    assertEquals(ChannelMode.STEREO, artistTitle.channelMode)

    val numberedTitle =
      extractor.extractTrack(
        id = "2",
        uriString = "file:///2.mp3",
        fileName = "01 - Nightcall.mp3",
        folderPath = "Music",
      )
    assertEquals("", numberedTitle.artist)
    assertEquals("Nightcall", numberedTitle.title)

    val numberedArtistTitle =
      extractor.extractTrack(
        id = "3",
        uriString = "file:///3.flac",
        fileName = "02 - Daft Punk - Aerodynamic.flac",
        folderPath = "Music",
      )
    assertEquals("Daft Punk", numberedArtistTitle.artist)
    assertEquals("Aerodynamic", numberedArtistTitle.title)

    val plainStem =
      extractor.extractTrack(
        id = "4",
        uriString = "file:///4.ogg",
        fileName = "plain_stem.ogg",
        folderPath = "Music",
      )
    assertEquals("", plainStem.artist)
    assertEquals("plain_stem", plainStem.title)
  }

  @Test
  fun extractTrack_cachesLruEvictsAndSupportsInvalidation() = runTest {
    val probe =
      FakeProbe { uri ->
        ProbedAudioMetadata(
          title = "Title-$uri",
          artist = "Artist",
          durationMs = 10_000L,
          channelMode = ChannelMode.MONO,
        )
      }
    val extractor = AudioMetadataExtractor(probe = probe, maxCacheEntries = 2)

    extractor.extractTrack("a", "uri://a", "a.mp3", "Music")
    extractor.extractTrack("b", "uri://b", "b.mp3", "Music")
    assertEquals(2, probe.invocations)
    assertEquals(2, extractor.cacheSize())

    // Hit on uri://a refreshes its recency, so uri://b is evicted next
    val cachedA = extractor.extractTrack("a-new-id", "uri://a", "a.mp3", "Music/Sub")
    assertEquals(2, probe.invocations)
    assertEquals("a-new-id", cachedA.id)
    assertEquals("Music/Sub", cachedA.folderPath)
    assertEquals(ChannelMode.MONO, cachedA.channelMode)

    extractor.extractTrack("c", "uri://c", "c.mp3", "Music")
    assertEquals(3, probe.invocations)
    assertEquals(2, extractor.cacheSize())

    // uri://a should still be cached, uri://b was evicted
    extractor.extractTrack("a", "uri://a", "a.mp3", "Music")
    assertEquals(3, probe.invocations)
    extractor.extractTrack("b", "uri://b", "b.mp3", "Music")
    assertEquals(4, probe.invocations)

    extractor.invalidate("uri://b")
    extractor.extractTrack("b", "uri://b", "b.mp3", "Music")
    assertEquals(5, probe.invocations)

    extractor.clearCache()
    assertEquals(0, extractor.cacheSize())
  }

  @Test
  fun extractTrack_handlesProbeErrorsSafelyAndRethrowsCancellation() = runTest {
    val failingProbe = FakeProbe { throw IllegalStateException("corrupt header") }
    val extractor = AudioMetadataExtractor(failingProbe)

    val fallback =
      extractor.extractTrack(
        id = "err",
        uriString = "content://bad.mp3",
        fileName = "04. Fallback Song.mp3",
        folderPath = "Music",
      )
    assertEquals("Fallback Song", fallback.title)

    val cancellingProbe = FakeProbe { throw CancellationException("cancelled") }
    val cancelExtractor = AudioMetadataExtractor(cancellingProbe)
    try {
      cancelExtractor.extractTrack("c", "content://c.mp3", "c.mp3", "Music")
      fail("Expected CancellationException to be rethrown")
    } catch (_: CancellationException) {
      // Expected
    }
  }

  @Test
  fun extractTrack_isThreadSafeUnderConcurrentCalls_andAudioTrackHasZeroArtworkFields() = runTest {
    val probe = FakeProbe { ProbedAudioMetadata(title = "Concurrent", durationMs = 1_000L) }
    val extractor = AudioMetadataExtractor(probe, maxCacheEntries = 16)

    val results =
      (1..20)
        .map { idx ->
          async {
            extractor.extractTrack("id-$idx", "uri://track-${idx % 4}", "track.mp3", "Music")
          }
        }.awaitAll()

    assertEquals(20, results.size)
    assertTrue(extractor.cacheSize() <= 4)

    val artFieldNames =
      AudioTrack::class.java.declaredFields.map { it.name.lowercase() }
    assertFalse(
      artFieldNames.any {
        "artwork" in it || "albumart" in it || "cover" in it || "bitmap" in it || "image" in it || "thumbnail" in it
      },
    )
  }
}
