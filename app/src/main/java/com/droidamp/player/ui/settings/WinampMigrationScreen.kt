package com.droidamp.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.droidamp.player.interop.IniAnalysisResult
import com.droidamp.player.interop.Q1AnalysisResult
import com.droidamp.player.interop.Q2AnalysisResult
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.ChassisTitleBar
import com.droidamp.player.ui.components.HardwarePanel
import com.droidamp.player.ui.components.RecessedWell

sealed interface PendingMigrationDialog {
  data class IniPreview(
    val fileName: String,
    val analysis: IniAnalysisResult,
  ) : PendingMigrationDialog

  data class Q1Preview(
    val fileName: String,
    val analysis: Q1AnalysisResult,
  ) : PendingMigrationDialog

  data class Q2Preview(
    val fileName: String,
    val analysis: Q2AnalysisResult,
  ) : PendingMigrationDialog
}

@Composable
fun WinampMigrationScreen(
  userPresetCount: Int,
  autoEqRuleCount: Int,
  pendingDialog: PendingMigrationDialog?,
  onImportIniClick: () -> Unit,
  onExportIniClick: () -> Unit,
  onImportQ1Click: () -> Unit,
  onExportQ1Click: () -> Unit,
  onImportQ2Click: () -> Unit,
  onExportQ2Click: () -> Unit,
  onConfirmIniImport: (IniAnalysisResult) -> Unit,
  onConfirmQ1Import: (Q1AnalysisResult, replaceAll: Boolean) -> Unit,
  onConfirmQ2Import: (Q2AnalysisResult, replaceAll: Boolean) -> Unit,
  onDismissMigrationDialog: () -> Unit,
  onResetAllSettings: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  var showResetConfirm by remember { mutableStateOf(false) }

  SettingsSubScreenScaffold(
    title = "WINAMP MIGRATION & DATA",
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
      SettingsSectionHeader(title = "CLASSIC WINAMP.INI SETTINGS")
      SettingsCardWell {
        Text(
          text = "Import or back up deck volume, pan, shuffle, repeat, EQ curve, ReplayGain, and crossfade settings.",
          style = skin.typography.microLabel,
          color = skin.chassis.labelMuted,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          BeveledButton(
            label = "IMPORT .INI",
            onClick = onImportIniClick,
            highlighted = true,
            modifier = Modifier.weight(1f).height(32.dp),
          )
          BeveledButton(
            label = "EXPORT .INI",
            onClick = onExportIniClick,
            modifier = Modifier.weight(1f).height(32.dp),
          )
        }
      }

      SettingsSectionHeader(title = "EQ PRESET LIBRARY (WINAMP.Q1)")
      SettingsCardWell {
        val presetLabel = if (userPresetCount == 1) "1 saved EQ preset" else "$userPresetCount saved EQ presets"
        Text(
          text = "$presetLabel. Import or export Winamp .q1 preset banks.",
          style = skin.typography.microLabel,
          color = skin.chassis.labelMuted,
        )
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

      SettingsSectionHeader(title = "AUTO-EQ RULES TABLE (WINAMP.Q2)")
      SettingsCardWell {
        Text(
          text = "$autoEqRuleCount saved Auto-EQ track/folder rules.",
          style = skin.typography.microLabel,
          color = skin.chassis.labelMuted,
        )
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

      SettingsSectionHeader(title = "FACTORY DEFAULTS")
      SettingsCardWell {
        Text(
          text = "Resets Controls, Audio, and Skin settings to defaults. Linked music folders are kept.",
          style = skin.typography.microLabel,
          color = skin.chassis.labelMuted,
        )
        BeveledButton(
          label = "RESET TO DEFAULTS",
          onClick = { showResetConfirm = true },
          modifier = Modifier.fillMaxWidth().height(34.dp),
        )
      }
    }
  }

  MigrationPreviewDialogs(
    pendingDialog = pendingDialog,
    onConfirmIniImport = onConfirmIniImport,
    onConfirmQ1Import = onConfirmQ1Import,
    onConfirmQ2Import = onConfirmQ2Import,
    onDismissMigrationDialog = onDismissMigrationDialog,
  )

  if (showResetConfirm) {
    MigrationConfirmDialog(
      title = "RESET SETTINGS",
      headline = "RESTORE DEFAULT PREFERENCES?",
      detail = "Controls, Audio, and Skin settings will reset to defaults. Linked music folders will stay intact.",
      onDismiss = { showResetConfirm = false },
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        BeveledButton(
          label = "CANCEL",
          onClick = { showResetConfirm = false },
          modifier = Modifier.weight(1f).height(34.dp),
        )
        BeveledButton(
          label = "RESET",
          onClick = {
            showResetConfirm = false
            onResetAllSettings()
          },
          highlighted = true,
          modifier = Modifier.weight(1f).height(34.dp),
        )
      }
    }
  }
}

