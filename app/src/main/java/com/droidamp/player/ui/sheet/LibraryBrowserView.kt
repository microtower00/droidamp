package com.droidamp.player.ui.sheet

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.LibraryViewLevel
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.formatWinampDuration
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.RecessedWell

/**
 * iPod-style Library browser (`ROOT` -> `FOLDERS` | `PLAYLISTS`) enforcing ADR-009 & ADR-020:
 * - Top-level root presents `FOLDERS` and `PLAYLISTS` cleanly with zero double tab-bar clutter.
 * - Folder row: single-tap explores; inline `[▶]` replace-plays & switches to QUEUE; `[+]` enqueues & stays.
 * - Track row: single-tap selects (never destructive); double-tap or `[▶]` strict Winamp single-track replace-play;
 *   `[+]` enqueues & stays in LIBRARY with haptic tick, `[Q×N]` row badge, and transient breadcrumb confirmation.
 * - Bottom contextual action bar (`[▶ PLAY (N)]`, `[+ QUEUE (N)]`, `[✕]`) appears ONLY when 1+ items are selected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryBrowserView(
  hasLinkedRoots: Boolean,
  libraryViewLevel: LibraryViewLevel,
  folderContents: FolderContents,
  playlists: List<SavedPlaylistEntry>,
  selectedTrackIds: Set<String>,
  selectedPlaylistId: String?,
  queueCountByTrackId: Map<String, Int>,
  libraryFeedbackBanner: String?,
  onSelectMusicFolder: () -> Unit,
  onOpenLibraryRoot: () -> Unit,
  onOpenLibraryFolders: () -> Unit,
  onOpenLibraryPlaylists: () -> Unit,
  onNavigateUp: () -> Unit,
  onOpenFolder: (FolderEntry) -> Unit,
  onPlayFolder: (FolderEntry) -> Unit,
  onEnqueueFolder: (FolderEntry) -> Unit,
  onPlayCurrentFolder: () -> Unit,
  onEnqueueCurrentFolder: () -> Unit,
  onToggleTrackSelection: (AudioTrack) -> Unit,
  onPlayTrack: (AudioTrack) -> Unit,
  onEnqueueTrack: (AudioTrack) -> Unit,
  onSelectPlaylist: (SavedPlaylistEntry) -> Unit,
  onPlayPlaylist: (SavedPlaylistEntry) -> Unit,
  onEnqueuePlaylist: (SavedPlaylistEntry) -> Unit,
  onPlayPrimarySelection: () -> Unit,
  onEnqueuePrimarySelection: () -> Unit,
  onClearSelection: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  val haptics = LocalHapticFeedback.current

  val hasSelection =
    when (libraryViewLevel) {
      LibraryViewLevel.FOLDERS -> selectedTrackIds.isNotEmpty()
      LibraryViewLevel.PLAYLISTS -> selectedPlaylistId != null
      LibraryViewLevel.ROOT -> false
    }

  Column(
    modifier = modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    if (!hasLinkedRoots) {
      EmptyFolderWell(
        onSelectMusicFolder = onSelectMusicFolder,
        modifier = Modifier.weight(1f).fillMaxWidth(),
      )
    } else {
      // Breadcrumb bar (doubles as transient 2s enqueue confirmation well with zero layout jump)
      val breadcrumbText =
        libraryFeedbackBanner
          ?: when (libraryViewLevel) {
            LibraryViewLevel.ROOT -> "LIBRARY"
            LibraryViewLevel.PLAYLISTS -> "LIBRARY / PLAYLISTS"
            LibraryViewLevel.FOLDERS -> {
              if (folderContents.currentPathSegments.isEmpty()) {
                "LIBRARY / FOLDERS"
              } else {
                "LIBRARY / " + folderContents.currentPathSegments.joinToString(" / ").uppercase()
              }
            }
          }
      val isFeedback = libraryFeedbackBanner != null

      RecessedWell(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = if (isFeedback) skin.vfd.wellBackground else skin.playlist.breadcrumbBackground,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = breadcrumbText,
            style = skin.typography.microLabel,
            color = if (isFeedback) skin.vfd.phosphorBright else skin.playlist.breadcrumbText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          // Inline whole-folder Play / Queue actions when inside a browsable folder
          if (libraryViewLevel == LibraryViewLevel.FOLDERS &&
            folderContents.currentFolderUri != null &&
            !isFeedback
          ) {
            BeveledButton(
              label = "▶ ALL",
              onClick = onPlayCurrentFolder,
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            )
            BeveledButton(
              label = "+ ALL",
              onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onEnqueueCurrentFolder()
              },
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }
      }

      // Main recessed browser list
      RecessedWell(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        backgroundColor = skin.playlist.normalBackground,
        contentPadding = PaddingValues(2.dp),
      ) {
        when (libraryViewLevel) {
          LibraryViewLevel.ROOT -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
              item(key = "root-folders") {
                Row(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .clickable(onClick = onOpenLibraryFolders)
                      .padding(horizontal = 8.dp, vertical = 12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "> [DIR] FOLDERS",
                      style = skin.typography.trackRow,
                      color = skin.playlist.folderAccentText,
                    )
                    val sub =
                      if (folderContents.currentPathSegments.isNotEmpty()) {
                        "RESUME: ${folderContents.currentPathSegments.joinToString(" / ").uppercase()}"
                      } else {
                        "BROWSE LINKED MUSIC FOLDERS"
                      }
                    Text(
                      text = sub,
                      style = skin.typography.microLabel,
                      color = skin.vfd.phosphorDim,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
              }

              item(key = "root-playlists") {
                Row(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .clickable(onClick = onOpenLibraryPlaylists)
                      .padding(horizontal = 8.dp, vertical = 12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "> [LST] PLAYLISTS",
                      style = skin.typography.trackRow,
                      color = skin.playlist.normalText,
                    )
                    Text(
                      text = "${playlists.size} SAVED .M3U / .M3U8 / .PLS",
                      style = skin.typography.microLabel,
                      color = skin.vfd.phosphorDim,
                    )
                  }
                }
              }
            }
          }

          LibraryViewLevel.FOLDERS -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
              item(key = "nav-up") {
                Row(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .clickable(
                        onClick = {
                          if (folderContents.canNavigateUp) {
                            onNavigateUp()
                          } else {
                            onOpenLibraryRoot()
                          }
                        },
                      )
                      .padding(horizontal = 8.dp, vertical = 9.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text =
                      if (folderContents.canNavigateUp) {
                        "[..]  PARENT FOLDER"
                      } else {
                        "[..]  BACK TO LIBRARY"
                      },
                    style = skin.typography.trackRow,
                    color = skin.playlist.breadcrumbText,
                  )
                }
              }

              // Folder rows: tap = explore; inline [▶] / [+]
              items(items = folderContents.folders, key = { "dir-${it.uriString}" }) { folder ->
                Row(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .clickable { onOpenFolder(folder) }
                      .padding(horizontal = 6.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = "[DIR] ${folder.name}",
                    style = skin.typography.trackRow,
                    color = skin.playlist.folderAccentText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                  )
                  Spacer(Modifier.width(6.dp))
                  BeveledButton(
                    label = "▶",
                    onClick = { onPlayFolder(folder) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                  )
                  Spacer(Modifier.width(4.dp))
                  BeveledButton(
                    label = "+",
                    onClick = {
                      haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                      onEnqueueFolder(folder)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                  )
                }
              }

              // Track rows: single-tap = SELECT (safe); double-tap / [▶] = strict Winamp single-track play; [+] = enqueue
              items(items = folderContents.tracks, key = { "trk-${it.id}" }) { track ->
                val isSelected = track.id in selectedTrackIds
                val inQueueCount = queueCountByTrackId[track.id] ?: 0
                val rowBg =
                  if (isSelected) skin.playlist.selectedBackground else skin.playlist.normalBackground
                val rowFg =
                  if (isSelected) skin.playlist.selectedText else skin.playlist.normalText

                Row(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .background(rowBg)
                      .combinedClickable(
                        onClick = { onToggleTrackSelection(track) },
                        onDoubleClick = { onPlayTrack(track) },
                      )
                      .padding(horizontal = 6.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = track.displayLabel,
                    style = skin.typography.trackRow,
                    color = rowFg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                  )
                  if (inQueueCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                      text = if (inQueueCount == 1) "[Q]" else "[Q×$inQueueCount]",
                      style = skin.typography.microLabel,
                      color = if (isSelected) rowFg else skin.vfd.phosphorPrimary,
                    )
                  }
                  Spacer(Modifier.width(5.dp))
                  Text(
                    text = formatWinampDuration(track.durationMs),
                    style = skin.typography.microLabel,
                    color = rowFg,
                  )
                  Spacer(Modifier.width(6.dp))
                  BeveledButton(
                    label = "▶",
                    onClick = { onPlayTrack(track) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                  )
                  Spacer(Modifier.width(4.dp))
                  BeveledButton(
                    label = "+",
                    onClick = {
                      haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                      onEnqueueTrack(track)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                  )
                }
              }

              if (folderContents.folders.isEmpty() && folderContents.tracks.isEmpty()) {
                item(key = "empty-dir") {
                  Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                  ) {
                    Text(
                      text = "EMPTY FOLDER",
                      style = skin.typography.vfdInfo,
                      color = skin.vfd.phosphorDim,
                    )
                  }
                }
              }
            }
          }

          LibraryViewLevel.PLAYLISTS -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
              item(key = "pl-back") {
                Row(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .clickable(onClick = onOpenLibraryRoot)
                      .padding(horizontal = 8.dp, vertical = 9.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = "[..]  BACK TO LIBRARY",
                    style = skin.typography.trackRow,
                    color = skin.playlist.breadcrumbText,
                  )
                }
              }

              if (playlists.isEmpty()) {
                item(key = "empty-playlists") {
                  Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                  ) {
                    Text(
                      text = "NO .M3U / .PLS PLAYLISTS FOUND",
                      style = skin.typography.vfdInfo,
                      color = skin.vfd.phosphorDim,
                    )
                  }
                }
              } else {
                items(items = playlists, key = { it.id }) { pl ->
                  val isSelected = pl.id == selectedPlaylistId
                  val rowBg =
                    if (isSelected) skin.playlist.selectedBackground else skin.playlist.normalBackground
                  val rowFg =
                    if (isSelected) skin.playlist.selectedText else skin.playlist.normalText
                  Row(
                    modifier =
                      Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .combinedClickable(
                          onClick = { onSelectPlaylist(pl) },
                          onDoubleClick = { onPlayPlaylist(pl) },
                        )
                        .padding(horizontal = 6.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        text = "[${pl.format.uppercase()}] ${pl.name}",
                        style = skin.typography.trackRow,
                        color = rowFg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                      Text(
                        text = "${pl.trackCount} TRACKS · ${formatWinampDuration(pl.totalDurationMs)}",
                        style = skin.typography.microLabel,
                        color = if (isSelected) rowFg else skin.vfd.phosphorDim,
                      )
                    }
                    Spacer(Modifier.width(6.dp))
                    BeveledButton(
                      label = "▶",
                      onClick = { onPlayPlaylist(pl) },
                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    BeveledButton(
                      label = "+",
                      onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onEnqueuePlaylist(pl)
                      },
                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    )
                  }
                }
              }
            }
          }
        }
      }

      // Contextual Selection Bar: ONLY visible when 1+ items are selected (ADR-020)
      if (hasSelection) {
        val countSuffix =
          if (libraryViewLevel == LibraryViewLevel.FOLDERS && selectedTrackIds.isNotEmpty()) {
            " (${selectedTrackIds.size})"
          } else {
            ""
          }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          BeveledButton(
            label = "▶ PLAY$countSuffix",
            onClick = onPlayPrimarySelection,
            highlighted = true,
            modifier = Modifier.weight(1.2f).height(32.dp),
            contentPadding = PaddingValues(0.dp),
          )
          BeveledButton(
            label = "+ QUEUE$countSuffix",
            onClick = {
              haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              onEnqueuePrimarySelection()
            },
            modifier = Modifier.weight(1.2f).height(32.dp),
            contentPadding = PaddingValues(0.dp),
          )
          BeveledButton(
            label = "✕",
            onClick = onClearSelection,
            modifier = Modifier.width(38.dp).height(32.dp),
            contentPadding = PaddingValues(0.dp),
          )
        }
      }
    }
  }
}
