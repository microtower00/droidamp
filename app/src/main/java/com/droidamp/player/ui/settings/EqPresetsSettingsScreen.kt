package com.droidamp.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.interop.IniAnalysisResult
import com.droidamp.player.interop.Q1AnalysisResult
import com.droidamp.player.interop.Q2AnalysisResult
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.RecessedWell
import com.droidamp.player.ui.components.recessedBevel

@Composable
fun EqPresetsSettingsScreen(
  activePresetName: String,
  userPresets: List<EqPreset>,
  autoEqRules: List<AutoEqRule>,
  currentTrack: AudioTrack?,
  pendingMigrationDialog: PendingMigrationDialog?,
  onApplyPreset: (EqPreset) -> Unit,
  onResetFlat: () -> Unit,
  onImportEqfClick: () -> Unit,
  onExportEqfClick: () -> Unit,
  onSaveAutoLoadForTrack: (AudioTrack) -> Unit,
  onSaveAutoLoadForFolder: (AudioTrack) -> Unit,
  onDeleteUserPreset: (String) -> Unit,
  onDeleteAutoRule: (String) -> Unit,
  onImportQ1Click: () -> Unit,
  onExportQ1Click: () -> Unit,
  onImportQ2Click: () -> Unit,
  onExportQ2Click: () -> Unit,
  onConfirmIniImport: (IniAnalysisResult) -> Unit,
  onConfirmQ1Import: (Q1AnalysisResult, replaceAll: Boolean) -> Unit,
  onConfirmQ2Import: (Q2AnalysisResult, replaceAll: Boolean) -> Unit,
  onDismissMigrationDialog: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current

  SettingsSubScreenScaffold(
    title = "EQ PRESETS & AUTO-EQ",
    onBack = onBack,
    modifier = modifier,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SettingsSectionHeader(title = "CURRENT EQ & .EQF FILE")
      SettingsCardWell {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          RecessedWell(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
          ) {
            Text(
              text = "Active Preset: ${activePresetName.ifBlank { "CUSTOM" }.uppercase()}",
              style = skin.typography.vfdInfo,
              color = skin.vfd.phosphorPrimary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          BeveledButton(
            label = "RESET FLAT",
            onClick = onResetFlat,
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          BeveledButton(
            label = "IMPORT .EQF",
            onClick = onImportEqfClick,
            highlighted = true,
            modifier = Modifier.weight(1f).height(32.dp),
          )
          BeveledButton(
            label = "EXPORT .EQF",
            onClick = onExportEqfClick,
            modifier = Modifier.weight(1f).height(32.dp),
          )
        }

        SettingsDivider()

        val canPinTrack = currentTrack != null
        AutoEqPinRow(
          title = "PIN AUTO-EQ FOR TRACK",
          detail = currentTrack?.fileName ?: "No track loaded",
          enabled = canPinTrack,
          onClick = { currentTrack?.let(onSaveAutoLoadForTrack) },
        )

        SettingsDivider()

        val canPinFolder = currentTrack != null && currentTrack.folderPath.isNotBlank()
        AutoEqPinRow(
          title = "PIN AUTO-EQ FOR FOLDER",
          detail = currentTrack?.folderPath?.ifBlank { "No folder path" } ?: "No track loaded",
          enabled = canPinFolder,
          onClick = { currentTrack?.let(onSaveAutoLoadForFolder) },
        )
      }

      SettingsSectionHeader(title = "SAVED CUSTOM PRESETS (.Q1)")
      SettingsCardWell {
        if (userPresets.isEmpty()) {
          RecessedWell(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(8.dp),
          ) {
            Text(
              text = "No custom presets saved yet. Use + SAVE CURRENT AS NEW… in the EQ PRESETS menu.",
              style = skin.typography.microLabel,
              color = skin.vfd.phosphorDim,
            )
          }
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            userPresets.forEach { preset ->
              val isCurrent = preset.name.equals(activePresetName, ignoreCase = true)
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .background(if (isCurrent) skin.playlist.selectedBackground else skin.vfd.wellBackground)
                    .recessedBevel(skin, thickness = 1.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Box(
                  modifier =
                    Modifier
                      .size(width = 5.dp, height = 8.dp)
                      .background(if (isCurrent) skin.vfd.ledOn else skin.vfd.ledOff)
                      .recessedBevel(skin, thickness = 1.dp),
                )
                Text(
                  text = preset.name.uppercase(),
                  style = skin.typography.buttonLabel,
                  color = if (isCurrent) skin.playlist.selectedText else skin.vfd.phosphorPrimary,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f),
                )
                BeveledButton(
                  label = "LOAD",
                  onClick = { onApplyPreset(preset) },
                  highlighted = isCurrent,
                  modifier = Modifier.height(24.dp),
                  contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                )
                BeveledButton(
                  label = "DEL",
                  onClick = { onDeleteUserPreset(preset.name) },
                  modifier = Modifier.height(24.dp),
                  contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                )
              }
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          BeveledButton(
            label = "IMPORT .Q1",
            onClick = onImportQ1Click,
            highlighted = true,
            modifier = Modifier.weight(1f).height(32.dp),
          )
          BeveledButton(
            label = "EXPORT .Q1",
            onClick = onExportQ1Click,
            modifier = Modifier.weight(1f).height(32.dp),
          )
        }
      }

      SettingsSectionHeader(title = "AUTO-EQ RULES (.Q2)")
      SettingsCardWell {
        if (autoEqRules.isEmpty()) {
          RecessedWell(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(8.dp),
          ) {
            Text(
              text = "No Auto-EQ rules saved yet.",
              style = skin.typography.microLabel,
              color = skin.vfd.phosphorDim,
            )
          }
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            autoEqRules.forEach { rule ->
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .background(skin.vfd.wellBackground)
                    .recessedBevel(skin, thickness = 1.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Column(
                  modifier = Modifier.weight(1f),
                  verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                  Text(
                    text = rule.pattern,
                    style = skin.typography.microLabel,
                    color = skin.vfd.phosphorPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  val presetLabel = rule.preset.name.trim()
                  if (presetLabel.isNotEmpty() && !presetLabel.equals(rule.pattern.trim(), ignoreCase = true)) {
                    Text(
                      text = presetLabel.uppercase(),
                      style = skin.typography.microLabel,
                      color = skin.vfd.phosphorDim,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
                BeveledButton(
                  label = "DEL",
                  onClick = { onDeleteAutoRule(rule.pattern) },
                  modifier = Modifier.height(24.dp),
                  contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                )
              }
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          BeveledButton(
            label = "IMPORT .Q2",
            onClick = onImportQ2Click,
            highlighted = true,
            modifier = Modifier.weight(1f).height(32.dp),
          )
          BeveledButton(
            label = "EXPORT .Q2",
            onClick = onExportQ2Click,
            modifier = Modifier.weight(1f).height(32.dp),
          )
        }
      }
    }
  }

  MigrationPreviewDialogs(
    pendingDialog = pendingMigrationDialog,
    onConfirmIniImport = onConfirmIniImport,
    onConfirmQ1Import = onConfirmQ1Import,
    onConfirmQ2Import = onConfirmQ2Import,
    onDismissMigrationDialog = onDismissMigrationDialog,
  )
}

@Composable
private fun AutoEqPinRow(
  title: String,
  detail: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val skin = LocalDroidAmpSkin.current
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .let { if (enabled) it.clickable(onClick = onClick) else it }
        .padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = title,
        style = skin.typography.buttonLabel,
        color = if (enabled) skin.chassis.labelText else skin.chassis.labelMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = detail,
        style = skin.typography.microLabel,
        color = if (enabled) skin.vfd.phosphorDim else skin.chassis.labelMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    BeveledButton(
      label = "PIN",
      onClick = onClick,
      enabled = enabled,
      highlighted = enabled,
      modifier = Modifier.height(26.dp),
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    )
  }
}
