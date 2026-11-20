package com.droidamp.player.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.LibraryViewLevel
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.RestingLayoutVariant
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.SheetMode
import com.droidamp.player.domain.model.formatWinampDuration
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BeveledLedToggleButton
import com.droidamp.player.ui.components.ChassisTitleBar
import com.droidamp.player.ui.components.HardwarePanel
import com.droidamp.player.ui.components.RecessedWell
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Non-scrollable peeking surface for the collapsed drawer (ADR-018):
 * - Entire peek area drags 1:1 with the finger (zero drag-vs-scroll conflict).
 * - Always reflects where the user left off (`QUEUE` vs. `LIBRARY`).
 * - Supports both Option A (Slim Glance Lip) and Option B (2-Tab Glance Peek).
 */
@Composable
fun PeekingDrawerSurface(
  layoutVariant: RestingLayoutVariant,
  sheetMode: SheetMode,
  libraryViewLevel: LibraryViewLevel,
  folderContents: FolderContents,
  playlists: List<SavedPlaylistEntry>,
  hasLinkedRoots: Boolean,
  playbackState: PlaybackState,
  onExpand: () -> Unit,
  onSwitchSheetMode: (SheetMode) -> Unit,
  onSelectMusicFolder: () -> Unit,
  onDragDelta: (Float) -> Unit,
  onDragFinished: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  val dragModifier =
    Modifier
      .pointerInput(Unit) {
        var totalDrag = 0f
        detectVerticalDragGestures(
          onDragStart = { totalDrag = 0f },
          onDragEnd = { onDragFinished(totalDrag) },
          onDragCancel = { onDragFinished(0f) },
        ) { _, dragAmount ->
          totalDrag += dragAmount
          onDragDelta(dragAmount)
        }
      }
      .clickable(onClick = onExpand)

  Column(modifier = modifier.fillMaxWidth()) {
    // Non-interactive chassis bezel buffer separating EQ bottom from pull-up handle (ADR-005)
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(skin.metrics.sheetSwipeBufferHeight)
          .background(skin.chassis.panelSurfaceAlt),
    )

    HardwarePanel(
      modifier =
        Modifier
          .fillMaxWidth()
          .weight(1f)
          .then(dragModifier),
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        val headerTitle =
          if (sheetMode == SheetMode.PLAYLIST) {
            "DROIDAMP QUEUE (${playbackState.queue.size}) ▲"
          } else {
            "DROIDAMP LIBRARY ▲"
          }
        ChassisTitleBar(title = headerTitle, showDragGrip = true)

        when (layoutVariant) {
          RestingLayoutVariant.OPTION_A_SLIM_LIP -> {
            Box(
              modifier =
                Modifier
                  .weight(1f)
                  .fillMaxWidth()
                  .padding(horizontal = skin.metrics.panelPadding, vertical = 4.dp),
            ) {
              if (!hasLinkedRoots && playbackState.queue.isEmpty()) {
                EmptyFolderWell(
                  onSelectMusicFolder = onSelectMusicFolder,
                  compactPeek = true,
                )
              } else {
                RecessedWell(
                  modifier = Modifier.fillMaxSize(),
                  backgroundColor = skin.playlist.normalBackground,
                  contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                  val glanceLine = buildSlimGlanceSummary(
                    sheetMode = sheetMode,
                    libraryViewLevel = libraryViewLevel,
                    folderContents = folderContents,
                    playlists = playlists,
                    playbackState = playbackState,
                  )
                  Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Text(
                      text = glanceLine,
                      style = skin.typography.trackRow,
                      color = skin.playlist.currentText,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.weight(1f),
                    )
                  }
                }
              }
            }
          }

          RestingLayoutVariant.OPTION_B_TAB_PEEK -> {
            Column(
              modifier =
                Modifier
                  .weight(1f)
                  .fillMaxWidth()
                  .padding(skin.metrics.panelPadding),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              if (!hasLinkedRoots && playbackState.queue.isEmpty()) {
                EmptyFolderWell(
                  onSelectMusicFolder = onSelectMusicFolder,
                  compactPeek = true,
                  modifier = Modifier.weight(1f),
                )
              } else {
                // Glance well reflecting where the user left off (non-scrollable)
                RecessedWell(
                  modifier = Modifier.weight(1f).fillMaxWidth(),
                  backgroundColor = skin.playlist.normalBackground,
                  contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                  if (sheetMode == SheetMode.PLAYLIST) {
                    QueueGlanceRows(playbackState = playbackState)
                  } else {
                    LibraryGlanceRows(
                      libraryViewLevel = libraryViewLevel,
                      folderContents = folderContents,
                      playlists = playlists,
                    )
                  }
                }

                // Bottom-aligned 2-tab switcher matching expanded drawer
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  BeveledLedToggleButton(
                    label = "QUEUE (${playbackState.queue.size})",
                    checked = sheetMode == SheetMode.PLAYLIST,
                    onClick = { onSwitchSheetMode(SheetMode.PLAYLIST) },
                    compact = true,
                    modifier = Modifier.weight(1f).height(26.dp),
                  )
                  BeveledLedToggleButton(
                    label = "LIBRARY",
                    checked = sheetMode == SheetMode.LIBRARY,
                    onClick = { onSwitchSheetMode(SheetMode.LIBRARY) },
                    compact = true,
                    modifier = Modifier.weight(1f).height(26.dp),
                  )
                }
              }
            }
          }
        }
      }
    }

    // Non-interactive bottom lip + Android gesture bar clearance
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .background(skin.chassis.bottomLip)
          .navigationBarsPadding()
          .height(skin.metrics.chassisBottomLipHeight),
    )
  }
}

