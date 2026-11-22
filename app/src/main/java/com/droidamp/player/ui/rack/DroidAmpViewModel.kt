package com.droidamp.player.ui.rack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidamp.player.domain.contract.AudioEngineController
import com.droidamp.player.domain.contract.AutoEqPresetResolver
import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.contract.EqfCodec
import com.droidamp.player.domain.contract.MusicSourceRepository
import com.droidamp.player.domain.contract.PlaybackRecoveryNotice
import com.droidamp.player.domain.contract.PlaylistFileCodec
import com.droidamp.player.domain.contract.Q2Codec
import com.droidamp.player.domain.contract.WinampIniCodec
import com.droidamp.player.domain.contract.WinampIniSnapshot
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.LibraryScanState
import com.droidamp.player.domain.model.LibraryTab
import com.droidamp.player.domain.model.LibraryViewLevel
import com.droidamp.player.domain.model.ManagedSafFolder
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaylistExportFormat
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.RepeatMode
import com.droidamp.player.domain.model.ReplayGainMode
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.SheetMode
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.domain.model.TimeDisplayMode
import com.droidamp.player.domain.model.VisualizerFrame
import com.droidamp.player.domain.model.WinampEqSpec
import com.droidamp.player.interop.AutoEqMatcher
import com.droidamp.player.interop.BinaryEqfCodec
import com.droidamp.player.interop.DetectedWinampFormat
import com.droidamp.player.interop.DroidAmpFileSniffer
import com.droidamp.player.interop.IniAnalysisResult
import com.droidamp.player.interop.PresetConflictType
import com.droidamp.player.interop.Q1AnalysisResult
import com.droidamp.player.interop.Q2AnalysisResult
import com.droidamp.player.interop.UserEqPresetStore
import com.droidamp.player.interop.WinampIniParser
import com.droidamp.player.interop.WinampMigrationAnalyzer
import com.droidamp.player.interop.WinampPlaylistCodec
import com.droidamp.player.interop.WinampPresetLibrary
import com.droidamp.player.interop.WinampQ2Codec
import com.droidamp.player.skin.DroidAmpSkin
import com.droidamp.player.skin.SkinEntry
import com.droidamp.player.skin.SkinRecoveryNotice
import com.droidamp.player.skin.SkinRenderMode
import com.droidamp.player.skin.SkinRepository
import com.droidamp.player.skin.SkinSettingsState
import com.droidamp.player.skin.StaticSkinRepository
import com.droidamp.player.storage.AppPreferences
import com.droidamp.player.storage.AppPreferencesRepository
import com.droidamp.player.storage.AudioBufferMode
import com.droidamp.player.storage.InMemoryAppPreferencesRepository
import com.droidamp.player.storage.StartupVolumeMode
import com.droidamp.player.storage.TrackTransitionMode
import com.droidamp.player.ui.settings.PendingMigrationDialog
import com.droidamp.player.ui.settings.SettingsRoute
import com.droidamp.player.ui.sheet.PlaylistMicroMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

private val DRIVE_OR_SCHEME_PREFIX_REGEX =
  Regex("^(?:[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*|[a-zA-Z]:)")

enum class ImportSlot {
  SKIN_WSZ,
  EQ_PRESET_EQF,
  MIGRATION_INI,
  MIGRATION_Q1,
  MIGRATION_Q2,
}

data class RackUiState(
  val isEqVisible: Boolean = true,
  val isSheetExpanded: Boolean = false,
  val sheetMode: SheetMode = SheetMode.PLAYLIST,
  val libraryTab: LibraryTab = LibraryTab.FOLDERS,
  val libraryViewLevel: LibraryViewLevel = LibraryViewLevel.ROOT,
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
  val isSettingsOpen: Boolean = false,
  val settingsRoute: SettingsRoute = SettingsRoute.MAIN,
  val pendingMigrationDialog: PendingMigrationDialog? = null,
)

