package com.droidamp.player.interop

import com.droidamp.player.domain.contract.PlaylistFileCodec
import com.droidamp.player.domain.model.AudioTrack
import java.util.TreeMap

class WinampPlaylistCodec : PlaylistFileCodec {
  override fun parseM3u(content: String): List<String> {
    val cleaned = content.removePrefix("\uFEFF")
    val entries = mutableListOf<String>()
    for (rawLine in cleaned.lineSequence()) {
      val line = rawLine.trim()
      if (line.isEmpty() || line.startsWith('#')) continue
      entries.add(line.replace('\\', '/'))
    }
    return entries
  }

  override fun encodeM3u8(tracks: List<AudioTrack>): String =
    buildString {
      append("#EXTM3U\n")
      for (track in tracks) {
        val durationSec = if (track.durationMs >= 0L) track.durationMs / 1000L else -1L
        val title = sanitizePlaylistTitle(track.displayLabel)
        val entry = trackEntry(track)
        append("#EXTINF:$durationSec,$title\n")
        append("$entry\n")
      }
    }

  override fun parsePls(content: String): List<String> {
    val cleaned = content.removePrefix("\uFEFF")
    val sortedEntries = TreeMap<Int, String>()
    var seenSectionHeader = false
    var inPlaylistSection = false

    for (rawLine in cleaned.lineSequence()) {
      val line = rawLine.trim()
      if (line.isEmpty() || line.startsWith(';') || line.startsWith('#')) continue

      if (line.startsWith('[') && line.endsWith(']')) {
        seenSectionHeader = true
        val section = line.substring(1, line.length - 1).trim()
        inPlaylistSection = section.equals("playlist", ignoreCase = true)
        continue
      }

      if (seenSectionHeader && !inPlaylistSection) continue

      val match = PLS_FILE_LINE_REGEX.matchEntire(line) ?: continue
      val entryNumber = match.groupValues[1].toIntOrNull() ?: continue
      if (entryNumber < 1) continue
      val entryPath = match.groupValues[2].trim().replace('\\', '/')
      if (entryPath.isNotEmpty()) {
        sortedEntries[entryNumber] = entryPath
      }
    }

    return sortedEntries.values.toList()
  }

  override fun encodePls(tracks: List<AudioTrack>): String =
    buildString {
      append("[playlist]\n")
      tracks.forEachIndexed { index, track ->
        val n = index + 1
        val durationSec = if (track.durationMs >= 0L) track.durationMs / 1000L else -1L
        val title = sanitizePlaylistTitle(track.displayLabel)
        val entry = trackEntry(track)
        append("File$n=$entry\n")
        append("Title$n=$title\n")
        append("Length$n=$durationSec\n")
      }
      append("NumberOfEntries=${tracks.size}\n")
      append("Version=2\n")
    }

  override fun resolveEntryPath(rawEntry: String, playlistFolderPath: String): String {
    val entry = rawEntry.trim().replace('\\', '/')
    if (entry.isEmpty()) return ""

    // URI pass-through (content://, file://, http://, etc.)
    if (URI_SCHEME_REGEX.matches(entry)) {
      return entry
    }

    // Windows drive absolute path (e.g. C:/Music/song.mp3)
    if (WINDOWS_DRIVE_ABS_REGEX.matches(entry)) {
      val drivePrefix = entry.substring(0, 3) // e.g. "C:/"
      val collapsed = collapseSegments(entry.substring(3), clampAtRoot = true)
      return drivePrefix + collapsed
    }

    // POSIX absolute path (e.g. /storage/emulated/0/Music/song.mp3)
    if (entry.startsWith('/')) {
      val collapsed = collapseSegments(entry.substring(1), clampAtRoot = true)
      return "/$collapsed"
    }

    // Relative entry: resolve against playlistFolderPath
    val rawBase = playlistFolderPath.trim().replace('\\', '/')
    if (rawBase.isEmpty()) {
      return collapseSegments(entry, clampAtRoot = false)
    }

    if (URI_SCHEME_REGEX.matches(rawBase)) {
      if (rawBase.startsWith("content://", ignoreCase = true)) {
        return resolveContentUriEntry(rawBase, entry)
      }
      val schemeEnd = rawBase.indexOf("://") + 3
      val firstSlashAfterAuthority = rawBase.indexOf('/', startIndex = schemeEnd)
      return if (firstSlashAfterAuthority >= 0) {
        val prefix = rawBase.substring(0, firstSlashAfterAuthority + 1)
        val basePath = rawBase.substring(firstSlashAfterAuthority + 1).trimEnd('/')
        val combined = if (basePath.isEmpty()) entry else "$basePath/$entry"
        prefix + collapseSegments(combined, clampAtRoot = true)
      } else {
        "$rawBase/${collapseSegments(entry, clampAtRoot = true)}"
      }
    }

    val base = rawBase.trimEnd('/')
    if (WINDOWS_DRIVE_BASE_REGEX.matches(base)) {
      val drivePrefix = base.substring(0, 2) + "/"
      val basePath = if (base.length > 3) base.substring(3) else ""
      val combined = if (basePath.isEmpty()) entry else "$basePath/$entry"
      return drivePrefix + collapseSegments(combined, clampAtRoot = true)
    }

    if (rawBase.startsWith('/')) {
      val basePath = rawBase.trim('/')
      val combined = if (basePath.isEmpty()) entry else "$basePath/$entry"
      return "/${collapseSegments(combined, clampAtRoot = true)}"
    }

    return collapseSegments("$base/$entry", clampAtRoot = false)
  }

