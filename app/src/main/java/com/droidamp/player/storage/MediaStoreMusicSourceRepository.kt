package com.droidamp.player.storage

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.droidamp.player.domain.contract.MusicSourceRepository
import com.droidamp.player.domain.contract.PlaylistFileCodec
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.interop.WinampPlaylistCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

data class MediaStoreAudioRecord(
  val mediaId: Long,
  val contentUriString: String,
  val displayName: String,
  val title: String,
  val artist: String,
  val album: String,
  val durationMs: Long,
  val relativePath: String,
  val dataPath: String = "",
  val mimeType: String? = null,
)

data class MediaStorePlaylistRecord(
  val playlistId: Long,
  val contentUriString: String,
  val name: String,
  val memberTrackIds: List<Long> = emptyList(),
  val filePath: String = "",
)

data class MediaStorePlaylistFileRecord(
  val id: String,
  val uriString: String,
  val name: String,
  val format: String,
  val folderPath: String,
)

interface MediaStoreGateway {
  fun hasAudioReadPermission(): Boolean
  fun queryAudioTracks(): List<MediaStoreAudioRecord>
  fun queryPlaylists(): List<MediaStorePlaylistRecord>
  fun queryPlaylistFiles(): List<MediaStorePlaylistFileRecord>
  fun readTextFile(uriOrPath: String): String?
  fun writeM3u8PlaylistFile(
    fileName: String,
    content: String,
  ): MediaStorePlaylistFileRecord?
}

