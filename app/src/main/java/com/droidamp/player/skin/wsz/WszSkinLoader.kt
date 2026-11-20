package com.droidamp.player.skin.wsz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.droidamp.player.skin.BevelPalette
import com.droidamp.player.skin.ChassisPalette
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.DroidAmpSkin
import com.droidamp.player.skin.PlaylistPalette
import com.droidamp.player.skin.SkinMetrics
import com.droidamp.player.skin.SkinSprites
import com.droidamp.player.skin.SkinTypography
import com.droidamp.player.skin.TransportIconType
import com.droidamp.player.skin.VfdPalette
import com.droidamp.player.skin.VisualizerPalette

@Immutable
data class WszDroidAmpSkin(
  override val id: String,
  override val displayName: String,
  override val chassis: ChassisPalette,
  override val bevel: BevelPalette,
  override val vfd: VfdPalette,
  override val visualizer: VisualizerPalette,
  override val playlist: PlaylistPalette,
  override val sprites: SkinSprites,
  override val typography: SkinTypography = ClassicDroidAmpSkin.typography,
  override val metrics: SkinMetrics = ClassicDroidAmpSkin.metrics,
) : DroidAmpSkin

interface SkinBitmapDecoder {
  fun decode(bytes: ByteArray): DecodedSkinBitmap?
  fun cropToImageBitmap(bitmap: DecodedSkinBitmap, rect: SpriteRect): ImageBitmap?
}

class AndroidSkinBitmapDecoder : SkinBitmapDecoder {
  private class AndroidDecodedBitmap(val bitmap: Bitmap) : DecodedSkinBitmap {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override fun pixelArgb(x: Int, y: Int): Int = bitmap.getPixel(x, y)
  }

