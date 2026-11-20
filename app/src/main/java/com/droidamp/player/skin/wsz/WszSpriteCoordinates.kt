package com.droidamp.player.skin.wsz

import com.droidamp.player.skin.TransportIconType

data class SpriteRect(
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
) {
  fun fitsIn(bmpWidth: Int, bmpHeight: Int): Boolean =
    x >= 0 && y >= 0 && width > 0 && height > 0 &&
      (x + width) <= bmpWidth && (y + height) <= bmpHeight
}

/**
 * Authoritative Winamp 2.x sprite coordinate table with bounds safety.
 * Uses standard 28-frame counts when BMP dimensions match spec, falling back to dynamic
 * frame counts only when a skin provides an unexpected/shorter height (e.g. Microchip_2.wsz).
 */
object WszSpriteCoordinates {
  // cbuttons.bmp (136x36)
  val CBUTTONS_NORMAL: Map<TransportIconType, SpriteRect> =
    mapOf(
      TransportIconType.PREV to SpriteRect(0, 0, 23, 18),
      TransportIconType.PLAY to SpriteRect(23, 0, 23, 18),
      TransportIconType.PAUSE to SpriteRect(46, 0, 23, 18),
      TransportIconType.STOP to SpriteRect(69, 0, 23, 18),
      TransportIconType.NEXT to SpriteRect(92, 0, 22, 18),
      TransportIconType.EJECT to SpriteRect(114, 0, 22, 16),
    )

  val CBUTTONS_PRESSED: Map<TransportIconType, SpriteRect> =
    mapOf(
      TransportIconType.PREV to SpriteRect(0, 18, 23, 18),
      TransportIconType.PLAY to SpriteRect(23, 18, 23, 18),
      TransportIconType.PAUSE to SpriteRect(46, 18, 23, 18),
      TransportIconType.STOP to SpriteRect(69, 18, 23, 18),
      TransportIconType.NEXT to SpriteRect(92, 18, 22, 18),
      TransportIconType.EJECT to SpriteRect(114, 16, 22, 16),
    )

  // playpaus.bmp (42x9)
  val PLAYPAUS_PLAY = SpriteRect(0, 0, 9, 9)
  val PLAYPAUS_PAUSE = SpriteRect(9, 0, 9, 9)
  val PLAYPAUS_STOP = SpriteRect(18, 0, 9, 9)

  // titlebar.bmp (344x87) -> Active main titlebar strip
  val TITLEBAR_ACTIVE = SpriteRect(27, 0, 275, 14)

  // posbar.bmp (307x10 or 248x10 without thumb)
  val POSBAR_TRACK = SpriteRect(0, 0, 248, 10)
  val POSBAR_THUMB = SpriteRect(248, 0, 29, 10)

  // volume.bmp / balance.bmp thumb at y=422 (only present when height >= 433)
  val VOLUME_THUMB = SpriteRect(15, 422, 14, 11)
  val BALANCE_THUMB = SpriteRect(15, 422, 14, 11)

  // eqmain.bmp (275x315)
  val EQ_THUMB = SpriteRect(0, 164, 11, 11)

  const val STANDARD_SLIDER_FRAME_COUNT = 28
  private const val SLIDER_FRAME_STRIDE_Y = 15
  private const val SLIDER_FRAME_HEIGHT = 13

  fun clockDigitRects(bmpWidth: Int, bmpHeight: Int): List<SpriteRect> {
    if (bmpHeight < 13) return emptyList()
    val rects = ArrayList<SpriteRect>(12)
    // 0..9 (90px), optional blank (99px), optional minus (108px in nums_ex.bmp)
    val maxSlots = minOf(12, bmpWidth / 9)
    for (i in 0 until maxSlots) {
      rects.add(SpriteRect(i * 9, 0, 9, 13))
    }
    return rects
  }

  /**
   * Returns 28 standard frames when `bmpHeight >= 420`, or falls back to dynamic frame count
   * only when a skin provides an unexpected/shorter BMP height.
   */
  fun volumeOrBalanceFrameRects(bmpWidth: Int, bmpHeight: Int): List<SpriteRect> {
    if (bmpWidth <= 0 || bmpHeight < SLIDER_FRAME_HEIGHT) return emptyList()
    val frameW = minOf(68, bmpWidth)
    val frameCount =
      if (bmpHeight >= STANDARD_SLIDER_FRAME_COUNT * SLIDER_FRAME_STRIDE_Y) {
        STANDARD_SLIDER_FRAME_COUNT
      } else {
        // Unexpected shorter BMP height (e.g. Microchip_2.wsz 66x405 -> 27 frames)
        ((bmpHeight - SLIDER_FRAME_HEIGHT) / SLIDER_FRAME_STRIDE_Y + 1)
          .coerceIn(1, STANDARD_SLIDER_FRAME_COUNT)
      }
    return List(frameCount) { idx ->
      SpriteRect(0, idx * SLIDER_FRAME_STRIDE_Y, frameW, SLIDER_FRAME_HEIGHT)
    }.filter { it.fitsIn(bmpWidth, bmpHeight) }
  }

  fun eqSliderFrameRects(bmpWidth: Int, bmpHeight: Int): List<SpriteRect> {
    val rects = ArrayList<SpriteRect>(STANDARD_SLIDER_FRAME_COUNT)
    for (row in 0 until 2) {
      for (col in 0 until 14) {
        val rect = SpriteRect(13 + col * 15, 164 + row * 65, 14, 63)
        if (rect.fitsIn(bmpWidth, bmpHeight)) {
          rects.add(rect)
        }
      }
    }
    return rects
  }
}
