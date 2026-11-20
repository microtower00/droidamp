package com.droidamp.player.e2e.harness

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.droidamp.player.MainActivity
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.SheetMode
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.rack.DroidAmpRackScreen
import com.droidamp.player.ui.rack.DroidAmpViewModel
import com.droidamp.player.ui.rack.FakeAudioEngineController
import com.droidamp.player.ui.rack.FakeMusicSourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Safe E2E rack driver supporting both:
 * 1. Opaque-box [MainActivity] tests (`fromMainActivity`), and
 * 2. Isolated [DroidAmpRackScreen] + [DroidAmpViewModel] tests (`launchIsolatedRack`)
 *    backed by [FakeAudioEngineController] & [FakeMusicSourceRepository].
 *
 * Does NOT directly implement AudioEngineController or MusicSourceRepository so tests compile
 * cleanly on M1 and survive M2–M4 interface extensions without edits.
 */
open class E2eRackDriver(
  val rule: ComposeTestRule,
) {

  fun waitForIdle() {
    rule.waitForIdle()
  }

  // --- Sheet Expansion / Collapse ---

  fun expandSheetByTitleTap() {
    DroidAmpSelectors.peekingSheetTitleBar(rule).performClick()
    rule.waitForIdle()
  }

  fun expandSheetBySwipeUp() {
    val titleBounds = DroidAmpSelectors.peekingSheetTitleBar(rule).fetchSemanticsNode().boundsInRoot
    val deltaPx = with(rule.density) { 120.dp.toPx() }
    rule.onRoot().performTouchInput {
      swipe(
        start = titleBounds.center,
        end = Offset(titleBounds.center.x, (titleBounds.center.y - deltaPx).coerceAtLeast(4f)),
        durationMillis = 180L,
      )
    }
    rule.waitForIdle()
  }

  fun collapseSheetByTitleTap() {
    DroidAmpSelectors.expandedSheetTitleBar(rule).performClick()
    rule.waitForIdle()
  }

  fun collapseSheetBySwipeDown() {
    val titleBounds = DroidAmpSelectors.expandedSheetTitleBar(rule).fetchSemanticsNode().boundsInRoot
    val deltaPx = with(rule.density) { 120.dp.toPx() }
    rule.onRoot().performTouchInput {
      swipe(
        start = titleBounds.center,
        end = Offset(titleBounds.center.x, titleBounds.center.y + deltaPx),
        durationMillis = 180L,
      )
    }
    rule.waitForIdle()
  }

  fun ensureSheetExpanded(mode: SheetMode? = null) {
    val isExpanded =
      rule.onAllNodes(
        hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"),
      ).fetchSemanticsNodes().isNotEmpty()
    if (!isExpanded) {
      if (mode == SheetMode.LIBRARY) {
        DroidAmpSelectors.deckEjectButton(rule).performClick()
        rule.waitForIdle()
        return
      }
      expandSheetByTitleTap()
    }
    if (mode == SheetMode.LIBRARY) {
      val inLibrary =
        rule.onAllNodesWithText("DROIDAMP LIBRARY ▼").fetchSemanticsNodes().isNotEmpty()
      if (!inLibrary) {
        val hasLibButton =
          rule.onAllNodes(hasText("LIB") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        if (hasLibButton) {
          DroidAmpSelectors.playlistLibButton(rule).performClick()
        } else {
          // EmptyFolderWell is active in PLAYLIST mode (no "LIB" button): collapse and tap deck Eject (⏏).
          collapseSheetByTitleTap()
          DroidAmpSelectors.deckEjectButton(rule).performClick()
        }
        rule.waitForIdle()
      }
    } else if (mode == SheetMode.PLAYLIST) {
      val inPlaylist =
        rule.onAllNodesWithText("DROIDAMP PLAYLIST ▼").fetchSemanticsNodes().isNotEmpty()
      if (!inPlaylist) {
        DroidAmpSelectors.libraryPlButton(rule).performClick()
        rule.waitForIdle()
      }
    }
  }

  fun ensureSheetCollapsed() {
    val isExpanded =
      rule.onAllNodes(
        hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"),
      ).fetchSemanticsNodes().isNotEmpty()
    if (isExpanded) {
      collapseSheetByTitleTap()
    }
  }

  // --- Main Deck Interactions ---

  fun clickPlayOnDeck() {
    ensureSheetCollapsed()
    DroidAmpSelectors.deckPlayButton(rule).performClick()
    rule.waitForIdle()
  }

  fun clickPauseOnDeck() {
    ensureSheetCollapsed()
    DroidAmpSelectors.deckPauseButton(rule).performClick()
    rule.waitForIdle()
  }

  fun clickStopOnDeck() {
    ensureSheetCollapsed()
    DroidAmpSelectors.deckStopButton(rule).performClick()
    rule.waitForIdle()
  }

  fun clickNextOnDeck() {
    ensureSheetCollapsed()
    DroidAmpSelectors.deckNextButton(rule).performClick()
    rule.waitForIdle()
  }

  fun clickPrevOnDeck() {
    ensureSheetCollapsed()
    DroidAmpSelectors.deckPrevButton(rule).performClick()
    rule.waitForIdle()
  }

  fun clickEjectOnDeck() {
    ensureSheetCollapsed()
    DroidAmpSelectors.deckEjectButton(rule).performClick()
    rule.waitForIdle()
  }

  fun toggleClockMode() {
    ensureSheetCollapsed()
    DroidAmpSelectors.clockButton(rule).performClick()
    rule.waitForIdle()
  }

  fun cycleVisualizerMode() {
    ensureSheetCollapsed()
    DroidAmpSelectors.visualizerButton(rule).performClick()
    rule.waitForIdle()
  }

  fun toggleShuffle() {
    ensureSheetCollapsed()
    DroidAmpSelectors.shuffleButton(rule).performClick()
    rule.waitForIdle()
  }

  fun cycleRepeatMode() {
    ensureSheetCollapsed()
    DroidAmpSelectors.repeatButton(rule).performClick()
    rule.waitForIdle()
  }

  fun toggleEqVisibility() {
    ensureSheetCollapsed()
    DroidAmpSelectors.deckEqToggleButton(rule).performClick()
    rule.waitForIdle()
  }

  fun setVolumeFraction(fraction: Float) {
    ensureSheetCollapsed()
    DroidAmpSelectors.setVolumeFraction(rule, fraction)
    rule.waitForIdle()
  }

  fun setBalanceFraction(fraction: Float) {
    ensureSheetCollapsed()
    DroidAmpSelectors.setBalanceFraction(rule, fraction)
    rule.waitForIdle()
  }

  fun seekToFraction(fraction: Float) {
    ensureSheetCollapsed()
    DroidAmpSelectors.seekToFraction(rule, fraction)
    rule.waitForIdle()
  }

  // --- Equalizer & RetroMicroMenuPopup Interactions ---

  fun toggleEqEnabled() {
    ensureSheetCollapsed()
    DroidAmpSelectors.eqOnButton(rule).performClick()
    rule.waitForIdle()
  }

  fun toggleEqAuto() {
    ensureSheetCollapsed()
    DroidAmpSelectors.eqAutoButton(rule).performClick()
    rule.waitForIdle()
  }

  fun setPreampDb(db: Float) {
    ensureSheetCollapsed()
    DroidAmpSelectors.setPreampDb(rule, db)
    rule.waitForIdle()
  }

  fun setEqBandDb(bandIndex: Int, db: Float) {
    ensureSheetCollapsed()
    DroidAmpSelectors.setEqBandDb(rule, bandIndex, db)
    rule.waitForIdle()
  }

  fun selectEqPreset(presetName: String) {
    ensureSheetCollapsed()
    DroidAmpSelectors.eqPresetsButton(rule).performClick()
    clickPopupMenuAction(expectedPopupTitle = "EQ PRESETS", actionLabel = presetName)
  }

  /**
   * Opens a RetroMicroMenuPopup action cleanly across separate Compose Popup window roots.
   * Calls performScrollTo() only when a scrollable container exists inside the Popup
   * (so it works both on M1's static Column and M-integration's scrollable popup).
   */
  fun clickPopupMenuAction(expectedPopupTitle: String, actionLabel: String) {
    rule.waitForIdle()
    rule.waitUntil(timeoutMillis = 3_000L) {
      rule.onAllNodes(
        hasText(expectedPopupTitle.uppercase()) and hasAnyAncestor(isPopup()),
      ).fetchSemanticsNodes().isNotEmpty()
    }
    val item = DroidAmpSelectors.popupMenuAction(rule, actionLabel)
    val hasScrollable = DroidAmpSelectors.popupScrollables(rule).fetchSemanticsNodes().isNotEmpty()
    if (hasScrollable) {
      item.performScrollTo()
    }
    item.performClick()
    rule.waitForIdle()
  }

  // --- Library & Playlist Sheet Interactions ---

  fun clickSelectMusicFolder() {
    ensureSheetExpanded()
    DroidAmpSelectors.selectMusicFolderButton(rule).performClick()
    rule.waitForIdle()
  }

  fun setJumpFilterQuery(query: String) {
    ensureSheetExpanded(SheetMode.PLAYLIST)
    val inputExists =
      rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
    if (!inputExists) {
      DroidAmpSelectors.playlistMiscButton(rule).performClick()
      clickPopupMenuAction(expectedPopupTitle = "MISC OPTS", actionLabel = "JUMP / FILTER")
    }
    val input = DroidAmpSelectors.jumpFilterInput(rule)
    input.performTextClearance()
    if (query.isNotEmpty()) {
      input.performTextInput(query)
    }
    rule.waitForIdle()
  }

  fun clickPlaylistMenuAction(
    menuButton: SemanticsNodeInteraction,
    popupTitle: String,
    actionLabel: String,
  ) {
    ensureSheetExpanded(SheetMode.PLAYLIST)
    menuButton.performClick()
    clickPopupMenuAction(expectedPopupTitle = popupTitle, actionLabel = actionLabel)
  }

  companion object {
    fun fromMainActivity(
      rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
    ): E2eRackDriver = E2eRackDriver(rule)

    fun launchIsolatedRack(
      rule: ComposeContentTestRule,
      startLinked: Boolean = false,
      enableSimulationTicker: Boolean = false,
      initialQueue: List<AudioTrack> = emptyList(),
      autoPlay: Boolean = false,
      initialVolume: Int? = null,
      prelinkedRootUri: String? = null,
    ): IsolatedRackDriver {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
      try {
        val engine = FakeAudioEngineController(scope = scope, enableSimulationTicker = enableSimulationTicker)
        val repository = FakeMusicSourceRepository(startLinked = startLinked)

        runBlocking {
          if (prelinkedRootUri != null) {
            repository.linkSafRoot(prelinkedRootUri)
          }
          if (initialQueue.isNotEmpty()) {
            engine.replaceQueue(initialQueue, startIndex = 0, autoPlay = autoPlay)
          }
          if (initialVolume != null) {
            engine.setVolume(initialVolume)
          }
        }

        val viewModel = DroidAmpViewModel(audioEngine = engine, musicRepository = repository)

        rule.setContent {
          CompositionLocalProvider(LocalDroidAmpSkin provides ClassicDroidAmpSkin) {
            DroidAmpRackScreen(viewModel = viewModel)
          }
        }
        rule.waitForIdle()

        if (startLinked || prelinkedRootUri != null) {
          rule.waitUntil(timeoutMillis = 3_000L) {
            viewModel.uiState.value.folderContents.currentFolderUri != null
          }
        }

        return IsolatedRackDriver(
          rule = rule,
          scope = scope,
          engine = engine,
          repository = repository,
          viewModel = viewModel,
        )
      } catch (t: Throwable) {
        scope.cancel()
        throw t
      }
    }
  }
}

class IsolatedRackDriver(
  rule: ComposeTestRule,
  private val scope: CoroutineScope,
  val engine: FakeAudioEngineController,
  val repository: FakeMusicSourceRepository,
  val viewModel: DroidAmpViewModel,
) : E2eRackDriver(rule), AutoCloseable {

  fun linkFakeSafRoot(treeUriString: String = FakeMusicSourceRepository.ROOT_URI) {
    runBlocking { repository.linkSafRoot(treeUriString) }
    viewModel.onSelectMusicFolder(treeUriString)
    rule.waitForIdle()
    rule.waitUntil(timeoutMillis = 3_000L) {
      viewModel.uiState.value.folderContents.currentFolderUri != null
    }
  }

  override fun close() {
    scope.cancel()
  }
}