  override fun decode(bytes: ByteArray): DecodedSkinBitmap? =
    runCatching {
      val opts =
        BitmapFactory.Options().apply {
          inScaled = false
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let(::AndroidDecodedBitmap)
    }.getOrNull()

  override fun cropToImageBitmap(bitmap: DecodedSkinBitmap, rect: SpriteRect): ImageBitmap? {
    val androidBmp = (bitmap as? AndroidDecodedBitmap)?.bitmap ?: return null
    if (!rect.fitsIn(androidBmp.width, androidBmp.height)) return null
    return runCatching {
      Bitmap.createBitmap(androidBmp, rect.x, rect.y, rect.width, rect.height).asImageBitmap()
    }.getOrNull()
  }
}

/**
 * Loads a classic Winamp 2.x `.wsz` archive into a complete Adaptive [DroidAmpSkin].
 * Every single BMP decode and sprite crop is bounds-checked and isolated via `runCatching`,
 * falling back gracefully to [ClassicDroidAmpSkin] on any missing or corrupt entry.
 */
class WszSkinLoader(
  private val decoder: SkinBitmapDecoder = AndroidSkinBitmapDecoder(),
) {
  fun load(
    id: String,
    displayName: String,
    zipBytes: ByteArray,
  ): DroidAmpSkin {
    val archive = WszArchiveParser.parse(zipBytes)
    return loadFromArchive(id = id, displayName = displayName, archive = archive)
  }

  fun loadFromArchive(
    id: String,
    displayName: String,
    archive: WszArchive,
  ): DroidAmpSkin {
    val visualizer = ViscolorParser.parse(archive.text("viscolor.txt"))
    val playlist = PleditParser.parse(archive.text("pledit.txt"))

    val mainBmp = archive.bytes("main.bmp")?.let(decoder::decode)
    val titleBarBmp = archive.bytes("titlebar.bmp")?.let(decoder::decode)
    val cbuttonsBmp = archive.bytes("cbuttons.bmp")?.let(decoder::decode)
    val playpausBmp = archive.bytes("playpaus.bmp")?.let(decoder::decode)
    val shufrepBmp = archive.bytes("shufrep.bmp")?.let(decoder::decode)
    val monosterBmp = archive.bytes("monoster.bmp")?.let(decoder::decode)
    val numsBmp = (archive.bytes("nums_ex.bmp") ?: archive.bytes("numbers.bmp"))?.let(decoder::decode)
    val volumeBmp = archive.bytes("volume.bmp")?.let(decoder::decode)
    val balanceBmp = (archive.bytes("balance.bmp") ?: archive.bytes("volume.bmp"))?.let(decoder::decode)
    val posbarBmp = archive.bytes("posbar.bmp")?.let(decoder::decode)
    val eqmainBmp = archive.bytes("eqmain.bmp")?.let(decoder::decode)

    val chassis = WszPaletteExtractor.extractChassisPalette(mainBmp, titleBarBmp, playlist)
    val bevel = WszPaletteExtractor.extractBevelPalette(chassis, playlist, cbuttonsBmp)
    val vfd = WszPaletteExtractor.extractVfdPalette(visualizer, playlist, numsBmp)

    val transportNormal = LinkedHashMap<TransportIconType, ImageBitmap>()
    val transportPressed = LinkedHashMap<TransportIconType, ImageBitmap>()
    if (cbuttonsBmp != null) {
      WszSpriteCoordinates.CBUTTONS_NORMAL.forEach { (type, rect) ->
        decoder.cropToImageBitmap(cbuttonsBmp, rect)?.let { transportNormal[type] = it }
      }
      WszSpriteCoordinates.CBUTTONS_PRESSED.forEach { (type, rect) ->
        decoder.cropToImageBitmap(cbuttonsBmp, rect)?.let { transportPressed[type] = it }
      }
    }

    val vfdPlay = playpausBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.PLAYPAUS_PLAY) }
    val vfdPause = playpausBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.PLAYPAUS_PAUSE) }
    val vfdStop = playpausBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.PLAYPAUS_STOP) }

    val clockDigits =
      if (numsBmp != null) {
        WszSpriteCoordinates.clockDigitRects(numsBmp.width, numsBmp.height)
          .mapNotNull { rect -> decoder.cropToImageBitmap(numsBmp, rect) }
      } else {
        emptyList()
      }

    val volumeFrames =
      if (volumeBmp != null) {
        WszSpriteCoordinates.volumeOrBalanceFrameRects(volumeBmp.width, volumeBmp.height)
          .mapNotNull { rect -> decoder.cropToImageBitmap(volumeBmp, rect) }
      } else {
        emptyList()
      }
    val volumeThumb = volumeBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.VOLUME_THUMB) }

    val balanceFrames =
      if (balanceBmp != null) {
        WszSpriteCoordinates.volumeOrBalanceFrameRects(
          bmpWidth = balanceBmp.width,
          bmpHeight = balanceBmp.height,
          isBalance = true,
        ).mapNotNull { rect -> decoder.cropToImageBitmap(balanceBmp, rect) }
      } else {
        emptyList()
      }
    val balanceThumb = balanceBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.BALANCE_THUMB) }

    val seekbarTrack = posbarBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.POSBAR_TRACK) }
    val seekbarThumb = posbarBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.POSBAR_THUMB) }

    val eqFrames =
      if (eqmainBmp != null) {
        WszSpriteCoordinates.eqSliderFrameRects(eqmainBmp.width, eqmainBmp.height)
          .mapNotNull { rect -> decoder.cropToImageBitmap(eqmainBmp, rect) }
      } else {
        emptyList()
      }
    val eqThumb = eqmainBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.EQ_THUMB) }

    val titleBarActive =
      titleBarBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.TITLEBAR_ACTIVE) }
    val eqTitleBarActive =
      eqmainBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.EQ_TITLEBAR_ACTIVE) }

    val sprites =
      SkinSprites(
        transportNormal = transportNormal,
        transportPressed = transportPressed,
        vfdPlayIcon = vfdPlay,
        vfdPauseIcon = vfdPause,
        vfdStopIcon = vfdStop,
        clockDigits = clockDigits,
        volumeTrackFrames = volumeFrames,
        volumeThumb = volumeThumb,
        balanceTrackFrames = balanceFrames,
        balanceThumb = balanceThumb,
        seekbarTrack = seekbarTrack,
        seekbarThumb = seekbarThumb,
        eqSliderFrames = eqFrames,
        eqThumb = eqThumb,
        titleBarActive = titleBarActive,
        eqTitleBarActive = eqTitleBarActive,
        shuffleOff = shufrepBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.SHUFFLE_OFF) },
        shuffleOn = shufrepBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.SHUFFLE_ON) },
        repeatOff = shufrepBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.REPEAT_OFF) },
        repeatOn = shufrepBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.REPEAT_ON) },
        eqButtonOff = shufrepBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.EQ_BTN_OFF) },
        eqButtonOn = shufrepBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.EQ_BTN_ON) },
        plButtonOff = shufrepBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.PL_BTN_OFF) },
        plButtonOn = shufrepBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.PL_BTN_ON) },
        eqOnOff = eqmainBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.EQ_ON_OFF) },
        eqOnOn = eqmainBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.EQ_ON_ON) },
        eqAutoOff = eqmainBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.EQ_AUTO_OFF) },
        eqAutoOn = eqmainBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.EQ_AUTO_ON) },
        eqPresetsNormal = eqmainBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.EQ_PRESETS_NORMAL) },
        eqPresetsPressed = eqmainBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.EQ_PRESETS_PRESSED) },
        monoActive = monosterBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.MONO_ACTIVE) },
        monoInactive = monosterBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.MONO_INACTIVE) },
        stereoActive = monosterBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.STEREO_ACTIVE) },
        stereoInactive = monosterBmp?.let { decoder.cropToImageBitmap(it, WszSpriteCoordinates.STEREO_INACTIVE) },
      )

    return WszDroidAmpSkin(
      id = id,
      displayName = displayName,
      chassis = chassis,
      bevel = bevel,
      vfd = vfd,
      visualizer = visualizer,
      playlist = playlist,
      sprites = sprites,
    )
  }
}
