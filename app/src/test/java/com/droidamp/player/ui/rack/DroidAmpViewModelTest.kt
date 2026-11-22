package com.droidamp.player.ui.rack

import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.contract.MusicSourceRepository
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.LibraryTab
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.SheetMode
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.domain.model.WinampEqSpec
import com.droidamp.player.interop.AutoEqMatcher
import com.droidamp.player.interop.WinampPlaylistCodec
import com.droidamp.player.interop.WinampQ2Codec
import com.droidamp.player.ui.sheet.buildPlaylistDisplayEntries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DroidAmpViewModelTest {
  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun coldLaunch_resetsVolumeTo204OnlyWhenStopped() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    engine.setVolume(40)
    assertEquals(PlaybackStatus.STOPPED, engine.playbackState.value.status)

    // Cold launch while stopped resets volume to default 204 (~80%)
    val repo = FakeMusicSourceRepository(startLinked = false)
    DroidAmpViewModel(engine, repo)
    assertEquals(PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME, engine.playbackState.value.volume)
    assertEquals(204, engine.playbackState.value.volume)

    // Start playback quietly at 35 -> resuming must NEVER blast volume back up to 204
    engine.replaceQueue(FakeMusicSourceRepository.DAFT_PUNK_TRACKS, startIndex = 0, autoPlay = true)
    engine.setVolume(35)
    assertEquals(PlaybackStatus.PLAYING, engine.playbackState.value.status)

    DroidAmpViewModel(engine, repo)
    assertEquals(35, engine.playbackState.value.volume)
  }

  @Test
  fun firstRun_startsUnlinkedEmptyState_andLinkingPopulatesSafLibrary() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = false)
    val vm = DroidAmpViewModel(engine, repo)
    advanceUntilIdle()

    assertTrue(vm.linkedRoots.value.isEmpty())
    assertTrue(vm.uiState.value.folderContents.folders.isEmpty())

    vm.onSelectMusicFolder()
    advanceUntilIdle()

    assertFalse(vm.linkedRoots.value.isEmpty())
    assertTrue(vm.uiState.value.isSheetExpanded)
    assertEquals(SheetMode.LIBRARY, vm.uiState.value.sheetMode)
    assertEquals(LibraryTab.FOLDERS, vm.uiState.value.libraryTab)
    assertTrue(vm.uiState.value.folderContents.folders.isNotEmpty())
  }

  @Test
  fun sheetMemory_preservesModeOnCollapseAndReopen_andEjectJumpsToLibrary() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val vm = DroidAmpViewModel(engine, repo)
    advanceUntilIdle()

    assertEquals(SheetMode.PLAYLIST, vm.uiState.value.sheetMode)

    // Switch to LIBRARY, collapse, reopen -> remembers LIBRARY
    vm.expandSheetRememberedMode()
    vm.switchSheetMode(SheetMode.LIBRARY)
    vm.collapseSheet()
    assertFalse(vm.uiState.value.isSheetExpanded)

    vm.expandSheetRememberedMode()
    assertTrue(vm.uiState.value.isSheetExpanded)
    assertEquals(SheetMode.LIBRARY, vm.uiState.value.sheetMode)

    // Switch back to PLAYLIST, collapse, press Eject -> jumps directly to LIBRARY
    vm.switchSheetMode(SheetMode.PLAYLIST)
    vm.collapseSheet()
    vm.onEjectPressed()
    assertTrue(vm.uiState.value.isSheetExpanded)
    assertEquals(SheetMode.LIBRARY, vm.uiState.value.sheetMode)
  }

  @Test
  fun libraryTracks_enforceSelectFirstSafety_andReplacePlayOnExplicitPlay() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val vm = DroidAmpViewModel(engine, repo)
    advanceUntilIdle()

    // Pre-populate an active 4-track playlist
    engine.replaceQueue(FakeMusicSourceRepository.DAFT_PUNK_TRACKS, startIndex = 0, autoPlay = true)
    val singleTrack = FakeMusicSourceRepository.ROOT_TRACKS.first()

    // Single tap on a track in LIBRARY selects it WITHOUT destroying the active queue
    vm.toggleLibraryTrackSelection(singleTrack)
    assertEquals(setOf(singleTrack.id), vm.uiState.value.selectedLibraryTrackIds)
    assertEquals(4, vm.playbackState.value.queue.size)

    // Enqueue appends without interrupting playback
    vm.enqueueSingleLibraryTrack(singleTrack)
    assertEquals(5, vm.playbackState.value.queue.size)
    assertEquals(0, vm.playbackState.value.currentIndex)

    // Double-tap / [▶] replaces queue with just that single track (Winamp behavior)
    vm.playSingleLibraryTrack(singleTrack)
    assertEquals(listOf(singleTrack), vm.playbackState.value.queue)
    assertEquals(PlaybackStatus.PLAYING, vm.playbackState.value.status)
    assertEquals(SheetMode.PLAYLIST, vm.uiState.value.sheetMode)
  }

  @Test
  fun equalizer_clampsBandsAndKeepsPreampIsolated() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val vm = DroidAmpViewModel(engine, repo)

    vm.setPreampDb(3.5f)
    vm.setEqBandDb(0, 20f) // clamped to +12
    vm.setEqBandDb(9, -18f) // clamped to -12

    val eq = vm.playbackState.value.equalizer
    assertEquals(3.5f, eq.preampDb, 0.001f)
    assertEquals(WinampEqSpec.MAX_DB, eq.bandsDb[0], 0.001f)
    assertEquals(WinampEqSpec.MIN_DB, eq.bandsDb[9], 0.001f)
    assertEquals(WinampEqSpec.BAND_COUNT, eq.bandsDb.size)
  }

  @Test
  fun playlistMicroMenuActions_removeCropSortReverseClear() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val vm = DroidAmpViewModel(engine, repo)
    engine.replaceQueue(FakeMusicSourceRepository.DAFT_PUNK_TRACKS, startIndex = 0, autoPlay = false)

    // Remove index 1
    vm.onPlaylistTrackClick(1)
    vm.removeSelectedPlaylistTracks()
    assertEquals(3, vm.playbackState.value.queue.size)

    // Crop to index 0 & 2
    vm.onPlaylistTrackClick(0)
    vm.onPlaylistTrackClick(2)
    vm.cropSelectedPlaylistTracks()
    assertEquals(2, vm.playbackState.value.queue.size)

    // Sort by title
    vm.sortPlaylist(PlaylistSortOrder.TITLE)
    val sortedTitles = vm.playbackState.value.queue.map { it.displayLabel }
    assertEquals(sortedTitles.sortedBy { it.lowercase() }, sortedTitles)

    // Reverse
    vm.reversePlaylist()
    assertEquals(sortedTitles.reversed(), vm.playbackState.value.queue.map { it.displayLabel })

    // Clear
    vm.clearPlaylist()
    assertTrue(vm.playbackState.value.queue.isEmpty())
    assertEquals(PlaybackStatus.STOPPED, vm.playbackState.value.status)
  }

  // --- Area A: AUTO EQ Matching (AutoEqMatcher + WinampQ2Codec wiring) ---

  @Test
  fun autoEq_togglingAutoOnResolvesMatchingFolderPresetImmediately() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val vm = DroidAmpViewModel(engine, repo)
    val rockTrack =
      AudioTrack(
        id = "rk-1",
        uriString = "content://tree/music/rock/01_track.mp3",
        title = "Rock Anthem",
        artist = "Band",
        album = "Live",
        fileName = "01_track.mp3",
        folderPath = "Music/rock",
        durationMs = 200_000L,
      )
    engine.replaceQueue(listOf(rockTrack), startIndex = 0, autoPlay = false)
    advanceUntilIdle()

    vm.toggleEqAuto()
    advanceUntilIdle()

    val eq = vm.playbackState.value.equalizer
    assertTrue(eq.autoEnabled)
    assertEquals(ClassicWinampPresets.ROCK.name, eq.activePresetName)
    assertEquals(ClassicWinampPresets.ROCK.bandsDb, eq.bandsDb)
  }

  @Test
  fun autoEq_trackChangeWhileAutoEnabledAppliesQ2RuleInPriorityOrder() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val q2Codec = WinampQ2Codec()
    val q2Bytes =
      q2Codec.encodeAutoPresetsQ2(
        listOf(
          AutoEqRule("Music/Electronic/Daft Punk - Discovery", ClassicWinampPresets.TECHNO),
          AutoEqRule("01_one_more_time.flac", ClassicWinampPresets.DANCE),
        ),
      )
    val decodedRules = q2Codec.parseAutoPresetsQ2(q2Bytes)
    val catalogProvider =
      object : AutoEqCatalogProvider {
        override suspend fun loadCatalog(
          linkedRootUris: List<String>,
          track: AudioTrack?,
        ): AutoEqCatalog = AutoEqCatalog(rules = decodedRules)
      }

    val vm =
      DroidAmpViewModel(
        audioEngine = engine,
        musicRepository = repo,
        autoEqResolver = AutoEqMatcher(),
        playlistCodec = WinampPlaylistCodec(),
        autoEqCatalogProvider = catalogProvider,
      )

    val tracks = FakeMusicSourceRepository.DAFT_PUNK_TRACKS
    engine.replaceQueue(tracks, startIndex = 0, autoPlay = false)
    vm.toggleEqAuto()
    advanceUntilIdle()

    // Track 0 matches filename rule "01_one_more_time.flac" -> DANCE bands (even though folder is Synthwave)
    assertEquals("01_one_more_time.flac", vm.playbackState.value.equalizer.activePresetName)
    assertEquals(ClassicWinampPresets.DANCE.bandsDb[0], vm.playbackState.value.equalizer.bandsDb[0], 0.5f)

    // Advance to Track 1 -> matches folder rule "Music/Electronic/Daft Punk - Discovery" -> TECHNO bands
    vm.next()
    advanceUntilIdle()
    assertEquals("Music/Electronic/Daft Punk - Discovery", vm.playbackState.value.equalizer.activePresetName)
    assertEquals(ClassicWinampPresets.TECHNO.bandsDb[3], vm.playbackState.value.equalizer.bandsDb[3], 0.5f)

    // Double-click back to Track 0 -> restores filename rule
    vm.onPlaylistTrackDoubleClick(0)
    advanceUntilIdle()
    assertEquals("01_one_more_time.flac", vm.playbackState.value.equalizer.activePresetName)

    // Previous wraps to Track 3 -> matches folder rule
    vm.previous()
    advanceUntilIdle()
    assertEquals("Music/Electronic/Daft Punk - Discovery", vm.playbackState.value.equalizer.activePresetName)
  }

  @Test
  fun autoEq_emptyQueueAndNonMatchingTrackAreSafeNoOps() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = false)
    val vm = DroidAmpViewModel(engine, repo)

    vm.toggleEqAuto()
    advanceUntilIdle()
    assertTrue(vm.playbackState.value.equalizer.autoEnabled)
    assertEquals(ClassicWinampPresets.FLAT.name, vm.playbackState.value.equalizer.activePresetName)

    val unknownTrack =
      AudioTrack(
        id = "unk-1",
        uriString = "content://tree/misc/song.mp3",
        title = "Unknown",
        artist = "Artist",
        album = "Album",
        fileName = "song.mp3",
        folderPath = "Music/Ambient",
        durationMs = 150_000L,
      )
    engine.replaceQueue(listOf(unknownTrack), startIndex = 0, autoPlay = false)
    advanceUntilIdle()
    assertEquals(ClassicWinampPresets.FLAT.name, vm.playbackState.value.equalizer.activePresetName)
  }

  @Test
  fun autoEq_preservesEqBypassStateWhenAutoAppliesPreset() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val vm = DroidAmpViewModel(engine, repo)
    vm.toggleEqEnabled() // bypass EQ (enabled = false)
    assertFalse(vm.playbackState.value.equalizer.enabled)

    val rockTrack =
      AudioTrack(
        id = "rk-2",
        uriString = "content://tree/music/rock/track.mp3",
        title = "Bypassed Rock",
        artist = "Band",
        album = "Album",
        fileName = "track.mp3",
        folderPath = "Music/Rock",
        durationMs = 180_000L,
      )
    engine.replaceQueue(listOf(rockTrack), startIndex = 0, autoPlay = false)
    vm.toggleEqAuto()
    advanceUntilIdle()

    val eq = vm.playbackState.value.equalizer
    assertEquals(ClassicWinampPresets.ROCK.name, eq.activePresetName)
    assertFalse(eq.enabled)
  }

  @Test
  fun autoEq_disabledAutoDoesNotOverrideManualPresetOnTrackChange() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val vm = DroidAmpViewModel(engine, repo)
    val rockTrack =
      AudioTrack(
        id = "rk-3",
        uriString = "content://tree/music/rock/a.mp3",
        title = "Track A",
        artist = "Band",
        album = "Album",
        fileName = "a.mp3",
        folderPath = "Music/Rock",
        durationMs = 180_000L,
      )
    val jazzTrack =
      AudioTrack(
        id = "jz-1",
        uriString = "content://tree/music/pop/b.mp3",
        title = "Track B",
        artist = "Band",
        album = "Album",
        fileName = "b.mp3",
        folderPath = "Music/Pop",
        durationMs = 180_000L,
      )
    engine.replaceQueue(listOf(rockTrack, jazzTrack), startIndex = 0, autoPlay = false)
    vm.applyEqPreset(ClassicWinampPresets.CLASSICAL)
    assertFalse(vm.playbackState.value.equalizer.autoEnabled)

    vm.next()
    advanceUntilIdle()
    assertEquals(ClassicWinampPresets.CLASSICAL.name, vm.playbackState.value.equalizer.activePresetName)
  }

  @Test
  fun autoEq_rapidTrackSkipCancelsLoadCatalogWithoutSwallowingOrWipingExtraPresets() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val importedPreset =
      EqPreset(
        name = "IMPORTED_WINAMP",
        preampDb = 1.5f,
        bandsDb = listOf(4f, 3f, 2f, 0f, -1f, -1f, 0f, 2f, 3f, 4f),
      )
    val trackA = FakeMusicSourceRepository.DAFT_PUNK_TRACKS[0]
    val trackB = FakeMusicSourceRepository.DAFT_PUNK_TRACKS[1]

    val trackAEntered = CompletableDeferred<Unit>()
    val trackAGate = CompletableDeferred<Unit>()
    val trackBGate = CompletableDeferred<Unit>()
    var cancelledCount = 0

    val catalogProvider =
      object : AutoEqCatalogProvider {
        override suspend fun loadCatalog(
          linkedRootUris: List<String>,
          track: AudioTrack?,
        ): AutoEqCatalog =
          when (track?.id) {
            trackA.id -> {
              trackAEntered.complete(Unit)
              try {
                trackAGate.await()
                AutoEqCatalog()
              } catch (ce: CancellationException) {
                cancelledCount++
                throw ce
              }
            }
            trackB.id -> {
              trackBGate.await()
              AutoEqCatalog(
                extraPresets = listOf(importedPreset),
                rules = listOf(AutoEqRule(trackB.fileName, importedPreset)),
              )
            }
            else -> AutoEqCatalog(extraPresets = listOf(importedPreset))
          }
      }

    val vm =
      DroidAmpViewModel(
        audioEngine = engine,
        musicRepository = repo,
        autoEqResolver = AutoEqMatcher(),
        playlistCodec = WinampPlaylistCodec(),
        autoEqCatalogProvider = catalogProvider,
      )
    advanceUntilIdle()
    assertTrue(vm.uiState.value.availableEqPresets.any { it.name == importedPreset.name })

    val observedPresetSnapshots = mutableListOf<List<String>>()
    val collector =
      backgroundScope.launch {
        vm.uiState.collect { state ->
          observedPresetSnapshots.add(state.availableEqPresets.map { it.name })
        }
      }

    engine.replaceQueue(listOf(trackA, trackB), startIndex = 0, autoPlay = false)
    vm.toggleEqAuto()
    runCurrent()
    assertTrue(trackAEntered.isCompleted)

    // Rapid skip to trackB while trackA's loadCatalog is suspended -> must cancel cleanly
    // without swallowing CancellationException or wiping importedPreset while trackB is loading.
    vm.next()
    runCurrent()

    assertEquals(1, cancelledCount)
    assertTrue(vm.uiState.value.availableEqPresets.any { it.name == importedPreset.name })

    trackBGate.complete(Unit)
    advanceUntilIdle()

    assertTrue(vm.uiState.value.availableEqPresets.any { it.name == importedPreset.name })
    assertEquals(importedPreset.name, vm.playbackState.value.equalizer.activePresetName)
    assertTrue(observedPresetSnapshots.all { importedPreset.name in it })
    collector.cancel()
  }

  // --- Area B: [LIST] -> SAVE .M3U8 ---

  @Test
  fun savePlaylist_encodesCurrentQueueAsM3u8CallsRepoAndRefreshesPlaylistsTab() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = RecordingSaveMusicRepository()
    val vm = DroidAmpViewModel(engine, repo)
    val queue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS.take(3)
    engine.replaceQueue(queue, startIndex = 0, autoPlay = false)

    vm.savePlaylistAsM3u8(fileName = "night_drive.m3u8")
    advanceUntilIdle()

    assertEquals("night_drive.m3u8", repo.lastSavedFileName)
    val savedContent = repo.lastSavedContent!!
    assertTrue(savedContent.startsWith("#EXTM3U"))
    assertEquals(3, savedContent.lines().count { it.startsWith("#EXTINF:") })
    assertTrue(savedContent.contains(queue[0].uriString))
    assertEquals(3, vm.playbackState.value.queue.size)
    assertEquals(1, vm.uiState.value.playlists.size)
    assertEquals("night_drive", vm.uiState.value.playlists.first().name)
    assertNotNull(vm.uiState.value.statusBanner)
    assertTrue(vm.uiState.value.statusBanner!!.contains("SAVED", ignoreCase = true))
  }

  @Test
  fun savePlaylist_handlesRepositoryFailureGracefullyWithStatusBanner() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true) // inherits UnsupportedOperationException failure
    val vm = DroidAmpViewModel(engine, repo)
    val queue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS.take(3)
    engine.replaceQueue(queue, startIndex = 0, autoPlay = false)
    var fallbackTriggered = false

    vm.savePlaylistAsM3u8(onNeedDocumentPickerFallback = { _, _ -> fallbackTriggered = true })
    advanceUntilIdle()

    assertFalse(fallbackTriggered)
    assertEquals(3, vm.playbackState.value.queue.size)
    assertNotNull(vm.uiState.value.statusBanner)
    assertTrue(vm.uiState.value.statusBanner!!.isNotBlank())
  }

  @Test
  fun savePlaylist_triggersDocumentFallbackOnStorageIoFailure() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = RecordingSaveMusicRepository(saveFailure = IOException("Read-only tree"))
    val vm = DroidAmpViewModel(engine, repo)
    engine.replaceQueue(FakeMusicSourceRepository.DAFT_PUNK_TRACKS.take(2), startIndex = 0, autoPlay = false)
    var fallbackFileName: String? = null
    var fallbackContent: String? = null

    vm.savePlaylistAsM3u8(
      fileName = "export.m3u8",
      onNeedDocumentPickerFallback = { name, content ->
        fallbackFileName = name
        fallbackContent = content
      },
    )
    advanceUntilIdle()

    assertEquals("export.m3u8", fallbackFileName)
    assertTrue(fallbackContent?.startsWith("#EXTM3U") == true)

    vm.onM3u8FallbackExportResult(success = true, displayLabel = "export.m3u8")
    assertTrue(vm.uiState.value.statusBanner!!.contains("EXPORTED"))
  }

  @Test
  fun savePlaylist_emptyQueueIsSafeNoOpWithStatusBanner() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = RecordingSaveMusicRepository()
    val vm = DroidAmpViewModel(engine, repo)

    vm.savePlaylistAsM3u8()
    advanceUntilIdle()

    assertEquals(0, repo.saveCallCount)
    assertTrue(vm.playbackState.value.queue.isEmpty())
    assertTrue(vm.uiState.value.statusBanner!!.isNotBlank())
  }

  // --- Area C: Playlist Move/Reorder Bounds & Filtered Index Mapping ---

  @Test
  fun movePlaylistTrack_validAdjacentAndMultiRowMovesShiftQueueAndSelections() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val vm = DroidAmpViewModel(engine, repo)
    val tracks = FakeMusicSourceRepository.DAFT_PUNK_TRACKS // 4 tracks
    engine.replaceQueue(tracks, startIndex = 0, autoPlay = false)

    // Select {0, 1, 3}, then move 0 -> 2: queue becomes [1, 2, 0, 3], selections become {2, 0, 3}
    vm.onPlaylistTrackClick(0)
    vm.onPlaylistTrackClick(1)
    vm.onPlaylistTrackClick(3)
    vm.movePlaylistTrack(0, 2)

    assertEquals(
      listOf(tracks[1].id, tracks[2].id, tracks[0].id, tracks[3].id),
      vm.playbackState.value.queue.map { it.id },
    )
    assertEquals(setOf(2, 0, 3), vm.uiState.value.selectedPlaylistIndices)

    // Now move 3 -> 1: queue becomes [1, 3, 2, 0], selections {2, 0, 3} -> {3, 0, 1}
    vm.movePlaylistTrack(3, 1)
    assertEquals(
      listOf(tracks[1].id, tracks[3].id, tracks[2].id, tracks[0].id),
      vm.playbackState.value.queue.map { it.id },
    )
    assertEquals(setOf(3, 0, 1), vm.uiState.value.selectedPlaylistIndices)
  }

  @Test
  fun movePlaylistTrack_outOfBoundsAndNoOpMovesLeaveQueueAndSelectionUnchanged() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val vm = DroidAmpViewModel(engine, repo)
    val tracks = FakeMusicSourceRepository.DAFT_PUNK_TRACKS.take(3)
    engine.replaceQueue(tracks, startIndex = 0, autoPlay = false)
    vm.onPlaylistTrackClick(0)

    vm.movePlaylistTrack(0, -1)
    vm.movePlaylistTrack(-1, 0)
    vm.movePlaylistTrack(2, 3)
    vm.movePlaylistTrack(1, 1)

    assertEquals(tracks.map { it.id }, vm.playbackState.value.queue.map { it.id })
    assertEquals(0, vm.playbackState.value.currentIndex)
    assertEquals(setOf(0), vm.uiState.value.selectedPlaylistIndices)
  }

  @Test
  fun jumpFilter_preservesOriginalQueueIndicesForSelectionDoubleClickAndMove() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val vm = DroidAmpViewModel(engine, repo)
    val tracks = FakeMusicSourceRepository.DAFT_PUNK_TRACKS
    engine.replaceQueue(tracks, startIndex = 0, autoPlay = false)

    vm.toggleJumpFilter()
    vm.onJumpFilterQueryChange("03_digital")
    val filtered = buildPlaylistDisplayEntries(vm.playbackState.value.queue, vm.uiState.value.jumpFilterQuery)

    assertEquals(1, filtered.size)
    assertEquals(2, filtered.first().originalIndex)
    assertEquals("trk-dp-3", filtered.first().track.id)

    vm.onPlaylistTrackDoubleClick(filtered.first().originalIndex)
    assertEquals(2, vm.playbackState.value.currentIndex)
    assertEquals(PlaybackStatus.PLAYING, vm.playbackState.value.status)

    vm.toggleJumpFilter()
    assertEquals("", vm.uiState.value.jumpFilterQuery)
  }

  // --- Area D: SAF Folder Selection & Select-First Safety ---

  @Test
  fun selectMusicFolder_failureOrInaccessibleUriKeepsSafeStateAndReportsBanner() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val failingRepo = RecordingSaveMusicRepository(linkFailure = IllegalStateException("Inaccessible tree"))
    val vm = DroidAmpViewModel(engine, failingRepo)
    advanceUntilIdle()

    vm.onSelectMusicFolder("content://bad/tree")
    advanceUntilIdle()

    assertTrue(vm.linkedRoots.value.isEmpty())
    assertEquals(FolderContents.EMPTY, vm.uiState.value.folderContents)
    assertTrue(vm.uiState.value.statusBanner!!.isNotBlank())
  }

  @Test
  fun selectMusicFolder_silentRejectionWithExistingRootReportsFailureBannerInsteadOfLinked() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val rootA =
      FolderEntry(
        uriString = "content://good/rootA",
        name = "GoodRoot",
        pathSegments = listOf("GoodRoot"),
        trackCount = 1,
        subfolderCount = 0,
      )
    val rootAContents =
      FolderContents(
        currentFolderUri = rootA.uriString,
        currentPathSegments = listOf("GoodRoot"),
        canNavigateUp = false,
        parentFolderUri = null,
        folders = emptyList(),
        tracks = FakeMusicSourceRepository.ROOT_TRACKS,
      )
    val repo =
      RecordingSaveMusicRepository(
        initialRoots = listOf(rootA),
        initialFolderContents = rootAContents,
        silentlyRejectedUris = setOf("content://denied/rootB"),
      )
    val vm = DroidAmpViewModel(engine, repo)
    advanceUntilIdle()

    assertEquals(listOf("content://good/rootA"), vm.linkedRoots.value.map { it.uriString })
    assertEquals(rootAContents, vm.uiState.value.folderContents)

    vm.onSelectMusicFolder("content://denied/rootB")
    advanceUntilIdle()

    assertEquals(listOf("content://good/rootA"), vm.linkedRoots.value.map { it.uriString })
    assertEquals(
      "UNABLE TO LINK MUSIC FOLDER — SELECT A READABLE FOLDER",
      vm.uiState.value.statusBanner,
    )
    assertFalse(vm.uiState.value.isSheetExpanded)
    assertEquals(rootAContents, vm.uiState.value.folderContents)

    // Linking a valid additional root still succeeds
    vm.onSelectMusicFolder("content://good/rootC")
    advanceUntilIdle()

    assertEquals(
      listOf("content://good/rootA", "content://good/rootC"),
      vm.linkedRoots.value.map { it.uriString },
    )
    assertEquals("LINKED MUSIC FOLDER (SAF)", vm.uiState.value.statusBanner)
    assertTrue(vm.uiState.value.isSheetExpanded)
  }

  @Test
  fun selectFirstSafety_multiSelectPlayPrimaryAndEnqueuePrimary() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val vm = DroidAmpViewModel(engine, repo)
    advanceUntilIdle()

    val rootTracks = vm.uiState.value.folderContents.tracks
    assertTrue(rootTracks.isNotEmpty())
    val existing = FakeMusicSourceRepository.DAFT_PUNK_TRACKS
    engine.replaceQueue(existing, startIndex = 0, autoPlay = true)

    vm.toggleLibraryTrackSelection(rootTracks.first())
    assertEquals(4, vm.playbackState.value.queue.size)

    vm.enqueuePrimaryLibrarySelection()
    assertEquals(5, vm.playbackState.value.queue.size)
    assertTrue(vm.uiState.value.selectedLibraryTrackIds.isEmpty())

    vm.toggleLibraryTrackSelection(rootTracks.first())
    vm.playPrimaryLibrarySelection()
    assertEquals(listOf(rootTracks.first()), vm.playbackState.value.queue)
    assertEquals(SheetMode.PLAYLIST, vm.uiState.value.sheetMode)
  }

  // --- Area E: Milestone 5 Wrong-Slot Redirects & Inform+Override Skin Recovery ---

  @Test
  fun wrongSlotRedirect_eqfQ1Q2IniAndM3uApplyDomainActionsAndDismissNotice() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = true)
    val skinRepo = RecordingSkinRepository()
    val eqfCodec = com.droidamp.player.interop.BinaryEqfCodec()
    val q2Codec = WinampQ2Codec()
    val iniCodec = com.droidamp.player.interop.WinampIniParser()
    val playlistCodec = WinampPlaylistCodec()
    val vm =
      DroidAmpViewModel(
        audioEngine = engine,
        musicRepository = repo,
        skinRepository = skinRepo,
        eqfCodec = eqfCodec,
        q2Codec = q2Codec,
        iniCodec = iniCodec,
      )
    advanceUntilIdle()

    // 1. Single .eqf redirect -> merges preset, applies to EQ, dismisses notice
    val customPreset =
      EqPreset(
        name = "Cyber Bass",
        preampDb = 2.0f,
        bandsDb = listOf(6f, 5f, 3f, 0f, -2f, -2f, 0f, 2f, 4f, 5f),
      )
    val eqfNotice =
      com.droidamp.player.skin.SkinRecoveryNotice.WrongSlotFileDetected(
        uriString = "content://downloads/cyber_bass.eqf",
        fileName = "cyber_bass.eqf",
        detectedFormat = com.droidamp.player.interop.DetectedWinampFormat.EqfSinglePreset,
        rawBytes = eqfCodec.encodeSingleEqf(customPreset),
      )
    skinRepo.emitNotice(eqfNotice)
    vm.confirmWrongSlotRedirect(eqfNotice)
    advanceUntilIdle()

    org.junit.Assert.assertNull(vm.skinRecoveryNotice.value)
    assertEquals("Cyber Bass", vm.playbackState.value.equalizer.activePresetName)
    assertTrue(vm.uiState.value.availableEqPresets.any { it.name == "Cyber Bass" })
    assertEquals("APPLIED EQ PRESET: CYBER BASS", vm.uiState.value.statusBanner)

    // 2. Multi-preset .q1 redirect -> loads presets, applies first, dismisses notice
    val q1Presets =
      listOf(
        EqPreset("Warm Tape", 1.0f, List(10) { 1.5f }),
        EqPreset("Crisp Studio", 0.0f, List(10) { 2.5f }),
      )
    val q1Notice =
      com.droidamp.player.skin.SkinRecoveryNotice.WrongSlotFileDetected(
        uriString = "content://downloads/winamp.q1",
        fileName = "winamp.q1",
        detectedFormat = com.droidamp.player.interop.DetectedWinampFormat.EqLibraryQ1,
        rawBytes = eqfCodec.encodePresetLibraryQ1(q1Presets),
      )
    skinRepo.emitNotice(q1Notice)
    vm.confirmWrongSlotRedirect(q1Notice)
    advanceUntilIdle()

    org.junit.Assert.assertNull(vm.skinRecoveryNotice.value)
    assertEquals("Warm Tape", vm.playbackState.value.equalizer.activePresetName)
    assertTrue(vm.uiState.value.availableEqPresets.any { it.name == "Crisp Studio" })
    assertEquals("LOADED 2 PRESETS (.Q1)", vm.uiState.value.statusBanner)

    // 3. Auto-EQ .q2 redirect -> feeds live observeAutoEqMatching()
    val q2Rules = listOf(AutoEqRule("01_one_more_time.flac", ClassicWinampPresets.TECHNO))
    val q2Notice =
      com.droidamp.player.skin.SkinRecoveryNotice.WrongSlotFileDetected(
        uriString = "content://downloads/winamp.q2",
        fileName = "winamp.q2",
        detectedFormat = com.droidamp.player.interop.DetectedWinampFormat.AutoEqQ2,
        rawBytes = q2Codec.encodeAutoPresetsQ2(q2Rules),
      )
    engine.replaceQueue(FakeMusicSourceRepository.DAFT_PUNK_TRACKS, startIndex = 0, autoPlay = false)
    engine.setEqAutoEnabled(true)
    advanceUntilIdle()
    skinRepo.emitNotice(q2Notice)
    vm.confirmWrongSlotRedirect(q2Notice)
    advanceUntilIdle()

    org.junit.Assert.assertNull(vm.skinRecoveryNotice.value)
    assertEquals("LOADED 1 AUTO-EQ RULES (.Q2)", vm.uiState.value.statusBanner)
    assertEquals("01_one_more_time.flac", vm.playbackState.value.equalizer.activePresetName)

    // 4. winamp.ini redirect -> applies volume, pan, shuffle, repeat, timeMode, EQ & DSP settings
    val iniSnapshot =
      com.droidamp.player.domain.contract.WinampIniSnapshot(
        volume = 180,
        pan = -32,
        shuffle = true,
        repeat = true,
        timeRemaining = true,
        eqEnabled = false,
        eqAuto = false,
        eqPreampDb = 3.0f,
        eqBandsDb = List(10) { -1.5f },
        replayGainMode = 2,
        replayGainPreampDb = 1.5f,
        crossfadeSeconds = 4,
      )
    val iniNotice =
      com.droidamp.player.skin.SkinRecoveryNotice.WrongSlotFileDetected(
        uriString = "content://downloads/winamp.ini",
        fileName = "winamp.ini",
        detectedFormat = com.droidamp.player.interop.DetectedWinampFormat.WinampIni,
        rawBytes = iniCodec.encode(iniSnapshot).toByteArray(Charsets.UTF_8),
      )
    skinRepo.emitNotice(iniNotice)
    vm.confirmWrongSlotRedirect(iniNotice)
    advanceUntilIdle()

    org.junit.Assert.assertNull(vm.skinRecoveryNotice.value)
    assertEquals(180, vm.playbackState.value.volume)
    assertEquals(-32, vm.playbackState.value.balance)
    assertTrue(vm.playbackState.value.shuffle)
    assertEquals(com.droidamp.player.domain.model.RepeatMode.ALL, vm.playbackState.value.repeatMode)
    assertEquals(
      com.droidamp.player.domain.model.TimeDisplayMode.REMAINING,
      vm.playbackState.value.timeDisplayMode,
    )
    assertFalse(vm.playbackState.value.equalizer.enabled)
    assertEquals(
      com.droidamp.player.domain.model.ReplayGainMode.ALBUM,
      engine.dspSettings.value.replayGainMode,
    )
    assertEquals(4, engine.dspSettings.value.crossfadeSeconds)
    assertEquals("APPLIED WINAMP.INI SETTINGS", vm.uiState.value.statusBanner)

    // 5. Playlist .m3u redirect -> resolves local tracks and switches to QUEUE
    val targetTracks = FakeMusicSourceRepository.DAFT_PUNK_TRACKS.take(2)
    val m3uBytes = playlistCodec.encodeM3u8(targetTracks).toByteArray(Charsets.UTF_8)
    val m3uNotice =
      com.droidamp.player.skin.SkinRecoveryNotice.WrongSlotFileDetected(
        uriString = "content://downloads/favorites.m3u8",
        fileName = "favorites.m3u8",
        detectedFormat = com.droidamp.player.interop.DetectedWinampFormat.PlaylistM3u,
        rawBytes = m3uBytes,
      )
    vm.switchSheetMode(SheetMode.LIBRARY)
    skinRepo.emitNotice(m3uNotice)
    vm.confirmWrongSlotRedirect(m3uNotice)
    advanceUntilIdle()

    org.junit.Assert.assertNull(vm.skinRecoveryNotice.value)
    assertEquals(SheetMode.PLAYLIST, vm.uiState.value.sheetMode)
    assertEquals(targetTracks.map { it.id }, vm.playbackState.value.queue.map { it.id })
  }

  @Test
  fun skinRecoveryActions_overrideRawColorsLegacyRenderModeAndDuplicateResolution() = runTest {
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val repo = FakeMusicSourceRepository(startLinked = false)
    val skinRepo = RecordingSkinRepository()
    val vm = DroidAmpViewModel(audioEngine = engine, musicRepository = repo, skinRepository = skinRepo)
    advanceUntilIdle()

    vm.setLegacySkinRenderMode(com.droidamp.player.skin.SkinRenderMode.CLASSIC_RAW_BMP)
    assertEquals(
      com.droidamp.player.skin.SkinRenderMode.CLASSIC_RAW_BMP,
      vm.skinSettings.value.legacyRenderMode,
    )

    vm.setAutoContrastGuard(false)
    assertFalse(vm.skinSettings.value.autoContrastGuard)

    skinRepo.emitNotice(
      com.droidamp.player.skin.SkinRecoveryNotice.ContrastGuardApplied(
        skinId = "legacy-amp",
        skinName = "Legacy Amp",
        adjustedTokenCount = 3,
      ),
    )
    vm.useRawOgColorsForSkin("legacy-amp")
    assertTrue("legacy-amp" in vm.skinSettings.value.rawColorOverrideSkinIds)
    org.junit.Assert.assertNull(vm.skinRecoveryNotice.value)

    val existing =
      com.droidamp.player.skin.SkinEntry(
        id = "imp-1",
        displayName = "Cyber Ice",
        source = com.droidamp.player.skin.SkinSource.IMPORTED,
        origin = com.droidamp.player.skin.SkinOrigin.AUGMENTED_WSZ,
      )
    val conflict =
      com.droidamp.player.skin.SkinRecoveryNotice.DuplicateSkinConflict(
        uriString = "content://downloads/cyber.wsz",
        existingEntry = existing,
        proposedFileName = "cyber.wsz",
        rawBytes = byteArrayOf(1, 2, 3),
      )
    skinRepo.emitNotice(conflict)
    vm.resolveDuplicateSkinImport(conflict, replaceExisting = true)
    advanceUntilIdle()

    assertEquals(true, skinRepo.lastReplaceExisting)
    org.junit.Assert.assertNull(vm.skinRecoveryNotice.value)
  }

  private class RecordingSkinRepository : com.droidamp.player.skin.SkinRepository {
    private val _availableSkins = MutableStateFlow<List<com.droidamp.player.skin.SkinEntry>>(emptyList())
    override val availableSkins: StateFlow<List<com.droidamp.player.skin.SkinEntry>> =
      _availableSkins.asStateFlow()

    private val _activeSkin =
      MutableStateFlow<com.droidamp.player.skin.DroidAmpSkin>(com.droidamp.player.skin.ClassicDroidAmpSkin)
    override val activeSkin: StateFlow<com.droidamp.player.skin.DroidAmpSkin> = _activeSkin.asStateFlow()

    private val _skinSettings = MutableStateFlow(com.droidamp.player.skin.SkinSettingsState())
    override val skinSettings: StateFlow<com.droidamp.player.skin.SkinSettingsState> =
      _skinSettings.asStateFlow()

    private val _recoveryNotice = MutableStateFlow<com.droidamp.player.skin.SkinRecoveryNotice?>(null)
    override val recoveryNotice: StateFlow<com.droidamp.player.skin.SkinRecoveryNotice?> =
      _recoveryNotice.asStateFlow()

    var lastReplaceExisting: Boolean? = null

    fun emitNotice(notice: com.droidamp.player.skin.SkinRecoveryNotice?) {
      _recoveryNotice.value = notice
    }

    override fun refreshSkins(linkedRootUris: List<String>) = Unit
    override fun selectSkin(skinId: String) = Unit
    override suspend fun importSkinFromUri(uriString: String): Result<com.droidamp.player.skin.SkinEntry?> =
      Result.success(null)

    override suspend fun resolveDuplicateImport(
      conflict: com.droidamp.player.skin.SkinRecoveryNotice.DuplicateSkinConflict,
      replaceExisting: Boolean,
    ): Result<com.droidamp.player.skin.SkinEntry> {
      lastReplaceExisting = replaceExisting
      _recoveryNotice.value = null
      return Result.success(conflict.existingEntry)
    }

    override suspend fun deleteImportedSkin(skinId: String): Boolean = true

    override fun setLegacyRenderMode(mode: com.droidamp.player.skin.SkinRenderMode) {
      _skinSettings.value = _skinSettings.value.copy(legacyRenderMode = mode)
    }

    override fun setAutoContrastGuard(enabled: Boolean) {
      _skinSettings.value = _skinSettings.value.copy(autoContrastGuard = enabled)
    }

    override fun setSkinRawColorsOverride(skinId: String, useRawColors: Boolean) {
      val updated =
        if (useRawColors) {
          _skinSettings.value.rawColorOverrideSkinIds + skinId
        } else {
          _skinSettings.value.rawColorOverrideSkinIds - skinId
        }
      _skinSettings.value = _skinSettings.value.copy(rawColorOverrideSkinIds = updated)
    }

    override fun retrySafeBootSkin(skinId: String) {
      _recoveryNotice.value = null
    }

    override fun revertToSkin(skinId: String) {
      _recoveryNotice.value = null
    }

    override fun dismissRecoveryNotice() {
      _recoveryNotice.value = null
    }
  }

  private class RecordingSaveMusicRepository(
    private val saveFailure: Throwable? = null,
    private val linkFailure: Throwable? = null,
    initialRoots: List<FolderEntry> = emptyList(),
    private val initialFolderContents: FolderContents = FolderContents.EMPTY,
    private val silentlyRejectedUris: Set<String> = emptySet(),
  ) : MusicSourceRepository {
    private val _storageBackend = MutableStateFlow(StorageBackend.SAF)
    override val storageBackend: StateFlow<StorageBackend> = _storageBackend.asStateFlow()

    private val _linkedRoots = MutableStateFlow(initialRoots)
    override val linkedRoots: StateFlow<List<FolderEntry>> = _linkedRoots.asStateFlow()

    private val savedPlaylists = mutableListOf<SavedPlaylistEntry>()
    var saveCallCount: Int = 0
    var lastSavedFileName: String? = null
    var lastSavedContent: String? = null

    override suspend fun setStorageBackend(backend: StorageBackend) {
      _storageBackend.value = backend
    }

    override suspend fun linkSafRoot(treeUriString: String) {
      linkFailure?.let { throw it }
      val trimmed = treeUriString.trim()
      if (trimmed.isEmpty() || trimmed in silentlyRejectedUris) {
        return
      }
      if (_linkedRoots.value.none { it.uriString == trimmed }) {
        _linkedRoots.value =
          _linkedRoots.value +
          FolderEntry(
            uriString = trimmed,
            name = trimmed.substringAfterLast('/'),
            pathSegments = listOf(trimmed.substringAfterLast('/')),
            trackCount = 0,
            subfolderCount = 0,
          )
      }
    }

    override suspend fun unlinkAllRoots() {
      _linkedRoots.value = emptyList()
    }

    override suspend fun browseFolder(folderUriString: String?): FolderContents = initialFolderContents

    override suspend fun resolveFolderTracks(folderUriString: String, recursive: Boolean): List<AudioTrack> =
      emptyList()

    override suspend fun listPlaylists(): List<SavedPlaylistEntry> = savedPlaylists.toList()

    override suspend fun resolvePlaylistTracks(playlistId: String): List<AudioTrack> = emptyList()

    override suspend fun saveM3u8Playlist(fileName: String, content: String): Result<SavedPlaylistEntry> {
      saveCallCount++
      lastSavedFileName = fileName
      lastSavedContent = content
      saveFailure?.let { return Result.failure(it) }
      val entry =
        SavedPlaylistEntry(
          id = "saved://$fileName",
          uriString = "saved://$fileName",
          name = fileName.substringBeforeLast('.'),
          format = "m3u8",
          trackCount = content.lines().count { it.startsWith("#EXTINF:") },
          totalDurationMs = 0L,
        )
      savedPlaylists.add(entry)
      return Result.success(entry)
    }
  }
}
