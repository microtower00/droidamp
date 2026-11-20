package com.droidamp.player.interop

import com.droidamp.player.domain.model.WinampEqSpec
import kotlin.math.roundToInt

/**
 * Converts between DroidAmp floating-point dB gains (`-12f..+12f`) and Winamp's inverted
 * 6-bit integer gain byte (`0..63`, where `0 = +12 dB`, `31 = 0 dB`, `63 = -12 dB`).
 *
 * Uses a 31-centered piecewise linear mapping so `0.0f` dB (`FLAT`), `+12.0f` dB, and `-12.0f` dB
 * round-trip exactly, every byte `0..63` round-trips 100% byte-identically, and any float in
 * `[-12f, +12f]` round-trips within `<= 0.20f` dB.
 */
internal object WinampEqByteScale {
  const val MIN_BYTE = 0
  const val ZERO_DB_BYTE = 31
  const val MAX_BYTE = 63

  private const val POSITIVE_STEPS = ZERO_DB_BYTE.toFloat() // 31 steps for +12..0 dB
  private const val NEGATIVE_STEPS = (MAX_BYTE - ZERO_DB_BYTE).toFloat() // 32 steps for 0..-12 dB

  fun encodeDbToByte(db: Float): Int {
    val safeDb = if (db.isNaN()) 0f else WinampEqSpec.clampDb(db)
    return if (safeDb >= 0f) {
      ((WinampEqSpec.MAX_DB - safeDb) / WinampEqSpec.MAX_DB * POSITIVE_STEPS)
        .roundToInt()
        .coerceIn(MIN_BYTE, ZERO_DB_BYTE)
    } else {
      ZERO_DB_BYTE +
        ((-safeDb / WinampEqSpec.MAX_DB) * NEGATIVE_STEPS)
          .roundToInt()
          .coerceIn(0, MAX_BYTE - ZERO_DB_BYTE)
    }
  }

  fun decodeByteToDb(raw: Int, fieldLabel: String = "gain"): Float {
    require(raw in MIN_BYTE..MAX_BYTE) {
      "Invalid Winamp EQ byte for $fieldLabel: $raw (expected $MIN_BYTE..$MAX_BYTE)."
    }
    return if (raw <= ZERO_DB_BYTE) {
      ((ZERO_DB_BYTE - raw) / POSITIVE_STEPS) * WinampEqSpec.MAX_DB
    } else {
      -((raw - ZERO_DB_BYTE) / NEGATIVE_STEPS) * WinampEqSpec.MAX_DB
    }
  }

  fun decodeSignedByteToDb(byte: Byte, fieldLabel: String = "gain"): Float =
    decodeByteToDb(byte.toInt() and 0xFF, fieldLabel)
}
