package com.droidamp.player.skin.wsz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.droidamp.player.skin.BevelPalette
import com.droidamp.player.skin.ChassisPalette
import com.droidamp.player.skin.PlaylistPalette
import com.droidamp.player.skin.SkinShapeStyle
import com.droidamp.player.skin.VfdPalette
import com.droidamp.player.skin.VisualizerPalette
import java.util.Locale

/**
 * Canonical sprite slots supported in Augmented `.wsz` archives (`ADR-028`),
 * matching the exact `sprites/<slot>.png` paths documented in `DroidAmpSkin.kt`.
 */
enum class CanonicalSkinSpriteSlot(
  val canonicalKey: String,
  val aliases: Set<String> = emptySet(),
) {
  // 3-panel headers + dual-state Queue/Library drawer
  HEADER_DECK("header_deck", setOf("deckheadersprite", "deckheader", "titlebaractive", "titlebar_active")),
  HEADER_EQ("header_eq", setOf("eqheadersprite", "eqheader", "eqtitlebaractive", "eq_titlebar_active")),
  HEADER_DRAWER_COLLAPSED(
    "header_drawer_collapsed",
    setOf("drawerheadercollapsedsprite", "drawerheadercollapsed"),
  ),
  HEADER_DRAWER_EXPANDED(
    "header_drawer_expanded",
    setOf("drawerheaderexpandedsprite", "drawerheaderexpanded"),
  ),

  // Transport buttons (normal & pressed)
  TRANSPORT_PREV_NORMAL("transport_prev_normal", setOf("transportprevnormal")),
  TRANSPORT_PREV_PRESSED("transport_prev_pressed", setOf("transportprevpressed")),
  TRANSPORT_PLAY_NORMAL("transport_play_normal", setOf("transportplaynormal")),
  TRANSPORT_PLAY_PRESSED("transport_play_pressed", setOf("transportplaypressed")),
  TRANSPORT_PAUSE_NORMAL("transport_pause_normal", setOf("transportpausenormal")),
  TRANSPORT_PAUSE_PRESSED("transport_pause_pressed", setOf("transportpausepressed")),
  TRANSPORT_STOP_NORMAL("transport_stop_normal", setOf("transportstopnormal")),
  TRANSPORT_STOP_PRESSED("transport_stop_pressed", setOf("transportstoppressed")),
  TRANSPORT_NEXT_NORMAL("transport_next_normal", setOf("transportnextnormal")),
  TRANSPORT_NEXT_PRESSED("transport_next_pressed", setOf("transportnextpressed")),
  TRANSPORT_EJECT_NORMAL("transport_eject_normal", setOf("transportejectnormal")),
  TRANSPORT_EJECT_PRESSED("transport_eject_pressed", setOf("transportejectpressed")),

  // Deck & EQ toggles
  SHUFFLE_OFF("shuffle_off", setOf("shuffleoff")),
  SHUFFLE_ON("shuffle_on", setOf("shuffleon")),
  REPEAT_OFF("repeat_off", setOf("repeatoff")),
  REPEAT_ON("repeat_on", setOf("repeaton")),
  EQ_BTN_OFF("eq_btn_off", setOf("eqbuttonoff", "eqbtnoff")),
  EQ_BTN_ON("eq_btn_on", setOf("eqbuttonon", "eqbtnon")),
  QUEUE_BTN_OFF("queue_btn_off", setOf("plbuttonoff", "queuebuttonoff", "queuebtnoff", "pl_btn_off")),
  QUEUE_BTN_ON("queue_btn_on", setOf("plbuttonon", "queuebuttonon", "queuebtnon", "pl_btn_on")),
  EQ_ON_OFF("eq_on_off", setOf("eqonoff")),
  EQ_ON_ON("eq_on_on", setOf("eqonon")),
  EQ_AUTO_OFF("eq_auto_off", setOf("eqautooff")),
  EQ_AUTO_ON("eq_auto_on", setOf("eqautoon")),
  EQ_PRESETS_NORMAL("eq_presets_normal", setOf("eqpresetsnormal")),
  EQ_PRESETS_PRESSED("eq_presets_pressed", setOf("eqpresetspressed")),

  // Optional mobile control alpha-PNG sprites
  MOBILE_TAB_QUEUE_OFF("mobile_tab_queue_off", setOf("mobiletabqueueoff")),
  MOBILE_TAB_QUEUE_ON("mobile_tab_queue_on", setOf("mobiletabqueueon")),
  MOBILE_TAB_LIBRARY_OFF("mobile_tab_library_off", setOf("mobiletablibraryoff")),
  MOBILE_TAB_LIBRARY_ON("mobile_tab_library_on", setOf("mobiletablibraryon")),
  MOBILE_REM_NORMAL("mobile_rem_normal", setOf("mobileremnormal")),
  MOBILE_REM_PRESSED("mobile_rem_pressed", setOf("mobilerempressed")),
  MOBILE_MISC_NORMAL("mobile_misc_normal", setOf("mobilemiscnormal")),
  MOBILE_MISC_PRESSED("mobile_misc_pressed", setOf("mobilemiscpressed")),
  MOBILE_LIST_NORMAL("mobile_list_normal", setOf("mobilelistnormal")),
  MOBILE_LIST_PRESSED("mobile_list_pressed", setOf("mobilelistpressed")),
  MOBILE_ROW_PLAY_NORMAL("mobile_row_play_normal", setOf("mobilerowplaynormal")),
  MOBILE_ROW_PLAY_PRESSED("mobile_row_play_pressed", setOf("mobilerowplaypressed")),
  MOBILE_ROW_ENQUEUE_NORMAL("mobile_row_enqueue_normal", setOf("mobilerowenqueuenormal")),
  MOBILE_ROW_ENQUEUE_PRESSED("mobile_row_enqueue_pressed", setOf("mobilerowenqueuepressed")),
  MOBILE_SETTINGS_GEAR_NORMAL("mobile_settings_gear_normal", setOf("mobilesettingsgearnormal")),
  MOBILE_SETTINGS_GEAR_PRESSED("mobile_settings_gear_pressed", setOf("mobilesettingsgearpressed")),

  // Optional auxiliary deck/slider/VFD sprites (used by `wsz_augment.py --extract-sprites`)
  VFD_PLAY("vfd_play", setOf("vfdplayicon", "playpaus_play", "vfd_play_icon")),
  VFD_PAUSE("vfd_pause", setOf("vfdpauseicon", "playpaus_pause", "vfd_pause_icon")),
  VFD_STOP("vfd_stop", setOf("vfdstopicon", "playpaus_stop", "vfd_stop_icon")),
  MONO_ACTIVE("mono_active", setOf("monoactive")),
  MONO_INACTIVE("mono_inactive", setOf("monoinactive")),
  STEREO_ACTIVE("stereo_active", setOf("stereoactive")),
  STEREO_INACTIVE("stereo_inactive", setOf("stereoinactive")),
  SEEKBAR_TRACK("seekbar_track", setOf("seekbartrack", "posbar_track")),
  SEEKBAR_THUMB("seekbar_thumb", setOf("seekbarthumb", "posbar_thumb")),
  VOLUME_THUMB("volume_thumb", setOf("volumethumb")),
  BALANCE_THUMB("balance_thumb", setOf("balancethumb")),
  EQ_THUMB("eq_thumb", setOf("eqthumb")),
  ;

  val defaultArchivePath: String = "sprites/$canonicalKey.png"

  companion object {
    private val LOOKUP: Map<String, CanonicalSkinSpriteSlot> =
      buildMap {
        for (slot in CanonicalSkinSpriteSlot.entries) {
          put(slot.canonicalKey, slot)
          put("${slot.canonicalKey}.png", slot)
          put(slot.defaultArchivePath, slot)
          for (alias in slot.aliases) {
            put(alias.lowercase(Locale.ROOT), slot)
            put("${alias.lowercase(Locale.ROOT)}.png", slot)
            put("sprites/${alias.lowercase(Locale.ROOT)}.png", slot)
          }
        }
      }

    fun fromManifestKey(rawKey: String): CanonicalSkinSpriteSlot? {
      val normalized =
        rawKey
          .replace('\\', '/')
          .trim()
          .trim('/')
          .lowercase(Locale.ROOT)
      return LOOKUP[normalized]
    }
  }
}

