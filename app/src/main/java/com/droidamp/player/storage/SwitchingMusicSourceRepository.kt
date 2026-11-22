package com.droidamp.player.storage

import com.droidamp.player.domain.contract.MusicSourceRepository
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.LibraryScanState
import com.droidamp.player.domain.model.ManagedSafFolder
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.StorageBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Delegating [MusicSourceRepository] that dynamically routes operations to either
 * [SafMusicSourceRepository] (default) or [MediaStoreMusicSourceRepository], keeping
 * [storageBackend], [linkedRoots], [managedSafFolders], and [LibraryMetadataCache]
 * synchronized with [StoragePreferencesStore].
 */
class SwitchingMusicSourceRepository(
  private val safRepository: SafMusicSourceRepository,
  private val mediaStoreRepository: MediaStoreMusicSourceRepository,
  private val preferencesStore: StoragePreferencesStore,
  private val scope: CoroutineScope,
  private val metadataCache: LibraryMetadataCache? = null,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MusicSourceRepository {

  private val loadMutex = Mutex()
  private var loaded = false
  private var reconcileJob: Job? = null

  private val _storageBackend = MutableStateFlow(StorageBackend.SAF)
  override val storageBackend: StateFlow<StorageBackend> = _storageBackend.asStateFlow()

  private val _linkedRoots = MutableStateFlow<List<FolderEntry>>(emptyList())
  override val linkedRoots: StateFlow<List<FolderEntry>> = _linkedRoots.asStateFlow()

  override val managedSafFolders: StateFlow<List<ManagedSafFolder>> =
    safRepository.managedSafFolders

  private val _scanState = MutableStateFlow(LibraryScanState())
  override val scanState: StateFlow<LibraryScanState> = _scanState.asStateFlow()

  init {
    scope.launch {
      ensureLoaded()
      combine(
        _storageBackend,
        safRepository.linkedRoots,
        mediaStoreRepository.linkedRoots,
      ) { backend, safRoots, mediaStoreRoots ->
        when (backend) {
          StorageBackend.SAF -> safRoots
          StorageBackend.MEDIA_STORE -> mediaStoreRoots
        }
      }.collect { activeRoots ->
        _linkedRoots.value = activeRoots
      }
    }
  }

  override suspend fun setStorageBackend(backend: StorageBackend) {
    loadMutex.withLock {
      ensureLoadedLocked()
      preferencesStore.setStorageBackend(backend)
      _storageBackend.value = backend
      emitCachedSnapshotImmediately(backend)
      refreshActiveBackend(backend)
      scheduleBackgroundReconcile(backend)
    }
  }

  override suspend fun linkSafRoot(treeUriString: String) {
    loadMutex.withLock {
      ensureLoadedLocked()
      safRepository.linkSafRoot(treeUriString)
      if (_storageBackend.value == StorageBackend.SAF) {
        _linkedRoots.value = safRepository.linkedRoots.value
      }
      scheduleBackgroundReconcile(_storageBackend.value)
    }
  }

  override suspend fun removeSafRoot(treeUriString: String) {
    loadMutex.withLock {
      ensureLoadedLocked()
      safRepository.removeSafRoot(treeUriString)
      if (_storageBackend.value == StorageBackend.SAF) {
        _linkedRoots.value = safRepository.linkedRoots.value
      }
      scheduleBackgroundReconcile(_storageBackend.value)
    }
  }

  override suspend fun replaceSafRoot(
    oldUriString: String,
    newUriString: String,
  ): Boolean =
    loadMutex.withLock {
      ensureLoadedLocked()
      val replaced = safRepository.replaceSafRoot(oldUriString, newUriString)
      if (_storageBackend.value == StorageBackend.SAF) {
        _linkedRoots.value = safRepository.linkedRoots.value
      }
      scheduleBackgroundReconcile(_storageBackend.value)
      replaced
    }

  override suspend fun unlinkAllRoots() {
    loadMutex.withLock {
      ensureLoadedLocked()
      safRepository.unlinkAllRoots()
      if (_storageBackend.value == StorageBackend.SAF) {
        _linkedRoots.value = safRepository.linkedRoots.value
        metadataCache?.clear(StorageBackend.SAF)
        _scanState.value =
          LibraryScanState(
            cachedTrackCount = 0,
            isRefreshing = false,
            hasAudioPermission = hasMediaStoreAudioPermission(),
          )
      }
    }
  }

  override suspend fun browseFolder(folderUriString: String?): FolderContents {
    ensureLoaded()
    return activeRepository().browseFolder(folderUriString)
  }

  override suspend fun resolveFolderTracks(
    folderUriString: String,
    recursive: Boolean,
  ): List<AudioTrack> {
    ensureLoaded()
    return activeRepository().resolveFolderTracks(folderUriString, recursive)
  }

  override suspend fun rescanLibrary(): Int {
    ensureLoaded()
    val backend = _storageBackend.value
    return reconcileNow(backend)
  }

  override suspend fun pruneMissingTrack(uriString: String) {
    val backend = _storageBackend.value
    val updated = metadataCache?.pruneTrackUri(backend, uriString)
    val count = updated?.tracks?.size ?: (_scanState.value.cachedTrackCount - 1).coerceAtLeast(0)
    _scanState.value =
      _scanState.value.copy(
        cachedTrackCount = count,
        hasAudioPermission = hasMediaStoreAudioPermission(),
      )
  }

  override fun hasMediaStoreAudioPermission(): Boolean =
    mediaStoreRepository.hasMediaStoreAudioPermission()

  override suspend fun listPlaylists(): List<SavedPlaylistEntry> {
    ensureLoaded()
    return activeRepository().listPlaylists()
  }

  override suspend fun resolvePlaylistTracks(playlistId: String): List<AudioTrack> {
    ensureLoaded()
    return activeRepository().resolvePlaylistTracks(playlistId)
  }

  override suspend fun saveM3u8Playlist(
    fileName: String,
    content: String,
  ): Result<SavedPlaylistEntry> {
    ensureLoaded()
    return activeRepository().saveM3u8Playlist(fileName, content)
  }

  private suspend fun ensureLoaded() {
    loadMutex.withLock {
      ensureLoadedLocked()
    }
  }

  private suspend fun ensureLoadedLocked() {
    if (loaded) return
    val snapshot = preferencesStore.loadSnapshot()
    val backend = snapshot.storageBackend
    _storageBackend.value = backend
    emitCachedSnapshotImmediately(backend)
    refreshActiveBackend(backend)
    loaded = true
    scheduleBackgroundReconcile(backend)
  }

  private fun emitCachedSnapshotImmediately(backend: StorageBackend) {
    val cached = metadataCache?.load(backend)
    val hasPerm =
      backend != StorageBackend.MEDIA_STORE || mediaStoreRepository.hasMediaStoreAudioPermission()
    if (cached != null) {
      if (_linkedRoots.value.isEmpty() && cached.roots.isNotEmpty()) {
        _linkedRoots.value = cached.roots
      }
      _scanState.value =
        LibraryScanState(
          cachedTrackCount = cached.tracks.size,
          isRefreshing = true,
          hasAudioPermission = hasPerm,
        )
    } else {
      _scanState.value =
        LibraryScanState(
          cachedTrackCount = 0,
          isRefreshing = false,
          hasAudioPermission = hasPerm,
        )
    }
  }

  private suspend fun refreshActiveBackend(backend: StorageBackend) {
    when (backend) {
      StorageBackend.SAF -> {
        safRepository.refreshRoots()
        _linkedRoots.value = safRepository.linkedRoots.value
      }
      StorageBackend.MEDIA_STORE -> {
        mediaStoreRepository.refreshRoots()
        _linkedRoots.value = mediaStoreRepository.linkedRoots.value
      }
    }
    _scanState.value =
      _scanState.value.copy(hasAudioPermission = hasMediaStoreAudioPermission())
  }

  private fun scheduleBackgroundReconcile(backend: StorageBackend) {
    if (metadataCache == null) {
      _scanState.value =
        _scanState.value.copy(
          isRefreshing = false,
          hasAudioPermission = hasMediaStoreAudioPermission(),
        )
      return
    }
    reconcileJob?.cancel()
    reconcileJob =
      scope.launch(ioDispatcher) {
        try {
          reconcileNow(backend)
        } catch (ce: CancellationException) {
          throw ce
        } catch (_: Throwable) {
          _scanState.value = _scanState.value.copy(isRefreshing = false)
        }
      }
  }

  private suspend fun reconcileNow(backend: StorageBackend): Int {
    val hasPerm =
      backend != StorageBackend.MEDIA_STORE || mediaStoreRepository.hasMediaStoreAudioPermission()
    _scanState.value = _scanState.value.copy(isRefreshing = true, hasAudioPermission = hasPerm)

    val previous = metadataCache?.load(backend)
    val byUri = previous?.tracks?.associateBy { it.uriString }.orEmpty()

    refreshActiveBackend(backend)
    val freshTracks =
      when (backend) {
        StorageBackend.SAF -> safRepository.collectIndexTracks(byUri)
        StorageBackend.MEDIA_STORE -> mediaStoreRepository.collectIndexTracks(byUri)
      }
    val rootsSnapshot =
      when (backend) {
        StorageBackend.SAF -> safRepository.linkedRoots.value
        StorageBackend.MEDIA_STORE -> mediaStoreRepository.linkedRoots.value
      }

    metadataCache?.save(
      CachedLibrarySnapshot(
        backend = backend,
        roots = rootsSnapshot,
        tracks = freshTracks,
        updatedAtEpochMs = System.currentTimeMillis(),
      ),
    )

    _scanState.value =
      LibraryScanState(
        cachedTrackCount = freshTracks.size,
        isRefreshing = false,
        hasAudioPermission = hasMediaStoreAudioPermission(),
      )
    return freshTracks.size
  }

  private fun activeRepository(backend: StorageBackend = _storageBackend.value): MusicSourceRepository =
    when (backend) {
      StorageBackend.SAF -> safRepository
      StorageBackend.MEDIA_STORE -> mediaStoreRepository
    }
}
