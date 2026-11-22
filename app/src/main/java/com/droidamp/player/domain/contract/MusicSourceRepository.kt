package com.droidamp.player.domain.contract

import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.LibraryScanState
import com.droidamp.player.domain.model.ManagedSafFolder
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.StorageBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val DefaultManagedSafFoldersFlow: StateFlow<List<ManagedSafFolder>> =
  MutableStateFlow(emptyList<ManagedSafFolder>()).asStateFlow()
private val DefaultLibraryScanStateFlow: StateFlow<LibraryScanState> =
  MutableStateFlow(LibraryScanState()).asStateFlow()

interface MusicSourceRepository {
  val storageBackend: StateFlow<StorageBackend>
  val linkedRoots: StateFlow<List<FolderEntry>>
  val managedSafFolders: StateFlow<List<ManagedSafFolder>>
    get() = DefaultManagedSafFoldersFlow
  val scanState: StateFlow<LibraryScanState>
    get() = DefaultLibraryScanStateFlow

  suspend fun setStorageBackend(backend: StorageBackend)
  suspend fun linkSafRoot(treeUriString: String)
  suspend fun removeSafRoot(treeUriString: String) {}
  suspend fun replaceSafRoot(oldUriString: String, newUriString: String): Boolean = false
  suspend fun unlinkAllRoots()
  suspend fun browseFolder(folderUriString: String?): FolderContents
  suspend fun resolveFolderTracks(
    folderUriString: String,
    recursive: Boolean = false,
  ): List<AudioTrack>
  suspend fun rescanLibrary(): Int = 0
  suspend fun pruneMissingTrack(uriString: String) {}
  fun hasMediaStoreAudioPermission(): Boolean = true
  suspend fun listPlaylists(): List<SavedPlaylistEntry>
  suspend fun resolvePlaylistTracks(playlistId: String): List<AudioTrack>

  suspend fun saveM3u8Playlist(
    fileName: String,
    content: String,
  ): Result<SavedPlaylistEntry> =
    Result.failure(UnsupportedOperationException("saveM3u8Playlist not supported"))
}