class AndroidMediaStoreGateway(
  context: Context,
) : MediaStoreGateway {
  private val appContext = context.applicationContext
  private val resolver = appContext.contentResolver
  private val appPlaylistsDir: File
    get() = File(appContext.filesDir, "playlists")

  override fun hasAudioReadPermission(): Boolean {
    val permission =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
      } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
      }
    return ContextCompat.checkSelfPermission(appContext, permission) ==
      PackageManager.PERMISSION_GRANTED
  }

  @Suppress("DEPRECATION")
  override fun queryAudioTracks(): List<MediaStoreAudioRecord> {
    if (!hasAudioReadPermission()) return emptyList()
    val projection =
      buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.DISPLAY_NAME)
        add(MediaStore.Audio.Media.TITLE)
        add(MediaStore.Audio.Media.ARTIST)
        add(MediaStore.Audio.Media.ALBUM)
        add(MediaStore.Audio.Media.DURATION)
        add(MediaStore.Audio.Media.MIME_TYPE)
        add(MediaStore.Audio.Media.IS_MUSIC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          add(MediaStore.Audio.Media.RELATIVE_PATH)
        }
        add(MediaStore.Audio.Media.DATA)
      }.toTypedArray()

    return try {
      val records = ArrayList<MediaStoreAudioRecord>()
      resolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        null,
      )?.use { cursor ->
        val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
        val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
        val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
        val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
        val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
        val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
        val isMusicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
        val relPathCol =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
          } else {
            -1
          }
        val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
        if (idCol < 0) return@use

        while (cursor.moveToNext()) {
          val mediaId = cursor.getLong(idCol)
          val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
          val displayName =
            (if (nameCol >= 0) cursor.getString(nameCol) else null)
              ?.takeIf { it.isNotBlank() }
              ?: dataPath.substringAfterLast('/').takeIf { it.isNotBlank() }
              ?: continue
          val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null
          if (StorageSupportedFormats.isPlaylistFile(displayName)) continue
          val isMusic = if (isMusicCol >= 0) cursor.getInt(isMusicCol) != 0 else false
          if (!isMusic && !StorageSupportedFormats.isAudioFile(displayName, mimeType)) continue

          val rawRelativePath =
            if (relPathCol >= 0) cursor.getString(relPathCol).orEmpty() else ""
          val contentUri =
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
          records.add(
            MediaStoreAudioRecord(
              mediaId = mediaId,
              contentUriString = contentUri.toString(),
              displayName = displayName,
              title = if (titleCol >= 0) cursor.getString(titleCol).orEmpty() else "",
              artist = if (artistCol >= 0) cursor.getString(artistCol).orEmpty() else "",
              album = if (albumCol >= 0) cursor.getString(albumCol).orEmpty() else "",
              durationMs = if (durationCol >= 0) cursor.getLong(durationCol).coerceAtLeast(0L) else 0L,
              relativePath = rawRelativePath,
              dataPath = dataPath,
              mimeType = mimeType,
            ),
          )
        }
      }
      records
    } catch (_: SecurityException) {
      emptyList()
    } catch (_: Throwable) {
      emptyList()
    }
  }

  @Suppress("DEPRECATION")
  override fun queryPlaylists(): List<MediaStorePlaylistRecord> {
    if (!hasAudioReadPermission()) return emptyList()
    return try {
      val playlists = ArrayList<MediaStorePlaylistRecord>()
      val projection =
        arrayOf(
          MediaStore.Audio.Playlists._ID,
          MediaStore.Audio.Playlists.NAME,
          MediaStore.Audio.Playlists.DATA,
        )
      resolver.query(
        MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        null,
      )?.use { cursor ->
        val idCol = cursor.getColumnIndex(MediaStore.Audio.Playlists._ID)
        val nameCol = cursor.getColumnIndex(MediaStore.Audio.Playlists.NAME)
        val dataCol = cursor.getColumnIndex(MediaStore.Audio.Playlists.DATA)
        if (idCol < 0) return@use
        while (cursor.moveToNext()) {
          val playlistId = cursor.getLong(idCol)
          val name =
            (if (nameCol >= 0) cursor.getString(nameCol) else null)
              ?.takeIf { it.isNotBlank() }
              ?: "Playlist $playlistId"
          val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
          val members = queryPlaylistMembers(playlistId)
          val contentUri =
            ContentUris.withAppendedId(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, playlistId)
          playlists.add(
            MediaStorePlaylistRecord(
              playlistId = playlistId,
              contentUriString = contentUri.toString(),
              name = name,
              memberTrackIds = members,
              filePath = dataPath,
            ),
          )
        }
      }
      playlists
    } catch (_: Throwable) {
      emptyList()
    }
  }

  @Suppress("DEPRECATION")
  private fun queryPlaylistMembers(playlistId: Long): List<Long> =
    try {
      val memberIds = ArrayList<Long>()
      val membersUri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
      val projection = arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID)
      resolver.query(
        membersUri,
        projection,
        null,
        null,
        MediaStore.Audio.Playlists.Members.PLAY_ORDER,
      )?.use { cursor ->
        val audioIdCol = cursor.getColumnIndex(MediaStore.Audio.Playlists.Members.AUDIO_ID)
        if (audioIdCol < 0) return@use
        while (cursor.moveToNext()) {
          memberIds.add(cursor.getLong(audioIdCol))
        }
      }
      memberIds
    } catch (_: Throwable) {
      emptyList()
    }

  @Suppress("DEPRECATION")
  override fun queryPlaylistFiles(): List<MediaStorePlaylistFileRecord> {
    val results = LinkedHashMap<String, MediaStorePlaylistFileRecord>()

    // 1. App-private playlists directory (works offline on API 24-36 regardless of scoped storage)
    runCatching {
      val dir = appPlaylistsDir
      if (dir.isDirectory) {
        val files = dir.listFiles().orEmpty().filter { it.isFile && StorageSupportedFormats.isPlaylistFile(it.name) }
        for (file in files) {
          val id = "$PRIVATE_FILE_ID_PREFIX${file.absolutePath}"
          results[id] =
            MediaStorePlaylistFileRecord(
              id = id,
              uriString = file.absolutePath,
              name = file.name,
              format = StorageSupportedFormats.playlistFormatFor(file.name),
              folderPath = dir.absolutePath,
            )
        }
      }
    }

    // 2. Shared MediaStore.Files playlists (when audio read permission is granted)
    if (hasAudioReadPermission()) {
      runCatching {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection =
          buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              add(MediaStore.Files.FileColumns.RELATIVE_PATH)
            }
            add(MediaStore.Files.FileColumns.DATA)
          }.toTypedArray()

        resolver.query(filesUri, projection, null, null, null)?.use { cursor ->
          val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
          val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
          val relCol =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
            } else {
              -1
            }
          val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
          if (idCol < 0) return@use

          while (cursor.moveToNext()) {
            val fileId = cursor.getLong(idCol)
            val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
            val name =
              (if (nameCol >= 0) cursor.getString(nameCol) else null)
                ?.takeIf { it.isNotBlank() }
                ?: dataPath.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: continue
            if (!StorageSupportedFormats.isPlaylistFile(name)) continue
            val relPath = if (relCol >= 0) cursor.getString(relCol).orEmpty() else ""
            val folderPath =
              when {
                dataPath.contains('/') -> dataPath.substringBeforeLast('/')
                relPath.isNotBlank() -> relPath.trimEnd('/')
                else -> ""
              }
            val contentUri = ContentUris.withAppendedId(filesUri, fileId).toString()
            val recordId = "$MEDIASTORE_FILE_ID_PREFIX$fileId"
            results[recordId] =
              MediaStorePlaylistFileRecord(
                id = recordId,
                uriString = contentUri,
                name = name,
                format = StorageSupportedFormats.playlistFormatFor(name),
                folderPath = folderPath,
              )
          }
        }
      }
    }

    return results.values.toList()
  }

  override fun readTextFile(uriOrPath: String): String? {
    if (uriOrPath.isBlank()) return null
    return runCatching {
      when {
        uriOrPath.startsWith("content://", ignoreCase = true) ->
          resolver.openInputStream(Uri.parse(uriOrPath))
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
        uriOrPath.startsWith("file://", ignoreCase = true) -> {
          val path = Uri.parse(uriOrPath).path ?: return@runCatching null
          File(path).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }
        else -> File(uriOrPath).takeIf { it.isFile }?.readText(Charsets.UTF_8)
      }
    }.getOrNull()
  }

  override fun writeM3u8PlaylistFile(
    fileName: String,
    content: String,
  ): MediaStorePlaylistFileRecord? =
    try {
      val dir = appPlaylistsDir
      if (!dir.exists() && !dir.mkdirs()) {
        null
      } else {
        val outFile = File(dir, fileName)
        outFile.writeText(content, Charsets.UTF_8)
        MediaStorePlaylistFileRecord(
          id = "$PRIVATE_FILE_ID_PREFIX${outFile.absolutePath}",
          uriString = outFile.absolutePath,
          name = fileName,
          format = "m3u8",
          folderPath = dir.absolutePath,
        )
      }
    } catch (_: Throwable) {
      null
    }

  private companion object {
    const val PRIVATE_FILE_ID_PREFIX = "ms-file:"
    const val MEDIASTORE_FILE_ID_PREFIX = "ms-plfile:"
  }
}