class DroidAmpViewModel(
  private val audioEngine: AudioEngineController,
  private val musicRepository: MusicSourceRepository,
  private val autoEqResolver: AutoEqPresetResolver = AutoEqMatcher(),
  private val playlistCodec: PlaylistFileCodec = WinampPlaylistCodec(),
  private val autoEqCatalogProvider: AutoEqCatalogProvider = EmptyAutoEqCatalogProvider,
  private val skinRepository: SkinRepository = StaticSkinRepository(),
  private val eqfCodec: EqfCodec = BinaryEqfCodec(),
  private val q2Codec: Q2Codec = WinampQ2Codec(),
  private val iniCodec: WinampIniCodec = WinampIniParser(),
  private val appPreferencesRepository: AppPreferencesRepository = InMemoryAppPreferencesRepository(),
  private val userEqPresetStore: UserEqPresetStore? = null,
) : ViewModel() {

  private data class AutoEqTriggerKey(
    val track: AudioTrack?,
    val autoEnabled: Boolean,
    val rootUris: List<String>,
    val importedPresets: List<EqPreset>,
    val importedRules: List<AutoEqRule>,
  )

  val playbackState: StateFlow<PlaybackState> = audioEngine.playbackState
  val visualizerFrame: StateFlow<VisualizerFrame> = audioEngine.visualizerFrame
  val dspSettings: StateFlow<DspSettings> = audioEngine.dspSettings
  val playbackRecoveryNotice: StateFlow<PlaybackRecoveryNotice?> = audioEngine.playbackRecoveryNotice
  val linkedRoots: StateFlow<List<FolderEntry>> = musicRepository.linkedRoots
  val managedSafFolders: StateFlow<List<ManagedSafFolder>> = musicRepository.managedSafFolders
  val libraryScanState: StateFlow<LibraryScanState> = musicRepository.scanState
  val storageBackend: StateFlow<StorageBackend> = musicRepository.storageBackend
  val appPreferences: StateFlow<AppPreferences> = appPreferencesRepository.preferences
  val availableSkins: StateFlow<List<SkinEntry>> = skinRepository.availableSkins
  val activeSkin: StateFlow<DroidAmpSkin> = skinRepository.activeSkin
  val skinSettings: StateFlow<SkinSettingsState> = skinRepository.skinSettings

  private val _localRecoveryNotice = MutableStateFlow<SkinRecoveryNotice?>(null)
  val skinRecoveryNotice: StateFlow<SkinRecoveryNotice?> =
    combine(skinRepository.recoveryNotice, _localRecoveryNotice) { skinNotice, localNotice ->
      localNotice ?: skinNotice
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.Eagerly,
      initialValue = _localRecoveryNotice.value ?: skinRepository.recoveryNotice.value,
    )

  private val _ephemeralUserPresets = MutableStateFlow<List<EqPreset>>(emptyList())
  private val _ephemeralUserAutoEqRules = MutableStateFlow<List<AutoEqRule>>(emptyList())
  val userEqPresets: StateFlow<List<EqPreset>> =
    userEqPresetStore?.userPresets ?: _ephemeralUserPresets.asStateFlow()
  val userAutoEqRules: StateFlow<List<AutoEqRule>> =
    userEqPresetStore?.userAutoEqRules ?: _ephemeralUserAutoEqRules.asStateFlow()

  private val _uiState = MutableStateFlow(RackUiState())
  val uiState: StateFlow<RackUiState> = _uiState.asStateFlow()

  private val importedEqPresets = MutableStateFlow<List<EqPreset>>(emptyList())
  private val importedAutoEqRules = MutableStateFlow<List<AutoEqRule>>(emptyList())

  private var coldLaunchStartupApplied = false
  private var libraryFeedbackJob: Job? = null
  private var autoEqFeedbackJob: Job? = null

  init {
    val initialPrefs = appPreferencesRepository.preferences.value
    syncPreferencesToAudioEngine(initialPrefs)
    if (initialPrefs.startupVolumeMode == StartupVolumeMode.CLASSIC_80_PERCENT) {
      coldLaunchStartupApplied = true
      audioEngine.applyColdLaunchVolumeResetIfStopped(PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME)
    }
    viewModelScope.launch {
      userEqPresetStore?.ensureLoaded()
    }
    observeAppPreferences()
    refreshLibraryFromCurrentRoot()
    observeLinkedRootHydration()
    observeAutoEqMatching()
  }

  private fun observeAppPreferences() {
    viewModelScope.launch {
      appPreferencesRepository.preferences.collect { prefs ->
        syncPreferencesToAudioEngine(prefs)
        if (!coldLaunchStartupApplied &&
          prefs.startupVolumeMode == StartupVolumeMode.CLASSIC_80_PERCENT
        ) {
          coldLaunchStartupApplied = true
          audioEngine.applyColdLaunchVolumeResetIfStopped(PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME)
        }
      }
    }
  }

  private fun syncPreferencesToAudioEngine(prefs: AppPreferences) {
    audioEngine.setVolumeKeyMode(prefs.volumeKeyMode)
    audioEngine.setPauseOnDisconnect(prefs.pauseOnDisconnect)
    audioEngine.setAutoResumeOnReconnect(prefs.autoResumeOnReconnect)
    audioEngine.setStartupVolumeMode(prefs.startupVolumeMode)
    audioEngine.setLowLatencyAudioBuffer(prefs.audioBufferMode == AudioBufferMode.LOW_LATENCY)

    val desiredGapless = prefs.trackTransitionMode == TrackTransitionMode.GAPLESS
    val desiredCrossfade =
      if (prefs.trackTransitionMode == TrackTransitionMode.CROSSFADE) {
        prefs.crossfadeDurationSec.coerceIn(
          AppPreferences.MIN_CROSSFADE_DURATION_SEC,
          AppPreferences.MAX_CROSSFADE_DURATION_SEC,
        )
      } else {
        0
      }
    val curDsp = audioEngine.dspSettings.value
    if (curDsp.gaplessEnabled != desiredGapless || curDsp.crossfadeSeconds != desiredCrossfade) {
      audioEngine.setDspSettings(
        curDsp.copy(
          gaplessEnabled = desiredGapless,
          crossfadeSeconds = desiredCrossfade,
        ).sanitized(),
      )
    }
  }

  private fun observeLinkedRootHydration() {
    viewModelScope.launch {
      musicRepository.linkedRoots
        .map { roots -> roots.map { it.uriString } }
        .distinctUntilChanged()
        .collect { rootUris ->
          skinRepository.refreshSkins(rootUris)
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
        combine(userEqPresets, importedEqPresets) { storePresets, memPresets ->
          mergePresetsPreferringIncoming(storePresets, memPresets)
        },
        combine(userAutoEqRules, importedAutoEqRules) { storeRules, memRules ->
          (memRules + storeRules).distinctBy {
            "${it.pattern.lowercase(Locale.ROOT)}|${it.preset.name.lowercase(Locale.ROOT)}"
          }
        },
      ) { (track, autoEnabled), rootUris, extraImportedPresets, extraImportedRules ->
        AutoEqTriggerKey(
          track = track,
          autoEnabled = autoEnabled,
          rootUris = rootUris,
          importedPresets = extraImportedPresets,
          importedRules = extraImportedRules,
        )
      }.collectLatest { trigger ->
        val catalog =
          try {
            autoEqCatalogProvider.loadCatalog(trigger.rootUris, trigger.track)
          } catch (ce: CancellationException) {
            throw ce
          } catch (_: Throwable) {
            AutoEqCatalog()
          }
        val baseMerged =
          WinampPresetLibrary.mergePresets(catalog.extraPresets, ClassicWinampPresets.ALL)
        val mergedPresets =
          mergePresetsPreferringIncoming(
            existing = baseMerged,
            incoming = trigger.importedPresets,
          )
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

        val combinedRules =
          (trigger.importedRules + catalog.rules).distinctBy {
            "${it.pattern.lowercase(Locale.ROOT)}|${it.preset.name.lowercase(Locale.ROOT)}"
          }
        val matched =
          autoEqResolver.resolveForTrack(track, combinedRules, mergedPresets) ?: return@collectLatest
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
                statusBanner = "MUSIC FOLDER LINKED",
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
            statusBanner = "COULDN'T ACCESS FOLDER — TRY ANOTHER",
          )
        }
      }
    }
  }

  fun onStoragePermissionResult(granted: Boolean) {
    if (!granted) {
      _uiState.update {
        it.copy(statusBanner = "STORAGE PERMISSION DENIED")
      }
      return
    }
    viewModelScope.launch {
      runCatching { musicRepository.rescanLibrary() }
      val contents = runCatching { musicRepository.browseFolder(null) }.getOrDefault(FolderContents.EMPTY)
      val lists = runCatching { musicRepository.listPlaylists() }.getOrDefault(emptyList())
      _uiState.update {
        it.copy(
          folderContents = contents,
          playlists = lists,
          sheetMode = SheetMode.LIBRARY,
          isSheetExpanded = if (it.isSettingsOpen) it.isSheetExpanded else true,
          libraryTab = LibraryTab.FOLDERS,
          selectedLibraryTrackIds = emptySet(),
          selectedLibraryPlaylistId = null,
          statusBanner = "MUSIC LIBRARY READY",
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
    _uiState.update {
      it.copy(
        isSheetExpanded = false,
        isSettingsOpen = false,
        settingsRoute = SettingsRoute.MAIN,
        activeMicroMenu = PlaylistMicroMenu.NONE,
      )
    }
  }

  fun toggleSheetExpanded() {
    _uiState.update { state ->
      if (state.isSheetExpanded) {
        state.copy(
          isSheetExpanded = false,
          isSettingsOpen = false,
          settingsRoute = SettingsRoute.MAIN,
          activeMicroMenu = PlaylistMicroMenu.NONE,
        )
      } else {
        state.copy(isSheetExpanded = true, activeMicroMenu = PlaylistMicroMenu.NONE)
      }
    }
  }

  /** ADR-019: Deck `QUEUE` toggle opens drawer directly to `QUEUE` (or collapses if already open on Queue). */
  fun onQueueButtonPressed() {
    _uiState.update {
      if (it.isSheetExpanded && !it.isSettingsOpen && it.sheetMode == SheetMode.PLAYLIST) {
        it.copy(isSheetExpanded = false, activeMicroMenu = PlaylistMicroMenu.NONE)
      } else {
        it.copy(
          sheetMode = SheetMode.PLAYLIST,
          isSheetExpanded = true,
          isSettingsOpen = false,
          settingsRoute = SettingsRoute.MAIN,
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
        isSettingsOpen = false,
        settingsRoute = SettingsRoute.MAIN,
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
        LibraryViewLevel.PLAYLISTS, LibraryViewLevel.SKINS -> openLibraryRoot()
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

  fun openLibrarySkins() {
    skinRepository.refreshSkins(linkedRoots.value.map { it.uriString })
    _uiState.update {
      it.copy(
        libraryViewLevel = LibraryViewLevel.SKINS,
        selectedLibraryTrackIds = emptySet(),
        selectedLibraryPlaylistId = null,
      )
    }
  }

  fun selectSkin(skinId: String) {
    skinRepository.selectSkin(skinId)
    val label = availableSkins.value.firstOrNull { it.id == skinId }?.displayName ?: skinId
    showLibraryBanner("SKIN: ${label.uppercase()}")
  }

  fun importSkinFromUri(uriString: String) {
    viewModelScope.launch {
      skinRepository.importSkinFromUri(uriString)
        .onSuccess { entry ->
          if (entry != null) {
            showLibraryBanner("IMPORTED SKIN: ${entry.displayName.uppercase()}")
          }
          // When entry == null, skinRepository.recoveryNotice drives SkinRecoveryDialog!
        }
        .onFailure { error ->
          showLibraryBanner("SKIN IMPORT FAILED: ${error.message?.take(36)?.uppercase() ?: "INVALID .WSZ"}")
        }
    }
  }

  fun setLegacySkinRenderMode(mode: SkinRenderMode) {
    skinRepository.setLegacyRenderMode(mode)
    val label =
      if (mode == SkinRenderMode.CLASSIC_RAW_BMP) {
        "SKIN STYLE: CLASSIC BITMAP"
      } else {
        "SKIN STYLE: VECTOR"
      }
    showLibraryBanner(label)
  }

  fun setAutoContrastGuard(enabled: Boolean) {
    skinRepository.setAutoContrastGuard(enabled)
    showLibraryBanner(if (enabled) "CONTRAST GUARD: ON" else "CONTRAST GUARD: OFF")
  }

  fun deleteImportedSkin(entry: SkinEntry) {
    if (!entry.source.canDelete) return
    viewModelScope.launch {
      val deleted = skinRepository.deleteImportedSkin(entry.id)
      if (deleted) {
        showLibraryBanner("DELETED SKIN: ${entry.displayName.uppercase()}")
      }
    }
  }

  fun dismissSkinRecoveryNotice() {
    _localRecoveryNotice.value = null
    skinRepository.dismissRecoveryNotice()
  }

  // --- M6 Settings Navigation, Preferences & Library Recovery ---

  fun openSettings() {
    _uiState.update {
      it.copy(
        isSheetExpanded = true,
        isSettingsOpen = true,
        settingsRoute = SettingsRoute.MAIN,
        activeMicroMenu = PlaylistMicroMenu.NONE,
      )
    }
  }

  fun navigateSettings(route: SettingsRoute) {
    _uiState.update { it.copy(settingsRoute = route) }
  }

  fun navigateBackInSettings() {
    val state = _uiState.value
    if (state.settingsRoute != SettingsRoute.MAIN) {
      _uiState.update { it.copy(settingsRoute = SettingsRoute.MAIN) }
    } else {
      _uiState.update {
        it.copy(
          isSettingsOpen = false,
          settingsRoute = SettingsRoute.MAIN,
        )
      }
    }
  }

  fun updateAppPreferences(transform: (AppPreferences) -> AppPreferences) {
    viewModelScope.launch {
      appPreferencesRepository.update(transform)
    }
  }

  fun setTrackTransitionMode(mode: TrackTransitionMode) {
    viewModelScope.launch {
      appPreferencesRepository.update { it.copy(trackTransitionMode = mode) }
    }
  }

  fun setCrossfadeDurationSec(seconds: Int) {
    val clamped =
      seconds.coerceIn(
        AppPreferences.MIN_CROSSFADE_DURATION_SEC,
        AppPreferences.MAX_CROSSFADE_DURATION_SEC,
      )
    viewModelScope.launch {
      appPreferencesRepository.update { it.copy(crossfadeDurationSec = clamped) }
    }
  }

  fun setReplayGainMode(mode: ReplayGainMode) {
    val current = audioEngine.dspSettings.value
    audioEngine.setDspSettings(current.copy(replayGainMode = mode).sanitized())
  }

  fun setReplayGainPreampDb(db: Float) {
    val current = audioEngine.dspSettings.value
    audioEngine.setDspSettings(current.copy(replayGainPreampDb = db).sanitized())
  }

  fun selectMusicSource(backend: StorageBackend) {
    viewModelScope.launch {
      musicRepository.setStorageBackend(backend)
      val contents = runCatching { musicRepository.browseFolder(null) }.getOrDefault(FolderContents.EMPTY)
      val lists = runCatching { musicRepository.listPlaylists() }.getOrDefault(emptyList())
      _uiState.update {
        it.copy(
          folderContents = contents,
          playlists = lists,
          libraryViewLevel = LibraryViewLevel.ROOT,
          selectedLibraryTrackIds = emptySet(),
          selectedLibraryPlaylistId = null,
          statusBanner =
            if (backend == StorageBackend.MEDIA_STORE) {
              "MUSIC SOURCE: ALL DEVICE MUSIC"
            } else {
              "MUSIC SOURCE: SELECTED FOLDERS"
            },
        )
      }
    }
  }

  fun rescanMusicLibrary() {
    viewModelScope.launch {
      val count = runCatching { musicRepository.rescanLibrary() }.getOrDefault(0)
      val contents = runCatching { musicRepository.browseFolder(null) }.getOrDefault(FolderContents.EMPTY)
      val lists = runCatching { musicRepository.listPlaylists() }.getOrDefault(emptyList())
      _uiState.update {
        it.copy(
          folderContents = contents,
          playlists = lists,
          statusBanner = "INDEXED $count TRACKS",
        )
      }
    }
  }

  fun removeSafRoot(treeUriString: String) {
    viewModelScope.launch {
      musicRepository.removeSafRoot(treeUriString)
      refreshLibraryFromCurrentRoot()
    }
  }

  fun regrantSafRoot(
    oldUriString: String,
    newUriString: String,
  ) {
    viewModelScope.launch {
      val replaced = musicRepository.replaceSafRoot(oldUriString, newUriString)
      refreshLibraryFromCurrentRoot()
      _uiState.update {
        it.copy(
          statusBanner =
            if (replaced) {
              "FOLDER ACCESS RESTORED"
            } else {
              "COULDN'T RESTORE FOLDER ACCESS"
            },
        )
      }
    }
  }

  fun resetAllSettingsToDefaults() {
    viewModelScope.launch {
      appPreferencesRepository.resetToDefaults()
      audioEngine.setDspSettings(DspSettings())
      skinRepository.setLegacyRenderMode(SkinRenderMode.VECTOR_CHROME)
      skinRepository.setAutoContrastGuard(true)
      _uiState.update {
        it.copy(statusBanner = "RESTORED DEFAULT SETTINGS")
      }
    }
  }

  fun removeMissingTrack(uriString: String) {
    audioEngine.removeTrackByUri(uriString)
    audioEngine.dismissPlaybackRecoveryNotice()
    viewModelScope.launch {
      musicRepository.pruneMissingTrack(uriString)
    }
    _uiState.update { it.copy(statusBanner = "REMOVED MISSING TRACK") }
  }

  fun dismissPlaybackRecoveryNotice() {
    audioEngine.dismissPlaybackRecoveryNotice()
  }

  // --- M6 On-Deck EQ Presets Menu, Settings Deep-Link & Persistence ---

  fun openEqPresetsSettings() {
    _uiState.update {
      it.copy(
        isSheetExpanded = true,
        isSettingsOpen = true,
        settingsRoute = SettingsRoute.EQ_PRESETS,
        activeMicroMenu = PlaylistMicroMenu.NONE,
      )
    }
  }

  fun checkEqPresetConflict(name: String): PresetConflictType {
    if (userEqPresetStore != null) {
      return userEqPresetStore.hasPresetConflict(name)
    }
    val key = name.trim().lowercase(Locale.ROOT)
    if (key.isEmpty()) return PresetConflictType.NONE
    if (_ephemeralUserPresets.value.any { it.name.trim().lowercase(Locale.ROOT) == key }) {
      return PresetConflictType.USER_PRESET
    }
    if (ClassicWinampPresets.ALL.any { it.name.trim().lowercase(Locale.ROOT) == key }) {
      return PresetConflictType.BUILT_IN_PRESET
    }
    return PresetConflictType.NONE
  }

  fun saveCurrentEqCurveAsPreset(
    name: String,
    overwrite: Boolean = true,
  ) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return
    val eq = playbackState.value.equalizer
    val preset = EqPreset(name = trimmed, preampDb = eq.preampDb, bandsDb = eq.bandsDb)
    viewModelScope.launch {
      if (userEqPresetStore != null) {
        userEqPresetStore.savePreset(preset, overwrite = overwrite)
      } else {
        _ephemeralUserPresets.update { mergePresetsPreferringIncoming(it, listOf(preset)) }
      }
      audioEngine.applyEqPreset(preset)
      showAutoEqFeedback("SAVED: ${trimmed.uppercase()}")
    }
  }

  fun saveAutoEqRuleForTrack(track: AudioTrack) {
    val pattern = track.fileName.trim().ifBlank { track.displayLabel }
    saveAutoEqRule(pattern = pattern, feedbackPrefix = "AUTO-EQ TRACK")
  }

  fun saveAutoEqRuleForFolder(track: AudioTrack) {
    val pattern = track.folderPath.trim()
    if (pattern.isEmpty()) return
    saveAutoEqRule(pattern = pattern, feedbackPrefix = "AUTO-EQ FOLDER")
  }

  private fun saveAutoEqRule(
    pattern: String,
    feedbackPrefix: String,
  ) {
    val eq = playbackState.value.equalizer
    val rule =
      AutoEqRule(
        pattern = pattern,
        preset = EqPreset(name = pattern, preampDb = eq.preampDb, bandsDb = eq.bandsDb),
      )
    viewModelScope.launch {
      if (userEqPresetStore != null) {
        userEqPresetStore.saveAutoRule(rule)
      } else {
        _ephemeralUserAutoEqRules.update { list ->
          (listOf(rule) + list).distinctBy { it.pattern.trim().lowercase(Locale.ROOT) }
        }
      }
      showAutoEqFeedback("$feedbackPrefix SAVED")
    }
  }

  fun deleteUserEqPreset(name: String) {
    val key = name.trim().lowercase(Locale.ROOT)
    importedEqPresets.update { list ->
      list.filterNot { it.name.trim().lowercase(Locale.ROOT) == key }
    }
    _uiState.update { state ->
      state.copy(
        availableEqPresets =
          mergePresetsPreferringIncoming(
            ClassicWinampPresets.ALL,
            importedEqPresets.value,
          ),
      )
    }
    viewModelScope.launch {
      if (userEqPresetStore != null) {
        userEqPresetStore.deletePreset(name)
      } else {
        _ephemeralUserPresets.update { list ->
          list.filterNot { it.name.trim().lowercase(Locale.ROOT) == key }
        }
      }
    }
  }

  fun deleteUserAutoEqRule(pattern: String) {
    val key = pattern.trim().lowercase(Locale.ROOT)
    importedAutoEqRules.update { list ->
      list.filterNot { it.pattern.trim().lowercase(Locale.ROOT) == key }
    }
    viewModelScope.launch {
      if (userEqPresetStore != null) {
        userEqPresetStore.deleteAutoRule(pattern)
      } else {
        _ephemeralUserAutoEqRules.update { list ->
          list.filterNot { it.pattern.trim().lowercase(Locale.ROOT) == key }
        }
      }
    }
  }

  fun resetEqToFlat() {
    audioEngine.applyEqPreset(ClassicWinampPresets.FLAT)
    showAutoEqFeedback("EQ: FLAT 0 DB")
  }

  fun encodeCurrentEqAsEqfBytes(): ByteArray {
    val eq = playbackState.value.equalizer
    val name = eq.activePresetName.ifBlank { "DroidAmp EQ" }
    return eqfCodec.encodeSingleEqf(
      EqPreset(name = name, preampDb = eq.preampDb, bandsDb = eq.bandsDb),
    )
  }

  fun exportWinampIniText(): String {
    val st = playbackState.value
    val dsp = audioEngine.dspSettings.value
    val rgInt =
      when (dsp.replayGainMode) {
        ReplayGainMode.OFF -> 0
        ReplayGainMode.TRACK -> 1
        ReplayGainMode.ALBUM -> 2
      }
    return iniCodec.encode(
      WinampIniSnapshot(
        volume = st.volume,
        pan = st.balance,
        shuffle = st.shuffle,
        repeat = st.repeatMode != RepeatMode.OFF,
        timeRemaining = st.timeDisplayMode == TimeDisplayMode.REMAINING,
        eqEnabled = st.equalizer.enabled,
        eqAuto = st.equalizer.autoEnabled,
        eqPreampDb = st.equalizer.preampDb,
        eqBandsDb = st.equalizer.bandsDb,
        replayGainMode = rgInt,
        replayGainPreampDb = dsp.replayGainPreampDb,
        crossfadeSeconds = dsp.crossfadeSeconds,
      ),
    )
  }

  fun exportWinampQ1Bytes(): ByteArray =
    userEqPresetStore?.exportUserPresetsQ1Bytes()
      ?: eqfCodec.encodePresetLibraryQ1(_ephemeralUserPresets.value)

  fun exportWinampQ2Bytes(): ByteArray =
    userEqPresetStore?.exportUserAutoEqQ2Bytes()
      ?: q2Codec.encodeAutoPresetsQ2(_ephemeralUserAutoEqRules.value)

  fun importFileForSlot(
    slot: ImportSlot,
    fileName: String,
    rawBytes: ByteArray,
    uriString: String = "",
  ) {
    val detected = DroidAmpFileSniffer.sniff(rawBytes, fileName)
    when (slot) {
      ImportSlot.EQ_PRESET_EQF -> {
        if (detected == DetectedWinampFormat.EqfSinglePreset) {
          applySingleEqfBytes(fileName, rawBytes)
          return
        }
      }
      ImportSlot.MIGRATION_INI -> {
        if (detected == DetectedWinampFormat.WinampIni) {
          val text = decodeTextBytes(rawBytes)
          val analysis = WinampMigrationAnalyzer.analyzeIni(text, iniCodec)
          _uiState.update {
            it.copy(pendingMigrationDialog = PendingMigrationDialog.IniPreview(fileName, analysis))
          }
          return
        }
      }
      ImportSlot.MIGRATION_Q1 -> {
        if (detected == DetectedWinampFormat.EqLibraryQ1) {
          val analysis = WinampMigrationAnalyzer.analyzeQ1(rawBytes)
          _uiState.update {
            it.copy(pendingMigrationDialog = PendingMigrationDialog.Q1Preview(fileName, analysis))
          }
          return
        }
      }
      ImportSlot.MIGRATION_Q2 -> {
        if (detected == DetectedWinampFormat.AutoEqQ2) {
          val analysis = WinampMigrationAnalyzer.analyzeQ2(rawBytes)
          _uiState.update {
            it.copy(pendingMigrationDialog = PendingMigrationDialog.Q2Preview(fileName, analysis))
          }
          return
        }
      }
      ImportSlot.SKIN_WSZ -> {
        if (detected is DetectedWinampFormat.WszSkin) {
          viewModelScope.launch {
            skinRepository.importSkinFromBytes(fileName, rawBytes)
          }
          return
        }
      }
    }

    _localRecoveryNotice.value =
      SkinRecoveryNotice.WrongSlotFileDetected(
        uriString = uriString,
        fileName = fileName,
        detectedFormat = detected,
        rawBytes = rawBytes,
      )
  }

  private fun applySingleEqfBytes(
    fileName: String,
    rawBytes: ByteArray,
  ) {
    runCatching { eqfCodec.parseSingleEqf(rawBytes) }
      .onSuccess { parsed ->
        val fallbackName = fileName.substringBeforeLast('.').trim().ifEmpty { "Imported Preset" }
        val preset = if (parsed.name.isBlank()) parsed.copy(name = fallbackName) else parsed
        viewModelScope.launch {
          if (userEqPresetStore != null) {
            userEqPresetStore.savePreset(preset, overwrite = true)
          } else {
            _ephemeralUserPresets.update { mergePresetsPreferringIncoming(it, listOf(preset)) }
          }
        }
        importedEqPresets.update { existing -> mergePresetsPreferringIncoming(existing, listOf(preset)) }
        audioEngine.applyEqPreset(preset)
        val banner = "APPLIED EQ PRESET: ${preset.name.uppercase()}"
        _uiState.update { state ->
          state.copy(
            availableEqPresets = mergePresetsPreferringIncoming(state.availableEqPresets, listOf(preset)),
            statusBanner = banner,
          )
        }
        showAutoEqFeedback(banner)
        showLibraryBanner(banner)
      }
      .onFailure { error ->
        val banner = "EQF IMPORT FAILED: ${error.message?.take(36)?.uppercase() ?: "CORRUPT .EQF"}"
        _uiState.update { it.copy(statusBanner = banner) }
      }
  }

  fun confirmIniMigration(analysis: IniAnalysisResult) {
    _uiState.update { it.copy(pendingMigrationDialog = null) }
    applyWinampIniSnapshot(analysis.snapshot)
  }

  fun confirmQ1Migration(
    analysis: Q1AnalysisResult,
    replaceAll: Boolean,
  ) {
    _uiState.update { it.copy(pendingMigrationDialog = null) }
    if (analysis.presets.isEmpty() && !replaceAll) return
    if (replaceAll) {
      importedEqPresets.value = emptyList()
      _uiState.update { it.copy(availableEqPresets = ClassicWinampPresets.ALL) }
    }
    viewModelScope.launch {
      if (userEqPresetStore != null) {
        userEqPresetStore.importPresets(analysis.presets, replaceAll = replaceAll)
      } else {
        _ephemeralUserPresets.update { existing ->
          if (replaceAll) analysis.presets else mergePresetsPreferringIncoming(existing, analysis.presets)
        }
      }
      _uiState.update {
        it.copy(statusBanner = "IMPORTED ${analysis.presets.size} PRESETS (.Q1)")
      }
    }
  }

  fun confirmQ2Migration(
    analysis: Q2AnalysisResult,
    replaceAll: Boolean,
  ) {
    _uiState.update { it.copy(pendingMigrationDialog = null) }
    if (analysis.rules.isEmpty() && !replaceAll) return
    if (replaceAll) {
      importedAutoEqRules.value = emptyList()
    }
    viewModelScope.launch {
      if (userEqPresetStore != null) {
        userEqPresetStore.importAutoRules(analysis.rules, replaceAll = replaceAll)
      } else {
        _ephemeralUserAutoEqRules.update { existing ->
          val combined = if (replaceAll) analysis.rules else (analysis.rules + existing)
          combined.distinctBy { "${it.pattern.lowercase(Locale.ROOT)}|${it.preset.name.lowercase(Locale.ROOT)}" }
        }
      }
      _uiState.update {
        it.copy(statusBanner = "IMPORTED ${analysis.rules.size} AUTO-EQ RULES (.Q2)")
      }
    }
  }

  fun dismissMigrationDialog() {
    _uiState.update { it.copy(pendingMigrationDialog = null) }
  }

  private fun applyWinampIniSnapshot(snapshot: WinampIniSnapshot) {
    audioEngine.setVolume(snapshot.volume)
    audioEngine.setBalance(snapshot.pan)
    if (playbackState.value.shuffle != snapshot.shuffle) {
      audioEngine.toggleShuffle()
    }
    val targetRepeat = if (snapshot.repeat) RepeatMode.ALL else RepeatMode.OFF
    repeat(3) {
      if (playbackState.value.repeatMode != targetRepeat) {
        audioEngine.cycleRepeatMode()
      }
    }
    val targetTimeMode =
      if (snapshot.timeRemaining) TimeDisplayMode.REMAINING else TimeDisplayMode.ELAPSED
    if (playbackState.value.timeDisplayMode != targetTimeMode) {
      audioEngine.toggleTimeDisplayMode()
    }
    audioEngine.setEqEnabled(snapshot.eqEnabled)
    audioEngine.setEqAutoEnabled(snapshot.eqAuto)
    audioEngine.setPreampDb(snapshot.eqPreampDb)
    snapshot.eqBandsDb.take(WinampEqSpec.BAND_COUNT).forEachIndexed { idx, db ->
      audioEngine.setEqBandDb(idx, db)
    }
    val mappedRgMode =
      when (snapshot.replayGainMode) {
        1 -> ReplayGainMode.TRACK
        2 -> ReplayGainMode.ALBUM
        else -> ReplayGainMode.OFF
      }
    val currentDsp = audioEngine.dspSettings.value
    audioEngine.setDspSettings(
      currentDsp
        .copy(
          replayGainMode = mappedRgMode,
          replayGainPreampDb = snapshot.replayGainPreampDb,
          crossfadeSeconds = snapshot.crossfadeSeconds,
        ).sanitized(),
    )
    if (snapshot.crossfadeSeconds > 0) {
      viewModelScope.launch {
        appPreferencesRepository.update {
          it.copy(
            trackTransitionMode = TrackTransitionMode.CROSSFADE,
            crossfadeDurationSec = snapshot.crossfadeSeconds.coerceIn(1, 12),
          )
        }
      }
    }
  }

  fun retrySafeBootSkin(skinId: String) {
    skinRepository.retrySafeBootSkin(skinId)
  }

  fun revertPartialSkin(previousSkinId: String) {
    skinRepository.revertToSkin(previousSkinId)
    skinRepository.dismissRecoveryNotice()
  }

  fun useRawOgColorsForSkin(skinId: String) {
    skinRepository.setSkinRawColorsOverride(skinId, true)
    skinRepository.dismissRecoveryNotice()
    showLibraryBanner("APPLIED ORIGINAL SKIN COLORS")
  }

  fun resolveDuplicateSkinImport(
    conflict: SkinRecoveryNotice.DuplicateSkinConflict,
    replaceExisting: Boolean,
  ) {
    viewModelScope.launch {
      skinRepository.resolveDuplicateImport(conflict, replaceExisting)
        .onSuccess { entry ->
          val prefix = if (replaceExisting) "REPLACED SKIN" else "IMPORTED SKIN"
          showLibraryBanner("$prefix: ${entry.displayName.uppercase()}")
        }
        .onFailure { error ->
          showLibraryBanner(
            "SKIN IMPORT FAILED: ${error.message?.take(36)?.uppercase() ?: "INVALID .WSZ"}",
          )
        }
    }
  }

  fun confirmWrongSlotRedirect(notice: SkinRecoveryNotice.WrongSlotFileDetected) {
    _localRecoveryNotice.value = null
    when (notice.detectedFormat) {
      DetectedWinampFormat.EqfSinglePreset -> {
        skinRepository.dismissRecoveryNotice()
        applySingleEqfBytes(notice.fileName, notice.rawBytes)
      }

      DetectedWinampFormat.EqLibraryQ1 -> {
        runCatching { eqfCodec.parsePresetLibraryQ1(notice.rawBytes) }
          .onSuccess { rawPresets ->
            val presets =
              rawPresets.mapIndexed { idx, preset ->
                if (preset.name.isBlank()) preset.copy(name = "Preset ${idx + 1}") else preset
              }
            viewModelScope.launch {
              userEqPresetStore?.importPresets(presets, replaceAll = false)
            }
            importedEqPresets.update { existing ->
              mergePresetsPreferringIncoming(existing, presets)
            }
            val banner = "LOADED ${presets.size} PRESETS (.Q1)"
            _uiState.update { state ->
              state.copy(
                availableEqPresets =
                  mergePresetsPreferringIncoming(state.availableEqPresets, presets),
                statusBanner = banner,
              )
            }
            presets.firstOrNull()?.let(audioEngine::applyEqPreset)
            showLibraryBanner(banner)
            skinRepository.dismissRecoveryNotice()
          }
          .onFailure { error ->
            val banner = "Q1 IMPORT FAILED: ${error.message?.take(36)?.uppercase() ?: "CORRUPT .Q1"}"
            _uiState.update { it.copy(statusBanner = banner) }
            showLibraryBanner(banner)
            skinRepository.dismissRecoveryNotice()
          }
      }

      DetectedWinampFormat.AutoEqQ2 -> {
        runCatching { q2Codec.parseAutoPresetsQ2(notice.rawBytes) }
          .onSuccess { rules ->
            viewModelScope.launch {
              userEqPresetStore?.importAutoRules(rules, replaceAll = false)
            }
            importedAutoEqRules.update { existing ->
              (rules + existing).distinctBy {
                "${it.pattern.lowercase(Locale.ROOT)}|${it.preset.name.lowercase(Locale.ROOT)}"
              }
            }
            val banner = "LOADED ${rules.size} AUTO-EQ RULES (.Q2)"
            _uiState.update { it.copy(statusBanner = banner) }
            showLibraryBanner(banner)
            skinRepository.dismissRecoveryNotice()
          }
          .onFailure { error ->
            val banner = "Q2 IMPORT FAILED: ${error.message?.take(36)?.uppercase() ?: "CORRUPT .Q2"}"
            _uiState.update { it.copy(statusBanner = banner) }
            showLibraryBanner(banner)
            skinRepository.dismissRecoveryNotice()
          }
      }

      DetectedWinampFormat.WinampIni -> {
        runCatching {
          val iniText = decodeTextBytes(notice.rawBytes)
          iniCodec.parse(iniText)
        }.onSuccess { snapshot ->
          applyWinampIniSnapshot(snapshot)
          val banner = "APPLIED WINAMP.INI SETTINGS"
          _uiState.update { it.copy(statusBanner = banner) }
          showLibraryBanner(banner)
          skinRepository.dismissRecoveryNotice()
        }.onFailure { error ->
          val banner = "INI IMPORT FAILED: ${error.message?.take(36)?.uppercase() ?: "INVALID .INI"}"
          _uiState.update { it.copy(statusBanner = banner) }
          showLibraryBanner(banner)
          skinRepository.dismissRecoveryNotice()
        }
      }

      DetectedWinampFormat.PlaylistM3u,
      DetectedWinampFormat.PlaylistPls,
      -> {
        val isM3u = notice.detectedFormat == DetectedWinampFormat.PlaylistM3u
        skinRepository.dismissRecoveryNotice()
        viewModelScope.launch {
          val text = decodeTextBytes(notice.rawBytes)
          val rawEntries =
            runCatching {
              if (isM3u) playlistCodec.parseM3u(text) else playlistCodec.parsePls(text)
            }.getOrDefault(emptyList())
          val localTracks = collectAllAvailableLocalTracks()
          val resolvedTracks = matchPlaylistEntriesToLocalTracks(rawEntries, localTracks)
          if (resolvedTracks.isNotEmpty()) {
            audioEngine.replaceQueue(resolvedTracks, startIndex = 0, autoPlay = true)
            val banner = "LOADED PLAYLIST: ${resolvedTracks.size} TRACKS"
            _uiState.update {
              it.copy(
                sheetMode = SheetMode.PLAYLIST,
                selectedPlaylistIndices = emptySet(),
                selectedLibraryTrackIds = emptySet(),
                statusBanner = banner,
              )
            }
            showLibraryBanner(banner)
          } else {
            val banner = "PLAYLIST PARSED — NO MATCHING LOCAL TRACKS"
            _uiState.update { it.copy(statusBanner = banner) }
            showLibraryBanner(banner)
          }
        }
      }

      is DetectedWinampFormat.WszSkin -> {
        skinRepository.dismissRecoveryNotice()
        viewModelScope.launch {
          skinRepository.importSkinFromBytes(notice.fileName, notice.rawBytes)
            .onSuccess { entry ->
              if (entry != null) {
                val banner = "IMPORTED SKIN: ${entry.displayName.uppercase()}"
                _uiState.update { it.copy(statusBanner = banner) }
                showLibraryBanner(banner)
              }
            }
            .onFailure { error ->
              val banner = "SKIN IMPORT FAILED: ${error.message?.take(36)?.uppercase() ?: "INVALID .WSZ"}"
              _uiState.update { it.copy(statusBanner = banner) }
            }
        }
      }

      is DetectedWinampFormat.Unsupported -> {
        skinRepository.dismissRecoveryNotice()
      }
    }
  }

  private suspend fun collectAllAvailableLocalTracks(): List<AudioTrack> {
    val byId = LinkedHashMap<String, AudioTrack>()
    for (root in musicRepository.linkedRoots.value) {
      val rootTracks =
        runCatching {
          musicRepository.resolveFolderTracks(root.uriString, recursive = true)
        }.getOrDefault(emptyList())
      for (track in rootTracks) {
        byId.putIfAbsent(track.id, track)
      }
      if (rootTracks.isEmpty()) {
        collectTracksByBrowsing(root.uriString, depth = 0, visited = HashSet(), out = byId)
      }
    }
    if (byId.isEmpty()) {
      collectTracksByBrowsing(null, depth = 0, visited = HashSet(), out = byId)
    }
    for (track in playbackState.value.queue) {
      byId.putIfAbsent(track.id, track)
    }
    return byId.values.toList()
  }

  private suspend fun collectTracksByBrowsing(
    folderUri: String?,
    depth: Int,
    visited: MutableSet<String>,
    out: LinkedHashMap<String, AudioTrack>,
  ) {
    if (depth > 6) return
    val key = folderUri ?: "<root>"
    if (!visited.add(key)) return
    val contents = runCatching { musicRepository.browseFolder(folderUri) }.getOrNull() ?: return
    for (track in contents.tracks) {
      out.putIfAbsent(track.id, track)
    }
    for (sub in contents.folders) {
      val resolved =
        runCatching {
          musicRepository.resolveFolderTracks(sub.uriString, recursive = true)
        }.getOrDefault(emptyList())
      if (resolved.isNotEmpty()) {
        for (track in resolved) {
          out.putIfAbsent(track.id, track)
        }
      } else {
        collectTracksByBrowsing(sub.uriString, depth + 1, visited, out)
      }
    }
  }

  private fun matchPlaylistEntriesToLocalTracks(
    rawEntries: List<String>,
    localTracks: List<AudioTrack>,
  ): List<AudioTrack> {
    if (rawEntries.isEmpty() || localTracks.isEmpty()) return emptyList()

    data class IndexedCandidate(
      val track: AudioTrack,
      val uriLower: String,
      val decodedUriLower: String,
      val relativePathLower: String,
      val fileNameLower: String,
    )

    val candidates =
      localTracks.map { track ->
        val folderNorm = track.folderPath.replace('\\', '/').trim('/')
        val fileNorm = track.fileName.replace('\\', '/').trim('/')
        val rel =
          listOf(folderNorm, fileNorm)
            .filter { it.isNotEmpty() }
            .joinToString("/")
            .lowercase(Locale.ROOT)
        IndexedCandidate(
          track = track,
          uriLower = track.uriString.trim().lowercase(Locale.ROOT),
          decodedUriLower = percentDecodeSafe(track.uriString).replace('\\', '/').lowercase(Locale.ROOT),
          relativePathLower = rel,
          fileNameLower = fileNorm.lowercase(Locale.ROOT),
        )
      }

    val matched = ArrayList<AudioTrack>()
    for (raw in rawEntries) {
      val resolvedPath = playlistCodec.resolveEntryPath(raw, "").trim()
      if (resolvedPath.isEmpty()) continue
      val rawLower = resolvedPath.lowercase(Locale.ROOT)
      val cleanNormalized =
        percentDecodeSafe(resolvedPath)
          .replace('\\', '/')
          .replace(DRIVE_OR_SCHEME_PREFIX_REGEX, "")
          .trim('/')
          .lowercase(Locale.ROOT)
      if (cleanNormalized.isEmpty()) continue
      val entryBaseName = cleanNormalized.substringAfterLast('/')

      val hit =
        candidates.firstOrNull { it.uriLower == rawLower }
          ?: candidates.firstOrNull {
            it.relativePathLower == cleanNormalized ||
              cleanNormalized.endsWith("/${it.relativePathLower}") ||
              (cleanNormalized.contains('/') && it.relativePathLower.endsWith("/$cleanNormalized"))
          }
          ?: candidates.firstOrNull {
            it.decodedUriLower.endsWith("/$cleanNormalized") ||
              it.decodedUriLower.endsWith(":$cleanNormalized")
          }
          ?: candidates.firstOrNull {
            entryBaseName.isNotEmpty() && it.fileNameLower == entryBaseName
          }

      if (hit != null) {
        matched.add(hit.track)
      }
    }
    return matched
  }

  private fun mergePresetsPreferringIncoming(
    existing: List<EqPreset>,
    incoming: List<EqPreset>,
  ): List<EqPreset> {
    if (incoming.isEmpty()) return existing
    val merged = LinkedHashMap<String, EqPreset>()
    for (preset in existing) {
      val key = preset.name.trim().lowercase(Locale.ROOT)
      if (key.isNotEmpty()) {
        merged[key] = preset
      }
    }
    for (preset in incoming) {
      val key = preset.name.trim().lowercase(Locale.ROOT)
      if (key.isNotEmpty()) {
        merged[key] = preset
      }
    }
    return merged.values.toList()
  }

  private fun decodeTextBytes(bytes: ByteArray): String =
    try {
      Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    } catch (_: CharacterCodingException) {
      String(bytes, Charsets.ISO_8859_1)
    }

  private fun percentDecodeSafe(input: String): String {
    if (!input.contains('%')) return input
    val bytes = ByteArray(input.length)
    var outLen = 0
    var i = 0
    while (i < input.length) {
      val ch = input[i]
      if (ch == '%' && i + 2 < input.length) {
        val hi = Character.digit(input[i + 1], 16)
        val lo = Character.digit(input[i + 2], 16)
        if (hi >= 0 && lo >= 0) {
          bytes[outLen++] = ((hi shl 4) or lo).toByte()
          i += 3
          continue
        }
      }
      val utf8 = ch.toString().toByteArray(Charsets.UTF_8)
      for (b in utf8) {
        if (outLen < bytes.size) {
          bytes[outLen++] = b
        }
      }
      i++
    }
    return bytes.decodeToString(0, outLen)
  }

  private fun showLibraryBanner(message: String) {
    libraryFeedbackJob?.cancel()
    _uiState.update { it.copy(libraryFeedbackBanner = message) }
    libraryFeedbackJob =
      viewModelScope.launch {
        delay(2_200L)
        _uiState.update { it.copy(libraryFeedbackBanner = null) }
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
        isSettingsOpen = false,
        settingsRoute = SettingsRoute.MAIN,
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
        "AUTO-EQ: ON"
      } else {
        "AUTO-EQ: OFF"
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

  fun movePlaylistTracksStep(
    anchorIndex: Int,
    delta: Int,
  ): Boolean {
    val queueSize = playbackState.value.queue.size
    if (delta == 0 || anchorIndex !in 0 until queueSize) return false
    val currentSelection = _uiState.value.selectedPlaylistIndices.filter { it in 0 until queueSize }.toSet()
    val movingGroup =
      if (anchorIndex in currentSelection) currentSelection else setOf(anchorIndex)
    val sorted = movingGroup.sorted()
    if (delta < 0 && sorted.first() + delta < 0) return false
    if (delta > 0 && sorted.last() + delta >= queueSize) return false

    audioEngine.moveTracksBy(movingGroup, delta)
    _uiState.update { state ->
      val updatedSel =
        if (anchorIndex in currentSelection) {
          currentSelection.map { it + delta }.toSet()
        } else {
          // Shift any other selected indices through the single-row displacement
          val toIndex = anchorIndex + delta
          state.selectedPlaylistIndices.map { idx ->
            when {
              anchorIndex < toIndex && idx in (anchorIndex + 1)..toIndex -> idx - 1
              anchorIndex > toIndex && idx in toIndex until anchorIndex -> idx + 1
              else -> idx
            }
          }.toSet()
        }
      state.copy(selectedPlaylistIndices = updatedSel)
    }
    return true
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
          statusBanner = "QUEUE EMPTY — NOTHING TO SAVE",
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
