package com.droidamp.player.skin.wsz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.droidamp.player.skin.ClassicDroidAmpSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DroidAmpSkinManifestTest {
  @Test
  fun parsesCompleteValidManifestWithZeroWarnings() {
    val visEntries = (0 until 24).joinToString(",") { idx -> "\"#${idx.toString(16).padStart(2, '0')}A0FF\"" }
    val json =
      """
      {
        "schemaVersion": 1,
        "id": "cyber-ice-99",
        "name": "Cyber Ice '99",
        "author": "DroidAmp Studio",
        "inheritLegacyBmpSprites": false,
        "shapeStyle": {
          "cornerRadiusDp": 4.0,
          "bevelThicknessDp": 2.0,
          "doubleStepBevel": false
        },
        "palette": {
          "chassis": {
            "windowBackground": "#0B121E",
            "panelSurface": "#1B283E",
            "labelMuted": "#B88CD8FF"
          },
          "bevel": {
            "buttonFace": "#243652",
            "buttonTextActive": "#3CF0FF"
          },
          "vfd": {
            "wellBackground": "#040A12",
            "phosphorPrimary": "#3CF0FF",
            "eqCurveFill": "#333CF0FF"
          },
          "playlist": {
            "normalText": "#3CF0FF",
            "normalBackground": "#040A12"
          },
          "viscolor": [$visEntries]
        },
        "sprites": {
          "header_deck": "sprites/custom_deck_header.png",
          "mobile_tab_queue_on": "sprites/queue_active.png"
        }
      }
      """.trimIndent()

    val manifest = DroidAmpSkinManifestParser.parse(json)
    assertTrue("Expected zero warnings, got: ${manifest.manifestWarnings}", manifest.manifestWarnings.isEmpty())
    assertEquals(1, manifest.schemaVersion)
    assertEquals("cyber-ice-99", manifest.id)
    assertEquals("Cyber Ice '99", manifest.name)
    assertEquals("DroidAmp Studio", manifest.author)
    assertFalse(manifest.inheritLegacyBmpSprites)
    assertEquals(4.dp, manifest.shapeStyle.cornerRadius)
    assertEquals(2.dp, manifest.shapeStyle.bevelThickness)
    assertFalse(manifest.shapeStyle.doubleStepBevel)

    val chassis = manifest.applyChassis(ClassicDroidAmpSkin.chassis)
    assertEquals(Color(0xFF0B121E), chassis.windowBackground)
    assertEquals(Color(0xFF1B283E), chassis.panelSurface)
    assertEquals(Color(0xB88CD8FF), chassis.labelMuted)
    // Omitted fields preserve base palette
    assertEquals(ClassicDroidAmpSkin.chassis.titleBarText, chassis.titleBarText)

    val vfd = manifest.applyVfd(ClassicDroidAmpSkin.vfd)
    assertEquals(Color(0xFF040A12), vfd.wellBackground)
    assertEquals(Color(0xFF3CF0FF), vfd.phosphorPrimary)
    assertEquals(Color(0x333CF0FF), vfd.eqCurveFill)
    assertEquals(ClassicDroidAmpSkin.vfd.ledAmber, vfd.ledAmber)

    val vis = manifest.applyVisualizer(ClassicDroidAmpSkin.visualizer)
    assertEquals(Color(0xFF00A0FF), vis.colors[0])
    assertEquals(Color(0xFF17A0FF), vis.colors[23])
  }

  @Test
  fun resolvesCanonicalSpritePathsWhenOmittedFromSpritesMap() {
    val minimalJson = """{"schemaVersion": 1, "name": "Minimal Augmented"}"""
    val manifest = DroidAmpSkinManifestParser.parse(minimalJson)

    assertFalse(manifest.isSpriteExplicit(CanonicalSkinSpriteSlot.HEADER_DECK))
    assertEquals(
      "sprites/header_deck.png",
      manifest.resolvedSpritePath(CanonicalSkinSpriteSlot.HEADER_DECK),
    )
    assertEquals(
      "sprites/header_drawer_collapsed.png",
      manifest.resolvedSpritePath(CanonicalSkinSpriteSlot.HEADER_DRAWER_COLLAPSED),
    )
    assertEquals(
      "sprites/header_drawer_expanded.png",
      manifest.resolvedSpritePath(CanonicalSkinSpriteSlot.HEADER_DRAWER_EXPANDED),
    )
    assertEquals(
      "sprites/transport_play_normal.png",
      manifest.resolvedSpritePath(CanonicalSkinSpriteSlot.TRANSPORT_PLAY_NORMAL),
    )
    assertEquals(
      "sprites/mobile_tab_queue_off.png",
      manifest.resolvedSpritePath(CanonicalSkinSpriteSlot.MOBILE_TAB_QUEUE_OFF),
    )
    assertEquals(
      "sprites/mobile_tab_library_on.png",
      manifest.resolvedSpritePath(CanonicalSkinSpriteSlot.MOBILE_TAB_LIBRARY_ON),
    )
    assertEquals(
      "sprites/mobile_rem_normal.png",
      manifest.resolvedSpritePath(CanonicalSkinSpriteSlot.MOBILE_REM_NORMAL),
    )
    assertEquals(
      "sprites/mobile_misc_normal.png",
      manifest.resolvedSpritePath(CanonicalSkinSpriteSlot.MOBILE_MISC_NORMAL),
    )
    assertEquals(
      "sprites/mobile_list_normal.png",
      manifest.resolvedSpritePath(CanonicalSkinSpriteSlot.MOBILE_LIST_NORMAL),
    )
    assertEquals(
      "sprites/mobile_row_play_normal.png",
      manifest.resolvedSpritePath(CanonicalSkinSpriteSlot.MOBILE_ROW_PLAY_NORMAL),
    )
    assertEquals(
      "sprites/mobile_row_enqueue_normal.png",
      manifest.resolvedSpritePath(CanonicalSkinSpriteSlot.MOBILE_ROW_ENQUEUE_NORMAL),
    )
    assertEquals(
      "sprites/mobile_settings_gear_normal.png",
      manifest.resolvedSpritePath(CanonicalSkinSpriteSlot.MOBILE_SETTINGS_GEAR_NORMAL),
    )
  }

  @Test
  fun fallsBackCleanlyAndRecordsWarningsOnMalformedFieldsColorsAndPaths() {
    val jsonWithErrors =
      """
      {
        "schemaVersion": 99,
        "id": "   ",
        "inheritLegacyBmpSprites": "yes",
        "shapeStyle": {
          "cornerRadiusDp": -5,
          "bevelThicknessDp": "thick"
        },
        "palette": {
          "chassis": {
            "panelSurface": "#2C251E",
            "windowBackground": "not-a-hex",
            "unknownToken": "#112233"
          },
          "viscolor": ["#050806", "#ZZZZZZ"]
        },
        "sprites": {
          "header_deck": "../escape/header.png",
          "non_existent_slot": "sprites/foo.png"
        }
      }
      """.trimIndent()

    val manifest = DroidAmpSkinManifestParser.parse(jsonWithErrors)
    assertTrue(manifest.manifestWarnings.size >= 7)
    assertEquals(null, manifest.id)
    assertFalse(manifest.inheritLegacyBmpSprites)
    assertEquals(0.dp, manifest.shapeStyle.cornerRadius)
    assertEquals(1.5.dp, manifest.shapeStyle.bevelThickness)

    val chassis = manifest.applyChassis(ClassicDroidAmpSkin.chassis)
    // Valid color applied
    assertEquals(Color(0xFF2C251E), chassis.panelSurface)
    // Malformed color fell back cleanly
    assertEquals(ClassicDroidAmpSkin.chassis.windowBackground, chassis.windowBackground)

    val vis = manifest.applyVisualizer(ClassicDroidAmpSkin.visualizer)
    assertEquals(Color(0xFF050806), vis.colors[0])
    assertEquals(ClassicDroidAmpSkin.visualizer.colors[1], vis.colors[1])
    assertEquals(ClassicDroidAmpSkin.visualizer.colors[23], vis.colors[23])

    // Unsafe `..` sprite override was rejected and fell back to canonical path
    assertFalse(manifest.isSpriteExplicit(CanonicalSkinSpriteSlot.HEADER_DECK))
    assertEquals("sprites/header_deck.png", manifest.resolvedSpritePath(CanonicalSkinSpriteSlot.HEADER_DECK))

    // Syntax error in JSON never throws — returns default manifest + warning
    val broken = DroidAmpSkinManifestParser.parse("{ broken json")
    assertFalse(broken.manifestWarnings.isEmpty())
  }
}