data class ManifestPaletteOverrides(
  val chassis: Map<String, Color> = emptyMap(),
  val bevel: Map<String, Color> = emptyMap(),
  val vfd: Map<String, Color> = emptyMap(),
  val playlist: Map<String, Color> = emptyMap(),
  val viscolor: Map<Int, Color> = emptyMap(),
)

/**
 * Parsed representation of `droidamp.json` inside an Augmented `.wsz` archive (`ADR-028`).
 * Omitted or malformed fields fall back cleanly per-field and record human-readable warnings
 * in [manifestWarnings].
 */
data class DroidAmpSkinManifest(
  val schemaVersion: Int = SUPPORTED_SCHEMA_VERSION,
  val id: String? = null,
  val name: String? = null,
  val author: String? = null,
  val inheritLegacyBmpSprites: Boolean = false,
  val shapeStyle: SkinShapeStyle = SkinShapeStyle(),
  val paletteOverrides: ManifestPaletteOverrides = ManifestPaletteOverrides(),
  val explicitSprites: Map<CanonicalSkinSpriteSlot, String> = emptyMap(),
  val manifestWarnings: List<String> = emptyList(),
) {
  fun resolvedSpritePath(slot: CanonicalSkinSpriteSlot): String =
    explicitSprites[slot] ?: slot.defaultArchivePath

  fun isSpriteExplicit(slot: CanonicalSkinSpriteSlot): Boolean =
    explicitSprites.containsKey(slot)

  fun applyChassis(base: ChassisPalette): ChassisPalette {
    val c = paletteOverrides.chassis
    if (c.isEmpty()) return base
    return ChassisPalette(
      windowBackground = c["windowBackground"] ?: base.windowBackground,
      panelSurface = c["panelSurface"] ?: base.panelSurface,
      panelSurfaceAlt = c["panelSurfaceAlt"] ?: base.panelSurfaceAlt,
      titleBarActiveStart = c["titleBarActiveStart"] ?: base.titleBarActiveStart,
      titleBarActiveEnd = c["titleBarActiveEnd"] ?: base.titleBarActiveEnd,
      titleBarStripeLight = c["titleBarStripeLight"] ?: base.titleBarStripeLight,
      titleBarStripeDark = c["titleBarStripeDark"] ?: base.titleBarStripeDark,
      titleBarText = c["titleBarText"] ?: base.titleBarText,
      divider = c["divider"] ?: base.divider,
      bottomLip = c["bottomLip"] ?: base.bottomLip,
      labelText = c["labelText"] ?: base.labelText,
      labelMuted = c["labelMuted"] ?: base.labelMuted,
    )
  }

  fun applyBevel(base: BevelPalette): BevelPalette {
    val b = paletteOverrides.bevel
    if (b.isEmpty()) return base
    return BevelPalette(
      highlightOuter = b["highlightOuter"] ?: base.highlightOuter,
      highlightInner = b["highlightInner"] ?: base.highlightInner,
      shadowInner = b["shadowInner"] ?: base.shadowInner,
      shadowOuter = b["shadowOuter"] ?: base.shadowOuter,
      wellBorderDark = b["wellBorderDark"] ?: base.wellBorderDark,
      wellBorderLight = b["wellBorderLight"] ?: base.wellBorderLight,
      buttonFace = b["buttonFace"] ?: base.buttonFace,
      buttonFacePressed = b["buttonFacePressed"] ?: base.buttonFacePressed,
      buttonText = b["buttonText"] ?: base.buttonText,
      buttonTextActive = b["buttonTextActive"] ?: base.buttonTextActive,
      sliderThumbTop = b["sliderThumbTop"] ?: base.sliderThumbTop,
      sliderThumbFace = b["sliderThumbFace"] ?: base.sliderThumbFace,
      sliderThumbGroove = b["sliderThumbGroove"] ?: base.sliderThumbGroove,
    )
  }

  fun applyVfd(base: VfdPalette): VfdPalette {
    val v = paletteOverrides.vfd
    if (v.isEmpty()) return base
    return VfdPalette(
      wellBackground = v["wellBackground"] ?: base.wellBackground,
      phosphorPrimary = v["phosphorPrimary"] ?: base.phosphorPrimary,
      phosphorBright = v["phosphorBright"] ?: base.phosphorBright,
      phosphorDim = v["phosphorDim"] ?: base.phosphorDim,
      segmentUnlit = v["segmentUnlit"] ?: base.segmentUnlit,
      ledOn = v["ledOn"] ?: base.ledOn,
      ledOff = v["ledOff"] ?: base.ledOff,
      ledAmber = v["ledAmber"] ?: base.ledAmber,
      eqCurveStroke = v["eqCurveStroke"] ?: base.eqCurveStroke,
      eqCurveFill = v["eqCurveFill"] ?: base.eqCurveFill,
      eqCurveZeroLine = v["eqCurveZeroLine"] ?: base.eqCurveZeroLine,
    )
  }

  fun applyPlaylist(base: PlaylistPalette): PlaylistPalette {
    val p = paletteOverrides.playlist
    if (p.isEmpty()) return base
    return PlaylistPalette(
      normalText = p["normalText"] ?: base.normalText,
      currentText = p["currentText"] ?: base.currentText,
      normalBackground = p["normalBackground"] ?: base.normalBackground,
      selectedBackground = p["selectedBackground"] ?: base.selectedBackground,
      selectedText = p["selectedText"] ?: base.selectedText,
      indexNumberText = p["indexNumberText"] ?: base.indexNumberText,
      folderAccentText = p["folderAccentText"] ?: base.folderAccentText,
      breadcrumbBackground = p["breadcrumbBackground"] ?: base.breadcrumbBackground,
      breadcrumbText = p["breadcrumbText"] ?: base.breadcrumbText,
    )
  }

  fun applyVisualizer(base: VisualizerPalette): VisualizerPalette {
    val vis = paletteOverrides.viscolor
    if (vis.isEmpty()) return base
    return VisualizerPalette(
      colors =
        List(VisualizerPalette.VISCOLOR_ENTRY_COUNT) { index ->
          vis[index] ?: base.colors[index]
        },
    )
  }

  companion object {
    const val SUPPORTED_SCHEMA_VERSION = 1
  }
}

