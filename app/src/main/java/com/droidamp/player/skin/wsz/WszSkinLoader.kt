package com.droidamp.player.skin.wsz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.droidamp.player.skin.BevelPalette
import com.droidamp.player.skin.ChassisPalette
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.DroidAmpSkin
import com.droidamp.player.skin.PlaylistPalette
import com.droidamp.player.skin.SkinLoadDiagnostic
import com.droidamp.player.skin.SkinMetrics
import com.droidamp.player.skin.SkinMiniPreviewPalette
import com.droidamp.player.skin.SkinOrigin
import com.droidamp.player.skin.SkinRenderMode
import com.droidamp.player.skin.SkinShapeStyle
import com.droidamp.player.skin.SkinSprites
import com.droidamp.player.skin.SkinTypography
import com.droidamp.player.skin.TransportIconType
import com.droidamp.player.skin.VfdPalette
import com.droidamp.player.skin.VisualizerPalette

@Immutable
data class WszDroidAmpSkin(
  override val id: String,
  override val displayName: String,
  override val author: String = "DroidAmp",
  override val origin: SkinOrigin = SkinOrigin.LEGACY_WSZ,
  override val chassis: ChassisPalette,
  override val bevel: BevelPalette,
  override val vfd: VfdPalette,
  override val visualizer: VisualizerPalette,
  override val playlist: PlaylistPalette,
  override val sprites: SkinSprites,
  override val typography: SkinTypography = ClassicDroidAmpSkin.typography,
  override val metrics: SkinMetrics = ClassicDroidAmpSkin.metrics,
  override val shapeStyle: SkinShapeStyle = SkinShapeStyle(bevelThickness = metrics.bevelThickness),
  override val diagnostic: SkinLoadDiagnostic = SkinLoadDiagnostic(),
) : DroidAmpSkin

interface SkinBitmapDecoder {
  fun decode(bytes: ByteArray): DecodedSkinBitmap?
  fun cropToImageBitmap(bitmap: DecodedSkinBitmap, rect: SpriteRect): ImageBitmap?

  fun decodeFullImageBitmap(bytes: ByteArray): ImageBitmap? =
    decode(bytes)?.let { bmp ->
      cropToImageBitmap(bmp, SpriteRect(0, 0, bmp.width, bmp.height))
    }

  fun assembleHorizontalStrip(bitmap: DecodedSkinBitmap, rects: List<SpriteRect>): ImageBitmap? {
    val first = rects.firstOrNull() ?: return null
    return cropToImageBitmap(bitmap, first)
  }
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

