package com.droidamp.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.droidamp.player.skin.DroidAmpSkin
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.skin.TransportIconType

private fun Modifier.skinClippedBackground(
  skin: DroidAmpSkin,
  color: Color,
): Modifier {
  val radius = skin.shapeStyle.cornerRadius
  return if (radius > 0.dp) {
    val shape = RoundedCornerShape(radius)
    this.clip(shape).background(color, shape)
  } else {
    this.background(color)
  }
}

fun Modifier.raisedBevel(
  skin: DroidAmpSkin,
  thickness: Dp = skin.shapeStyle.bevelThickness,
  pressed: Boolean = false,
): Modifier =
  drawBehind {
    val px = thickness.toPx()
    val topLeftOuter = if (pressed) skin.bevel.shadowOuter else skin.bevel.highlightOuter
    val topLeftInner = if (pressed) skin.bevel.shadowInner else skin.bevel.highlightInner
    val bottomRightOuter = if (pressed) skin.bevel.highlightOuter else skin.bevel.shadowOuter
    val bottomRightInner = if (pressed) skin.bevel.highlightInner else skin.bevel.shadowInner
    val radiusPx = skin.shapeStyle.cornerRadius.toPx().coerceAtLeast(0f)

    if (radiusPx > 0f) {
      drawDirectionalRoundedBevel(
        inset = 0f,
        strokeWidth = px,
        cornerRadiusPx = radiusPx,
        topLeftColor = topLeftOuter,
        bottomRightColor = bottomRightOuter,
      )
      if (skin.shapeStyle.doubleStepBevel && size.width > px * 4f && size.height > px * 4f) {
        val half = px * 0.6f
        drawDirectionalRoundedBevel(
          inset = px,
          strokeWidth = half,
          cornerRadiusPx = (radiusPx - px).coerceAtLeast(0f),
          topLeftColor = topLeftInner,
          bottomRightColor = bottomRightInner,
        )
      }
      return@drawBehind
    }

    // Outer 1px crisp pixel bevel
    drawLine(topLeftOuter, Offset(0f, px * 0.5f), Offset(size.width, px * 0.5f), strokeWidth = px)
    drawLine(topLeftOuter, Offset(px * 0.5f, 0f), Offset(px * 0.5f, size.height), strokeWidth = px)
    drawLine(
      bottomRightOuter,
      Offset(0f, size.height - px * 0.5f),
      Offset(size.width, size.height - px * 0.5f),
      strokeWidth = px,
    )
    drawLine(
      bottomRightOuter,
      Offset(size.width - px * 0.5f, 0f),
      Offset(size.width - px * 0.5f, size.height),
      strokeWidth = px,
    )

    // Subtle inner bevel step for deeper hardware look (honours skin.shapeStyle.doubleStepBevel)
    val inset = px
    if (skin.shapeStyle.doubleStepBevel && size.width > inset * 4f && size.height > inset * 4f) {
      val half = px * 0.6f
      drawLine(
        topLeftInner,
        Offset(inset, inset + half * 0.5f),
        Offset(size.width - inset, inset + half * 0.5f),
        strokeWidth = half,
      )
      drawLine(
        topLeftInner,
        Offset(inset + half * 0.5f, inset),
        Offset(inset + half * 0.5f, size.height - inset),
        strokeWidth = half,
      )
      drawLine(
        bottomRightInner,
        Offset(inset, size.height - inset - half * 0.5f),
        Offset(size.width - inset, size.height - inset - half * 0.5f),
        strokeWidth = half,
      )
      drawLine(
        bottomRightInner,
        Offset(size.width - inset - half * 0.5f, inset),
        Offset(size.width - inset - half * 0.5f, size.height - inset),
        strokeWidth = half,
      )
    }
  }

