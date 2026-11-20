package com.droidamp.player.storage

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class ProbedAudioMetadata(
  val title: String? = null,
  val artist: String? = null,
  val album: String? = null,
  val durationMs: Long = 0L,
  val bitrateKbps: Int = 0,
  val sampleRateHz: Int = 0,
  val channelMode: ChannelMode? = null,
)

interface AudioMetadataProbe {
  fun probe(uriString: String): ProbedAudioMetadata?
}

/**
 * Android offline audio metadata probe using [MediaMetadataRetriever] and [MediaExtractor].
 *
 * Extracts only text and technical audio properties; NEVER extracts album artwork.
 */
class AndroidAudioMetadataProbe(
  context: Context,
) : AudioMetadataProbe {
  private val appContext = context.applicationContext

  override fun probe(uriString: String): ProbedAudioMetadata? {
    if (uriString.isBlank()) return null

    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var durationMs = 0L
    var bitrateKbps = 0
    var sampleRateHz = 0
    var channelMode: ChannelMode? = null
    var anyMetadataRead = false

    val parsedUri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
    val scheme = parsedUri.scheme?.lowercase(Locale.ROOT)
    val localFilePath =
      when {
        scheme.isNullOrEmpty() && uriString.startsWith("/") -> uriString
        scheme == "file" -> parsedUri.path
        else -> null
      }

    val retriever = MediaMetadataRetriever()
    try {
      if (localFilePath != null) {
        retriever.setDataSource(localFilePath)
      } else {
        retriever.setDataSource(appContext, parsedUri)
      }
      anyMetadataRead = true
      title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
      artist =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
          ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
      album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
      durationMs =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
          ?.toLongOrNull()
          ?.coerceAtLeast(0L)
          ?: 0L
      val bitrateBps =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
          ?.toLongOrNull()
          ?.coerceAtLeast(0L)
          ?: 0L
      bitrateKbps = (bitrateBps / 1000L).toInt().coerceAtLeast(0)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        sampleRateHz =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: 0
      }
    } catch (_: Throwable) {
      // Fall through to MediaExtractor attempt if retriever failed.
    } finally {
      runCatching { retriever.release() }
    }

    val extractor = MediaExtractor()
    try {
      if (localFilePath != null) {
        extractor.setDataSource(localFilePath)
      } else {
        extractor.setDataSource(appContext, parsedUri, null)
      }
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase(Locale.ROOT).orEmpty()
        if (!mime.startsWith("audio/")) continue
        anyMetadataRead = true
        if (sampleRateHz <= 0 && format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
          sampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(0)
        }
        if (channelMode == null && format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
          val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
          channelMode = if (channels == 1) ChannelMode.MONO else ChannelMode.STEREO
        }
        if (durationMs <= 0L && format.containsKey(MediaFormat.KEY_DURATION)) {
          val durationUs = format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L)
          durationMs = durationUs / 1000L
        }
        if (bitrateKbps <= 0 && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
          val bps = format.getInteger(MediaFormat.KEY_BIT_RATE).coerceAtLeast(0)
          bitrateKbps = bps / 1000
        }
        break
      }
    } catch (_: Throwable) {
      // Ignore extractor errors when format cannot be probed.
    } finally {
      runCatching { extractor.release() }
    }

    if (!anyMetadataRead) return null
    return ProbedAudioMetadata(
      title = title,
      artist = artist,
      album = album,
      durationMs = durationMs,
      bitrateKbps = bitrateKbps,
      sampleRateHz = sampleRateHz,
      channelMode = channelMode,
    )
  }
}

/**
 * Pure-Kotlin offline audio metadata extractor with a synchronized bounded LRU cache and
 * clean filename fallbacks. Extracts zero album art.
 */