@Composable
internal fun MigrationPreviewDialogs(
  pendingDialog: PendingMigrationDialog?,
  onConfirmIniImport: (IniAnalysisResult) -> Unit,
  onConfirmQ1Import: (Q1AnalysisResult, replaceAll: Boolean) -> Unit,
  onConfirmQ2Import: (Q2AnalysisResult, replaceAll: Boolean) -> Unit,
  onDismissMigrationDialog: () -> Unit,
) {
  when (pendingDialog) {
    is PendingMigrationDialog.IniPreview -> {
      val res = pendingDialog.analysis
      val detail =
        buildString {
          append("Found ${res.matchedKeys.size} compatible settings in \"${pendingDialog.fileName}\"")
          if (res.skippedLines > 0) {
            append(" (${res.skippedLines} unsupported or malformed lines skipped)")
          }
          append(".\n\nYour linked Android music folders and active skin will not be changed.")
        }
      MigrationConfirmDialog(
        title = "IMPORT WINAMP.INI",
        headline = if (res.matchedKeys.isNotEmpty()) "${res.matchedKeys.size} COMPATIBLE SETTINGS READY" else "NO COMPATIBLE SETTINGS FOUND",
        detail = detail,
        onDismiss = onDismissMigrationDialog,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          BeveledButton(
            label = "CANCEL",
            onClick = onDismissMigrationDialog,
            modifier = Modifier.weight(1f).height(34.dp),
          )
          BeveledButton(
            label = "APPLY VALID",
            onClick = { onConfirmIniImport(res) },
            enabled = res.matchedKeys.isNotEmpty(),
            highlighted = true,
            modifier = Modifier.weight(1.2f).height(34.dp),
          )
        }
      }
    }

    is PendingMigrationDialog.Q1Preview -> {
      val res = pendingDialog.analysis
      MigrationConfirmDialog(
        title = "IMPORT PRESET LIBRARY (.Q1)",
        headline = "FOUND ${res.presets.size} PRESETS (${res.skippedBlocks} SKIPPED)",
        detail = "Merge new presets into your library or replace all saved custom presets?",
        onDismiss = onDismissMigrationDialog,
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            BeveledButton(
              label = "MERGE",
              onClick = { onConfirmQ1Import(res, false) },
              enabled = res.presets.isNotEmpty(),
              highlighted = true,
              modifier = Modifier.weight(1f).height(34.dp),
            )
            BeveledButton(
              label = "REPLACE ALL",
              onClick = { onConfirmQ1Import(res, true) },
              enabled = res.presets.isNotEmpty(),
              modifier = Modifier.weight(1f).height(34.dp),
            )
          }
          BeveledButton(
            label = "CANCEL",
            onClick = onDismissMigrationDialog,
            modifier = Modifier.fillMaxWidth().height(30.dp),
          )
        }
      }
    }

    is PendingMigrationDialog.Q2Preview -> {
      val res = pendingDialog.analysis
      MigrationConfirmDialog(
        title = "IMPORT AUTO-EQ RULES (.Q2)",
        headline = "FOUND ${res.rules.size} RULES (${res.skippedBlocks} SKIPPED)",
        detail = "Merge Auto-EQ rules with your current rules or replace them all?",
        onDismiss = onDismissMigrationDialog,
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            BeveledButton(
              label = "MERGE",
              onClick = { onConfirmQ2Import(res, false) },
              enabled = res.rules.isNotEmpty(),
              highlighted = true,
              modifier = Modifier.weight(1f).height(34.dp),
            )
            BeveledButton(
              label = "REPLACE ALL",
              onClick = { onConfirmQ2Import(res, true) },
              enabled = res.rules.isNotEmpty(),
              modifier = Modifier.weight(1f).height(34.dp),
            )
          }
          BeveledButton(
            label = "CANCEL",
            onClick = onDismissMigrationDialog,
            modifier = Modifier.fillMaxWidth().height(30.dp),
          )
        }
      }
    }

    null -> Unit
  }
}

@Composable
private fun MigrationConfirmDialog(
  title: String,
  headline: String,
  detail: String,
  onDismiss: () -> Unit,
  actions: @Composable () -> Unit,
) {
  val skin = LocalDroidAmpSkin.current
  Dialog(onDismissRequest = onDismiss) {
    HardwarePanel(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth()) {
        ChassisTitleBar(title = title)
        Column(
          modifier = Modifier.fillMaxWidth().padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          RecessedWell(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
              Text(
                text = headline,
                style = skin.typography.vfdInfo,
                color = skin.vfd.phosphorBright,
              )
              Text(
                text = detail,
                style = skin.typography.emptyBody,
                color = skin.vfd.phosphorPrimary,
              )
            }
          }
          actions()
        }
      }
    }
  }
}
