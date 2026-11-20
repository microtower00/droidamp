package com.droidamp.player.e2e.fixtures

import com.droidamp.player.domain.contract.WinampIniSnapshot
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.WinampEqSpec
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.roundToInt

object SyntheticWinampFixtures {

  private const val EQF_HEADER_TEXT = "Winamp EQ library file v1.1"
  private val EQF_HEADER_SUFFIX = byteArrayOf(0x1A, '!'.code.toByte(), '-'.code.toByte(), '-'.code.toByte())
  const val EQF_HEADER_SIZE = 31
  const val EQF_NAME_FIELD_SIZE = 257
  const val EQF_RECORD_SIZE = 299
  const val EQF_CLASSIC_ENTRY_SIZE = 268

  data class Q2RuleSpec(
    val pattern: String,
    val preset: EqPreset,
  )

  data class RawEqRecord(
    val name: String,
    val bandBytes0To63: List<Int>,
    val preampByte0To63: Int,
  )

  // ---- Gain Mapping (31-centered piecewise 0..63 <-> +12..-12 dB) ----

  fun dbToWinampByte(db: Float): Int {
    val safeDb = if (db.isNaN()) 0f else WinampEqSpec.clampDb(db)
    return if (safeDb >= 0f) {
      ((WinampEqSpec.MAX_DB - safeDb) / WinampEqSpec.MAX_DB * 31f)
        .roundToInt()
        .coerceIn(0, 31)
    } else {
      31 +
        ((-safeDb / WinampEqSpec.MAX_DB) * 32f)
          .roundToInt()
          .coerceIn(0, 32)
    }
  }

  fun winampByteToDb(raw: Int): Float {
    require(raw in 0..63) { "Winamp EQ byte must be in 0..63, got $raw" }
    return if (raw <= 31) {
      ((31 - raw) / 31f) * WinampEqSpec.MAX_DB
    } else {
      -((raw - 31) / 32f) * WinampEqSpec.MAX_DB
    }
  }

  // ---- .eqf (299-byte single preset + Tier 2 corrupt variants) ----

  fun eqfHeaderBytes(): ByteArray =
    EQF_HEADER_TEXT.toByteArray(Charsets.US_ASCII) + EQF_HEADER_SUFFIX

  fun eqfBytes(preset: EqPreset): ByteArray =
    eqfRawBytes(
      name = preset.name,
      bandBytes0To63 = preset.bandsDb.map(::dbToWinampByte),
      preampByte0To63 = dbToWinampByte(preset.preampDb),
    )

  fun eqfRawBytes(
    name: String,
    bandBytes0To63: List<Int>,
    preampByte0To63: Int,
    validateRange: Boolean = true,
  ): ByteArray {
    require(bandBytes0To63.size == WinampEqSpec.BAND_COUNT) {
      "Expected ${WinampEqSpec.BAND_COUNT} bands"
    }
    val out = ByteArray(EQF_RECORD_SIZE)
    val header = eqfHeaderBytes()
    System.arraycopy(header, 0, out, 0, EQF_HEADER_SIZE)
    writeNullPaddedName(out, EQF_HEADER_SIZE, EQF_NAME_FIELD_SIZE, name)

    for (i in 0 until WinampEqSpec.BAND_COUNT) {
      val v = bandBytes0To63[i]
      if (validateRange) require(v in 0..63) { "Band[$i]=$v out of 0..63" }
      out[EQF_HEADER_SIZE + EQF_NAME_FIELD_SIZE + i] = (v and 0xFF).toByte()
    }
    if (validateRange) require(preampByte0To63 in 0..63) { "Preamp=$preampByte0To63 out of 0..63" }
    out[EQF_RECORD_SIZE - 1] = (preampByte0To63 and 0xFF).toByte()
    return out
  }

  fun truncatedEqf(lengthBytes: Int = 298, preset: EqPreset = ClassicWinampPresets.ROCK): ByteArray =
    eqfBytes(preset).copyOf(lengthBytes.coerceIn(0, EQF_RECORD_SIZE - 1))

  fun oversizedEqf(extraBytes: Int = 1, preset: EqPreset = ClassicWinampPresets.ROCK): ByteArray =
    eqfBytes(preset) + ByteArray(extraBytes.coerceAtLeast(1))

  fun badHeaderEqf(preset: EqPreset = ClassicWinampPresets.ROCK): ByteArray =
    eqfBytes(preset).apply {
      "BAD_HEADER".toByteArray(Charsets.US_ASCII).copyInto(this, destinationOffset = 0)
    }

