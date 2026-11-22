package com.droidamp.player.interop

import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Identified Winamp / DroidAmp file format returned by [DroidAmpFileSniffer] (`ADR-029`),
 * enabling 1-tap wrong-slot redirect dialogs across Skin, EQ, Migration, and Playlist importers.
 */
sealed interface DetectedWinampFormat {
  data class WszSkin(
    val isAugmented: Boolean,
    val entryCount: Int,
  ) : DetectedWinampFormat

  data object EqfSinglePreset : DetectedWinampFormat

  data object EqLibraryQ1 : DetectedWinampFormat

  data object AutoEqQ2 : DetectedWinampFormat

  data object WinampIni : DetectedWinampFormat

  data object PlaylistM3u : DetectedWinampFormat

  data object PlaylistPls : DetectedWinampFormat

  data class Unsupported(
    val reason: String,
  ) : DetectedWinampFormat
}

/**
 * Pure-Kotlin magic-byte and header inspector for all supported Winamp / DroidAmp file types:
 * - `.wsz` (Legacy Winamp 2.x & Augmented DroidAmp ZIP skins)
 * - `.eqf` (299-byte single Winamp EQ preset)
 * - `winamp.q1` (multi-record Winamp EQ preset library)
 * - `winamp.q2` (multi-record Winamp Auto-EQ rule table)
 * - `winamp.ini` (`[Winamp]` configuration file)
 * - `.m3u` / `.m3u8` (`#EXTM3U` / track path playlist)
 * - `.pls` (`[playlist]` INI-style playlist)
 */
object DroidAmpFileSniffer {
  private val WSZ_MARKER_BASENAMES =
    setOf(
      "droidamp.json",
      "main.bmp",
      "cbuttons.bmp",
      "eqmain.bmp",
      "titlebar.bmp",
      "pledit.bmp",
      "viscolor.txt",
      "pledit.txt",
      "shufrep.bmp",
      "volume.bmp",
      "posbar.bmp",
      "nums_ex.bmp",
      "numbers.bmp",
    )

  private val AUDIO_EXT_HINTS =
    listOf(".mp3", ".flac", ".ogg", ".opus", ".wav", ".m4a", ".aac", ".wv")

  fun sniff(
    bytes: ByteArray,
    fileNameHint: String? = null,
  ): DetectedWinampFormat {
    if (bytes.isEmpty()) {
      return DetectedWinampFormat.Unsupported("Selected file is empty (0 bytes).")
    }

    val normalizedHint = fileNameHint?.trim()?.lowercase(Locale.ROOT).orEmpty()

    // 1. Check ZIP magic (`PK\x03\x04`, `PK\x05\x06`, `PK\x07\x08`) -> `.wsz` vs non-skin ZIP
    if (hasZipMagic(bytes)) {
      return inspectZip(bytes)
    }

    // 2. Check binary Winamp EQ signature (`"Winamp EQ library file v1.1\x1A!--"`)
    if (WinampEqBinaryContainer.hasHeaderAt(bytes, 0)) {
      return inspectEqBinary(bytes, normalizedHint)
    }

    // 3. Reject obvious binary files (NUL bytes in first 512 bytes) before text heuristics
    val sampleLen = minOf(bytes.size, 4096)
    val nulCheckLen = minOf(bytes.size, 512)
    for (i in 0 until nulCheckLen) {
      if (bytes[i] == 0.toByte()) {
        return DetectedWinampFormat.Unsupported("Unrecognized binary file format.")
      }
    }

    val textSample =
      bytes
        .copyOfRange(0, sampleLen)
        .toString(Charsets.UTF_8)
        .removePrefix("\uFEFF")

    return inspectTextSample(textSample, normalizedHint)
  }

