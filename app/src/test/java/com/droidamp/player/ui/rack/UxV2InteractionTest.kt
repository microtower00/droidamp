package com.droidamp.player.ui.rack

import com.droidamp.player.audio.dsp.LockFreeAudioRingBuffer
import com.droidamp.player.audio.dsp.VisualizerTapProcessor
import com.droidamp.player.domain.model.LibraryViewLevel
import com.droidamp.player.domain.model.PlaylistExportFormat
import com.droidamp.player.domain.model.SheetMode
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UxV2InteractionTest {
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
  fun cursorTrackedVisualizerAdvancesSmoothlyAcrossBufferedBurst() {
    var fakeNowNanos = 1_000_000_000L
    val ring = LockFreeAudioRingBuffer(capacity = 32768)
    // Write a 6000-sample ramp so different time windows have distinct waveform tails.
    val ramp = FloatArray(6000) { i -> ((i % 2000) / 1000f) - 1f }
    ring.writeMono(ramp)

    val processor = VisualizerTapProcessor(ringBuffer = ring, nanoTimeProvider = { fakeNowNanos })
    val frame1 = processor.readVisualizerFrame(isPlaying = true)
    fakeNowNanos += 16_000_000L // +16ms (~705 samples at 44.1kHz)
    val frame2 = processor.readVisualizerFrame(isPlaying = true)

    assertFalse(
      "Consecutive frames should advance through the buffered burst instead of stalling",
      frame1.waveform.contentEquals(frame2.waveform),
    )
  }

  @Test
  fun queueAndEjectButtonsRouteDirectlyAndBackStepsUpInLibrary() = runTest(testDispatcher) {
    val repo = FakeMusicSourceRepository(startLinked = true)
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val vm = DroidAmpViewModel(engine, repo)
    advanceUntilIdle()

    // Deck QUEUE opens directly to QUEUE
    vm.onQueueButtonPressed()
    assertTrue(vm.uiState.value.isSheetExpanded)
    assertEquals(SheetMode.PLAYLIST, vm.uiState.value.sheetMode)

    // Deck Eject opens directly to LIBRARY
    vm.onEjectPressed()
    assertTrue(vm.uiState.value.isSheetExpanded)
    assertEquals(SheetMode.LIBRARY, vm.uiState.value.sheetMode)
    assertEquals(LibraryViewLevel.ROOT, vm.uiState.value.libraryViewLevel)

    // Drill into FOLDERS -> Back steps up to ROOT -> Back collapses drawer
    vm.openLibraryFolders()
    assertEquals(LibraryViewLevel.FOLDERS, vm.uiState.value.libraryViewLevel)

    vm.onBackPressedInExpandedSheet()
    assertTrue(vm.uiState.value.isSheetExpanded)
    assertEquals(LibraryViewLevel.ROOT, vm.uiState.value.libraryViewLevel)

    vm.onBackPressedInExpandedSheet()
    assertFalse(vm.uiState.value.isSheetExpanded)
  }

  @Test
  fun enqueueFromLibraryStaysInLibraryAndEmitsFeedbackPulse() = runTest(testDispatcher) {
    val repo = FakeMusicSourceRepository(startLinked = true)
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val vm = DroidAmpViewModel(engine, repo)
    advanceUntilIdle()

    vm.onEjectPressed()
    vm.openLibraryFolders()
    val firstFolder = vm.uiState.value.folderContents.folders.first()
    vm.openFolder(firstFolder)
    advanceUntilIdle()

    val track = vm.uiState.value.folderContents.tracks.first()
    val pulseBefore = vm.uiState.value.queueFeedbackPulseCount
    vm.enqueueSingleLibraryTrack(track)

    assertEquals(SheetMode.LIBRARY, vm.uiState.value.sheetMode)
    assertEquals(pulseBefore + 1, vm.uiState.value.queueFeedbackPulseCount)
    assertNotNull(vm.uiState.value.libraryFeedbackBanner)
  }

  @Test
  fun clearQueueConfirmAndSavePlaylistDialogWork() = runTest(testDispatcher) {
    val repo = FakeMusicSourceRepository(startLinked = true)
    val engine = FakeAudioEngineController(backgroundScope, enableSimulationTicker = false)
    val vm = DroidAmpViewModel(engine, repo)
    advanceUntilIdle()

    // Seed queue with 1 track
    val firstFolder = vm.uiState.value.folderContents.folders.first()
    vm.playFolderAsPlaylist(firstFolder)
    advanceUntilIdle()
    assertTrue(vm.playbackState.value.queue.isNotEmpty())

    // Save Playlist dialog opens & triggers fallback in FakeMusicSourceRepository
    vm.openSavePlaylistDialog()
    assertTrue(vm.uiState.value.showSavePlaylistDialog)
    var fallbackName: String? = null
    vm.saveQueueAsPlaylist(
      baseName = "road_trip",
      format = PlaylistExportFormat.PLS,
      selectedOnly = false,
      onNeedDocumentPickerFallback = { name, _ -> fallbackName = name },
    )
    advanceUntilIdle()
    assertFalse(vm.uiState.value.showSavePlaylistDialog)
    // FakeMusicSourceRepository throws UnsupportedOperationException, so fallback is skipped cleanly
    assertEquals(null, fallbackName)

    // Clear Queue confirmation flow
    vm.requestClearQueue()
    assertTrue(vm.uiState.value.showClearQueueConfirm)
    vm.confirmClearQueue()
    assertFalse(vm.uiState.value.showClearQueueConfirm)
    assertTrue(vm.playbackState.value.queue.isEmpty())
  }
}
