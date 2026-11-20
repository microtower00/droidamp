package com.droidamp.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.droidamp.player.skin.LocalDroidAmpSkin
import kotlin.math.abs

enum class SliderTrackStyle {
  SEEKBAR,
  INTENSITY_RAMP,
  CENTER_BALANCE,
}

/**
 * Hardware horizontal fader for Position Seekbar, independent Winamp Volume (`0..255`),
 * and Stereo Balance (`-127..+127`), with beveled metallic thumb and recessed track.
 */
@Composable
fun HorizontalHardwareSlider(
  value: Float, // normalized 0f..1f
  onValueChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
  onDragFinished: () -> Unit = {},
  trackStyle: SliderTrackStyle = SliderTrackStyle.SEEKBAR,
  height: Dp = 24.dp,
  thumbWidth: Dp = 18.dp,
) {
  val skin = LocalDroidAmpSkin.current
  val latestOnChange by rememberUpdatedState(onValueChange)
  val latestOnFinished by rememberUpdatedState(onDragFinished)

  Canvas(
    modifier =
      modifier
        .fillMaxWidth()
        .height(height)
        .pointerInput(Unit) {
          detectTapGestures { offset ->
            val norm = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            latestOnChange(norm)
            latestOnFinished()
          }
        }
        .pointerInput(Unit) {
          detectHorizontalDragGestures(
            onDragEnd = { latestOnFinished() },
            onDragCancel = { latestOnFinished() },
          ) { change, _ ->
            change.consume()
            val norm = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            latestOnChange(norm)
          }
        },
  ) {
    val thumbW = thumbWidth.toPx()
    val trackHeight = (size.height * 0.42f).coerceAtLeast(8f)
    val trackTop = (size.height - trackHeight) * 0.5f

    // Recessed track well
    drawRect(
      color = skin.vfd.wellBackground,
      topLeft = Offset(0f, trackTop),
      size = Size(size.width, trackHeight),
    )

    val norm = value.coerceIn(0f, 1f)
    when (trackStyle) {
      SliderTrackStyle.SEEKBAR -> {
        drawRect(
          color = skin.vfd.phosphorDim,
          topLeft = Offset(2f, trackTop + 2f),
          size = Size(((size.width - 4f) * norm).coerceAtLeast(0f), (trackHeight - 4f).coerceAtLeast(2f)),
        )
      }
      SliderTrackStyle.INTENSITY_RAMP -> {
        val color = skin.visualizer.spectrumColorForLevel(norm)
        drawRect(
          color = color.copy(alpha = 0.85f),
          topLeft = Offset(2f, trackTop + 2f),
          size = Size(((size.width - 4f) * norm).coerceAtLeast(0f), (trackHeight - 4f).coerceAtLeast(2f)),
        )
      }
      SliderTrackStyle.CENTER_BALANCE -> {
        val center = size.width * 0.5f
        val delta = abs(norm - 0.5f) * 2f
        val fillColor = skin.visualizer.spectrumColorForLevel(delta)
        val xPos = norm * size.width
        val left = minOf(center, xPos)
        val fillW = abs(xPos - center)
        drawRect(
          color = fillColor.copy(alpha = 0.85f),
          topLeft = Offset(left, trackTop + 2f),
          size = Size(fillW, (trackHeight - 4f).coerceAtLeast(2f)),
        )
        // Center detent tick
        drawLine(
          color = skin.chassis.labelMuted,
          start = Offset(center, trackTop - 3f),
          end = Offset(center, trackTop + trackHeight + 3f),
          strokeWidth = 1.5f,
        )
      }
    }

    // Recessed track border
    drawLine(skin.bevel.wellBorderDark, Offset(0f, trackTop), Offset(size.width, trackTop), 1.5f)
    drawLine(skin.bevel.wellBorderDark, Offset(0f, trackTop), Offset(0f, trackTop + trackHeight), 1.5f)
    drawLine(
      skin.bevel.wellBorderLight,
      Offset(0f, trackTop + trackHeight),
      Offset(size.width, trackTop + trackHeight),
      1.5f,
    )
    drawLine(
      skin.bevel.wellBorderLight,
      Offset(size.width, trackTop),
      Offset(size.width, trackTop + trackHeight),
      1.5f,
    )

    // Beveled metallic thumb cap
    val thumbX = ((size.width - thumbW) * norm).coerceIn(0f, (size.width - thumbW).coerceAtLeast(0f))
    val thumbH = (size.height * 0.82f).coerceAtLeast(14f)
    val thumbTop = (size.height - thumbH) * 0.5f

    drawRect(
      color = skin.bevel.sliderThumbFace,
      topLeft = Offset(thumbX, thumbTop),
      size = Size(thumbW, thumbH),
    )
    // Highlight top/left
    drawLine(skin.bevel.sliderThumbTop, Offset(thumbX, thumbTop), Offset(thumbX + thumbW, thumbTop), 2f)
    drawLine(skin.bevel.sliderThumbTop, Offset(thumbX, thumbTop), Offset(thumbX, thumbTop + thumbH), 2f)
    // Shadow bottom/right
    drawLine(
      skin.bevel.shadowOuter,
      Offset(thumbX, thumbTop + thumbH),
      Offset(thumbX + thumbW, thumbTop + thumbH),
      2f,
    )
    drawLine(
      skin.bevel.shadowOuter,
      Offset(thumbX + thumbW, thumbTop),
      Offset(thumbX + thumbW, thumbTop + thumbH),
      2f,
    )
    // Tactile vertical grip notch in center of thumb
    val notchX = thumbX + thumbW * 0.5f
    drawLine(
      skin.bevel.sliderThumbGroove,
      Offset(notchX, thumbTop + 3f),
      Offset(notchX, thumbTop + thumbH - 3f),
      1.5f,
    )
  }
}
