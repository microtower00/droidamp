package com.droidamp.player.ui.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.LibraryViewLevel
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.SheetMode
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.storage.QueueTapAction
import com.droidamp.player.ui.components.BeveledLedToggleButton
import com.droidamp.player.ui.components.ChassisTitleBar
import com.droidamp.player.ui.components.HardwarePanel
import com.droidamp.player.ui.components.SettingsGearButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Unified, always-live Queue & Library deck docked below the Equalizer (ADR-023):
 * - Never unmounts between resting and expanded states, so 1:1 stereo-tower drag gestures never cancel.
 * - Both QUEUE (all tracks scrollable) and LIBRARY (live folders/playlists) are 100% interactive at all times.
 * - Dragging the titlebar pans the entire stereo stack (Deck + EQ + this panel) 1:1.
 * - Pulling down at scroll-top when expanded pans Deck + EQ smoothly back into view.
 */
@Composable
fun UnifiedQueueLibraryPanel(
  isExpanded: Boolean,
  sheetMode: SheetMode,
  hasLinkedRoots: Boolean,
  playbackState: PlaybackState,
  selectedPlaylistIndices: Set<Int>,
  showJumpFilter: Boolean,
  jumpFilterQuery: String,
  activeMicroMenu: PlaylistMicroMenu,
  showClearQueueConfirm: Boolean,
  libraryViewLevel: LibraryViewLevel,
  folderContents: FolderContents,
  playlists: List<SavedPlaylistEntry>,
  selectedLibraryTrackIds: Set<String>,
  selectedPlaylistId: String?,
  libraryFeedbackBanner: String?,
  queueFeedbackPulseCount: Int,
  onToggleExpand: () -> Unit,
  onDragDelta: (Float) -> Unit,
  onDragFinished: (Float) -> Unit,
  canPullDownToCollapse: () -> Boolean,
  isMidPan: () -> Boolean,
  onSelectMusicFolder: () -> Unit,
  onSwitchSheetMode: (SheetMode) -> Unit,
  onPlaylistTrackClick: (Int) -> Unit,
  onPlaylistTrackDoubleClick: (Int) -> Unit,
  onMoveTrackUp: (Int) -> Unit,
  onMoveTrackDown: (Int) -> Unit,
  onJumpFilterChange: (String) -> Unit,
  onToggleJumpFilter: () -> Unit,
  onOpenMicroMenu: (PlaylistMicroMenu) -> Unit,
  onDismissMicroMenu: () -> Unit,
  onRemoveSelected: () -> Unit,
  onCropSelected: () -> Unit,
  onRequestClearQueue: () -> Unit,
  onConfirmClearQueue: () -> Unit,
  onDismissClearQueueConfirm: () -> Unit,
  onSortQueue: (PlaylistSortOrder) -> Unit,
  onReverseQueue: () -> Unit,
  onRandomizeQueue: () -> Unit,
  onNewQueue: () -> Unit,
  onSavePlaylist: () -> Unit,
  onLoadPlaylist: () -> Unit,
  onOpenLibraryRoot: () -> Unit,
  onOpenLibraryFolders: () -> Unit,
  onOpenLibraryPlaylists: () -> Unit,
  onNavigateUp: () -> Unit,
  onOpenFolder: (FolderEntry) -> Unit,
  onPlayFolder: (FolderEntry) -> Unit,
  onEnqueueFolder: (FolderEntry) -> Unit,
  onPlayCurrentFolder: () -> Unit,
  onEnqueueCurrentFolder: () -> Unit,
  onToggleLibraryTrackSelection: (AudioTrack) -> Unit,
  onPlayLibraryTrack: (AudioTrack) -> Unit,
  onEnqueueLibraryTrack: (AudioTrack) -> Unit,
  onSelectLibraryPlaylist: (SavedPlaylistEntry) -> Unit,
  onPlayLibraryPlaylist: (SavedPlaylistEntry) -> Unit,
  onEnqueueLibraryPlaylist: (SavedPlaylistEntry) -> Unit,
  onPlayPrimaryLibrarySelection: () -> Unit,
  onEnqueuePrimaryLibrarySelection: () -> Unit,
  onClearLibrarySelection: () -> Unit,
  onPrev: () -> Unit,
  onPlay: () -> Unit,
  onPause: () -> Unit,
  onStop: () -> Unit,
  onNext: () -> Unit,
  modifier: Modifier = Modifier,
  onMoveTrack: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
  onMoveTracksStep: (anchorIndex: Int, delta: Int) -> Boolean = { _, _ -> false },
  queueTapAction: QueueTapAction = QueueTapAction.DOUBLE_TAP,
  hapticFeedbackEnabled: Boolean = true,
  isSettingsOpen: Boolean = false,
  isExitingSettingsArmed: Boolean = false,
  onOpenSettings: () -> Unit = {},
  settingsContent: @Composable () -> Unit = {},
) {
  val skin = LocalDroidAmpSkin.current
  val scope = rememberCoroutineScope()
  val pagerState =
    rememberPagerState(
      initialPage = if (sheetMode == SheetMode.PLAYLIST) 0 else 1,
      pageCount = { 2 },
    )
  val activePagerTab by remember(pagerState) {
    derivedStateOf {
      val continuousPage = pagerState.currentPage + pagerState.currentPageOffsetFraction
      if (pagerState.settledPage == 0) {
        if (continuousPage >= 0.22f) 1 else 0
      } else {
        if (continuousPage <= 0.78f) 0 else 1
      }
    }
  }

  LaunchedEffect(sheetMode) {
    val targetPage = if (sheetMode == SheetMode.PLAYLIST) 0 else 1
    if (pagerState.currentPage != targetPage) {
      pagerState.animateScrollToPage(targetPage)
    }
  }

  LaunchedEffect(pagerState.settledPage) {
    when (pagerState.settledPage) {
      0 -> if (sheetMode != SheetMode.PLAYLIST) onSwitchSheetMode(SheetMode.PLAYLIST)
      1 -> if (sheetMode != SheetMode.LIBRARY) onSwitchSheetMode(SheetMode.LIBRARY)
    }
  }

  var queueTabFlashing by remember { mutableStateOf(false) }
  LaunchedEffect(queueFeedbackPulseCount) {
    if (queueFeedbackPulseCount > 0) {
      queueTabFlashing = true
      delay(850L)
      queueTabFlashing = false
    }
  }

  val queueCountByTrackId =
    remember(playbackState.queue) {
      buildMap<String, Int> {
        playbackState.queue.forEach { track ->
          this[track.id] = (this[track.id] ?: 0) + 1
        }
      }
    }

  val latestOnDragDelta by rememberUpdatedState(onDragDelta)
  val latestOnDragFinished by rememberUpdatedState(onDragFinished)
  val latestCanPullDownToCollapse by rememberUpdatedState(canPullDownToCollapse)
  val latestIsMidPan by rememberUpdatedState(isMidPan)
  val latestIsExpanded by rememberUpdatedState(isExpanded)

  // Effortless expand-on-drag-up + pull-down at scroll-top + mid-pan direction-reversal lock
  val pullDownNestedScroll =
    remember {
      var dragAccum = 0f
      var activeNestedPan = false
      object : NestedScrollConnection {
        override fun onPreScroll(
          available: Offset,
          source: NestedScrollSource,
        ): Offset {
          if (source == NestedScrollSource.UserInput && available.y != 0f) {
            if (latestIsMidPan() || (!latestIsExpanded && available.y < 0f)) {
              activeNestedPan = true
              dragAccum += available.y
              latestOnDragDelta(available.y)
              return Offset(0f, available.y)
            }
          }
          return Offset.Zero
        }

        override fun onPostScroll(
          consumed: Offset,
          available: Offset,
          source: NestedScrollSource,
        ): Offset {
          if (source == NestedScrollSource.UserInput) {
            if ((available.y > 0f && latestCanPullDownToCollapse()) ||
              (available.y < 0f && !latestIsExpanded)
            ) {
              activeNestedPan = true
              dragAccum += available.y
              latestOnDragDelta(available.y)
              return Offset(0f, available.y)
            }
          }
          return Offset.Zero
        }

        override suspend fun onPostFling(
          consumed: Velocity,
          available: Velocity,
        ): Velocity {
          if (activeNestedPan) {
            val total = dragAccum
            dragAccum = 0f
            activeNestedPan = false
            latestOnDragFinished(total)
          }
          return Velocity.Zero
        }
      }
    }

  HardwarePanel(modifier = modifier.fillMaxSize().nestedScroll(pullDownNestedScroll)) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Draggable & tappable stereo-tower handle titlebar (never unmounted during drag!)
      val arrow = if (isExpanded) "▼" else "▲"
      val title =
        when {
          isSettingsOpen && isExitingSettingsArmed -> "RELEASE TO EXIT SETTINGS $arrow"
          isSettingsOpen -> "DROIDAMP SETTINGS $arrow"
          activePagerTab == 0 -> "DROIDAMP QUEUE (${playbackState.queue.size}) $arrow"
          else -> "DROIDAMP LIBRARY $arrow"
        }
      val drawerHeaderSprite =
        if (isExpanded) {
          skin.sprites.drawerHeaderExpandedSprite
        } else {
          skin.sprites.drawerHeaderCollapsedSprite
        }
      ChassisTitleBar(
        title = title,
        showDragGrip = true,
        customSprite = drawerHeaderSprite.takeIf { skin.sprites.hasCompletePanelHeaders },
        modifier =
          Modifier
            .pointerInput(Unit) {
              var totalDrag = 0f
              detectVerticalDragGestures(
                onDragStart = { totalDrag = 0f },
                onDragEnd = { latestOnDragFinished(totalDrag) },
                onDragCancel = { latestOnDragFinished(totalDrag) },
              ) { _, dragAmount ->
                totalDrag += dragAmount
                latestOnDragDelta(dragAmount)
              }
            }
            .clickable(onClick = onToggleExpand),
      )

      Column(
        modifier =
          Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(skin.metrics.panelPadding),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxWidth()
              .pointerInput(Unit) {
                var totalDrag = 0f
                detectVerticalDragGestures(
                  onDragStart = { totalDrag = 0f },
                  onDragEnd = { latestOnDragFinished(totalDrag) },
                  onDragCancel = { latestOnDragFinished(totalDrag) },
                ) { _, dragAmount ->
                  totalDrag += dragAmount
                  latestOnDragDelta(dragAmount)
                }
              },
        ) {
          // Always-live, always-scrollable HorizontalPager: Page 0 = QUEUE, Page 1 = LIBRARY
          HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isSettingsOpen,
            flingBehavior = PagerDefaults.flingBehavior(state = pagerState, snapPositionalThreshold = 0.22f),
            modifier =
              Modifier
                .fillMaxSize()
                .then(if (isSettingsOpen) Modifier.semantics { hideFromAccessibility() } else Modifier),
          ) { page ->
            when (page) {
              0 -> {
                if (!hasLinkedRoots && playbackState.queue.isEmpty()) {
                  EmptyFolderWell(
                    onSelectMusicFolder = onSelectMusicFolder,
                    compactPeek = !isExpanded,
                  )
                } else {
                  PlaylistEditorView(
                    queue = playbackState.queue,
                    currentIndex = playbackState.currentIndex,
                    selectedIndices = selectedPlaylistIndices,
                    showJumpFilter = showJumpFilter,
                    jumpFilterQuery = jumpFilterQuery,
                    activeMicroMenu = activeMicroMenu,
                    showClearQueueConfirm = showClearQueueConfirm,
                    showBottomActionBar = isExpanded,
                    onTrackClick = onPlaylistTrackClick,
                    onTrackDoubleClick = onPlaylistTrackDoubleClick,
                    onMoveTrackUp = onMoveTrackUp,
                    onMoveTrackDown = onMoveTrackDown,
                    onJumpFilterChange = onJumpFilterChange,
                    onToggleJumpFilter = onToggleJumpFilter,
                    onOpenMicroMenu = onOpenMicroMenu,
                    onDismissMicroMenu = onDismissMicroMenu,
                    onRemoveSelected = onRemoveSelected,
                    onCropSelected = onCropSelected,
                    onRequestClearQueue = onRequestClearQueue,
                    onConfirmClearQueue = onConfirmClearQueue,
                    onDismissClearQueueConfirm = onDismissClearQueueConfirm,
                    onSortQueue = onSortQueue,
                    onReverseQueue = onReverseQueue,
                    onRandomizeQueue = onRandomizeQueue,
                    onNewQueue = onNewQueue,
                    onSavePlaylist = onSavePlaylist,
                    onLoadPlaylist = onLoadPlaylist,
                    queueTapAction = queueTapAction,
                    hapticFeedbackEnabled = hapticFeedbackEnabled,
                    onMoveTrack = onMoveTrack,
                    onMoveTracksStep = onMoveTracksStep,
                  )
                }
              }

              else -> {
                LibraryBrowserView(
                  hasLinkedRoots = hasLinkedRoots,
                  libraryViewLevel = libraryViewLevel,
                  folderContents = folderContents,
                  playlists = playlists,
                  selectedTrackIds = selectedLibraryTrackIds,
                  selectedPlaylistId = selectedPlaylistId,
                  queueCountByTrackId = queueCountByTrackId,
                  libraryFeedbackBanner = libraryFeedbackBanner,
                  onSelectMusicFolder = onSelectMusicFolder,
                  onOpenLibraryRoot = onOpenLibraryRoot,
                  onOpenLibraryFolders = onOpenLibraryFolders,
                  onOpenLibraryPlaylists = onOpenLibraryPlaylists,
                  onNavigateUp = onNavigateUp,
                  onOpenFolder = onOpenFolder,
                  onPlayFolder = onPlayFolder,
                  onEnqueueFolder = onEnqueueFolder,
                  onPlayCurrentFolder = onPlayCurrentFolder,
                  onEnqueueCurrentFolder = onEnqueueCurrentFolder,
                  onToggleTrackSelection = onToggleLibraryTrackSelection,
                  onPlayTrack = onPlayLibraryTrack,
                  onEnqueueTrack = onEnqueueLibraryTrack,
                  onSelectPlaylist = onSelectLibraryPlaylist,
                  onPlayPlaylist = onPlayLibraryPlaylist,
                  onEnqueuePlaylist = onEnqueueLibraryPlaylist,
                  onPlayPrimarySelection = onPlayPrimaryLibrarySelection,
                  onEnqueuePrimarySelection = onEnqueuePrimaryLibrarySelection,
                  onClearSelection = onClearLibrarySelection,
                  hapticFeedbackEnabled = hapticFeedbackEnabled,
                )
              }
            }
          }

          androidx.compose.animation.AnimatedVisibility(
            visible = isSettingsOpen,
            enter =
              slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(durationMillis = 200),
              ) + fadeIn(animationSpec = tween(durationMillis = 160)),
            exit =
              slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(durationMillis = 180),
              ) + fadeOut(animationSpec = tween(durationMillis = 140)),
          ) {
            Box(
              modifier =
                Modifier
                  .fillMaxSize()
                  .background(skin.chassis.panelSurface),
            ) {
              settingsContent()
            }
          }
        }

        // Bottom Thumb-Zone Primary Tabs: ALWAYS visible & reachable (ADR-019 / ADR-023 / ADR-029)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          val queueLabel =
            if (queueTabFlashing) {
              "QUEUE (${playbackState.queue.size}) +"
            } else {
              "QUEUE (${playbackState.queue.size})"
            }
          BeveledLedToggleButton(
            label = queueLabel,
            checked = (!isSettingsOpen && activePagerTab == 0) || queueTabFlashing,
            onClick = {
              onSwitchSheetMode(SheetMode.PLAYLIST)
              scope.launch { pagerState.animateScrollToPage(0) }
            },
            spriteOff = skin.sprites.mobileTabQueueOff,
            spriteOn = skin.sprites.mobileTabQueueOn,
            modifier = Modifier.weight(1f).height(30.dp),
          )
          BeveledLedToggleButton(
            label = "LIBRARY",
            checked = !isSettingsOpen && activePagerTab == 1,
            onClick = {
              onSwitchSheetMode(SheetMode.LIBRARY)
              scope.launch { pagerState.animateScrollToPage(1) }
            },
            spriteOff = skin.sprites.mobileTabLibraryOff,
            spriteOn = skin.sprites.mobileTabLibraryOn,
            modifier = Modifier.weight(1f).height(30.dp),
          )
          SettingsGearButton(
            onClick = onOpenSettings,
            highlighted = isSettingsOpen,
            glyphSize = 14.dp,
            modifier = Modifier.size(30.dp),
          )
        }

        // Compact Micro-Transport Bar: always visible when expanded (including Settings) since Main Deck has panned out of view
        AnimatedVisibility(
          visible = isExpanded,
          enter = expandVertically(),
          exit = shrinkVertically(),
        ) {
          PlaylistMicroTransportBar(
            playbackState = playbackState,
            onPrev = onPrev,
            onPlay = onPlay,
            onPause = onPause,
            onStop = onStop,
            onNext = onNext,
          )
        }
      }

      // Mandatory safe clearance above Android's navigation gesture bar + non-interactive chassis lip
      Spacer(
        modifier =
          Modifier
            .fillMaxWidth()
            .background(skin.chassis.bottomLip)
            .navigationBarsPadding()
            .height(skin.metrics.chassisBottomLipHeight),
      )
    }
  }
}
