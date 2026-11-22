package com.droidamp.player.skin.wsz

import androidx.compose.ui.graphics.Color
import com.droidamp.player.skin.BevelPalette
import com.droidamp.player.skin.ChassisPalette
import com.droidamp.player.skin.PlaylistPalette
import com.droidamp.player.skin.VfdPalette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class ContrastGuardResult(
  val chassis: ChassisPalette,
  val bevel: BevelPalette,
  val vfd: VfdPalette,
  val playlist: PlaylistPalette,
  val adjustedTokens: List<String>,
)

/**
 * Pure-Kotlin WCAG 2.1 relative luminance & contrast ratio verifier + hue-preserving HSL lightness
 * adjuster (`ADR-029` "Inform + Allow Overriding").
 *
 * Ensures imported `.wsz` skins remain legible on phone displays while preserving original hue and
 * saturation, and records any nudged token names in [ContrastGuardResult.adjustedTokens] so the UI
 * can inform the user and offer a 1-tap "Use Raw OG Colors" override.
 */
object WszContrastGuard {
  const val MIN_CHROME_CONTRAST = 3.5f
  const val MIN_BUTTON_BREADCRUMB_CONTRAST = 3.8f
  const val MIN_PRIMARY_READOUT_CONTRAST = 4.5f

  // Small safety margin so 8-bit/float rounding never lands at e.g. 4.499:1
  private const val TARGET_SAFETY_EPSILON = 0.02f

  fun apply(
    chassis: ChassisPalette,
    bevel: BevelPalette,
    vfd: VfdPalette,
    playlist: PlaylistPalette,
    enabled: Boolean = true,
  ): ContrastGuardResult {
    if (!enabled) {
      return ContrastGuardResult(
        chassis = chassis,
        bevel = bevel,
        vfd = vfd,
        playlist = playlist,
        adjustedTokens = emptyList(),
      )
    }

    val adjustedTokens = ArrayList<String>()

    fun guardToken(
      tokenName: String,
      foreground: Color,
      background: Color,
      minRatio: Float,
    ): Color {
      if (contrastRatio(foreground, background) >= minRatio) {
        return foreground
      }
      adjustedTokens.add(tokenName)
      return ensureContrast(foreground, background, minRatio)
    }

    val titleBarAvg = averageColor(chassis.titleBarActiveStart, chassis.titleBarActiveEnd)
    val safeTitleBarText =
      guardToken(
        tokenName = "chassis.titleBarText",
        foreground = chassis.titleBarText,
        background = titleBarAvg,
        minRatio = MIN_CHROME_CONTRAST,
      )
    val safeLabelText =
      guardToken(
        tokenName = "chassis.labelText",
        foreground = chassis.labelText,
        background = chassis.panelSurface,
        minRatio = MIN_CHROME_CONTRAST,
      )

    val safeButtonText =
      guardToken(
        tokenName = "bevel.buttonText",
        foreground = bevel.buttonText,
        background = bevel.buttonFace,
        minRatio = MIN_BUTTON_BREADCRUMB_CONTRAST,
      )
    val safeButtonTextActive =
      guardToken(
        tokenName = "bevel.buttonTextActive",
        foreground = bevel.buttonTextActive,
        background = bevel.buttonFace,
        minRatio = MIN_BUTTON_BREADCRUMB_CONTRAST,
      )

    val safePhosphorPrimary =
      guardToken(
        tokenName = "vfd.phosphorPrimary",
        foreground = vfd.phosphorPrimary,
        background = vfd.wellBackground,
        minRatio = MIN_PRIMARY_READOUT_CONTRAST,
      )
    val safePhosphorBright =
      guardToken(
        tokenName = "vfd.phosphorBright",
        foreground = vfd.phosphorBright,
        background = vfd.wellBackground,
        minRatio = MIN_PRIMARY_READOUT_CONTRAST,
      )

    val safePlaylistNormal =
      guardToken(
        tokenName = "playlist.normalText",
        foreground = playlist.normalText,
        background = playlist.normalBackground,
        minRatio = MIN_PRIMARY_READOUT_CONTRAST,
      )
    val safePlaylistCurrent =
      guardToken(
        tokenName = "playlist.currentText",
        foreground = playlist.currentText,
        background = playlist.normalBackground,
        minRatio = MIN_PRIMARY_READOUT_CONTRAST,
      )
    val safePlaylistSelected =
      guardToken(
        tokenName = "playlist.selectedText",
        foreground = playlist.selectedText,
        background = playlist.selectedBackground,
        minRatio = MIN_PRIMARY_READOUT_CONTRAST,
      )
    val safeBreadcrumbText =
      guardToken(
        tokenName = "playlist.breadcrumbText",
        foreground = playlist.breadcrumbText,
        background = playlist.breadcrumbBackground,
        minRatio = MIN_BUTTON_BREADCRUMB_CONTRAST,
      )

    val updatedChassis =
      if (safeTitleBarText != chassis.titleBarText || safeLabelText != chassis.labelText) {
        chassis.copy(
          titleBarText = safeTitleBarText,
          labelText = safeLabelText,
        )
      } else {
        chassis
      }

    val updatedBevel =
      if (safeButtonText != bevel.buttonText || safeButtonTextActive != bevel.buttonTextActive) {
        bevel.copy(
          buttonText = safeButtonText,
          buttonTextActive = safeButtonTextActive,
        )
      } else {
        bevel
      }

    val updatedVfd =
      if (safePhosphorPrimary != vfd.phosphorPrimary || safePhosphorBright != vfd.phosphorBright) {
        vfd.copy(
          phosphorPrimary = safePhosphorPrimary,
          phosphorBright = safePhosphorBright,
        )
      } else {
        vfd
      }

    val updatedPlaylist =
      if (safePlaylistNormal != playlist.normalText ||
        safePlaylistCurrent != playlist.currentText ||
        safePlaylistSelected != playlist.selectedText ||
        safeBreadcrumbText != playlist.breadcrumbText
      ) {
        playlist.copy(
          normalText = safePlaylistNormal,
          currentText = safePlaylistCurrent,
          selectedText = safePlaylistSelected,
          breadcrumbText = safeBreadcrumbText,
        )
      } else {
        playlist
      }

    return ContrastGuardResult(
      chassis = updatedChassis,
      bevel = updatedBevel,
      vfd = updatedVfd,
      playlist = updatedPlaylist,
      adjustedTokens = adjustedTokens,
    )
  }

