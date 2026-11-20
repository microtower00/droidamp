package com.droidamp.player.domain.model

object WinampEqSpec {
  const val BAND_COUNT = 10
  val BAND_FREQUENCIES_HZ = intArrayOf(60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000)
  val BAND_LABELS = listOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")
  val BAND_READOUT_LABELS =
    listOf("60HZ", "170HZ", "310HZ", "600HZ", "1KHZ", "3KHZ", "6KHZ", "12KHZ", "14KHZ", "16KHZ")
  const val MIN_DB = -12f
  const val MAX_DB = 12f
  const val PREAMP_INDEX = -1

  fun clampDb(value: Float): Float = value.coerceIn(MIN_DB, MAX_DB)
}

data class EqPreset(
  val name: String,
  val preampDb: Float,
  val bandsDb: List<Float>,
) {
  init {
    require(bandsDb.size == WinampEqSpec.BAND_COUNT) {
      "Winamp EQ preset requires exactly ${WinampEqSpec.BAND_COUNT} bands."
    }
  }
}

object ClassicWinampPresets {
  val FLAT = EqPreset("FLAT", 0f, List(WinampEqSpec.BAND_COUNT) { 0f })
  val ROCK = EqPreset("ROCK", 0f, listOf(4.8f, 3.0f, -3.4f, -4.8f, -2.0f, 2.4f, 5.4f, 6.8f, 6.8f, 6.8f))
  val POP = EqPreset("POP", 0f, listOf(-1.2f, 2.8f, 4.4f, 4.8f, 3.2f, -0.8f, -1.6f, -1.6f, -1.2f, -1.2f))
  val TECHNO = EqPreset("TECHNO", 0f, listOf(4.8f, 3.6f, 0f, -3.4f, -3.0f, 0f, 4.8f, 6.0f, 6.0f, 5.4f))
  val FULL_BASS_TREBLE =
    EqPreset("FULL BASS & TREBLE", -1.0f, listOf(4.4f, 3.6f, 0f, -4.4f, -2.8f, 1.0f, 5.0f, 6.8f, 7.4f, 7.4f))
  val CLASSICAL = EqPreset("CLASSICAL", 0f, listOf(0f, 0f, 0f, 0f, 0f, 0f, -4.4f, -4.4f, -4.4f, -5.8f))
  val CLUB = EqPreset("CLUB", 0f, listOf(0f, 0f, 4.8f, 3.4f, 3.4f, 3.4f, 2.0f, 0f, 0f, 0f))
  val DANCE = EqPreset("DANCE", 0f, listOf(5.8f, 4.4f, 1.4f, 0f, 0f, -3.4f, -4.4f, -4.4f, 0f, 0f))
  val FULL_BASS =
    EqPreset("FULL BASS", -1.0f, listOf(5.8f, 5.8f, 5.8f, 3.4f, 1.0f, -2.4f, -4.8f, -6.2f, -6.8f, -6.8f))
  val FULL_TREBLE =
    EqPreset("FULL TREBLE", -1.0f, listOf(-5.8f, -5.8f, -5.8f, -2.4f, 1.4f, 6.8f, 9.6f, 9.6f, 9.6f, 10.2f))
  val LAPTOP_SPEAKERS_HEADPHONES =
    EqPreset(
      "LAPTOP SPEAKERS / HEADPHONES",
      0f,
      listOf(2.8f, 6.8f, 3.4f, -2.0f, -1.4f, 1.0f, 2.8f, 5.8f, 7.6f, 8.6f),
    )
  val LARGE_HALL = EqPreset("LARGE HALL", 0f, listOf(6.2f, 6.2f, 3.4f, 3.4f, 0f, -2.8f, -2.8f, -2.8f, 0f, 0f))
  val LIVE = EqPreset("LIVE", 0f, listOf(-2.8f, 0f, 2.4f, 3.4f, 3.4f, 3.4f, 2.4f, 1.4f, 1.4f, 1.4f))
  val PARTY = EqPreset("PARTY", 0f, listOf(4.4f, 4.4f, 0f, 0f, 0f, 0f, 0f, 0f, 4.4f, 4.4f))
  val REGGAE = EqPreset("REGGAE", 0f, listOf(0f, 0f, 0f, -3.4f, 0f, 3.8f, 3.8f, 0f, 0f, 0f))
  val SKA = EqPreset("SKA", 0f, listOf(-1.4f, -2.8f, -2.4f, 0f, 2.4f, 3.4f, 5.4f, 5.8f, 6.8f, 5.8f))
  val SOFT = EqPreset("SOFT", 0f, listOf(2.8f, 1.0f, 0f, -1.4f, 0f, 2.4f, 4.8f, 5.8f, 6.8f, 7.2f))
  val SOFT_ROCK = EqPreset("SOFT ROCK", 0f, listOf(2.4f, 2.4f, 1.4f, 0f, -2.4f, -3.4f, -2.0f, 0f, 1.4f, 5.4f))

  val ALL =
    listOf(
      FLAT,
      CLASSICAL,
      CLUB,
      DANCE,
      FULL_BASS,
      FULL_BASS_TREBLE,
      FULL_TREBLE,
      LAPTOP_SPEAKERS_HEADPHONES,
      LARGE_HALL,
      LIVE,
      PARTY,
      POP,
      REGGAE,
      ROCK,
      SKA,
      SOFT,
      SOFT_ROCK,
      TECHNO,
    )
}

data class EqualizerState(
  val enabled: Boolean = true,
  val autoEnabled: Boolean = false,
  val preampDb: Float = 0f,
  val bandsDb: List<Float> = List(WinampEqSpec.BAND_COUNT) { 0f },
  val activePresetName: String = ClassicWinampPresets.FLAT.name,
) {
  init {
    require(bandsDb.size == WinampEqSpec.BAND_COUNT) {
      "EqualizerState requires exactly ${WinampEqSpec.BAND_COUNT} bands."
    }
  }
}