private fun buildSlimGlanceSummary(
  sheetMode: SheetMode,
  libraryViewLevel: LibraryViewLevel,
  folderContents: FolderContents,
  playlists: List<SavedPlaylistEntry>,
  playbackState: PlaybackState,
): String {
  return if (sheetMode == SheetMode.PLAYLIST) {
    val queue = playbackState.queue
    if (queue.isEmpty()) {
      "QUEUE EMPTY · DRAG UP TO BROWSE"
    } else {
      val nextIdx = (playbackState.currentIndex + 1).takeIf { it in queue.indices }
      val nextTrack = nextIdx?.let { queue[it] }
      if (nextTrack != null) {
        "NEXT: ${nextTrack.displayLabel}"
      } else {
        val current = playbackState.currentTrack
        "NOW: ${current?.displayLabel ?: queue.first().displayLabel}"
      }
    }
  } else {
    when (libraryViewLevel) {
      LibraryViewLevel.ROOT -> "LIBRARY · FOLDERS & PLAYLISTS (${playlists.size})"
      LibraryViewLevel.PLAYLISTS -> "LIBRARY / PLAYLISTS (${playlists.size})"
      LibraryViewLevel.FOLDERS -> {
        val path =
          folderContents.currentPathSegments.takeLast(2).joinToString(" / ").uppercase()
        if (path.isNotEmpty()) {
          "LIBRARY / $path"
        } else {
          "LIBRARY / FOLDERS"
        }
      }
    }
  }
}

@Composable
private fun QueueGlanceRows(playbackState: PlaybackState) {
  val skin = LocalDroidAmpSkin.current
  val queue = playbackState.queue
  if (queue.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        text = "QUEUE EMPTY — DRAG UP TO ADD TRACKS",
        style = skin.typography.microLabel,
        color = skin.vfd.phosphorDim,
      )
    }
    return
  }
  val startIdx = playbackState.currentIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
  val slice = queue.drop(startIdx).take(4)
  Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    slice.forEachIndexed { offset, track ->
      val idx = startIdx + offset
      val isCurrent = idx == playbackState.currentIndex
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "${idx + 1}. ${track.displayLabel}",
          style = skin.typography.trackRow,
          color = if (isCurrent) skin.playlist.currentText else skin.playlist.normalText,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = formatWinampDuration(track.durationMs),
          style = skin.typography.microLabel,
          color = if (isCurrent) skin.playlist.currentText else skin.playlist.normalText,
        )
      }
    }
  }
}

