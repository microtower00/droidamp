package com.droidamp.player.ui.sheet

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import kotlin.math.abs
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.formatWinampDuration
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.storage.QueueTapAction
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.ChassisTitleBar
import com.droidamp.player.ui.components.HardwarePanel
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

internal data class PlaylistDisplayEntry(
  val originalIndex: Int,
  val track: AudioTrack,
  val rowKey: String,
)

internal fun buildPlaylistDisplayEntries(
  queue: List<AudioTrack>,
  jumpFilterQuery: String,
): List<PlaylistDisplayEntry> {
  val seenCounts = HashMap<String, Int>(queue.size)
  val allEntries =
    queue.mapIndexed { idx, track ->
      val occurrence = seenCounts.getOrDefault(track.id, 0)
      seenCounts[track.id] = occurrence + 1
      PlaylistDisplayEntry(
        originalIndex = idx,
        track = track,
        rowKey = "${track.id}#$occurrence",
      )
    }
  if (jumpFilterQuery.isBlank()) return allEntries
  return allEntries.filter { entry ->
    entry.track.displayLabel.contains(jumpFilterQuery, ignoreCase = true) ||
      entry.track.fileName.contains(jumpFilterQuery, ignoreCase = true)
  }
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
  showClearQueueConfirm: Boolean,
  onTrackClick: (Int) -> Unit,
  onTrackDoubleClick: (Int) -> Unit,
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
  modifier: Modifier = Modifier,
  showBottomActionBar: Boolean = true,
  queueTapAction: QueueTapAction = QueueTapAction.DOUBLE_TAP,
  hapticFeedbackEnabled: Boolean = true,
  onMoveTrack: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
  onMoveTracksStep: (anchorIndex: Int, delta: Int) -> Boolean = { _, _ -> false },
) {
  val skin = LocalDroidAmpSkin.current
  val density = LocalDensity.current
  val haptics = LocalHapticFeedback.current
  val rowHeightDp = 42.dp
  val rowHeightPx = with(density) { rowHeightDp.toPx() }
  val canDragReorder = jumpFilterQuery.isBlank()
  val filteredEntries =
    remember(queue, jumpFilterQuery) {
      buildPlaylistDisplayEntries(queue = queue, jumpFilterQuery = jumpFilterQuery)
    }
  val listState = rememberLazyListState()
  val jumpFocusRequester = remember { FocusRequester() }
  val keyboardController = LocalSoftwareKeyboardController.current
  var draggedRowKey by remember { mutableStateOf<String?>(null) }
  var dragOffsetY by remember { mutableFloatStateOf(0f) }
  val latestEntries by rememberUpdatedState(filteredEntries)
  val latestOnMoveTrack by rememberUpdatedState(onMoveTrack)
  val latestOnMoveTracksStep by rememberUpdatedState(onMoveTracksStep)
  val latestOnTrackClick by rememberUpdatedState(onTrackClick)
  val latestHapticEnabled by rememberUpdatedState(hapticFeedbackEnabled)

  val draggedAnchorIndex =
    draggedRowKey?.let { key ->
      filteredEntries.firstOrNull { it.rowKey == key }?.originalIndex
    }
  val activeDraggingIndices: Set<Int> =
    when {
      draggedAnchorIndex == null -> emptySet()
      draggedAnchorIndex in selectedIndices -> selectedIndices
      else -> setOf(draggedAnchorIndex)
    }

  LaunchedEffect(showJumpFilter) {
    if (showJumpFilter) {
      jumpFocusRequester.requestFocus()
      keyboardController?.show()
    }
  }

  Column(
    modifier = modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    if (showJumpFilter) {
      RecessedWell(
        modifier = Modifier.fillMaxWidth().height(34.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxSize(),
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
            modifier = Modifier.weight(1f).focusRequester(jumpFocusRequester),
          )
          BeveledButton(
            label = "×",
            onClick = {
              keyboardController?.hide()
              onToggleJumpFilter()
            },
            modifier = Modifier.height(24.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
          )
        }
      }
    }

    // Recessed pitch-black Winamp queue well (no extra LCD banner per ADR-020)
    RecessedWell(
      modifier = Modifier.weight(1f).fillMaxWidth(),
      backgroundColor = skin.playlist.normalBackground,
      contentPadding = PaddingValues(2.dp),
    ) {
      if (filteredEntries.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxSize().padding(top = 28.dp),
          contentAlignment = Alignment.TopCenter,
        ) {
          Text(
            text =
              if (queue.isEmpty()) {
                "QUEUE EMPTY — SWIPE OR TAP LIBRARY"
              } else {
                "NO MATCHING TRACKS"
              },
            style = skin.typography.vfdInfo,
            color = skin.vfd.phosphorDim,
          )
        }
      } else {
        LazyColumn(
          state = listState,
          userScrollEnabled = draggedRowKey == null,
          modifier =
            Modifier
              .fillMaxSize()
              .pointerInput(canDragReorder) {
                if (!canDragReorder) return@pointerInput
                detectDragGesturesAfterLongPress(
                  onDragStart = { offset ->
                    val hitItem =
                      listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                        offset.y >= item.offset && offset.y <= item.offset + item.size
                      }
                    val entry = hitItem?.let { latestEntries.getOrNull(it.index) }
                    if (entry != null) {
                      draggedRowKey = entry.rowKey
                      dragOffsetY = 0f
                      if (latestHapticEnabled) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                      }
                    }
                  },
                  onDrag = { change, dragAmount ->
                    val activeKey = draggedRowKey ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    dragOffsetY += dragAmount.y

                    val entries = latestEntries
                    val currentFilteredIdx = entries.indexOfFirst { it.rowKey == activeKey }
                    val currentEntry = entries.getOrNull(currentFilteredIdx) ?: return@detectDragGesturesAfterLongPress
                    var anchorOriginalIdx = currentEntry.originalIndex

                    while (dragOffsetY >= rowHeightPx * 0.55f) {
                      val moved =
                        latestOnMoveTracksStep(anchorOriginalIdx, 1) ||
                          (anchorOriginalIdx < queue.lastIndex && run {
                            latestOnMoveTrack(anchorOriginalIdx, anchorOriginalIdx + 1)
                            true
                          })
                      if (moved) {
                        anchorOriginalIdx += 1
                        dragOffsetY -= rowHeightPx
                        if (latestHapticEnabled) {
                          haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                      } else {
                        break
                      }
                    }
                    while (dragOffsetY <= -rowHeightPx * 0.55f) {
                      val moved =
                        latestOnMoveTracksStep(anchorOriginalIdx, -1) ||
                          (anchorOriginalIdx > 0 && run {
                            latestOnMoveTrack(anchorOriginalIdx, anchorOriginalIdx - 1)
                            true
                          })
                      if (moved) {
                        anchorOriginalIdx -= 1
                        dragOffsetY += rowHeightPx
                        if (latestHapticEnabled) {
                          haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                      } else {
                        break
                      }
                    }
                  },
                  onDragEnd = {
                    draggedRowKey = null
                    dragOffsetY = 0f
                  },
                  onDragCancel = {
                    draggedRowKey = null
                    dragOffsetY = 0f
                  },
                )
              },
        ) {
          items(
            items = filteredEntries,
            key = { it.rowKey },
          ) { entry ->
            val originalIndex = entry.originalIndex
            val track = entry.track
            val isCurrent = originalIndex == currentIndex
            val isSelected = originalIndex in selectedIndices
            val isAnchorDragged = draggedRowKey == entry.rowKey
            val isDragging = originalIndex in activeDraggingIndices
            val latestRowOriginalIdx by rememberUpdatedState(originalIndex)
            val rowBg =
              if (isSelected) skin.playlist.selectedBackground else skin.playlist.normalBackground
            val rowTextColor =
              when {
                isSelected -> skin.playlist.selectedText
                isCurrent -> skin.playlist.currentText
                else -> skin.playlist.normalText
              }

            // Fixed 42.dp row height prevents vertical layout shift when selecting/deselecting (ADR-023)
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .height(rowHeightDp)
                  .zIndex(if (isDragging) 2f else 0f)
                  .graphicsLayer {
                    translationY = if (isDragging) dragOffsetY else 0f
                  }
                  .background(rowBg)
                  .combinedClickable(
                    onClick = {
                      if (queueTapAction == QueueTapAction.SINGLE_TAP) {
                        onTrackDoubleClick(originalIndex)
                      } else {
                        onTrackClick(originalIndex)
                      }
                    },
                    onDoubleClick = {
                      if (queueTapAction == QueueTapAction.DOUBLE_TAP) {
                        onTrackDoubleClick(originalIndex)
                      }
                    },
                  )
                  .padding(end = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              // Generous 44dp wide × 42dp high immediate-drag left grip (moves whole selection if row is selected)
              Box(
                modifier =
                  Modifier
                    .width(44.dp)
                    .fillMaxHeight()
                    .systemGestureExclusion()
                    .pointerInput(entry.rowKey, canDragReorder) {
                      awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!canDragReorder) {
                          down.consume()
                          while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                              latestOnTrackClick(latestRowOriginalIdx)
                              break
                            }
                          }
                          return@awaitEachGesture
                        }

                        down.consume()
                        var totalDy = 0f
                        var totalDx = 0f
                        var startedDrag = false
                        var activeAnchorIdx = latestRowOriginalIdx
                        val dragStartSlop = viewConfiguration.touchSlop * 0.5f

                        while (true) {
                          val event = awaitPointerEvent()
                          val change = event.changes.firstOrNull() ?: break
                          if (!change.pressed) {
                            if (!startedDrag &&
                              abs(totalDx) < viewConfiguration.touchSlop &&
                              abs(totalDy) < viewConfiguration.touchSlop
                            ) {
                              latestOnTrackClick(activeAnchorIdx)
                            }
                            if (draggedRowKey == entry.rowKey) {
                              draggedRowKey = null
                              dragOffsetY = 0f
                            }
                            break
                          }
                          val dy = change.position.y - change.previousPosition.y
                          val dx = change.position.x - change.previousPosition.x
                          totalDy += dy
                          totalDx += dx
                          change.consume()

                          if (!startedDrag && abs(totalDy) >= dragStartSlop) {
                            startedDrag = true
                            activeAnchorIdx = latestRowOriginalIdx
                            draggedRowKey = entry.rowKey
                            dragOffsetY = totalDy
                            if (latestHapticEnabled) {
                              haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                          } else if (startedDrag) {
                            dragOffsetY += dy
                            while (dragOffsetY >= rowHeightPx * 0.55f) {
                              val moved =
                                latestOnMoveTracksStep(activeAnchorIdx, 1) ||
                                  (activeAnchorIdx < queue.lastIndex && run {
                                    latestOnMoveTrack(activeAnchorIdx, activeAnchorIdx + 1)
                                    true
                                  })
                              if (moved) {
                                activeAnchorIdx += 1
                                dragOffsetY -= rowHeightPx
                                if (latestHapticEnabled) {
                                  haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                              } else {
                                break
                              }
                            }
                            while (dragOffsetY <= -rowHeightPx * 0.55f) {
                              val moved =
                                latestOnMoveTracksStep(activeAnchorIdx, -1) ||
                                  (activeAnchorIdx > 0 && run {
                                    latestOnMoveTrack(activeAnchorIdx, activeAnchorIdx - 1)
                                    true
                                  })
                              if (moved) {
                                activeAnchorIdx -= 1
                                dragOffsetY += rowHeightPx
                                if (latestHapticEnabled) {
                                  haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                              } else {
                                break
                              }
                            }
                          }
                        }
                      }
                    },
                contentAlignment = Alignment.Center,
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                  Text(
                    text = "≡",
                    style = skin.typography.buttonLabel,
                    color = if (isSelected || isCurrent) rowTextColor else skin.playlist.indexNumberText,
                  )
                  if (isAnchorDragged && activeDraggingIndices.size > 1) {
                    Text(
                      text = "×${activeDraggingIndices.size}",
                      style = skin.typography.microLabel,
                      color = skin.vfd.phosphorBright,
                    )
                  }
                }
              }
              Text(
                text = "${originalIndex + 1}.",
                style = skin.typography.trackRow,
                color = if (isSelected || isCurrent) rowTextColor else skin.playlist.indexNumberText,
                modifier = Modifier.width(26.dp),
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
                val canMoveSelectionUp =
                  if (selectedIndices.isNotEmpty()) selectedIndices.min() > 0 else originalIndex > 0
                val canMoveSelectionDown =
                  if (selectedIndices.isNotEmpty()) selectedIndices.max() < queue.lastIndex else originalIndex < queue.lastIndex
                Spacer(Modifier.width(6.dp))
                BeveledButton(
                  label = "▲",
                  onClick = { onMoveTrackUp(originalIndex) },
                  enabled = canMoveSelectionUp,
                  modifier = Modifier.height(26.dp),
                  contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                )
                Spacer(Modifier.width(2.dp))
                BeveledButton(
                  label = "▼",
                  onClick = { onMoveTrackDown(originalIndex) },
                  enabled = canMoveSelectionDown,
                  modifier = Modifier.height(26.dp),
                  contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                )
              }
            }
          }
        }
      }
    }

    // Focused 3-Button Winamp Context Bar (ADR-019 / ADR-020: [REM] [MISC] [LIST])
    if (showBottomActionBar) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Box(modifier = Modifier.weight(1f)) {
          BeveledButton(
            label = "REM",
            onClick = { onOpenMicroMenu(PlaylistMicroMenu.REM) },
            spriteNormal = skin.sprites.mobileRemNormal,
            spritePressed = skin.sprites.mobileRemPressed,
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
                MicroMenuAction("CLEAR QUEUE", onRequestClearQueue),
              ),
            onDismiss = onDismissMicroMenu,
          )
        }

        Box(modifier = Modifier.weight(1f)) {
          BeveledButton(
            label = "MISC",
            onClick = { onOpenMicroMenu(PlaylistMicroMenu.MISC) },
            spriteNormal = skin.sprites.mobileMiscNormal,
            spritePressed = skin.sprites.mobileMiscPressed,
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
                MicroMenuAction("REVERSE QUEUE", onReverseQueue),
                MicroMenuAction("RANDOMIZE", onRandomizeQueue),
              ),
            onDismiss = onDismissMicroMenu,
          )
        }

        Box(modifier = Modifier.weight(1f)) {
          BeveledButton(
            label = "LIST",
            onClick = { onOpenMicroMenu(PlaylistMicroMenu.LIST) },
            spriteNormal = skin.sprites.mobileListNormal,
            spritePressed = skin.sprites.mobileListPressed,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            contentPadding = PaddingValues(0.dp),
          )
          RetroMicroMenuPopup(
            expanded = activeMicroMenu == PlaylistMicroMenu.LIST,
            title = "LIST OPTS",
            actions =
              listOf(
                MicroMenuAction("NEW QUEUE", onNewQueue),
                MicroMenuAction("LOAD PLAYLIST", onLoadPlaylist),
                MicroMenuAction("SAVE AS PLAYLIST...", onSavePlaylist),
              ),
            onDismiss = onDismissMicroMenu,
          )
        }
      }
    }
  }

  if (showClearQueueConfirm) {
    Dialog(onDismissRequest = onDismissClearQueueConfirm) {
      HardwarePanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
          ChassisTitleBar(title = "CLEAR QUEUE")
          Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            RecessedWell(
              modifier = Modifier.fillMaxWidth(),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
              Text(
                text = "REMOVE ALL ${queue.size} TRACKS FROM CURRENT QUEUE?",
                style = skin.typography.vfdInfo,
                color = skin.vfd.phosphorPrimary,
              )
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              BeveledButton(
                label = "CANCEL",
                onClick = onDismissClearQueueConfirm,
                modifier = Modifier.weight(1f).height(34.dp),
              )
              BeveledButton(
                label = "CLEAR",
                onClick = onConfirmClearQueue,
                highlighted = true,
                modifier = Modifier.weight(1f).height(34.dp),
              )
            }
          }
        }
      }
    }
  }
}