object DroidAmpSkinManifestParser {
  private val CHASSIS_KEYS =
    setOf(
      "windowBackground",
      "panelSurface",
      "panelSurfaceAlt",
      "titleBarActiveStart",
      "titleBarActiveEnd",
      "titleBarStripeLight",
      "titleBarStripeDark",
      "titleBarText",
      "divider",
      "bottomLip",
      "labelText",
      "labelMuted",
    )

  private val BEVEL_KEYS =
    setOf(
      "highlightOuter",
      "highlightInner",
      "shadowInner",
      "shadowOuter",
      "wellBorderDark",
      "wellBorderLight",
      "buttonFace",
      "buttonFacePressed",
      "buttonText",
      "buttonTextActive",
      "sliderThumbTop",
      "sliderThumbFace",
      "sliderThumbGroove",
    )

  private val VFD_KEYS =
    setOf(
      "wellBackground",
      "phosphorPrimary",
      "phosphorBright",
      "phosphorDim",
      "segmentUnlit",
      "ledOn",
      "ledOff",
      "ledAmber",
      "eqCurveStroke",
      "eqCurveFill",
      "eqCurveZeroLine",
    )

  private val PLAYLIST_KEYS =
    setOf(
      "normalText",
      "currentText",
      "normalBackground",
      "selectedBackground",
      "selectedText",
      "indexNumberText",
      "folderAccentText",
      "breadcrumbBackground",
      "breadcrumbText",
    )

