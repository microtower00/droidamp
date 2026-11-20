package com.droidamp.player.e2e.harness

import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.WinampEqSpec

/**
 * Opaque-box Compose UI selectors and coordinate-based hardware fader helpers for DroidAmp.
 *
 * Key invariants verified against M1 Compose UI:
 * - MainDeckPanel and EqualizerPanel remain in the semantics tree beneath ExpandedPlaylistLibrarySheet.
 * - Deck transport glyphs ("▶", "■") collide with non-clickable VFD status Text and
 *   PlaylistMicroTransportBar; filter with hasClickAction() and .onFirst() (Deck) vs .onLast() (Sheet).
 * - Sliders (VOL, BAL, Seekbar, PREAMP, 10-band EQ) are untagged Canvas/Box pointerInput targets;
 *   touch coordinates are computed in root coordinates relative to sibling labels.
 */
object DroidAmpSelectors {

  // --- Title Bars & VFD Readouts ---

  fun deckTitleBar(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNodeWithText("DROIDAMP")

  fun eqTitleBar(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNodeWithText("DROIDAMP EQUALIZER")

  fun peekingSheetTitleBar(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(
      hasText("DROIDAMP PLAYLIST ▲") or hasText("DROIDAMP LIBRARY ▲"),
    )

  fun expandedSheetTitleBar(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(
      hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"),
    )

  fun clockButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasContentDescription("Track timer ", substring = true) and hasClickAction())

  fun clockWithTime(rule: ComposeTestRule, formattedTime: String): SemanticsNodeInteraction =
    rule.onNodeWithContentDescription("Track timer $formattedTime")

  fun visualizerButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasContentDescription("Visualizer mode ", substring = true) and hasClickAction())

  fun visualizerWithMode(rule: ComposeTestRule, modeName: String): SemanticsNodeInteraction =
    rule.onNodeWithContentDescription("Visualizer mode $modeName")

  fun marqueeText(rule: ComposeTestRule, substring: String): SemanticsNodeInteraction {
    val deckTitleBottom = deckTitleBar(rule).fetchSemanticsNode().boundsInRoot.bottom
    val kbpsTop = rule.onNodeWithText("KBPS").fetchSemanticsNode().boundsInRoot.top
    val volBounds = volumeLabel(rule).fetchSemanticsNode().boundsInRoot
    val upperCutoff = minOf(kbpsTop, volBounds.top)
    val vfdMarqueeBoundsMatcher =
      SemanticsMatcher("within MainDeckPanel VFD marquee region") { node ->
        val b = node.boundsInRoot
        b.top >= deckTitleBottom - 1f &&
          b.bottom <= upperCutoff + 1f &&
          b.left >= volBounds.left - 1f
      }
    return rule.onNode(
      hasText(substring.uppercase(), substring = true) and
        !hasClickAction() and
        vfdMarqueeBoundsMatcher,
    )
  }

  fun volumeLabel(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNodeWithText("VOL")

  fun balanceLabel(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNodeWithText("BAL")

  fun stereoLabel(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNodeWithText("STEREO")

  fun monoLabel(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNodeWithText("MONO")

  // --- Main Deck Transport & Mode Toggles (Always .onFirst() with hasClickAction()) ---

  fun deckPrevButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodes(hasText("|<") and hasClickAction()).onFirst()

  fun deckPlayButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodes(hasText("▶") and hasClickAction()).onFirst()

  fun deckPauseButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodes(hasText("||") and hasClickAction()).onFirst()

  fun deckStopButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodes(hasText("■") and hasClickAction()).onFirst()

  fun deckNextButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodes(hasText(">|") and hasClickAction()).onFirst()

  fun deckEjectButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("⏏") and hasClickAction())

  fun shuffleButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("SHUFFLE") and hasClickAction())

  fun repeatButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(
      hasClickAction() and (hasText("REPEAT") or hasText("REP:ALL") or hasText("REP:1")),
    )

  fun deckEqToggleButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("EQ") and hasClickAction())

  fun deckPlToggleButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodes(hasText("PL") and hasClickAction()).onFirst()

  // --- Equalizer Controls ---

  fun eqOnButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("ON") and hasClickAction())

  fun eqAutoButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("AUTO") and hasClickAction())

  fun eqPresetsButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("PRESETS") and hasClickAction())

