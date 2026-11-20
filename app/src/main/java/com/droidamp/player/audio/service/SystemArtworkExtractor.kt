package com.droidamp.player.audio.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.annotation.VisibleForTesting
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaMetadata
import com.droidamp.player.domain.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * Extracts album artwork strictly for OS media surfaces (lockscreen, media notification,
 * Bluetooth AVRCP) via Media3 [MediaMetadata]. Zero artwork is ever exposed to the app UI.
 */
class SystemArtworkExtractor(
  context: Context,
  maxCacheEntries: Int = DEFAULT_CACHE_ENTRIES,
) {
  private val appContext = context.applicationContext
  private val cache = LruCache<String, CachedArtwork>(maxCacheEntries.coerceAtLeast(1))

  private class CachedArtwork(
    val bytes: ByteArray?,
  )

  suspend fun extractArtwork(
    uriString: String,
    folderUriString: String? = null,
  ): ByteArray? {
    if (uriString.isBlank()) return null
    val cacheKey = "$uriString|${folderUriString.orEmpty()}"
    cache.get(cacheKey)?.let { return it.bytes }

    val extracted =
      withContext(Dispatchers.IO) {
        val raw =
          extractEmbeddedPicture(uriString)
            ?: extractFolderCoverArt(uriString, folderUriString)
        val normalized = raw?.let(::normalizeForBinderIpc)
        cache.put(cacheKey, CachedArtwork(normalized))
        normalized
      }
    return extracted
  }

  fun buildSystemMediaMetadata(
    track: AudioTrack,
    artworkBytes: ByteArray?,
  ): MediaMetadata {
    val builder =
      MediaMetadata.Builder()
        .setTitle(track.title.ifBlank { track.displayLabel })
        .setDisplayTitle(track.displayLabel)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)

    if (track.artist.isNotBlank()) {
      builder.setArtist(track.artist)
    }
    if (track.album.isNotBlank()) {
      builder.setAlbumTitle(track.album)
    }
    if (track.durationMs > 0L) {
      builder.setDurationMs(track.durationMs)
    }
    if (artworkBytes != null && artworkBytes.isNotEmpty()) {
      builder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    }
    return builder.build()
  }

  private fun extractEmbeddedPicture(uriString: String): ByteArray? {
    val retriever = MediaMetadataRetriever()
    return try {
      val parsedUri = Uri.parse(uriString)
      if (parsedUri.scheme.isNullOrEmpty() && uriString.startsWith("/")) {
        retriever.setDataSource(uriString)
      } else {
        retriever.setDataSource(appContext, parsedUri)
      }
      retriever.embeddedPicture?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun extractFolderCoverArt(
    uriString: String,
    folderUriString: String?,
  ): ByteArray? {
    val parsedUri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
    val scheme = parsedUri.scheme?.lowercase(Locale.US)

    if (scheme == "file" || (scheme == null && uriString.startsWith("/"))) {
      val filePath = parsedUri.path ?: return null
      val parentDir = File(filePath).parentFile ?: return null
      if (!parentDir.isDirectory) return null
      val files = parentDir.listFiles()?.filter { it.isFile } ?: return null
      val matchName = selectCoverArtFileName(files.map { it.name }) ?: return null
      val matchFile = files.firstOrNull { it.name == matchName } ?: return null
      return runCatching {
        if (matchFile.length() in 1L..MAX_RAW_ARTWORK_BYTES.toLong()) {
          matchFile.readBytes()
        } else if (matchFile.length() > MAX_RAW_ARTWORK_BYTES.toLong()) {
          matchFile.inputStream().use(::readBoundedStream)
        } else {
          null
        }
      }.getOrNull()
    }

    if (scheme == "content") {
      extractSafDocumentCoverArt(parsedUri)?.let { return it }
      if (!folderUriString.isNullOrBlank()) {
        extractFromTreeFolderUri(folderUriString)?.let { return it }
      }
    }

    return null
  }

  private fun extractSafDocumentCoverArt(trackUri: Uri): ByteArray? {
    val resolver = appContext.contentResolver
    val hasTreeSegment = trackUri.pathSegments.contains("tree") && trackUri.pathSegments.contains("document")
    if (!hasTreeSegment) return null

    return runCatching {
      val treeDocId = DocumentsContract.getTreeDocumentId(trackUri)
      val docId = DocumentsContract.getDocumentId(trackUri)
      val parentDocId =
        when {
          docId.contains('/') -> docId.substringBeforeLast('/')
          docId != treeDocId -> treeDocId
          else -> return@runCatching null
        }

      val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(trackUri, parentDocId)
      val projection =
        arrayOf(
          DocumentsContract.Document.COLUMN_DOCUMENT_ID,
          DocumentsContract.Document.COLUMN_DISPLAY_NAME,
          DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
      val candidates = LinkedHashMap<String, String>()
      resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        if (idCol < 0 || nameCol < 0) return@use
        while (cursor.moveToNext()) {
          val childId = cursor.getString(idCol) ?: continue
          val childName = cursor.getString(nameCol) ?: continue
          val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null
          if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue
          candidates[childName] = childId
        }
      }

      val chosenName = selectCoverArtFileName(candidates.keys) ?: return@runCatching null
      val chosenDocId = candidates[chosenName] ?: return@runCatching null
      val coverUri = DocumentsContract.buildDocumentUriUsingTree(trackUri, chosenDocId)
      resolver.openInputStream(coverUri)?.use(::readBoundedStream)
    }.getOrNull()
  }

  private fun extractFromTreeFolderUri(folderUriString: String): ByteArray? =
    runCatching {
      val folderUri = Uri.parse(folderUriString)
      val treeDir = DocumentFile.fromTreeUri(appContext, folderUri) ?: return@runCatching null
      val files = treeDir.listFiles().filter { it.isFile }
      val nameToFile = LinkedHashMap<String, DocumentFile>()
      for (file in files) {
        val name = file.name ?: continue
        nameToFile[name] = file
      }
      val chosenName = selectCoverArtFileName(nameToFile.keys) ?: return@runCatching null
      val chosenFile = nameToFile[chosenName] ?: return@runCatching null
      appContext.contentResolver.openInputStream(chosenFile.uri)?.use(::readBoundedStream)
    }.getOrNull()

  private fun readBoundedStream(input: InputStream): ByteArray? {
    val buffer = ByteArray(8192)
    val out = ByteArrayOutputStream()
    var total = 0
    while (true) {
      val read = input.read(buffer)
      if (read <= 0) break
      total += read
      if (total > MAX_RAW_ARTWORK_BYTES) {
        return null
      }
      out.write(buffer, 0, read)
    }
    return out.toByteArray().takeIf { it.isNotEmpty() }
  }

  private fun normalizeForBinderIpc(rawBytes: ByteArray): ByteArray? {
    if (rawBytes.isEmpty()) return null

    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
    val srcWidth = boundsOpts.outWidth
    val srcHeight = boundsOpts.outHeight
    if (srcWidth <= 0 || srcHeight <= 0) {
      return rawBytes.takeIf { it.size <= MAX_IPC_ARTWORK_BYTES }
    }

    if (
      rawBytes.size <= MAX_IPC_ARTWORK_BYTES &&
      srcWidth <= MAX_ARTWORK_DIMENSION_PX &&
      srcHeight <= MAX_ARTWORK_DIMENSION_PX
    ) {
      return rawBytes
    }

    val sampleSize = computeInSampleSize(srcWidth, srcHeight, MAX_ARTWORK_DIMENSION_PX)
    val decodeOpts =
      BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
    var bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts) ?: return null
    try {
      val maxDim = maxOf(bitmap.width, bitmap.height)
      if (maxDim > MAX_ARTWORK_DIMENSION_PX && maxDim > 0) {
        val scale = MAX_ARTWORK_DIMENSION_PX.toFloat() / maxDim.toFloat()
        val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        if (scaled !== bitmap) {
          bitmap.recycle()
          bitmap = scaled
        }
      }

      for (quality in JPEG_QUALITIES) {
        val out = ByteArrayOutputStream()
        if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
          val bytes = out.toByteArray()
          if (bytes.size <= MAX_IPC_ARTWORK_BYTES || quality == JPEG_QUALITIES.last()) {
            return bytes.takeIf { it.size <= MAX_IPC_ARTWORK_BYTES }
          }
        }
      }
      return null
    } finally {
      if (!bitmap.isRecycled) {
        bitmap.recycle()
      }
    }
  }

  companion object {
    const val MAX_ARTWORK_DIMENSION_PX = 512
    const val MAX_IPC_ARTWORK_BYTES = 512 * 1024
    private const val MAX_RAW_ARTWORK_BYTES = 8 * 1024 * 1024
    private const val DEFAULT_CACHE_ENTRIES = 16
    private val JPEG_QUALITIES = intArrayOf(85, 75, 65, 50)

    val COVER_ART_CANDIDATE_NAMES =
      listOf(
        "cover.jpg",
        "cover.jpeg",
        "cover.png",
        "folder.jpg",
        "folder.jpeg",
        "folder.png",
        "album.jpg",
        "album.jpeg",
        "album.png",
        "front.jpg",
        "front.jpeg",
        "front.png",
      )

    /**
     * Pure-Kotlin cover art filename selector honoring priority order and case-insensitive matching.
     */
    @VisibleForTesting
    fun selectCoverArtFileName(fileNames: Iterable<String>): String? {
      val nonBlankNames = fileNames.filter { it.isNotBlank() }
      if (nonBlankNames.isEmpty()) return null
      for (candidate in COVER_ART_CANDIDATE_NAMES) {
        val match = nonBlankNames.firstOrNull { it.trim().equals(candidate, ignoreCase = true) }
        if (match != null) {
          return match
        }
      }
      return null
    }

    @VisibleForTesting
    fun computeInSampleSize(
      width: Int,
      height: Int,
      maxDimensionPx: Int = MAX_ARTWORK_DIMENSION_PX,
    ): Int {
      if (width <= 0 || height <= 0 || maxDimensionPx <= 0) return 1
      var inSampleSize = 1
      val maxDim = maxOf(width, height)
      while ((maxDim / inSampleSize) > maxDimensionPx) {
        inSampleSize *= 2
      }
      return inSampleSize
    }
  }
}
