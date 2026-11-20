package com.droidamp.player.ui.deck

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.domain.model.RepeatMode
import com.droidamp.player.domain.model.VisualizerFrame
import com.droidamp.player.domain.model.formatWinampDuration
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.skin.TransportIconType
import com.droidamp.player.ui.components.BeveledIconButton
import com.droidamp.player.ui.components.BeveledLedToggleButton
import com.droidamp.player.ui.components.ChassisTitleBar
import com.droidamp.player.ui.components.HardwarePanel
import com.droidamp.player.ui.components.HardwareText
import com.droidamp.player.ui.components.HorizontalHardwareSlider
import com.droidamp.player.ui.components.RecessedWell
import com.droidamp.player.ui.components.ScrollingMarqueeText
import com.droidamp.player.ui.components.SevenSegmentClock
import com.droidamp.player.ui.components.SliderTrackStyle
import com.droidamp.player.ui.components.VfdStatusGlyph
import com.droidamp.player.ui.components.VfdVisualizerCanvas
import kotlin.math.roundToInt

@Composable
fun MainDeckPanel(
  playbackState: PlaybackState,
  visualizerFrame: VisualizerFrame,
  isEqVisible: Boolean,
  isSheetExpanded: Boolean,
  onToggleTimeMode: () -> Unit,
  onCycleVisualizerMode: () -> Unit,
  onVolumeChange: (Int) -> Unit,
  onBalanceChange: (Int) -> Unit,
  onSeekTo: (Long) -> Unit,
  onPrev: () -> Unit,
  onPlay: () -> Unit,
  onPause: () -> Unit,
  onStop: () -> Unit,
  onNext: () -> Unit,
  onEject: () -> Unit,
  onToggleShuffle: () -> Unit,
  onCycleRepeat: () -> Unit,
  onToggleEqVisible: () -> Unit,
  onTogglePlaylistSheet: () -> Unit,
  modifier: Modifier = Modifier,
) {
  MainDeckPanel(
    playbackState = playbackState,
    visualizerFrameProvider = { visualizerFrame },
    isEqVisible = isEqVisible,
    isSheetExpanded = isSheetExpanded,
    onToggleTimeMode = onToggleTimeMode,
    onCycleVisualizerMode = onCycleVisualizerMode,
    onVolumeChange = onVolumeChange,
    onBalanceChange = onBalanceChange,
    onSeekTo = onSeekTo,
    onPrev = onPrev,
    onPlay = onPlay,
    onPause = onPause,
    onStop = onStop,
    onNext = onNext,
    onEject = onEject,
    onToggleShuffle = onToggleShuffle,
    onCycleRepeat = onCycleRepeat,
    onToggleEqVisible = onToggleEqVisible,
    onTogglePlaylistSheet = onTogglePlaylistSheet,
    modifier = modifier,
  )
}

