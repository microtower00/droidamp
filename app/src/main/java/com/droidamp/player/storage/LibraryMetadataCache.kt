package com.droidamp.player.storage

import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.StorageBackend
import java.io.File
import java.util.Locale

interface LibraryCacheStorage {
  fun readText(key: String): String?
  fun writeText(key: String, content: String)
  fun delete(key: String)
}

class FileLibraryCacheStorage(
  private val cacheDir: File,
) : LibraryCacheStorage {
  override fun readText(key: String): String? {
    val file = File(cacheDir, key)
    if (!file.isFile) return null
    return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
  }

  override fun writeText(
    key: String,
    content: String,
  ) {
    runCatching {
      if (!cacheDir.exists()) {
        cacheDir.mkdirs()
      }
      val tempFile = File(cacheDir, "$key.tmp")
      val targetFile = File(cacheDir, key)
      tempFile.writeText(content, Charsets.UTF_8)
      if (!tempFile.renameTo(targetFile)) {
        targetFile.writeText(content, Charsets.UTF_8)
        tempFile.delete()
      }
    }
  }

  override fun delete(key: String) {
    runCatching {
      File(cacheDir, key).delete()
      File(cacheDir, "$key.tmp").delete()
    }
  }
}

data class CachedLibrarySnapshot(
  val backend: StorageBackend,
  val roots: List<FolderEntry>,
  val tracks: List<AudioTrack>,
  val updatedAtEpochMs: Long = 0L,
)

data class LibraryCacheDiff(
  val addedTracks: List<AudioTrack>,
  val removedTrackUris: Set<String>,
) {
  val hasChanges: Boolean get() = addedTracks.isNotEmpty() || removedTrackUris.isNotEmpty()
}

/**
 * Lightweight local metadata index cache per [StorageBackend] (`ADR-029`).
 *
 * Stores root folder summaries + track metadata in escaped TSV (~1-3 MB for 10k tracks; zero
 * audio duplication) so cold launch and Music Source switches emit cached counts immediately.
 */