  private fun resolveContentUriEntry(base: String, entry: String): String {
    val schemeEnd = base.indexOf("://") + 3
    val firstSlashAfterAuthority = base.indexOf('/', startIndex = schemeEnd)
    if (firstSlashAfterAuthority < 0) {
      return "$base/${collapseSegments(entry, clampAtRoot = true)}"
    }

    val docMarkerIdx = base.indexOf("/document/", startIndex = firstSlashAfterAuthority)
    if (docMarkerIdx >= 0) {
      val prefixEnd = docMarkerIdx + "/document/".length
      return resolveSafSpec(base.substring(0, prefixEnd), base.substring(prefixEnd), entry)
    }

    val treeMarkerIdx = base.indexOf("/tree/", startIndex = firstSlashAfterAuthority)
    if (treeMarkerIdx >= 0) {
      val prefixEnd = treeMarkerIdx + "/tree/".length
      return resolveSafSpec(base.substring(0, prefixEnd), base.substring(prefixEnd), entry)
    }

    val prefix = base.substring(0, firstSlashAfterAuthority + 1)
    val basePath = base.substring(firstSlashAfterAuthority + 1).trimEnd('/')
    val combined = if (basePath.isEmpty()) entry else "$basePath/$entry"
    return prefix + collapseSegments(combined, clampAtRoot = true)
  }

  private fun resolveSafSpec(prefix: String, spec: String, entry: String): String {
    val separator =
      when {
        spec.contains("%2F") -> "%2F"
        spec.contains("%2f") -> "%2f"
        spec.contains("%3A") && !spec.contains('/') -> "%2F"
        spec.contains("%3a") && !spec.contains('/') -> "%2f"
        else -> "/"
      }

    val specSegments = spec.split(SAF_SEGMENT_SPLIT_REGEX).filter { it.isNotEmpty() }
    val rootSegment = specSegments.firstOrNull().orEmpty()
    val baseSubSegments = if (specSegments.size > 1) specSegments.drop(1) else emptyList()
    val collapsed = collapseSegmentList(baseSubSegments + entry.split('/'), clampAtRoot = true)

    if (rootSegment.isEmpty()) {
      return prefix + collapsed.joinToString(separator)
    }
    if (collapsed.isEmpty()) {
      return prefix + rootSegment
    }
    return if (rootSegment.endsWith(':') || rootSegment.endsWith("%3A", ignoreCase = true)) {
      prefix + rootSegment + collapsed.joinToString(separator)
    } else {
      prefix + rootSegment + separator + collapsed.joinToString(separator)
    }
  }

  private fun trackEntry(track: AudioTrack): String {
    val trimmedUri = sanitizePlaylistPath(track.uriString)
    if (trimmedUri.isNotEmpty()) return trimmedUri
    val rawFolder = sanitizePlaylistPath(track.folderPath)
    val file = sanitizePlaylistPath(track.fileName).trimStart('/')
    if (rawFolder.startsWith('/') && rawFolder.trim('/').isEmpty()) {
      return if (file.isEmpty()) "/" else "/$file"
    }
    val folder = rawFolder.trimEnd('/')
    return listOf(folder, file).filter { it.isNotEmpty() }.joinToString("/")
  }

  private fun sanitizePlaylistPath(raw: String): String =
    raw.replace("\r", "").replace("\n", "").trim().replace('\\', '/')

  private fun sanitizePlaylistTitle(raw: String): String =
    raw.replace("\r", " ")
      .replace("\n", " ")
      .replace(MULTI_SPACE_REGEX, " ")
      .trim()

  private fun collapseSegments(path: String, clampAtRoot: Boolean): String =
    collapseSegmentList(path.split('/'), clampAtRoot).joinToString("/")

  private fun collapseSegmentList(segments: List<String>, clampAtRoot: Boolean): List<String> {
    val stack = ArrayDeque<String>()
    for (segment in segments) {
      when (segment) {
        "", "." -> Unit
        ".." -> {
          if (stack.isNotEmpty() && stack.last() != "..") {
            stack.removeLast()
          } else if (!clampAtRoot) {
            stack.addLast("..")
          }
        }
        else -> stack.addLast(segment)
      }
    }
    return stack
  }

  private companion object {
    val PLS_FILE_LINE_REGEX = Regex("^file(\\d+)\\s*=\\s*(.*)$", RegexOption.IGNORE_CASE)
    val URI_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
    val WINDOWS_DRIVE_ABS_REGEX = Regex("^[a-zA-Z]:/.*")
    val WINDOWS_DRIVE_BASE_REGEX = Regex("^[a-zA-Z]:(?:/.*)?$")
    val SAF_SEGMENT_SPLIT_REGEX = Regex("/|%2[fF]")
    val MULTI_SPACE_REGEX = Regex(" {2,}")
  }
}