  fun parse(rawJson: String): DroidAmpSkinManifest {
    val warnings = ArrayList<String>()
    val root =
      try {
        MinimalJson.parseObject(rawJson)
      } catch (e: JsonParseException) {
        warnings.add("Invalid droidamp.json syntax: ${e.message}")
        return DroidAmpSkinManifest(manifestWarnings = warnings)
      }

    val schemaVersion = parseSchemaVersion(root["schemaVersion"], warnings)
    val id = parseOptionalNonBlankString(root, "id", warnings)
    val name =
      if (root.containsKey("name")) {
        parseOptionalNonBlankString(root, "name", warnings)
      } else {
        parseOptionalNonBlankString(root, "displayName", warnings)
      }
    val author = parseOptionalNonBlankString(root, "author", warnings)
    val inheritLegacyBmpSprites =
      parseBooleanField(
        element = root["inheritLegacyBmpSprites"],
        fieldPath = "inheritLegacyBmpSprites",
        defaultValue = false,
        warnings = warnings,
      )

    val shapeStyle = parseShapeStyle(root["shapeStyle"], warnings)
    val paletteOverrides = parsePaletteOverrides(root["palette"], warnings)
    val explicitSprites = parseSpritesObject(root["sprites"], warnings)

    return DroidAmpSkinManifest(
      schemaVersion = schemaVersion,
      id = id,
      name = name,
      author = author,
      inheritLegacyBmpSprites = inheritLegacyBmpSprites,
      shapeStyle = shapeStyle,
      paletteOverrides = paletteOverrides,
      explicitSprites = explicitSprites,
      manifestWarnings = warnings,
    )
  }