  fun eqPreampLabel(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNodeWithText("PRE")

  fun eqBandLabel(rule: ComposeTestRule, bandIndex: Int): SemanticsNodeInteraction {
    require(bandIndex in 0 until WinampEqSpec.BAND_COUNT) {
      "bandIndex must be in 0..${WinampEqSpec.BAND_COUNT - 1}, got $bandIndex"
    }
    return rule.onNodeWithText(WinampEqSpec.BAND_LABELS[bandIndex])
  }

  // --- Onboarding Empty Folder Well ---

  fun emptyFolderTitlePeeking(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodesWithText("NO MUSIC FOLDER LINKED").onFirst()

  fun emptyFolderTitleExpanded(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodesWithText("NO MUSIC FOLDER LINKED").onLast()

  fun selectMusicFolderButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("[ SELECT MUSIC FOLDER ]") and hasClickAction())

  // --- Expanded Sheet Micro-Transport Bar (.onLast() when sheet expanded) ---

  fun sheetMicroPrevButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodes(hasText("|<") and hasClickAction()).onLast()

  fun sheetMicroPlayButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodes(hasText("▶") and hasClickAction()).onLast()

  fun sheetMicroPauseButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodes(hasText("||") and hasClickAction()).onLast()

  fun sheetMicroStopButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodes(hasText("■") and hasClickAction()).onLast()

  fun sheetMicroNextButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodes(hasText(">|") and hasClickAction()).onLast()

  // --- Playlist Editor Controls ---

  fun playlistLibButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("LIB") and hasClickAction())

  fun playlistRemButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("REM") and hasClickAction())

