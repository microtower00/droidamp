package com.droidamp.player.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.mp3.Mp3InfoReplayGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(UnstableApi::class)
class ReplayGainTagParserTest {
  @Test
  fun parsesCaseInsensitiveTagMapWithDbSuffixesAndWhitespace() {
    val parsed =
      ReplayGainTagParser.parseTagMap(
        mapOf(
          " replaygain_track_gain " to " -6.50 dB ",
          "REPLAYGAIN_TRACK_PEAK" to " 0.985412 ",
          "ReplayGain_Album_Gain" to "+2.25db",
          "com.apple.iTunes:replaygain_album_peak" to "0.991",
        ),
      )

    assertEquals(-6.50f, parsed.trackGainDb!!, 1e-4f)
    assertEquals(0.985412f, parsed.trackPeak!!, 1e-6f)
    assertEquals(2.25f, parsed.albumGainDb!!, 1e-4f)
    assertEquals(0.991f, parsed.albumPeak!!, 1e-4f)
  }

  @Test
  fun rejectsInvalidNonFiniteAndNegativePeakTags() {
    val parsed =
      ReplayGainTagParser.parseTagMap(
        mapOf(
          "REPLAYGAIN_TRACK_GAIN" to "NaN",
          "REPLAYGAIN_TRACK_PEAK" to "-0.5",
          "REPLAYGAIN_ALBUM_GAIN" to "Infinity dB",
          "REPLAYGAIN_ALBUM_PEAK" to "not-a-number",
        ),
      )

    assertNull(parsed.trackGainDb)
    assertNull(parsed.trackPeak)
    assertNull(parsed.albumGainDb)
    assertNull(parsed.albumPeak)
  }

  @Test
  fun parsesId3TxxxVorbisCommentAndMp4InternalFrameMetadata() {
    val id3Metadata =
      Metadata(
        TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_GAIN", listOf("-7.25 dB")),
        TextInformationFrame("TXX", "replaygain_track_peak", listOf("0.875")),
        TextInformationFrame("TIT2", "Title", listOf("Ignored")),
      )
    val id3Parsed = ReplayGainTagParser.parseMetadata(id3Metadata)
    assertEquals(-7.25f, id3Parsed.trackGainDb!!, 1e-4f)
    assertEquals(0.875f, id3Parsed.trackPeak!!, 1e-4f)

    val vorbisMetadata =
      Metadata(
        VorbisComment("REPLAYGAIN_ALBUM_GAIN", "-4.10 DB"),
        VorbisComment("REPLAYGAIN_ALBUM_PEAK", "0.95"),
        InternalFrame("com.apple.iTunes", "replaygain_track_gain", "-3.40 dB"),
      )
    val vorbisParsed = ReplayGainTagParser.parseMetadata(vorbisMetadata)
    assertEquals(-3.40f, vorbisParsed.trackGainDb!!, 1e-4f)
    assertEquals(-4.10f, vorbisParsed.albumGainDb!!, 1e-4f)
    assertEquals(0.95f, vorbisParsed.albumPeak!!, 1e-4f)
  }

  @Test
  fun supportsR128AndLameMp3InfoReplayGainFallbacksWithoutOverridingPrimaryTags() {
    val r128Only =
      ReplayGainTagParser.parseTagMap(
        mapOf(
          "R128_TRACK_GAIN" to "-1280", // -5.0 dB relative to -23 LUFS -> 0.0 dB RG (-18 LUFS)
          "R128_ALBUM_GAIN" to "-2560", // -10.0 dB -> -5.0 dB RG
        ),
      )
    assertEquals(0.0f, r128Only.trackGainDb!!, 1e-4f)
    assertEquals(-5.0f, r128Only.albumGainDb!!, 1e-4f)

    val withPrimary =
      ReplayGainTagParser.parseTagMap(
        mapOf(
          "REPLAYGAIN_TRACK_GAIN" to "-8.0 dB",
          "R128_TRACK_GAIN" to "0",
        ),
      )
    assertEquals(-8.0f, withPrimary.trackGainDb!!, 1e-4f)

    // LAME 16-bit fields: name=1 (radio/track), originator=3, sign=1 (-), 65 => -6.5 dB
    // name=2 (audiophile/album), originator=3, sign=0 (+), 15 => +1.5 dB
    val rawTrack = (1 shl 13) or (3 shl 10) or (1 shl 9) or 65
    val rawAlbum = (2 shl 13) or (3 shl 10) or 15
    val lame = Mp3InfoReplayGain.parse(0.92f, rawTrack, rawAlbum)!!
    val lameParsed = ReplayGainTagParser.parseMetadata(Metadata(lame))
    assertEquals(-6.5f, lameParsed.trackGainDb!!, 1e-3f)
    assertEquals(1.5f, lameParsed.albumGainDb!!, 1e-3f)
    assertEquals(0.92f, lameParsed.trackPeak!!, 1e-4f)
  }
}
