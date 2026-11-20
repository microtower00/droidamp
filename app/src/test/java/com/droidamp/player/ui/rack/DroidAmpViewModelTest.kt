package com.droidamp.player.ui.rack

import com.droidamp.player.domain.model.LibraryTab
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.SheetMode
import com.droidamp.player.domain.model.WinampEqSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
}
