package com.droidamp.player.storage

import java.util.Locale

internal object StorageSupportedFormats {
  val SUPPORTED_AUDIO_EXTENSIONS: Set<String> =
    setOf(
      "mp3",
      "flac",
      "ogg",
      "oga",
      "opus",
      "wav",
      "m4a",
      "m4b",
      "aac",
      "wma",
      "aiff",
      "aif",
      "alac",
      "amr",
      "mid",
      "midi",
      "spx",
      "tta",
      "wv",
      "ape",
      "dsf",
      "dff",
    )

  val SUPPORTED_PLAYLIST_EXTENSIONS: Set<String> =
    setOf(
      "m3u",
      "m3u8",
      "pls",
    )

  fun fileExtensionLower(fileName: String): String {
    val baseName = fileName.substringAfterLast('/').substringAfterLast('\\')
    val dotIndex = baseName.lastIndexOf('.')
    if (dotIndex <= 0 || dotIndex >= baseName.lastIndex) return ""
    return baseName.substring(dotIndex + 1).lowercase(Locale.ROOT)
  }

  fun isPlaylistFile(fileName: String): Boolean =
    fileExtensionLower(fileName) in SUPPORTED_PLAYLIST_EXTENSIONS

  fun isAudioFile(
    fileName: String,
    mimeType: String? = null,
  ): Boolean {
    if (isPlaylistFile(fileName) || isPlaylistMimeType(mimeType)) return false
    val ext = fileExtensionLower(fileName)
    if (ext in SUPPORTED_AUDIO_EXTENSIONS) return true
    if (ext.isEmpty()) {
      val normalizedMime = mimeType?.trim()?.lowercase(Locale.ROOT).orEmpty()
      return normalizedMime.startsWith("audio/")
    }
    return false
  }

  private fun isPlaylistMimeType(mimeType: String?): Boolean {
    val normalizedMime = mimeType?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (normalizedMime.isEmpty()) return false
    return normalizedMime.contains("mpegurl") ||
      normalizedMime.contains("scpls") ||
      normalizedMime.contains("x-pls") ||
      normalizedMime.contains("/pls") ||
      normalizedMime.contains("pls+xml")
  }

  fun playlistFormatFor(fileName: String): String =
    when (val ext = fileExtensionLower(fileName)) {
      "m3u8", "m3u", "pls" -> ext
      else -> "m3u8"
    }

  fun playlistDisplayName(fileName: String): String {
    val baseName = fileName.substringAfterLast('/').substringAfterLast('\\')
    val dotIndex = baseName.lastIndexOf('.')
    return if (dotIndex > 0) baseName.substring(0, dotIndex) else baseName
  }

  fun sanitizePlaylistFileName(rawName: String): String? {
    val cleaned =
      buildString(rawName.length) {
        for (ch in rawName) {
          if (ch.code < 0x20 || ch.code == 0x7F) continue
          if (ch in INVALID_FILE_NAME_CHARS) continue
          append(ch)
        }
      }.trimEnd { it.isWhitespace() || it == '.' }
        .trimStart { it.isWhitespace() }

    if (cleaned.isEmpty()) return null
    val rawStem =
      when {
        cleaned.endsWith(".m3u8", ignoreCase = true) -> cleaned.dropLast(5)
        cleaned.endsWith(".m3u", ignoreCase = true) -> cleaned.dropLast(4)
        cleaned.endsWith(".pls", ignoreCase = true) -> cleaned.dropLast(4)
        else -> cleaned
      }
    val stem = rawStem.trim { it.isWhitespace() || it == '.' }
    return if (stem.isEmpty()) null else "$stem.m3u8"
  }

  private val INVALID_FILE_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
