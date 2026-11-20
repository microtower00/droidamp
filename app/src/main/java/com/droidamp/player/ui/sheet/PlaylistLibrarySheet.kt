package com.droidamp.player.ui.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
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
import com.droidamp.player.ui.components.BeveledLedToggleButton
import com.droidamp.player.ui.components.ChassisTitleBar
import com.droidamp.player.ui.components.HardwarePanel
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
) {
  val skin = LocalDroidAmpSkin.current
  val scope = rememberCoroutineScope()
  val pagerState =
    rememberPagerState(
      initialPage = if (sheetMode == SheetMode.PLAYLIST) 0 else 1,
      pageCount = { 2 },
    )

  LaunchedEffect(sheetMode) {
    val targetPage = if (sheetMode == SheetMode.PLAYLIST) 0 else 1
    if (pagerState.currentPage != targetPage) {
      pagerState.animateScrollToPage(targetPage)
    }
  }
  LaunchedEffect(pagerState.settledPage) {
    val settledMode = if (pagerState.settledPage == 0) SheetMode.PLAYLIST else SheetMode.LIBRARY
    if (settledMode != sheetMode) {
      onSwitchSheetMode(settledMode)
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

  // Pull-down at scroll-top when expanded pans the stereo tower smoothly back to Deck + EQ
  val pullDownNestedScroll =
    remember {
      var dragAccum = 0f
      object : NestedScrollConnection {
        override fun onPostScroll(
          consumed: Offset,
          available: Offset,
          source: NestedScrollSource,
        ): Offset {
          if (source == NestedScrollSource.UserInput && available.y > 0f && canPullDownToCollapse()) {
            dragAccum += available.y
            onDragDelta(available.y)
            return Offset(0f, available.y)
          }
          return Offset.Zero
        }

        override suspend fun onPostFling(
          consumed: Velocity,
          available: Velocity,
        ): Velocity {
          if (dragAccum > 0f) {
            val total = dragAccum
            dragAccum = 0f
            onDragFinished(total)
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
        if (sheetMode == SheetMode.PLAYLIST) {
          "DROIDAMP QUEUE (${playbackState.queue.size}) $arrow"
        } else {
          "DROIDAMP LIBRARY $arrow"
        }
      ChassisTitleBar(
        title = title,
        showDragGrip = true,
        modifier =
          Modifier
            .pointerInput(Unit) {
              var totalDrag = 0f
              detectVerticalDragGestures(
                onDragStart = { totalDrag = 0f },
                onDragEnd = { onDragFinished(totalDrag) },
                onDragCancel = { onDragFinished(totalDrag) },
              ) { _, dragAmount ->
                totalDrag += dragAmount
                onDragDelta(dragAmount)
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
        // Always-live, always-scrollable HorizontalPager: Page 0 = QUEUE, Page 1 = LIBRARY
        HorizontalPager(
          state = pagerState,
          modifier = Modifier.weight(1f).fillMaxWidth(),
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
                  onMoveTrack = onMoveTrack,
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
              )
            }
          }
        }

        // Bottom Thumb-Zone Primary Tabs: ALWAYS visible & reachable (ADR-019 / ADR-023)
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
            checked = sheetMode == SheetMode.PLAYLIST || queueTabFlashing,
            onClick = {
              onSwitchSheetMode(SheetMode.PLAYLIST)
              scope.launch { pagerState.animateScrollToPage(0) }
            },
            modifier = Modifier.weight(1f).height(30.dp),
          )
          BeveledLedToggleButton(
            label = "LIBRARY",
            checked = sheetMode == SheetMode.LIBRARY,
            onClick = {
              onSwitchSheetMode(SheetMode.LIBRARY)
              scope.launch { pagerState.animateScrollToPage(1) }
            },
            modifier = Modifier.weight(1f).height(30.dp),
          )
        }

        // Compact Micro-Transport Bar: shown when Main Deck has panned out of view
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
