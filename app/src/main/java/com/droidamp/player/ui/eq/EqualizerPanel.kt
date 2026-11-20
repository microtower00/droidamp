package com.droidamp.player.ui.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.WinampEqSpec
import com.droidamp.player.skin.DroidAmpSkin
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.BeveledLedToggleButton
import com.droidamp.player.ui.components.ChassisTitleBar
import com.droidamp.player.ui.components.HardwarePanel
import com.droidamp.player.ui.components.HardwareText
import com.droidamp.player.ui.components.MicroMenuAction
import com.droidamp.player.ui.components.RecessedWell
import com.droidamp.player.ui.components.RetroMicroMenuPopup
import kotlin.math.roundToInt

/**
 * 10-band Winamp Equalizer (`60Hz`..`16kHz`) + physically isolated `PREAMP` slider:
 * - Supports horizontal **EQ curve drawing** across the 10 frequency bands without clipping PREAMP.
 * - Shows a transient VFD drag readout (`3KHZ +4.5DB`, `PREAMP +2.0DB`) so thumbs never hide values.
 */
@Composable
fun EqualizerPanel(
  eqState: EqualizerState,
  onToggleEnabled: () -> Unit,
  onToggleAuto: () -> Unit,
  onPreampChange: (Float) -> Unit,
  onBandChange: (bandIndex: Int, db: Float) -> Unit,
  onApplyPreset: (EqPreset) -> Unit,
  modifier: Modifier = Modifier,
  availablePresets: List<EqPreset> = ClassicWinampPresets.ALL,
  autoEqStatusReadout: String? = null,
  sliderBankHeight: Dp = 136.dp,
) {
  val skin = LocalDroidAmpSkin.current
  var activeDragReadout by remember { mutableStateOf<String?>(null) }
  var showPresetsMenu by remember { mutableStateOf(false) }

  val latestPreampChange by rememberUpdatedState(onPreampChange)
  val latestBandChange by rememberUpdatedState(onBandChange)

  HardwarePanel(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth()) {
      ChassisTitleBar(title = "DROIDAMP EQUALIZER")

      Column(
        modifier = Modifier.fillMaxWidth().padding(skin.metrics.panelPadding),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        // Top strip: [ON] [AUTO] [VFD Spline Curve / Live Drag Readout] [PRESETS]
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(5.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          BeveledLedToggleButton(
            label = "ON",
            checked = eqState.enabled,
            onClick = onToggleEnabled,
            compact = true,
            modifier = Modifier.height(28.dp),
          )
          BeveledLedToggleButton(
            label = "AUTO",
            checked = eqState.autoEnabled,
            onClick = onToggleAuto,
            compact = true,
            modifier = Modifier.height(28.dp),
          )

          // Recessed VFD EQ Spline Graph + transient drag readout overlay
          RecessedWell(
            modifier = Modifier.weight(1f).height(28.dp),
            contentPadding = PaddingValues(2.dp),
          ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              EqResponseSplineCanvas(
                bandsDb = eqState.bandsDb,
                preampDb = eqState.preampDb,
                enabled = eqState.enabled,
                modifier = Modifier.fillMaxSize(),
              )
              val overlayReadout =
                activeDragReadout
                  ?: autoEqStatusReadout
                  ?: eqState.activePresetName.takeIf { it.isNotBlank() }?.let { preset ->
                    if (eqState.autoEnabled) "AUTO: ${preset.uppercase()}" else preset.uppercase()
                  }
              if (overlayReadout != null) {
                Box(
                  modifier =
                    Modifier
                      .background(skin.vfd.wellBackground.copy(alpha = if (activeDragReadout != null || autoEqStatusReadout != null) 0.90f else 0.62f))
                      .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                  Text(
                    text = overlayReadout,
                    style = if (activeDragReadout != null || autoEqStatusReadout != null) skin.typography.vfdInfo else skin.typography.microLabel,
                    color = if (activeDragReadout != null || autoEqStatusReadout != null) skin.vfd.phosphorBright else skin.vfd.phosphorDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            }
          }

          Box {
            BeveledButton(
              label = "PRESETS",
              onClick = { showPresetsMenu = true },
              modifier = Modifier.height(28.dp),
              contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
            )
            RetroMicroMenuPopup(
              expanded = showPresetsMenu,
              title = "EQ PRESETS",
              actions =
                availablePresets.map { preset ->
                  MicroMenuAction(label = preset.name) {
                    onApplyPreset(preset)
                  }
                },
              onDismiss = { showPresetsMenu = false },
            )
          }
        }

        // Slider Bank: Isolated PREAMP + etched physical gap + 10-band curve-drawable bank.
        // Protected by systemGestureExclusion() + horizontal inset so edge drags never trigger Android Back (ADR-021).
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(sliderBankHeight)
              .systemGestureExclusion()
              .padding(horizontal = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Left: Isolated PREAMP column
          Column(
            modifier = Modifier.width(34.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Box(
              modifier =
                Modifier
                  .weight(1f)
                  .fillMaxWidth()
                  .pointerInput(Unit) {
                    awaitEachGesture {
                      val down = awaitFirstDown(requireUnconsumed = false)
                      down.consume()
                      val update = { y: Float ->
                        val norm = 1f - (y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val db = snapHalfDb(WinampEqSpec.MIN_DB + norm * 24f)
                        latestPreampChange(db)
                        activeDragReadout = "PREAMP ${formatSignedDb(db)}"
                      }
                      update(down.position.y)
                      while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                          activeDragReadout = null
                          break
                        }
                        change.consume()
                        update(change.position.y)
                      }
                    }
                  },
            ) {
              Canvas(modifier = Modifier.fillMaxSize()) {
                drawVerticalEqTrackAndThumb(
                  skin = skin,
                  db = eqState.preampDb,
                  enabled = eqState.enabled,
                  left = 0f,
                  width = size.width,
                  height = size.height,
                )
              }
            }
            Spacer(Modifier.height(3.dp))
            HardwareText(text = "PRE", color = skin.chassis.labelText)
          }

          // Physical etched gap + dB scale ticks separating PREAMP from the 10 frequency bands (ADR-005)
          Column(
            modifier =
              Modifier
                .width(skin.metrics.preampGap + 18.dp)
                .fillMaxHeight()
                .padding(bottom = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            HardwareText(text = "+12", color = skin.chassis.labelMuted)
            Box(
              modifier =
                Modifier
                  .width(2.dp)
                  .height(14.dp)
                  .background(skin.chassis.divider),
            )
            HardwareText(text = "0dB", color = skin.chassis.labelMuted)
            Box(
              modifier =
                Modifier
                  .width(2.dp)
                  .height(14.dp)
                  .background(skin.chassis.divider),
            )
            HardwareText(text = "-12", color = skin.chassis.labelMuted)
          }

          // Right: 10-band horizontal curve-drawable bank (`60Hz`..`16kHz`)
          Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
          ) {
            Box(
              modifier =
                Modifier
                  .weight(1f)
                  .fillMaxWidth()
                  .pointerInput(Unit) {
                    awaitEachGesture {
                      val down = awaitFirstDown(requireUnconsumed = false)
                      down.consume()
                      var lastBand = -1
                      var lastDb = 0f

                      val applyPoint = { x: Float, y: Float ->
                        val colWidth = size.width.coerceAtLeast(1) / WinampEqSpec.BAND_COUNT.toFloat()
                        val bandIdx = (x / colWidth).toInt().coerceIn(0, WinampEqSpec.BAND_COUNT - 1)
                        val norm = 1f - (y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val db = snapHalfDb(WinampEqSpec.MIN_DB + norm * 24f)

                        // Interpolate intermediate bands during fast horizontal curve drawing
                        if (lastBand in 0 until WinampEqSpec.BAND_COUNT && kotlin.math.abs(bandIdx - lastBand) > 1) {
                          val step = if (bandIdx > lastBand) 1 else -1
                          val totalSteps = kotlin.math.abs(bandIdx - lastBand)
                          var idx = lastBand + step
                          var stepCount = 1
                          while (idx != bandIdx) {
                            val t = stepCount.toFloat() / totalSteps
                            val interp = snapHalfDb(lastDb + (db - lastDb) * t)
                            latestBandChange(idx, interp)
                            idx += step
                            stepCount++
                          }
                        }

                        latestBandChange(bandIdx, db)
                        lastBand = bandIdx
                        lastDb = db
                        activeDragReadout = "${WinampEqSpec.BAND_READOUT_LABELS[bandIdx]} ${formatSignedDb(db)}"
                      }

                      applyPoint(down.position.x, down.position.y)
                      while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                          activeDragReadout = null
                          break
                        }
                        change.consume()
                        applyPoint(change.position.x, change.position.y)
                      }
                    }
                  },
            ) {
              Canvas(modifier = Modifier.fillMaxSize()) {
                val colWidth = size.width / WinampEqSpec.BAND_COUNT.toFloat()
                // Subtle 0dB reference hairline across the 10-band well
                drawLine(
                  color = skin.vfd.eqCurveZeroLine,
                  start = Offset(0f, size.height * 0.5f),
                  end = Offset(size.width, size.height * 0.5f),
                  strokeWidth = 1f,
                )
                for (i in 0 until WinampEqSpec.BAND_COUNT) {
                  drawVerticalEqTrackAndThumb(
                    skin = skin,
                    db = eqState.bandsDb[i],
                    enabled = eqState.enabled,
                    left = i * colWidth,
                    width = colWidth,
                    height = size.height,
                  )
                }
              }
            }

            Spacer(Modifier.height(3.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
              WinampEqSpec.BAND_LABELS.forEach { freqLabel ->
                Text(
                  text = freqLabel,
                  style = skin.typography.microLabel,
                  color = skin.chassis.labelText,
                  textAlign = TextAlign.Center,
                  maxLines = 1,
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun EqResponseSplineCanvas(
  bandsDb: List<Float>,
  preampDb: Float,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  Canvas(modifier = modifier) {
    val midY = size.height * 0.5f
    // 0dB reference line
    drawLine(
      color = skin.vfd.eqCurveZeroLine,
      start = Offset(0f, midY),
      end = Offset(size.width, midY),
      strokeWidth = 1f,
    )

    if (bandsDb.isEmpty()) return@Canvas
    val stepX = size.width / (bandsDb.size - 1).coerceAtLeast(1)
    val path = Path()
    val strokeColor = if (enabled) skin.vfd.eqCurveStroke else skin.vfd.phosphorDim

    bandsDb.forEachIndexed { idx, db ->
      val effective = WinampEqSpec.clampDb(db + preampDb * 0.35f)
      val norm = (effective - WinampEqSpec.MIN_DB) / 24f // 0..1
      val x = idx * stepX
      val y = size.height - norm * size.height
      if (idx == 0) {
        path.moveTo(x, y)
      } else {
        val prevX = (idx - 1) * stepX
        val prevEffective = WinampEqSpec.clampDb(bandsDb[idx - 1] + preampDb * 0.35f)
        val prevY = size.height - ((prevEffective - WinampEqSpec.MIN_DB) / 24f) * size.height
        val cx = (prevX + x) * 0.5f
        path.cubicTo(cx, prevY, cx, y, x, y)
      }
    }

    drawPath(path = path, color = strokeColor, style = Stroke(width = 2f))
  }
}

private fun DrawScope.drawVerticalEqTrackAndThumb(
  skin: DroidAmpSkin,
  db: Float,
  enabled: Boolean,
  left: Float,
  width: Float,
  height: Float,
) {
  val norm = ((WinampEqSpec.clampDb(db) - WinampEqSpec.MIN_DB) / 24f).coerceIn(0f, 1f)
  val trackW = (width * 0.50f).coerceIn(10f, 18f)
  val trackLeft = left + (width - trackW) * 0.5f
  val padY = 4f
  val trackH = (height - padY * 2f).coerceAtLeast(16f)

  val eqFrames = skin.sprites.eqSliderFrames
  if (eqFrames.isNotEmpty()) {
    val frameIdx = (norm * (eqFrames.size - 1)).roundToInt().coerceIn(0, eqFrames.lastIndex)
    drawImage(
      image = eqFrames[frameIdx],
      dstOffset = IntOffset(trackLeft.roundToInt(), padY.roundToInt()),
      dstSize = IntSize(trackW.roundToInt().coerceAtLeast(1), trackH.roundToInt().coerceAtLeast(1)),
      filterQuality = FilterQuality.None,
    )
  } else {
    // Recessed dark track slot
    drawRect(
      color = skin.vfd.wellBackground,
      topLeft = Offset(trackLeft, padY),
      size = Size(trackW, trackH),
    )

    // Classic Winamp intensity tint (bottom green -> mid yellow -> top orange/red)
    val fillColor =
      if (enabled) {
        skin.visualizer.spectrumColorForLevel(norm).copy(alpha = 0.78f)
      } else {
        skin.vfd.phosphorDim.copy(alpha = 0.35f)
      }
    val fillH = (trackH - 2f) * norm
    drawRect(
      color = fillColor,
      topLeft = Offset(trackLeft + 1f, padY + trackH - 1f - fillH),
      size = Size((trackW - 2f).coerceAtLeast(2f), fillH.coerceAtLeast(1f)),
    )

    // Recessed track outline
    drawLine(skin.bevel.wellBorderDark, Offset(trackLeft, padY), Offset(trackLeft + trackW, padY), 1f)
    drawLine(skin.bevel.wellBorderDark, Offset(trackLeft, padY), Offset(trackLeft, padY + trackH), 1f)
    drawLine(
      skin.bevel.wellBorderLight,
      Offset(trackLeft, padY + trackH),
      Offset(trackLeft + trackW, padY + trackH),
      1f,
    )
    drawLine(
      skin.bevel.wellBorderLight,
      Offset(trackLeft + trackW, padY),
      Offset(trackLeft + trackW, padY + trackH),
      1f,
    )
  }

  // Fader cap (aspect-preserving .wsz sprite if available, else chunky beveled cap)
  val capW = (width * 0.84f).coerceIn(10.dp.toPx(), 20.dp.toPx())
  val capH = 12.dp.toPx()
  val capLeft = left + (width - capW) * 0.5f
  val capTop = padY + (1f - norm) * (trackH - capH).coerceAtLeast(0f)

  val eqThumbSprite = skin.sprites.eqThumb
  if (eqThumbSprite != null) {
    val thumbSide = minOf(capW, capH * 1.35f).roundToInt().coerceAtLeast(1)
    val thumbX = (left + (width - thumbSide) * 0.5f).roundToInt()
    val thumbY = (padY + (1f - norm) * (trackH - thumbSide).coerceAtLeast(0f)).roundToInt()
    drawImage(
      image = eqThumbSprite,
      dstOffset = IntOffset(thumbX, thumbY),
      dstSize = IntSize(thumbSide, thumbSide),
      filterQuality = FilterQuality.None,
    )
    return
  }

  drawRect(
    color = skin.bevel.sliderThumbFace,
    topLeft = Offset(capLeft, capTop),
    size = Size(capW, capH),
  )
  val bevelPx = 1.5.dp.toPx()
  drawLine(skin.bevel.sliderThumbTop, Offset(capLeft, capTop), Offset(capLeft + capW, capTop), bevelPx)
  drawLine(skin.bevel.sliderThumbTop, Offset(capLeft, capTop), Offset(capLeft, capTop + capH), bevelPx)
  drawLine(
    skin.bevel.shadowOuter,
    Offset(capLeft, capTop + capH),
    Offset(capLeft + capW, capTop + capH),
    bevelPx,
  )
  drawLine(
    skin.bevel.shadowOuter,
    Offset(capLeft + capW, capTop),
    Offset(capLeft + capW, capTop + capH),
    bevelPx,
  )
  // Center horizontal tactile groove
  val midY = capTop + capH * 0.5f
  drawLine(
    skin.bevel.sliderThumbGroove,
    Offset(capLeft + 2.dp.toPx(), midY),
    Offset(capLeft + capW - 2.dp.toPx(), midY),
    1.2.dp.toPx(),
  )
}

private fun snapHalfDb(value: Float): Float =
  ((value * 2f).roundToInt() / 2f).coerceIn(WinampEqSpec.MIN_DB, WinampEqSpec.MAX_DB)

private fun formatSignedDb(db: Float): String {
  val sign = if (db > 0f) "+" else ""
  return "$sign%.1fDB".format(db)
}
