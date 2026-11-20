package com.droidamp.player.domain.contract

import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.StorageBackend
import kotlinx.coroutines.flow.StateFlow

interface MusicSourceRepository {
  val storageBackend: StateFlow<StorageBackend>
  val linkedRoots: StateFlow<List<FolderEntry>>

  suspend fun setStorageBackend(backend: StorageBackend)
  suspend fun linkSafRoot(treeUriString: String)
  suspend fun unlinkAllRoots()
  suspend fun browseFolder(folderUriString: String?): FolderContents
  suspend fun resolveFolderTracks(
    folderUriString: String,
    recursive: Boolean = false,
  ): List<AudioTrack>
  suspend fun listPlaylists(): List<SavedPlaylistEntry>
  suspend fun resolvePlaylistTracks(playlistId: String): List<AudioTrack>
}
