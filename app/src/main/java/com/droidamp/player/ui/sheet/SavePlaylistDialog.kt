package com.droidamp.player.ui.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.droidamp.player.domain.model.PlaylistExportFormat
import com.droidamp.player.domain.model.formatWinampDuration
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.BeveledLedToggleButton
import com.droidamp.player.ui.components.ChassisTitleBar
import com.droidamp.player.ui.components.HardwarePanel
import com.droidamp.player.ui.components.HardwareText
import com.droidamp.player.ui.components.RecessedWell

/**
 * Skeuomorphic retro VFD dialog for saving the live Queue (or selected tracks)
 * as a named `.m3u8` or `.pls` playlist file (ADR-020).
 */
@Composable
fun SavePlaylistDialog(
  totalTrackCount: Int,
  totalDurationMs: Long,
  selectedTrackCount: Int,
  existingPlaylistNames: Set<String>,
  onDismiss: () -> Unit,
  onConfirmSave: (baseName: String, format: PlaylistExportFormat, selectedOnly: Boolean) -> Unit,
) {
  val skin = LocalDroidAmpSkin.current
  var nameInput by remember { mutableStateOf("My Playlist") }
  var selectedFormat by remember { mutableStateOf(PlaylistExportFormat.M3U8) }
  var selectedOnly by remember { mutableStateOf(false) }

  val sanitizedBase =
    nameInput
      .trim()
      .removeSuffix(".m3u8")
      .removeSuffix(".m3u")
      .removeSuffix(".pls")
      .replace(Regex("[\\\\/:*?\"<>|]"), "_")
      .trim()
  val resolvedFileName =
    if (sanitizedBase.isNotEmpty()) "$sanitizedBase.${selectedFormat.extension}" else ""
  val willOverwrite =
    sanitizedBase.isNotEmpty() &&
      existingPlaylistNames.any { existing ->
        existing.equals(sanitizedBase, ignoreCase = true) ||
          existing.equals(resolvedFileName, ignoreCase = true)
      }

  Dialog(onDismissRequest = onDismiss) {
    HardwarePanel(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth()) {
        ChassisTitleBar(title = "SAVE QUEUE AS PLAYLIST")

        Column(
          modifier = Modifier.fillMaxWidth().padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          HardwareText(text = "PLAYLIST NAME:")
          RecessedWell(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp),
          ) {
            BasicTextField(
              value = nameInput,
              onValueChange = { nameInput = it },
              singleLine = true,
              textStyle = skin.typography.trackRow.copy(color = skin.vfd.phosphorPrimary),
              cursorBrush = SolidColor(skin.vfd.phosphorPrimary),
              modifier = Modifier.fillMaxWidth(),
            )
          }

          HardwareText(text = "FORMAT:")
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            BeveledLedToggleButton(
              label = ".M3U8",
              checked = selectedFormat == PlaylistExportFormat.M3U8,
              onClick = { selectedFormat = PlaylistExportFormat.M3U8 },
              modifier = Modifier.weight(1f).height(28.dp),
            )
            BeveledLedToggleButton(
              label = ".PLS",
              checked = selectedFormat == PlaylistExportFormat.PLS,
              onClick = { selectedFormat = PlaylistExportFormat.PLS },
              modifier = Modifier.weight(1f).height(28.dp),
            )
          }

          if (selectedTrackCount > 0) {
            HardwareText(text = "SCOPE:")
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              BeveledLedToggleButton(
                label = "ALL ($totalTrackCount)",
                checked = !selectedOnly,
                onClick = { selectedOnly = false },
                compact = true,
                modifier = Modifier.weight(1f).height(28.dp),
              )
              BeveledLedToggleButton(
                label = "SELECTED ($selectedTrackCount)",
                checked = selectedOnly,
                onClick = { selectedOnly = true },
                compact = true,
                modifier = Modifier.weight(1f).height(28.dp),
              )
            }
          }

          RecessedWell(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              val scopeSummary =
                if (selectedOnly && selectedTrackCount > 0) {
                  val word = if (selectedTrackCount == 1) "SELECTED TRACK" else "SELECTED TRACKS"
                  "$selectedTrackCount $word"
                } else {
                  val word = if (totalTrackCount == 1) "TRACK" else "TRACKS"
                  "$totalTrackCount $word · ${formatWinampDuration(totalDurationMs)}"
                }
              Text(
                text = if (resolvedFileName.isNotEmpty()) "${resolvedFileName.uppercase()} · $scopeSummary" else "ENTER A VALID NAME",
                style = skin.typography.microLabel,
                color = skin.vfd.phosphorPrimary,
              )
              if (willOverwrite) {
                Text(
                  text = "OVERWRITES EXISTING PLAYLIST",
                  style = skin.typography.microLabel,
                  color = skin.vfd.ledAmber,
                )
              }
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            BeveledButton(
              label = "CANCEL",
              onClick = onDismiss,
              modifier = Modifier.weight(1f).height(34.dp),
            )
            BeveledButton(
              label = "SAVE",
              onClick = {
                if (sanitizedBase.isNotEmpty()) {
                  onConfirmSave(sanitizedBase, selectedFormat, selectedOnly)
                }
              },
              enabled = sanitizedBase.isNotEmpty(),
              highlighted = sanitizedBase.isNotEmpty(),
              modifier = Modifier.weight(1f).height(34.dp),
            )
          }
        }
      }
    }
  }
}
