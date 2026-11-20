package com.droidamp.player.storage

import com.droidamp.player.domain.contract.MusicSourceRepository
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.StorageBackend
import kotlinx.coroutines.CoroutineScope
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
 * [storageBackend] and [linkedRoots] synchronized with [StoragePreferencesStore].
 */
class SwitchingMusicSourceRepository(
  private val safRepository: SafMusicSourceRepository,
  private val mediaStoreRepository: MediaStoreMusicSourceRepository,
  private val preferencesStore: StoragePreferencesStore,
  scope: CoroutineScope,
) : MusicSourceRepository {

  private val loadMutex = Mutex()
  private var loaded = false

  private val _storageBackend = MutableStateFlow(StorageBackend.SAF)
  override val storageBackend: StateFlow<StorageBackend> = _storageBackend.asStateFlow()

  private val _linkedRoots = MutableStateFlow<List<FolderEntry>>(emptyList())
  override val linkedRoots: StateFlow<List<FolderEntry>> = _linkedRoots.asStateFlow()

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
      refreshActiveBackend(backend)
    }
  }

  override suspend fun linkSafRoot(treeUriString: String) {
    loadMutex.withLock {
      ensureLoadedLocked()
      safRepository.linkSafRoot(treeUriString)
      if (_storageBackend.value == StorageBackend.SAF) {
        _linkedRoots.value = safRepository.linkedRoots.value
      }
    }
  }

  override suspend fun unlinkAllRoots() {
    loadMutex.withLock {
      ensureLoadedLocked()
      safRepository.unlinkAllRoots()
      if (_storageBackend.value == StorageBackend.SAF) {
        _linkedRoots.value = safRepository.linkedRoots.value
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
    refreshActiveBackend(backend)
    loaded = true
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
  }

  private fun activeRepository(backend: StorageBackend = _storageBackend.value): MusicSourceRepository =
    when (backend) {
      StorageBackend.SAF -> safRepository
      StorageBackend.MEDIA_STORE -> mediaStoreRepository
    }
}
