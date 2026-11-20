package com.droidamp.player.interop

import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.contract.Q2Codec
import com.droidamp.player.domain.model.EqPreset

class WinampQ2Codec : Q2Codec {
  override fun parseAutoPresetsQ2(bytes: ByteArray): List<AutoEqRule> =
    WinampEqBinaryContainer.parseLibraryRecords(bytes, formatName = "winamp.q2")
      .filter { record -> record.label.isNotBlank() }
      .map { record ->
        AutoEqRule(
          pattern = record.label,
          preset = EqPreset(
            name = record.label,
            preampDb = record.preampDb,
            bandsDb = record.bandsDb,
          ),
        )
      }

  override fun encodeAutoPresetsQ2(rules: List<AutoEqRule>): ByteArray {
    val records =
      rules.mapIndexed { index, rule ->
        require(rule.pattern.isNotBlank()) {
          "winamp.q2 rule pattern at index $index must not be blank."
        }
        WinampEqBinaryRecord(
          label = rule.pattern.trim(),
          preampDb = rule.preset.preampDb,
          bandsDb = rule.preset.bandsDb,
        )
      }
    return WinampEqBinaryContainer.encodeLibraryRecords(records)
  }
}
