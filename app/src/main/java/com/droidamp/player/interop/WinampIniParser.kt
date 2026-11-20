package com.droidamp.player.interop

import com.droidamp.player.domain.contract.WinampIniCodec
import com.droidamp.player.domain.contract.WinampIniSnapshot
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.WinampEqSpec
import java.util.Locale

class WinampIniParser : WinampIniCodec {
  override fun parse(iniText: String): WinampIniSnapshot {
    var volume = PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME
    var pan = 0
    var shuffle = false
    var repeat = false
    var timeRemaining = false
    var eqEnabled = true
    var eqAuto = false
    var eqPreampDb = 0f
    var eqBandsDb = List(WinampEqSpec.BAND_COUNT) { 0f }
    var replayGainMode = 0
    var replayGainPreampDb = 0f
    var crossfadeSeconds = 0

    var inWinampSection = false
    val cleaned = iniText.removePrefix("\uFEFF")

    for (rawLine in cleaned.lineSequence()) {
      val line = stripIniComment(rawLine.trim())
      if (line.isEmpty()) continue

      if (line.startsWith('[') && line.endsWith(']')) {
        val section = line.substring(1, line.length - 1).trim()
        inWinampSection = section.equals("Winamp", ignoreCase = true)
        continue
      }

      if (!inWinampSection) continue

      val equalsIndex = line.indexOf('=')
      if (equalsIndex <= 0) continue

      val key = line.substring(0, equalsIndex).trim().lowercase(Locale.ROOT)
      val value = line.substring(equalsIndex + 1).trim()

      when (key) {
        "volume" ->
          value.toIntOrNull()?.let {
            volume = it.coerceIn(PlaybackState.MIN_VOLUME, PlaybackState.MAX_VOLUME)
          }
        "pan" ->
          value.toIntOrNull()?.let {
            pan = it.coerceIn(PlaybackState.MIN_BALANCE, PlaybackState.MAX_BALANCE)
          }
        "shuffle" -> parseIniBoolean(value)?.let { shuffle = it }
        "repeat" -> parseIniBoolean(value)?.let { repeat = it }
        "timemode" -> parseIniBoolean(value)?.let { timeRemaining = it }
        "eq_use" -> parseIniBoolean(value)?.let { eqEnabled = it }
        "eq_auto" -> parseIniBoolean(value)?.let { eqAuto = it }
        "eq_data" ->
          parseEqData(value)?.let { (bands, preamp) ->
            eqBandsDb = bands
            eqPreampDb = preamp
          }
        "rg_mode" ->
          value.toIntOrNull()?.let { replayGainMode = it.coerceIn(0, 2) }
        "rg_preamp" ->
          value.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }?.let {
            replayGainPreampDb = WinampEqSpec.clampDb(it)
          }
        "crossfade" ->
          value.toIntOrNull()?.let { crossfadeSeconds = it.coerceIn(0, MAX_CROSSFADE_SECONDS) }
      }
    }

