package com.droidamp.player.ui.rack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidamp.player.domain.contract.AudioEngineController
import com.droidamp.player.domain.contract.AutoEqPresetResolver
import com.droidamp.player.domain.contract.MusicSourceRepository
import com.droidamp.player.domain.contract.PlaylistFileCodec
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.LibraryTab
import com.droidamp.player.domain.model.LibraryViewLevel
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaylistExportFormat
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.RestingLayoutVariant
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.SheetMode
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.domain.model.VisualizerFrame
import com.droidamp.player.domain.model.WinampEqSpec
import com.droidamp.player.interop.AutoEqMatcher
import com.droidamp.player.interop.WinampPlaylistCodec
import com.droidamp.player.interop.WinampPresetLibrary
import com.droidamp.player.ui.sheet.PlaylistMicroMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RackUiState(
  val isEqVisible: Boolean = true,
  val isSheetExpanded: Boolean = false,
  val sheetMode: SheetMode = SheetMode.PLAYLIST,
  val libraryTab: LibraryTab = LibraryTab.FOLDERS,
  val libraryViewLevel: LibraryViewLevel = LibraryViewLevel.ROOT,
  val restingLayoutVariant: RestingLayoutVariant = RestingLayoutVariant.OPTION_A_SLIM_LIP,
  val folderContents: FolderContents = FolderContents.EMPTY,
  val playlists: List<SavedPlaylistEntry> = emptyList(),
  val selectedPlaylistIndices: Set<Int> = emptySet(),
  val selectedLibraryTrackIds: Set<String> = emptySet(),
  val selectedLibraryPlaylistId: String? = null,
  val showJumpFilter: Boolean = false,
  val jumpFilterQuery: String = "",
  val activeMicroMenu: PlaylistMicroMenu = PlaylistMicroMenu.NONE,
  val statusBanner: String? = null,
  val libraryFeedbackBanner: String? = null,
  val queueFeedbackPulseCount: Int = 0,
  val autoEqFeedbackMessage: String? = null,
  val showClearQueueConfirm: Boolean = false,
  val showSavePlaylistDialog: Boolean = false,
  val availableEqPresets: List<EqPreset> = ClassicWinampPresets.ALL,
)

