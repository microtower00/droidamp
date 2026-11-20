package com.droidamp.player.skin.wsz

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Extracted allowlisted entries from a Winamp 2.x `.wsz` ZIP archive,
 * keyed by exact lowercase basename (e.g. `"main.bmp"`, `"viscolor.txt"`).
 */
data class WszArchive(
  val entries: Map<String, ByteArray>,
) {
  fun bytes(fileName: String): ByteArray? = entries[fileName.lowercase(Locale.ROOT)]

  fun text(fileName: String): String? =
    bytes(fileName)?.toString(Charsets.ISO_8859_1)
}

/**
 * Pure-Kotlin `.wsz` ZIP reader with:
 * - Case-insensitive exact basename matching (handles nested folders inside ZIPs and prevents
 *   `main.bmp` vs `eqmain.bmp` suffix collisions).
 * - Strict 13-file allowlist ignoring unused desktop bloat (`vidamp.bmp`, `amarok.bmp`, etc.).
 * - Zip-bomb protection (per-entry and total uncompressed byte caps).
 */
object WszArchiveParser {
  val ALLOWED_FILES: Set<String> =
    setOf(
      "main.bmp",
      "cbuttons.bmp",
      "playpaus.bmp",
      "shufrep.bmp",
      "monoster.bmp",
      "nums_ex.bmp",
      "numbers.bmp",
      "volume.bmp",
      "balance.bmp",
      "posbar.bmp",
      "eqmain.bmp",
      "titlebar.bmp",
      "pledit.bmp",
      "viscolor.txt",
      "pledit.txt",
    )

  const val MAX_ENTRY_BYTES = 2 * 1024 * 1024 // 2 MB
  const val MAX_TOTAL_BYTES = 12 * 1024 * 1024 // 12 MB

  fun hasZipMagic(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
      bytes[0] == 0x50.toByte() &&
      bytes[1] == 0x4B.toByte() &&
      (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

  fun parse(zipBytes: ByteArray): WszArchive =
    ByteArrayInputStream(zipBytes).use(::parse)

  fun parse(inputStream: InputStream): WszArchive {
    val result = LinkedHashMap<String, ByteArray>()
    var totalRead = 0L
    val buffer = ByteArray(8192)

    ZipInputStream(inputStream).use { zis ->
      while (true) {
        val entry = zis.nextEntry ?: break
        try {
          if (entry.isDirectory) continue
          val baseName =
            entry.name
              .substringAfterLast('/')
              .substringAfterLast('\\')
              .trim()
              .lowercase(Locale.ROOT)
          if (baseName !in ALLOWED_FILES) continue
          if (baseName in result) continue

          val out = ByteArrayOutputStream()
          var entryBytes = 0
          var overflow = false
          while (true) {
            val n = zis.read(buffer)
            if (n <= 0) break
            entryBytes += n
            totalRead += n
            if (entryBytes > MAX_ENTRY_BYTES || totalRead > MAX_TOTAL_BYTES) {
              overflow = true
              break
            }
            out.write(buffer, 0, n)
          }
          if (!overflow && entryBytes > 0) {
            result[baseName] = out.toByteArray()
          }
        } finally {
          zis.closeEntry()
        }
      }
    }
    return WszArchive(result)
  }
}
