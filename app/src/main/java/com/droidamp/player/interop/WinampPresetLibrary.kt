package com.droidamp.player.interop

import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import java.util.Locale

object WinampPresetLibrary {
  val builtInPresets: List<EqPreset>
    get() = ClassicWinampPresets.ALL

  fun findByName(name: String, extraPresets: List<EqPreset> = emptyList()): EqPreset? {
    val target = name.trim().lowercase(Locale.ROOT)
    if (target.isEmpty()) return null
    return extraPresets.firstOrNull { it.name.trim().lowercase(Locale.ROOT) == target }
      ?: builtInPresets.firstOrNull { it.name.trim().lowercase(Locale.ROOT) == target }
  }

  fun mergePresets(
    imported: List<EqPreset>,
    builtIn: List<EqPreset> = ClassicWinampPresets.ALL,
  ): List<EqPreset> {
    val merged = LinkedHashMap<String, EqPreset>()
    for (preset in builtIn) {
      val key = preset.name.trim().lowercase(Locale.ROOT)
      if (key.isNotEmpty()) {
        merged[key] = preset
      }
    }
    for (preset in imported) {
      val key = preset.name.trim().lowercase(Locale.ROOT)
      if (key.isNotEmpty()) {
        merged.putIfAbsent(key, preset)
      }
    }
    return merged.values.toList()
  }
}
