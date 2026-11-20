package com.droidamp.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.droidamp.player.domain.model.VisualizerFrame
import com.droidamp.player.domain.model.VisualizerMode
import com.droidamp.player.skin.LocalDroidAmpSkin
import kotlin.math.roundToInt

/**
 * 60fps Winamp VFD Visualizer supporting 19-band Spectrum Analyzer (with peak dots),
 * real-time Oscilloscope, and Off mode — driven exclusively by the 24-entry `viscolor.txt` LUT.
 */
@Composable
fun VfdVisualizerCanvas(
  frame: VisualizerFrame,
  mode: VisualizerMode,
  onCycleMode: () -> Unit,
  modifier: Modifier = Modifier,
) {
  VfdVisualizerCanvas(
    frameProvider = { frame },
    mode = mode,
    onCycleMode = onCycleMode,
    modifier = modifier,
  )
}

@Composable
fun VfdVisualizerCanvas(
  frameProvider: () -> VisualizerFrame,
  mode: VisualizerMode,
  onCycleMode: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  val vis = skin.visualizer

  Canvas(
    modifier =
      modifier
        .fillMaxSize()
        .semantics { contentDescription = "Visualizer mode ${mode.name}" }
        .clickable(role = Role.Button, onClick = onCycleMode),
  ) {
    drawRect(color = vis.background)

    // Subtle classic Winamp VFD dot matrix backdrop
    val dotStep = 5f
    var gx = dotStep
    while (gx < size.width) {
      var gy = dotStep
      while (gy < size.height) {
        drawRect(
          color = vis.gridDot,
          topLeft = Offset(gx, gy),
          size = Size(1.2f, 1.2f),
        )
        gy += dotStep
      }
      gx += dotStep
    }

    when (mode) {
      VisualizerMode.SPECTRUM -> {
        val frame = frameProvider()
        val barCount = frame.spectrumBars.size.coerceAtLeast(1)
        val gap = 2f
        val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
        val ledRows = 16
        val rowGap = 1.2f
        val rowHeight = ((size.height - rowGap * (ledRows - 1)) / ledRows).coerceAtLeast(1.5f)

        for (i in 0 until barCount) {
          val level = frame.spectrumBars[i].coerceIn(0f, 1f)
          val litRows = (level * ledRows).roundToInt().coerceIn(0, ledRows)
          val x = i * (barWidth + gap)

          for (row in 0 until litRows) {
            val rowNorm = row.toFloat() / (ledRows - 1).coerceAtLeast(1)
            val y = size.height - (row + 1) * rowHeight - row * rowGap
            drawRect(
              color = vis.spectrumColorForLevel(rowNorm),
              topLeft = Offset(x, y),
              size = Size(barWidth, rowHeight),
            )
          }

          val peak = frame.peakDots.getOrElse(i) { 0f }.coerceIn(0f, 1f)
          if (peak > 0.03f) {
            val peakRow = (peak * (ledRows - 1)).roundToInt().coerceIn(0, ledRows - 1)
            val peakY = size.height - (peakRow + 1) * rowHeight - peakRow * rowGap
            drawRect(
              color = vis.peakCap,
              topLeft = Offset(x, peakY),
              size = Size(barWidth, rowHeight),
            )
          }
        }
      }
      VisualizerMode.OSCILLOSCOPE -> {
        val frame = frameProvider()
        val samples = frame.waveform
        if (samples.size >= 2) {
          val centerY = size.height * 0.5f
          val amp = size.height * 0.44f
          val stepX = size.width / (samples.size - 1)

          for (i in 0 until samples.size - 1) {
            val x1 = i * stepX
            val x2 = (i + 1) * stepX
            val y1 = centerY - samples[i].coerceIn(-1f, 1f) * amp
            val y2 = centerY - samples[i + 1].coerceIn(-1f, 1f) * amp
            drawLine(
              color = vis.oscilloscopePrimary,
              start = Offset(x1, y1),
              end = Offset(x2, y2),
              strokeWidth = 2f,
            )
          }
        }
      }
      VisualizerMode.OFF -> {
        // Clean dormant VFD center hairline
        drawLine(
          color = vis.gridDot,
          start = Offset(4f, size.height * 0.5f),
          end = Offset(size.width - 4f, size.height * 0.5f),
          strokeWidth = 1f,
        )
      }
    }
  }
}
