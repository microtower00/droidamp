package com.droidamp.player.ui.sheet

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.formatWinampDuration
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.HardwareText
import com.droidamp.player.ui.components.MicroMenuAction
import com.droidamp.player.ui.components.RecessedWell
import com.droidamp.player.ui.components.RetroMicroMenuPopup

enum class PlaylistMicroMenu {
  NONE,
  REM,
  MISC,
  LIST,
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistEditorView(
  queue: List<AudioTrack>,
  currentIndex: Int,
  selectedIndices: Set<Int>,
  showJumpFilter: Boolean,
  jumpFilterQuery: String,
  activeMicroMenu: PlaylistMicroMenu,
  statusBanner: String?,
  onTrackClick: (Int) -> Unit,
  onTrackDoubleClick: (Int) -> Unit,
  onMoveTrackUp: (Int) -> Unit,
  onMoveTrackDown: (Int) -> Unit,
  onJumpFilterChange: (String) -> Unit,
  onToggleJumpFilter: () -> Unit,
  onSwitchToLibrary: () -> Unit,
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
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  val filteredEntries =
    queue.mapIndexedNotNull { idx, track ->
      if (jumpFilterQuery.isBlank() ||
        track.displayLabel.contains(jumpFilterQuery, ignoreCase = true) ||
        track.fileName.contains(jumpFilterQuery, ignoreCase = true)
      ) {
        idx to track
      } else {
        null
      }
    }

  Column(
    modifier = modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    if (showJumpFilter) {
      RecessedWell(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          HardwareText(text = "JUMP:", color = skin.vfd.phosphorDim)
          BasicTextField(
            value = jumpFilterQuery,
            onValueChange = onJumpFilterChange,
            singleLine = true,
            textStyle = skin.typography.trackRow.copy(color = skin.vfd.phosphorPrimary),
            cursorBrush = SolidColor(skin.vfd.phosphorPrimary),
            modifier = Modifier.weight(1f),
          )
          BeveledButton(
            label = "X",
            onClick = onToggleJumpFilter,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }
    }

    if (statusBanner != null) {
      RecessedWell(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
      ) {
        Text(
          text = statusBanner.uppercase(),
          style = skin.typography.microLabel,
          color = skin.vfd.ledAmber,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    // Recessed pitch-black Winamp playlist well
    RecessedWell(
      modifier = Modifier.weight(1f).fillMaxWidth(),
      backgroundColor = skin.playlist.normalBackground,
      contentPadding = PaddingValues(2.dp),
    ) {
      if (filteredEntries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            text = if (queue.isEmpty()) "PLAYLIST EMPTY — TAP [LIB] TO ADD TRACKS" else "NO MATCHING TRACKS",
            style = skin.typography.vfdInfo,
            color = skin.vfd.phosphorDim,
          )
        }
      } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
          itemsIndexed(
            items = filteredEntries,
            key = { _, pair -> "${pair.first}-${pair.second.id}" },
          ) { _, (originalIndex, track) ->
            val isCurrent = originalIndex == currentIndex
            val isSelected = originalIndex in selectedIndices
            val rowBg =
              if (isSelected) skin.playlist.selectedBackground else skin.playlist.normalBackground
            val rowTextColor =
              when {
                isSelected -> skin.playlist.selectedText
                isCurrent -> skin.playlist.currentText
                else -> skin.playlist.normalText
              }

            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .background(rowBg)
                  .combinedClickable(
                    onClick = { onTrackClick(originalIndex) },
                    onDoubleClick = { onTrackDoubleClick(originalIndex) },
                  )
                  .padding(horizontal = 6.dp, vertical = 7.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "${originalIndex + 1}.",
                style = skin.typography.trackRow,
                color = if (isSelected || isCurrent) rowTextColor else skin.playlist.indexNumberText,
                modifier = Modifier.width(28.dp),
              )
              Text(
                text = track.displayLabel,
                style = skin.typography.trackRow,
                color = rowTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              Spacer(Modifier.width(6.dp))
              Text(
                text = formatWinampDuration(track.durationMs),
                style = skin.typography.trackRow,
                color = rowTextColor,
              )
              if (isSelected) {
                Spacer(Modifier.width(6.dp))
                BeveledButton(
                  label = "▲",
                  onClick = { onMoveTrackUp(originalIndex) },
                  enabled = originalIndex > 0,
                  contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(2.dp))
                BeveledButton(
                  label = "▼",
                  onClick = { onMoveTrackDown(originalIndex) },
                  enabled = originalIndex < queue.lastIndex,
                  contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp),
                )
              }
            }
          }
        }
      }
    }

    // Mode-Specific 4-Button Bottom Bar (ADR-010: [LIB] [REM] [MISC] [LIST], zero dead buttons)
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      BeveledButton(
        label = "LIB",
        onClick = onSwitchToLibrary,
        highlighted = true,
        modifier = Modifier.weight(1f).height(32.dp),
        contentPadding = PaddingValues(0.dp),
      )

      Box(modifier = Modifier.weight(1f)) {
        BeveledButton(
          label = "REM",
          onClick = { onOpenMicroMenu(PlaylistMicroMenu.REM) },
          modifier = Modifier.fillMaxWidth().height(32.dp),
          contentPadding = PaddingValues(0.dp),
        )
        RetroMicroMenuPopup(
          expanded = activeMicroMenu == PlaylistMicroMenu.REM,
          title = "REMOVE",
          actions =
            listOf(
              MicroMenuAction("REM SELECTED", onRemoveSelected),
              MicroMenuAction("CROP SELECTED", onCropSelected),
              MicroMenuAction("CLEAR LIST", onClearQueue),
            ),
          onDismiss = onDismissMicroMenu,
        )
      }

      Box(modifier = Modifier.weight(1f)) {
        BeveledButton(
          label = "MISC",
          onClick = { onOpenMicroMenu(PlaylistMicroMenu.MISC) },
          modifier = Modifier.fillMaxWidth().height(32.dp),
          contentPadding = PaddingValues(0.dp),
        )
        RetroMicroMenuPopup(
          expanded = activeMicroMenu == PlaylistMicroMenu.MISC,
          title = "MISC OPTS",
          actions =
            listOf(
              MicroMenuAction("JUMP / FILTER", onToggleJumpFilter),
              MicroMenuAction("SORT BY TITLE") { onSortQueue(PlaylistSortOrder.TITLE) },
              MicroMenuAction("SORT BY FILE") { onSortQueue(PlaylistSortOrder.FILENAME) },
              MicroMenuAction("REVERSE LIST", onReverseQueue),
              MicroMenuAction("RANDOMIZE", onRandomizeQueue),
            ),
          onDismiss = onDismissMicroMenu,
        )
      }

      Box(modifier = Modifier.weight(1f)) {
        BeveledButton(
          label = "LIST",
          onClick = { onOpenMicroMenu(PlaylistMicroMenu.LIST) },
          modifier = Modifier.fillMaxWidth().height(32.dp),
          contentPadding = PaddingValues(0.dp),
        )
        RetroMicroMenuPopup(
          expanded = activeMicroMenu == PlaylistMicroMenu.LIST,
          title = "LIST OPTS",
          actions =
            listOf(
              MicroMenuAction("NEW LIST", onNewPlaylist),
              MicroMenuAction("LOAD PLAYLIST", onLoadPlaylist),
              MicroMenuAction("SAVE .M3U8", onSavePlaylist),
            ),
          onDismiss = onDismissMicroMenu,
        )
      }
    }
  }
}
