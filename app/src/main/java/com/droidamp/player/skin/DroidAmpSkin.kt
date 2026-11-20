package com.droidamp.player.skin

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class ChassisPalette(
  val windowBackground: Color,
  val panelSurface: Color,
  val panelSurfaceAlt: Color,
  val titleBarActiveStart: Color,
  val titleBarActiveEnd: Color,
  val titleBarStripeLight: Color,
  val titleBarStripeDark: Color,
  val titleBarText: Color,
  val divider: Color,
  val bottomLip: Color,
  val labelText: Color,
  val labelMuted: Color,
)

@Immutable
data class BevelPalette(
  val highlightOuter: Color,
  val highlightInner: Color,
  val shadowInner: Color,
  val shadowOuter: Color,
  val wellBorderDark: Color,
  val wellBorderLight: Color,
  val buttonFace: Color,
  val buttonFacePressed: Color,
  val buttonText: Color,
  val buttonTextActive: Color,
  val sliderThumbTop: Color,
  val sliderThumbFace: Color,
  val sliderThumbGroove: Color,
)

@Immutable
data class VfdPalette(
  val wellBackground: Color,
  val phosphorPrimary: Color,
  val phosphorBright: Color,
  val phosphorDim: Color,
  val segmentUnlit: Color,
  val ledOn: Color,
  val ledOff: Color,
  val ledAmber: Color,
  val eqCurveStroke: Color,
  val eqCurveFill: Color,
  val eqCurveZeroLine: Color,
)

/**
 * Exact 24-entry Winamp `viscolor.txt` palette mapping:
 * - [0]: Background
 * - [1]: Background dot grid
 * - [2..17]: 16-step spectrum gradient (top red -> orange -> yellow -> bottom green)
 * - [18..22]: 5 oscilloscope trace shades
 * - [23]: Spectrum peak dot cap
 */
@Immutable
data class VisualizerPalette(
  val colors: List<Color>,
) {
  init {
    require(colors.size == VISCOLOR_ENTRY_COUNT) {
      "Winamp viscolor palette requires exactly $VISCOLOR_ENTRY_COUNT entries."
    }
  }

  val background: Color get() = colors[0]
  val gridDot: Color get() = colors[1]
  val peakCap: Color get() = colors[23]
  val oscilloscopePrimary: Color get() = colors[18]
  val oscilloscopeSecondary: Color get() = colors[20]

  /** Maps normalized level [0f..1f] (0=bottom green, 1=top red) into viscolor indices 17..2. */
  fun spectrumColorForLevel(normalizedLevel: Float): Color {
    val clamped = normalizedLevel.coerceIn(0f, 1f)
    val index = (17 - (clamped * 15f).toInt()).coerceIn(2, 17)
    return colors[index]
  }

  companion object {
    const val VISCOLOR_ENTRY_COUNT = 24
  }
}

/** Exact Winamp `pledit.txt` semantic mapping plus Library breadcrumb cues. */
@Immutable
data class PlaylistPalette(
  val normalText: Color,
  val currentText: Color,
  val normalBackground: Color,
  val selectedBackground: Color,
  val selectedText: Color,
  val indexNumberText: Color,
  val folderAccentText: Color,
  val breadcrumbBackground: Color,
  val breadcrumbText: Color,
)

enum class TransportIconType {
  PREV,
  PLAY,
  PAUSE,
  STOP,
  NEXT,
  EJECT,
}

/** Optional bitmap sprite slots populated by `.wsz` Winamp 2.x skin archives (Milestone 5). */
@Immutable
data class SkinSprites(
  val transportNormal: Map<TransportIconType, ImageBitmap> = emptyMap(),
  val transportPressed: Map<TransportIconType, ImageBitmap> = emptyMap(),
  val vfdPlayIcon: ImageBitmap? = null,
  val vfdPauseIcon: ImageBitmap? = null,
  val vfdStopIcon: ImageBitmap? = null,
  val clockDigits: List<ImageBitmap> = emptyList(), // 0..9, optional blank(10), optional minus(11)
  val volumeTrackFrames: List<ImageBitmap> = emptyList(), // up to 28 horizontal frames
  val volumeThumb: ImageBitmap? = null,
  val balanceTrackFrames: List<ImageBitmap> = emptyList(), // up to 28 horizontal frames
  val balanceThumb: ImageBitmap? = null,
  val seekbarTrack: ImageBitmap? = null,
  val seekbarThumb: ImageBitmap? = null,
  val eqSliderFrames: List<ImageBitmap> = emptyList(), // up to 28 vertical frames
  val eqThumb: ImageBitmap? = null,
  val titleBarActive: ImageBitmap? = null,
  val eqTitleBarActive: ImageBitmap? = null,
  val shuffleOff: ImageBitmap? = null,
  val shuffleOn: ImageBitmap? = null,
  val repeatOff: ImageBitmap? = null,
  val repeatOn: ImageBitmap? = null,
  val eqButtonOff: ImageBitmap? = null,
  val eqButtonOn: ImageBitmap? = null,
  val plButtonOff: ImageBitmap? = null,
  val plButtonOn: ImageBitmap? = null,
  val eqOnOff: ImageBitmap? = null,
  val eqOnOn: ImageBitmap? = null,
  val eqAutoOff: ImageBitmap? = null,
  val eqAutoOn: ImageBitmap? = null,
  val eqPresetsNormal: ImageBitmap? = null,
  val eqPresetsPressed: ImageBitmap? = null,
  val monoActive: ImageBitmap? = null,
  val monoInactive: ImageBitmap? = null,
  val stereoActive: ImageBitmap? = null,
  val stereoInactive: ImageBitmap? = null,
)

@Immutable
data class SkinTypography(
  val titleBar: TextStyle,
  val microLabel: TextStyle,
  val buttonLabel: TextStyle,
  val vfdInfo: TextStyle,
  val trackRow: TextStyle,
  val emptyHeader: TextStyle,
  val emptyBody: TextStyle,
)

@Immutable
data class SkinMetrics(
  val bevelThickness: Dp = 1.5.dp,
  val panelPadding: Dp = 6.dp,
  val preampGap: Dp = 10.dp,
  val sheetSwipeBufferHeight: Dp = 12.dp,
  val chassisBottomLipHeight: Dp = 8.dp,
)

@Immutable
interface DroidAmpSkin {
  val id: String
  val displayName: String
  val chassis: ChassisPalette
  val bevel: BevelPalette
  val vfd: VfdPalette
  val visualizer: VisualizerPalette
  val playlist: PlaylistPalette
  val sprites: SkinSprites
  val typography: SkinTypography
  val metrics: SkinMetrics
}

val LocalDroidAmpSkin = staticCompositionLocalOf<DroidAmpSkin> { ClassicDroidAmpSkin }
