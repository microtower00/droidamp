package com.droidamp.player.ui.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.WinampEqSpec
import com.droidamp.player.interop.PresetConflictType
import com.droidamp.player.skin.DroidAmpSkin
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.BeveledLedToggleButton
import com.droidamp.player.ui.components.ChassisTitleBar
import com.droidamp.player.ui.components.HardwarePanel
import com.droidamp.player.ui.components.HardwareText
import com.droidamp.player.ui.components.RecessedWell
import com.droidamp.player.ui.components.recessedBevel
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 10-band Winamp Equalizer (`60Hz`..`16kHz`) + physically isolated `PREAMP` slider:
 * - Supports horizontal **EQ curve drawing** across the 10 frequency bands without clipping PREAMP.
 * - Supports **double-tap reset to `0.0 dB`** on PREAMP and every frequency band.
 * - Shows a transient VFD drag/reset readout (`3KHZ +4.5DB`, `PREAMP +0.0DB`) so thumbs never hide values.
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
  userPresetNames: Set<String> = emptySet(),
  autoEqStatusReadout: String? = null,
  sliderBankHeight: Dp = 136.dp,
  onCheckPresetConflict: ((String) -> PresetConflictType)? = null,
  onSavePreset: ((name: String, overwrite: Boolean) -> Unit)? = null,
  onResetFlat: (() -> Unit)? = null,
  onManagePresetsClick: (() -> Unit)? = null,
) {
  val skin = LocalDroidAmpSkin.current
  var activeDragReadout by remember { mutableStateOf<String?>(null) }
  var tapResetReadout by remember { mutableStateOf<String?>(null) }
  var tapResetSeq by remember { mutableIntStateOf(0) }
  LaunchedEffect(tapResetSeq) {
    if (tapResetSeq > 0) {
      delay(650L)
      tapResetReadout = null
    }
  }

  var showPresetsMenu by remember { mutableStateOf(false) }
  var isNamingPreset by remember { mutableStateOf(false) }
  var draftPresetName by remember { mutableStateOf("") }
  var namingConflict by remember { mutableStateOf(PresetConflictType.NONE) }
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(isNamingPreset) {
    if (isNamingPreset) {
      focusRequester.requestFocus()
    }
  }

  val normalizedUserPresetNames =
    remember(userPresetNames) {
      userPresetNames.map { it.trim().lowercase(Locale.ROOT) }.toSet()
    }

  val latestPreampChange by rememberUpdatedState(onPreampChange)
  val latestBandChange by rememberUpdatedState(onBandChange)

  fun attemptInlineSave() {
    val trimmed = draftPresetName.trim()
    if (trimmed.isEmpty()) return
    val conflict = onCheckPresetConflict?.invoke(trimmed) ?: PresetConflictType.NONE
    if (conflict == PresetConflictType.NONE) {
      onSavePreset?.invoke(trimmed, true)
      isNamingPreset = false
      namingConflict = PresetConflictType.NONE
    } else {
      namingConflict = conflict
    }
  }

  HardwarePanel(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth()) {
      ChassisTitleBar(
        title = "DROIDAMP EQUALIZER",
        customSprite = skin.sprites.resolvedEqHeader.takeIf { skin.sprites.hasCompletePanelHeaders },
      )

      Column(
        modifier = Modifier.fillMaxWidth().padding(skin.metrics.panelPadding),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        // Top strip: Inline VFD preset naming bar OR [ON] [AUTO] [VFD Spline Curve] [PRESETS]
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(5.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (isNamingPreset) {
            if (namingConflict == PresetConflictType.USER_PRESET) {
              RecessedWell(
                modifier = Modifier.weight(1f).height(28.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
              ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                  Text(
                    text = "OVERWRITE '${draftPresetName.trim().uppercase()}'?",
                    style = skin.typography.microLabel,
                    color = skin.vfd.ledAmber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
              BeveledButton(
                label = "YES",
                highlighted = true,
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                onClick = {
                  onSavePreset?.invoke(draftPresetName.trim(), true)
                  isNamingPreset = false
                  namingConflict = PresetConflictType.NONE
                },
              )
              BeveledButton(
                label = "×",
                modifier = Modifier.width(28.dp).height(28.dp),
                contentPadding = PaddingValues(0.dp),
                onClick = { namingConflict = PresetConflictType.NONE },
              )
            } else {
              RecessedWell(
                modifier = Modifier.weight(1f).height(28.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxSize(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                  ) {
                    if (draftPresetName.isEmpty()) {
                      Text(
                        text = "PRESET NAME",
                        style = skin.typography.trackRow,
                        color = skin.vfd.phosphorDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    }
                    BasicTextField(
                      value = draftPresetName,
                      onValueChange = {
                        draftPresetName = it
                        if (namingConflict != PresetConflictType.NONE) {
                          namingConflict = PresetConflictType.NONE
                        }
                      },
                      singleLine = true,
                      textStyle = skin.typography.trackRow.copy(color = skin.vfd.phosphorPrimary),
                      cursorBrush = SolidColor(skin.vfd.phosphorPrimary),
                      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                      keyboardActions = KeyboardActions(onDone = { attemptInlineSave() }),
                      modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                  }
                  if (namingConflict == PresetConflictType.BUILT_IN_PRESET) {
                    Text(
                      text = "BUILT-IN",
                      style = skin.typography.microLabel,
                      color = skin.vfd.ledAmber,
                      maxLines = 1,
                    )
                  }
                }
              }
              BeveledButton(
                label = "SAVE",
                highlighted = true,
                enabled = draftPresetName.isNotBlank(),
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                onClick = { attemptInlineSave() },
              )
              BeveledButton(
                label = "×",
                modifier = Modifier.width(28.dp).height(28.dp),
                contentPadding = PaddingValues(0.dp),
                onClick = {
                  isNamingPreset = false
                  namingConflict = PresetConflictType.NONE
                },
              )
            }
          } else {
            BeveledLedToggleButton(
              label = "ON",
              checked = eqState.enabled,
              onClick = onToggleEnabled,
              compact = true,
              spriteOff = skin.sprites.eqOnOff,
              spriteOn = skin.sprites.eqOnOn,
              modifier = Modifier.height(28.dp).let { if (skin.sprites.eqOnOff != null) it.width(46.dp) else it },
            )
            BeveledLedToggleButton(
              label = "AUTO",
              checked = eqState.autoEnabled,
              onClick = onToggleAuto,
              compact = true,
              spriteOff = skin.sprites.eqAutoOff,
              spriteOn = skin.sprites.eqAutoOn,
              modifier = Modifier.height(28.dp).let { if (skin.sprites.eqAutoOff != null) it.width(56.dp) else it },
            )

            // Recessed VFD EQ Spline Graph + transient drag/reset readout overlay
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
                    ?: tapResetReadout
                    ?: autoEqStatusReadout
                    ?: eqState.activePresetName.takeIf { it.isNotBlank() }?.let { preset ->
                      if (eqState.autoEnabled) "AUTO: ${preset.uppercase()}" else preset.uppercase()
                    }
                val isHighlightedReadout =
                  activeDragReadout != null || tapResetReadout != null || autoEqStatusReadout != null
                if (overlayReadout != null) {
                  Box(
                    modifier =
                      Modifier
                        .background(skin.vfd.wellBackground.copy(alpha = if (isHighlightedReadout) 0.90f else 0.62f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                  ) {
                    Text(
                      text = overlayReadout,
                      style = if (isHighlightedReadout) skin.typography.vfdInfo else skin.typography.microLabel,
                      color = if (isHighlightedReadout) skin.vfd.phosphorBright else skin.vfd.phosphorDim,
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
                spriteNormal = skin.sprites.eqPresetsNormal,
                spritePressed = skin.sprites.eqPresetsPressed,
                modifier = Modifier.height(28.dp).let { if (skin.sprites.eqPresetsNormal != null) it.width(74.dp) else it },
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
              )
              EqPresetsContextualPopup(
                expanded = showPresetsMenu,
                activePresetName = eqState.activePresetName,
                availablePresets = availablePresets,
                normalizedUserPresetNames = normalizedUserPresetNames,
                onDismiss = { showPresetsMenu = false },
                onStartSavePreset =
                  if (onSavePreset != null) {
                    {
                      draftPresetName =
                        if (eqState.activePresetName.trim().lowercase(Locale.ROOT) in normalizedUserPresetNames) {
                          eqState.activePresetName
                        } else {
                          ""
                        }
                      namingConflict = PresetConflictType.NONE
                      isNamingPreset = true
                    }
                  } else {
                    null
                  },
                onResetFlat = onResetFlat,
                onApplyPreset = onApplyPreset,
                onManagePresetsClick = onManagePresetsClick,
              )
            }
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
                    var lastTapUpMs = 0L
                    awaitEachGesture {
                      val down = awaitFirstDown(requireUnconsumed = false)
                      down.consume()
                      val downPos = down.position
                      var maxMoveSq = 0f
                      val slopSq = viewConfiguration.touchSlop * viewConfiguration.touchSlop
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
                        val dx = change.position.x - downPos.x
                        val dy = change.position.y - downPos.y
                        val moveSq = dx * dx + dy * dy
                        if (moveSq > maxMoveSq) {
                          maxMoveSq = moveSq
                        }
                        if (!change.pressed) {
                          if (maxMoveSq <= slopSq) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapUpMs <= viewConfiguration.doubleTapTimeoutMillis) {
                              lastTapUpMs = 0L
                              latestPreampChange(0f)
                              activeDragReadout = null
                              tapResetReadout = "PREAMP +0.0DB"
                              tapResetSeq++
                            } else {
                              lastTapUpMs = now
                              activeDragReadout = null
                            }
                          } else {
                            lastTapUpMs = 0L
                            activeDragReadout = null
                          }
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
            HardwareText(text = "0 dB", color = skin.chassis.labelMuted)
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
                    var lastTapUpMs = 0L
                    var lastTapBand = -1
                    awaitEachGesture {
                      val down = awaitFirstDown(requireUnconsumed = false)
                      down.consume()
                      val downPos = down.position
                      val downBand =
                        (downPos.x / (size.width.coerceAtLeast(1) / WinampEqSpec.BAND_COUNT.toFloat()))
                          .toInt()
                          .coerceIn(0, WinampEqSpec.BAND_COUNT - 1)
                      var maxMoveSq = 0f
                      val slopSq = viewConfiguration.touchSlop * viewConfiguration.touchSlop
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
                        val dx = change.position.x - downPos.x
                        val dy = change.position.y - downPos.y
                        val moveSq = dx * dx + dy * dy
                        if (moveSq > maxMoveSq) {
                          maxMoveSq = moveSq
                        }
                        if (!change.pressed) {
                          if (maxMoveSq <= slopSq) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapUpMs <= viewConfiguration.doubleTapTimeoutMillis && lastTapBand == downBand) {
                              lastTapUpMs = 0L
                              lastTapBand = -1
                              latestBandChange(downBand, 0f)
                              activeDragReadout = null
                              tapResetReadout = "${WinampEqSpec.BAND_READOUT_LABELS[downBand]} +0.0DB"
                              tapResetSeq++
                            } else {
                              lastTapUpMs = now
                              lastTapBand = downBand
                              activeDragReadout = null
                            }
                          } else {
                            lastTapUpMs = 0L
                            lastTapBand = -1
                            activeDragReadout = null
                          }
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
private fun EqPresetsContextualPopup(
  expanded: Boolean,
  activePresetName: String,
  availablePresets: List<EqPreset>,
  normalizedUserPresetNames: Set<String>,
  onDismiss: () -> Unit,
  onStartSavePreset: (() -> Unit)?,
  onResetFlat: (() -> Unit)?,
  onApplyPreset: (EqPreset) -> Unit,
  onManagePresetsClick: (() -> Unit)?,
) {
  if (!expanded) return
  val skin = LocalDroidAmpSkin.current
  val popupOffsetPx = with(LocalDensity.current) { 30.dp.roundToPx() }
  val (userList, builtInList) =
    remember(availablePresets, normalizedUserPresetNames) {
      availablePresets.partition { preset ->
        preset.name.trim().lowercase(Locale.ROOT) in normalizedUserPresetNames
      }
    }
  val orderedPresets = remember(userList, builtInList) { userList + builtInList }

  Popup(
    alignment = Alignment.TopEnd,
    offset = IntOffset(0, popupOffsetPx),
    onDismissRequest = onDismiss,
    properties = PopupProperties(focusable = true),
  ) {
    HardwarePanel(
      modifier = Modifier.width(252.dp),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = "EQ PRESETS",
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

        if (onStartSavePreset != null) {
          PopupActionRow(
            label = "+ SAVE CURRENT AS NEW…",
            textColor = skin.vfd.phosphorBright,
            onClick = {
              onDismiss()
              onStartSavePreset()
            },
          )
        }

        if (onResetFlat != null) {
          PopupActionRow(
            label = "RESET TO FLAT (0 DB)",
            textColor = skin.vfd.phosphorPrimary,
            onClick = {
              onDismiss()
              onResetFlat()
            },
          )
        }

        if (onStartSavePreset != null || onResetFlat != null) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(skin.chassis.divider),
          )
        }

        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(max = 220.dp)
              .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          orderedPresets.forEach { preset ->
            val isCurrent = preset.name.equals(activePresetName, ignoreCase = true)
            val isUserPreset = preset.name.trim().lowercase(Locale.ROOT) in normalizedUserPresetNames
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .background(if (isCurrent) skin.playlist.selectedBackground else skin.vfd.wellBackground)
                  .recessedBevel(skin, thickness = 1.dp)
                  .clickable {
                    onDismiss()
                    onApplyPreset(preset)
                  }
                  .padding(horizontal = 8.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Box(
                modifier =
                  Modifier
                    .size(width = 5.dp, height = 8.dp)
                    .background(if (isCurrent) skin.vfd.ledOn else skin.vfd.ledOff)
                    .recessedBevel(skin, thickness = 1.dp),
              )
              Text(
                text = preset.name.uppercase(),
                style = skin.typography.buttonLabel,
                color = if (isCurrent) skin.playlist.selectedText else skin.vfd.phosphorPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              if (isUserPreset) {
                Text(
                  text = "USER",
                  style = skin.typography.microLabel,
                  color = skin.vfd.phosphorDim,
                  maxLines = 1,
                )
              }
            }
          }
        }

        if (onManagePresetsClick != null) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(skin.chassis.divider),
          )
          PopupActionRow(
            label = "⚙ MANAGE PRESETS & AUTO-EQ…",
            textColor = skin.vfd.phosphorPrimary,
            onClick = {
              onDismiss()
              onManagePresetsClick()
            },
          )
        }
      }
    }
  }
}

@Composable
private fun PopupActionRow(
  label: String,
  textColor: androidx.compose.ui.graphics.Color,
  onClick: () -> Unit,
) {
  val skin = LocalDroidAmpSkin.current
  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .background(skin.vfd.wellBackground)
        .recessedBevel(skin, thickness = 1.dp)
        .clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 6.dp),
  ) {
    Text(
      text = label,
      style = skin.typography.microLabel,
      color = textColor,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
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