fun Modifier.recessedBevel(
  skin: DroidAmpSkin,
  thickness: Dp = skin.shapeStyle.bevelThickness,
): Modifier =
  drawBehind {
    val px = thickness.toPx()
    val radiusPx = skin.shapeStyle.cornerRadius.toPx().coerceAtLeast(0f)
    if (radiusPx > 0f) {
      drawDirectionalRoundedBevel(
        inset = 0f,
        strokeWidth = px,
        cornerRadiusPx = radiusPx,
        topLeftColor = skin.bevel.wellBorderDark,
        bottomRightColor = skin.bevel.wellBorderLight,
      )
      return@drawBehind
    }

    drawLine(
      skin.bevel.wellBorderDark,
      Offset(0f, px * 0.5f),
      Offset(size.width, px * 0.5f),
      strokeWidth = px,
    )
    drawLine(
      skin.bevel.wellBorderDark,
      Offset(px * 0.5f, 0f),
      Offset(px * 0.5f, size.height),
      strokeWidth = px,
    )
    drawLine(
      skin.bevel.wellBorderLight,
      Offset(0f, size.height - px * 0.5f),
      Offset(size.width, size.height - px * 0.5f),
      strokeWidth = px,
    )
    drawLine(
      skin.bevel.wellBorderLight,
      Offset(size.width - px * 0.5f, 0f),
      Offset(size.width - px * 0.5f, size.height),
      strokeWidth = px,
    )
  }

private fun DrawScope.drawDirectionalRoundedBevel(
  inset: Float,
  strokeWidth: Float,
  cornerRadiusPx: Float,
  topLeftColor: Color,
  bottomRightColor: Color,
) {
  val w = size.width
  val h = size.height
  val boxW = (w - inset * 2f - strokeWidth).coerceAtLeast(1f)
  val boxH = (h - inset * 2f - strokeWidth).coerceAtLeast(1f)
  val topLeftOffset = Offset(inset + strokeWidth * 0.5f, inset + strokeWidth * 0.5f)
  val boxSize = Size(boxW, boxH)
  val clampedRadius =
    (cornerRadiusPx - strokeWidth * 0.5f)
      .coerceIn(0f, minOf(boxW, boxH) * 0.5f)
  val cr = CornerRadius(clampedRadius, clampedRadius)
  val miter = minOf(w, h) * 0.5f

  val topLeftHalf =
    Path().apply {
      moveTo(0f, 0f)
      lineTo(w, 0f)
      lineTo(w - miter, miter)
      lineTo(miter, h - miter)
      lineTo(0f, h)
      close()
    }
  val bottomRightHalf =
    Path().apply {
      moveTo(w, 0f)
      lineTo(w, h)
      lineTo(0f, h)
      lineTo(miter, h - miter)
      lineTo(w - miter, miter)
      close()
    }

  clipPath(topLeftHalf) {
    drawRoundRect(
      color = topLeftColor,
      topLeft = topLeftOffset,
      size = boxSize,
      cornerRadius = cr,
      style = Stroke(width = strokeWidth),
    )
  }
  clipPath(bottomRightHalf) {
    drawRoundRect(
      color = bottomRightColor,
      topLeft = topLeftOffset,
      size = boxSize,
      cornerRadius = cr,
      style = Stroke(width = strokeWidth),
    )
  }
}

@Composable
fun HardwarePanel(
  modifier: Modifier = Modifier,
  backgroundColor: Color? = null,
  content: @Composable BoxScope.() -> Unit,
) {
  val skin = LocalDroidAmpSkin.current
  Box(
    modifier =
      modifier
        .skinClippedBackground(skin, backgroundColor ?: skin.chassis.panelSurface)
        .raisedBevel(skin),
    content = content,
  )
}

@Composable
fun RecessedWell(
  modifier: Modifier = Modifier,
  backgroundColor: Color? = null,
  contentPadding: PaddingValues = PaddingValues(4.dp),
  content: @Composable BoxScope.() -> Unit,
) {
  val skin = LocalDroidAmpSkin.current
  Box(
    modifier =
      modifier
        .skinClippedBackground(skin, backgroundColor ?: skin.vfd.wellBackground)
        .recessedBevel(skin)
        .padding(contentPadding),
    content = content,
  )
}