  private fun parseSchemaVersion(
    element: JsonElement?,
    warnings: MutableList<String>,
  ): Int {
    if (element == null || element is JsonNull) {
      return DroidAmpSkinManifest.SUPPORTED_SCHEMA_VERSION
    }
    if (element is JsonNumber) {
      val ver = element.toStrictIntOrNull()
      if (ver != null) {
        if (ver != DroidAmpSkinManifest.SUPPORTED_SCHEMA_VERSION) {
          warnings.add(
            "Unsupported droidamp.json schemaVersion=$ver (expected ${DroidAmpSkinManifest.SUPPORTED_SCHEMA_VERSION}); using best-effort fallback.",
          )
        }
        return ver
      }
    }
    warnings.add("Malformed 'schemaVersion' in droidamp.json; defaulting to ${DroidAmpSkinManifest.SUPPORTED_SCHEMA_VERSION}.")
    return DroidAmpSkinManifest.SUPPORTED_SCHEMA_VERSION
  }

  private fun parseOptionalNonBlankString(
    obj: JsonObject,
    key: String,
    warnings: MutableList<String>,
  ): String? {
    val el = obj[key] ?: return null
    if (el is JsonNull) return null
    if (el is JsonString) {
      val trimmed = el.value.trim()
      if (trimmed.isEmpty()) {
        warnings.add("Field '$key' in droidamp.json is blank; falling back to default.")
        return null
      }
      return trimmed
    }
    warnings.add("Field '$key' in droidamp.json must be a string; falling back to default.")
    return null
  }

  private fun parseBooleanField(
    element: JsonElement?,
    fieldPath: String,
    defaultValue: Boolean,
    warnings: MutableList<String>,
  ): Boolean {
    if (element == null || element is JsonNull) return defaultValue
    if (element is JsonBoolean) return element.value
    warnings.add("Field '$fieldPath' in droidamp.json must be a boolean; defaulting to $defaultValue.")
    return defaultValue
  }