  fun outOfRangeBandEqf(bandIndex: Int = 0, rawByte: Int = 64): ByteArray =
    eqfRawBytes(
      name = "OOR_BAND",
      bandBytes0To63 = List(WinampEqSpec.BAND_COUNT) { i -> if (i == bandIndex) rawByte else 31 },
      preampByte0To63 = 31,
      validateRange = false,
    )

  fun outOfRangePreampEqf(rawByte: Int = 255): ByteArray =
    eqfRawBytes(
      name = "OOR_PREAMP",
      bandBytes0To63 = List(WinampEqSpec.BAND_COUNT) { 31 },
      preampByte0To63 = rawByte,
      validateRange = false,
    )

  // ---- winamp.q1 (N * 299 concatenated + classic 31 + N * 268) ----

  fun q1Bytes(presets: List<EqPreset>): ByteArray {
    val out = ByteArrayOutputStream(presets.size * EQF_RECORD_SIZE)
    presets.forEach { out.write(eqfBytes(it)) }
    return out.toByteArray()
  }

  fun q1RawBytes(records: List<RawEqRecord>): ByteArray {
    val out = ByteArrayOutputStream(records.size * EQF_RECORD_SIZE)
    records.forEach { rec ->
      out.write(eqfRawBytes(rec.name, rec.bandBytes0To63, rec.preampByte0To63))
    }
    return out.toByteArray()
  }

  fun q1ClassicSingleHeaderBytes(presets: List<EqPreset>): ByteArray {
    val out = ByteArrayOutputStream(EQF_HEADER_SIZE + presets.size * EQF_CLASSIC_ENTRY_SIZE)
    out.write(eqfHeaderBytes())
    presets.forEach { preset ->
      val entry = ByteArray(EQF_CLASSIC_ENTRY_SIZE)
      writeNullPaddedName(entry, 0, EQF_NAME_FIELD_SIZE, preset.name)
      preset.bandsDb.forEachIndexed { idx, db ->
        entry[EQF_NAME_FIELD_SIZE + idx] = dbToWinampByte(db).toByte()
      }
      entry[EQF_CLASSIC_ENTRY_SIZE - 1] = dbToWinampByte(preset.preampDb).toByte()
      out.write(entry)
    }
    return out.toByteArray()
  }

  fun q1InvalidSizeBytes(
    presets: List<EqPreset> = listOf(ClassicWinampPresets.FLAT),
    extraBytes: Int = 7,
  ): ByteArray = q1Bytes(presets) + ByteArray(extraBytes.coerceAtLeast(1))

  // ---- winamp.q2 (Auto-load EQ rules) ----

  fun q2Bytes(rules: List<Q2RuleSpec>): ByteArray =
    q1Bytes(rules.map { EqPreset(it.pattern, it.preset.preampDb, it.preset.bandsDb) })

  fun q2RawBytes(records: List<RawEqRecord>): ByteArray =
    q1RawBytes(records)

  fun q2ClassicSingleHeaderBytes(rules: List<Q2RuleSpec>): ByteArray =
    q1ClassicSingleHeaderBytes(rules.map { EqPreset(it.pattern, it.preset.preampDb, it.preset.bandsDb) })

  // ---- winamp.ini ([Winamp] text fixtures) ----

  fun winampIni(
    snapshot: WinampIniSnapshot,
    lineEnding: String = "\n",
    includeBom: Boolean = false,
    includeComments: Boolean = false,
    unknownKeys: Map<String, String> = emptyMap(),
    extraSections: Map<String, Map<String, String>> = emptyMap(),
    spaceSeparatedEqData: Boolean = false,
    rawEqDataOverride: List<Int>? = null,
  ): String {
    val sep = if (spaceSeparatedEqData) " " else ","
    val eqValues = if (rawEqDataOverride != null) {
      rawEqDataOverride.joinToString(sep)
    } else {
      (snapshot.eqBandsDb.take(WinampEqSpec.BAND_COUNT) + snapshot.eqPreampDb)
        .joinToString(sep) { dbToWinampByte(it).toString() }
    }

    val lines = mutableListOf<String>()
    if (includeComments) {
      lines += "; Synthetic Winamp INI fixture"
      lines += "# Generated for DroidAmp E2E tests"
    }
    extraSections.forEach { (section, entries) ->
      lines += "[$section]"
      entries.forEach { (k, v) -> lines += "$k=$v" }
    }
    lines += "[Winamp]"
    lines += "volume=${snapshot.volume}"
    lines += "pan=${snapshot.pan}"
    lines += "shuffle=${if (snapshot.shuffle) 1 else 0}"
    lines += "repeat=${if (snapshot.repeat) 1 else 0}"
    lines += "timemode=${if (snapshot.timeRemaining) 1 else 0}"
    lines += "eq_use=${if (snapshot.eqEnabled) 1 else 0}"
    lines += "eq_auto=${if (snapshot.eqAuto) 1 else 0}"
    lines += "eq_data=$eqValues"
    lines += "rg_mode=${snapshot.replayGainMode}"
    lines += "rg_preamp=${String.format(Locale.US, "%.2f", snapshot.replayGainPreampDb)}"
    lines += "crossfade=${snapshot.crossfadeSeconds}"
    unknownKeys.forEach { (k, v) -> lines += "$k=$v" }

    val body = lines.joinToString(lineEnding) + lineEnding
    return if (includeBom) "\uFEFF$body" else body
  }