@Composable
fun ChassisTitleBar(
  title: String,
  modifier: Modifier = Modifier,
  showDragGrip: Boolean = false,
  customSprite: ImageBitmap? = null,
  leadingContent: @Composable (() -> Unit)? = null,
  trailingContent: @Composable (() -> Unit)? = null,
) {
  val skin = LocalDroidAmpSkin.current
  if (customSprite != null) {
    // Render custom `.wsz` / Augmented header sprite directly with nearest-neighbor crispness
    // while keeping gesture modifiers (e.g. 1:1 drawer pan + tap) and optional leading/trailing content live!
    Box(
      modifier =
        modifier
          .fillMaxWidth()
          .height(22.dp)
          .semantics { contentDescription = title },
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        drawImage(
          image = customSprite,
          dstOffset = IntOffset.Zero,
          dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
          filterQuality = FilterQuality.None,
        )
      }
      if (leadingContent != null) {
        Box(
          modifier =
            Modifier
              .align(Alignment.CenterStart)
              .padding(start = 4.dp),
        ) {
          leadingContent()
        }
      }
      if (trailingContent != null) {
        Box(
          modifier =
            Modifier
              .align(Alignment.CenterEnd)
              .padding(end = 4.dp),
        ) {
          trailingContent()
        }
      }
    }
    return
  }

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .height(22.dp)
        .background(
          Brush.horizontalGradient(
            listOf(skin.chassis.titleBarActiveStart, skin.chassis.titleBarActiveEnd, skin.chassis.titleBarActiveStart),
          ),
        )
        .padding(horizontal = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (leadingContent != null) {
      leadingContent()
    }
    TitleBarStripes(skin = skin, modifier = Modifier.weight(1f))
    if (showDragGrip) {
      Box(
        modifier =
          Modifier
            .width(18.dp)
            .height(4.dp)
            .background(skin.chassis.titleBarStripeLight.copy(alpha = 0.7f)),
      )
    }
    Text(
      text = title.uppercase(),
      style = skin.typography.titleBar,
      color = skin.chassis.titleBarText,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    TitleBarStripes(skin = skin, modifier = Modifier.weight(1f))
    if (trailingContent != null) {
      trailingContent()
    }
  }
}

@Composable
private fun TitleBarStripes(skin: DroidAmpSkin, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    repeat(3) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(skin.chassis.titleBarStripeLight.copy(alpha = 0.55f)),
      )
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(skin.chassis.titleBarStripeDark.copy(alpha = 0.75f)),
      )
    }
  }
}

@Composable
fun BeveledButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  highlighted: Boolean = false,
  spriteNormal: ImageBitmap? = null,
  spritePressed: ImageBitmap? = null,
  contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
) {
  val skin = LocalDroidAmpSkin.current
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()

  if (spriteNormal != null) {
    val activeSprite = if (isPressed && spritePressed != null) spritePressed else spriteNormal
    Box(
      modifier =
        modifier
          .semantics { this.contentDescription = label }
          .clickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
          ),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        drawAspectFitSprite(activeSprite)
      }
    }
    return
  }

  Box(
    modifier =
      modifier
        .skinClippedBackground(skin, if (isPressed) skin.bevel.buttonFacePressed else skin.bevel.buttonFace)
        .raisedBevel(skin, pressed = isPressed)
        .clickable(
          enabled = enabled,
          interactionSource = interactionSource,
          indication = null,
          onClick = onClick,
        )
        .padding(contentPadding),
    contentAlignment = Alignment.Center,
  ) {
    val shift = if (isPressed) 1.dp else 0.dp
    Text(
      text = label,
      style = skin.typography.buttonLabel,
      color =
        when {
          !enabled -> skin.chassis.labelMuted
          highlighted -> skin.bevel.buttonTextActive
          else -> skin.bevel.buttonText
        },
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.offset(x = shift, y = shift),
    )
  }
}

