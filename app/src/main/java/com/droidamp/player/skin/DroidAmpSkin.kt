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

/** Optional bitmap sprite slots populated by future `.wsz` skin archives (Milestone 5). */
@Immutable
data class SkinSprites(
  val mainBackground: ImageBitmap? = null,
  val transportNormal: ImageBitmap? = null,
  val transportPressed: ImageBitmap? = null,
  val digitSheet: ImageBitmap? = null,
  val bitmapFontSheet: ImageBitmap? = null,
  val sliderThumb: ImageBitmap? = null,
  val playlistBorderTile: ImageBitmap? = null,
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