class DroidAmpViewModel(
  private val audioEngine: AudioEngineController,
  private val musicRepository: MusicSourceRepository,
  private val autoEqResolver: AutoEqPresetResolver = AutoEqMatcher(),
  private val playlistCodec: PlaylistFileCodec = WinampPlaylistCodec(),
  private val autoEqCatalogProvider: AutoEqCatalogProvider = EmptyAutoEqCatalogProvider,
) : ViewModel() {

  private data class AutoEqTriggerKey(
    val track: AudioTrack?,
    val autoEnabled: Boolean,
    val rootUris: List<String>,
  )

  val playbackState: StateFlow<PlaybackState> = audioEngine.playbackState
  val visualizerFrame: StateFlow<VisualizerFrame> = audioEngine.visualizerFrame
  val linkedRoots: StateFlow<List<FolderEntry>> = musicRepository.linkedRoots
  val storageBackend: StateFlow<StorageBackend> = musicRepository.storageBackend

  private val _uiState = MutableStateFlow(RackUiState())
  val uiState: StateFlow<RackUiState> = _uiState.asStateFlow()

  private var libraryFeedbackJob: Job? = null
  private var autoEqFeedbackJob: Job? = null

  init {
    // ADR-007: Apply cold-launch internal volume reset (~80% = 204) ONLY if playback is stopped.
    audioEngine.applyColdLaunchVolumeResetIfStopped(PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME)
    refreshLibraryFromCurrentRoot()
    observeLinkedRootHydration()
    observeAutoEqMatching()
  }

  private fun observeLinkedRootHydration() {
    viewModelScope.launch {
      musicRepository.linkedRoots
        .map { roots -> roots.map { it.uriString } }
        .distinctUntilChanged()
        .drop(1)
        .collect {
          refreshLibraryFromCurrentRoot()
        }
    }
  }

  private fun observeAutoEqMatching() {
    viewModelScope.launch {
      combine(
        playbackState
          .map { it.currentTrack to it.equalizer.autoEnabled }
          .distinctUntilChanged { old, new ->
            old.first?.id == new.first?.id &&
              old.first?.uriString == new.first?.uriString &&
              old.second == new.second
          },
        musicRepository.linkedRoots
          .map { roots -> roots.map { it.uriString } }
          .distinctUntilChanged(),
      ) { (track, autoEnabled), rootUris ->
        AutoEqTriggerKey(track = track, autoEnabled = autoEnabled, rootUris = rootUris)
      }.collectLatest { trigger ->
        val catalog =
          try {
            autoEqCatalogProvider.loadCatalog(trigger.rootUris, trigger.track)
          } catch (ce: CancellationException) {
            throw ce
          } catch (_: Throwable) {
            AutoEqCatalog()
          }
        val mergedPresets =
          WinampPresetLibrary.mergePresets(catalog.extraPresets, ClassicWinampPresets.ALL)
        _uiState.update { it.copy(availableEqPresets = mergedPresets) }

        val track = trigger.track ?: return@collectLatest
        if (!trigger.autoEnabled) return@collectLatest

        val latest = playbackState.value
        if (!latest.equalizer.autoEnabled ||
          latest.currentTrack?.id != track.id ||
          latest.currentTrack?.uriString != track.uriString
        ) {
          return@collectLatest
        }

        val matched =
          autoEqResolver.resolveForTrack(track, catalog.rules, mergedPresets) ?: return@collectLatest
        val currentEq = latest.equalizer
        val clampedPreamp = WinampEqSpec.clampDb(matched.preampDb)
        val clampedBands = matched.bandsDb.map(WinampEqSpec::clampDb)
        val alreadyApplied =
          currentEq.activePresetName.equals(matched.name, ignoreCase = true) &&
            currentEq.preampDb == clampedPreamp &&
            currentEq.bandsDb == clampedBands
        if (!alreadyApplied) {
          audioEngine.applyEqPreset(matched)
          showAutoEqFeedback("AUTO: ${matched.name.uppercase()}")
        }
      }
    }
  }

  fun refreshLibrary() {
    refreshLibraryFromCurrentRoot()
  }

  private fun refreshLibraryFromCurrentRoot() {
    viewModelScope.launch {
      val contents = runCatching { musicRepository.browseFolder(null) }.getOrDefault(FolderContents.EMPTY)
      val lists = runCatching { musicRepository.listPlaylists() }.getOrDefault(emptyList())
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
    val targetUri = treeUriString.trim()
    viewModelScope.launch {
      val linked =
        runCatching {
          musicRepository.linkSafRoot(treeUriString)
          if (targetUri.isEmpty() || musicRepository.linkedRoots.value.none { it.uriString == targetUri }) {
            false
          } else {
            val contents = musicRepository.browseFolder(null)
            val lists = musicRepository.listPlaylists()
            _uiState.update {
              it.copy(
                folderContents = contents,
                playlists = lists,
                sheetMode = SheetMode.LIBRARY,
                isSheetExpanded = true,
                libraryTab = LibraryTab.FOLDERS,
                libraryViewLevel = LibraryViewLevel.FOLDERS,
                selectedLibraryTrackIds = emptySet(),
                selectedLibraryPlaylistId = null,
                statusBanner = "LINKED MUSIC FOLDER (SAF)",
              )
            }
            true
          }
        }.getOrDefault(false)

      if (!linked) {
        _uiState.update {
          it.copy(
            folderContents =
              if (musicRepository.linkedRoots.value.isEmpty()) {
                FolderContents.EMPTY
              } else {
                it.folderContents
              },
            statusBanner = "UNABLE TO LINK MUSIC FOLDER — SELECT A READABLE FOLDER",
          )
        }
      }
    }
  }

  fun onStoragePermissionResult(granted: Boolean) {
    if (!granted) {
      _uiState.update {
        it.copy(statusBanner = "AUDIO STORAGE PERMISSION DENIED")
      }
      return
    }
    viewModelScope.launch {
      val contents = runCatching { musicRepository.browseFolder(null) }.getOrDefault(FolderContents.EMPTY)
      val lists = runCatching { musicRepository.listPlaylists() }.getOrDefault(emptyList())
      _uiState.update {
        it.copy(
          folderContents = contents,
          playlists = lists,
          sheetMode = SheetMode.LIBRARY,
          isSheetExpanded = true,
          libraryTab = LibraryTab.FOLDERS,
          selectedLibraryTrackIds = emptySet(),
          selectedLibraryPlaylistId = null,
          statusBanner = "MEDIASTORE LIBRARY READY",
        )
      }
    }
  }

  // --- Deck & Sheet Navigation ---

  fun toggleEqVisible() {
    _uiState.update { it.copy(isEqVisible = !it.isEqVisible) }
  }

  fun toggleRestingLayoutVariant() {
    _uiState.update {
      val next =
        if (it.restingLayoutVariant == RestingLayoutVariant.OPTION_A_SLIM_LIP) {
          RestingLayoutVariant.OPTION_B_TAB_PEEK
        } else {
          RestingLayoutVariant.OPTION_A_SLIM_LIP
        }
      it.copy(restingLayoutVariant = next)
    }
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

  /** ADR-019: Deck `QUEUE` toggle opens drawer directly to `QUEUE` (or collapses if already open on Queue). */
  fun onQueueButtonPressed() {
    _uiState.update {
      if (it.isSheetExpanded && it.sheetMode == SheetMode.PLAYLIST) {
        it.copy(isSheetExpanded = false, activeMicroMenu = PlaylistMicroMenu.NONE)
      } else {
        it.copy(
          sheetMode = SheetMode.PLAYLIST,
          isSheetExpanded = true,
          activeMicroMenu = PlaylistMicroMenu.NONE,
        )
      }
    }
  }

  /** ADR-008 / ADR-019: Eject (`⏏`) always opens drawer directly to `LIBRARY` (preserving drill-down location). */
  fun onEjectPressed() {
    _uiState.update {
      it.copy(
        sheetMode = SheetMode.LIBRARY,
        isSheetExpanded = true,
        activeMicroMenu = PlaylistMicroMenu.NONE,
      )
    }
  }

  /**
   * ADR-020: Android Back inside `LIBRARY` steps up one level (`DOORS` -> `FOLDERS` -> `ROOT`),
   * then collapses the drawer once at `LIBRARY` root (or anywhere in `QUEUE`).
   */
  fun onBackPressedInExpandedSheet() {
    val state = _uiState.value
    if (!state.isSheetExpanded) return
    if (state.sheetMode == SheetMode.LIBRARY) {
      when (state.libraryViewLevel) {
        LibraryViewLevel.FOLDERS -> {
          if (state.folderContents.canNavigateUp) {
            navigateFolderUp()
          } else {
            openLibraryRoot()
          }
        }
        LibraryViewLevel.PLAYLISTS -> openLibraryRoot()
        LibraryViewLevel.ROOT -> collapseSheet()
      }
    } else {
      collapseSheet()
    }
  }

  fun openLibraryRoot() {
    _uiState.update {
      it.copy(
        libraryViewLevel = LibraryViewLevel.ROOT,
        selectedLibraryTrackIds = emptySet(),
        selectedLibraryPlaylistId = null,
      )
    }
  }

  fun openLibraryFolders() {
    _uiState.update {
      it.copy(
        libraryTab = LibraryTab.FOLDERS,
        libraryViewLevel = LibraryViewLevel.FOLDERS,
        selectedLibraryTrackIds = emptySet(),
        selectedLibraryPlaylistId = null,
      )
    }
  }

  fun openLibraryPlaylists() {
    _uiState.update {
      it.copy(
        libraryTab = LibraryTab.PLAYLISTS,
        libraryViewLevel = LibraryViewLevel.PLAYLISTS,
        selectedLibraryTrackIds = emptySet(),
        selectedLibraryPlaylistId = null,
      )
    }
  }

  fun clearLibrarySelection() {
    _uiState.update {
      it.copy(
        selectedLibraryTrackIds = emptySet(),
        selectedLibraryPlaylistId = null,
      )
    }
  }

  fun requestClearQueue() {
    _uiState.update {
      it.copy(
        activeMicroMenu = PlaylistMicroMenu.NONE,
        showClearQueueConfirm = true,
      )
    }
  }

  fun dismissClearQueueConfirm() {
    _uiState.update { it.copy(showClearQueueConfirm = false) }
  }

  fun confirmClearQueue() {
    clearPlaylist()
    _uiState.update { it.copy(showClearQueueConfirm = false) }
  }

  fun openSavePlaylistDialog() {
    if (playbackState.value.queue.isEmpty()) {
      _uiState.update {
        it.copy(
          activeMicroMenu = PlaylistMicroMenu.NONE,
          statusBanner = "QUEUE EMPTY — NOTHING TO SAVE",
        )
      }
      return
    }
    _uiState.update {
      it.copy(
        activeMicroMenu = PlaylistMicroMenu.NONE,
        showSavePlaylistDialog = true,
      )
    }
  }

  fun dismissSavePlaylistDialog() {
    _uiState.update { it.copy(showSavePlaylistDialog = false) }
  }

  fun saveQueueAsPlaylist(
    baseName: String,
    format: PlaylistExportFormat = PlaylistExportFormat.M3U8,
    selectedOnly: Boolean = false,
    onNeedDocumentPickerFallback: ((fileName: String, content: String) -> Unit)? = null,
  ) {
    val queue = playbackState.value.queue
    val selected = _uiState.value.selectedPlaylistIndices
    val tracksToSave =
      if (selectedOnly && selected.isNotEmpty()) {
        queue.filterIndexed { idx, _ -> idx in selected }
      } else {
        queue
      }
    _uiState.update { it.copy(showSavePlaylistDialog = false, activeMicroMenu = PlaylistMicroMenu.NONE) }
    if (tracksToSave.isEmpty()) return

    val cleanBase =
      baseName
        .trim()
        .removeSuffix(".m3u8")
        .removeSuffix(".m3u")
        .removeSuffix(".pls")
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifEmpty { "droidamp_playlist" }
    val resolvedFileName = "$cleanBase.${format.extension}"

    viewModelScope.launch {
      val content =
        when (format) {
          PlaylistExportFormat.M3U8 -> playlistCodec.encodeM3u8(tracksToSave)
          PlaylistExportFormat.PLS -> playlistCodec.encodePls(tracksToSave)
        }
      val result =
        runCatching {
          musicRepository.saveM3u8Playlist(resolvedFileName, content)
        }.getOrElse { Result.failure(it) }

      result
        .onSuccess {
          val refreshedPlaylists =
            runCatching { musicRepository.listPlaylists() }.getOrDefault(_uiState.value.playlists)
          _uiState.update {
            it.copy(
              playlists = refreshedPlaylists,
              statusBanner = "SAVED ${resolvedFileName.uppercase()} (${tracksToSave.size} TRACKS)",
            )
          }
        }
        .onFailure { error ->
          if (error is UnsupportedOperationException || onNeedDocumentPickerFallback == null) {
            _uiState.update {
              it.copy(
                statusBanner = "SAVE FAILED: ${error.message?.take(40) ?: "STORAGE ERROR"}",
              )
            }
          } else {
            onNeedDocumentPickerFallback(resolvedFileName, content)
          }
        }
    }
  }

  private fun showAutoEqFeedback(message: String) {
    autoEqFeedbackJob?.cancel()
    _uiState.update { it.copy(autoEqFeedbackMessage = message) }
    autoEqFeedbackJob =
      viewModelScope.launch {
        delay(2_600L)
        _uiState.update { it.copy(autoEqFeedbackMessage = null) }
      }
  }

  private fun emitEnqueueFeedback(addedCount: Int) {
    if (addedCount <= 0) return
    val total = playbackState.value.queue.size
    libraryFeedbackJob?.cancel()
    _uiState.update {
      it.copy(
        queueFeedbackPulseCount = it.queueFeedbackPulseCount + 1,
        libraryFeedbackBanner = "+$addedCount ADDED TO QUEUE ($total TOTAL)",
      )
    }
    libraryFeedbackJob =
      viewModelScope.launch {
        delay(2_200L)
        _uiState.update { it.copy(libraryFeedbackBanner = null) }
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

  fun toggleEqAuto() {
    val nextEnabled = !playbackState.value.equalizer.autoEnabled
    audioEngine.setEqAutoEnabled(nextEnabled)
    showAutoEqFeedback(
      if (nextEnabled) {
        "AUTO EQ: ON (GENRE / .EQF)"
      } else {
        "AUTO EQ: OFF"
      },
    )
  }
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
    val queueSize = playbackState.value.queue.size
    if (fromIndex !in 0 until queueSize || toIndex !in 0 until queueSize || fromIndex == toIndex) {
      return
    }
    audioEngine.moveTrack(fromIndex, toIndex)
    _uiState.update { state ->
      val updatedSel =
        state.selectedPlaylistIndices.map { idx ->
          when {
            idx == fromIndex -> toIndex
            fromIndex < toIndex && idx in (fromIndex + 1)..toIndex -> idx - 1
            fromIndex > toIndex && idx in toIndex until fromIndex -> idx + 1
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

  fun savePlaylistAsM3u8(
    fileName: String = DEFAULT_SAVED_PLAYLIST_NAME,
    onNeedDocumentPickerFallback: ((fileName: String, content: String) -> Unit)? = null,
  ) {
    val queue = playbackState.value.queue
    if (queue.isEmpty()) {
      _uiState.update {
        it.copy(
          activeMicroMenu = PlaylistMicroMenu.NONE,
          statusBanner = "PLAYLIST EMPTY — NOTHING TO SAVE",
        )
      }
      return
    }

    viewModelScope.launch {
      val content = playlistCodec.encodeM3u8(queue)
      val result =
        runCatching {
          musicRepository.saveM3u8Playlist(fileName, content)
        }.getOrElse { Result.failure(it) }

      result
        .onSuccess { entry ->
          val refreshedPlaylists =
            runCatching { musicRepository.listPlaylists() }.getOrDefault(_uiState.value.playlists)
          val upperName =
            if (entry.name.endsWith(".m3u8", ignoreCase = true)) {
              entry.name.uppercase()
            } else {
              "${entry.name.uppercase()}.M3U8"
            }
          _uiState.update {
            it.copy(
              playlists = refreshedPlaylists,
              activeMicroMenu = PlaylistMicroMenu.NONE,
              statusBanner = "SAVED $upperName (${queue.size} TRACKS)",
            )
          }
        }
        .onFailure { error ->
          if (error is UnsupportedOperationException || onNeedDocumentPickerFallback == null) {
            val banner =
              if (error is UnsupportedOperationException) {
                "SAVE .M3U8 UNAVAILABLE IN PREVIEW"
              } else {
                "SAVE .M3U8 FAILED: ${error.message?.take(40) ?: "STORAGE ERROR"}"
              }
            _uiState.update {
              it.copy(
                activeMicroMenu = PlaylistMicroMenu.NONE,
                statusBanner = banner,
              )
            }
          } else {
            _uiState.update {
              it.copy(
                activeMicroMenu = PlaylistMicroMenu.NONE,
                statusBanner = "CHOOSE EXPORT LOCATION FOR $fileName",
              )
            }
            onNeedDocumentPickerFallback(fileName, content)
          }
        }
    }
  }

  fun onM3u8FallbackExportResult(
    success: Boolean,
    displayLabel: String,
  ) {
    _uiState.update {
      it.copy(
        statusBanner =
          if (success) {
            "EXPORTED ${displayLabel.uppercase()}"
          } else {
            "M3U8 EXPORT CANCELLED OR FAILED"
          },
      )
    }
  }

  fun onLoadPlaylistShortcut() {
    _uiState.update {
      it.copy(
        sheetMode = SheetMode.LIBRARY,
        libraryTab = LibraryTab.PLAYLISTS,
        libraryViewLevel = LibraryViewLevel.PLAYLISTS,
        activeMicroMenu = PlaylistMicroMenu.NONE,
      )
    }
  }

  // --- Library Browser Actions (ADR-009 / ADR-020 Select-First Track Safety) ---

  fun selectLibraryTab(tab: LibraryTab) {
    _uiState.update {
      it.copy(
        libraryTab = tab,
        libraryViewLevel =
          if (tab == LibraryTab.FOLDERS) LibraryViewLevel.FOLDERS else LibraryViewLevel.PLAYLISTS,
        selectedLibraryTrackIds = emptySet(),
        selectedLibraryPlaylistId = null,
      )
    }
  }

  fun playCurrentFolder() {
    val folderUri = _uiState.value.folderContents.currentFolderUri ?: return
    viewModelScope.launch {
      val tracks = musicRepository.resolveFolderTracks(folderUri, recursive = true)
      if (tracks.isNotEmpty()) {
        audioEngine.replaceQueue(tracks, startIndex = 0, autoPlay = true)
        _uiState.update {
          it.copy(
            sheetMode = SheetMode.PLAYLIST,
            selectedPlaylistIndices = emptySet(),
            selectedLibraryTrackIds = emptySet(),
            statusBanner = null,
          )
        }
      }
    }
  }

  fun enqueueCurrentFolder() {
    val folderUri = _uiState.value.folderContents.currentFolderUri ?: return
    viewModelScope.launch {
      val tracks = musicRepository.resolveFolderTracks(folderUri, recursive = true)
      if (tracks.isNotEmpty()) {
        audioEngine.enqueueTracks(tracks)
        emitEnqueueFeedback(tracks.size)
      }
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
        emitEnqueueFeedback(tracks.size)
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
    emitEnqueueFeedback(1)
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
        emitEnqueueFeedback(tracks.size)
      }
    }
  }

  /** Bottom contextual `[▶ PLAY]` button in LIBRARY acts on selected items or current folder. */
  fun playPrimaryLibrarySelection() {
    val state = _uiState.value
    when (state.libraryTab) {
      LibraryTab.FOLDERS -> {
        val selectedTracks = state.folderContents.tracks.filter { it.id in state.selectedLibraryTrackIds }
        if (selectedTracks.isNotEmpty()) {
          audioEngine.replaceQueue(selectedTracks, startIndex = 0, autoPlay = true)
          _uiState.update {
            it.copy(
              selectedLibraryTrackIds = emptySet(),
              selectedPlaylistIndices = emptySet(),
              sheetMode = SheetMode.PLAYLIST,
            )
          }
        } else {
          playCurrentFolder()
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

  /** Bottom contextual `[+ QUEUE]` button in LIBRARY enqueues selected items or current folder. */
  fun enqueuePrimaryLibrarySelection() {
    val state = _uiState.value
    when (state.libraryTab) {
      LibraryTab.FOLDERS -> {
        val selectedTracks = state.folderContents.tracks.filter { it.id in state.selectedLibraryTrackIds }
        if (selectedTracks.isNotEmpty()) {
          audioEngine.enqueueTracks(selectedTracks)
          _uiState.update { it.copy(selectedLibraryTrackIds = emptySet()) }
          emitEnqueueFeedback(selectedTracks.size)
        } else {
          enqueueCurrentFolder()
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

  companion object {
    const val DEFAULT_SAVED_PLAYLIST_NAME = "droidamp_playlist.m3u8"
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
