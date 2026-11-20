package com.droidamp.player.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.mp3.Mp3InfoReplayGain
import java.util.Locale

data class ReplayGainMetadata(
  val trackGainDb: Float? = null,
  val trackPeak: Float? = null,
  val albumGainDb: Float? = null,
  val albumPeak: Float? = null,
) {
  companion object {
    val EMPTY = ReplayGainMetadata()
  }
}

@OptIn(UnstableApi::class)
object ReplayGainTagParser {
  private const val KEY_TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
  private const val KEY_TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK"
  private const val KEY_ALBUM_GAIN = "REPLAYGAIN_ALBUM_GAIN"
  private const val KEY_ALBUM_PEAK = "REPLAYGAIN_ALBUM_PEAK"
  private const val KEY_R128_TRACK_GAIN = "R128_TRACK_GAIN"
  private const val KEY_R128_ALBUM_GAIN = "R128_ALBUM_GAIN"

  fun parseTagMap(tags: Map<String, String>): ReplayGainMetadata =
    parseEntries(tags.entries.map { it.key to it.value })

  fun parseEntries(entries: Iterable<Pair<String, String>>): ReplayGainMetadata {
    var trackGainDb: Float? = null
    var trackPeak: Float? = null
    var albumGainDb: Float? = null
    var albumPeak: Float? = null
    var r128TrackGainDb: Float? = null
    var r128AlbumGainDb: Float? = null

    for ((rawKey, rawValue) in entries) {
      val key = normalizeKey(rawKey)
      when (key) {
        KEY_TRACK_GAIN -> parseGainDb(rawValue)?.let { trackGainDb = it }
        KEY_TRACK_PEAK -> parsePeak(rawValue)?.let { trackPeak = it }
        KEY_ALBUM_GAIN -> parseGainDb(rawValue)?.let { albumGainDb = it }
        KEY_ALBUM_PEAK -> parsePeak(rawValue)?.let { albumPeak = it }
        KEY_R128_TRACK_GAIN -> parseR128GainDb(rawValue)?.let { r128TrackGainDb = it }
        KEY_R128_ALBUM_GAIN -> parseR128GainDb(rawValue)?.let { r128AlbumGainDb = it }
      }
    }

    return ReplayGainMetadata(
      trackGainDb = trackGainDb ?: r128TrackGainDb,
      trackPeak = trackPeak,
      albumGainDb = albumGainDb ?: r128AlbumGainDb,
      albumPeak = albumPeak,
    )
  }

  fun parseMetadata(metadata: Metadata?): ReplayGainMetadata {
    if (metadata == null || metadata.length() == 0) {
      return ReplayGainMetadata.EMPTY
    }

    val entries = ArrayList<Pair<String, String>>(metadata.length())
    var lameTrackGainDb: Float? = null
    var lameAlbumGainDb: Float? = null
    var lameTrackPeak: Float? = null

    for (i in 0 until metadata.length()) {
      when (val entry = metadata[i]) {
        is TextInformationFrame -> {
          if (entry.id.equals("TXXX", ignoreCase = true) || entry.id.equals("TXX", ignoreCase = true)) {
            val desc = entry.description?.trim().orEmpty()
            val value = entry.values.firstOrNull { it.isNotBlank() }
            if (desc.isNotEmpty() && value != null) {
              entries.add(desc to value)
            }
          }
        }
        is VorbisComment -> {
          if (entry.key.isNotBlank() && entry.value.isNotBlank()) {
            entries.add(entry.key to entry.value)
          }
        }
        is InternalFrame -> {
          if (entry.description.isNotBlank() && entry.text.isNotBlank()) {
            entries.add(entry.description to entry.text)
          }
        }
        is Mp3InfoReplayGain -> {
          if (entry.peak.isFinite() && entry.peak > 0f) {
            lameTrackPeak = entry.peak
          }
          extractLameGain(entry.field1)?.let { (isAlbum, gainDb) ->
            if (isAlbum) lameAlbumGainDb = gainDb else lameTrackGainDb = gainDb
          }
          extractLameGain(entry.field2)?.let { (isAlbum, gainDb) ->
            if (isAlbum) lameAlbumGainDb = gainDb else lameTrackGainDb = gainDb
          }
        }
      }
    }

    val parsed = parseEntries(entries)
    return ReplayGainMetadata(
      trackGainDb = parsed.trackGainDb ?: lameTrackGainDb,
      trackPeak = parsed.trackPeak ?: lameTrackPeak,
      albumGainDb = parsed.albumGainDb ?: lameAlbumGainDb,
      albumPeak = parsed.albumPeak,
    )
  }

  fun parseGainDb(raw: String?): Float? {
    if (raw.isNullOrBlank()) return null
    var text = raw.trim()
    if (text.endsWith("db", ignoreCase = true)) {
      text = text.dropLast(2).trim()
    }
    val value = text.toFloatOrNull() ?: return null
    return value.takeIf { it.isFinite() }
  }

  fun parsePeak(raw: String?): Float? {
    if (raw.isNullOrBlank()) return null
    val value = raw.trim().toFloatOrNull() ?: return null
    return value.takeIf { it.isFinite() && it >= 0f }
  }

  private fun parseR128GainDb(raw: String?): Float? {
    if (raw.isNullOrBlank()) return null
    val q78 = raw.trim().toIntOrNull() ?: return null
    // RFC 7845 R128_*_GAIN is Q7.8 relative to -23 LUFS; ReplayGain reference is -18 LUFS (+5 dB).
    return (q78 / 256f) + 5f
  }

  private fun normalizeKey(rawKey: String): String {
    val upper = rawKey.trim().uppercase(Locale.US)
    val suffix = upper.substringAfterLast(':')
    return if (suffix.startsWith("REPLAYGAIN_") || suffix.startsWith("R128_")) suffix else upper
  }

  private fun extractLameGain(field: Mp3InfoReplayGain.GainField?): Pair<Boolean, Float>? {
    if (field == null) return null
    if (field.originator == Mp3InfoReplayGain.GainField.ORIGINATOR_UNSET) return null
    if (!field.gain.isFinite()) return null
    return when (field.name) {
      Mp3InfoReplayGain.GainField.NAME_RADIO -> false to field.gain
      Mp3InfoReplayGain.GainField.NAME_AUDIOPHILE -> true to field.gain
      else -> null
    }
  }
}