class LibraryMetadataCache(
  private val storage: LibraryCacheStorage,
) {
  fun load(backend: StorageBackend): CachedLibrarySnapshot? {
    val raw = storage.readText(cacheKey(backend)) ?: return null
    val lines = raw.lineSequence().iterator()
    if (!lines.hasNext()) return null
    val header = lines.next().trim()
    if (header != CACHE_HEADER) return null

    var updatedAt = 0L
    val roots = ArrayList<FolderEntry>()
    val tracks = ArrayList<AudioTrack>()

    while (lines.hasNext()) {
      val line = lines.next()
      if (line.isBlank()) continue
      val cols = line.split('\t')
      when (cols.firstOrNull()) {
        ROW_TIME -> {
          updatedAt = cols.getOrNull(1)?.toLongOrNull() ?: 0L
        }
        ROW_ROOT -> {
          if (cols.size >= 6) {
            val uri = unescape(cols[1])
            val name = unescape(cols[2])
            val segments =
              unescape(cols[3])
                .split('/')
                .filter { it.isNotEmpty() }
            val trackCount = cols[4].toIntOrNull()?.coerceAtLeast(0) ?: 0
            val subfolderCount = cols[5].toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (uri.isNotEmpty()) {
              roots.add(
                FolderEntry(
                  uriString = uri,
                  name = name,
                  pathSegments = segments,
                  trackCount = trackCount,
                  subfolderCount = subfolderCount,
                ),
              )
            }
          }
        }
        ROW_TRACK -> {
          if (cols.size >= 11) {
            val id = unescape(cols[1])
            val uri = unescape(cols[2])
            val title = unescape(cols[3])
            val artist = unescape(cols[4])
            val album = unescape(cols[5])
            val fileName = unescape(cols[6])
            val folderPath = unescape(cols[7])
            val durationMs = cols[8].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val bitrateKbps = cols[9].toIntOrNull()?.coerceAtLeast(0) ?: 320
            val sampleRateHz = cols[10].toIntOrNull()?.coerceAtLeast(0) ?: 44_100
            val channelMode =
              if (cols.getOrNull(11) == ChannelMode.MONO.name) {
                ChannelMode.MONO
              } else {
                ChannelMode.STEREO
              }
            if (id.isNotEmpty() && uri.isNotEmpty()) {
              tracks.add(
                AudioTrack(
                  id = id,
                  uriString = uri,
                  title = title,
                  artist = artist,
                  album = album,
                  fileName = fileName,
                  folderPath = folderPath,
                  durationMs = durationMs,
                  bitrateKbps = bitrateKbps,
                  sampleRateHz = sampleRateHz,
                  channelMode = channelMode,
                ),
              )
            }
          }
        }
      }
    }

    return CachedLibrarySnapshot(
      backend = backend,
      roots = roots,
      tracks = tracks,
      updatedAtEpochMs = updatedAt,
    )
  }

  fun save(snapshot: CachedLibrarySnapshot) {
    val content =
      buildString {
        appendLine(CACHE_HEADER)
        append(ROW_TIME).append('\t').appendLine(snapshot.updatedAtEpochMs)
        for (root in snapshot.roots) {
          append(ROW_ROOT).append('\t')
            .append(escape(root.uriString)).append('\t')
            .append(escape(root.name)).append('\t')
            .append(escape(root.pathSegments.joinToString("/"))).append('\t')
            .append(root.trackCount).append('\t')
            .appendLine(root.subfolderCount)
        }
        for (track in snapshot.tracks) {
          append(ROW_TRACK).append('\t')
            .append(escape(track.id)).append('\t')
            .append(escape(track.uriString)).append('\t')
            .append(escape(track.title)).append('\t')
            .append(escape(track.artist)).append('\t')
            .append(escape(track.album)).append('\t')
            .append(escape(track.fileName)).append('\t')
            .append(escape(track.folderPath)).append('\t')
            .append(track.durationMs).append('\t')
            .append(track.bitrateKbps).append('\t')
            .append(track.sampleRateHz).append('\t')
            .appendLine(track.channelMode.name)
        }
      }
    storage.writeText(cacheKey(snapshot.backend), content)
  }

  fun pruneTrackUri(
    backend: StorageBackend,
    uriString: String,
  ): CachedLibrarySnapshot? {
    val trimmed = uriString.trim()
    val current = load(backend) ?: return null
    val filteredTracks = current.tracks.filterNot { it.uriString == trimmed }
    if (filteredTracks.size == current.tracks.size) return current
    val updated = current.copy(tracks = filteredTracks)
    save(updated)
    return updated
  }

  fun clear(backend: StorageBackend) {
    storage.delete(cacheKey(backend))
  }

  fun computeDiff(
    previous: CachedLibrarySnapshot?,
    fresh: CachedLibrarySnapshot,
  ): LibraryCacheDiff {
    if (previous == null) {
      return LibraryCacheDiff(addedTracks = fresh.tracks, removedTrackUris = emptySet())
    }
    val prevUris = previous.tracks.mapTo(HashSet()) { it.uriString }
    val freshUris = fresh.tracks.mapTo(HashSet()) { it.uriString }
    val added = fresh.tracks.filter { it.uriString !in prevUris }
    val removed = prevUris - freshUris
    return LibraryCacheDiff(addedTracks = added, removedTrackUris = removed)
  }

  private fun cacheKey(backend: StorageBackend): String =
    when (backend) {
      StorageBackend.SAF -> "saf_index.tsv"
      StorageBackend.MEDIA_STORE -> "mediastore_index.tsv"
    }

  private fun escape(value: String): String =
    buildString(value.length) {
      for (ch in value) {
        when (ch) {
          '%' -> append("%25")
          '\t' -> append("%09")
          '\n' -> append("%0A")
          '\r' -> append("%0D")
          else -> append(ch)
        }
      }
    }

  private fun unescape(value: String): String {
    if ('%' !in value) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
      val ch = value[i]
      if (ch == '%' && i + 2 < value.length) {
        val code = value.substring(i, i + 3).uppercase(Locale.ROOT)
        when (code) {
          "%25" -> {
            out.append('%')
            i += 3
            continue
          }
          "%09" -> {
            out.append('\t')
            i += 3
            continue
          }
          "%0A" -> {
            out.append('\n')
            i += 3
            continue
          }
          "%0D" -> {
            out.append('\r')
            i += 3
            continue
          }
        }
      }
      out.append(ch)
      i += 1
    }
    return out.toString()
  }

  private companion object {
    const val CACHE_HEADER = "DROIDAMP_INDEX\t1"
    const val ROW_TIME = "TIME"
    const val ROW_ROOT = "ROOT"
    const val ROW_TRACK = "TRACK"
  }
}