  private fun parseNonNegativeFloat(
    element: JsonElement?,
    fieldPath: String,
    defaultValue: Float,
    maxValue: Float,
    warnings: MutableList<String>,
  ): Float {
    if (element == null || element is JsonNull) return defaultValue
    if (element is JsonNumber) {
      val value = element.floatValue
      if (value.isFinite() && value >= 0f) {
        if (value > maxValue) {
          warnings.add("Field '$fieldPath' ($value) exceeds max $maxValue; clamping to $maxValue.")
          return maxValue
        }
        return value
      }
    }
    warnings.add("Field '$fieldPath' in droidamp.json must be a non-negative number; defaulting to $defaultValue.")
    return defaultValue
  }

  private fun parseShapeStyle(
    element: JsonElement?,
    warnings: MutableList<String>,
  ): SkinShapeStyle {
    if (element == null || element is JsonNull) return SkinShapeStyle()
    if (element !is JsonObject) {
      warnings.add("Field 'shapeStyle' in droidamp.json must be an object; falling back to default shape style.")
      return SkinShapeStyle()
    }
    val cornerRadiusDp =
      parseNonNegativeFloat(
        element = element["cornerRadiusDp"],
        fieldPath = "shapeStyle.cornerRadiusDp",
        defaultValue = 0f,
        maxValue = 32f,
        warnings = warnings,
      )
    val bevelThicknessDp =
      parseNonNegativeFloat(
        element = element["bevelThicknessDp"],
        fieldPath = "shapeStyle.bevelThicknessDp",
        defaultValue = 1.5f,
        maxValue = 8f,
        warnings = warnings,
      )
    val doubleStepBevel =
      parseBooleanField(
        element = element["doubleStepBevel"],
        fieldPath = "shapeStyle.doubleStepBevel",
        defaultValue = true,
        warnings = warnings,
      )
    return SkinShapeStyle(
      cornerRadius = cornerRadiusDp.dp,
      bevelThickness = bevelThicknessDp.dp,
      doubleStepBevel = doubleStepBevel,
    )
  }

  private fun parsePaletteOverrides(
    element: JsonElement?,
    warnings: MutableList<String>,
  ): ManifestPaletteOverrides {
    if (element == null || element is JsonNull) return ManifestPaletteOverrides()
    if (element !is JsonObject) {
      warnings.add("Field 'palette' in droidamp.json must be an object; ignoring palette overrides.")
      return ManifestPaletteOverrides()
    }

    val knownSections = setOf("chassis", "bevel", "vfd", "playlist", "viscolor")
    for (key in element.entries.keys) {
      if (key !in knownSections) {
        warnings.add("Unknown palette section 'palette.$key' in droidamp.json.")
      }
    }

    val chassis = parseColorMap(element["chassis"], "palette.chassis", CHASSIS_KEYS, warnings)
    val bevel = parseColorMap(element["bevel"], "palette.bevel", BEVEL_KEYS, warnings)
    val vfd = parseColorMap(element["vfd"], "palette.vfd", VFD_KEYS, warnings)
    val playlist = parseColorMap(element["playlist"], "palette.playlist", PLAYLIST_KEYS, warnings)
    val viscolor = parseViscolorArray(element["viscolor"], warnings)

    return ManifestPaletteOverrides(
      chassis = chassis,
      bevel = bevel,
      vfd = vfd,
      playlist = playlist,
      viscolor = viscolor,
    )
  }

