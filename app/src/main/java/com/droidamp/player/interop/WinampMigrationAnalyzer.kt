package com.droidamp.player.interop

import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.contract.WinampIniCodec
import com.droidamp.player.domain.contract.WinampIniSnapshot
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.WinampEqSpec
import java.util.Locale

data class IniAnalysisResult(
  val snapshot: WinampIniSnapshot,
  val matchedKeys: List<String>,
  val skippedLines: Int,
  val hasWinampSection: Boolean,
)

data class Q1AnalysisResult(
  val presets: List<EqPreset>,
  val skippedBlocks: Int,
)

data class Q2AnalysisResult(
  val rules: List<AutoEqRule>,
  val skippedBlocks: Int,
)

/**
 * Defensive line-by-line / block-by-block analyzer for partial or corrupted `winamp.ini`,
 * `winamp.q1`, and `winamp.q2` files (`ADR-029` "Inform + Allow Overriding").
 */
object WinampMigrationAnalyzer {

  private val RECOGNIZED_INI_KEYS =
    setOf(
      "volume",
      "pan",
      "shuffle",
      "repeat",
      "timemode",
      "eq_use",
      "eq_auto",
      "eq_data",
      "rg_mode",
      "rg_preamp",
      "crossfade",
    )

  fun analyzeIni(
    iniText: String,
    parser: WinampIniCodec = WinampIniParser(),
  ): IniAnalysisResult {
    val cleaned = iniText.removePrefix("\uFEFF")
    var inWinampSection = false
    var hasWinampSection = false
    var skippedLines = 0
    val matchedKeys = ArrayList<String>()

    for (rawLine in cleaned.lineSequence()) {
      val trimmed = stripComment(rawLine.trim())
      if (trimmed.isEmpty()) continue

      if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
        val section = trimmed.substring(1, trimmed.length - 1).trim()
        inWinampSection = section.equals("Winamp", ignoreCase = true)
        if (inWinampSection) {
          hasWinampSection = true
        }
        continue
      }

      if (!inWinampSection) continue

      val equalsIdx = trimmed.indexOf('=')
      if (equalsIdx <= 0) {
        skippedLines++
        continue
      }
      val key = trimmed.substring(0, equalsIdx).trim().lowercase(Locale.ROOT)
      val value = trimmed.substring(equalsIdx + 1).trim()
      if (key in RECOGNIZED_INI_KEYS && isValidIniValue(key, value)) {
        if (key !in matchedKeys) {
          matchedKeys.add(key)
        }
      } else {
        skippedLines++
      }
    }

    val snapshot = parser.parse(iniText)
    return IniAnalysisResult(
      snapshot = snapshot,
      matchedKeys = matchedKeys,
      skippedLines = skippedLines,
      hasWinampSection = hasWinampSection,
    )
  }

  fun analyzeQ1(bytes: ByteArray): Q1AnalysisResult {
    val (records, skipped) = parseBinaryRecordsDefensively(bytes)
    val presets =
      records.mapIndexed { idx, rec ->
        EqPreset(
          name = rec.label.ifBlank { "Preset ${idx + 1}" },
          preampDb = rec.preampDb,
          bandsDb = rec.bandsDb,
        )
      }
    return Q1AnalysisResult(presets = presets, skippedBlocks = skipped)
  }

  fun analyzeQ2(bytes: ByteArray): Q2AnalysisResult {
    val (records, skipped) = parseBinaryRecordsDefensively(bytes)
    var skippedBlankLabels = 0
    val rules = ArrayList<AutoEqRule>(records.size)
    for (rec in records) {
      val pattern = rec.label.trim()
      if (pattern.isEmpty()) {
        skippedBlankLabels++
      } else {
        rules.add(
          AutoEqRule(
            pattern = pattern,
            preset =
              EqPreset(
                name = pattern,
                preampDb = rec.preampDb,
                bandsDb = rec.bandsDb,
              ),
          ),
        )
      }
    }
    return Q2AnalysisResult(rules = rules, skippedBlocks = skipped + skippedBlankLabels)
  }

  private fun parseBinaryRecordsDefensively(
    bytes: ByteArray,
  ): Pair<List<WinampEqBinaryRecord>, Int> {
    if (bytes.isEmpty()) return emptyList<WinampEqBinaryRecord>() to 0
    if (!WinampEqBinaryContainer.hasHeaderAt(bytes, 0)) {
      return emptyList<WinampEqBinaryRecord>() to 1
    }
    if (bytes.size == WinampEqBinaryContainer.HEADER_SIZE) {
      return emptyList<WinampEqBinaryRecord>() to 0
    }

    val records = ArrayList<WinampEqBinaryRecord>()
    var skipped = 0

    // Layout 1: Concatenated full 299-byte records (each starts with HEADER_BYTES)
    val looksConcatenated =
      bytes.size >= WinampEqBinaryContainer.RECORD_SIZE &&
        (bytes.size == WinampEqBinaryContainer.RECORD_SIZE ||
          WinampEqBinaryContainer.hasHeaderAt(bytes, WinampEqBinaryContainer.RECORD_SIZE))

    if (looksConcatenated) {
      var offset = 0
      while (offset < bytes.size) {
        if (offset + WinampEqBinaryContainer.RECORD_SIZE > bytes.size) {
          skipped++
          break
        }
        val parsed =
          if (WinampEqBinaryContainer.hasHeaderAt(bytes, offset)) {
            WinampEqBinaryContainer.parsePayloadOrNull(
              bytes,
              offset + WinampEqBinaryContainer.HEADER_SIZE,
            )
          } else {
            null
          }
        if (parsed != null) {
          records.add(parsed)
        } else {
          skipped++
        }
        offset += WinampEqBinaryContainer.RECORD_SIZE
      }
      return records to skipped
    }

    // Layout 2: Shared 31-byte header + N * 268-byte payloads
    var offset = WinampEqBinaryContainer.HEADER_SIZE
    while (offset < bytes.size) {
      if (offset + WinampEqBinaryContainer.PAYLOAD_SIZE > bytes.size) {
        skipped++
        break
      }
      val parsed = WinampEqBinaryContainer.parsePayloadOrNull(bytes, offset)
      if (parsed != null) {
        records.add(parsed)
      } else {
        skipped++
      }
      offset += WinampEqBinaryContainer.PAYLOAD_SIZE
    }
    return records to skipped
  }

  private fun stripComment(line: String): String {
    val semi = line.indexOf(';')
    val hash = line.indexOf('#')
    val idx =
      when {
        semi >= 0 && hash >= 0 -> minOf(semi, hash)
        semi >= 0 -> semi
        hash >= 0 -> hash
        else -> -1
      }
    return if (idx >= 0) line.substring(0, idx).trim() else line
  }

  private fun isValidIniValue(key: String, value: String): Boolean =
    when (key) {
      "volume", "pan", "rg_mode", "crossfade" -> value.toIntOrNull() != null
      "shuffle", "repeat", "timemode", "eq_use", "eq_auto" -> {
        value.lowercase(Locale.ROOT) in setOf("0", "1", "true", "false", "yes", "no", "on", "off") ||
          value.toIntOrNull() != null
      }
      "rg_preamp" -> value.replace(',', '.').toFloatOrNull()?.isFinite() == true
      "eq_data" -> {
        val tokens = value.split(',', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        tokens.size >= WinampEqSpec.BAND_COUNT + 1 &&
          tokens.take(WinampEqSpec.BAND_COUNT + 1).all { tok ->
            tok.toFloatOrNull()?.isFinite() == true || tok.toIntOrNull() != null
          }
      }
      else -> false
    }
}
