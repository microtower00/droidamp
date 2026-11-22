package com.droidamp.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BackArrowHardwareGlyph
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.BeveledLedToggleButton
import com.droidamp.player.ui.components.ChassisTitleBar
import com.droidamp.player.ui.components.HardwarePanel
import com.droidamp.player.ui.components.HardwareText
import com.droidamp.player.ui.components.HorizontalHardwareSlider
import com.droidamp.player.ui.components.RecessedWell
import com.droidamp.player.ui.components.SliderTrackStyle
import com.droidamp.player.ui.components.raisedBevel
import com.droidamp.player.ui.components.recessedBevel

data class SegmentedOption<T>(
  val value: T,
  val label: String,
  val isOg: Boolean = false,
)

/**
 * Tiny recessed beveled `[OG]` pill badge in the active skin's phosphor/accent palette
 * marking options that preserve classic 1999 Winamp behavior (`ADR-029`).
 */
@Composable
fun OgWinampBadge(
  modifier: Modifier = Modifier,
  label: String = "OG",
) {
  val skin = LocalDroidAmpSkin.current
  Box(
    modifier =
      modifier
        .background(skin.vfd.wellBackground)
        .recessedBevel(skin, thickness = 1.dp)
        .padding(horizontal = 4.dp, vertical = 1.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label.uppercase(),
      style =
        skin.typography.microLabel.copy(
          fontSize = 8.sp,
          lineHeight = 9.sp,
          fontWeight = FontWeight.Bold,
        ),
      color = skin.vfd.ledAmber,
      maxLines = 1,
    )
  }
}

@Composable
fun SettingsSubScreenScaffold(
  title: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  showBackButton: Boolean = true,
  showSubHeader: Boolean = true,
  trailingContent: @Composable (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val skin = LocalDroidAmpSkin.current
  Column(modifier = modifier.fillMaxSize()) {
    if (showSubHeader) {
      RecessedWell(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        backgroundColor = skin.chassis.panelSurfaceAlt,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (showBackButton) {
            Box(
              modifier =
                Modifier
                  .size(width = 26.dp, height = 22.dp)
                  .background(skin.bevel.buttonFace)
                  .raisedBevel(skin, thickness = 1.dp)
                  .clickable(onClick = onBack),
              contentAlignment = Alignment.Center,
            ) {
              BackArrowHardwareGlyph(tint = skin.bevel.buttonText, glyphSize = 11.dp)
            }
          }
          Text(
            text = title.uppercase(),
            style = skin.typography.buttonLabel,
            color = skin.vfd.phosphorPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          if (trailingContent != null) {
            trailingContent()
          }
        }
      }
    }
    Column(
      modifier = Modifier.weight(1f).fillMaxWidth(),
      content = content,
    )
  }
}

@Composable
fun SettingsSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  HardwareText(
    text = title,
    color = skin.vfd.phosphorPrimary,
    modifier = modifier.padding(top = 6.dp, bottom = 2.dp, start = 2.dp),
  )
}

@Composable
fun SettingsCardWell(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val skin = LocalDroidAmpSkin.current
  RecessedWell(
    modifier = modifier.fillMaxWidth(),
    backgroundColor = skin.chassis.panelSurfaceAlt,
    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      content = content,
    )
  }
}

@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
  val skin = LocalDroidAmpSkin.current
  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(skin.chassis.divider),
  )
}

