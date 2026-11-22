package com.droidamp.player.skin.wsz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.unit.dp
import com.droidamp.player.skin.SkinOrigin
import com.droidamp.player.skin.SkinRenderMode
import com.droidamp.player.skin.TransportIconType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WszSkinParserTest {
  @Test
  fun viscolorParserHandlesLeadingZerosCommentsAndCrlf() {
    val raw =
      "39,073,135 // color 0 = bg\r\n" +
        "0,034,099 // color 1 = dots\r\n" +
        "255,255,255 // color 2 = top\r\n"
    val palette = ViscolorParser.parse(raw)
    assertEquals(Color(39, 73, 135), palette.background)
    assertEquals(Color(0, 34, 99), palette.gridDot)
    assertEquals(Color(255, 255, 255), palette.colors[2])
    assertEquals(24, palette.colors.size)
  }

  @Test
  fun pleditParserRetainsExactOgHexColors() {
    val raw =
      "[Text]\r\n" +
        "Normal=#00ff00\r\n" +
        "Current=#ffff00\r\n" +
        "NormalBG=#008080\r\n" +
        "SelectedBG=#007070\r\n" +
        "mbFG=#ffff00\r\n" +
        "mbBG=#008080\r\n"
    val palette = PleditParser.parse(raw)
    assertEquals(Color(0xFF00FF00), palette.normalText)
    assertEquals(Color(0xFFFFFF00), palette.currentText)
    assertEquals(Color(0xFF008080), palette.normalBackground)
    assertEquals(Color(0xFF007070), palette.selectedBackground)
    assertEquals(Color(0xFF008080), palette.breadcrumbBackground)
    assertEquals(Color(0xFFFFFF00), palette.breadcrumbText)
  }

  @Test
  fun archiveParserFiltersAllowlistAndHandlesNestedCaseInsensitiveNames() {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zos ->
      zos.putNextEntry(ZipEntry("MySkin/Main.BMP"))
      zos.write(byteArrayOf(1, 2, 3))
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("MySkin/EQMAIN.BMP"))
      zos.write(byteArrayOf(4, 5, 6))
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("MySkin/vidamp.bmp"))
      zos.write(byteArrayOf(7, 8, 9))
      zos.closeEntry()
    }
    val bytes = bos.toByteArray()
    assertTrue(WszArchiveParser.hasZipMagic(bytes))
    val archive = WszArchiveParser.parse(bytes)
    assertNotNull(archive.bytes("main.bmp"))
    assertNotNull(archive.bytes("eqmain.bmp"))
    assertNull(archive.bytes("vidamp.bmp"))
  }

  @Test
  fun archiveParserRejectsZipSlipTraversalAndExtractsManifestAndSprites() {
    val zipBytes =
      buildTestZip(
        "../main.bmp" to byteArrayOf(1, 2, 3),
        "Nested/../../evil.bmp" to byteArrayOf(4, 5, 6),
        "sprites/../../escape.png" to byteArrayOf(7, 8, 9),
        "SkinFolder/droidamp.json" to "\uFEFF{\"schemaVersion\":1,\"name\":\"Safe Skin\"}".toByteArray(Charsets.UTF_8),
        "SkinFolder/Sprites/Header_Deck.PNG" to fakeImageTag(275, 22),
      )

    val archive = WszArchiveParser.parse(zipBytes)
    assertNull(archive.bytes("main.bmp"))
    assertNull(archive.bytes("evil.bmp"))
    assertNull(archive.spriteBytes("escape.png"))
    assertEquals(3, archive.rejectedEntries.size)
    assertEquals("{\"schemaVersion\":1,\"name\":\"Safe Skin\"}", archive.manifestJson())
    assertNotNull(archive.spriteBytes("sprites/header_deck.png"))
    assertNotNull(archive.spriteBytes("header_deck.png"))
  }

  @Test
  fun spriteCoordinatesHandleStandard28FramesAndMicrochip2UnexpectedDimensions() {
    // Standard Winamp 68x433 volume.bmp -> 28 frames + thumb fits
    val stdFrames = WszSpriteCoordinates.volumeOrBalanceFrameRects(68, 433)
    assertEquals(28, stdFrames.size)
    assertTrue(WszSpriteCoordinates.VOLUME_THUMB.fitsIn(68, 433))

    // Microchip_2.wsz 66x405 volume.bmp -> 27 frames, no thumb strip
    val microchipFrames = WszSpriteCoordinates.volumeOrBalanceFrameRects(66, 405)
    assertEquals(27, microchipFrames.size)
    assertEquals(66, microchipFrames.first().width)
    assertFalse(WszSpriteCoordinates.VOLUME_THUMB.fitsIn(66, 405))

    // Microchip_2.wsz 248x10 posbar.bmp -> track fits, thumb does not
    assertTrue(WszSpriteCoordinates.POSBAR_TRACK.fitsIn(248, 10))
    assertFalse(WszSpriteCoordinates.POSBAR_THUMB.fitsIn(248, 10))
  }

  @Test
  fun legacyWszVectorChromeVsClassicRawBmpAndPleditHeaders() {
    val loader = WszSkinLoader(decoder = FakeJvmSkinBitmapDecoder())
    val legacyZip =
      buildTestZip(
        "main.bmp" to fakeImageTag(275, 116, 0xFF283040.toInt()),
        "titlebar.bmp" to fakeImageTag(344, 87, 0xFF1E4078.toInt()),
        "eqmain.bmp" to fakeImageTag(275, 315, 0xFF1E4078.toInt()),
        "pledit.bmp" to fakeImageTag(280, 186, 0xFF1E4078.toInt()),
        "cbuttons.bmp" to fakeImageTag(136, 36, 0xFF3A4458.toInt()),
        "shufrep.bmp" to fakeImageTag(92, 85, 0xFF3A4458.toInt()),
        "pledit.txt" to "[Text]\r\nNormal=#38FF48\r\nCurrent=#FFFFFF\r\nNormalBG=#050806\r\nSelectedBG=#183870\r\n".toByteArray(),
      )

    // Default VECTOR_CHROME -> OG colors extracted, zero opaque BMP sprites
    val vectorSkin =
      loader.load(
        id = "legacy-test",
        displayName = "Legacy Test",
        zipBytes = legacyZip,
        legacyRenderMode = SkinRenderMode.VECTOR_CHROME,
      )
    assertEquals(SkinOrigin.LEGACY_WSZ, vectorSkin.origin)
    assertTrue(vectorSkin.sprites.transportNormal.isEmpty())
    assertNull(vectorSkin.sprites.shuffleOff)
    assertFalse(vectorSkin.sprites.hasCompletePanelHeaders)

    // Optional CLASSIC_RAW_BMP -> slices BMP controls + all 4 panel headers (including pledit strip)
    val rawBmpSkin =
      loader.load(
        id = "legacy-test",
        displayName = "Legacy Test",
        zipBytes = legacyZip,
        legacyRenderMode = SkinRenderMode.CLASSIC_RAW_BMP,
      )
    assertEquals(SkinOrigin.LEGACY_WSZ, rawBmpSkin.origin)
    assertEquals(6, rawBmpSkin.sprites.transportNormal.size)
    assertNotNull(rawBmpSkin.sprites.shuffleOff)
    assertTrue(rawBmpSkin.sprites.hasCompletePanelHeaders)
    assertEquals(203, rawBmpSkin.sprites.drawerHeaderCollapsedSprite?.width)
    assertEquals(14, rawBmpSkin.sprites.drawerHeaderCollapsedSprite?.height)
    assertEquals(275, rawBmpSkin.sprites.drawerHeaderExpandedSprite?.width)
    assertEquals(20, rawBmpSkin.sprites.drawerHeaderExpandedSprite?.height)
  }

  @Test
  fun augmentedWszAppliesManifestMobileSpritesAndAllOrNoneHeaderConsistency() {
    val loader = WszSkinLoader(decoder = FakeJvmSkinBitmapDecoder())
    val manifestJson =
      """
      {
        "schemaVersion": 1,
        "id": "amber-hifi-rack",
        "name": "Amber Hi-Fi Rack",
        "author": "DroidAmp Studio",
        "shapeStyle": { "cornerRadiusDp": 2.0, "bevelThicknessDp": 1.5, "doubleStepBevel": true },
        "palette": {
          "chassis": { "panelSurface": "#2C251E", "windowBackground": "#14100C" },
          "vfd": { "wellBackground": "#090603", "phosphorPrimary": "#FFB028", "phosphorBright": "#FFD878" }
        }
      }
      """.trimIndent()

    // Only 3 of 4 headers provided -> hasCompletePanelHeaders MUST be false (all-or-none ADR-028)
    val partialHeadersZip =
      buildTestZip(
        "droidamp.json" to manifestJson.toByteArray(Charsets.UTF_8),
        "cbuttons.bmp" to fakeImageTag(136, 36),
        "sprites/header_deck.png" to fakeImageTag(275, 22),
        "sprites/header_eq.png" to fakeImageTag(275, 22),
        "sprites/header_drawer_collapsed.png" to fakeImageTag(275, 22),
        "sprites/transport_play_normal.png" to fakeImageTag(48, 36),
        "sprites/mobile_tab_queue_on.png" to fakeImageTag(140, 30),
        "sprites/mobile_rem_normal.png" to fakeImageTag(96, 32),
        "sprites/mobile_row_play_normal.png" to fakeImageTag(32, 28),
      )

    val partialSkin = loader.load("fallback", "Fallback", partialHeadersZip)
    assertEquals(SkinOrigin.AUGMENTED_WSZ, partialSkin.origin)
    assertEquals("amber-hifi-rack", partialSkin.id)
    assertEquals("Amber Hi-Fi Rack", partialSkin.displayName)
    assertEquals("DroidAmp Studio", partialSkin.author)
    assertEquals(2.dp, partialSkin.shapeStyle.cornerRadius)
    assertEquals(Color(0xFF2C251E), partialSkin.chassis.panelSurface)
    // inheritLegacyBmpSprites defaults to false -> PREV from cbuttons.bmp is NOT sliced, PLAY from PNG IS loaded
    assertNull(partialSkin.sprites.transportNormal[TransportIconType.PREV])
    assertNotNull(partialSkin.sprites.transportNormal[TransportIconType.PLAY])
    assertNotNull(partialSkin.sprites.mobileTabQueueOn)
    assertNotNull(partialSkin.sprites.mobileRemNormal)
    assertNotNull(partialSkin.sprites.mobileRowPlayNormal)
    assertFalse(partialSkin.sprites.hasCompletePanelHeaders)

    // All 4 headers provided -> hasCompletePanelHeaders flips to true
    val completeHeadersZip =
      buildTestZip(
        "droidamp.json" to manifestJson.toByteArray(Charsets.UTF_8),
        "sprites/header_deck.png" to fakeImageTag(275, 22),
        "sprites/header_eq.png" to fakeImageTag(275, 22),
        "sprites/header_drawer_collapsed.png" to fakeImageTag(275, 22),
        "sprites/header_drawer_expanded.png" to fakeImageTag(275, 22),
      )
    val completeSkin = loader.load("fallback", "Fallback", completeHeadersZip)
    assertTrue(completeSkin.sprites.hasCompletePanelHeaders)
  }

  @Test
  fun contrastGuardBoostsLowContrastPalettesAndHonoursRawOverride() {
    val loader = WszSkinLoader(decoder = FakeJvmSkinBitmapDecoder())
    val lowContrastManifest =
      """
      {
        "schemaVersion": 1,
        "name": "Murky Stealth",
        "palette": {
          "chassis": { "panelSurface": "#202226", "labelText": "#282C32" },
          "vfd": { "wellBackground": "#101410", "phosphorPrimary": "#182818", "phosphorBright": "#1C2E1C" },
          "playlist": { "normalBackground": "#121216", "normalText": "#22222A", "currentText": "#252530" }
        }
      }
      """.trimIndent()
    val murkyZip = buildTestZip("droidamp.json" to lowContrastManifest.toByteArray(Charsets.UTF_8))

    val guarded = loader.load("murky", "Murky", murkyZip, contrastGuardEnabled = true)
    assertTrue(guarded.diagnostic.hasContrastAdjustments)
    assertTrue("vfd.phosphorPrimary" in guarded.diagnostic.contrastAdjustedTokens)
    assertTrue("playlist.normalText" in guarded.diagnostic.contrastAdjustedTokens)
    assertTrue(
      WszContrastGuard.contrastRatio(guarded.vfd.phosphorPrimary, guarded.vfd.wellBackground) >=
        WszContrastGuard.MIN_PRIMARY_READOUT_CONTRAST,
    )
    assertTrue(
      WszContrastGuard.contrastRatio(guarded.playlist.normalText, guarded.playlist.normalBackground) >=
        WszContrastGuard.MIN_PRIMARY_READOUT_CONTRAST,
    )
    assertTrue(
      WszContrastGuard.contrastRatio(guarded.chassis.labelText, guarded.chassis.panelSurface) >=
        WszContrastGuard.MIN_CHROME_CONTRAST,
    )

    val rawUnmodified = loader.load("murky", "Murky", murkyZip, contrastGuardEnabled = false)
    assertFalse(rawUnmodified.diagnostic.hasContrastAdjustments)
    assertEquals(Color(0xFF182818), rawUnmodified.vfd.phosphorPrimary)
    assertEquals(Color(0xFF22222A), rawUnmodified.playlist.normalText)
  }

  companion object {
    internal fun fakeImageTag(
      width: Int,
      height: Int,
      fillArgb: Int = 0xFF262D3B.toInt(),
    ): ByteArray =
      ByteBuffer.allocate(16)
        .putInt(0x46414B45) // "FAKE"
        .putInt(width)
        .putInt(height)
        .putInt(fillArgb)
        .array()

    internal fun buildTestZip(vararg entries: Pair<String, ByteArray>): ByteArray {
      val out = ByteArrayOutputStream()
      ZipOutputStream(out).use { zos ->
        for ((name, bytes) in entries) {
          zos.putNextEntry(ZipEntry(name))
          zos.write(bytes)
          zos.closeEntry()
        }
      }
      return out.toByteArray()
    }

    internal fun createProxyImageBitmap(width: Int, height: Int): ImageBitmap =
      Proxy.newProxyInstance(
        ImageBitmap::class.java.classLoader,
        arrayOf(ImageBitmap::class.java),
      ) { proxy, method, args ->
        when (method.name) {
          "getWidth" -> width
          "getHeight" -> height
          "getHasAlpha" -> true
          "equals" -> proxy === args?.getOrNull(0)
          "hashCode" -> System.identityHashCode(proxy)
          "toString" -> "FakeImageBitmap(${width}x${height})"
          else ->
            when {
              method.returnType == Void.TYPE -> null
              method.returnType == Int::class.javaPrimitiveType -> 0
              method.returnType == Boolean::class.javaPrimitiveType -> false
              ColorSpace::class.java.isAssignableFrom(method.returnType) -> ColorSpaces.Srgb
              else -> null
            }
        }
      } as ImageBitmap
  }
}

