package com.droidamp.player.skin.wsz

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
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
}