@Composable
fun MainDeckPanel(
  playbackState: PlaybackState,
  visualizerFrameProvider: () -> VisualizerFrame,
  isEqVisible: Boolean,
  isSheetExpanded: Boolean,
  onToggleTimeMode: () -> Unit,
  onCycleVisualizerMode: () -> Unit,
  onVolumeChange: (Int) -> Unit,
  onBalanceChange: (Int) -> Unit,
  onSeekTo: (Long) -> Unit,
  onPrev: () -> Unit,
  onPlay: () -> Unit,
  onPause: () -> Unit,
  onStop: () -> Unit,
  onNext: () -> Unit,
  onEject: () -> Unit,
  onToggleShuffle: () -> Unit,
  onCycleRepeat: () -> Unit,
  onToggleEqVisible: () -> Unit,
  onTogglePlaylistSheet: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  var transientMarqueeOverride by remember { mutableStateOf<String?>(null) }
  var isDraggingSeek by remember { mutableStateOf(false) }
  var seekPreviewNorm by remember { mutableStateOf(0f) }

  val track = playbackState.currentTrack
  val defaultMarquee =
    if (track != null) {
      val idx = (playbackState.currentIndex + 1).coerceAtLeast(1)
      "$idx. ${track.displayLabel} (${formatWinampDuration(track.durationMs)})  ***  "
    } else {
      "DROIDAMP v0.1.0-alpha.1  —  NO TRACK LOADED  ***  "
    }

  val durationMs = playbackState.durationMs.coerceAtLeast(1L)
  val seekNorm =
    if (isDraggingSeek) {
      seekPreviewNorm
    } else {
      (playbackState.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

  val visualizerHeight = 36.dp
  val transportHeight = 36.dp
  val sliderHeight = 24.dp

  HardwarePanel(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth()) {
      ChassisTitleBar(title = "DROIDAMP")

      Column(
        modifier = Modifier.fillMaxWidth().padding(skin.metrics.panelPadding),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        // Row 1: Recessed VFD Clock + Visualizer (left) & Marquee + Bitrate/kHz/Stereo (right)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.Top,
        ) {
          // Left VFD Well: Carved Status Glyph + 7-Segment Clock + 60-120fps Visualizer
          RecessedWell(
            modifier = Modifier.width(128.dp),
            contentPadding = PaddingValues(4.dp),
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                VfdStatusGlyph(
                  status = playbackState.status,
                  glyphSize = 11.dp,
                )
                SevenSegmentClock(
                  positionMs = playbackState.positionMs,
                  durationMs = playbackState.durationMs,
                  displayMode = playbackState.timeDisplayMode,
                  onToggleMode = onToggleTimeMode,
                )
              }
              Box(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .height(visualizerHeight)
                    .background(skin.visualizer.background),
              ) {
                VfdVisualizerCanvas(
                  frameProvider = visualizerFrameProvider,
                  mode = playbackState.visualizerMode,
                  onCycleMode = onCycleVisualizerMode,
                )
              }
            }
          }

          // Right VFD Column: Scrolling Marquee + Bitrate/kHz + Stereo/Mono
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            RecessedWell(
              modifier = Modifier.fillMaxWidth(),
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
            ) {
              ScrollingMarqueeText(
                text = transientMarqueeOverride ?: defaultMarquee,
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(5.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              // KBPS
              RecessedWell(contentPadding = PaddingValues(horizontal = 5.dp, vertical = 3.dp)) {
                Text(
                  text = (track?.bitrateKbps ?: 0).toString().padStart(3, ' '),
                  style = skin.typography.vfdInfo,
                  color = skin.vfd.phosphorPrimary,
                )
              }
              HardwareText(text = "KBPS")

              // KHZ
              RecessedWell(contentPadding = PaddingValues(horizontal = 5.dp, vertical = 3.dp)) {
                Text(
                  text = (track?.sampleRateKhz ?: 0).toString().padStart(2, ' '),
                  style = skin.typography.vfdInfo,
                  color = skin.vfd.phosphorPrimary,
                )
              }
              HardwareText(text = "KHZ")

              Spacer(Modifier.weight(1f))

              // MONO / STEREO indicators (use monoster.bmp sprites when provided by .wsz skin)
              val isStereo = track?.channelMode == ChannelMode.STEREO
              val isMono = track?.channelMode == ChannelMode.MONO
              val monoSprite = if (isMono) skin.sprites.monoActive else skin.sprites.monoInactive
              val stereoSprite = if (isStereo) skin.sprites.stereoActive else skin.sprites.stereoInactive
              if (monoSprite != null && stereoSprite != null) {
                Canvas(modifier = Modifier.width(34.dp).height(14.dp)) {
                  drawImage(
                    image = monoSprite,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.None,
                  )
                }
                Canvas(modifier = Modifier.width(34.dp).height(14.dp)) {
                  drawImage(
                    image = stereoSprite,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.None,
                  )
                }
              } else {
                HardwareText(
                  text = "MONO",
                  color = if (isMono) skin.vfd.phosphorPrimary else skin.chassis.labelMuted,
                )
                HardwareText(
                  text = "STEREO",
                  color = if (isStereo) skin.vfd.phosphorPrimary else skin.chassis.labelMuted,
                )
              }
            }
          }
        }

        // Row 2: Dedicated Full-Width Volume (60%) + Balance (40%) Row so sliders & .wsz sprites aren't squished
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1.45f)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              HardwareText(text = "VOL")
              HardwareText(
                text = "${playbackState.volumePercent}%",
                color = skin.vfd.phosphorPrimary,
              )
            }
            HorizontalHardwareSlider(
              value = playbackState.volume / PlaybackState.MAX_VOLUME.toFloat(),
              onValueChange = { norm ->
                val vol = (norm * PlaybackState.MAX_VOLUME).roundToInt().coerceIn(0, 255)
                transientMarqueeOverride = "VOLUME: ${((vol / 255f) * 100f).roundToInt()}% ($vol/255)"
                onVolumeChange(vol)
              },
              onDragFinished = { transientMarqueeOverride = null },
              trackStyle = SliderTrackStyle.INTENSITY_RAMP,
              height = sliderHeight,
              thumbWidth = 16.dp,
            )
          }

          Column(modifier = Modifier.weight(1.0f)) {
            val balLabel =
              when {
                playbackState.balance == 0 -> "CTR"
                playbackState.balance < 0 -> "L${(-playbackState.balance * 100 / 127)}"
                else -> "R${(playbackState.balance * 100 / 127)}"
              }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              HardwareText(text = "BAL")
              HardwareText(text = balLabel, color = skin.vfd.phosphorPrimary)
            }
            HorizontalHardwareSlider(
              value = (playbackState.balance - PlaybackState.MIN_BALANCE) / 254f,
              onValueChange = { norm ->
                val raw = (norm * 254f + PlaybackState.MIN_BALANCE).roundToInt()
                val snapped = if (raw in -8..8) 0 else raw.coerceIn(-127, 127)
                transientMarqueeOverride =
                  when {
                    snapped == 0 -> "BALANCE: CENTER"
                    snapped < 0 -> "BALANCE: ${-snapped * 100 / 127}% LEFT"
                    else -> "BALANCE: ${snapped * 100 / 127}% RIGHT"
                  }
                onBalanceChange(snapped)
              },
              onDragFinished = { transientMarqueeOverride = null },
              trackStyle = SliderTrackStyle.CENTER_BALANCE,
              height = sliderHeight,
              thumbWidth = 14.dp,
            )
          }
        }

        // Row 2: Full-width Position Seekbar
        HorizontalHardwareSlider(
          value = seekNorm,
          onValueChange = { norm ->
            isDraggingSeek = true
            seekPreviewNorm = norm
            val previewMs = (norm * playbackState.durationMs).toLong()
            transientMarqueeOverride =
              "SEEK TO: ${formatWinampDuration(previewMs)} / ${formatWinampDuration(playbackState.durationMs)}"
          },
          onDragFinished = {
            if (isDraggingSeek) {
              onSeekTo((seekPreviewNorm * playbackState.durationMs).toLong())
            }
            isDraggingSeek = false
            transientMarqueeOverride = null
          },
          trackStyle = SliderTrackStyle.SEEKBAR,
          height = 22.dp,
          thumbWidth = 24.dp,
        )

        // Row 3: Classic Carved-Glyph Transport Buttons (|< ▶ ❚❚ ■ >| ⏏)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(3.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          BeveledIconButton(
            icon = TransportIconType.PREV,
            contentDescription = "Previous track",
            onClick = onPrev,
            glyphSize = 15.dp,
            modifier = Modifier.weight(1f).height(transportHeight),
          )
          BeveledIconButton(
            icon = TransportIconType.PLAY,
            contentDescription = "Play",
            onClick = onPlay,
            highlighted = playbackState.status == PlaybackStatus.PLAYING,
            glyphSize = 15.dp,
            modifier = Modifier.weight(1f).height(transportHeight),
          )
          BeveledIconButton(
            icon = TransportIconType.PAUSE,
            contentDescription = "Pause",
            onClick = onPause,
            highlighted = playbackState.status == PlaybackStatus.PAUSED,
            glyphSize = 15.dp,
            modifier = Modifier.weight(1f).height(transportHeight),
          )
          BeveledIconButton(
            icon = TransportIconType.STOP,
            contentDescription = "Stop",
            onClick = onStop,
            glyphSize = 14.dp,
            modifier = Modifier.weight(1f).height(transportHeight),
          )
          BeveledIconButton(
            icon = TransportIconType.NEXT,
            contentDescription = "Next track",
            onClick = onNext,
            glyphSize = 15.dp,
            modifier = Modifier.weight(1f).height(transportHeight),
          )
          BeveledIconButton(
            icon = TransportIconType.EJECT,
            contentDescription = "Open Library",
            onClick = onEject,
            glyphSize = 15.dp,
            modifier = Modifier.weight(1f).height(transportHeight),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          BeveledLedToggleButton(
            label = "SHUFFLE",
            checked = playbackState.shuffle,
            onClick = onToggleShuffle,
            compact = true,
            spriteOff = skin.sprites.shuffleOff,
            spriteOn = skin.sprites.shuffleOn,
            modifier = Modifier.weight(1.25f).height(28.dp),
          )
          val repeatLabel =
            when (playbackState.repeatMode) {
              RepeatMode.OFF -> "REPEAT"
              RepeatMode.ALL -> "REP:ALL"
              RepeatMode.ONE -> "REP:1"
            }
          BeveledLedToggleButton(
            label = repeatLabel,
            checked = playbackState.repeatMode != RepeatMode.OFF,
            onClick = onCycleRepeat,
            compact = true,
            spriteOff = skin.sprites.repeatOff,
            spriteOn = skin.sprites.repeatOn,
            modifier = Modifier.weight(1.05f).height(28.dp),
          )
          BeveledLedToggleButton(
            label = "EQ",
            checked = isEqVisible,
            onClick = onToggleEqVisible,
            compact = true,
            spriteOff = skin.sprites.eqButtonOff,
            spriteOn = skin.sprites.eqButtonOn,
            modifier = Modifier.weight(0.75f).height(28.dp),
          )
          BeveledLedToggleButton(
            label = "QUEUE",
            checked = isSheetExpanded,
            onClick = onTogglePlaylistSheet,
            compact = true,
            spriteOff = skin.sprites.plButtonOff,
            spriteOn = skin.sprites.plButtonOn,
            modifier = Modifier.weight(0.85f).height(28.dp),
          )
        }
      }
    }
  }
}