class AudioMetadataExtractor(
  private val probe: AudioMetadataProbe,
  maxCacheEntries: Int = DEFAULT_CACHE_ENTRIES,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  private val cacheLimit = maxCacheEntries.coerceAtLeast(1)
  private val cacheLock = Any()
  private val cache =
    object : LinkedHashMap<String, AudioTrack>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AudioTrack>?): Boolean =
        size > cacheLimit
    }

  suspend fun extractTrack(
    id: String,
    uriString: String,
    fileName: String,
    folderPath: String,
    fallbackTitle: String = "",
    fallbackArtist: String = "",
    fallbackAlbum: String = "",
    fallbackDurationMs: Long = 0L,
  ): AudioTrack {
    synchronized(cacheLock) {
      cache[uriString]?.let { cached ->
        return cached.copy(
          id = id,
          fileName = fileName,
          folderPath = folderPath,
        )
      }
    }

    val probed =
      withContext(ioDispatcher) {
        try {
          probe.probe(uriString)
        } catch (ce: CancellationException) {
          throw ce
        } catch (_: Throwable) {
          null
        }
      }

    val track =
      buildTrackFromMetadata(
        id = id,
        uriString = uriString,
        fileName = fileName,
        folderPath = folderPath,
        probed = probed,
        fallbackTitle = fallbackTitle,
        fallbackArtist = fallbackArtist,
        fallbackAlbum = fallbackAlbum,
        fallbackDurationMs = fallbackDurationMs,
      )

    synchronized(cacheLock) {
      cache[uriString] = track
    }
    return track
  }

  fun invalidate(uriString: String) {
    synchronized(cacheLock) {
      cache.remove(uriString)
    }
  }

  fun clearCache() {
    synchronized(cacheLock) {
      cache.clear()
    }
  }

  fun cacheSize(): Int =
    synchronized(cacheLock) {
      cache.size
    }

  companion object {
    const val DEFAULT_CACHE_ENTRIES = 512
    private val TRACK_NUMBER_PREFIX_REGEX = Regex("""^\d{1,2}\.?$""")
    private val INLINE_TRACK_NUMBER_PREFIX_REGEX = Regex("""^\d{1,2}\.\s+(.+)$""")

    fun sanitizeTag(raw: String?): String {
      val trimmed = raw?.trim().orEmpty()
      if (trimmed.isEmpty()) return ""
      if (trimmed.equals("<unknown>", ignoreCase = true)) return ""
      return trimmed
    }

    fun parseFilenameFallback(fileName: String): Pair<String, String> {
      val baseName = fileName.substringAfterLast('/').substringAfterLast('\\')
      val dotIndex = baseName.lastIndexOf('.')
      val stem = (if (dotIndex > 0) baseName.substring(0, dotIndex) else baseName).trim()
      if (stem.isEmpty()) return "" to baseName

      val parts = stem.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
      if (parts.size >= 2) {
        val firstIsTrackNumber = TRACK_NUMBER_PREFIX_REGEX.matches(parts[0])
        return when {
          firstIsTrackNumber && parts.size >= 3 ->
            parts[1] to parts.drop(2).joinToString(" - ")
          firstIsTrackNumber ->
            "" to parts.drop(1).joinToString(" - ")
          else ->
            parts[0] to parts.drop(1).joinToString(" - ")
        }
      }

      val inlineMatch = INLINE_TRACK_NUMBER_PREFIX_REGEX.matchEntire(stem)
      if (inlineMatch != null) {
        return "" to inlineMatch.groupValues[1].trim()
      }
      return "" to stem
    }

    fun buildTrackFromMetadata(
      id: String,
      uriString: String,
      fileName: String,
      folderPath: String,
      probed: ProbedAudioMetadata?,
      fallbackTitle: String = "",
      fallbackArtist: String = "",
      fallbackAlbum: String = "",
      fallbackDurationMs: Long = 0L,
    ): AudioTrack {
      val (parsedArtist, parsedTitle) = parseFilenameFallback(fileName)

      val title =
        sanitizeTag(probed?.title)
          .ifEmpty { sanitizeTag(fallbackTitle) }
          .ifEmpty { parsedTitle }

      val artist =
        sanitizeTag(probed?.artist)
          .ifEmpty { sanitizeTag(fallbackArtist) }
          .ifEmpty { parsedArtist }

      val album =
        sanitizeTag(probed?.album)
          .ifEmpty { sanitizeTag(fallbackAlbum) }

      val probedDuration = probed?.durationMs?.coerceAtLeast(0L) ?: 0L
      val durationMs =
        if (probedDuration > 0L) {
          probedDuration
        } else {
          fallbackDurationMs.coerceAtLeast(0L)
        }

      val bitrateKbps = (probed?.bitrateKbps ?: 0).coerceAtLeast(0)
      val sampleRateHz = (probed?.sampleRateHz ?: 0).coerceAtLeast(0)
      val channelMode = probed?.channelMode ?: ChannelMode.STEREO

      return AudioTrack(
        id = id,
        uriString = uriString,
        title = title,
        artist = artist,
        album = album,
        fileName = fileName,
        folderPath = folderPath,
        durationMs = durationMs,
        bitrateKbps = bitrateKbps,
        sampleRateHz = sampleRateHz,
        channelMode = channelMode,
      )
    }
  }
}