  fun partialWinampIni(lineEnding: String = "\n"): String =
    listOf("[Winamp]", "volume=128", "pan=-64", "shuffle=1").joinToString(lineEnding) + lineEnding

  fun outOfRangeWinampIni(lineEnding: String = "\n"): String = listOf(
    "[Winamp]",
    "volume=999",
    "pan=-500",
    "shuffle=true",
    "repeat=false",
    "timemode=1",
    "eq_use=1",
    "eq_auto=0",
    "eq_data=99,0,63,31,31,31,31,31,31,31,200",
    "rg_mode=9",
    "rg_preamp=42.5",
    "crossfade=99",
  ).joinToString(lineEnding) + lineEnding

  // ---- .m3u / .m3u8 / .pls Playlist Fixtures ----

  data class PlaylistEntrySpec(
    val pathOrUri: String,
    val title: String,
    val durationSeconds: Long,
  )

  fun m3u8Content(
    entries: List<PlaylistEntrySpec>,
    extended: Boolean = true,
    lineEnding: String = "\n",
    includeBom: Boolean = false,
    includeComments: Boolean = false,
  ): String {
    val lines = mutableListOf<String>()
    if (extended) lines += "#EXTM3U"
    if (includeComments) lines += "# Synthetic playlist fixture"
    entries.forEach { entry ->
      if (extended) lines += "#EXTINF:${entry.durationSeconds},${entry.title}"
      lines += entry.pathOrUri
    }
    val text = lines.joinToString(lineEnding) + lineEnding
    return if (includeBom) "\uFEFF$text" else text
  }

  fun plsContent(
    entries: List<PlaylistEntrySpec>,
    lineEnding: String = "\n",
    includeBom: Boolean = false,
    reverseOrderKeys: Boolean = false,
    declaredCountOverride: Int? = null,
  ): String {
    val lines = mutableListOf("[playlist]")
    val indexed = entries.mapIndexed { idx, spec -> (idx + 1) to spec }
    val ordered = if (reverseOrderKeys) indexed.reversed() else indexed
    ordered.forEach { (n, spec) ->
      lines += "File$n=${spec.pathOrUri}"
      lines += "Title$n=${spec.title}"
      lines += "Length$n=${spec.durationSeconds}"
    }
    lines += "NumberOfEntries=${declaredCountOverride ?: entries.size}"
    lines += "Version=2"
    val text = lines.joinToString(lineEnding) + lineEnding
    return if (includeBom) "\uFEFF$text" else text
  }

  fun relativeAndWindowsPathPlaylistEntries(): List<PlaylistEntrySpec> = listOf(
    PlaylistEntrySpec("01_local.wav", "Local Track", 12),
    PlaylistEntrySpec("Artist\\Album\\02_windows_sep.mp3", "Windows Track", 25),
    PlaylistEntrySpec("./sub/03_dot_relative.wav", "Subfolder Track", 8),
    PlaylistEntrySpec("../Sibling/04_parent_relative.mp3", "Sibling Track", 30),
    PlaylistEntrySpec("missing/99_missing_target.mp3", "Missing Target", 10),
  )

  fun tracksToEntrySpecs(tracks: List<AudioTrack>): List<PlaylistEntrySpec> =
    tracks.map { track ->
      PlaylistEntrySpec(
        pathOrUri = track.uriString.ifBlank { "${track.folderPath}/${track.fileName}" },
        title = track.displayLabel,
        durationSeconds = (track.durationMs / 1000L).coerceAtLeast(0L),
      )
    }

  private fun writeNullPaddedName(
    dest: ByteArray,
    offset: Int,
    maxFieldSize: Int,
    name: String,
  ) {
    val raw = name.trim().toByteArray(Charsets.UTF_8)
    val copyLen = raw.size.coerceAtMost(maxFieldSize - 1) // keep final byte 0x00
    System.arraycopy(raw, 0, dest, offset, copyLen)
  }
}
