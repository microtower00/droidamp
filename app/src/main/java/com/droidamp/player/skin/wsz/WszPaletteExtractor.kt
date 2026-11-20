package com.droidamp.player.skin.wsz

import androidx.compose.ui.graphics.Color
import com.droidamp.player.skin.BevelPalette
import com.droidamp.player.skin.ChassisPalette
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.PlaylistPalette
import com.droidamp.player.skin.VfdPalette
import com.droidamp.player.skin.VisualizerPalette

/**
 * Pixel-readable bitmap abstraction decoupling pure-JVM `.wsz` palette & slicing logic
 * from Android's `android.graphics.Bitmap`.
 */
interface DecodedSkinBitmap {
  val width: Int
  val height: Int
  fun pixelArgb(x: Int, y: Int): Int
}

/**
 * Extracts `ChassisPalette`, `BevelPalette`, and `VfdPalette` directly from the OG `.wsz`
 * bitmaps (`main.bmp`, `titlebar.bmp`, `nums_ex.bmp`) + `pledit.txt` + `viscolor.txt`,
 * faithfully retaining the OG skin's original colors without artificially shifting them.
 */
object WszPaletteExtractor {
  private const val CHROMA_MAGENTA = 0x00FF00FF

  fun extractChassisPalette(
    mainBmp: DecodedSkinBitmap?,
    titleBarBmp: DecodedSkinBitmap?,
    playlist: PlaylistPalette,
    fallback: ChassisPalette = ClassicDroidAmpSkin.chassis,
  ): ChassisPalette {
    val panelSurface =
      samplePatchColor(mainBmp, x = 180, y = 45, w = 85, h = 22)
        ?: samplePatchColor(mainBmp, x = 8, y = 76, w = 60, h = 14)
        ?: fallback.panelSurface
    val panelSurfaceAlt = scaleRgb(panelSurface, 0.84f)
    val windowBg = scaleRgb(panelSurface, 0.62f)
    val titleStart =
      samplePatchColor(titleBarBmp, x = 35, y = 4, w = 50, h = 6)
        ?: scaleRgb(panelSurface, 0.90f)
    val titleEnd =
      samplePatchColor(titleBarBmp, x = 140, y = 4, w = 60, h = 6)
        ?: titleStart

    return ChassisPalette(
      windowBackground = windowBg,
      panelSurface = panelSurface,
      panelSurfaceAlt = panelSurfaceAlt,
      titleBarActiveStart = titleStart,
      titleBarActiveEnd = titleEnd,
      titleBarStripeLight = scaleRgb(titleStart, 1.35f),
      titleBarStripeDark = scaleRgb(titleStart, 0.55f),
      titleBarText = playlist.currentText,
      divider = scaleRgb(panelSurface, 0.45f),
      bottomLip = panelSurfaceAlt,
      labelText = playlist.currentText,
      labelMuted = playlist.normalText.copy(alpha = 0.72f),
    )
  }

  fun extractBevelPalette(
    chassis: ChassisPalette,
    playlist: PlaylistPalette,
    cbuttonsBmp: DecodedSkinBitmap?,
    fallback: BevelPalette = ClassicDroidAmpSkin.bevel,
  ): BevelPalette {
    val buttonFace =
      samplePatchColor(cbuttonsBmp, x = 28, y = 4, w = 12, h = 10)
        ?: scaleRgb(chassis.panelSurface, 1.15f)
    val buttonFacePressed =
      samplePatchColor(cbuttonsBmp, x = 28, y = 22, w = 12, h = 10)
        ?: scaleRgb(buttonFace, 0.78f)
    return BevelPalette(
      highlightOuter = scaleRgb(chassis.panelSurface, 1.55f),
      highlightInner = scaleRgb(chassis.panelSurface, 1.25f),
      shadowInner = scaleRgb(chassis.panelSurface, 0.62f),
      shadowOuter = scaleRgb(chassis.panelSurface, 0.32f),
      wellBorderDark = scaleRgb(chassis.panelSurface, 0.28f),
      wellBorderLight = scaleRgb(chassis.panelSurface, 1.38f),
      buttonFace = buttonFace,
      buttonFacePressed = buttonFacePressed,
      buttonText = playlist.currentText,
      buttonTextActive = playlist.normalText,
      sliderThumbTop = scaleRgb(buttonFace, 1.45f),
      sliderThumbFace = buttonFace,
      sliderThumbGroove = scaleRgb(buttonFace, 0.45f),
    )
  }

