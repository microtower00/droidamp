package com.droidamp.player.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.skin.SkinEntry
import com.droidamp.player.skin.SkinOrigin
import com.droidamp.player.skin.SkinRenderMode
import com.droidamp.player.skin.SkinSettingsState
import com.droidamp.player.skin.SkinSource
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.raisedBevel
import com.droidamp.player.ui.components.recessedBevel

private sealed interface SkinGridCell {
  data class EntryCell(val entry: SkinEntry) : SkinGridCell
  data object ImportActionCell : SkinGridCell
}

@Composable
fun SkinBrowserScreen(
  availableSkins: List<SkinEntry>,
  activeSkinId: String,
  skinSettings: SkinSettingsState,
  onSelectSkin: (String) -> Unit,
  onImportSkinClick: () -> Unit,
  onDeleteImportedSkin: (SkinEntry) -> Unit,
  onSetLegacyRenderMode: (SkinRenderMode) -> Unit,
  onSetAutoContrastGuard: (Boolean) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cells =
    availableSkins.map { SkinGridCell.EntryCell(it) } + SkinGridCell.ImportActionCell
  val rows = cells.chunked(2)

  SettingsSubScreenScaffold(
    title = "SKINS",
    onBack = onBack,
    modifier = modifier,
  ) {
    LazyColumn(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item(key = "skins-header") {
        SettingsSectionHeader(title = "INSTALLED SKINS (${availableSkins.size})")
      }

      items(
        items = rows,
        key = { pair ->
          pair.joinToString("|") { cell ->
            when (cell) {
              is SkinGridCell.EntryCell -> cell.entry.id
              SkinGridCell.ImportActionCell -> "__import__"
            }
          }
        },
      ) { pair ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          pair.forEach { cell ->
            when (cell) {
              is SkinGridCell.EntryCell -> {
                SkinPreviewCard(
                  entry = cell.entry,
                  isActive = cell.entry.id == activeSkinId,
                  onSelect = { onSelectSkin(cell.entry.id) },
                  onDelete = { onDeleteImportedSkin(cell.entry) },
                  modifier = Modifier.weight(1f),
                )
              }
              SkinGridCell.ImportActionCell -> {
                ImportSkinTile(
                  onClick = onImportSkinClick,
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
          if (pair.size == 1) {
            Spacer(Modifier.weight(1f))
          }
        }
      }

      item(key = "legacy-render-mode") {
        SettingsSectionHeader(title = "LEGACY 1999 .WSZ RENDERING")
        Spacer(Modifier.height(4.dp))
        SettingsCardWell {
          SettingsSegmentedRow(
            title = "Render Mode",
            subtitle = "Crisp Vector redraws OG colors cleanly; Classic BMP uses original 1999 bitmap graphics.",
            options =
              listOf(
                SegmentedOption(SkinRenderMode.VECTOR_CHROME, "Crisp Vector"),
                SegmentedOption(SkinRenderMode.CLASSIC_RAW_BMP, "Classic BMP", isOg = true),
              ),
            selected = skinSettings.legacyRenderMode,
            onSelect = onSetLegacyRenderMode,
          )
        }
      }

      item(key = "contrast-guard") {
        SettingsSectionHeader(title = "CONTRAST GUARD")
        Spacer(Modifier.height(4.dp))
        SettingsCardWell {
          SettingsSegmentedRow(
            title = "Auto-Contrast",
            subtitle = "Automatically boosts low-contrast text on old desktop .wsz palettes unless Raw Colors is selected.",
            options =
              listOf(
                SegmentedOption(true, "Auto Fix"),
                SegmentedOption(false, "Raw Colors", isOg = true),
              ),
            selected = skinSettings.autoContrastGuard,
            onSelect = onSetAutoContrastGuard,
          )
        }
      }
    }
  }
}

@Composable
private fun SkinPreviewCard(
  entry: SkinEntry,
  isActive: Boolean,
  onSelect: () -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  val preview = entry.previewPalette

  Column(
    modifier =
      modifier
        .background(if (isActive) skin.bevel.buttonFacePressed else skin.bevel.buttonFace)
        .raisedBevel(skin, pressed = isActive)
        .clickable(onClick = onSelect)
        .padding(6.dp),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    // Mini live hardware preview matching the candidate skin's actual colors
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .background(preview.panelBg)
          .raisedBevel(skin, thickness = 1.dp)
          .padding(3.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(11.dp)
            .background(
              Brush.horizontalGradient(
                listOf(preview.titleBarStart, preview.titleBarEnd, preview.titleBarStart),
              ),
            )
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        Text(
          text = "DROIDAMP",
          style = skin.typography.microLabel.copy(fontSize = 7.sp, lineHeight = 8.sp),
          color = preview.accent,
          maxLines = 1,
        )
      }
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .background(preview.vfdBg)
            .recessedBevel(skin, thickness = 1.dp)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "02:14",
          style = skin.typography.microLabel.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
          color = preview.vfdPhosphor,
        )
        Canvas(modifier = Modifier.size(width = 24.dp, height = 10.dp)) {
          val heights = floatArrayOf(0.45f, 0.85f, 1.0f, 0.65f, 0.35f)
          val barW = size.width / (heights.size * 1.6f)
          heights.forEachIndexed { idx, frac ->
            val bh = size.height * frac
            drawRect(
              color = if (idx == 2) preview.accent else preview.vfdPhosphor,
              topLeft = Offset(idx * barW * 1.6f, size.height - bh),
              size = Size(barW, bh),
            )
          }
        }
        Text(
          text = "192K",
          style = skin.typography.microLabel.copy(fontSize = 7.sp),
          color = preview.vfdPhosphor.copy(alpha = 0.8f),
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Box(
        modifier =
          Modifier
            .size(width = 5.dp, height = 8.dp)
            .background(if (isActive) skin.vfd.ledOn else skin.vfd.ledOff)
            .recessedBevel(skin, thickness = 1.dp),
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = entry.displayName,
          style = skin.typography.buttonLabel,
          color = if (isActive) skin.bevel.buttonTextActive else skin.bevel.buttonText,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = skinOriginLabel(entry),
          style = skin.typography.microLabel,
          color = skin.vfd.phosphorDim,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (entry.source.canDelete) {
        BeveledButton(
          label = "DEL",
          onClick = onDelete,
          modifier = Modifier.height(22.dp),
          contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
        )
      }
    }
  }
}

@Composable
private fun ImportSkinTile(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  Column(
    modifier =
      modifier
        .height(94.dp)
        .background(skin.chassis.panelSurfaceAlt)
        .recessedBevel(skin)
        .clickable(onClick = onClick)
        .padding(8.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = "+ IMPORT .WSZ",
      style = skin.typography.buttonLabel,
      color = skin.vfd.phosphorBright,
      maxLines = 1,
    )
    Spacer(Modifier.height(3.dp))
    Text(
      text = "From device files",
      style = skin.typography.microLabel,
      color = skin.chassis.labelMuted,
      maxLines = 1,
    )
  }
}

private fun skinOriginLabel(entry: SkinEntry): String =
  when (entry.source) {
    SkinSource.BUILT_IN -> "Built-in"
    SkinSource.BUILT_IN_SHOWCASE -> "Showcase · Augmented .wsz"
    SkinSource.IMPORTED ->
      if (entry.origin == SkinOrigin.AUGMENTED_WSZ) "Imported · Augmented" else "Imported · Legacy .wsz"
    SkinSource.SAF_FOLDER ->
      if (entry.origin == SkinOrigin.AUGMENTED_WSZ) "Folder · Augmented" else "Folder · Legacy .wsz"
  }