  /** WCAG 2.1 relative luminance in `[0f, 1f]` for an opaque sRGB color. */
  fun relativeLuminance(color: Color): Float {
    val r = linearizeChannel(color.red)
    val g = linearizeChannel(color.green)
    val b = linearizeChannel(color.blue)
    return (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
  }

  /**
   * WCAG 2.1 contrast ratio in `[1f, 21f]`, compositing [foreground] over [background]
   * if [foreground] is translucent.
   */
  fun contrastRatio(foreground: Color, background: Color): Float {
    val effectiveFg = compositeOver(foreground, background)
    val l1 = relativeLuminance(effectiveFg)
    val l2 = relativeLuminance(background)
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05f) / (darker + 0.05f)
  }

  /**
   * Nudges [foreground] lightness in HSL space (preserving hue & saturation) by the minimum amount
   * needed to satisfy [minRatio] against [background].
   */
  fun ensureContrast(
    foreground: Color,
    background: Color,
    minRatio: Float,
  ): Color {
    if (contrastRatio(foreground, background) >= minRatio) return foreground

    val compositedFg = compositeOver(foreground, background)
    val fgLum = relativeLuminance(compositedFg)
    val bgLum = relativeLuminance(background)

    val maxLightenRatio = 1.05f / (bgLum + 0.05f)
    val maxDarkenRatio = (bgLum + 0.05f) / 0.05f
    val target = minRatio + TARGET_SAFETY_EPSILON

    val shouldLighten =
      when {
        fgLum >= bgLum && maxLightenRatio >= minRatio -> true
        fgLum < bgLum && maxDarkenRatio >= minRatio -> false
        else -> maxLightenRatio >= maxDarkenRatio
      }

    val (hue, sat, curLightness) = rgbToHsl(compositedFg)
    val outAlpha = if (foreground.alpha >= 0.99f) foreground.alpha else 1f

    if (shouldLighten) {
      if (maxLightenRatio <= target) {
        return Color(1f, 1f, 1f, outAlpha)
      }
      var low = curLightness
      var high = 1f
      repeat(28) {
        val mid = (low + high) * 0.5f
        val candidate = hslToRgb(hue, sat, mid, outAlpha)
        if (contrastRatio(candidate, background) >= target) {
          high = mid
        } else {
          low = mid
        }
      }
      return hslToRgb(hue, sat, high, outAlpha)
    } else {
      if (maxDarkenRatio <= target) {
        return Color(0f, 0f, 0f, outAlpha)
      }
      var low = 0f
      var high = curLightness
      repeat(28) {
        val mid = (low + high) * 0.5f
        val candidate = hslToRgb(hue, sat, mid, outAlpha)
        if (contrastRatio(candidate, background) >= target) {
          low = mid
        } else {
          high = mid
        }
      }
      return hslToRgb(hue, sat, low, outAlpha)
    }
  }