  private fun hasZipMagic(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
      bytes[0] == 0x50.toByte() &&
      bytes[1] == 0x4B.toByte() &&
      (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

  private fun inspectZip(bytes: ByteArray): DetectedWinampFormat {
    var entryCount = 0
    var hasDroidAmpManifest = false
    var hasSkinMarker = false

    val inspectOk =
      runCatching {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
          while (entryCount < 512) {
            val entry = zis.nextEntry ?: break
            try {
              if (entry.isDirectory) continue
              entryCount++
              val normalized = entry.name.replace('\\', '/').trim('/')
              val baseName = normalized.substringAfterLast('/').lowercase(Locale.ROOT)
              if (baseName == "droidamp.json") {
                hasDroidAmpManifest = true
                hasSkinMarker = true
              } else if (baseName in WSZ_MARKER_BASENAMES) {
                hasSkinMarker = true
              } else if (normalized.lowercase(Locale.ROOT).startsWith("sprites/") &&
                baseName.endsWith(".png")
              ) {
                hasSkinMarker = true
              }
            } finally {
              zis.closeEntry()
            }
          }
        }
      }.isSuccess

    if (!inspectOk || entryCount == 0) {
      return DetectedWinampFormat.Unsupported("Corrupt or empty ZIP archive.")
    }
    if (!hasSkinMarker) {
      return DetectedWinampFormat.Unsupported(
        "ZIP archive does not contain any Winamp .wsz skin bitmaps or droidamp.json.",
      )
    }
    return DetectedWinampFormat.WszSkin(
      isAugmented = hasDroidAmpManifest,
      entryCount = entryCount,
    )
  }

  private fun inspectEqBinary(
    bytes: ByteArray,
    normalizedHint: String,
  ): DetectedWinampFormat {
    val isSingleSize = bytes.size == WinampEqBinaryContainer.RECORD_SIZE
    val isMultiFullRecords =
      bytes.size >= WinampEqBinaryContainer.RECORD_SIZE &&
        bytes.size % WinampEqBinaryContainer.RECORD_SIZE == 0
    val isSharedHeaderMulti =
      bytes.size >= WinampEqBinaryContainer.HEADER_SIZE &&
        (bytes.size - WinampEqBinaryContainer.HEADER_SIZE) % WinampEqBinaryContainer.PAYLOAD_SIZE == 0

    if (!isSingleSize && !isMultiFullRecords && !isSharedHeaderMulti) {
      return DetectedWinampFormat.Unsupported(
        "Corrupt Winamp EQ file (${bytes.size} bytes does not match .eqf/.q1/.q2 record layout).",
      )
    }

    if (normalizedHint.endsWith(".q2")) return DetectedWinampFormat.AutoEqQ2
    if (normalizedHint.endsWith(".q1")) return DetectedWinampFormat.EqLibraryQ1
    if (isSingleSize && !normalizedHint.endsWith(".q1") && !normalizedHint.endsWith(".q2")) {
      return DetectedWinampFormat.EqfSinglePreset
    }

    // Disambiguate multi-record .q1 vs .q2 by inspecting record labels for path/wildcard patterns
    val records =
      runCatching {
        WinampEqBinaryContainer.parseLibraryRecords(bytes, formatName = "eq-lib")
      }.getOrNull() ?: return DetectedWinampFormat.Unsupported("Corrupt Winamp EQ record table.")

    val looksLikeQ2Rule =
      records.any { rec ->
        val lbl = rec.label.lowercase(Locale.ROOT)
        lbl.contains('*') ||
          lbl.contains('?') ||
          lbl.contains('/') ||
          lbl.contains('\\') ||
          AUDIO_EXT_HINTS.any { ext -> lbl.endsWith(ext) }
      }

    return if (looksLikeQ2Rule) {
      DetectedWinampFormat.AutoEqQ2
    } else {
      DetectedWinampFormat.EqLibraryQ1
    }
  }

  private fun inspectTextSample(
    textSample: String,
    normalizedHint: String,
  ): DetectedWinampFormat {
    val nonEmptyLines =
      textSample
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(40)
        .toList()

    if (nonEmptyLines.isEmpty()) {
      return DetectedWinampFormat.Unsupported("File contains only whitespace.")
    }

    val firstLine = nonEmptyLines.first()
    if (firstLine.startsWith("#EXTM3U", ignoreCase = true) ||
      nonEmptyLines.any { it.startsWith("#EXTINF:", ignoreCase = true) }
    ) {
      return DetectedWinampFormat.PlaylistM3u
    }

    for (line in nonEmptyLines) {
      if (line.startsWith(';') || line.startsWith('#')) continue
      if (line.equals("[Winamp]", ignoreCase = true)) {
        return DetectedWinampFormat.WinampIni
      }
      if (line.equals("[playlist]", ignoreCase = true)) {
        return DetectedWinampFormat.PlaylistPls
      }
    }

    if (nonEmptyLines.any { PLS_FILE_ENTRY_REGEX.matches(it) }) {
      return DetectedWinampFormat.PlaylistPls
    }

    if (normalizedHint.endsWith(".m3u") || normalizedHint.endsWith(".m3u8")) {
      val hasTrackLikeLines =
        nonEmptyLines.any { line ->
          !line.startsWith('#') &&
            (line.contains('/') || line.contains('\\') || AUDIO_EXT_HINTS.any { line.lowercase(Locale.ROOT).endsWith(it) })
        }
      if (hasTrackLikeLines) {
        return DetectedWinampFormat.PlaylistM3u
      }
    }

    return DetectedWinampFormat.Unsupported("Unrecognized file format.")
  }

  private val PLS_FILE_ENTRY_REGEX = Regex("^file\\d+\\s*=\\s*.+$", RegexOption.IGNORE_CASE)
}
