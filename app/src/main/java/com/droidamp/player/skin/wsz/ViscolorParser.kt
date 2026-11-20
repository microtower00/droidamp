package com.droidamp.player.skin.wsz

import androidx.compose.ui.graphics.Color
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.VisualizerPalette

/**
 * Parses classic Winamp `viscolor.txt` (24 RGB lines) while retaining exact OG skin colors
 * untouched. Handles `//` comments, leading-zero decimals (e.g. `39,073,135`), CRLF/LF,
 * and falls back per-index to [ClassicDroidAmpSkin.visualizer] if lines are omitted.
 */
object ViscolorParser {
  private val NUMBER_REGEX = Regex("\\d+")

  fun parse(
    content: String?,
    fallback: VisualizerPalette = ClassicDroidAmpSkin.visualizer,
  ): VisualizerPalette {
    if (content.isNullOrBlank()) return fallback
    val parsed = ArrayList<Color>(VisualizerPalette.VISCOLOR_ENTRY_COUNT)

    for (rawLine in content.lineSequence()) {
      if (parsed.size >= VisualizerPalette.VISCOLOR_ENTRY_COUNT) break
      val stripped = rawLine.substringBefore("//").substringBefore(";").trim()
      if (stripped.isEmpty()) continue
      val nums = NUMBER_REGEX.findAll(stripped).mapNotNull { it.value.toIntOrNull() }.take(3).toList()
      if (nums.size < 3) continue
      val r = nums[0].coerceIn(0, 255)
      val g = nums[1].coerceIn(0, 255)
      val b = nums[2].coerceIn(0, 255)
      parsed.add(Color(red = r, green = g, blue = b))
    }

    if (parsed.isEmpty()) return fallback
    while (parsed.size < VisualizerPalette.VISCOLOR_ENTRY_COUNT) {
      parsed.add(fallback.colors[parsed.size])
    }
    return VisualizerPalette(parsed)
  }
}