  private fun linearizeChannel(srgb: Float): Float {
    val c = srgb.coerceIn(0f, 1f)
    return if (c <= 0.04045f) {
      c / 12.92f
    } else {
      ((c + 0.055) / 1.055).pow(2.4).toFloat()
    }
  }

  private fun compositeOver(fg: Color, bg: Color): Color {
    val a = fg.alpha.coerceIn(0f, 1f)
    if (a >= 0.999f) return fg
    val inv = 1f - a
    return Color(
      red = (fg.red * a + bg.red * inv).coerceIn(0f, 1f),
      green = (fg.green * a + bg.green * inv).coerceIn(0f, 1f),
      blue = (fg.blue * a + bg.blue * inv).coerceIn(0f, 1f),
      alpha = 1f,
    )
  }

  private fun averageColor(a: Color, b: Color): Color =
    Color(
      red = ((a.red + b.red) * 0.5f).coerceIn(0f, 1f),
      green = ((a.green + b.green) * 0.5f).coerceIn(0f, 1f),
      blue = ((a.blue + b.blue) * 0.5f).coerceIn(0f, 1f),
      alpha = 1f,
    )

  private data class Hsl(val h: Float, val s: Float, val l: Float)

  private fun rgbToHsl(color: Color): Hsl {
    val r = color.red.coerceIn(0f, 1f)
    val g = color.green.coerceIn(0f, 1f)
    val b = color.blue.coerceIn(0f, 1f)
    val maxC = maxOf(r, g, b)
    val minC = minOf(r, g, b)
    val delta = maxC - minC
    val l = (maxC + minC) * 0.5f
    if (delta < 1e-6f) {
      return Hsl(h = 0f, s = 0f, l = l)
    }
    val s = (delta / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
    val rawHue =
      when (maxC) {
        r -> ((g - b) / delta) % 6f
        g -> ((b - r) / delta) + 2f
        else -> ((r - g) / delta) + 4f
      }
    val h = ((rawHue * 60f) + 360f) % 360f
    return Hsl(h = h, s = s, l = l)
  }

  private fun hslToRgb(h: Float, s: Float, l: Float, alpha: Float = 1f): Color {
    val clampedL = l.coerceIn(0f, 1f)
    val clampedS = s.coerceIn(0f, 1f)
    if (clampedS < 1e-6f) {
      return Color(clampedL, clampedL, clampedL, alpha)
    }
    val c = (1f - abs(2f * clampedL - 1f)) * clampedS
    val hueSector = ((h % 360f + 360f) % 360f) / 60f
    val x = c * (1f - abs(hueSector % 2f - 1f))
    val (r1, g1, b1) =
      when {
        hueSector < 1f -> Triple(c, x, 0f)
        hueSector < 2f -> Triple(x, c, 0f)
        hueSector < 3f -> Triple(0f, c, x)
        hueSector < 4f -> Triple(0f, x, c)
        hueSector < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
      }
    val m = clampedL - c * 0.5f
    return Color(
      red = (r1 + m).coerceIn(0f, 1f),
      green = (g1 + m).coerceIn(0f, 1f),
      blue = (b1 + m).coerceIn(0f, 1f),
      alpha = alpha.coerceIn(0f, 1f),
    )
  }
}
