package com.droidamp.player.ui.rack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidamp.player.domain.contract.AudioEngineController
import com.droidamp.player.domain.contract.MusicSourceRepository
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.LibraryTab
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.SheetMode
import com.droidamp.player.domain.model.VisualizerFrame
import com.droidamp.player.ui.sheet.PlaylistMicroMenu
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RackUiState(
  val isEqVisible: Boolean = true,
  val isSheetExpanded: Boolean = false,
  val sheetMode: SheetMode = SheetMode.PLAYLIST,
  val libraryTab: LibraryTab = LibraryTab.FOLDERS,
  val folderContents: FolderContents = FolderContents.EMPTY,
  val playlists: List<SavedPlaylistEntry> = emptyList(),
  val selectedPlaylistIndices: Set<Int> = emptySet(),
  val selectedLibraryTrackIds: Set<String> = emptySet(),
  val selectedLibraryPlaylistId: String? = null,
  val showJumpFilter: Boolean = false,
  val jumpFilterQuery: String = "",
  val activeMicroMenu: PlaylistMicroMenu = PlaylistMicroMenu.NONE,
  val statusBanner: String? = null,
)

class DroidAmpViewModel(
  private val audioEngine: AudioEngineController,
  private val musicRepository: MusicSourceRepository,
) : ViewModel() {

  val playbackState: StateFlow<PlaybackState> = audioEngine.playbackState
  val visualizerFrame: StateFlow<VisualizerFrame> = audioEngine.visualizerFrame
  val linkedRoots: StateFlow<List<FolderEntry>> = musicRepository.linkedRoots

  private val _uiState = MutableStateFlow(RackUiState())
  val uiState: StateFlow<RackUiState> = _uiState.asStateFlow()

  init {
    // ADR-007: Apply cold-launch internal volume reset (~80% = 204) ONLY if playback is stopped.
    audioEngine.applyColdLaunchVolumeResetIfStopped(PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME)
    refreshLibraryFromCurrentRoot()
  }

  private fun refreshLibraryFromCurrentRoot() {
    viewModelScope.launch {
      val contents = musicRepository.browseFolder(null)
      val lists = musicRepository.listPlaylists()
      _uiState.update {
        it.copy(
          folderContents = contents,
          playlists = lists,
          selectedLibraryTrackIds = emptySet(),
          selectedLibraryPlaylistId = null,
        )
      }
    }
  }

  // --- First-Run SAF Onboarding ---

  fun onSelectMusicFolder(treeUriString: String = FakeMusicSourceRepository.ROOT_URI) {
    viewModelScope.launch {
      musicRepository.linkSafRoot(treeUriString)
      val contents = musicRepository.browseFolder(null)
      val lists = musicRepository.listPlaylists()
      _uiState.update {
        it.copy(
          folderContents = contents,
          playlists = lists,
          sheetMode = SheetMode.LIBRARY,
          isSheetExpanded = true,
          libraryTab = LibraryTab.FOLDERS,
          statusBanner = "LINKED MUSIC FOLDER (SAF)",
        )
      }
    }
  }

  // --- Deck & Sheet Navigation ---

  fun toggleEqVisible() {
    _uiState.update { it.copy(isEqVisible = !it.isEqVisible) }
  }

  fun expandSheetRememberedMode() {
    _uiState.update { it.copy(isSheetExpanded = true, activeMicroMenu = PlaylistMicroMenu.NONE) }
  }

  fun collapseSheet() {
    _uiState.update { it.copy(isSheetExpanded = false, activeMicroMenu = PlaylistMicroMenu.NONE) }
  }

  fun toggleSheetExpanded() {
    _uiState.update {
      it.copy(isSheetExpanded = !it.isSheetExpanded, activeMicroMenu = PlaylistMicroMenu.NONE)
    }
  }

  /** ADR-008: Eject (`⏏`) always jumps straight to LIBRARY in the full-screen sheet. */
  fun onEjectPressed() {
    _uiState.update {
      it.copy(
        sheetMode = SheetMode.LIBRARY,
        isSheetExpanded = true,
        activeMicroMenu = PlaylistMicroMenu.NONE,
      )
    }
  }

  fun switchSheetMode(mode: SheetMode) {
    _uiState.update {
      it.copy(
        sheetMode = mode,
        activeMicroMenu = PlaylistMicroMenu.NONE,
        statusBanner = null,
      )
    }
  }

  // --- Transport & Mixer Delegation ---

  fun play() = audioEngine.play()
  fun pause() = audioEngine.pause()
  fun stop() = audioEngine.stop()
  fun next() = audioEngine.next()
  fun previous() = audioEngine.previous()
  fun seekTo(positionMs: Long) = audioEngine.seekTo(positionMs)
  fun setVolume(volume: Int) = audioEngine.setVolume(volume)
  fun setBalance(balance: Int) = audioEngine.setBalance(balance)
  fun toggleShuffle() = audioEngine.toggleShuffle()
  fun cycleRepeatMode() = audioEngine.cycleRepeatMode()
  fun toggleTimeDisplayMode() = audioEngine.toggleTimeDisplayMode()
  fun cycleVisualizerMode() = audioEngine.cycleVisualizerMode()

  // --- Equalizer ---

  fun toggleEqEnabled() = audioEngine.setEqEnabled(!playbackState.value.equalizer.enabled)
  fun toggleEqAuto() = audioEngine.setEqAutoEnabled(!playbackState.value.equalizer.autoEnabled)
  fun setPreampDb(db: Float) = audioEngine.setPreampDb(db)
  fun setEqBandDb(bandIndex: Int, db: Float) = audioEngine.setEqBandDb(bandIndex, db)
  fun applyEqPreset(preset: EqPreset) = audioEngine.applyEqPreset(preset)

  // --- Playlist Editor Actions ---

  fun onPlaylistTrackClick(index: Int) {
    _uiState.update { state ->
      val nextSel =
        if (index in state.selectedPlaylistIndices) {
          state.selectedPlaylistIndices - index
        } else {
          state.selectedPlaylistIndices + index
        }
      state.copy(selectedPlaylistIndices = nextSel)
    }
  }

  fun onPlaylistTrackDoubleClick(index: Int) {
    audioEngine.playTrackAt(index)
  }

  fun movePlaylistTrack(fromIndex: Int, toIndex: Int) {
    audioEngine.moveTrack(fromIndex, toIndex)
    _uiState.update { state ->
      val updatedSel =
        state.selectedPlaylistIndices.map { idx ->
          when (idx) {
            fromIndex -> toIndex
            toIndex -> fromIndex
            else -> idx
          }
        }.toSet()
      state.copy(selectedPlaylistIndices = updatedSel)
    }
  }

  fun openMicroMenu(menu: PlaylistMicroMenu) {
    _uiState.update { it.copy(activeMicroMenu = menu) }
  }

  fun dismissMicroMenu() {
    _uiState.update { it.copy(activeMicroMenu = PlaylistMicroMenu.NONE) }
  }

  fun removeSelectedPlaylistTracks() {
    val indices = _uiState.value.selectedPlaylistIndices
    if (indices.isEmpty()) return
    audioEngine.removeIndices(indices)
    _uiState.update { it.copy(selectedPlaylistIndices = emptySet()) }
  }

  fun cropSelectedPlaylistTracks() {
    val indices = _uiState.value.selectedPlaylistIndices
    if (indices.isEmpty()) return
    audioEngine.cropIndices(indices)
    _uiState.update { it.copy(selectedPlaylistIndices = emptySet()) }
  }

  fun clearPlaylist() {
    audioEngine.clearQueue()
    _uiState.update { it.copy(selectedPlaylistIndices = emptySet()) }
  }

  fun sortPlaylist(order: PlaylistSortOrder) {
    audioEngine.sortQueue(order)
    _uiState.update { it.copy(selectedPlaylistIndices = emptySet()) }
  }

  fun reversePlaylist() {
    audioEngine.reverseQueue()
    _uiState.update { it.copy(selectedPlaylistIndices = emptySet()) }
  }

  fun randomizePlaylist() {
    audioEngine.randomizeQueue()
    _uiState.update { it.copy(selectedPlaylistIndices = emptySet()) }
  }

  fun toggleJumpFilter() {
    _uiState.update {
      it.copy(
        showJumpFilter = !it.showJumpFilter,
        jumpFilterQuery = if (it.showJumpFilter) "" else it.jumpFilterQuery,
      )
    }
  }

  fun onJumpFilterQueryChange(query: String) {
    _uiState.update { it.copy(jumpFilterQuery = query) }
  }

  fun onSavePlaylistStub() {
    _uiState.update { it.copy(statusBanner = "M3U8 EXPORT ENGINE LANDS IN MILESTONE 4") }
  }

  fun onLoadPlaylistShortcut() {
    _uiState.update {
      it.copy(
        sheetMode = SheetMode.LIBRARY,
        libraryTab = LibraryTab.PLAYLISTS,
        activeMicroMenu = PlaylistMicroMenu.NONE,
      )
    }
  }

  // --- Library Browser Actions (ADR-009 Select-First Track Safety) ---

  fun selectLibraryTab(tab: LibraryTab) {
    _uiState.update {
      it.copy(
        libraryTab = tab,
        selectedLibraryTrackIds = emptySet(),
        selectedLibraryPlaylistId = null,
      )
    }
  }

  fun openFolder(folder: FolderEntry) {
    viewModelScope.launch {
      val contents = musicRepository.browseFolder(folder.uriString)
      _uiState.update {
        it.copy(folderContents = contents, selectedLibraryTrackIds = emptySet())
      }
    }
  }

  fun navigateFolderUp() {
    val parentUri = _uiState.value.folderContents.parentFolderUri ?: return
    viewModelScope.launch {
      val contents = musicRepository.browseFolder(parentUri)
      _uiState.update {
        it.copy(folderContents = contents, selectedLibraryTrackIds = emptySet())
      }
    }
  }

  fun playFolderAsPlaylist(folder: FolderEntry) {
    viewModelScope.launch {
      val tracks = musicRepository.resolveFolderTracks(folder.uriString, recursive = true)
      if (tracks.isNotEmpty()) {
        audioEngine.replaceQueue(tracks, startIndex = 0, autoPlay = true)
        _uiState.update {
          it.copy(
            sheetMode = SheetMode.PLAYLIST,
            selectedPlaylistIndices = emptySet(),
            statusBanner = null,
          )
        }
      }
    }
  }

  fun enqueueFolder(folder: FolderEntry) {
    viewModelScope.launch {
      val tracks = musicRepository.resolveFolderTracks(folder.uriString, recursive = true)
      if (tracks.isNotEmpty()) {
        audioEngine.enqueueTracks(tracks)
      }
    }
  }

  /** ADR-009: Single-tap on a track in LIBRARY selects/highlights it (never destructive). */
  fun toggleLibraryTrackSelection(track: AudioTrack) {
    _uiState.update { state ->
      val next =
        if (track.id in state.selectedLibraryTrackIds) {
          state.selectedLibraryTrackIds - track.id
        } else {
          state.selectedLibraryTrackIds + track.id
        }
      state.copy(selectedLibraryTrackIds = next)
    }
  }

  /** ADR-009: Double-tap or inline `[▶]` replace-plays track (strict Winamp behavior). */
  fun playSingleLibraryTrack(track: AudioTrack) {
    audioEngine.replaceQueue(listOf(track), startIndex = 0, autoPlay = true)
    _uiState.update {
      it.copy(
        selectedLibraryTrackIds = emptySet(),
        sheetMode = SheetMode.PLAYLIST,
        selectedPlaylistIndices = emptySet(),
      )
    }
  }

  fun enqueueSingleLibraryTrack(track: AudioTrack) {
    audioEngine.enqueueTracks(listOf(track))
  }

  fun selectLibraryPlaylist(playlist: SavedPlaylistEntry) {
    _uiState.update {
      it.copy(
        selectedLibraryPlaylistId =
          if (it.selectedLibraryPlaylistId == playlist.id) null else playlist.id,
      )
    }
  }

  fun playSavedPlaylist(playlist: SavedPlaylistEntry) {
    viewModelScope.launch {
      val tracks = musicRepository.resolvePlaylistTracks(playlist.id)
      if (tracks.isNotEmpty()) {
        audioEngine.replaceQueue(tracks, startIndex = 0, autoPlay = true)
        _uiState.update {
          it.copy(sheetMode = SheetMode.PLAYLIST, selectedPlaylistIndices = emptySet())
        }
      }
    }
  }

  fun enqueueSavedPlaylist(playlist: SavedPlaylistEntry) {
    viewModelScope.launch {
      val tracks = musicRepository.resolvePlaylistTracks(playlist.id)
      if (tracks.isNotEmpty()) {
        audioEngine.enqueueTracks(tracks)
      }
    }
  }

  /** Bottom `[▶ PLAY]` button in LIBRARY acts on selected items or the current folder. */
  fun playPrimaryLibrarySelection() {
    val state = _uiState.value
    when (state.libraryTab) {
      LibraryTab.FOLDERS -> {
        val selectedTracks = state.folderContents.tracks.filter { it.id in state.selectedLibraryTrackIds }
        if (selectedTracks.isNotEmpty()) {
          audioEngine.replaceQueue(selectedTracks, startIndex = 0, autoPlay = true)
          _uiState.update {
            it.copy(selectedLibraryTrackIds = emptySet(), sheetMode = SheetMode.PLAYLIST)
          }
        } else {
          val folderUri = state.folderContents.currentFolderUri ?: return
          viewModelScope.launch {
            val tracks = musicRepository.resolveFolderTracks(folderUri, recursive = true)
            if (tracks.isNotEmpty()) {
              audioEngine.replaceQueue(tracks, startIndex = 0, autoPlay = true)
              _uiState.update { it.copy(sheetMode = SheetMode.PLAYLIST) }
            }
          }
        }
      }
      LibraryTab.PLAYLISTS -> {
        val target =
          state.playlists.firstOrNull { it.id == state.selectedLibraryPlaylistId }
            ?: state.playlists.firstOrNull()
            ?: return
        playSavedPlaylist(target)
      }
    }
  }

  /** Bottom `[+ QUEUE]` button in LIBRARY enqueues selected items or the current folder. */
  fun enqueuePrimaryLibrarySelection() {
    val state = _uiState.value
    when (state.libraryTab) {
      LibraryTab.FOLDERS -> {
        val selectedTracks = state.folderContents.tracks.filter { it.id in state.selectedLibraryTrackIds }
        if (selectedTracks.isNotEmpty()) {
          audioEngine.enqueueTracks(selectedTracks)
          _uiState.update { it.copy(selectedLibraryTrackIds = emptySet()) }
        } else {
          val folderUri = state.folderContents.currentFolderUri ?: return
          viewModelScope.launch {
            val tracks = musicRepository.resolveFolderTracks(folderUri, recursive = true)
            if (tracks.isNotEmpty()) audioEngine.enqueueTracks(tracks)
          }
        }
      }
      LibraryTab.PLAYLISTS -> {
        val target =
          state.playlists.firstOrNull { it.id == state.selectedLibraryPlaylistId }
            ?: state.playlists.firstOrNull()
            ?: return
        enqueueSavedPlaylist(target)
      }
    }
  }

}

/**
 * Convenience ViewModel wiring [FakeAudioEngineController] to `viewModelScope` for Milestone 1 preview.
 */
class PreviewDroidAmpViewModel(
  private val previewRepo: FakeMusicSourceRepository = FakeMusicSourceRepository(startLinked = false),
) : ViewModel() {
  private val previewEngine = FakeAudioEngineController(viewModelScope, enableSimulationTicker = true)
  val delegate = DroidAmpViewModel(previewEngine, previewRepo)
}