  fun extractVfdPalette(
    visualizer: VisualizerPalette,
    playlist: PlaylistPalette,
    numsBmp: DecodedSkinBitmap?,
    fallback: VfdPalette = ClassicDroidAmpSkin.vfd,
  ): VfdPalette {
    // Match `nums_ex.bmp` corner pixel if available so clock digits sit seamlessly in the VFD well
    val wellBg =
      sampleSinglePixel(numsBmp, x = 0, y = 0)
        ?: visualizer.background
    val primary = playlist.normalText
    val bright = playlist.currentText
    val dim = primary.copy(alpha = 0.65f)
    return VfdPalette(
      wellBackground = wellBg,
      phosphorPrimary = primary,
      phosphorBright = bright,
      phosphorDim = dim,
      segmentUnlit = scaleRgb(wellBg, 1.4f),
      ledOn = primary,
      ledOff = scaleRgb(wellBg, 1.35f),
      ledAmber = visualizer.colors.getOrElse(8) { fallback.ledAmber },
      eqCurveStroke = visualizer.oscilloscopePrimary,
      eqCurveFill = visualizer.oscilloscopePrimary.copy(alpha = 0.22f),
      eqCurveZeroLine = visualizer.gridDot,
    )
  }

  internal fun samplePatchColor(
    bmp: DecodedSkinBitmap?,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
  ): Color? {
    if (bmp == null || bmp.width <= 0 || bmp.height <= 0) return null
    val x0 = x.coerceIn(0, bmp.width - 1)
    val y0 = y.coerceIn(0, bmp.height - 1)
    val x1 = (x + w).coerceIn(x0 + 1, bmp.width)
    val y1 = (y + h).coerceIn(y0 + 1, bmp.height)

    var sumR = 0L
    var sumG = 0L
    var sumB = 0L
    var count = 0L
    val stepX = maxOf(1, (x1 - x0) / 12)
    val stepY = maxOf(1, (y1 - y0) / 8)
    var py = y0
    while (py < y1) {
      var px = x0
      while (px < x1) {
        val rgb = bmp.pixelArgb(px, py) and 0x00FFFFFF
        if (rgb != CHROMA_MAGENTA) {
          sumR += (rgb shr 16) and 0xFF
          sumG += (rgb shr 8) and 0xFF
          sumB += rgb and 0xFF
          count++
        }
        px += stepX
      }
      py += stepY
    }
    if (count == 0L) return null
    return Color(
      red = (sumR / count).toInt().coerceIn(0, 255),
      green = (sumG / count).toInt().coerceIn(0, 255),
      blue = (sumB / count).toInt().coerceIn(0, 255),
    )
  }

  private fun sampleSinglePixel(bmp: DecodedSkinBitmap?, x: Int, y: Int): Color? {
    if (bmp == null || x !in 0 until bmp.width || y !in 0 until bmp.height) return null
    val rgb = bmp.pixelArgb(x, y) and 0x00FFFFFF
    if (rgb == CHROMA_MAGENTA) return null
    return Color(0xFF000000L or rgb.toLong())
  }

  private fun scaleRgb(color: Color, factor: Float): Color =
    Color(
      red = (color.red * factor).coerceIn(0f, 1f),
      green = (color.green * factor).coerceIn(0f, 1f),
      blue = (color.blue * factor).coerceIn(0f, 1f),
      alpha = color.alpha,
    )
}
