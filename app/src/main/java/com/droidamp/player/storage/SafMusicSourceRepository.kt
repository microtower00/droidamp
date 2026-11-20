package com.droidamp.player.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.droidamp.player.domain.contract.MusicSourceRepository
import com.droidamp.player.domain.contract.PlaylistFileCodec
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.interop.WinampPlaylistCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

data class SafDocumentNode(
  val uriString: String,
  val name: String,
  val isDirectory: Boolean,
  val mimeType: String? = null,
)

interface SafTreeGateway {
  fun takePersistablePermission(treeUriString: String): Boolean
  fun releasePersistablePermission(treeUriString: String)
  fun getPersistedReadTreeUris(): Set<String>
  fun isTreeAccessible(treeUriString: String): Boolean
  fun getTreeRootNode(treeUriString: String): SafDocumentNode?
  fun listChildren(
    treeUriString: String,
    directoryUriString: String,
  ): List<SafDocumentNode>?
  fun readTextFile(documentUriString: String): String?
  fun writeTextFile(
    treeUriString: String,
    directoryUriString: String,
    fileName: String,
    mimeType: String,
    content: String,
  ): SafDocumentNode?
}

/**
 * Android SAF gateway backed by [DocumentFile] and [DocumentsContract].
 *
 * Uses [DocumentsContract.buildChildDocumentsUriUsingTree] for directory queries to avoid
 * `DocumentFile.fromTreeUri`'s subfolder root-reset behavior and IPC overhead.
 */