  override fun decodeFullImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching {
      val opts =
        BitmapFactory.Options().apply {
          inScaled = false
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()

  override fun cropToImageBitmap(bitmap: DecodedSkinBitmap, rect: SpriteRect): ImageBitmap? {
    val androidBmp = (bitmap as? AndroidDecodedBitmap)?.bitmap ?: return null
    if (!rect.fitsIn(androidBmp.width, androidBmp.height)) return null
    return runCatching {
      Bitmap.createBitmap(androidBmp, rect.x, rect.y, rect.width, rect.height).asImageBitmap()
    }.getOrNull()
  }

  override fun assembleHorizontalStrip(
    bitmap: DecodedSkinBitmap,
    rects: List<SpriteRect>,
  ): ImageBitmap? {
    val androidBmp = (bitmap as? AndroidDecodedBitmap)?.bitmap ?: return null
    if (rects.isEmpty() || !rects.all { it.fitsIn(androidBmp.width, androidBmp.height) }) {
      return null
    }
    val totalWidth = rects.sumOf { it.width }
    val maxHeight = rects.maxOf { it.height }
    if (totalWidth <= 0 || maxHeight <= 0) return null

    return runCatching {
      val assembled = Bitmap.createBitmap(totalWidth, maxHeight, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(assembled)
      var dstX = 0
      val srcRect = Rect()
      val dstRect = Rect()
      for (part in rects) {
        srcRect.set(part.x, part.y, part.x + part.width, part.y + part.height)
        dstRect.set(dstX, 0, dstX + part.width, part.height)
        canvas.drawBitmap(androidBmp, srcRect, dstRect, null)
        dstX += part.width
      }
      assembled.asImageBitmap()
    }.getOrNull()
  }
}

/**
 * Loads Augmented (`droidamp.json` + alpha-PNG `sprites/<name>.png`) and Legacy 1999 Winamp 2.x `.wsz`
 * archives into a complete [DroidAmpSkin] (`ADR-028`, `ADR-029`).
 */
class WszSkinLoader(
  private val decoder: SkinBitmapDecoder = AndroidSkinBitmapDecoder(),
) {
  fun load(
    id: String,
    displayName: String,
    zipBytes: ByteArray,
    legacyRenderMode: SkinRenderMode = SkinRenderMode.VECTOR_CHROME,
    contrastGuardEnabled: Boolean = true,
    preserveCallerId: Boolean = false,
  ): DroidAmpSkin {
    val archive = WszArchiveParser.parse(zipBytes)
    return loadFromArchive(
      id = id,
      displayName = displayName,
      archive = archive,
      legacyRenderMode = legacyRenderMode,
      contrastGuardEnabled = contrastGuardEnabled,
      preserveCallerId = preserveCallerId,
    )
  }

  /**
   * Extracts lightweight card preview colors for `SkinBrowserScreen` (`ADR-029`)
   * using the skin's `.txt` / `droidamp.json` tokens and primary `.bmp` surfaces.
   */
  fun extractPreviewPalette(archive: WszArchive): SkinMiniPreviewPalette {
    val baseVis = ViscolorParser.parse(archive.text("viscolor.txt"))
    val basePl = PleditParser.parse(archive.text("pledit.txt"))
    val issues = ArrayList<String>()
    val mainBmp = decodeArchiveBmp(archive, "main.bmp", issues)
    val titleBarBmp = decodeArchiveBmp(archive, "titlebar.bmp", issues)
    val numsBmp =
      decodeArchiveBmp(archive, "nums_ex.bmp", issues)
        ?: decodeArchiveBmp(archive, "numbers.bmp", issues)

    var chassis = WszPaletteExtractor.extractChassisPalette(mainBmp, titleBarBmp, basePl)
    var vfd = WszPaletteExtractor.extractVfdPalette(baseVis, basePl, numsBmp)
    val manifestJson = archive.manifestJson()
    if (manifestJson != null) {
      val manifest = DroidAmpSkinManifestParser.parse(manifestJson)
      val pl = manifest.applyPlaylist(basePl)
      chassis = manifest.applyChassis(chassis)
      vfd = manifest.applyVfd(vfd)
      return SkinMiniPreviewPalette(
        panelBg = chassis.panelSurface,
        titleBarStart = chassis.titleBarActiveStart,
        titleBarEnd = chassis.titleBarActiveEnd,
        vfdBg = vfd.wellBackground,
        vfdPhosphor = vfd.phosphorPrimary,
        accent = pl.currentText,
      )
    }
    return SkinMiniPreviewPalette(
      panelBg = chassis.panelSurface,
      titleBarStart = chassis.titleBarActiveStart,
      titleBarEnd = chassis.titleBarActiveEnd,
      vfdBg = vfd.wellBackground,
      vfdPhosphor = vfd.phosphorPrimary,
      accent = vfd.ledAmber,
    )
  }

  fun loadFromArchive(
    id: String,
    displayName: String,
    archive: WszArchive,
    legacyRenderMode: SkinRenderMode = SkinRenderMode.VECTOR_CHROME,
    contrastGuardEnabled: Boolean = true,
    preserveCallerId: Boolean = false,
  ): DroidAmpSkin {
    val diagnosticIssues = ArrayList<String>()
    for (rejected in archive.rejectedEntries) {
      diagnosticIssues.add("Rejected unsafe or oversized archive entry: $rejected")
    }

    val baseVisualizer = ViscolorParser.parse(archive.text("viscolor.txt"))
    val basePlaylist = PleditParser.parse(archive.text("pledit.txt"))

    val mainBmp = decodeArchiveBmp(archive, "main.bmp", diagnosticIssues)
    val titleBarBmp = decodeArchiveBmp(archive, "titlebar.bmp", diagnosticIssues)
    val cbuttonsBmp = decodeArchiveBmp(archive, "cbuttons.bmp", diagnosticIssues)
    val numsBmp =
      decodeArchiveBmp(archive, "nums_ex.bmp", diagnosticIssues)
        ?: decodeArchiveBmp(archive, "numbers.bmp", diagnosticIssues)

    val extractedChassis = WszPaletteExtractor.extractChassisPalette(mainBmp, titleBarBmp, basePlaylist)
    val extractedBevel = WszPaletteExtractor.extractBevelPalette(extractedChassis, basePlaylist, cbuttonsBmp)
    val extractedVfd = WszPaletteExtractor.extractVfdPalette(baseVisualizer, basePlaylist, numsBmp)

    val manifestJson = archive.manifestJson()
    if (manifestJson != null) {
      val manifest = DroidAmpSkinManifestParser.parse(manifestJson)
      diagnosticIssues.addAll(manifest.manifestWarnings)

      val rawVisualizer = manifest.applyVisualizer(baseVisualizer)
      val rawPlaylist = manifest.applyPlaylist(basePlaylist)
      val rawChassis = manifest.applyChassis(extractedChassis)
      val rawBevel = manifest.applyBevel(extractedBevel)
      val rawVfd = manifest.applyVfd(extractedVfd)

      val baseSprites =
        if (manifest.inheritLegacyBmpSprites) {
          sliceLegacyBmpSprites(
            archive = archive,
            mainTitleBarBmp = titleBarBmp,
            cbuttonsBmp = cbuttonsBmp,
            numsBmp = numsBmp,
            diagnosticIssues = diagnosticIssues,
            requireDrawerHeaders = false,
          )
        } else {
          SkinSprites()
        }

      val mergedSprites =
        overlayManifestPngSprites(
          archive = archive,
          manifest = manifest,
          base = baseSprites,
          diagnosticIssues = diagnosticIssues,
        )

      val contrastResult =
        WszContrastGuard.apply(
          chassis = rawChassis,
          bevel = rawBevel,
          vfd = rawVfd,
          playlist = rawPlaylist,
          enabled = contrastGuardEnabled,
        )

      val shapeStyle = manifest.shapeStyle
      val metrics = ClassicDroidAmpSkin.metrics.copy(bevelThickness = shapeStyle.bevelThickness)
      val resolvedId = if (preserveCallerId) id else (manifest.id ?: id)
      val resolvedName = manifest.name ?: displayName
      val resolvedAuthor = manifest.author ?: "DroidAmp"

      return WszDroidAmpSkin(
        id = resolvedId,
        displayName = resolvedName,
        author = resolvedAuthor,
        origin = SkinOrigin.AUGMENTED_WSZ,
        chassis = contrastResult.chassis,
        bevel = contrastResult.bevel,
        vfd = contrastResult.vfd,
        visualizer = rawVisualizer,
        playlist = contrastResult.playlist,
        sprites = mergedSprites,
        metrics = metrics,
        shapeStyle = shapeStyle,
        diagnostic =
          SkinLoadDiagnostic(
            missingOrCorruptAssets = diagnosticIssues,
            contrastAdjustedTokens = contrastResult.adjustedTokens,
            fallbackUsed = diagnosticIssues.isNotEmpty(),
          ),
      )
    }

    // Legacy 1999 `.wsz` path (no `droidamp.json`)
    val sprites =
      when (legacyRenderMode) {
        SkinRenderMode.VECTOR_CHROME -> SkinSprites()
        SkinRenderMode.CLASSIC_RAW_BMP ->
          sliceLegacyBmpSprites(
            archive = archive,
            mainTitleBarBmp = titleBarBmp,
            cbuttonsBmp = cbuttonsBmp,
            numsBmp = numsBmp,
            diagnosticIssues = diagnosticIssues,
            requireDrawerHeaders = true,
          )
      }

    val contrastResult =
      WszContrastGuard.apply(
        chassis = extractedChassis,
        bevel = extractedBevel,
        vfd = extractedVfd,
        playlist = basePlaylist,
        enabled = contrastGuardEnabled,
      )

    return WszDroidAmpSkin(
      id = id,
      displayName = displayName,
      author = "DroidAmp",
      origin = SkinOrigin.LEGACY_WSZ,
      chassis = contrastResult.chassis,
      bevel = contrastResult.bevel,
      vfd = contrastResult.vfd,
      visualizer = baseVisualizer,
      playlist = contrastResult.playlist,
      sprites = sprites,
      diagnostic =
        SkinLoadDiagnostic(
          missingOrCorruptAssets = diagnosticIssues,
          contrastAdjustedTokens = contrastResult.adjustedTokens,
          fallbackUsed = diagnosticIssues.isNotEmpty(),
        ),
    )
  }

  private fun decodeArchiveBmp(
    archive: WszArchive,
    fileName: String,
    diagnosticIssues: MutableList<String>,
  ): DecodedSkinBitmap? {
    val bytes = archive.bytes(fileName) ?: return null
    val decoded = runCatching { decoder.decode(bytes) }.getOrNull()
    if (decoded == null) {
      diagnosticIssues.add("Corrupt bitmap in archive: $fileName")
    }
    return decoded
  }

  private fun sliceLegacyBmpSprites(
    archive: WszArchive,
    mainTitleBarBmp: DecodedSkinBitmap?,
    cbuttonsBmp: DecodedSkinBitmap?,
    numsBmp: DecodedSkinBitmap?,
    diagnosticIssues: MutableList<String>,
    requireDrawerHeaders: Boolean,
  ): SkinSprites {
    val playpausBmp = decodeArchiveBmp(archive, "playpaus.bmp", diagnosticIssues)
    val shufrepBmp = decodeArchiveBmp(archive, "shufrep.bmp", diagnosticIssues)
    val monosterBmp = decodeArchiveBmp(archive, "monoster.bmp", diagnosticIssues)
    val volumeBmp = decodeArchiveBmp(archive, "volume.bmp", diagnosticIssues)
    val balanceBmp = decodeArchiveBmp(archive, "balance.bmp", diagnosticIssues) ?: volumeBmp
    val posbarBmp = decodeArchiveBmp(archive, "posbar.bmp", diagnosticIssues)
    val eqmainBmp = decodeArchiveBmp(archive, "eqmain.bmp", diagnosticIssues)
    val pleditBmp = decodeArchiveBmp(archive, "pledit.bmp", diagnosticIssues)

    val transportNormal = LinkedHashMap<TransportIconType, ImageBitmap>()
    val transportPressed = LinkedHashMap<TransportIconType, ImageBitmap>()
    if (cbuttonsBmp != null) {
      WszSpriteCoordinates.CBUTTONS_NORMAL.forEach { (type, rect) ->
        safeCrop(cbuttonsBmp, rect)?.let { transportNormal[type] = it }
      }
      WszSpriteCoordinates.CBUTTONS_PRESSED.forEach { (type, rect) ->
        safeCrop(cbuttonsBmp, rect)?.let { transportPressed[type] = it }
      }
    }

    val vfdPlay = playpausBmp?.let { safeCrop(it, WszSpriteCoordinates.PLAYPAUS_PLAY) }
    val vfdPause = playpausBmp?.let { safeCrop(it, WszSpriteCoordinates.PLAYPAUS_PAUSE) }
    val vfdStop = playpausBmp?.let { safeCrop(it, WszSpriteCoordinates.PLAYPAUS_STOP) }

    val clockDigits =
      if (numsBmp != null) {
        WszSpriteCoordinates.clockDigitRects(numsBmp.width, numsBmp.height)
          .mapNotNull { rect -> safeCrop(numsBmp, rect) }
      } else {
        emptyList()
      }

    val volumeFrames =
      if (volumeBmp != null) {
        WszSpriteCoordinates.volumeOrBalanceFrameRects(volumeBmp.width, volumeBmp.height)
          .mapNotNull { rect -> safeCrop(volumeBmp, rect) }
      } else {
        emptyList()
      }
    val volumeThumb = volumeBmp?.let { safeCrop(it, WszSpriteCoordinates.VOLUME_THUMB) }

    val balanceFrames =
      if (balanceBmp != null) {
        WszSpriteCoordinates.volumeOrBalanceFrameRects(
          bmpWidth = balanceBmp.width,
          bmpHeight = balanceBmp.height,
          isBalance = true,
        ).mapNotNull { rect -> safeCrop(balanceBmp, rect) }
      } else {
        emptyList()
      }
    val balanceThumb = balanceBmp?.let { safeCrop(it, WszSpriteCoordinates.BALANCE_THUMB) }

    val seekbarTrack = posbarBmp?.let { safeCrop(it, WszSpriteCoordinates.POSBAR_TRACK) }
    val seekbarThumb = posbarBmp?.let { safeCrop(it, WszSpriteCoordinates.POSBAR_THUMB) }

    val eqFrames =
      if (eqmainBmp != null) {
        WszSpriteCoordinates.eqSliderFrameRects(eqmainBmp.width, eqmainBmp.height)
          .mapNotNull { rect -> safeCrop(eqmainBmp, rect) }
      } else {
        emptyList()
      }
    val eqThumb = eqmainBmp?.let { safeCrop(it, WszSpriteCoordinates.EQ_THUMB) }

    val titleBarActive = mainTitleBarBmp?.let { safeCrop(it, WszSpriteCoordinates.TITLEBAR_ACTIVE) }
    val eqTitleBarActive = eqmainBmp?.let { safeCrop(it, WszSpriteCoordinates.EQ_TITLEBAR_ACTIVE) }

    val collapsedFromPledit =
      pleditBmp?.let { bmp ->
        WszSpriteCoordinates.pleditCollapsedHeaderRect(bmp.width, bmp.height)
          ?.let { rect -> safeCrop(bmp, rect) }
      }
    val expandedFromPledit =
      pleditBmp?.let { bmp ->
        val parts = WszSpriteCoordinates.pleditExpandedHeaderStripRects(bmp.width, bmp.height)
        if (parts.isNotEmpty()) {
          runCatching { decoder.assembleHorizontalStrip(bmp, parts) }.getOrNull()
        } else {
          null
        }
      }

    val drawerCollapsed: ImageBitmap?
    val drawerExpanded: ImageBitmap?
    if (requireDrawerHeaders) {
      if (titleBarActive == null) {
        diagnosticIssues.add("Missing or undersized titlebar.bmp for raw BMP header")
      }
      if (eqTitleBarActive == null) {
        diagnosticIssues.add("Missing or undersized eqmain.bmp for raw BMP header")
      }
      if (collapsedFromPledit == null) {
        diagnosticIssues.add("Missing or undersized pledit.bmp collapsed header; falling back")
      }
      if (expandedFromPledit == null) {
        diagnosticIssues.add("Missing or undersized pledit.bmp expanded header; falling back")
      }
      drawerCollapsed = collapsedFromPledit ?: titleBarActive
      drawerExpanded = expandedFromPledit ?: titleBarActive
    } else {
      drawerCollapsed = collapsedFromPledit
      drawerExpanded = expandedFromPledit
    }

    return SkinSprites(
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
      deckHeaderSprite = titleBarActive,
      eqHeaderSprite = eqTitleBarActive,
      drawerHeaderCollapsedSprite = drawerCollapsed,
      drawerHeaderExpandedSprite = drawerExpanded,
      shuffleOff = shufrepBmp?.let { safeCrop(it, WszSpriteCoordinates.SHUFFLE_OFF) },
      shuffleOn = shufrepBmp?.let { safeCrop(it, WszSpriteCoordinates.SHUFFLE_ON) },
      repeatOff = shufrepBmp?.let { safeCrop(it, WszSpriteCoordinates.REPEAT_OFF) },
      repeatOn = shufrepBmp?.let { safeCrop(it, WszSpriteCoordinates.REPEAT_ON) },
      eqButtonOff = shufrepBmp?.let { safeCrop(it, WszSpriteCoordinates.EQ_BTN_OFF) },
      eqButtonOn = shufrepBmp?.let { safeCrop(it, WszSpriteCoordinates.EQ_BTN_ON) },
      plButtonOff = shufrepBmp?.let { safeCrop(it, WszSpriteCoordinates.PL_BTN_OFF) },
      plButtonOn = shufrepBmp?.let { safeCrop(it, WszSpriteCoordinates.PL_BTN_ON) },
      eqOnOff = eqmainBmp?.let { safeCrop(it, WszSpriteCoordinates.EQ_ON_OFF) },
      eqOnOn = eqmainBmp?.let { safeCrop(it, WszSpriteCoordinates.EQ_ON_ON) },
      eqAutoOff = eqmainBmp?.let { safeCrop(it, WszSpriteCoordinates.EQ_AUTO_OFF) },
      eqAutoOn = eqmainBmp?.let { safeCrop(it, WszSpriteCoordinates.EQ_AUTO_ON) },
      eqPresetsNormal = eqmainBmp?.let { safeCrop(it, WszSpriteCoordinates.EQ_PRESETS_NORMAL) },
      eqPresetsPressed = eqmainBmp?.let { safeCrop(it, WszSpriteCoordinates.EQ_PRESETS_PRESSED) },
      monoActive = monosterBmp?.let { safeCrop(it, WszSpriteCoordinates.MONO_ACTIVE) },
      monoInactive = monosterBmp?.let { safeCrop(it, WszSpriteCoordinates.MONO_INACTIVE) },
      stereoActive = monosterBmp?.let { safeCrop(it, WszSpriteCoordinates.STEREO_ACTIVE) },
      stereoInactive = monosterBmp?.let { safeCrop(it, WszSpriteCoordinates.STEREO_INACTIVE) },
    )
  }

  private fun overlayManifestPngSprites(
    archive: WszArchive,
    manifest: DroidAmpSkinManifest,
    base: SkinSprites,
    diagnosticIssues: MutableList<String>,
  ): SkinSprites {
    fun resolveSlot(
      slot: CanonicalSkinSpriteSlot,
      fallbackSpriteNames: List<String> = emptyList(),
    ): ImageBitmap? {
      val path = manifest.resolvedSpritePath(slot)
      val explicit = manifest.isSpriteExplicit(slot)
      var bytes = archive.spriteBytes(path)
      var effectivePath = path

      if (bytes == null && !explicit) {
        for (alt in fallbackSpriteNames) {
          val altBytes = archive.spriteBytes(alt)
          if (altBytes != null) {
            bytes = altBytes
            effectivePath = alt
            break
          }
        }
      }

      if (bytes == null) {
        if (explicit) {
          diagnosticIssues.add("Missing manifest sprite: $path")
        }
        return null
      }

      val decoded = runCatching { decoder.decodeFullImageBitmap(bytes) }.getOrNull()
      if (decoded == null) {
        diagnosticIssues.add("Corrupt PNG sprite: $effectivePath")
      }
      return decoded
    }

    val transportNormal = LinkedHashMap(base.transportNormal)
    val transportPressed = LinkedHashMap(base.transportPressed)

    resolveSlot(CanonicalSkinSpriteSlot.TRANSPORT_PREV_NORMAL)?.let {
      transportNormal[TransportIconType.PREV] = it
    }
    resolveSlot(CanonicalSkinSpriteSlot.TRANSPORT_PREV_PRESSED)?.let {
      transportPressed[TransportIconType.PREV] = it
    }
    resolveSlot(CanonicalSkinSpriteSlot.TRANSPORT_PLAY_NORMAL)?.let {
      transportNormal[TransportIconType.PLAY] = it
    }
    resolveSlot(CanonicalSkinSpriteSlot.TRANSPORT_PLAY_PRESSED)?.let {
      transportPressed[TransportIconType.PLAY] = it
    }
    resolveSlot(CanonicalSkinSpriteSlot.TRANSPORT_PAUSE_NORMAL)?.let {
      transportNormal[TransportIconType.PAUSE] = it
    }
    resolveSlot(CanonicalSkinSpriteSlot.TRANSPORT_PAUSE_PRESSED)?.let {
      transportPressed[TransportIconType.PAUSE] = it
    }
    resolveSlot(CanonicalSkinSpriteSlot.TRANSPORT_STOP_NORMAL)?.let {
      transportNormal[TransportIconType.STOP] = it
    }
    resolveSlot(CanonicalSkinSpriteSlot.TRANSPORT_STOP_PRESSED)?.let {
      transportPressed[TransportIconType.STOP] = it
    }
    resolveSlot(CanonicalSkinSpriteSlot.TRANSPORT_NEXT_NORMAL)?.let {
      transportNormal[TransportIconType.NEXT] = it
    }
    resolveSlot(CanonicalSkinSpriteSlot.TRANSPORT_NEXT_PRESSED)?.let {
      transportPressed[TransportIconType.NEXT] = it
    }
    resolveSlot(CanonicalSkinSpriteSlot.TRANSPORT_EJECT_NORMAL)?.let {
      transportNormal[TransportIconType.EJECT] = it
    }
    resolveSlot(CanonicalSkinSpriteSlot.TRANSPORT_EJECT_PRESSED)?.let {
      transportPressed[TransportIconType.EJECT] = it
    }

    val headerDeck = resolveSlot(CanonicalSkinSpriteSlot.HEADER_DECK)
    val headerEq = resolveSlot(CanonicalSkinSpriteSlot.HEADER_EQ)
    val headerDrawerCollapsed = resolveSlot(CanonicalSkinSpriteSlot.HEADER_DRAWER_COLLAPSED)
    val headerDrawerExpanded = resolveSlot(CanonicalSkinSpriteSlot.HEADER_DRAWER_EXPANDED)

    return base.copy(
      transportNormal = transportNormal,
      transportPressed = transportPressed,
      vfdPlayIcon =
        resolveSlot(CanonicalSkinSpriteSlot.VFD_PLAY, listOf("sprites/playpaus_play.png"))
          ?: base.vfdPlayIcon,
      vfdPauseIcon =
        resolveSlot(CanonicalSkinSpriteSlot.VFD_PAUSE, listOf("sprites/playpaus_pause.png"))
          ?: base.vfdPauseIcon,
      vfdStopIcon =
        resolveSlot(CanonicalSkinSpriteSlot.VFD_STOP, listOf("sprites/playpaus_stop.png"))
          ?: base.vfdStopIcon,
      volumeThumb = resolveSlot(CanonicalSkinSpriteSlot.VOLUME_THUMB) ?: base.volumeThumb,
      balanceThumb = resolveSlot(CanonicalSkinSpriteSlot.BALANCE_THUMB) ?: base.balanceThumb,
      seekbarTrack =
        resolveSlot(CanonicalSkinSpriteSlot.SEEKBAR_TRACK, listOf("sprites/posbar_track.png"))
          ?: base.seekbarTrack,
      seekbarThumb =
        resolveSlot(CanonicalSkinSpriteSlot.SEEKBAR_THUMB, listOf("sprites/posbar_thumb.png"))
          ?: base.seekbarThumb,
      eqThumb = resolveSlot(CanonicalSkinSpriteSlot.EQ_THUMB) ?: base.eqThumb,
      titleBarActive = headerDeck ?: base.titleBarActive,
      eqTitleBarActive = headerEq ?: base.eqTitleBarActive,
      deckHeaderSprite = headerDeck ?: base.deckHeaderSprite,
      eqHeaderSprite = headerEq ?: base.eqHeaderSprite,
      drawerHeaderCollapsedSprite = headerDrawerCollapsed ?: base.drawerHeaderCollapsedSprite,
      drawerHeaderExpandedSprite = headerDrawerExpanded ?: base.drawerHeaderExpandedSprite,
      shuffleOff = resolveSlot(CanonicalSkinSpriteSlot.SHUFFLE_OFF) ?: base.shuffleOff,
      shuffleOn = resolveSlot(CanonicalSkinSpriteSlot.SHUFFLE_ON) ?: base.shuffleOn,
      repeatOff = resolveSlot(CanonicalSkinSpriteSlot.REPEAT_OFF) ?: base.repeatOff,
      repeatOn = resolveSlot(CanonicalSkinSpriteSlot.REPEAT_ON) ?: base.repeatOn,
      eqButtonOff = resolveSlot(CanonicalSkinSpriteSlot.EQ_BTN_OFF) ?: base.eqButtonOff,
      eqButtonOn = resolveSlot(CanonicalSkinSpriteSlot.EQ_BTN_ON) ?: base.eqButtonOn,
      plButtonOff = resolveSlot(CanonicalSkinSpriteSlot.QUEUE_BTN_OFF) ?: base.plButtonOff,
      plButtonOn = resolveSlot(CanonicalSkinSpriteSlot.QUEUE_BTN_ON) ?: base.plButtonOn,
      eqOnOff = resolveSlot(CanonicalSkinSpriteSlot.EQ_ON_OFF) ?: base.eqOnOff,
      eqOnOn = resolveSlot(CanonicalSkinSpriteSlot.EQ_ON_ON) ?: base.eqOnOn,
      eqAutoOff = resolveSlot(CanonicalSkinSpriteSlot.EQ_AUTO_OFF) ?: base.eqAutoOff,
      eqAutoOn = resolveSlot(CanonicalSkinSpriteSlot.EQ_AUTO_ON) ?: base.eqAutoOn,
      eqPresetsNormal = resolveSlot(CanonicalSkinSpriteSlot.EQ_PRESETS_NORMAL) ?: base.eqPresetsNormal,
      eqPresetsPressed = resolveSlot(CanonicalSkinSpriteSlot.EQ_PRESETS_PRESSED) ?: base.eqPresetsPressed,
      monoActive = resolveSlot(CanonicalSkinSpriteSlot.MONO_ACTIVE) ?: base.monoActive,
      monoInactive = resolveSlot(CanonicalSkinSpriteSlot.MONO_INACTIVE) ?: base.monoInactive,
      stereoActive = resolveSlot(CanonicalSkinSpriteSlot.STEREO_ACTIVE) ?: base.stereoActive,
      stereoInactive = resolveSlot(CanonicalSkinSpriteSlot.STEREO_INACTIVE) ?: base.stereoInactive,
      mobileTabQueueOff = resolveSlot(CanonicalSkinSpriteSlot.MOBILE_TAB_QUEUE_OFF) ?: base.mobileTabQueueOff,
      mobileTabQueueOn = resolveSlot(CanonicalSkinSpriteSlot.MOBILE_TAB_QUEUE_ON) ?: base.mobileTabQueueOn,
      mobileTabLibraryOff = resolveSlot(CanonicalSkinSpriteSlot.MOBILE_TAB_LIBRARY_OFF) ?: base.mobileTabLibraryOff,
      mobileTabLibraryOn = resolveSlot(CanonicalSkinSpriteSlot.MOBILE_TAB_LIBRARY_ON) ?: base.mobileTabLibraryOn,
      mobileRemNormal = resolveSlot(CanonicalSkinSpriteSlot.MOBILE_REM_NORMAL) ?: base.mobileRemNormal,
      mobileRemPressed = resolveSlot(CanonicalSkinSpriteSlot.MOBILE_REM_PRESSED) ?: base.mobileRemPressed,
      mobileMiscNormal = resolveSlot(CanonicalSkinSpriteSlot.MOBILE_MISC_NORMAL) ?: base.mobileMiscNormal,
      mobileMiscPressed = resolveSlot(CanonicalSkinSpriteSlot.MOBILE_MISC_PRESSED) ?: base.mobileMiscPressed,
      mobileListNormal = resolveSlot(CanonicalSkinSpriteSlot.MOBILE_LIST_NORMAL) ?: base.mobileListNormal,
      mobileListPressed = resolveSlot(CanonicalSkinSpriteSlot.MOBILE_LIST_PRESSED) ?: base.mobileListPressed,
      mobileRowPlayNormal = resolveSlot(CanonicalSkinSpriteSlot.MOBILE_ROW_PLAY_NORMAL) ?: base.mobileRowPlayNormal,
      mobileRowPlayPressed = resolveSlot(CanonicalSkinSpriteSlot.MOBILE_ROW_PLAY_PRESSED) ?: base.mobileRowPlayPressed,
      mobileRowEnqueueNormal =
        resolveSlot(CanonicalSkinSpriteSlot.MOBILE_ROW_ENQUEUE_NORMAL) ?: base.mobileRowEnqueueNormal,
      mobileRowEnqueuePressed =
        resolveSlot(CanonicalSkinSpriteSlot.MOBILE_ROW_ENQUEUE_PRESSED) ?: base.mobileRowEnqueuePressed,
      mobileSettingsGearNormal =
        resolveSlot(CanonicalSkinSpriteSlot.MOBILE_SETTINGS_GEAR_NORMAL) ?: base.mobileSettingsGearNormal,
      mobileSettingsGearPressed =
        resolveSlot(CanonicalSkinSpriteSlot.MOBILE_SETTINGS_GEAR_PRESSED) ?: base.mobileSettingsGearPressed,
    )
  }

  private fun safeCrop(bitmap: DecodedSkinBitmap, rect: SpriteRect): ImageBitmap? =
    runCatching { decoder.cropToImageBitmap(bitmap, rect) }.getOrNull()
}