internal class FakeJvmSkinBitmapDecoder : SkinBitmapDecoder {
  private class FakeBitmap(
    override val width: Int,
    override val height: Int,
    private val fillArgb: Int,
  ) : DecodedSkinBitmap {
    override fun pixelArgb(x: Int, y: Int): Int = fillArgb
  }

  override fun decode(bytes: ByteArray): DecodedSkinBitmap? {
    if (bytes.size < 16) return null
    val buf = ByteBuffer.wrap(bytes)
    if (buf.int != 0x46414B45) return null
    val w = buf.int
    val h = buf.int
    val argb = buf.int
    if (w <= 0 || h <= 0) return null
    return FakeBitmap(w, h, argb)
  }

  override fun decodeFullImageBitmap(bytes: ByteArray): ImageBitmap? {
    val bmp = decode(bytes) ?: return null
    return WszSkinParserTest.createProxyImageBitmap(bmp.width, bmp.height)
  }

  override fun cropToImageBitmap(bitmap: DecodedSkinBitmap, rect: SpriteRect): ImageBitmap? {
    if (!rect.fitsIn(bitmap.width, bitmap.height)) return null
    return WszSkinParserTest.createProxyImageBitmap(rect.width, rect.height)
  }

  override fun assembleHorizontalStrip(
    bitmap: DecodedSkinBitmap,
    rects: List<SpriteRect>,
  ): ImageBitmap? {
    if (rects.isEmpty() || !rects.all { it.fitsIn(bitmap.width, bitmap.height) }) return null
    return WszSkinParserTest.createProxyImageBitmap(
      width = rects.sumOf { it.width },
      height = rects.maxOf { it.height },
    )
  }
}