@Composable
private fun LibraryGlanceRows(
  libraryViewLevel: LibraryViewLevel,
  folderContents: FolderContents,
  playlists: List<SavedPlaylistEntry>,
) {
  val skin = LocalDroidAmpSkin.current
  Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    when (libraryViewLevel) {
      LibraryViewLevel.ROOT -> {
        Text(
          text = "> [DIR] FOLDERS",
          style = skin.typography.trackRow,
          color = skin.playlist.folderAccentText,
          maxLines = 1,
        )
        Text(
          text = "> [LST] PLAYLISTS (${playlists.size})",
          style = skin.typography.trackRow,
          color = skin.playlist.normalText,
          maxLines = 1,
        )
      }
      LibraryViewLevel.PLAYLISTS -> {
        Text(
          text = "LIBRARY / PLAYLISTS",
          style = skin.typography.microLabel,
          color = skin.playlist.breadcrumbText,
          maxLines = 1,
        )
        playlists.take(3).forEach { pl ->
          Text(
            text = "[${pl.format.uppercase()}] ${pl.name} (${pl.trackCount})",
            style = skin.typography.trackRow,
            color = skin.playlist.normalText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      LibraryViewLevel.FOLDERS -> {
        val crumb =
          if (folderContents.currentPathSegments.isEmpty()) {
            "LIBRARY / FOLDERS"
          } else {
            folderContents.currentPathSegments.joinToString(" / ").uppercase()
          }
        Text(
          text = crumb,
          style = skin.typography.microLabel,
          color = skin.playlist.breadcrumbText,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val previewLines =
          buildList {
            folderContents.folders.take(2).forEach { add("[DIR] ${it.name}" to true) }
            folderContents.tracks.take(3 - size).forEach { add(it.displayLabel to false) }
          }
        previewLines.forEach { (label, isDir) ->
          Text(
            text = label,
            style = skin.typography.trackRow,
            color = if (isDir) skin.playlist.folderAccentText else skin.playlist.normalText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

/**
 * Full-screen expanded Queue / Library drawer (ADR-018, ADR-019, ADR-020):
 * - HorizontalPager swipe between QUEUE (page 0) and LIBRARY (page 1).
 * - Bottom thumb-zone stack: (1) Context actions, (2) [QUEUE (N)] | [LIBRARY] tabs, (3) Micro-transport bar.
 * - Dragging down from the titlebar OR pulling down at scroll-top collapses 1:1.
 */
@Composable
fun ExpandedPlaylistLibrarySheet(
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
  onCollapse: () -> Unit,
  onDragDelta: (Float) -> Unit,
  onDragFinished: (Float) -> Unit,
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

  // Keep HorizontalPager synced with ViewModel sheetMode
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

  // Brief phosphor pulse on the bottom [QUEUE (N)] tab when tracks are enqueued from LIBRARY
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

  // Pull-down at scroll-top collapses the drawer 1:1 (ADR-018)
  val pullDownNestedScroll =
    remember {
      var dragAccum = 0f
      object : NestedScrollConnection {
        override fun onPostScroll(
          consumed: Offset,
          available: Offset,
          source: NestedScrollSource,
        ): Offset {
          if (source == NestedScrollSource.UserInput && available.y > 0f) {
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
      // Swipe-down & tappable Winamp titlebar
      val title =
        if (sheetMode == SheetMode.PLAYLIST) {
          "DROIDAMP QUEUE (${playbackState.queue.size}) ▼"
        } else {
          "DROIDAMP LIBRARY ▼"
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
                onDragCancel = { onDragFinished(0f) },
              ) { _, dragAmount ->
                totalDrag += dragAmount
                onDragDelta(dragAmount)
              }
            }
            .clickable(onClick = onCollapse),
      )

      Column(
        modifier =
          Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(skin.metrics.panelPadding),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        // HorizontalPager: Page 0 = QUEUE, Page 1 = LIBRARY (ADR-019)
        HorizontalPager(
          state = pagerState,
          modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
          when (page) {
            0 -> {
              if (!hasLinkedRoots && playbackState.queue.isEmpty()) {
                EmptyFolderWell(onSelectMusicFolder = onSelectMusicFolder)
              } else {
                PlaylistEditorView(
                  queue = playbackState.queue,
                  currentIndex = playbackState.currentIndex,
                  selectedIndices = selectedPlaylistIndices,
                  showJumpFilter = showJumpFilter,
                  jumpFilterQuery = jumpFilterQuery,
                  activeMicroMenu = activeMicroMenu,
                  showClearQueueConfirm = showClearQueueConfirm,
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

        // Bottom Stack Row 2: Primary [QUEUE (N)] | [LIBRARY] thumb-zone tabs (ADR-019)
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

        // Bottom Stack Row 3: Compact carved-glyph micro-transport bar (ADR-019)
        PlaylistMicroTransportBar(
          playbackState = playbackState,
          onPrev = onPrev,
          onPlay = onPlay,
          onPause = onPause,
          onStop = onStop,
          onNext = onNext,
        )
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