@Composable
fun BeveledIconButton(
  icon: TransportIconType,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  highlighted: Boolean = false,
  glyphSize: Dp = 14.dp,
  contentPadding: PaddingValues = PaddingValues(0.dp),
) {
  val skin = LocalDroidAmpSkin.current
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val spriteNormal = skin.sprites.transportNormal[icon]
  val spritePressed = skin.sprites.transportPressed[icon] ?: spriteNormal

  if (spriteNormal != null) {
    val activeSprite = if (isPressed || highlighted) spritePressed else spriteNormal
    Box(
      modifier =
        modifier
          .semantics { this.contentDescription = contentDescription }
          .clickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
          ),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        drawAspectFitSprite(activeSprite!!)
      }
    }
    return
  }

  Box(
    modifier =
      modifier
        .skinClippedBackground(skin, if (isPressed) skin.bevel.buttonFacePressed else skin.bevel.buttonFace)
        .raisedBevel(skin, pressed = isPressed)
        .semantics { this.contentDescription = contentDescription }
        .clickable(
          enabled = enabled,
          role = Role.Button,
          interactionSource = interactionSource,
          indication = null,
          onClick = onClick,
        )
        .padding(contentPadding),
    contentAlignment = Alignment.Center,
  ) {
    val shift = if (isPressed) 1.dp else 0.dp
    val tint =
      when {
        !enabled -> skin.chassis.labelMuted
        highlighted -> skin.bevel.buttonTextActive
        else -> skin.bevel.buttonText
      }
    TransportHardwareGlyph(
      type = icon,
      tint = tint,
      glyphSize = glyphSize,
      modifier = Modifier.offset(x = shift, y = shift),
    )
  }
}

@Composable
fun BeveledLedToggleButton(
  label: String,
  checked: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  spriteOff: ImageBitmap? = null,
  spriteOn: ImageBitmap? = null,
  enabled: Boolean = true,
  trailingContent: (@Composable () -> Unit)? = null,
) {
  val skin = LocalDroidAmpSkin.current
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()

  val activeSprite = if (checked) (spriteOn ?: spriteOff) else spriteOff
  if (activeSprite != null) {
    Box(
      modifier =
        modifier
          .semantics { this.contentDescription = label }
          .clickable(
            enabled = enabled,
            role = Role.Switch,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
          ),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        drawAspectFitSprite(activeSprite)
      }
    }
    return
  }

  Row(
    modifier =
      modifier
        .skinClippedBackground(skin, if (isPressed || checked) skin.bevel.buttonFacePressed else skin.bevel.buttonFace)
        .raisedBevel(skin, pressed = isPressed)
        .clickable(
          enabled = enabled,
          interactionSource = interactionSource,
          indication = null,
          onClick = onClick,
        )
        .padding(horizontal = if (compact) 5.dp else 7.dp, vertical = if (compact) 4.dp else 5.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
  ) {
    // Classic miniature phosphor LED indicator
    Box(
      modifier =
        Modifier
          .size(width = if (compact) 5.dp else 6.dp, height = if (compact) 8.dp else 9.dp)
          .background(if (checked) skin.vfd.ledOn else skin.vfd.ledOff)
          .recessedBevel(skin, thickness = 1.dp),
    )
    Spacer(Modifier.width(4.dp))
    Text(
      text = label,
      style = if (compact) skin.typography.microLabel else skin.typography.buttonLabel,
      color =
        when {
          !enabled -> skin.chassis.labelMuted
          checked -> skin.bevel.buttonTextActive
          else -> skin.bevel.buttonText
        },
      maxLines = 1,
    )
    if (trailingContent != null) {
      Spacer(Modifier.width(4.dp))
      trailingContent()
    }
  }
}

data class MicroMenuAction(
  val label: String,
  val onClick: () -> Unit,
)

/**
 * Classic Winamp stacked vertical micro-menu anchored directly above bottom toolbar buttons.
 */