  private fun parseColorMap(
    element: JsonElement?,
    sectionPath: String,
    allowedKeys: Set<String>,
    warnings: MutableList<String>,
  ): Map<String, Color> {
    if (element == null || element is JsonNull) return emptyMap()
    if (element !is JsonObject) {
      warnings.add("Section '$sectionPath' must be an object; falling back to defaults.")
      return emptyMap()
    }
    val out = LinkedHashMap<String, Color>()
    for ((key, rawVal) in element.entries) {
      if (key !in allowedKeys) {
        warnings.add("Unknown color token '$sectionPath.$key' in droidamp.json.")
        continue
      }
      if (rawVal is JsonNull) continue
      if (rawVal !is JsonString) {
        warnings.add("Malformed color '$sectionPath.$key' (expected #RRGGBB or #AARRGGBB string).")
        continue
      }
      val parsed = parseManifestHexColor(rawVal.value)
      if (parsed == null) {
        warnings.add("Malformed hex color '${rawVal.value}' at '$sectionPath.$key'; falling back.")
      } else {
        out[key] = parsed
      }
    }
    return out
  }

  private fun parseViscolorArray(
    element: JsonElement?,
    warnings: MutableList<String>,
  ): Map<Int, Color> {
    if (element == null || element is JsonNull) return emptyMap()
    if (element !is JsonArray) {
      warnings.add("Field 'palette.viscolor' must be a 24-entry array; falling back to defaults.")
      return emptyMap()
    }
    if (element.size != VisualizerPalette.VISCOLOR_ENTRY_COUNT) {
      warnings.add(
        "Field 'palette.viscolor' expected ${VisualizerPalette.VISCOLOR_ENTRY_COUNT} entries, got ${element.size}; missing entries fall back.",
      )
    }
    val out = LinkedHashMap<Int, Color>()
    val count = minOf(element.size, VisualizerPalette.VISCOLOR_ENTRY_COUNT)
    for (index in 0 until count) {
      val item = element[index]
      if (item is JsonNull) continue
      if (item !is JsonString) {
        warnings.add("Malformed color at 'palette.viscolor[$index]' (expected hex string).")
        continue
      }
      val parsed = parseManifestHexColor(item.value)
      if (parsed == null) {
        warnings.add("Malformed hex color '${item.value}' at 'palette.viscolor[$index]'; falling back.")
      } else {
        out[index] = parsed
      }
    }
    return out
  }

  private fun parseSpritesObject(
    element: JsonElement?,
    warnings: MutableList<String>,
  ): Map<CanonicalSkinSpriteSlot, String> {
    if (element == null || element is JsonNull) return emptyMap()
    if (element !is JsonObject) {
      warnings.add("Field 'sprites' in droidamp.json must be an object; ignoring explicit sprite map.")
      return emptyMap()
    }
    val out = LinkedHashMap<CanonicalSkinSpriteSlot, String>()
    for ((rawKey, rawVal) in element.entries) {
      val slot = CanonicalSkinSpriteSlot.fromManifestKey(rawKey)
      if (slot == null) {
        warnings.add("Unknown sprite slot '$rawKey' in droidamp.json 'sprites'.")
        continue
      }
      if (rawVal is JsonNull) continue
      if (rawVal !is JsonString || rawVal.value.isBlank()) {
        warnings.add("Sprite path for '$rawKey' in droidamp.json must be a non-empty string.")
        continue
      }
      val normalizedPath = rawVal.value.replace('\\', '/').trim().trimStart('/')
      if (!WszArchiveParser.isSafeEntryPath(normalizedPath)) {
        warnings.add("Rejected unsafe sprite path '${rawVal.value}' for '$rawKey'.")
        continue
      }
      out[slot] = normalizedPath
    }
    return out
  }

  internal fun parseManifestHexColor(raw: String): Color? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith('#')) return null
    val hex = trimmed.substring(1)
    return when (hex.length) {
      6 -> {
        val rgb = hex.toLongOrNull(16) ?: return null
        Color(0xFF000000L or (rgb and 0x00FFFFFFL))
      }
      8 -> {
        val argb = hex.toLongOrNull(16) ?: return null
        Color(argb and 0xFFFFFFFFL)
      }
      else -> null
    }
  }
}