  fun playlistMiscButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("MISC") and hasClickAction())

  fun playlistListButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("LIST") and hasClickAction())

  fun jumpFilterInput(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasSetTextAction())

  fun jumpFilterCloseButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("X") and hasClickAction())

  fun playlistRow(rule: ComposeTestRule, trackDisplayLabel: String): SemanticsNodeInteraction =
    rule.onNode(hasText(trackDisplayLabel) and hasClickAction() and !hasSetTextAction())

  fun playlistMoveUpButton(rule: ComposeTestRule, trackDisplayLabel: String): SemanticsNodeInteraction =
    rule.onNode(
      hasText("▲") and hasClickAction() and hasAnyAncestor(hasText(trackDisplayLabel) and !hasSetTextAction()),
    )

  fun playlistMoveDownButton(rule: ComposeTestRule, trackDisplayLabel: String): SemanticsNodeInteraction =
    rule.onNode(
      hasText("▼") and hasClickAction() and hasAnyAncestor(hasText(trackDisplayLabel) and !hasSetTextAction()),
    )

  // --- Library Browser Controls ---

  fun libraryFoldersTab(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("FOLDERS") and hasClickAction())

  fun libraryPlaylistsTab(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("PLAYLISTS") and hasClickAction())

  fun libraryPlButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onAllNodes(hasText("PL") and hasClickAction()).onLast()

  fun libraryPrimaryPlayButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("▶ PLAY", substring = true) and hasClickAction())

  fun libraryPrimaryQueueButton(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("+ QUEUE") and hasClickAction())

  fun libraryParentFolderRow(rule: ComposeTestRule): SemanticsNodeInteraction =
    rule.onNode(hasText("[..]  PARENT FOLDER") and hasClickAction())

  fun libraryFolderRow(rule: ComposeTestRule, folderName: String): SemanticsNodeInteraction =
    rule.onNode(hasText("[DIR] $folderName") and hasClickAction())

  fun libraryFolderPlayButton(rule: ComposeTestRule, folderName: String): SemanticsNodeInteraction =
    rule.onNode(
      hasText("▶") and hasClickAction() and hasAnyAncestor(hasText("[DIR] $folderName")),
    )

  fun libraryFolderEnqueueButton(rule: ComposeTestRule, folderName: String): SemanticsNodeInteraction =
    rule.onNode(
      hasText("+") and hasClickAction() and hasAnyAncestor(hasText("[DIR] $folderName")),
    )

  fun libraryTrackRow(rule: ComposeTestRule, trackDisplayLabel: String): SemanticsNodeInteraction =
    rule.onNode(hasText(trackDisplayLabel) and hasClickAction() and !hasSetTextAction())

  fun libraryTrackPlayButton(rule: ComposeTestRule, trackDisplayLabel: String): SemanticsNodeInteraction =
    rule.onNode(
      hasText("▶") and hasClickAction() and hasAnyAncestor(hasText(trackDisplayLabel) and !hasSetTextAction()),
    )

  fun libraryTrackEnqueueButton(rule: ComposeTestRule, trackDisplayLabel: String): SemanticsNodeInteraction =
    rule.onNode(
      hasText("+") and hasClickAction() and hasAnyAncestor(hasText(trackDisplayLabel) and !hasSetTextAction()),
    )

  fun libraryPlaylistRow(rule: ComposeTestRule, format: String, name: String): SemanticsNodeInteraction =
    rule.onNode(hasText("[${format.uppercase()}] $name") and hasClickAction())

  fun libraryPlaylistPlayButton(rule: ComposeTestRule, format: String, name: String): SemanticsNodeInteraction =
    rule.onNode(
      hasText("▶") and hasClickAction() and hasAnyAncestor(hasText("[${format.uppercase()}] $name")),
    )

  fun libraryPlaylistEnqueueButton(rule: ComposeTestRule, format: String, name: String): SemanticsNodeInteraction =
    rule.onNode(
      hasText("+") and hasClickAction() and hasAnyAncestor(hasText("[${format.uppercase()}] $name")),
    )

  // --- RetroMicroMenuPopup Helpers ---

  fun popupTitle(rule: ComposeTestRule, title: String): SemanticsNodeInteraction =
    rule.onNode(hasText(title.uppercase()) and hasAnyAncestor(isPopup()))

  fun popupMenuAction(rule: ComposeTestRule, label: String): SemanticsNodeInteraction =
    rule.onNode(
      hasText(label.uppercase()) and hasClickAction() and hasAnyAncestor(isPopup()),
    )

  fun popupScrollables(rule: ComposeTestRule): SemanticsNodeInteractionCollection =
    rule.onAllNodes(hasScrollAction() and hasAnyAncestor(isPopup()))

  // --- Exact Root-Coordinate Bounds for Untagged Sliders ---

  /**
   * MainDeckPanel.kt:208-236:
   * Left = VOL label left, Right = BAL label left - 6.dp spacer,
   * Top = VOL label bottom, Height = 20.dp.
   */
  fun volumeSliderBounds(rule: ComposeTestRule): Rect {
    val volBounds = volumeLabel(rule).fetchSemanticsNode().boundsInRoot
    val balBounds = balanceLabel(rule).fetchSemanticsNode().boundsInRoot
    val gapPx = with(rule.density) { 6.dp.toPx() }
    val heightPx = with(rule.density) { 20.dp.toPx() }
    return Rect(
      left = volBounds.left,
      top = volBounds.bottom,
      right = (balBounds.left - gapPx).coerceAtLeast(volBounds.left + 1f),
      bottom = volBounds.bottom + heightPx,
    )
  }

  /**
   * MainDeckPanel.kt:197-205, 238-270:
   * Left = BAL label left, Right = rightmost (STEREO or MONO) channel indicator right
   * (flush right in Right VFD Column regardless of channel mode of loaded track),
   * Top = BAL label bottom, Height = 20.dp.
   */
  fun balanceSliderBounds(rule: ComposeTestRule): Rect {
    val balBounds = balanceLabel(rule).fetchSemanticsNode().boundsInRoot
    val channelNodes =
      rule.onAllNodes(hasText("STEREO") or hasText("MONO")).fetchSemanticsNodes()
    val channelAnchor =
      (channelNodes.filter { it.boundsInRoot.top <= balBounds.top }.ifEmpty { channelNodes })
        .maxByOrNull { it.boundsInRoot.right }
        ?.boundsInRoot
        ?: error("Missing STEREO/MONO indicator node for balanceSliderBounds")
    val heightPx = with(rule.density) { 20.dp.toPx() }
    return Rect(
      left = balBounds.left,
      top = balBounds.bottom,
      right = channelAnchor.right.coerceAtLeast(balBounds.left + 1f),
      bottom = balBounds.bottom + heightPx,
    )
  }

  /**
   * MainDeckPanel.kt:275-341:
   * Full-width seekbar directly above transport row ("|<" .. "⏏"), separated by 5.dp.
   * Left = "|<" left, Right = "⏏" right, Bottom = "|<" top - 5.dp, Height = 20.dp.
   */
  fun seekbarBounds(rule: ComposeTestRule): Rect {
    val prevBounds = deckPrevButton(rule).fetchSemanticsNode().boundsInRoot
    val ejectBounds = deckEjectButton(rule).fetchSemanticsNode().boundsInRoot
    val gapPx = with(rule.density) { 5.dp.toPx() }
    val heightPx = with(rule.density) { 20.dp.toPx() }
    val bottom = prevBounds.top - gapPx
    return Rect(
      left = prevBounds.left,
      top = bottom - heightPx,
      right = ejectBounds.right.coerceAtLeast(prevBounds.left + 1f),
      bottom = bottom,
    )
  }

  /**
   * EqualizerPanel.kt:156-206:
   * 132.dp-tall Row; left Column is 34.dp wide centered on "PRE" label,
   * Box(weight(1f)) spans from Row top (PRE.bottom - 132.dp) to PRE.top - 3.dp.
   */
  fun preampSliderBounds(rule: ComposeTestRule): Rect {
    val preBounds = eqPreampLabel(rule).fetchSemanticsNode().boundsInRoot
    val halfWidthPx = with(rule.density) { 17.dp.toPx() }
    val rowHeightPx = with(rule.density) { 132.dp.toPx() }
    val spacerPx = with(rule.density) { 3.dp.toPx() }
    return Rect(
      left = preBounds.center.x - halfWidthPx,
      top = preBounds.bottom - rowHeightPx,
      right = preBounds.center.x + halfWidthPx,
      bottom = preBounds.top - spacerPx,
    )
  }

  /**
   * EqualizerPanel.kt:238-329:
   * 10 equal-width band columns aligned with "60".."16K" labels;
   * Box(weight(1f)) spans from Row top (label0.bottom - 132.dp) to label0.top - 3.dp.
   */
  fun eqBankBounds(rule: ComposeTestRule): Rect {
    val firstLabel = eqBandLabel(rule, 0).fetchSemanticsNode().boundsInRoot
    val lastLabel = eqBandLabel(rule, WinampEqSpec.BAND_COUNT - 1).fetchSemanticsNode().boundsInRoot
    val rowHeightPx = with(rule.density) { 132.dp.toPx() }
    val spacerPx = with(rule.density) { 3.dp.toPx() }
    return Rect(
      left = firstLabel.left,
      top = firstLabel.bottom - rowHeightPx,
      right = lastLabel.right,
      bottom = firstLabel.top - spacerPx,
    )
  }

  fun eqBandBounds(rule: ComposeTestRule, bandIndex: Int): Rect {
    val bank = eqBankBounds(rule)
    val label = eqBandLabel(rule, bandIndex).fetchSemanticsNode().boundsInRoot
    return Rect(
      left = label.left,
      top = bank.top,
      right = label.right,
      bottom = bank.bottom,
    )
  }

  // --- Coordinate Touch Injection Helpers (Must be called when sheet is collapsed!) ---

  fun setHorizontalSliderFraction(
    rule: ComposeTestRule,
    bounds: Rect,
    fraction: Float,
    useDragForExactEnds: Boolean = true,
  ) {
    val clamped = fraction.coerceIn(0f, 1f)
    val y = bounds.center.y
    if (useDragForExactEnds && (clamped <= 0.005f || clamped >= 0.995f)) {
      val endX = if (clamped <= 0.005f) bounds.left - 4f else bounds.right + 4f
      rule.onRoot().performTouchInput {
        swipe(start = Offset(bounds.center.x, y), end = Offset(endX, y), durationMillis = 120L)
      }
    } else {
      val x = bounds.left + bounds.width * clamped.coerceIn(0.005f, 0.995f)
      rule.onRoot().performTouchInput {
        click(Offset(x, y))
      }
    }
  }

  fun setVolumeFraction(rule: ComposeTestRule, fraction: Float) {
    setHorizontalSliderFraction(rule, volumeSliderBounds(rule), fraction)
  }

  fun setBalanceFraction(rule: ComposeTestRule, fraction: Float) {
    setHorizontalSliderFraction(rule, balanceSliderBounds(rule), fraction)
  }

  fun seekToFraction(rule: ComposeTestRule, fraction: Float) {
    setHorizontalSliderFraction(rule, seekbarBounds(rule), fraction)
  }

  fun setPreampDb(rule: ComposeTestRule, db: Float) {
    val bounds = preampSliderBounds(rule)
    val clampedDb = WinampEqSpec.clampDb(db)
    val norm = (clampedDb - WinampEqSpec.MIN_DB) / 24f // 0 = -12dB (bottom), 1 = +12dB (top)
    val x = bounds.center.x
    if (norm <= 0.02f || norm >= 0.98f) {
      val endY = if (norm >= 0.98f) bounds.top - 4f else bounds.bottom + 4f
      rule.onRoot().performTouchInput {
        swipe(start = Offset(x, bounds.center.y), end = Offset(x, endY), durationMillis = 120L)
      }
    } else {
      val y = bounds.bottom - bounds.height * norm
      rule.onRoot().performTouchInput {
        click(Offset(x, y))
      }
    }
  }

  fun setEqBandDb(rule: ComposeTestRule, bandIndex: Int, db: Float) {
    val bounds = eqBandBounds(rule, bandIndex)
    val clampedDb = WinampEqSpec.clampDb(db)
    val norm = (clampedDb - WinampEqSpec.MIN_DB) / 24f
    val x = bounds.center.x
    if (norm <= 0.02f || norm >= 0.98f) {
      val endY = if (norm >= 0.98f) bounds.top - 4f else bounds.bottom + 4f
      rule.onRoot().performTouchInput {
        swipe(start = Offset(x, bounds.center.y), end = Offset(x, endY), durationMillis = 120L)
      }
    } else {
      val y = bounds.bottom - bounds.height * norm
      rule.onRoot().performTouchInput {
        click(Offset(x, y))
      }
    }
  }

  /**
   * Draws an EQ curve horizontally across bands [fromBand..toBand] without entering PREAMP.
   */
  fun drawEqCurve(
    rule: ComposeTestRule,
    fromBand: Int = 0,
    toBand: Int = WinampEqSpec.BAND_COUNT - 1,
    startDb: Float,
    endDb: Float,
  ) {
    val startBounds = eqBandBounds(rule, fromBand)
    val endBounds = eqBandBounds(rule, toBand)
    val startNorm = (WinampEqSpec.clampDb(startDb) - WinampEqSpec.MIN_DB) / 24f
    val endNorm = (WinampEqSpec.clampDb(endDb) - WinampEqSpec.MIN_DB) / 24f
    val startY = startBounds.bottom - startBounds.height * startNorm.coerceIn(0.02f, 0.98f)
    val endY = endBounds.bottom - endBounds.height * endNorm.coerceIn(0.02f, 0.98f)
    rule.onRoot().performTouchInput {
      swipe(
        start = Offset(startBounds.center.x, startY),
        end = Offset(endBounds.center.x, endY),
        durationMillis = 200L,
      )
    }
  }

  /**
   * Single-tap helper for PlaylistEditorView / LibraryBrowserView combinedClickable rows.
   * Advances Compose's virtual test clock past doubleTapTimeoutMillis so onClick fires
   * without modifying production combinedClickable double-tap disambiguation.
   */
  fun singleTapNode(rule: ComposeTestRule, node: SemanticsNodeInteraction) {
    node.performClick()
    rule.mainClock.advanceTimeBy(ViewConfiguration.getDoubleTapTimeout().toLong() + 50L)
    rule.waitForIdle()
  }

  /**
   * Convenience double-tap helper for PlaylistEditorView / LibraryBrowserView combinedClickable rows.
   */
  fun doubleTapNode(node: SemanticsNodeInteraction) {
    node.performTouchInput { doubleClick() }
  }
}