@Composable
fun RetroMicroMenuPopup(
  expanded: Boolean,
  title: String,
  actions: List<MicroMenuAction>,
  onDismiss: () -> Unit,
  maxMenuHeight: Dp = 260.dp,
) {
  if (!expanded) return
  val skin = LocalDroidAmpSkin.current
  val verticalOffsetPx = with(LocalDensity.current) { (-38).dp.roundToPx() }
  Popup(
    alignment = Alignment.BottomCenter,
    offset = IntOffset(0, verticalOffsetPx),
    onDismissRequest = onDismiss,
    properties = PopupProperties(focusable = true),
  ) {
    Column(
      modifier =
        Modifier
          .widthIn(min = 160.dp, max = 240.dp)
          .skinClippedBackground(skin, skin.chassis.panelSurface)
          .raisedBevel(skin)
          .padding(3.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = title.uppercase(),
        style = skin.typography.microLabel,
        color = skin.chassis.titleBarText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
          Modifier
            .fillMaxWidth()
            .background(skin.chassis.titleBarActiveStart)
            .padding(horizontal = 6.dp, vertical = 3.dp),
      )
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(max = maxMenuHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        actions.forEach { action ->
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .background(skin.vfd.wellBackground)
                .recessedBevel(skin, thickness = 1.dp)
                .clickable {
                  onDismiss()
                  action.onClick()
                }
                .padding(horizontal = 8.dp, vertical = 7.dp),
          ) {
            Text(
              text = action.label.uppercase(),
              style = skin.typography.buttonLabel,
              color = skin.vfd.phosphorPrimary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAspectFitSprite(
  sprite: ImageBitmap,
) {
  val srcW = sprite.width.toFloat().coerceAtLeast(1f)
  val srcH = sprite.height.toFloat().coerceAtLeast(1f)
  val scale = minOf(size.width / srcW, size.height / srcH)
  val dstW = (srcW * scale).toInt().coerceAtLeast(1)
  val dstH = (srcH * scale).toInt().coerceAtLeast(1)
  val ox = ((size.width - dstW) * 0.5f).toInt()
  val oy = ((size.height - dstH) * 0.5f).toInt()
  drawImage(
    image = sprite,
    dstOffset = IntOffset(ox, oy),
    dstSize = IntSize(dstW, dstH),
    filterQuality = FilterQuality.None,
  )
}

/**
 * Skin-aware square beveled Settings gear button (`mobile_settings_gear_*.png` or carved vector glyph).
 */
@Composable
fun SettingsGearButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  highlighted: Boolean = false,
  glyphSize: Dp = 13.dp,
  contentDescription: String = "Settings",
) {
  val skin = LocalDroidAmpSkin.current
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val spriteNormal = skin.sprites.mobileSettingsGearNormal
  val spritePressed = skin.sprites.mobileSettingsGearPressed

  if (spriteNormal != null) {
    val activeSprite = if (isPressed && spritePressed != null) spritePressed else spriteNormal
    Box(
      modifier =
        modifier
          .semantics { this.contentDescription = contentDescription }
          .clickable(
            role = Role.Button,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
          ),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        drawAspectFitSprite(activeSprite)
      }
    }
    return
  }

  Box(
    modifier =
      modifier
        .skinClippedBackground(
          skin,
          if (isPressed || highlighted) skin.bevel.buttonFacePressed else skin.bevel.buttonFace,
        )
        .raisedBevel(skin, pressed = isPressed)
        .semantics { this.contentDescription = contentDescription }
        .clickable(
          role = Role.Button,
          interactionSource = interactionSource,
          indication = null,
          onClick = onClick,
        ),
    contentAlignment = Alignment.Center,
  ) {
    val shift = if (isPressed) 1.dp else 0.dp
    GearHardwareGlyph(
      tint = if (highlighted) skin.bevel.buttonTextActive else skin.bevel.buttonText,
      glyphSize = glyphSize,
      modifier = Modifier.offset(x = shift, y = shift),
    )
  }
}
