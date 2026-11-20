package com.droidamp.player.domain.model

enum class PlaybackStatus {
  STOPPED,
  PLAYING,
  PAUSED,
}

enum class RepeatMode {
  OFF,
  ALL,
  ONE,
}

enum class TimeDisplayMode {
  ELAPSED,
  REMAINING,
}

enum class VisualizerMode {
  SPECTRUM,
  OSCILLOSCOPE,
  OFF,
}

data class VisualizerFrame(
  val spectrumBars: FloatArray = FloatArray(SPECTRUM_BAR_COUNT),
  val peakDots: FloatArray = FloatArray(SPECTRUM_BAR_COUNT),
  val waveform: FloatArray = FloatArray(WAVEFORM_SAMPLE_COUNT),
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is VisualizerFrame) return false
    return spectrumBars.contentEquals(other.spectrumBars) &&
      peakDots.contentEquals(other.peakDots) &&
      waveform.contentEquals(other.waveform)
  }

  override fun hashCode(): Int {
    var result = spectrumBars.contentHashCode()
    result = 31 * result + peakDots.contentHashCode()
    result = 31 * result + waveform.contentHashCode()
    return result
  }

  companion object {
    const val SPECTRUM_BAR_COUNT = 19
    const val WAVEFORM_SAMPLE_COUNT = 64
    val SILENT = VisualizerFrame()
  }
}

data class PlaybackState(
  val status: PlaybackStatus = PlaybackStatus.STOPPED,
  val queue: List<AudioTrack> = emptyList(),
  val currentIndex: Int = -1,
  val positionMs: Long = 0L,
  val volume: Int = DEFAULT_COLD_LAUNCH_VOLUME,
  val balance: Int = 0,
  val shuffle: Boolean = false,
  val repeatMode: RepeatMode = RepeatMode.OFF,
  val timeDisplayMode: TimeDisplayMode = TimeDisplayMode.ELAPSED,
  val visualizerMode: VisualizerMode = VisualizerMode.SPECTRUM,
  val equalizer: EqualizerState = EqualizerState(),
) {
  val currentTrack: AudioTrack?
    get() = queue.getOrNull(currentIndex)

  val durationMs: Long
    get() = currentTrack?.durationMs ?: 0L

  val totalQueueDurationMs: Long
    get() = queue.sumOf { it.durationMs }

  val volumePercent: Int
    get() = ((volume.coerceIn(MIN_VOLUME, MAX_VOLUME) / MAX_VOLUME.toFloat()) * 100f).toInt()

  companion object {
    const val MIN_VOLUME = 0
    const val MAX_VOLUME = 255
    const val DEFAULT_COLD_LAUNCH_VOLUME = 204 // ~80%
    const val MIN_BALANCE = -127
    const val MAX_BALANCE = 127
  }
}
