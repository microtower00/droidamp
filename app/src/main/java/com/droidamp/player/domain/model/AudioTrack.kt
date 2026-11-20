package com.droidamp.player.domain.model

enum class ChannelMode {
  MONO,
  STEREO,
}

data class AudioTrack(
  val id: String,
  val uriString: String,
  val title: String,
  val artist: String,
  val album: String = "",
  val fileName: String,
  val folderPath: String,
  val durationMs: Long,
  val bitrateKbps: Int = 320,
  val sampleRateHz: Int = 44_100,
  val channelMode: ChannelMode = ChannelMode.STEREO,
) {
  val displayLabel: String
    get() =
      when {
        artist.isNotBlank() && title.isNotBlank() -> "$artist - $title"
        title.isNotBlank() -> title
        else -> fileName.substringBeforeLast('.')
      }

  val sampleRateKhz: Int
    get() = sampleRateHz / 1000
}

fun formatWinampDuration(durationMs: Long): String {
  val totalSeconds = (durationMs.coerceAtLeast(0L)) / 1000L
  val minutes = totalSeconds / 60L
  val seconds = totalSeconds % 60L
  return "%02d:%02d".format(minutes, seconds)
}
