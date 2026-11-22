package com.droidamp.player.skin.wsz

import androidx.compose.ui.graphics.Color
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.PlaylistPalette
import java.util.Locale

/**
 * Parses classic Winamp `pledit.txt` (`[Text]` INI section) retaining the exact OG skin colors
 * (`Normal`, `Current`, `NormalBG`, `SelectedBG`, `mbFG`, `mbBG`) without altering them.
 */
object PleditParser {
  fun parse(
    content: String?,
    fallback: PlaylistPalette = ClassicDroidAmpSkin.playlist,
  ): PlaylistPalette {
    if (content.isNullOrBlank()) return fallback
    val values = HashMap<String, Color>()

    for (rawLine in content.lineSequence()) {
      val line = rawLine.substringBefore(';').substringBefore("//").trim()
      if (line.isEmpty() || line.startsWith("[")) continue
      val eqIdx = line.indexOf('=')
      if (eqIdx <= 0) continue
      val key = line.substring(0, eqIdx).trim().lowercase(Locale.ROOT)
      val rawVal = line.substring(eqIdx + 1).trim()
      parseHexColor(rawVal)?.let { values[key] = it }
    }

    if (values.isEmpty()) return fallback

    val normal = values["normal"] ?: fallback.normalText
    val current = values["current"] ?: fallback.currentText
    val normalBg = values["normalbg"] ?: fallback.normalBackground
    val selectedBg = values["selectedbg"] ?: fallback.selectedBackground
    val mbBg = values["mbbg"] ?: normalBg
    val mbFg = values["mbfg"] ?: current

    return PlaylistPalette(
      normalText = normal,
      currentText = current,
      normalBackground = normalBg,
      selectedBackground = selectedBg,
      selectedText = current,
      indexNumberText = normal.copy(alpha = 0.72f),
      folderAccentText = current,
      breadcrumbBackground = mbBg,
      breadcrumbText = mbFg,
    )
  }

  internal fun parseHexColor(raw: String): Color? {
    val hex = raw.trim().removePrefix("#")
    if (hex.length != 6) return null
    val rgb = hex.toIntOrNull(16) ?: return null
    return Color(0xFF000000L or (rgb.toLong() and 0xFFFFFFL))
  }
}