class MediaStoreMusicSourceRepository(
  private val gateway: MediaStoreGateway,
  private val metadataExtractor: AudioMetadataExtractor,
  private val playlistCodec: PlaylistFileCodec = WinampPlaylistCodec(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MusicSourceRepository {

  private data class NormalizedRecord(
    val record: MediaStoreAudioRecord,
    val folderSegments: List<String>,
    val folderKey: String,
  )

  private data class VirtualFolderNode(
    val key: String,
    val name: String,
    val segments: List<String>,
    val childKeys: LinkedHashSet<String> = linkedSetOf(),
    val directRecords: MutableList<NormalizedRecord> = mutableListOf(),
  )

  private data class VirtualFolderTree(
    val nodesByKey: Map<String, VirtualFolderNode>,
    val allNormalizedRecords: List<NormalizedRecord>,
  )

  private val _storageBackend = MutableStateFlow(StorageBackend.MEDIA_STORE)
  override val storageBackend: StateFlow<StorageBackend> = _storageBackend.asStateFlow()

  private val _linkedRoots = MutableStateFlow<List<FolderEntry>>(emptyList())
  override val linkedRoots: StateFlow<List<FolderEntry>> = _linkedRoots.asStateFlow()

  override suspend fun setStorageBackend(backend: StorageBackend) {
    // Switching is orchestrated by SwitchingMusicSourceRepository.
  }

  override suspend fun linkSafRoot(treeUriString: String) {
    // No-op on MediaStore backend.
  }

  override suspend fun unlinkAllRoots() {
    // MediaStore roots reflect device permission state; nothing to unlink.
  }

  suspend fun refreshRoots() {
    withContext(ioDispatcher) {
      refreshRootsInternal()
    }
  }

  override suspend fun browseFolder(folderUriString: String?): FolderContents =
    withContext(ioDispatcher) {
      if (!gateway.hasAudioReadPermission()) {
        _linkedRoots.value = emptyList()
        return@withContext FolderContents.EMPTY
      }

      val tree = buildVirtualTree()
      updateLinkedRootsFromTree(tree)

      val targetKey =
        when {
          folderUriString == null || folderUriString == MEDIASTORE_ROOT_URI -> ""
          folderUriString.startsWith(MEDIASTORE_FOLDER_URI_PREFIX) ->
            decodeFolderUriKey(folderUriString) ?: return@withContext FolderContents.EMPTY
          else -> return@withContext FolderContents.EMPTY
        }

      val node = tree.nodesByKey[targetKey] ?: return@withContext FolderContents.EMPTY

      val childFolders =
        node.childKeys
          .mapNotNull { tree.nodesByKey[it] }
          .sortedBy { it.name.lowercase(Locale.ROOT) }
          .map { child ->
            FolderEntry(
              uriString = encodeFolderUri(child.key),
              name = child.name,
              pathSegments = listOf(ROOT_DISPLAY_SEGMENT) + child.segments,
              trackCount = child.directRecords.size,
              subfolderCount = child.childKeys.size,
            )
          }

      val tracks =
        node.directRecords
          .sortedBy { it.record.displayName.lowercase(Locale.ROOT) }
          .map { extractTrack(it) }

      val isRoot = targetKey.isEmpty()
      val parentUri =
        when {
          isRoot -> null
          node.segments.size <= 1 -> MEDIASTORE_ROOT_URI
          else -> encodeFolderUri(node.segments.dropLast(1).joinToString("/"))
        }

      FolderContents(
        currentFolderUri = if (isRoot) MEDIASTORE_ROOT_URI else encodeFolderUri(node.key),
        currentPathSegments = listOf(ROOT_DISPLAY_SEGMENT) + node.segments,
        canNavigateUp = !isRoot,
        parentFolderUri = parentUri,
        folders = childFolders,
        tracks = tracks,
      )
    }

  override suspend fun resolveFolderTracks(
    folderUriString: String,
    recursive: Boolean,
  ): List<AudioTrack> =
    withContext(ioDispatcher) {
      if (!gateway.hasAudioReadPermission()) {
        _linkedRoots.value = emptyList()
        return@withContext emptyList()
      }

      val tree = buildVirtualTree()
      updateLinkedRootsFromTree(tree)

      val targetKey =
        when {
          folderUriString == MEDIASTORE_ROOT_URI -> ""
          folderUriString.startsWith(MEDIASTORE_FOLDER_URI_PREFIX) ->
            decodeFolderUriKey(folderUriString) ?: return@withContext emptyList()
          else -> return@withContext emptyList()
        }

      if (targetKey !in tree.nodesByKey) return@withContext emptyList()
      val tracks = ArrayList<AudioTrack>()
      collectTracksFromNode(
        tree = tree,
        key = targetKey,
        recursive = recursive,
        out = tracks,
      )
      tracks
    }

  override suspend fun listPlaylists(): List<SavedPlaylistEntry> =
    withContext(ioDispatcher) {
      if (!gateway.hasAudioReadPermission()) {
        _linkedRoots.value = emptyList()
        return@withContext emptyList()
      }

      val tree = buildVirtualTree()
      updateLinkedRootsFromTree(tree)
      val tracksById = HashMap<Long, AudioTrack>(tree.allNormalizedRecords.size)
      val normalizedTrackPairs = ArrayList<Pair<NormalizedRecord, AudioTrack>>(tree.allNormalizedRecords.size)
      for (nr in tree.allNormalizedRecords) {
        val track = extractTrack(nr)
        tracksById[nr.record.mediaId] = track
        normalizedTrackPairs.add(nr to track)
      }

      val entries = ArrayList<SavedPlaylistEntry>()
      for (pl in gateway.queryPlaylists()) {
        val resolved = pl.memberTrackIds.mapNotNull { tracksById[it] }
        entries.add(
          SavedPlaylistEntry(
            id = "$PLAYLIST_ID_PREFIX${pl.playlistId}",
            uriString = pl.contentUriString,
            name = pl.name,
            format = "m3u8",
            trackCount = resolved.size,
            totalDurationMs = resolved.sumOf { it.durationMs.coerceAtLeast(0L) },
          ),
        )
      }

      for (filePl in gateway.queryPlaylistFiles()) {
        val text = gateway.readTextFile(filePl.uriString) ?: continue
        val resolved = resolvePlaylistFileEntries(filePl, text, normalizedTrackPairs)
        entries.add(
          SavedPlaylistEntry(
            id = filePl.id,
            uriString = filePl.uriString,
            name = StorageSupportedFormats.playlistDisplayName(filePl.name),
            format = filePl.format.ifBlank { StorageSupportedFormats.playlistFormatFor(filePl.name) },
            trackCount = resolved.size,
            totalDurationMs = resolved.sumOf { it.durationMs.coerceAtLeast(0L) },
          ),
        )
      }

      entries.sortedWith(
        compareBy<SavedPlaylistEntry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id },
      )
    }

  override suspend fun resolvePlaylistTracks(playlistId: String): List<AudioTrack> =
    withContext(ioDispatcher) {
      if (!gateway.hasAudioReadPermission()) {
        _linkedRoots.value = emptyList()
        return@withContext emptyList()
      }

      val tree = buildVirtualTree()
      updateLinkedRootsFromTree(tree)

      if (playlistId.startsWith(PLAYLIST_ID_PREFIX)) {
        val targetId = playlistId.removePrefix(PLAYLIST_ID_PREFIX).toLongOrNull() ?: return@withContext emptyList()
        val pl = gateway.queryPlaylists().firstOrNull { it.playlistId == targetId } ?: return@withContext emptyList()
        val recordsById = tree.allNormalizedRecords.associateBy { it.record.mediaId }
        val resolved = ArrayList<AudioTrack>(pl.memberTrackIds.size)
        for (memberId in pl.memberTrackIds) {
          val nr = recordsById[memberId] ?: continue
          resolved.add(extractTrack(nr))
        }
        return@withContext resolved
      }

      val filePl =
        gateway.queryPlaylistFiles().firstOrNull { it.id == playlistId }
          ?: return@withContext emptyList()
      val text = gateway.readTextFile(filePl.uriString) ?: return@withContext emptyList()
      val trackPairs = tree.allNormalizedRecords.map { it to extractTrack(it) }
      resolvePlaylistFileEntries(filePl, text, trackPairs)
    }

  override suspend fun saveM3u8Playlist(
    fileName: String,
    content: String,
  ): Result<SavedPlaylistEntry> =
    withContext(ioDispatcher) {
      val sanitizedName =
        StorageSupportedFormats.sanitizePlaylistFileName(fileName)
          ?: return@withContext Result.failure(IllegalArgumentException("Invalid playlist file name"))

      val written =
        gateway.writeM3u8PlaylistFile(sanitizedName, content)
          ?: return@withContext Result.failure(IOException("Failed to save playlist $sanitizedName"))

      val resolvedTracks =
        if (gateway.hasAudioReadPermission()) {
          val tree = buildVirtualTree()
          updateLinkedRootsFromTree(tree)
          val trackPairs = tree.allNormalizedRecords.map { it to extractTrack(it) }
          resolvePlaylistFileEntries(written, content, trackPairs)
        } else {
          emptyList()
        }

      Result.success(
        SavedPlaylistEntry(
          id = written.id,
          uriString = written.uriString,
          name = StorageSupportedFormats.playlistDisplayName(written.name),
          format = "m3u8",
          trackCount = resolvedTracks.size,
          totalDurationMs = resolvedTracks.sumOf { it.durationMs.coerceAtLeast(0L) },
        ),
      )
    }

  private fun refreshRootsInternal() {
    if (!gateway.hasAudioReadPermission()) {
      _linkedRoots.value = emptyList()
      return
    }
    val tree = buildVirtualTree()
    updateLinkedRootsFromTree(tree)
  }

  private fun updateLinkedRootsFromTree(tree: VirtualFolderTree) {
    val rootNode = tree.nodesByKey[""]
    _linkedRoots.value =
      listOf(
        FolderEntry(
          uriString = MEDIASTORE_ROOT_URI,
          name = "Device Audio",
          pathSegments = listOf(ROOT_DISPLAY_SEGMENT),
          trackCount = rootNode?.directRecords?.size ?: 0,
          subfolderCount = rootNode?.childKeys?.size ?: 0,
        ),
      )
  }

  private fun buildVirtualTree(): VirtualFolderTree {
    val rawRecords = gateway.queryAudioTracks()
    val nodes = LinkedHashMap<String, VirtualFolderNode>()
    nodes[""] = VirtualFolderNode(key = "", name = ROOT_DISPLAY_SEGMENT, segments = emptyList())

    val normalizedRecords = ArrayList<NormalizedRecord>(rawRecords.size)
    for (record in rawRecords) {
      val segments = deriveFolderSegments(record)
      val key = segments.joinToString("/")
      val nr = NormalizedRecord(record = record, folderSegments = segments, folderKey = key)
      normalizedRecords.add(nr)

      var parentKey = ""
      for (i in segments.indices) {
        val prefixSegments = segments.subList(0, i + 1)
        val currentKey = prefixSegments.joinToString("/")
        val node =
          nodes.getOrPut(currentKey) {
            VirtualFolderNode(
              key = currentKey,
              name = segments[i],
              segments = prefixSegments.toList(),
            )
          }
        nodes[parentKey]?.childKeys?.add(currentKey)
        parentKey = currentKey
        if (i == segments.lastIndex) {
          node.directRecords.add(nr)
        }
      }
      if (segments.isEmpty()) {
        nodes[""]?.directRecords?.add(nr)
      }
    }

    return VirtualFolderTree(
      nodesByKey = nodes,
      allNormalizedRecords = normalizedRecords,
    )
  }

  private suspend fun collectTracksFromNode(
    tree: VirtualFolderTree,
    key: String,
    recursive: Boolean,
    out: MutableList<AudioTrack>,
  ) {
    val node = tree.nodesByKey[key] ?: return
    val sortedDirect = node.directRecords.sortedBy { it.record.displayName.lowercase(Locale.ROOT) }
    for (nr in sortedDirect) {
      out.add(extractTrack(nr))
    }
    if (!recursive) return
    val sortedChildren =
      node.childKeys
        .mapNotNull { tree.nodesByKey[it] }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
    for (child in sortedChildren) {
      collectTracksFromNode(tree, child.key, recursive = true, out = out)
    }
  }

  private suspend fun extractTrack(nr: NormalizedRecord): AudioTrack {
    val folderPath = nr.folderKey.ifEmpty { ROOT_DISPLAY_SEGMENT }
    return metadataExtractor.extractTrack(
      id = "ms-${nr.record.mediaId}",
      uriString = nr.record.contentUriString,
      fileName = nr.record.displayName,
      folderPath = folderPath,
      fallbackTitle = nr.record.title,
      fallbackArtist = nr.record.artist,
      fallbackAlbum = nr.record.album,
      fallbackDurationMs = nr.record.durationMs,
    )
  }

  private fun resolvePlaylistFileEntries(
    playlistFile: MediaStorePlaylistFileRecord,
    content: String,
    tracks: List<Pair<NormalizedRecord, AudioTrack>>,
  ): List<AudioTrack> {
    val format = playlistFile.format.ifBlank { StorageSupportedFormats.playlistFormatFor(playlistFile.name) }
    val rawEntries =
      if (format.equals("pls", ignoreCase = true)) {
        playlistCodec.parsePls(content)
      } else {
        playlistCodec.parseM3u(content)
      }
    if (rawEntries.isEmpty() || tracks.isEmpty()) return emptyList()

    val byUri = LinkedHashMap<String, AudioTrack>(tracks.size)
    val byNormalizedDataPath = LinkedHashMap<String, AudioTrack>(tracks.size)
    val byNormalizedRelPath = LinkedHashMap<String, AudioTrack>(tracks.size)

    for ((nr, track) in tracks) {
      byUri[track.uriString] = track
      if (nr.record.dataPath.isNotBlank()) {
        byNormalizedDataPath[normalizePathKey(nr.record.dataPath)] = track
      }
      val relFull =
        if (nr.folderSegments.isEmpty()) {
          nr.record.displayName
        } else {
          (nr.folderSegments + nr.record.displayName).joinToString("/")
        }
      byNormalizedRelPath[normalizePathKey(relFull)] = track
    }

    val playlistFolder = normalizeStoragePrefix(playlistFile.folderPath)
    val resolved = ArrayList<AudioTrack>(rawEntries.size)
    for (rawEntry in rawEntries) {
      val resolvedPath = playlistCodec.resolveEntryPath(rawEntry, playlistFile.folderPath)
      if (resolvedPath.isEmpty()) continue

      byUri[resolvedPath]?.let {
        resolved.add(it)
        continue
      }

      val effectivePath =
        if (resolvedPath.startsWith("file://", ignoreCase = true)) {
          extractFileUriPath(resolvedPath)
        } else {
          resolvedPath
        }

      val normalizedResolved = normalizePathKey(effectivePath)
      byNormalizedDataPath[normalizedResolved]?.let {
        resolved.add(it)
        continue
      }

      if (URI_SCHEME_REGEX.matches(effectivePath)) continue

      val strippedResolved = normalizeStoragePrefix(effectivePath)
      byNormalizedRelPath[normalizePathKey(strippedResolved)]?.let {
        resolved.add(it)
        continue
      }

      val entrySegments = splitCleanSegments(strippedResolved)
      if (entrySegments.isEmpty()) continue

      // Check relative to playlist folder if playlist lives inside storage
      val playlistRelSegments =
        if (!rawEntry.trim().startsWith('/') &&
          !WINDOWS_DRIVE_ABS_REGEX.matches(rawEntry.trim().replace('\\', '/')) &&
          !URI_SCHEME_REGEX.matches(rawEntry.trim()) &&
          playlistFolder.isNotEmpty()
        ) {
          collapseSegments(splitCleanSegments(playlistFolder) + splitCleanSegments(rawEntry))
        } else {
          emptyList()
        }

      val exactPlaylistMatch =
        if (playlistRelSegments.isNotEmpty()) {
          tracks.firstOrNull { (nr, _) ->
            segmentsEqualIgnoreCase(nr.folderSegments + nr.record.displayName, playlistRelSegments)
          }
        } else {
          null
        }
      if (exactPlaylistMatch != null) {
        resolved.add(exactPlaylistMatch.second)
        continue
      }

      val exactEntryMatch =
        tracks.firstOrNull { (nr, _) ->
          segmentsEqualIgnoreCase(nr.folderSegments + nr.record.displayName, entrySegments)
        }
      if (exactEntryMatch != null) {
        resolved.add(exactEntryMatch.second)
        continue
      }

      val bestSuffixMatch =
        tracks
          .filter { (nr, _) ->
            val trackRelSegments = nr.folderSegments + nr.record.displayName
            trackRelSegments.size >= 2 &&
              segmentsEndWithIgnoreCase(entrySegments, trackRelSegments)
          }.maxByOrNull { (nr, _) ->
            nr.folderSegments.size + 1
          }
      if (bestSuffixMatch != null) {
        resolved.add(bestSuffixMatch.second)
      }
    }
    return resolved
  }

  private fun extractFileUriPath(fileUri: String): String {
    var path = fileUri.substring("file://".length)
    if (path.startsWith("localhost/", ignoreCase = true)) {
      path = path.substring("localhost".length)
    }
    path = percentDecode(path)
    if (path.length >= 4 && path[0] == '/' && path[1].isLetter() && path[2] == ':' && path[3] == '/') {
      path = path.substring(1)
    }
    return path
  }

  private fun deriveFolderSegments(record: MediaStoreAudioRecord): List<String> {
    val rel = normalizeStoragePrefix(record.relativePath)
    if (rel.isNotEmpty()) {
      return splitCleanSegments(rel)
    }
    val dataParent = record.dataPath.replace('\\', '/').substringBeforeLast('/', missingDelimiterValue = "")
    val strippedParent = normalizeStoragePrefix(dataParent)
    return splitCleanSegments(strippedParent)
  }

  private fun normalizeStoragePrefix(rawPath: String): String {
    var path = rawPath.trim().replace('\\', '/').trim('/')
    if (path.isEmpty()) return ""
    if (WINDOWS_DRIVE_ABS_REGEX.matches(path)) {
      path = path.substring(3).trimStart('/')
    }
    val lower = path.lowercase(Locale.ROOT)
    val prefixMatch = KNOWN_STORAGE_PREFIX_REGEX.matchAt(lower, 0)
    if (prefixMatch != null) {
      path = path.substring(prefixMatch.range.last + 1).trimStart('/')
    }
    return path
  }

  private fun splitCleanSegments(path: String): List<String> =
    path.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }

  private fun collapseSegments(segments: List<String>): List<String> {
    val stack = ArrayDeque<String>()
    for (seg in segments) {
      when (seg) {
        "", "." -> Unit
        ".." -> if (stack.isNotEmpty()) stack.removeLast()
        else -> stack.addLast(seg)
      }
    }
    return stack.toList()
  }

  private fun normalizePathKey(path: String): String =
    path.trim().replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

  private fun segmentsEqualIgnoreCase(
    a: List<String>,
    b: List<String>,
  ): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) {
      if (!a[i].equals(b[i], ignoreCase = true)) return false
    }
    return true
  }

  private fun segmentsEndWithIgnoreCase(
    full: List<String>,
    suffix: List<String>,
  ): Boolean {
    if (suffix.isEmpty() || full.size < suffix.size) return false
    val offset = full.size - suffix.size
    for (i in suffix.indices) {
      if (!full[offset + i].equals(suffix[i], ignoreCase = true)) return false
    }
    return true
  }

  private fun encodeFolderUri(key: String): String =
    MEDIASTORE_FOLDER_URI_PREFIX + percentEncodePath(key)

  private fun decodeFolderUriKey(uriString: String): String? {
    if (!uriString.startsWith(MEDIASTORE_FOLDER_URI_PREFIX)) return null
    val encoded = uriString.removePrefix(MEDIASTORE_FOLDER_URI_PREFIX)
    return percentDecode(encoded).trim('/')
  }

  private fun percentEncodePath(value: String): String =
    buildString(value.length) {
      for (ch in value) {
        when (ch) {
          '%' -> append("%25")
          ' ' -> append("%20")
          '#' -> append("%23")
          '?' -> append("%3F")
          else -> append(ch)
        }
      }
    }

  private fun percentDecode(input: String): String {
    if ('%' !in input) return input
    val out = StringBuilder(input.length)
    val bytes = ByteArrayOutputStream()
    fun flushBytes() {
      if (bytes.size() > 0) {
        out.append(bytes.toString(Charsets.UTF_8.name()))
        bytes.reset()
      }
    }
    var i = 0
    while (i < input.length) {
      val ch = input[i]
      if (ch == '%' && i + 2 < input.length) {
        val hi = hexDigitValue(input[i + 1])
        val lo = hexDigitValue(input[i + 2])
        if (hi >= 0 && lo >= 0) {
          bytes.write((hi shl 4) or lo)
          i += 3
          continue
        }
      }
      flushBytes()
      out.append(ch)
      i += 1
    }
    flushBytes()
    return out.toString()
  }

  private fun hexDigitValue(c: Char): Int =
    when (c) {
      in '0'..'9' -> c - '0'
      in 'a'..'f' -> c - 'a' + 10
      in 'A'..'F' -> c - 'A' + 10
      else -> -1
    }

  companion object {
    const val MEDIASTORE_ROOT_URI = "mediastore://folders"
    const val MEDIASTORE_FOLDER_URI_PREFIX = "mediastore://folder/"
    private const val ROOT_DISPLAY_SEGMENT = "MEDIASTORE"
    private const val PLAYLIST_ID_PREFIX = "ms-pl:"
    private val KNOWN_STORAGE_PREFIX_REGEX =
      Regex("^(?:storage/emulated/\\d+|storage/self/primary|mnt/sdcard|sdcard)(?:/|$)")
    private val URI_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
    private val WINDOWS_DRIVE_ABS_REGEX = Regex("^[a-zA-Z]:/.*")
  }
}