    return WinampIniSnapshot(
      volume = volume,
      pan = pan,
      shuffle = shuffle,
      repeat = repeat,
      timeRemaining = timeRemaining,
      eqEnabled = eqEnabled,
      eqAuto = eqAuto,
      eqPreampDb = eqPreampDb,
      eqBandsDb = eqBandsDb,
      replayGainMode = replayGainMode,
      replayGainPreampDb = replayGainPreampDb,
      crossfadeSeconds = crossfadeSeconds,
    )
  }

  override fun encode(snapshot: WinampIniSnapshot): String {
    val clampedVolume =
      snapshot.volume.coerceIn(PlaybackState.MIN_VOLUME, PlaybackState.MAX_VOLUME)
    val clampedPan =
      snapshot.pan.coerceIn(PlaybackState.MIN_BALANCE, PlaybackState.MAX_BALANCE)
    val clampedRgMode = snapshot.replayGainMode.coerceIn(0, 2)
    val safeRgPreamp =
      if (snapshot.replayGainPreampDb.isNaN()) {
        0f
      } else {
        WinampEqSpec.clampDb(snapshot.replayGainPreampDb)
      }
    val normalizedRgPreamp = if (safeRgPreamp == 0f) 0f else safeRgPreamp
    val clampedCrossfade = snapshot.crossfadeSeconds.coerceIn(0, MAX_CROSSFADE_SECONDS)

    val bandBytes =
      (0 until WinampEqSpec.BAND_COUNT).map { index ->
        val db = snapshot.eqBandsDb.getOrElse(index) { 0f }
        WinampEqByteScale.encodeDbToByte(db)
      }
    val preampByte = WinampEqByteScale.encodeDbToByte(snapshot.eqPreampDb)
    val eqDataStr = (bandBytes + preampByte).joinToString(",")

    return buildString {
      appendLine("[Winamp]")
      appendLine("volume=$clampedVolume")
      appendLine("pan=$clampedPan")
      appendLine("shuffle=${boolToIniInt(snapshot.shuffle)}")
      appendLine("repeat=${boolToIniInt(snapshot.repeat)}")
      appendLine("timemode=${boolToIniInt(snapshot.timeRemaining)}")
      appendLine("eq_use=${boolToIniInt(snapshot.eqEnabled)}")
      appendLine("eq_auto=${boolToIniInt(snapshot.eqAuto)}")
      appendLine("eq_data=$eqDataStr")
      appendLine("rg_mode=$clampedRgMode")
      appendLine("rg_preamp=${formatFloatUs(normalizedRgPreamp)}")
      append("crossfade=$clampedCrossfade\n")
    }
  }

  private fun stripIniComment(line: String): String {
    val semicolonIdx = line.indexOf(';')
    val hashIdx = line.indexOf('#')
    val commentIdx =
      when {
        semicolonIdx >= 0 && hashIdx >= 0 -> minOf(semicolonIdx, hashIdx)
        semicolonIdx >= 0 -> semicolonIdx
        hashIdx >= 0 -> hashIdx
        else -> -1
      }
    return if (commentIdx >= 0) line.substring(0, commentIdx).trim() else line
  }

  private fun parseIniBoolean(raw: String): Boolean? =
    when (raw.lowercase(Locale.ROOT)) {
      "1", "true", "yes", "on" -> true
      "0", "false", "no", "off" -> false
      else -> raw.toIntOrNull()?.let { it != 0 }
    }

  private fun parseEqData(raw: String): Pair<List<Float>, Float>? {
    val tokens =
      raw.split(',', ' ', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (tokens.size < WinampEqSpec.BAND_COUNT + 1) return null

    val relevant = tokens.take(WinampEqSpec.BAND_COUNT + 1)
    val hasDecimalPoint = relevant.any { it.contains('.') }
    val hasSignPrefix = relevant.any { it.startsWith('-') || it.startsWith('+') }
    val allFiniteDbRange =
      relevant.all { token ->
        val parsed = token.toFloatOrNull()
        parsed != null && parsed.isFinite() && parsed in WinampEqSpec.MIN_DB..WinampEqSpec.MAX_DB
      }
    val isDbMode = hasDecimalPoint || (hasSignPrefix && allFiniteDbRange)

    return if (isDbMode) {
      val floats =
        relevant.map { token ->
          val parsed = token.toFloatOrNull() ?: return null
          if (!parsed.isFinite()) return null
          WinampEqSpec.clampDb(parsed)
        }
      floats.subList(0, WinampEqSpec.BAND_COUNT) to floats[WinampEqSpec.BAND_COUNT]
    } else {
      val dbs =
        relevant.map { token ->
          val intVal = token.toIntOrNull() ?: return null
          WinampEqByteScale.decodeByteToDb(
            raw = intVal.coerceIn(WinampEqByteScale.MIN_BYTE, WinampEqByteScale.MAX_BYTE),
          )
        }
      dbs.subList(0, WinampEqSpec.BAND_COUNT) to dbs[WinampEqSpec.BAND_COUNT]
    }
  }

  private fun boolToIniInt(value: Boolean): Int = if (value) 1 else 0

  private fun formatFloatUs(value: Float): String {
    val formatted = String.format(Locale.US, "%.4f", value).trimEnd('0')
    return if (formatted.endsWith('.')) "${formatted}0" else formatted
  }

  private companion object {
    const val MAX_CROSSFADE_SECONDS = 12
  }
}
