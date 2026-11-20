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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.LibraryTab
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.formatWinampDuration
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.BeveledLedToggleButton
import com.droidamp.player.ui.components.RecessedWell

/**
 * Library browser (`[FOLDERS]` | `[PLAYLISTS]`) enforcing ADR-009 Select-First Track Safety:
 * - Folder row: single-tap explores; inline `[▶]` replace-plays; inline `[+]` enqueues.
 * - Track row: single-tap selects/toggles selection (never destructive); double-tap or `[▶]` replace-plays; `[+]` enqueues.
 * - Mode-specific 3-button bottom bar: `[PL]` · `[▶ PLAY]` · `[+ QUEUE]`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryBrowserView(
  hasLinkedRoots: Boolean,
  libraryTab: LibraryTab,
  folderContents: FolderContents,
  playlists: List<SavedPlaylistEntry>,
  selectedTrackIds: Set<String>,
  selectedPlaylistId: String?,
  onSelectMusicFolder: () -> Unit,
  onSelectTab: (LibraryTab) -> Unit,
  onNavigateUp: () -> Unit,
  onOpenFolder: (FolderEntry) -> Unit,
  onPlayFolder: (FolderEntry) -> Unit,
  onEnqueueFolder: (FolderEntry) -> Unit,
  onToggleTrackSelection: (AudioTrack) -> Unit,
  onPlayTrack: (AudioTrack) -> Unit,
  onEnqueueTrack: (AudioTrack) -> Unit,
  onSelectPlaylist: (SavedPlaylistEntry) -> Unit,
  onPlayPlaylist: (SavedPlaylistEntry) -> Unit,
  onEnqueuePlaylist: (SavedPlaylistEntry) -> Unit,
  onSwitchToPlaylist: () -> Unit,
  onPlayPrimarySelection: () -> Unit,
  onEnqueuePrimarySelection: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current

  Column(
    modifier = modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    // Top-level Library Switcher: [FOLDERS] | [PLAYLISTS]
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      BeveledLedToggleButton(
        label = "FOLDERS",
        checked = libraryTab == LibraryTab.FOLDERS,
        onClick = { onSelectTab(LibraryTab.FOLDERS) },
        modifier = Modifier.weight(1f).height(28.dp),
      )
      BeveledLedToggleButton(
        label = "PLAYLISTS",
        checked = libraryTab == LibraryTab.PLAYLISTS,
        onClick = { onSelectTab(LibraryTab.PLAYLISTS) },
        modifier = Modifier.weight(1f).height(28.dp),
      )
    }

    if (!hasLinkedRoots) {
      EmptyFolderWell(
        onSelectMusicFolder = onSelectMusicFolder,
        modifier = Modifier.weight(1f).fillMaxWidth(),
      )
    } else {
      // Distinct breadcrumb strip in FOLDERS mode for instant mode recognition (ADR-008)
      if (libraryTab == LibraryTab.FOLDERS) {
        val breadcrumb =
          if (folderContents.currentPathSegments.isEmpty()) {
            "SAF ROOTS"
          } else {
            folderContents.currentPathSegments.joinToString(" / ").uppercase()
          }
        RecessedWell(
          modifier = Modifier.fillMaxWidth(),
          backgroundColor = skin.playlist.breadcrumbBackground,
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
          Text(
            text = breadcrumb,
            style = skin.typography.microLabel,
            color = skin.playlist.breadcrumbText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      // Main recessed file/playlist browser list
      RecessedWell(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        backgroundColor = skin.playlist.normalBackground,
        contentPadding = PaddingValues(2.dp),
      ) {
        when (libraryTab) {
          LibraryTab.FOLDERS -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
              if (folderContents.canNavigateUp) {
                item(key = "nav-up") {
                  Row(
                    modifier =
                      Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNavigateUp)
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      text = "[..]  PARENT FOLDER",
                      style = skin.typography.trackRow,
                      color = skin.playlist.breadcrumbText,
                    )
                  }
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
                    onClick = { onEnqueueFolder(folder) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                  )
                }
              }

              // Track rows: single-tap = SELECT (safe); double-tap / [▶] = replace-play; [+] = enqueue
              items(items = folderContents.tracks, key = { "trk-${it.id}" }) { track ->
                val isSelected = track.id in selectedTrackIds
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
                  Spacer(Modifier.width(6.dp))
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
                    onClick = { onEnqueueTrack(track) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                  )
                }
              }

              if (!folderContents.canNavigateUp &&
                folderContents.folders.isEmpty() &&
                folderContents.tracks.isEmpty()
              ) {
                item(key = "empty-dir") {
                  Box(
                    modifier = Modifier.fillParentMaxSize(),
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

          LibraryTab.PLAYLISTS -> {
            if (playlists.isEmpty()) {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                  text = "NO .M3U / .PLS PLAYLISTS FOUND",
                  style = skin.typography.vfdInfo,
                  color = skin.vfd.phosphorDim,
                )
              }
            } else {
              LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                      onClick = { onEnqueuePlaylist(pl) },
                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

    // Mode-Specific 3-Button Bottom Bar (ADR-010: [PL] [▶ PLAY] [+ QUEUE])
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      BeveledButton(
        label = "PL",
        onClick = onSwitchToPlaylist,
        highlighted = true,
        modifier = Modifier.weight(0.9f).height(32.dp),
        contentPadding = PaddingValues(0.dp),
      )
      val playLabel =
        if (selectedTrackIds.isNotEmpty() && libraryTab == LibraryTab.FOLDERS) {
          "▶ PLAY (${selectedTrackIds.size})"
        } else {
          "▶ PLAY"
        }
      BeveledButton(
        label = playLabel,
        onClick = onPlayPrimarySelection,
        enabled = hasLinkedRoots,
        modifier = Modifier.weight(1.1f).height(32.dp),
        contentPadding = PaddingValues(0.dp),
      )
      BeveledButton(
        label = "+ QUEUE",
        onClick = onEnqueuePrimarySelection,
        enabled = hasLinkedRoots,
        modifier = Modifier.weight(1.1f).height(32.dp),
        contentPadding = PaddingValues(0.dp),
      )
    }
  }
}
