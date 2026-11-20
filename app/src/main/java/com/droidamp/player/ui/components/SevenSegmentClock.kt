package com.droidamp.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.TimeDisplayMode
import com.droidamp.player.skin.LocalDroidAmpSkin

/**
 * Classic 7-segment phosphor green VFD clock (`-MM:SS`) rendered on Canvas
 * with dim unlit ghost segments for authentic hardware depth.
 */
@Composable
fun SevenSegmentClock(
  positionMs: Long,
  durationMs: Long,
  displayMode: TimeDisplayMode,
  onToggleMode: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  val shownMs =
    when (displayMode) {
      TimeDisplayMode.ELAPSED -> positionMs.coerceAtLeast(0L)
      TimeDisplayMode.REMAINING -> (durationMs - positionMs).coerceAtLeast(0L)
    }
  val totalSeconds = (shownMs / 1000L).toInt()
  val minutes = (totalSeconds / 60).coerceIn(0, 99)
  val seconds = totalSeconds % 60
  val isRemaining = displayMode == TimeDisplayMode.REMAINING
  val formatted = "${if (isRemaining) "-" else ""}%02d:%02d".format(minutes, seconds)

  Canvas(
    modifier =
      modifier
        .width(96.dp)
        .height(30.dp)
        .semantics { contentDescription = "Track timer $formatted" }
        .clickable(role = Role.Button, onClick = onToggleMode),
  ) {
    val activeColor = skin.vfd.phosphorPrimary
    val unlitColor = skin.vfd.segmentUnlit
    val h = size.height
    val padY = h * 0.10f
    val digitH = h - padY * 2f
    val digitW = digitH * 0.54f
    val stroke = (digitH * 0.135f).coerceAtLeast(2f)
    val minusW = digitW * 0.45f
    val colonW = digitW * 0.32f
    val gap = digitW * 0.20f

    var x = 2f
    // Minus sign slot (lit only in REMAINING mode)
    drawHorizontalSegment(
      left = x,
      centerY = padY + digitH * 0.5f,
      length = minusW,
      thickness = stroke,
      color = if (isRemaining) activeColor else unlitColor,
    )
    x += minusW + gap * 0.8f

    drawSevenSegmentDigit(minutes / 10, x, padY, digitW, digitH, stroke, activeColor, unlitColor)
    x += digitW + gap
    drawSevenSegmentDigit(minutes % 10, x, padY, digitW, digitH, stroke, activeColor, unlitColor)
    x += digitW + gap * 0.6f

    // Colon dots
    val dotRadius = stroke * 0.52f
    val colonX = x + colonW * 0.5f
    drawCircle(activeColor, radius = dotRadius, center = Offset(colonX, padY + digitH * 0.30f))
    drawCircle(activeColor, radius = dotRadius, center = Offset(colonX, padY + digitH * 0.70f))
    x += colonW + gap * 0.6f

    drawSevenSegmentDigit(seconds / 10, x, padY, digitW, digitH, stroke, activeColor, unlitColor)
    x += digitW + gap
    drawSevenSegmentDigit(seconds % 10, x, padY, digitW, digitH, stroke, activeColor, unlitColor)
  }
}

private val DIGIT_MASKS =
  intArrayOf(
    0b0111111, // 0: a,b,c,d,e,f
    0b0000110, // 1: b,c
    0b1011011, // 2: a,b,d,e,g
    0b1001111, // 3: a,b,c,d,g
    0b1100110, // 4: b,c,f,g
    0b1101101, // 5: a,c,d,f,g
    0b1111101, // 6: a,c,d,e,f,g
    0b0000111, // 7: a,b,c
    0b1111111, // 8: all
    0b1101111, // 9: a,b,c,d,f,g
  )

private fun DrawScope.drawSevenSegmentDigit(
  digit: Int,
  left: Float,
  top: Float,
  width: Float,
  height: Float,
  thickness: Float,
  onColor: Color,
  offColor: Color,
) {
  val mask = DIGIT_MASKS[digit.coerceIn(0, 9)]
  val halfH = height * 0.5f
  val inset = thickness * 0.55f

  // a (top)
  drawHorizontalSegment(left + inset, top + thickness * 0.5f, width - inset * 2f, thickness, if (mask and 0x01 != 0) onColor else offColor)
  // b (top-right)
  drawVerticalSegment(left + width - thickness * 0.5f, top + inset, halfH - inset * 1.4f, thickness, if (mask and 0x02 != 0) onColor else offColor)
  // c (bottom-right)
  drawVerticalSegment(left + width - thickness * 0.5f, top + halfH + inset * 0.4f, halfH - inset * 1.4f, thickness, if (mask and 0x04 != 0) onColor else offColor)
  // d (bottom)
  drawHorizontalSegment(left + inset, top + height - thickness * 0.5f, width - inset * 2f, thickness, if (mask and 0x08 != 0) onColor else offColor)
  // e (bottom-left)
  drawVerticalSegment(left + thickness * 0.5f, top + halfH + inset * 0.4f, halfH - inset * 1.4f, thickness, if (mask and 0x10 != 0) onColor else offColor)
  // f (top-left)
  drawVerticalSegment(left + thickness * 0.5f, top + inset, halfH - inset * 1.4f, thickness, if (mask and 0x20 != 0) onColor else offColor)
  // g (middle)
  drawHorizontalSegment(left + inset, top + halfH, width - inset * 2f, thickness, if (mask and 0x40 != 0) onColor else offColor)
}

private fun DrawScope.drawHorizontalSegment(
  left: Float,
  centerY: Float,
  length: Float,
  thickness: Float,
  color: Color,
) {
  val halfT = thickness * 0.5f
  val tip = halfT * 0.9f
  val path =
    Path().apply {
      moveTo(left, centerY)
      lineTo(left + tip, centerY - halfT)
      lineTo(left + length - tip, centerY - halfT)
      lineTo(left + length, centerY)
      lineTo(left + length - tip, centerY + halfT)
      lineTo(left + tip, centerY + halfT)
      close()
    }
  drawPath(path, color = color)
}

private fun DrawScope.drawVerticalSegment(
  centerX: Float,
  top: Float,
  length: Float,
  thickness: Float,
  color: Color,
) {
  val halfT = thickness * 0.5f
  val tip = halfT * 0.9f
  val path =
    Path().apply {
      moveTo(centerX, top)
      lineTo(centerX + halfT, top + tip)
      lineTo(centerX + halfT, top + length - tip)
      lineTo(centerX, top + length)
      lineTo(centerX - halfT, top + length - tip)
      lineTo(centerX - halfT, top + tip)
      close()
    }
  drawPath(path, color = color)
}
