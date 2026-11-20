package com.droidamp.player.domain.model

enum class ReplayGainMode {
  OFF,
  TRACK,
  ALBUM,
}

data class DspSettings(
  val gaplessEnabled: Boolean = true,
  val crossfadeSeconds: Int = 0, // 0..12 (0 = off)
  val replayGainMode: ReplayGainMode = ReplayGainMode.OFF,
  val replayGainPreampDb: Float = 0f,
) {
  fun sanitized(): DspSettings =
    copy(
      crossfadeSeconds = crossfadeSeconds.coerceIn(MIN_CROSSFADE_SECONDS, MAX_CROSSFADE_SECONDS),
      replayGainPreampDb =
        if (replayGainPreampDb.isFinite()) {
          replayGainPreampDb.coerceIn(MIN_REPLAY_GAIN_PREAMP_DB, MAX_REPLAY_GAIN_PREAMP_DB)
        } else {
          0f
        },
    )

  companion object {
    const val MIN_CROSSFADE_SECONDS = 0
    const val MAX_CROSSFADE_SECONDS = 12
    const val MIN_REPLAY_GAIN_PREAMP_DB = -15f
    const val MAX_REPLAY_GAIN_PREAMP_DB = 15f
  }
}
