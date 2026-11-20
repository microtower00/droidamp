package com.droidamp.player.skin

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Built-in late-1990s hardware skeuomorphic skin ("DroidAmp Classic"):
 * industrial slate blues, gunmetal chassis, pitch-black recessed VFD wells,
 * phosphor green readouts, and yellow->orange->red spectrum & EQ bars.
 */
@Immutable
object ClassicDroidAmpSkin : DroidAmpSkin {
  override val id: String = "droidamp-classic"
  override val displayName: String = "DroidAmp Classic"

  override val chassis =
    ChassisPalette(
      windowBackground = Color(0xFF10141C),
      panelSurface = Color(0xFF262D3B),
      panelSurfaceAlt = Color(0xFF1E2430),
      titleBarActiveStart = Color(0xFF1A3A6E),
      titleBarActiveEnd = Color(0xFF2B528C),
      titleBarStripeLight = Color(0xFF8C9BB4),
      titleBarStripeDark = Color(0xFF121824),
      titleBarText = Color(0xFFE4ECF8),
      divider = Color(0xFF11151E),
      bottomLip = Color(0xFF1A202B),
      labelText = Color(0xFFC4CEDE),
      labelMuted = Color(0xFF8390A6),
    )

  override val bevel =
    BevelPalette(
      highlightOuter = Color(0xFF6B778D),
      highlightInner = Color(0xFF475266),
      shadowInner = Color(0xFF171C26),
      shadowOuter = Color(0xFF0A0D12),
      wellBorderDark = Color(0xFF07090D),
      wellBorderLight = Color(0xFF566278),
      buttonFace = Color(0xFF333C4D),
      buttonFacePressed = Color(0xFF1E2430),
      buttonText = Color(0xFFDCE4F2),
      buttonTextActive = Color(0xFF32F832),
      sliderThumbTop = Color(0xFF9EAAC0),
      sliderThumbFace = Color(0xFF5D697F),
      sliderThumbGroove = Color(0xFF1B202A),
    )

  override val vfd =
    VfdPalette(
      wellBackground = Color(0xFF050806),
      phosphorPrimary = Color(0xFF28F028),
      phosphorBright = Color(0xFF82FF82),
      phosphorDim = Color(0xFF1F9A28),
      segmentUnlit = Color(0xFF0C1E10),
      ledOn = Color(0xFF28F028),
      ledOff = Color(0xFF1A2920),
      ledAmber = Color(0xFFFFB020),
      eqCurveStroke = Color(0xFF38F858),
      eqCurveFill = Color(0x3328F028),
      eqCurveZeroLine = Color(0xFF1C3A24),
    )

  override val visualizer =
    VisualizerPalette(
      colors =
        listOf(
          Color(0xFF050806), // 0: background
          Color(0xFF142018), // 1: grid dots
          Color(0xFFF02014), // 2: peak red
          Color(0xFFF43814), // 3
          Color(0xFFF65014), // 4
          Color(0xFFF86814), // 5: orange-red
          Color(0xFFFA8214), // 6: orange
          Color(0xFFFC9C14), // 7
          Color(0xFFFDB814), // 8: amber
          Color(0xFFFED416), // 9: yellow-orange
          Color(0xFFF8EC1A), // 10: yellow
          Color(0xFFD8F01E), // 11: yellow-green
          Color(0xFFB0F020), // 12
          Color(0xFF84F022), // 13
          Color(0xFF58F024), // 14
          Color(0xFF38F026), // 15
          Color(0xFF28E428), // 16: phosphor green
          Color(0xFF1CC824), // 17: base green
          Color(0xFF38FF48), // 18: osc primary
          Color(0xFF28E038), // 19: osc 2
          Color(0xFF20B82E), // 20: osc 3
          Color(0xFF188C24), // 21: osc 4
          Color(0xFF105C18), // 22: osc 5
          Color(0xFFDCEEE0), // 23: peak cap dot
        ),
    )

  override val playlist =
    PlaylistPalette(
      normalText = Color(0xFF28E828),
      currentText = Color(0xFFFFFFFF),
      normalBackground = Color(0xFF050806),
      selectedBackground = Color(0xFF1A3A7A),
      selectedText = Color(0xFFFFFFFF),
      indexNumberText = Color(0xFF1A9624),
      folderAccentText = Color(0xFFF4C842),
      breadcrumbBackground = Color(0xFF0D141C),
      breadcrumbText = Color(0xFF8CD8FF),
    )

  override val sprites = SkinSprites()

  // Enforces minimum readability floor: ~9.5sp micro labels, 11-12sp buttons/track rows.
  override val typography =
    SkinTypography(
      titleBar =
        TextStyle(
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 10.5.sp,
          lineHeight = 12.sp,
          letterSpacing = 1.2.sp,
        ),
      microLabel =
        TextStyle(
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 9.5.sp,
          lineHeight = 11.sp,
          letterSpacing = 0.5.sp,
        ),
      buttonLabel =
        TextStyle(
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 11.sp,
          lineHeight = 13.sp,
          letterSpacing = 0.6.sp,
        ),
      vfdInfo =
        TextStyle(
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 10.5.sp,
          lineHeight = 13.sp,
          letterSpacing = 0.6.sp,
        ),
      trackRow =
        TextStyle(
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Medium,
          fontSize = 12.sp,
          lineHeight = 16.sp,
          letterSpacing = 0.2.sp,
        ),
      emptyHeader =
        TextStyle(
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          lineHeight = 17.sp,
          letterSpacing = 1.0.sp,
        ),
      emptyBody =
        TextStyle(
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Normal,
          fontSize = 11.sp,
          lineHeight = 15.sp,
          letterSpacing = 0.3.sp,
        ),
    )

  override val metrics = SkinMetrics()
}