@Composable
fun SettingsToggleRow(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  isOg: Boolean = false,
  enabled: Boolean = true,
) {
  val skin = LocalDroidAmpSkin.current
  Row(
    modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text(
          text = title.uppercase(),
          style = skin.typography.buttonLabel,
          color = skin.chassis.labelText,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (isOg) {
          OgWinampBadge()
        }
      }
      if (!subtitle.isNullOrBlank()) {
        Text(
          text = subtitle,
          style = skin.typography.microLabel,
          color = skin.chassis.labelMuted,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    BeveledLedToggleButton(
      label = if (checked) "ON" else "OFF",
      checked = checked,
      onClick = { onCheckedChange(!checked) },
      compact = true,
      enabled = enabled,
      modifier = Modifier.width(58.dp).height(28.dp),
    )
  }
}

@Composable
fun <T> SettingsSegmentedRow(
  title: String,
  options: List<SegmentedOption<T>>,
  selected: T,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
) {
  val skin = LocalDroidAmpSkin.current
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Text(
      text = title.uppercase(),
      style = skin.typography.buttonLabel,
      color = skin.chassis.labelText,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (!subtitle.isNullOrBlank()) {
      Text(
        text = subtitle,
        style = skin.typography.microLabel,
        color = skin.chassis.labelMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      options.forEach { opt ->
        BeveledLedToggleButton(
          label = opt.label,
          checked = opt.value == selected,
          onClick = { onSelect(opt.value) },
          compact = true,
          trailingContent =
            if (opt.isOg) {
              { OgWinampBadge() }
            } else {
              null
            },
          modifier = Modifier.weight(1f).height(28.dp),
        )
      }
    }
  }
}

@Composable
fun <T> SettingsRadioGroup(
  title: String,
  options: List<SegmentedOption<T>>,
  selected: T,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
) {
  val skin = LocalDroidAmpSkin.current
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Text(
      text = title.uppercase(),
      style = skin.typography.buttonLabel,
      color = skin.chassis.labelText,
    )
    if (!subtitle.isNullOrBlank()) {
      Text(
        text = subtitle,
        style = skin.typography.microLabel,
        color = skin.chassis.labelMuted,
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      options.forEach { opt ->
        val isSelected = opt.value == selected
        Row(
          modifier =
            Modifier
              .weight(1f)
              .height(30.dp)
              .background(if (isSelected) skin.bevel.buttonFacePressed else skin.bevel.buttonFace)
              .raisedBevel(skin, pressed = isSelected)
              .clickable { onSelect(opt.value) }
              .padding(horizontal = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
        ) {
          Text(
            text = if (isSelected) "(●)" else "( )",
            style = skin.typography.microLabel,
            color = if (isSelected) skin.vfd.phosphorBright else skin.chassis.labelMuted,
          )
          Spacer(Modifier.width(4.dp))
          Text(
            text = opt.label.uppercase(),
            style = skin.typography.microLabel,
            color = if (isSelected) skin.bevel.buttonTextActive else skin.bevel.buttonText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
fun SettingsSliderRow(
  title: String,
  valueLabel: String,
  value0To1: Float,
  onValueChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  trackStyle: SliderTrackStyle = SliderTrackStyle.INTENSITY_RAMP,
) {
  val skin = LocalDroidAmpSkin.current
  Column(
    modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = title.uppercase(),
        style = skin.typography.microLabel,
        color = skin.chassis.labelText,
      )
      Text(
        text = valueLabel.uppercase(),
        style = skin.typography.vfdInfo,
        color = skin.vfd.phosphorPrimary,
      )
    }
    HorizontalHardwareSlider(
      value = value0To1.coerceIn(0f, 1f),
      onValueChange = { if (enabled) onValueChange(it) },
      trackStyle = trackStyle,
      height = 24.dp,
    )
  }
}

@Composable
fun SettingsNavRow(
  title: String,
  valueSummary: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  isOg: Boolean = false,
  badgeText: String? = null,
  badgeColor: Color? = null,
  actionLabel: String = ">",
) {
  val skin = LocalDroidAmpSkin.current
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text(
          text = title.uppercase(),
          style = skin.typography.buttonLabel,
          color = skin.chassis.labelText,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (isOg) {
          OgWinampBadge()
        }
        if (!badgeText.isNullOrBlank()) {
          Text(
            text = badgeText.uppercase(),
            style = skin.typography.microLabel,
            color = badgeColor ?: skin.vfd.ledAmber,
          )
        }
      }
      Text(
        text = valueSummary,
        style = skin.typography.microLabel,
        color = skin.vfd.phosphorDim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    BeveledButton(
      label = actionLabel,
      onClick = onClick,
      modifier = Modifier.height(26.dp),
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    )
  }
}
