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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.droidamp.player.skin.LocalDroidAmpSkin
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val trackHeight = (size.height * 0.48f).coerceAtLeast(10f)
    val trackTop = (size.height - trackHeight) * 0.5f
    val norm = value.coerceIn(0f, 1f)

    // Check if active `.wsz` skin supplies sprite track frames / thumb for this slider
    val spriteTrack =
      when (trackStyle) {
        SliderTrackStyle.SEEKBAR -> skin.sprites.seekbarTrack
        SliderTrackStyle.INTENSITY_RAMP -> {
          val frames = skin.sprites.volumeTrackFrames
          if (frames.isNotEmpty()) {
            frames[(norm * (frames.size - 1)).roundToInt().coerceIn(0, frames.lastIndex)]
          } else {
            null
          }
        }
        SliderTrackStyle.CENTER_BALANCE -> {
          val frames = skin.sprites.balanceTrackFrames
          if (frames.isNotEmpty()) {
            val delta = abs(norm - 0.5f) * 2f
            frames[(delta * (frames.size - 1)).roundToInt().coerceIn(0, frames.lastIndex)]
          } else {
            null
          }
        }
      }

    val spriteThumb =
      when (trackStyle) {
        SliderTrackStyle.SEEKBAR -> skin.sprites.seekbarThumb
        SliderTrackStyle.INTENSITY_RAMP -> skin.sprites.volumeThumb
        SliderTrackStyle.CENTER_BALANCE -> skin.sprites.balanceThumb
      }

    if (spriteTrack != null) {
      drawImage(
        image = spriteTrack,
        dstOffset = IntOffset(0, trackTop.roundToInt()),
        dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), trackHeight.roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.None,
      )
    } else {
      // Recessed track well
      drawRect(
        color = skin.vfd.wellBackground,
        topLeft = Offset(0f, trackTop),
        size = Size(size.width, trackHeight),
      )

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
    }

    // Thumb cap (sprite if provided by .wsz, else beveled metallic thumb)
    val thumbX = ((size.width - thumbW) * norm).coerceIn(0f, (size.width - thumbW).coerceAtLeast(0f))
    val thumbH = (size.height * 0.82f).coerceAtLeast(14f)
    val thumbTop = (size.height - thumbH) * 0.5f

    if (spriteThumb != null) {
      drawImage(
        image = spriteThumb,
        dstOffset = IntOffset(thumbX.roundToInt(), thumbTop.roundToInt()),
        dstSize = IntSize(thumbW.roundToInt().coerceAtLeast(1), thumbH.roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.None,
      )
    } else {
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
}
