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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.LibraryTab
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.SheetMode
import com.droidamp.player.domain.model.formatWinampDuration
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.ChassisTitleBar
import com.droidamp.player.ui.components.HardwarePanel
import com.droidamp.player.ui.components.RecessedWell

/**
 * Peeking bottom panel docked below the Equalizer in the resting Hi-Fi Rack.
 * Includes a dedicated non-interactive bezel buffer above the grab handle (ADR-005)
 * and a safe non-interactive bottom lip above Android's navigation gesture bar (ADR-010).
 */
@Composable
fun PeekingPlaylistSheet(
  sheetMode: SheetMode,
  hasLinkedRoots: Boolean,
  playbackState: PlaybackState,
  onExpand: () -> Unit,
  onSelectMusicFolder: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  var dragAccumulator by remember { mutableFloatStateOf(0f) }

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
          .pointerInput(Unit) {
            detectVerticalDragGestures(
              onDragStart = { dragAccumulator = 0f },
              onDragEnd = {
                if (dragAccumulator < -28f) onExpand()
                dragAccumulator = 0f
              },
              onDragCancel = { dragAccumulator = 0f },
            ) { _, dragAmount ->
              dragAccumulator += dragAmount
            }
          }
          .clickable(onClick = onExpand),
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        val headerTitle =
          if (sheetMode == SheetMode.PLAYLIST) "DROIDAMP PLAYLIST ▲" else "DROIDAMP LIBRARY ▲"
        ChassisTitleBar(title = headerTitle, showDragGrip = true)

        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxWidth()
              .padding(skin.metrics.panelPadding),
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
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            ) {
              if (playbackState.queue.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                  Text(
                    text = "DRAG UP OR TAP TO BROWSE LIBRARY / PLAYLIST",
                    style = skin.typography.microLabel,
                    color = skin.vfd.phosphorDim,
                  )
                }
              } else {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                  playbackState.queue.take(5).forEachIndexed { idx, track ->
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
            }
          }
        }
      }
    }

    // Non-interactive bottom lip + Android gesture bar clearance (outside clickable HardwarePanel)
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

/**
 * Full-screen expanded Playlist / Library sheet covering both Deck and EQ (ADR-004).
 * Swiping down from the titlebar or pressing Back collapses smoothly while remembering SheetMode.
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
  statusBanner: String?,
  libraryTab: LibraryTab,
  folderContents: FolderContents,
  playlists: List<SavedPlaylistEntry>,
  selectedLibraryTrackIds: Set<String>,
  selectedPlaylistId: String?,
  onCollapse: () -> Unit,
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
  onClearQueue: () -> Unit,
  onSortQueue: (PlaylistSortOrder) -> Unit,
  onReverseQueue: () -> Unit,
  onRandomizeQueue: () -> Unit,
  onNewPlaylist: () -> Unit,
  onSavePlaylist: () -> Unit,
  onLoadPlaylist: () -> Unit,
  onSelectLibraryTab: (LibraryTab) -> Unit,
  onNavigateUp: () -> Unit,
  onOpenFolder: (FolderEntry) -> Unit,
  onPlayFolder: (FolderEntry) -> Unit,
  onEnqueueFolder: (FolderEntry) -> Unit,
  onToggleLibraryTrackSelection: (AudioTrack) -> Unit,
  onPlayLibraryTrack: (AudioTrack) -> Unit,
  onEnqueueLibraryTrack: (AudioTrack) -> Unit,
  onSelectLibraryPlaylist: (SavedPlaylistEntry) -> Unit,
  onPlayLibraryPlaylist: (SavedPlaylistEntry) -> Unit,
  onEnqueueLibraryPlaylist: (SavedPlaylistEntry) -> Unit,
  onPlayPrimaryLibrarySelection: () -> Unit,
  onEnqueuePrimaryLibrarySelection: () -> Unit,
  onPrev: () -> Unit,
  onPlay: () -> Unit,
  onPause: () -> Unit,
  onStop: () -> Unit,
  onNext: () -> Unit,
  modifier: Modifier = Modifier,
  onMoveTrack: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
) {
  val skin = LocalDroidAmpSkin.current
  var dragAccumulator by remember { mutableFloatStateOf(0f) }

  HardwarePanel(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Swipe-down & tappable Winamp titlebar
      val title =
        if (sheetMode == SheetMode.PLAYLIST) "DROIDAMP PLAYLIST ▼" else "DROIDAMP LIBRARY ▼"
      ChassisTitleBar(
        title = title,
        showDragGrip = true,
        modifier =
          Modifier
            .pointerInput(Unit) {
              detectVerticalDragGestures(
                onDragStart = { dragAccumulator = 0f },
                onDragEnd = {
                  if (dragAccumulator > 32f) onCollapse()
                  dragAccumulator = 0f
                },
                onDragCancel = { dragAccumulator = 0f },
              ) { _, dragAmount ->
                dragAccumulator += dragAmount
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
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
          when (sheetMode) {
            SheetMode.PLAYLIST -> {
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
                  statusBanner = statusBanner,
                  onTrackClick = onPlaylistTrackClick,
                  onTrackDoubleClick = onPlaylistTrackDoubleClick,
                  onMoveTrackUp = onMoveTrackUp,
                  onMoveTrackDown = onMoveTrackDown,
                  onJumpFilterChange = onJumpFilterChange,
                  onToggleJumpFilter = onToggleJumpFilter,
                  onSwitchToLibrary = { onSwitchSheetMode(SheetMode.LIBRARY) },
                  onOpenMicroMenu = onOpenMicroMenu,
                  onDismissMicroMenu = onDismissMicroMenu,
                  onRemoveSelected = onRemoveSelected,
                  onCropSelected = onCropSelected,
                  onClearQueue = onClearQueue,
                  onSortQueue = onSortQueue,
                  onReverseQueue = onReverseQueue,
                  onRandomizeQueue = onRandomizeQueue,
                  onNewPlaylist = onNewPlaylist,
                  onSavePlaylist = onSavePlaylist,
                  onLoadPlaylist = onLoadPlaylist,
                  onMoveTrack = onMoveTrack,
                )
              }
            }

            SheetMode.LIBRARY -> {
              LibraryBrowserView(
                hasLinkedRoots = hasLinkedRoots,
                libraryTab = libraryTab,
                folderContents = folderContents,
                playlists = playlists,
                selectedTrackIds = selectedLibraryTrackIds,
                selectedPlaylistId = selectedPlaylistId,
                onSelectMusicFolder = onSelectMusicFolder,
                onSelectTab = onSelectLibraryTab,
                onNavigateUp = onNavigateUp,
                onOpenFolder = onOpenFolder,
                onPlayFolder = onPlayFolder,
                onEnqueueFolder = onEnqueueFolder,
                onToggleTrackSelection = onToggleLibraryTrackSelection,
                onPlayTrack = onPlayLibraryTrack,
                onEnqueueTrack = onEnqueueLibraryTrack,
                onSelectPlaylist = onSelectLibraryPlaylist,
                onPlayPlaylist = onPlayLibraryPlaylist,
                onEnqueuePlaylist = onEnqueueLibraryPlaylist,
                onSwitchToPlaylist = { onSwitchSheetMode(SheetMode.PLAYLIST) },
                onPlayPrimarySelection = onPlayPrimaryLibrarySelection,
                onEnqueuePrimarySelection = onEnqueuePrimaryLibrarySelection,
              )
            }
          }
        }

        // Classic Winamp playlist bottom micro-transport bar (kept per ADR-010, no duplicate Eject)
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
