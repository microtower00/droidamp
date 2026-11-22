package com.droidamp.player.skin

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Origin classification of a loaded [DroidAmpSkin] (`ADR-028`). */
enum class SkinOrigin {
  BUILT_IN,
  AUGMENTED_WSZ,
  LEGACY_WSZ,
}

/**
 * Rendering mode for unmodified legacy 1999 `.wsz` archives (`ADR-028`):
 * - [VECTOR_CHROME] (default): Extracted OG colors + crisp vector chrome (zero 24-bit BMP seams).
 * - [CLASSIC_RAW_BMP]: Slices and renders raw `.wsz` BMP sprites with `FilterQuality.None`.
 */
enum class SkinRenderMode {
  VECTOR_CHROME,
  CLASSIC_RAW_BMP,
}

/** Optional geometric/bevel tokens customisable via `droidamp.json` (`ADR-028`). */
@Immutable
data class SkinShapeStyle(
  val cornerRadius: Dp = 0.dp,
  val bevelThickness: Dp = 1.5.dp,
  val doubleStepBevel: Boolean = true,
)

/**
 * Diagnostic metadata captured while loading a `.wsz` skin (`ADR-029` "Inform + Allow Overriding"):
 * - [missingOrCorruptAssets]: Archive entries or manifest sprite paths that failed or fell back.
 * - [contrastAdjustedTokens]: Palette token keys nudged by Auto-Contrast Guard for readability.
 * - [fallbackUsed]: True if partial fallback occurred so the UI can notify the user.
 */
@Immutable
data class SkinLoadDiagnostic(
  val missingOrCorruptAssets: List<String> = emptyList(),
  val contrastAdjustedTokens: List<String> = emptyList(),
  val fallbackUsed: Boolean = false,
) {
  val hasContrastAdjustments: Boolean get() = contrastAdjustedTokens.isNotEmpty()
}

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

/**
 * Optional bitmap & alpha-PNG sprite slots populated by Augmented `.wsz` (`sprites/<name>.png`)
 * or Legacy `.wsz` in [SkinRenderMode.CLASSIC_RAW_BMP] mode (`ADR-028`).
 *
 * Canonical Augmented `.wsz` paths under `sprites/`:
 * - Headers (all-or-none 3-panel + dual-state drawer):
 *   `header_deck.png`, `header_eq.png`, `header_drawer_collapsed.png`, `header_drawer_expanded.png`
 * - Transport:
 *   `transport_{prev|play|pause|stop|next|eject}_{normal|pressed}.png`
 * - Deck & EQ toggles:
 *   `shuffle_{off|on}.png`, `repeat_{off|on}.png`, `eq_btn_{off|on}.png`, `queue_btn_{off|on}.png`,
 *   `eq_on_{off|on}.png`, `eq_auto_{off|on}.png`, `eq_presets_{normal|pressed}.png`
 * - Mobile controls:
 *   `mobile_tab_queue_{off|on}.png`, `mobile_tab_library_{off|on}.png`,
 *   `mobile_{rem|misc|list}_{normal|pressed}.png`,
 *   `mobile_row_{play|enqueue}_{normal|pressed}.png`,
 *   `mobile_settings_gear_{normal|pressed}.png`
 */
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
  // Panel headers (Deck, EQ, and dual-state Queue/Library drawer)
  val titleBarActive: ImageBitmap? = null,
  val eqTitleBarActive: ImageBitmap? = null,
  val deckHeaderSprite: ImageBitmap? = null,
  val eqHeaderSprite: ImageBitmap? = null,
  val drawerHeaderCollapsedSprite: ImageBitmap? = null,
  val drawerHeaderExpandedSprite: ImageBitmap? = null,
  // Deck & EQ toggles
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
  // Optional alpha-PNG mobile controls (`ADR-028`)
  val mobileTabQueueOff: ImageBitmap? = null,
  val mobileTabQueueOn: ImageBitmap? = null,
  val mobileTabLibraryOff: ImageBitmap? = null,
  val mobileTabLibraryOn: ImageBitmap? = null,
  val mobileRemNormal: ImageBitmap? = null,
  val mobileRemPressed: ImageBitmap? = null,
  val mobileMiscNormal: ImageBitmap? = null,
  val mobileMiscPressed: ImageBitmap? = null,
  val mobileListNormal: ImageBitmap? = null,
  val mobileListPressed: ImageBitmap? = null,
  val mobileRowPlayNormal: ImageBitmap? = null,
  val mobileRowPlayPressed: ImageBitmap? = null,
  val mobileRowEnqueueNormal: ImageBitmap? = null,
  val mobileRowEnqueuePressed: ImageBitmap? = null,
  val mobileSettingsGearNormal: ImageBitmap? = null,
  val mobileSettingsGearPressed: ImageBitmap? = null,
) {
  val resolvedDeckHeader: ImageBitmap? get() = deckHeaderSprite ?: titleBarActive
  val resolvedEqHeader: ImageBitmap? get() = eqHeaderSprite ?: eqTitleBarActive

  /**
   * `ADR-028` All-or-None Header Consistency:
   * Panel header sprites are rendered if and only if all 3 panels (including both collapsed
   * and expanded Queue/Library drawer states) have valid sprites. Otherwise all 3 panels
   * fall back together to themed vector [com.droidamp.player.ui.components.ChassisTitleBar]s.
   */
  val hasCompletePanelHeaders: Boolean
    get() =
      resolvedDeckHeader != null &&
        resolvedEqHeader != null &&
        drawerHeaderCollapsedSprite != null &&
        drawerHeaderExpandedSprite != null
}

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
  val author: String get() = "DroidAmp"
  val origin: SkinOrigin get() = SkinOrigin.BUILT_IN
  val chassis: ChassisPalette
  val bevel: BevelPalette
  val vfd: VfdPalette
  val visualizer: VisualizerPalette
  val playlist: PlaylistPalette
  val sprites: SkinSprites
  val typography: SkinTypography
  val metrics: SkinMetrics
  val shapeStyle: SkinShapeStyle get() = SkinShapeStyle(bevelThickness = metrics.bevelThickness)
  val diagnostic: SkinLoadDiagnostic get() = SkinLoadDiagnostic()
}

val LocalDroidAmpSkin = staticCompositionLocalOf<DroidAmpSkin> { ClassicDroidAmpSkin }
