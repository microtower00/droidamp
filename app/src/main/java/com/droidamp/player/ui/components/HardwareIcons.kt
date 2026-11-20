package com.droidamp.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.skin.TransportIconType

/**
 * Pixel-aligned classic Winamp carved hardware transport glyphs (`cbuttons.bmp` equivalent).
 * Drawn with a crisp 1px highlight etch beneath/behind the face so each icon feels physically
 * recessed into the beveled button (and ready to swap to `.wsz` sprites in Milestone 5).
 */
@Composable
fun TransportHardwareGlyph(
  type: TransportIconType,
  tint: Color,
  modifier: Modifier = Modifier,
  glyphSize: Dp = 14.dp,
) {
  val skin = LocalDroidAmpSkin.current
  val etchColor = skin.bevel.highlightOuter.copy(alpha = 0.55f)
  Canvas(modifier = modifier.size(glyphSize)) {
    val w = size.width
    val h = size.height
    val etchOffset = Offset(1f, 1f)
    drawTransportGlyph(type = type, color = etchColor, width = w, height = h, offset = etchOffset)
    drawTransportGlyph(type = type, color = tint, width = w, height = h, offset = Offset.Zero)
  }
}

/**
 * Classic Winamp VFD playback status glyph (`playpaus.bmp` equivalent) rendered next to the
 * 7-segment clock instead of raw Unicode text.
 */
@Composable
fun VfdStatusGlyph(
  status: PlaybackStatus,
  modifier: Modifier = Modifier,
  glyphSize: Dp = 11.dp,
) {
  val skin = LocalDroidAmpSkin.current
  val statusSprite =
    when (status) {
      PlaybackStatus.PLAYING -> skin.sprites.vfdPlayIcon
      PlaybackStatus.PAUSED -> skin.sprites.vfdPauseIcon
      PlaybackStatus.STOPPED -> skin.sprites.vfdStopIcon
    }
  if (statusSprite != null) {
    Canvas(modifier = modifier.size(glyphSize)) {
      val side = minOf(size.width, size.height).toInt().coerceAtLeast(1)
      val ox = ((size.width - side) * 0.5f).toInt()
      val oy = ((size.height - side) * 0.5f).toInt()
      drawImage(
        image = statusSprite,
        dstOffset = IntOffset(ox, oy),
        dstSize = IntSize(side, side),
        filterQuality = FilterQuality.None,
      )
    }
    return
  }

  val activeColor =
    when (status) {
      PlaybackStatus.PLAYING -> skin.vfd.phosphorPrimary
      PlaybackStatus.PAUSED -> skin.vfd.ledAmber
      PlaybackStatus.STOPPED -> skin.vfd.phosphorDim
    }
  val iconType =
    when (status) {
      PlaybackStatus.PLAYING -> TransportIconType.PLAY
      PlaybackStatus.PAUSED -> TransportIconType.PAUSE
      PlaybackStatus.STOPPED -> TransportIconType.STOP
    }
  Canvas(modifier = modifier.size(glyphSize)) {
    drawTransportGlyph(
      type = iconType,
      color = activeColor,
      width = size.width,
      height = size.height,
      offset = Offset.Zero,
    )
  }
}

private fun DrawScope.drawTransportGlyph(
  type: TransportIconType,
  color: Color,
  width: Float,
  height: Float,
  offset: Offset,
) {
  val ox = offset.x
  val oy = offset.y
  val padX = width * 0.12f
  val padY = height * 0.14f
  val innerW = width - padX * 2f
  val innerH = height - padY * 2f

  when (type) {
    TransportIconType.PLAY -> {
      val path =
        Path().apply {
          moveTo(ox + padX + innerW * 0.10f, oy + padY)
          lineTo(ox + width - padX, oy + height * 0.5f)
          lineTo(ox + padX + innerW * 0.10f, oy + height - padY)
          close()
        }
      drawPath(path = path, color = color)
    }

    TransportIconType.PAUSE -> {
      val barW = innerW * 0.32f
      val gap = innerW * 0.22f
      val startX = ox + padX + (innerW - barW * 2f - gap) * 0.5f
      drawRect(
        color = color,
        topLeft = Offset(startX, oy + padY),
        size = Size(barW, innerH),
      )
      drawRect(
        color = color,
        topLeft = Offset(startX + barW + gap, oy + padY),
        size = Size(barW, innerH),
      )
    }

    TransportIconType.STOP -> {
      val side = minOf(innerW, innerH) * 0.90f
      val left = ox + (width - side) * 0.5f
      val top = oy + (height - side) * 0.5f
      drawRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(side, side),
      )
    }

    TransportIconType.PREV -> {
      val barW = innerW * 0.18f
      drawRect(
        color = color,
        topLeft = Offset(ox + padX, oy + padY),
        size = Size(barW, innerH),
      )
      val triLeft = ox + padX + barW + innerW * 0.06f
      val triMid = triLeft + (innerW - barW - innerW * 0.06f) * 0.5f
      val triRight = ox + width - padX
      val tri1 =
        Path().apply {
          moveTo(triMid, oy + padY)
          lineTo(triLeft, oy + height * 0.5f)
          lineTo(triMid, oy + height - padY)
          close()
        }
      val tri2 =
        Path().apply {
          moveTo(triRight, oy + padY)
          lineTo(triMid, oy + height * 0.5f)
          lineTo(triRight, oy + height - padY)
          close()
        }
      drawPath(tri1, color)
      drawPath(tri2, color)
    }

    TransportIconType.NEXT -> {
      val barW = innerW * 0.18f
      val barX = ox + width - padX - barW
      val triLeft = ox + padX
      val triRight = barX - innerW * 0.06f
      val triMid = (triLeft + triRight) * 0.5f
      val tri1 =
        Path().apply {
          moveTo(triLeft, oy + padY)
          lineTo(triMid, oy + height * 0.5f)
          lineTo(triLeft, oy + height - padY)
          close()
        }
      val tri2 =
        Path().apply {
          moveTo(triMid, oy + padY)
          lineTo(triRight, oy + height * 0.5f)
          lineTo(triMid, oy + height - padY)
          close()
        }
      drawPath(tri1, color)
      drawPath(tri2, color)
      drawRect(
        color = color,
        topLeft = Offset(barX, oy + padY),
        size = Size(barW, innerH),
      )
    }

    TransportIconType.EJECT -> {
      val triBottom = oy + padY + innerH * 0.62f
      val tri =
        Path().apply {
          moveTo(ox + width * 0.5f, oy + padY)
          lineTo(ox + width - padX, triBottom)
          lineTo(ox + padX, triBottom)
          close()
        }
      drawPath(tri, color)
      val barH = innerH * 0.22f
      drawRect(
        color = color,
        topLeft = Offset(ox + padX, oy + height - padY - barH),
        size = Size(innerW, barH),
      )
    }
  }
}