class DocumentFileSafTreeGateway(
  context: Context,
) : SafTreeGateway {
  private val appContext = context.applicationContext
  private val resolver = appContext.contentResolver

  override fun takePersistablePermission(treeUriString: String): Boolean {
    val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return false
    val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val tookRw =
      runCatching {
        resolver.takePersistableUriPermission(treeUri, rwFlags)
      }.isSuccess
    if (!tookRw) {
      runCatching {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    }
    if (treeUriString in getPersistedReadTreeUris()) return true
    return isTreeAccessible(treeUriString)
  }

  override fun releasePersistablePermission(treeUriString: String) {
    val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return
    val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val releasedBoth =
      runCatching {
        resolver.releasePersistableUriPermission(treeUri, rwFlags)
      }.isSuccess
    if (!releasedBoth) {
      runCatching {
        resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      runCatching {
        resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
      }
    }
  }

  override fun getPersistedReadTreeUris(): Set<String> =
    runCatching {
      resolver.persistedUriPermissions
        .filter { it.isReadPermission }
        .map { it.uri.toString() }
        .toSet()
    }.getOrDefault(emptySet())

  override fun isTreeAccessible(treeUriString: String): Boolean {
    val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return false
    return runCatching {
      val rootDoc = DocumentFile.fromTreeUri(appContext, treeUri) ?: return@runCatching false
      rootDoc.exists() && rootDoc.canRead()
    }.getOrDefault(false)
  }

  override fun getTreeRootNode(treeUriString: String): SafDocumentNode? {
    val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return null
    return runCatching {
      val rootDoc = DocumentFile.fromTreeUri(appContext, treeUri) ?: return@runCatching null
      if (!rootDoc.exists() || !rootDoc.canRead()) return@runCatching null
      val docId = DocumentsContract.getTreeDocumentId(treeUri)
      val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
      val name =
        rootDoc.name?.takeIf { it.isNotBlank() }
          ?: docId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Music" }
      SafDocumentNode(
        uriString = rootDocUri.toString(),
        name = name,
        isDirectory = true,
        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
      )
    }.getOrNull()
  }

  override fun listChildren(
    treeUriString: String,
    directoryUriString: String,
  ): List<SafDocumentNode>? {
    val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return null
    val dirUri = runCatching { Uri.parse(directoryUriString) }.getOrNull() ?: return null
    return try {
      val parentDocId =
        if (dirUri.pathSegments.contains("document")) {
          DocumentsContract.getDocumentId(dirUri)
        } else {
          DocumentsContract.getTreeDocumentId(treeUri)
        }
      val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
      val projection =
        arrayOf(
          DocumentsContract.Document.COLUMN_DOCUMENT_ID,
          DocumentsContract.Document.COLUMN_DISPLAY_NAME,
          DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
      val cursor = resolver.query(childrenUri, projection, null, null, null) ?: return null
      cursor.use {
        val idCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        if (idCol < 0 || nameCol < 0) return emptyList()

        val nodes = ArrayList<SafDocumentNode>()
        while (it.moveToNext()) {
          val docId = it.getString(idCol) ?: continue
          val name = it.getString(nameCol)?.takeIf { n -> n.isNotBlank() } ?: continue
          val mimeType = if (mimeCol >= 0) it.getString(mimeCol) else null
          val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
          val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
          nodes.add(
            SafDocumentNode(
              uriString = childUri.toString(),
              name = name,
              isDirectory = isDir,
              mimeType = mimeType,
            ),
          )
        }
        nodes
      }
    } catch (_: SecurityException) {
      null
    } catch (_: Throwable) {
      null
    }
  }

  override fun readTextFile(documentUriString: String): String? {
    val docUri = runCatching { Uri.parse(documentUriString) }.getOrNull() ?: return null
    return runCatching {
      resolver.openInputStream(docUri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.getOrNull()
  }

  override fun writeTextFile(
    treeUriString: String,
    directoryUriString: String,
    fileName: String,
    mimeType: String,
    content: String,
  ): SafDocumentNode? {
    val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return null
    val dirUri = runCatching { Uri.parse(directoryUriString) }.getOrNull() ?: return null
    val ext = StorageSupportedFormats.fileExtensionLower(fileName)
    val resolvedMime =
      MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { it.isNotBlank() }
        ?: mimeType

    return try {
      val existing =
        listChildren(treeUriString, directoryUriString)
          ?.firstOrNull { !it.isDirectory && it.name.equals(fileName, ignoreCase = true) }

      if (existing != null) {
        val existingUri = Uri.parse(existing.uriString)
        val wroteTruncated =
          runCatching {
            resolver.openOutputStream(existingUri, "wt")?.use { out ->
              out.write(content.toByteArray(Charsets.UTF_8))
              out.flush()
              true
            } ?: false
          }.getOrDefault(false)
        if (wroteTruncated) {
          return existing
        }
        runCatching { DocumentsContract.deleteDocument(resolver, existingUri) }
      }

      val parentDocUri =
        if (dirUri.pathSegments.contains("document")) {
          dirUri
        } else {
          DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
          )
        }
      val createdUri =
        DocumentsContract.createDocument(resolver, parentDocUri, resolvedMime, fileName)
          ?: return null
      val wrote =
        runCatching {
          resolver.openOutputStream(createdUri, "wt")?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            true
          } ?: false
        }.getOrElse {
          resolver.openOutputStream(createdUri, "w")?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            true
          } ?: false
        }
      if (!wrote) return null

      val actualName =
        DocumentFile.fromSingleUri(appContext, createdUri)?.name?.takeIf { it.isNotBlank() }
          ?: fileName
      SafDocumentNode(
        uriString = createdUri.toString(),
        name = actualName,
        isDirectory = false,
        mimeType = resolvedMime,
      )
    } catch (_: Throwable) {
      null
    }
  }
}

class SafMusicSourceRepository(
  private val gateway: SafTreeGateway,
  private val preferencesStore: StoragePreferencesStore,
  private val metadataExtractor: AudioMetadataExtractor,
  private val playlistCodec: PlaylistFileCodec = WinampPlaylistCodec(),
  @Suppress("unused") private val scope: CoroutineScope,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val maxTraversalDepth: Int = DEFAULT_MAX_TRAVERSAL_DEPTH,
) : MusicSourceRepository {

  private data class LinkedRootRecord(
    val treeUriString: String,
    val rootDocUriString: String,
    val displayName: String,
    val entry: FolderEntry,
  )

  private data class ResolvedDirectoryTarget(
    val root: LinkedRootRecord,
    val directoryDocUriString: String,
    val canonicalUriString: String,
    val pathSegments: List<String>,
    val parentFolderUri: String?,
    val canNavigateUp: Boolean,
  )

  private data class IndexedTrackRecord(
    val track: AudioTrack,
    val root: LinkedRootRecord,
    val relativeSegmentsFromRoot: List<String>,
    val fullPathSegments: List<String>,
  )

  private data class DiscoveredPlaylist(
    val node: SafDocumentNode,
    val root: LinkedRootRecord,
    val parentDirDocUriString: String,
    val parentDirSegmentsFromRoot: List<String>,
  )

  private val stateMutex = Mutex()
  private var loadedFromStore = false
  private var rootRecords: List<LinkedRootRecord> = emptyList()

  private val _storageBackend = MutableStateFlow(StorageBackend.SAF)
  override val storageBackend: StateFlow<StorageBackend> = _storageBackend.asStateFlow()

  private val _linkedRoots = MutableStateFlow<List<FolderEntry>>(emptyList())
  override val linkedRoots: StateFlow<List<FolderEntry>> = _linkedRoots.asStateFlow()

  override suspend fun setStorageBackend(backend: StorageBackend) {
    // Switching is orchestrated by SwitchingMusicSourceRepository; SAF repository remains SAF.
  }

  suspend fun refreshRoots() {
    withContext(ioDispatcher) {
      stateMutex.withLock {
        ensureLoadedAndPruneLocked()
      }
    }
  }

  override suspend fun linkSafRoot(treeUriString: String) {
    val trimmed = treeUriString.trim()
    if (trimmed.isEmpty()) return
    withContext(ioDispatcher) {
      stateMutex.withLock {
        ensureLoadedAndPruneLocked()
        val granted = gateway.takePersistablePermission(trimmed)
        if (!granted || !gateway.isTreeAccessible(trimmed)) {
          return@withLock
        }
        val rootNode = gateway.getTreeRootNode(trimmed) ?: return@withLock
        val displayName = rootNode.name.ifBlank { deriveFallbackName(trimmed) }
        preferencesStore.addSafRoot(trimmed, displayName)
        reloadFromStoreLocked()
      }
    }
  }

  override suspend fun unlinkAllRoots() {
    withContext(ioDispatcher) {
      stateMutex.withLock {
        ensureLoadedAndPruneLocked()
        for (root in rootRecords) {
          gateway.releasePersistablePermission(root.treeUriString)
        }
        for (persisted in preferencesStore.loadSnapshot().safRoots) {
          gateway.releasePersistablePermission(persisted.uriString)
        }
        preferencesStore.clearSafRoots()
        rootRecords = emptyList()
        _linkedRoots.value = emptyList()
      }
    }
  }

  override suspend fun browseFolder(folderUriString: String?): FolderContents =
    withContext(ioDispatcher) {
      val roots =
        stateMutex.withLock {
          ensureLoadedAndPruneLocked()
          rootRecords
        }
      if (roots.isEmpty()) return@withContext FolderContents.EMPTY

      if (folderUriString == null || folderUriString == SAF_VIRTUAL_ROOT_URI) {
        if (roots.size == 1) {
          val onlyRoot = roots[0]
          return@withContext browseTargetDirectory(
            ResolvedDirectoryTarget(
              root = onlyRoot,
              directoryDocUriString = onlyRoot.rootDocUriString,
              canonicalUriString = onlyRoot.treeUriString,
              pathSegments = listOf(onlyRoot.displayName),
              parentFolderUri = null,
              canNavigateUp = false,
            ),
          )
        }
        return@withContext FolderContents(
          currentFolderUri = null,
          currentPathSegments = emptyList(),
          canNavigateUp = false,
          parentFolderUri = null,
          folders = roots.map { it.entry },
          tracks = emptyList(),
        )
      }

      val target = resolveDirectoryTarget(roots, folderUriString)
      if (target == null) {
        pruneInaccessibleRoots()
        return@withContext FolderContents.EMPTY
      }
      browseTargetDirectory(target)
    }

  override suspend fun resolveFolderTracks(
    folderUriString: String,
    recursive: Boolean,
  ): List<AudioTrack> =
    withContext(ioDispatcher) {
      val roots =
        stateMutex.withLock {
          ensureLoadedAndPruneLocked()
          rootRecords
        }
      if (roots.isEmpty()) return@withContext emptyList()

      if (folderUriString == SAF_VIRTUAL_ROOT_URI) {
        if (!recursive) return@withContext emptyList()
        val allTracks = ArrayList<AudioTrack>()
        for (root in roots) {
          collectFolderTracksRecursive(
            root = root,
            directoryDocUriString = root.rootDocUriString,
            pathSegments = listOf(root.displayName),
            recursive = true,
            depth = 0,
            visitedDirs = HashSet(),
            out = allTracks,
          )
        }
        return@withContext allTracks
      }

      val target = resolveDirectoryTarget(roots, folderUriString) ?: return@withContext emptyList()
      val tracks = ArrayList<AudioTrack>()
      collectFolderTracksRecursive(
        root = target.root,
        directoryDocUriString = target.directoryDocUriString,
        pathSegments = target.pathSegments,
        recursive = recursive,
        depth = 0,
        visitedDirs = HashSet(),
        out = tracks,
      )
      tracks
    }

  override suspend fun listPlaylists(): List<SavedPlaylistEntry> =
    withContext(ioDispatcher) {
      val roots =
        stateMutex.withLock {
          ensureLoadedAndPruneLocked()
          rootRecords
        }
      if (roots.isEmpty()) return@withContext emptyList()

      val (tracks, playlists) = scanAllRoots(roots)
      if (playlists.isEmpty()) return@withContext emptyList()

      val trackIndex = buildTrackIndex(tracks)
      val entries = ArrayList<SavedPlaylistEntry>(playlists.size)
      for (pl in playlists) {
        val text = gateway.readTextFile(pl.node.uriString) ?: continue
        val resolvedTracks = resolvePlaylistEntries(pl, text, trackIndex)
        entries.add(
          SavedPlaylistEntry(
            id = pl.node.uriString,
            uriString = pl.node.uriString,
            name = StorageSupportedFormats.playlistDisplayName(pl.node.name),
            format = StorageSupportedFormats.playlistFormatFor(pl.node.name),
            trackCount = resolvedTracks.size,
            totalDurationMs = resolvedTracks.sumOf { it.durationMs.coerceAtLeast(0L) },
          ),
        )
      }
      entries.sortedWith(
        compareBy<SavedPlaylistEntry> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id },
      )
    }

  override suspend fun resolvePlaylistTracks(playlistId: String): List<AudioTrack> =
    withContext(ioDispatcher) {
      val roots =
        stateMutex.withLock {
          ensureLoadedAndPruneLocked()
          rootRecords
        }
      if (roots.isEmpty()) return@withContext emptyList()

      val (tracks, playlists) = scanAllRoots(roots)
      val targetPlaylist = playlists.firstOrNull { it.node.uriString == playlistId } ?: return@withContext emptyList()
      val text = gateway.readTextFile(targetPlaylist.node.uriString) ?: return@withContext emptyList()
      val trackIndex = buildTrackIndex(tracks)
      resolvePlaylistEntries(targetPlaylist, text, trackIndex)
    }

  override suspend fun saveM3u8Playlist(
    fileName: String,
    content: String,
  ): Result<SavedPlaylistEntry> =
    withContext(ioDispatcher) {
      val sanitizedName =
        StorageSupportedFormats.sanitizePlaylistFileName(fileName)
          ?: return@withContext Result.failure(IllegalArgumentException("Invalid playlist file name"))

      val roots =
        stateMutex.withLock {
          ensureLoadedAndPruneLocked()
          rootRecords
        }
      val primaryRoot =
        roots.firstOrNull()
          ?: return@withContext Result.failure(IllegalStateException("No accessible SAF music folder linked"))

      val resolvedMime =
        if (sanitizedName.endsWith(".pls", ignoreCase = true)) {
          "audio/x-scpls"
        } else {
          MIME_M3U8
        }
      val writtenNode =
        gateway.writeTextFile(
          treeUriString = primaryRoot.treeUriString,
          directoryUriString = primaryRoot.rootDocUriString,
          fileName = sanitizedName,
          mimeType = resolvedMime,
          content = content,
        ) ?: return@withContext Result.failure(IOException("Failed to write SAF playlist $sanitizedName"))

      val (tracks, _) = scanAllRoots(roots)
      val trackIndex = buildTrackIndex(tracks)
      val virtualPlaylist =
        DiscoveredPlaylist(
          node = writtenNode,
          root = primaryRoot,
          parentDirDocUriString = primaryRoot.rootDocUriString,
          parentDirSegmentsFromRoot = emptyList(),
        )
      val resolvedTracks = resolvePlaylistEntries(virtualPlaylist, content, trackIndex)

      Result.success(
        SavedPlaylistEntry(
          id = writtenNode.uriString,
          uriString = writtenNode.uriString,
          name = StorageSupportedFormats.playlistDisplayName(writtenNode.name),
          format = StorageSupportedFormats.playlistFormatFor(writtenNode.name),
          trackCount = resolvedTracks.size,
          totalDurationMs = resolvedTracks.sumOf { it.durationMs.coerceAtLeast(0L) },
        ),
      )
    }

  private suspend fun ensureLoadedAndPruneLocked() {
    if (!loadedFromStore) {
      reloadFromStoreLocked()
      loadedFromStore = true
      return
    }
    val stillValid = rootRecords.filter { gateway.isTreeAccessible(it.treeUriString) }
    if (stillValid.size != rootRecords.size) {
      preferencesStore.retainSafRoots(stillValid.map { it.treeUriString }.toSet())
      rootRecords = stillValid
      _linkedRoots.value = stillValid.map { it.entry }
    }
  }

  private suspend fun reloadFromStoreLocked() {
    val snapshot = preferencesStore.loadSnapshot()
    val validRecords = ArrayList<LinkedRootRecord>()
    val validUris = LinkedHashSet<String>()
    var prunedAny = false

    for (persisted in snapshot.safRoots) {
      val treeUri = persisted.uriString
      if (!gateway.isTreeAccessible(treeUri)) {
        prunedAny = true
        continue
      }
      val rootNode = gateway.getTreeRootNode(treeUri)
      if (rootNode == null) {
        prunedAny = true
        continue
      }
      val displayName =
        persisted.displayName.takeIf { it.isNotBlank() }
          ?: rootNode.name.ifBlank { deriveFallbackName(treeUri) }

      val children = gateway.listChildren(treeUri, rootNode.uriString)
      if (children == null) {
        prunedAny = true
        continue
      }
      val audioCount = children.count { !it.isDirectory && StorageSupportedFormats.isAudioFile(it.name, it.mimeType) }
      val subdirCount = if (maxTraversalDepth <= 0) 0 else children.count { it.isDirectory }

      val entry =
        FolderEntry(
          uriString = treeUri,
          name = displayName,
          pathSegments = listOf(displayName),
          trackCount = audioCount,
          subfolderCount = subdirCount,
        )
      validRecords.add(
        LinkedRootRecord(
          treeUriString = treeUri,
          rootDocUriString = rootNode.uriString,
          displayName = displayName,
          entry = entry,
        ),
      )
      validUris.add(treeUri)
    }

    if (prunedAny) {
      preferencesStore.retainSafRoots(validUris)
    }
    rootRecords = validRecords
    _linkedRoots.value = validRecords.map { it.entry }
  }

  private suspend fun pruneInaccessibleRoots() {
    stateMutex.withLock {
      val stillValid = rootRecords.filter { gateway.isTreeAccessible(it.treeUriString) }
      if (stillValid.size != rootRecords.size) {
        preferencesStore.retainSafRoots(stillValid.map { it.treeUriString }.toSet())
        rootRecords = stillValid
        _linkedRoots.value = stillValid.map { it.entry }
      }
    }
  }

  private suspend fun browseTargetDirectory(target: ResolvedDirectoryTarget): FolderContents {
    val children = gateway.listChildren(target.root.treeUriString, target.directoryDocUriString)
    if (children == null) {
      pruneInaccessibleRoots()
      return FolderContents.EMPTY
    }

    val currentDepth = (target.pathSegments.size - 1).coerceAtLeast(0)
    val subdirs =
      if (currentDepth >= maxTraversalDepth) {
        emptyList()
      } else {
        children
          .filter { it.isDirectory }
          .sortedBy { it.name.lowercase(Locale.ROOT) }
          .map { dirNode ->
            val grandChildren = gateway.listChildren(target.root.treeUriString, dirNode.uriString).orEmpty()
            val trackCount =
              grandChildren.count {
                !it.isDirectory && StorageSupportedFormats.isAudioFile(it.name, it.mimeType)
              }
            val subfolderCount =
              if (currentDepth + 1 >= maxTraversalDepth) {
                0
              } else {
                grandChildren.count { it.isDirectory }
              }
            FolderEntry(
              uriString = dirNode.uriString,
              name = dirNode.name,
              pathSegments = target.pathSegments + dirNode.name,
              trackCount = trackCount,
              subfolderCount = subfolderCount,
            )
          }
      }

    val folderPath = target.pathSegments.joinToString("/")
    val tracks =
      children
        .filter { !it.isDirectory && StorageSupportedFormats.isAudioFile(it.name, it.mimeType) }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
        .map { fileNode ->
          extractTrack(fileNode, folderPath)
        }

    return FolderContents(
      currentFolderUri = target.canonicalUriString,
      currentPathSegments = target.pathSegments,
      canNavigateUp = target.canNavigateUp,
      parentFolderUri = target.parentFolderUri,
      folders = subdirs,
      tracks = tracks,
    )
  }

  private fun resolveDirectoryTarget(
    roots: List<LinkedRootRecord>,
    folderUriString: String,
  ): ResolvedDirectoryTarget? {
    val multiRoot = roots.size > 1
    for (root in roots) {
      if (folderUriString == root.treeUriString || folderUriString == root.rootDocUriString) {
        if (!gateway.isTreeAccessible(root.treeUriString)) return null
        return ResolvedDirectoryTarget(
          root = root,
          directoryDocUriString = root.rootDocUriString,
          canonicalUriString = root.treeUriString,
          pathSegments = listOf(root.displayName),
          parentFolderUri = if (multiRoot) SAF_VIRTUAL_ROOT_URI else null,
          canNavigateUp = multiRoot,
        )
      }
    }

    for (root in roots) {
      if (!gateway.isTreeAccessible(root.treeUriString)) continue
      val found =
        findSubdirectoryInRoot(
          root = root,
          currentDocUri = root.rootDocUriString,
          currentCanonicalUri = root.treeUriString,
          currentPathSegments = listOf(root.displayName),
          targetUriString = folderUriString,
          depth = 0,
          visited = HashSet(),
        )
      if (found != null) return found
    }
    return null
  }

  private fun findSubdirectoryInRoot(
    root: LinkedRootRecord,
    currentDocUri: String,
    currentCanonicalUri: String,
    currentPathSegments: List<String>,
    targetUriString: String,
    depth: Int,
    visited: MutableSet<String>,
  ): ResolvedDirectoryTarget? {
    if (depth >= maxTraversalDepth || !visited.add(currentDocUri)) return null
    val children = gateway.listChildren(root.treeUriString, currentDocUri) ?: return null
    for (child in children) {
      if (!child.isDirectory) continue
      val nextPath = currentPathSegments + child.name
      if (child.uriString == targetUriString) {
        return ResolvedDirectoryTarget(
          root = root,
          directoryDocUriString = child.uriString,
          canonicalUriString = child.uriString,
          pathSegments = nextPath,
          parentFolderUri = currentCanonicalUri,
          canNavigateUp = true,
        )
      }
      val deeper =
        findSubdirectoryInRoot(
          root = root,
          currentDocUri = child.uriString,
          currentCanonicalUri = child.uriString,
          currentPathSegments = nextPath,
          targetUriString = targetUriString,
          depth = depth + 1,
          visited = visited,
        )
      if (deeper != null) return deeper
    }
    return null
  }

  private suspend fun collectFolderTracksRecursive(
    root: LinkedRootRecord,
    directoryDocUriString: String,
    pathSegments: List<String>,
    recursive: Boolean,
    depth: Int,
    visitedDirs: MutableSet<String>,
    out: MutableList<AudioTrack>,
  ) {
    if (!visitedDirs.add(directoryDocUriString)) return
    val children = gateway.listChildren(root.treeUriString, directoryDocUriString) ?: return
    val folderPath = pathSegments.joinToString("/")

    val fileNodes =
      children
        .filter { !it.isDirectory && StorageSupportedFormats.isAudioFile(it.name, it.mimeType) }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
    for (fileNode in fileNodes) {
      out.add(extractTrack(fileNode, folderPath))
    }

    if (!recursive || depth >= maxTraversalDepth) return
    val subdirs =
      children
        .filter { it.isDirectory }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
    for (subdir in subdirs) {
      collectFolderTracksRecursive(
        root = root,
        directoryDocUriString = subdir.uriString,
        pathSegments = pathSegments + subdir.name,
        recursive = true,
        depth = depth + 1,
        visitedDirs = visitedDirs,
        out = out,
      )
    }
  }

  private suspend fun scanAllRoots(
    roots: List<LinkedRootRecord>,
  ): Pair<List<IndexedTrackRecord>, List<DiscoveredPlaylist>> {
    val tracks = ArrayList<IndexedTrackRecord>()
    val playlists = ArrayList<DiscoveredPlaylist>()

    for (root in roots) {
      if (!gateway.isTreeAccessible(root.treeUriString)) continue
      scanDirectory(
        root = root,
        directoryDocUriString = root.rootDocUriString,
        relativeSegmentsFromRoot = emptyList(),
        depth = 0,
        visitedDirs = HashSet(),
        tracksOut = tracks,
        playlistsOut = playlists,
      )
    }
    return tracks to playlists
  }

  private suspend fun scanDirectory(
    root: LinkedRootRecord,
    directoryDocUriString: String,
    relativeSegmentsFromRoot: List<String>,
    depth: Int,
    visitedDirs: MutableSet<String>,
    tracksOut: MutableList<IndexedTrackRecord>,
    playlistsOut: MutableList<DiscoveredPlaylist>,
  ) {
    if (!visitedDirs.add(directoryDocUriString)) return
    val children = gateway.listChildren(root.treeUriString, directoryDocUriString) ?: return
    val fullFolderSegments = listOf(root.displayName) + relativeSegmentsFromRoot
    val folderPath = fullFolderSegments.joinToString("/")

    val sortedFiles = children.filter { !it.isDirectory }.sortedBy { it.name.lowercase(Locale.ROOT) }
    for (fileNode in sortedFiles) {
      if (StorageSupportedFormats.isPlaylistFile(fileNode.name)) {
        playlistsOut.add(
          DiscoveredPlaylist(
            node = fileNode,
            root = root,
            parentDirDocUriString = directoryDocUriString,
            parentDirSegmentsFromRoot = relativeSegmentsFromRoot,
          ),
        )
      } else if (StorageSupportedFormats.isAudioFile(fileNode.name, fileNode.mimeType)) {
        val audioTrack = extractTrack(fileNode, folderPath)
        tracksOut.add(
          IndexedTrackRecord(
            track = audioTrack,
            root = root,
            relativeSegmentsFromRoot = relativeSegmentsFromRoot + fileNode.name,
            fullPathSegments = fullFolderSegments + fileNode.name,
          ),
        )
      }
    }

    if (depth >= maxTraversalDepth) return
    val sortedDirs = children.filter { it.isDirectory }.sortedBy { it.name.lowercase(Locale.ROOT) }
    for (dirNode in sortedDirs) {
      scanDirectory(
        root = root,
        directoryDocUriString = dirNode.uriString,
        relativeSegmentsFromRoot = relativeSegmentsFromRoot + dirNode.name,
        depth = depth + 1,
        visitedDirs = visitedDirs,
        tracksOut = tracksOut,
        playlistsOut = playlistsOut,
      )
    }
  }

  private suspend fun extractTrack(
    fileNode: SafDocumentNode,
    folderPath: String,
  ): AudioTrack =
    metadataExtractor.extractTrack(
      id = "saf:${fileNode.uriString}",
      uriString = fileNode.uriString,
      fileName = fileNode.name,
      folderPath = folderPath,
    )

  private class TrackIndex(
    val byExactUri: Map<String, IndexedTrackRecord>,
    val byNormalizedDecodedUri: Map<String, IndexedTrackRecord>,
    val allTracks: List<IndexedTrackRecord>,
  )

  private fun buildTrackIndex(tracks: List<IndexedTrackRecord>): TrackIndex {
    val byExact = LinkedHashMap<String, IndexedTrackRecord>(tracks.size)
    val byDecoded = LinkedHashMap<String, IndexedTrackRecord>(tracks.size)
    for (record in tracks) {
      byExact.putIfAbsent(record.track.uriString, record)
      byDecoded.putIfAbsent(normalizeDecodedUri(record.track.uriString), record)
    }
    return TrackIndex(
      byExactUri = byExact,
      byNormalizedDecodedUri = byDecoded,
      allTracks = tracks,
    )
  }

  private fun resolvePlaylistEntries(
    playlist: DiscoveredPlaylist,
    content: String,
    index: TrackIndex,
  ): List<AudioTrack> {
    val format = StorageSupportedFormats.playlistFormatFor(playlist.node.name)
    val rawEntries =
      if (format == "pls") {
        playlistCodec.parsePls(content)
      } else {
        playlistCodec.parseM3u(content)
      }
    if (rawEntries.isEmpty()) return emptyList()

    val resolvedTracks = ArrayList<AudioTrack>(rawEntries.size)
    for (rawEntry in rawEntries) {
      val resolved =
        resolveSingleEntry(
          rawEntry = rawEntry,
          playlist = playlist,
          index = index,
        )
      if (resolved != null) {
        resolvedTracks.add(resolved)
      }
    }
    return resolvedTracks
  }

  private fun resolveSingleEntry(
    rawEntry: String,
    playlist: DiscoveredPlaylist,
    index: TrackIndex,
  ): AudioTrack? {
    val trimmed = rawEntry.trim().replace('\\', '/')
    if (trimmed.isEmpty()) return null

    // 1. Resolve via WinampPlaylistCodec against the playlist's parent folder document URI.
    val codecResolved = playlistCodec.resolveEntryPath(trimmed, playlist.parentDirDocUriString)
    if (codecResolved.isNotEmpty()) {
      index.byExactUri[codecResolved]?.let { return it.track }
      index.byNormalizedDecodedUri[normalizeDecodedUri(codecResolved)]?.let { return it.track }
    }

    // 2. If raw entry is a relative path, resolve relative segments against playlist's folder in tree.
    val effectivePath =
      if (trimmed.startsWith("file://", ignoreCase = true)) {
        extractFileUriPath(trimmed)
      } else {
        trimmed
      }
    val isUri = URI_SCHEME_REGEX.matches(effectivePath)
    val isWindowsAbs = WINDOWS_DRIVE_ABS_REGEX.matches(effectivePath)
    val isPosixAbs = effectivePath.startsWith('/')

    if (!isUri && !isWindowsAbs && !isPosixAbs) {
      val targetRelSegments =
        collapseSegments(playlist.parentDirSegmentsFromRoot + effectivePath.split('/'), clampAtRoot = true)
      if (targetRelSegments.isNotEmpty()) {
        val matchInSameRoot =
          index.allTracks.firstOrNull { record ->
            record.root.treeUriString == playlist.root.treeUriString &&
              segmentsEqualIgnoreCase(record.relativeSegmentsFromRoot, targetRelSegments)
          }
        if (matchInSameRoot != null) return matchInSameRoot.track

        // Also walk children directly in case provider uses opaque document IDs with custom names
        walkRelativeTreeChild(playlist.root, targetRelSegments)?.let { walkedNode ->
          index.byExactUri[walkedNode.uriString]?.let { return it.track }
        }
      }
      return null
    }

    // 3. For Windows / POSIX absolute paths (including decoded file:// URIs), prioritize longest full-path
    // matches before falling back cleanly without false-matching unrelated POSIX mount directories.
    if (isWindowsAbs || isPosixAbs) {
      val pathOnly =
        if (isWindowsAbs) effectivePath.substring(3) else effectivePath.trimStart('/')
      val entrySegments = collapseSegments(pathOnly.split('/'), clampAtRoot = true)
      if (entrySegments.isEmpty()) return null

      // 3a. Exact fullPathSegments match
      val fullExactMatches =
        index.allTracks.filter { segmentsEqualIgnoreCase(it.fullPathSegments, entrySegments) }
      if (fullExactMatches.isNotEmpty()) {
        return selectUniqueMatch(fullExactMatches)
      }

      // 3b. Exact relativeSegmentsFromRoot match
      val relExactMatches =
        index.allTracks.filter { segmentsEqualIgnoreCase(it.relativeSegmentsFromRoot, entrySegments) }
      if (relExactMatches.isNotEmpty()) {
        return selectUniqueMatch(relExactMatches)
      }

      // 3c. Suffix match against fullPathSegments (longest unique match wins)
      val fullSuffixMatches =
        index.allTracks.filter { record ->
          record.fullPathSegments.size >= 2 &&
            segmentsEndWithIgnoreCase(entrySegments, record.fullPathSegments)
        }
      if (fullSuffixMatches.isNotEmpty()) {
        return selectUniqueLongestMatch(fullSuffixMatches) { it.fullPathSegments.size }
      }

      // 3d. Windows desktop exports often omit the Android SAF root name -> longest relative suffix match
      if (isWindowsAbs) {
        val relSuffixMatches =
          index.allTracks.filter { record ->
            record.relativeSegmentsFromRoot.size >= 2 &&
              segmentsEndWithIgnoreCase(entrySegments, record.relativeSegmentsFromRoot)
          }
        if (relSuffixMatches.isNotEmpty()) {
          return selectUniqueLongestMatch(relSuffixMatches) { it.relativeSegmentsFromRoot.size }
        }
      } else {
        // 3e. POSIX paths under known Android device mount prefixes when SAF root is the storage volume root
        val mountStripped = stripKnownAndroidMountPrefix(entrySegments)
        if (mountStripped != null && mountStripped.isNotEmpty()) {
          val mountRelMatches =
            index.allTracks.filter { segmentsEqualIgnoreCase(it.relativeSegmentsFromRoot, mountStripped) }
          if (mountRelMatches.isNotEmpty()) {
            return selectUniqueMatch(mountRelMatches)
          }
        }
      }
    }

    return null
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

  private fun stripKnownAndroidMountPrefix(segments: List<String>): List<String>? =
    when {
      segments.size > 3 &&
        segments[0].equals("storage", ignoreCase = true) &&
        segments[1].equals("emulated", ignoreCase = true) &&
        segments[2].all { it.isDigit() } -> segments.drop(3)
      segments.size > 3 &&
        segments[0].equals("storage", ignoreCase = true) &&
        segments[1].equals("self", ignoreCase = true) &&
        segments[2].equals("primary", ignoreCase = true) -> segments.drop(3)
      segments.size > 2 &&
        segments[0].equals("mnt", ignoreCase = true) &&
        segments[1].equals("sdcard", ignoreCase = true) -> segments.drop(2)
      segments.size > 1 &&
        segments[0].equals("sdcard", ignoreCase = true) -> segments.drop(1)
      else -> null
    }

  private fun selectUniqueMatch(candidates: List<IndexedTrackRecord>): AudioTrack? {
    val distinct = candidates.distinctBy { it.track.uriString }
    return if (distinct.size == 1) distinct[0].track else null
  }

  private fun selectUniqueLongestMatch(
    candidates: List<IndexedTrackRecord>,
    lengthSelector: (IndexedTrackRecord) -> Int,
  ): AudioTrack? {
    if (candidates.isEmpty()) return null
    val maxLength = candidates.maxOf(lengthSelector)
    val longest = candidates.filter { lengthSelector(it) == maxLength }
    return selectUniqueMatch(longest)
  }

  private fun walkRelativeTreeChild(
    root: LinkedRootRecord,
    targetRelSegments: List<String>,
  ): SafDocumentNode? {
    var currentDirUri = root.rootDocUriString
    for (i in targetRelSegments.indices) {
      val segment = targetRelSegments[i]
      val isLast = i == targetRelSegments.lastIndex
      val children = gateway.listChildren(root.treeUriString, currentDirUri) ?: return null
      val match =
        children.firstOrNull { node ->
          node.isDirectory == !isLast && node.name == segment
        } ?: children.firstOrNull { node ->
          node.isDirectory == !isLast && node.name.equals(segment, ignoreCase = true)
        } ?: return null
      if (isLast) return match
      currentDirUri = match.uriString
    }
    return null
  }

  private fun normalizeDecodedUri(uriString: String): String {
    val decoded = percentDecode(uriString).replace('\\', '/')
    return decoded.trim().lowercase(Locale.ROOT)
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

  private fun collapseSegments(
    segments: List<String>,
    clampAtRoot: Boolean,
  ): List<String> {
    val stack = ArrayDeque<String>()
    for (seg in segments) {
      when (seg.trim()) {
        "", "." -> Unit
        ".." -> {
          if (stack.isNotEmpty() && stack.last() != "..") {
            stack.removeLast()
          } else if (!clampAtRoot) {
            stack.addLast("..")
          }
        }
        else -> stack.addLast(seg.trim())
      }
    }
    return stack.toList()
  }

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

  private fun deriveFallbackName(treeUriString: String): String {
    val decoded = percentDecode(treeUriString)
    return decoded.substringAfterLast(':').substringAfterLast('/').ifBlank { "Music" }
  }

  companion object {
    const val SAF_VIRTUAL_ROOT_URI = "droidamp://saf-roots"
    const val DEFAULT_MAX_TRAVERSAL_DEPTH = 16
    private const val MIME_M3U8 = "audio/x-mpegurl"
    private val URI_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
    private val WINDOWS_DRIVE_ABS_REGEX = Regex("^[a-zA-Z]:/.*")
  }
}
