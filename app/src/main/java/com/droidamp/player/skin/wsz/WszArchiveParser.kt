package com.droidamp.player.skin.wsz

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Extracted entries from a Winamp 2.x Legacy or DroidAmp Augmented `.wsz` ZIP archive (`ADR-028`):
 * - [entries]: classic allowlisted `.bmp` / `.txt` files keyed by lowercase basename.
 * - [manifestRawBytes]: optional `droidamp.json` bytes (decoded as UTF-8, BOM-stripped via [manifestJson]).
 * - [sprites]: optional alpha-PNG sprites under `sprites/<name>.png` keyed by normalized lowercase path
 *   (e.g. `"sprites/header_deck.png"`).
 * - [rejectedEntries]: unsafe (`..` traversal) or oversized entry names skipped during parsing.
 */
data class WszArchive(
  val entries: Map<String, ByteArray>,
  val manifestRawBytes: ByteArray? = null,
  val sprites: Map<String, ByteArray> = emptyMap(),
  val rejectedEntries: List<String> = emptyList(),
) {
  fun bytes(fileName: String): ByteArray? = entries[fileName.lowercase(Locale.ROOT)]

  fun text(fileName: String): String? =
    bytes(fileName)?.toString(Charsets.ISO_8859_1)

  fun manifestJson(): String? =
    manifestRawBytes?.toString(Charsets.UTF_8)?.removePrefix("\uFEFF")

  fun spriteBytes(keyOrPath: String): ByteArray? {
    val normalized =
      keyOrPath
        .replace('\\', '/')
        .trim()
        .trim('/')
        .lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return null
    sprites[normalized]?.let { return it }
    if (!normalized.startsWith("sprites/")) {
      sprites["sprites/$normalized"]?.let { return it }
    }
    val baseName = normalized.substringAfterLast('/')
    return sprites["sprites/$baseName"]
  }
}

/**
 * Pure-Kotlin `.wsz` ZIP reader with:
 * - Case-insensitive exact basename matching for classic Winamp `.bmp`/`.txt` entries.
 * - Support for Augmented `.wsz` `droidamp.json` + `sprites/<name>.png` entries (`ADR-028`).
 * - Strict zip-slip rejection (`..` path segments) and zip-bomb byte caps.
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

  const val MANIFEST_FILE_NAME = "droidamp.json"
  const val MAX_ENTRY_BYTES = 2 * 1024 * 1024 // 2 MB
  const val MAX_TOTAL_BYTES = 16 * 1024 * 1024 // 16 MB

  fun hasZipMagic(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
      bytes[0] == 0x50.toByte() &&
      bytes[1] == 0x4B.toByte() &&
      (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

  fun isSafeEntryPath(rawEntryName: String): Boolean {
    if (rawEntryName.isBlank()) return false
    val normalized = rawEntryName.replace('\\', '/')
    val segments = normalized.split('/')
    if (segments.any { it.trim() == ".." }) return false
    return segments.any { it.isNotBlank() && it.trim() != "." }
  }

  fun parse(zipBytes: ByteArray): WszArchive =
    ByteArrayInputStream(zipBytes).use(::parse)

  fun parse(inputStream: InputStream): WszArchive {
    val classicEntries = LinkedHashMap<String, ByteArray>()
    val spriteEntries = LinkedHashMap<String, ByteArray>()
    val rejected = ArrayList<String>()
    var manifestBytes: ByteArray? = null
    var totalRead = 0L
    val buffer = ByteArray(8192)

    ZipInputStream(inputStream).use { zis ->
      while (true) {
        val entry = zis.nextEntry ?: break
        try {
          if (!isSafeEntryPath(entry.name)) {
            rejected.add(entry.name)
            continue
          }
          if (entry.isDirectory) continue

          val normalizedSegments =
            entry.name
              .replace('\\', '/')
              .split('/')
              .map { it.trim() }
              .filter { it.isNotEmpty() && it != "." }
          if (normalizedSegments.isEmpty()) continue

          val lowerSegments = normalizedSegments.map { it.lowercase(Locale.ROOT) }
          val baseName = lowerSegments.last()
          val spritesDirIndex = lowerSegments.indexOf("sprites")

          val targetKind: EntryTarget? =
            when {
              spritesDirIndex >= 0 &&
                spritesDirIndex < lowerSegments.lastIndex &&
                baseName.endsWith(".png") -> {
                val relSpriteKey =
                  lowerSegments.subList(spritesDirIndex, lowerSegments.size).joinToString("/")
                if (relSpriteKey in spriteEntries) null else EntryTarget.Sprite(relSpriteKey)
              }
              baseName == MANIFEST_FILE_NAME && spritesDirIndex < 0 -> {
                if (manifestBytes != null) null else EntryTarget.Manifest
              }
              baseName in ALLOWED_FILES && spritesDirIndex < 0 -> {
                if (baseName in classicEntries) null else EntryTarget.Classic(baseName)
              }
              else -> null
            }

          if (targetKind == null) continue

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

          if (overflow) {
            rejected.add(entry.name)
            continue
          }

          if (entryBytes > 0 || targetKind == EntryTarget.Manifest) {
            val extracted = out.toByteArray()
            when (targetKind) {
              is EntryTarget.Classic -> if (extracted.isNotEmpty()) classicEntries[targetKind.key] = extracted
              is EntryTarget.Sprite -> if (extracted.isNotEmpty()) spriteEntries[targetKind.key] = extracted
              EntryTarget.Manifest -> if (extracted.isNotEmpty()) manifestBytes = extracted
            }
          }
        } finally {
          zis.closeEntry()
        }
      }
    }

    return WszArchive(
      entries = classicEntries,
      manifestRawBytes = manifestBytes,
      sprites = spriteEntries,
      rejectedEntries = rejected,
    )
  }

  private sealed interface EntryTarget {
    data class Classic(val key: String) : EntryTarget
    data class Sprite(val key: String) : EntryTarget
    data object Manifest : EntryTarget
  }
}
