package com.droidamp.player.skin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.droidamp.player.interop.DetectedWinampFormat
import com.droidamp.player.interop.DroidAmpFileSniffer
import com.droidamp.player.skin.wsz.DroidAmpSkinManifestParser
import com.droidamp.player.skin.wsz.WszArchiveParser
import com.droidamp.player.skin.wsz.WszSkinLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class StoredSkinFile(
  val fileName: String,
  val pathOrUri: String,
)

/**
 * Pure-Kotlin storage boundary isolating `Context`, `AssetManager`, `ContentResolver`,
 * `DocumentFile`, and `Uri.parse` from [AndroidSkinRepository] so local JVM unit tests run
 * cleanly without Android framework mocks.
 */
interface SkinStorageGateway {
  fun listBundledShowcaseSkins(): List<String>
  fun readBundledShowcaseSkin(fileName: String): ByteArray?

  fun listImportedSkins(): List<StoredSkinFile>
  fun readImportedSkin(fileName: String): ByteArray?
  fun writeImportedSkin(fileName: String, bytes: ByteArray): StoredSkinFile
  fun deleteImportedSkin(fileName: String): Boolean

  fun listSafSkins(rootUriString: String): List<StoredSkinFile>
  fun readExternalUriBytes(uriString: String): ByteArray?
  fun resolveExternalDisplayName(uriString: String): String?
}

class AndroidSkinStorageGateway(
  context: Context,
) : SkinStorageGateway {
  private val appContext = context.applicationContext
  private val skinsDir = File(appContext.filesDir, "skins")

  override fun listBundledShowcaseSkins(): List<String> =
    runCatching {
      appContext.assets
        .list(SHOWCASE_ASSETS_DIR)
        ?.filter { it.lowercase(Locale.ROOT).endsWith(".wsz") }
        ?.sortedBy { it.lowercase(Locale.ROOT) }
        .orEmpty()
    }.getOrDefault(emptyList())

  override fun readBundledShowcaseSkin(fileName: String): ByteArray? =
    runCatching {
      appContext.assets.open("$SHOWCASE_ASSETS_DIR/$fileName").use { it.readBytes() }
    }.getOrNull()

  override fun listImportedSkins(): List<StoredSkinFile> {
    if (!skinsDir.isDirectory) return emptyList()
    return skinsDir
      .listFiles { f -> f.isFile && f.name.lowercase(Locale.ROOT).endsWith(".wsz") }
      ?.sortedBy { it.name.lowercase(Locale.ROOT) }
      ?.map { file -> StoredSkinFile(fileName = file.name, pathOrUri = file.absolutePath) }
      .orEmpty()
  }

  override fun readImportedSkin(fileName: String): ByteArray? {
    val file = File(skinsDir, fileName)
    if (!file.isFile) return null
    return runCatching { file.readBytes() }.getOrNull()
  }

  override fun writeImportedSkin(fileName: String, bytes: ByteArray): StoredSkinFile {
    if (!skinsDir.exists()) {
      skinsDir.mkdirs()
    }
    val destFile = File(skinsDir, fileName)
    destFile.writeBytes(bytes)
    return StoredSkinFile(fileName = destFile.name, pathOrUri = destFile.absolutePath)
  }

  override fun deleteImportedSkin(fileName: String): Boolean {
    val file = File(skinsDir, fileName)
    return file.isFile && file.delete()
  }

  override fun listSafSkins(rootUriString: String): List<StoredSkinFile> =
    runCatching {
      val rootDoc = DocumentFile.fromTreeUri(appContext, Uri.parse(rootUriString)) ?: return emptyList()
      val dirsToCheck = ArrayList<DocumentFile>(2)
      dirsToCheck.add(rootDoc)
      rootDoc.listFiles()
        .firstOrNull { it.isDirectory && it.name.equals("skins", ignoreCase = true) }
        ?.let(dirsToCheck::add)

      val found = ArrayList<StoredSkinFile>()
      for (dir in dirsToCheck) {
        dir.listFiles().forEach { child ->
          val name = child.name ?: return@forEach
          if (!child.isDirectory && name.lowercase(Locale.ROOT).endsWith(".wsz")) {
            found.add(StoredSkinFile(fileName = name, pathOrUri = child.uri.toString()))
          }
        }
      }
      found
    }.getOrDefault(emptyList())

  override fun readExternalUriBytes(uriString: String): ByteArray? =
    runCatching {
      val uri = Uri.parse(uriString)
      appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

  override fun resolveExternalDisplayName(uriString: String): String? {
    runCatching {
      val uri = Uri.parse(uriString)
      appContext.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) {
              val display = cursor.getString(idx)
              if (!display.isNullOrBlank()) return display
            }
          }
        }
      return uri.lastPathSegment?.substringAfterLast('/')
    }
    return uriString.substringAfterLast('/').takeIf { it.isNotBlank() }
  }

  companion object {
    private const val SHOWCASE_ASSETS_DIR = "skins"
  }
}

/**
 * Manages built-in [ClassicDroidAmpSkin], bundled Augmented `.wsz` showcase skins (`assets/skins/`),
 * locally imported `.wsz` archives (`filesDir/skins/`), shallow SAF `.wsz` discovery, Cold-Launch
 * Safe-Boot crash recovery, magic-byte wrong-slot detection, and live render/contrast overrides
 * (`ADR-028`, `ADR-029`).
 */
class AndroidSkinRepository internal constructor(
  private val storageGateway: SkinStorageGateway,
  private val preferencesStore: SkinPreferencesStore,
  private val scope: CoroutineScope,
  private val loader: WszSkinLoader = WszSkinLoader(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SkinRepository {

  constructor(
    appContext: Context,
    preferencesStore: SkinPreferencesStore,
    scope: CoroutineScope,
    loader: WszSkinLoader = WszSkinLoader(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) : this(
    storageGateway = AndroidSkinStorageGateway(appContext),
    preferencesStore = preferencesStore,
    scope = scope,
    loader = loader,
    ioDispatcher = ioDispatcher,
  )

  private data class CachedHeaderMeta(
    val displayName: String,
    val author: String,
    val origin: SkinOrigin,
    val previewPalette: SkinMiniPreviewPalette = SkinMiniPreviewPalette.DEFAULT,
  )

  private val decodedCache = ConcurrentHashMap<String, DroidAmpSkin>()
  private val metadataCache = ConcurrentHashMap<String, CachedHeaderMeta>()
  private val stateMutex = Mutex()

  private val _availableSkins = MutableStateFlow(listOf(BUILT_IN_ENTRY))
  override val availableSkins: StateFlow<List<SkinEntry>> = _availableSkins.asStateFlow()

  private val _activeSkin = MutableStateFlow<DroidAmpSkin>(ClassicDroidAmpSkin)
  override val activeSkin: StateFlow<DroidAmpSkin> = _activeSkin.asStateFlow()

  private val _skinSettings = MutableStateFlow(SkinSettingsState())
  override val skinSettings: StateFlow<SkinSettingsState> = _skinSettings.asStateFlow()

  private val _recoveryNotice = MutableStateFlow<SkinRecoveryNotice?>(null)
  override val recoveryNotice: StateFlow<SkinRecoveryNotice?> = _recoveryNotice.asStateFlow()

  @Volatile
  private var lastLinkedRoots: List<String> = emptyList()

  @Volatile
  private var deferredSafBootSkinId: String? = null

  init {
    scope.launch {
      stateMutex.withLock {
        val initialSettings = loadPersistedSettings()
        _skinSettings.value = initialSettings

        val discovered = scanSkins(lastLinkedRoots)
        _availableSkins.value = discovered

        // Cold-Launch Safe-Boot Guard: detect un-cleared boot marker from a previous crash
        val pendingCrashSkinId = runCatching { preferencesStore.loadPendingBootSkinId() }.getOrNull()
        if (pendingCrashSkinId != null) {
          deferredSafBootSkinId = null
          runCatching { preferencesStore.savePendingBootSkinId(null) }
          runCatching { preferencesStore.saveActiveSkinId(ClassicDroidAmpSkin.id) }
          _activeSkin.value = ClassicDroidAmpSkin
          _recoveryNotice.value =
            SkinRecoveryNotice.SafeBootReverted(
              failedSkinId = pendingCrashSkinId,
              failedSkinName = resolveDisplayNameForId(pendingCrashSkinId, discovered),
            )
          return@withLock
        }

        val savedId =
          runCatching { preferencesStore.loadActiveSkinId() }
            .getOrDefault(ClassicDroidAmpSkin.id)
        if (savedId == ClassicDroidAmpSkin.id) {
          _activeSkin.value = ClassicDroidAmpSkin
        } else if (savedId.startsWith(SAF_PREFIX) && lastLinkedRoots.isEmpty()) {
          // Wait for linkedRoots hydration before concluding a SAF-stored skin is missing
          deferredSafBootSkinId = savedId
        } else {
          coldBootLoadSkin(savedId, discovered)
        }
      }
    }
  }

  override fun refreshSkins(linkedRootUris: List<String>) {
    lastLinkedRoots = linkedRootUris
    scope.launch {
      stateMutex.withLock {
        val discovered = scanSkins(linkedRootUris)
        _availableSkins.value = discovered

        val deferredId = deferredSafBootSkinId
        if (deferredId != null) {
          if (linkedRootUris.isNotEmpty() || discovered.any { it.id == deferredId }) {
            deferredSafBootSkinId = null
            coldBootLoadSkin(deferredId, discovered)
          }
          return@withLock
        }

        val savedId =
          runCatching { preferencesStore.loadActiveSkinId() }
            .getOrDefault(_activeSkin.value.id)
        if (_activeSkin.value.id != savedId &&
          _recoveryNotice.value !is SkinRecoveryNotice.SafeBootReverted &&
          discovered.any { it.id == savedId }
        ) {
          applySkinById(
            skinId = savedId,
            entries = discovered,
            emitDiagnosticNotices = false,
          )
        }
      }
    }
  }

  override fun selectSkin(skinId: String) {
    scope.launch {
      stateMutex.withLock {
        deferredSafBootSkinId = null
        if (_activeSkin.value.id == skinId) return@withLock
        val previousSkinId = _activeSkin.value.id
        val list = _availableSkins.value
        applySkinById(
          skinId = skinId,
          entries = list,
          emitDiagnosticNotices = true,
          previousSkinId = previousSkinId,
        )
        runCatching { preferencesStore.saveActiveSkinId(_activeSkin.value.id) }
      }
    }
  }

  override suspend fun importSkinFromUri(uriString: String): Result<SkinEntry?> =
    withContext(ioDispatcher) {
      try {
        val rawFileName =
          storageGateway.resolveExternalDisplayName(uriString)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: uriString.substringAfterLast('/').substringAfterLast(':').ifBlank { "imported_skin.wsz" }

        val rawBytes = storageGateway.readExternalUriBytes(uriString)
        if (rawBytes == null) {
          _recoveryNotice.value =
            SkinRecoveryNotice.InvalidSkinError(
              fileName = rawFileName,
              reason = "Unable to read selected skin file.",
            )
          return@withContext Result.success(null)
        }

        processImportedBytes(
          uriString = uriString,
          rawFileName = rawFileName,
          rawBytes = rawBytes,
        )
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        Result.failure(t)
      }
    }

  override suspend fun importSkinFromBytes(
    fileName: String,
    rawBytes: ByteArray,
  ): Result<SkinEntry?> =
    withContext(ioDispatcher) {
      try {
        processImportedBytes(
          uriString = "",
          rawFileName = fileName.ifBlank { "imported_skin.wsz" },
          rawBytes = rawBytes,
        )
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        Result.failure(t)
      }
    }

  private suspend fun processImportedBytes(
    uriString: String,
    rawFileName: String,
    rawBytes: ByteArray,
  ): Result<SkinEntry?> =
    when (val detected = DroidAmpFileSniffer.sniff(rawBytes, rawFileName)) {
      DetectedWinampFormat.EqfSinglePreset,
      DetectedWinampFormat.EqLibraryQ1,
      DetectedWinampFormat.AutoEqQ2,
      DetectedWinampFormat.WinampIni,
      DetectedWinampFormat.PlaylistM3u,
      DetectedWinampFormat.PlaylistPls,
      -> {
        _recoveryNotice.value =
          SkinRecoveryNotice.WrongSlotFileDetected(
            uriString = uriString,
            fileName = rawFileName,
            detectedFormat = detected,
            rawBytes = rawBytes,
          )
        Result.success(null)
      }

      is DetectedWinampFormat.Unsupported -> {
        _recoveryNotice.value =
          SkinRecoveryNotice.InvalidSkinError(
            fileName = rawFileName,
            reason = detected.reason,
          )
        Result.success(null)
      }

      is DetectedWinampFormat.WszSkin -> {
        val parsedArchive = WszArchiveParser.parse(rawBytes)
        if (parsedArchive.entries.isEmpty() &&
          parsedArchive.manifestJson() == null &&
          parsedArchive.sprites.isEmpty()
        ) {
          _recoveryNotice.value =
            SkinRecoveryNotice.InvalidSkinError(
              fileName = rawFileName,
              reason = "ZIP archive contains no safe .wsz skin assets.",
            )
          return Result.success(null)
        }

        val cleanBase = sanitizeBaseFileName(rawFileName)
        val proposedFileName = "$cleanBase.wsz"
        val existingStored =
          storageGateway.listImportedSkins().firstOrNull {
            it.fileName.equals(proposedFileName, ignoreCase = true)
          }

        if (existingStored != null) {
          val existingId = "$IMPORTED_PREFIX${existingStored.fileName}"
          val existingEntry =
            _availableSkins.value.firstOrNull { it.id == existingId }
              ?: buildImportedEntry(existingStored)
          _recoveryNotice.value =
            SkinRecoveryNotice.DuplicateSkinConflict(
              uriString = uriString,
              existingEntry = existingEntry,
              proposedFileName = existingStored.fileName,
              rawBytes = rawBytes,
            )
          return Result.success(null)
        }

        val entry = commitImportedSkinBytes(fileName = proposedFileName, rawBytes = rawBytes)
        Result.success(entry)
      }
    }

  override suspend fun resolveDuplicateImport(
    conflict: SkinRecoveryNotice.DuplicateSkinConflict,
    replaceExisting: Boolean,
  ): Result<SkinEntry> =
    withContext(ioDispatcher) {
      runCatching {
        _recoveryNotice.value = null
        val targetFileName =
          if (replaceExisting) {
            conflict.existingEntry.id
              .removePrefix(IMPORTED_PREFIX)
              .ifBlank { conflict.proposedFileName }
          } else {
            allocateUniqueImportedFileName(conflict.proposedFileName)
          }
        commitImportedSkinBytes(fileName = targetFileName, rawBytes = conflict.rawBytes)
      }
    }

  override suspend fun deleteImportedSkin(skinId: String): Boolean =
    withContext(ioDispatcher) {
      if (!skinId.startsWith(IMPORTED_PREFIX)) return@withContext false
      val fileName = skinId.removePrefix(IMPORTED_PREFIX)
      if (fileName.isBlank()) return@withContext false

      val deletedDisplayName = resolveDisplayNameForId(skinId, _availableSkins.value)
      val wasActive = (_activeSkin.value.id == skinId)

      val deleted = storageGateway.deleteImportedSkin(fileName)
      if (!deleted) return@withContext false

      decodedCache.remove(skinId)
      metadataCache.remove(skinId)

      if (skinId in _skinSettings.value.rawColorOverrideSkinIds) {
        val cleanedIds = _skinSettings.value.rawColorOverrideSkinIds - skinId
        _skinSettings.value = _skinSettings.value.copy(rawColorOverrideSkinIds = cleanedIds)
        runCatching { preferencesStore.saveRawColorOverrideSkinIds(cleanedIds) }
      }

      val refreshed = scanSkins(lastLinkedRoots)
      _availableSkins.value = refreshed

      if (wasActive) {
        _activeSkin.value = ClassicDroidAmpSkin
        runCatching { preferencesStore.saveActiveSkinId(ClassicDroidAmpSkin.id) }
        _recoveryNotice.value =
          SkinRecoveryNotice.ActiveSkinDeletedReverted(deletedSkinName = deletedDisplayName)
      }
      true
    }

  override fun setLegacyRenderMode(mode: SkinRenderMode) {
    scope.launch {
      stateMutex.withLock {
        if (_skinSettings.value.legacyRenderMode == mode) return@withLock
        _skinSettings.value = _skinSettings.value.copy(legacyRenderMode = mode)
        decodedCache.clear()
        runCatching { preferencesStore.saveLegacyRenderMode(mode) }
        reloadActiveSkinInPlace()
      }
    }
  }

  override fun setAutoContrastGuard(enabled: Boolean) {
    scope.launch {
      stateMutex.withLock {
        if (_skinSettings.value.autoContrastGuard == enabled) return@withLock
        _skinSettings.value = _skinSettings.value.copy(autoContrastGuard = enabled)
        decodedCache.clear()
        runCatching { preferencesStore.saveAutoContrastGuard(enabled) }
        reloadActiveSkinInPlace()
      }
    }
  }

  override fun setSkinRawColorsOverride(skinId: String, useRawColors: Boolean) {
    scope.launch {
      stateMutex.withLock {
        val curSet = _skinSettings.value.rawColorOverrideSkinIds
        val updatedSet = if (useRawColors) curSet + skinId else curSet - skinId
        if (updatedSet == curSet) return@withLock
        _skinSettings.value = _skinSettings.value.copy(rawColorOverrideSkinIds = updatedSet)
        decodedCache.remove(skinId)
        runCatching { preferencesStore.saveRawColorOverrideSkinIds(updatedSet) }
        if (_activeSkin.value.id == skinId) {
          reloadActiveSkinInPlace()
        }
      }
    }
  }

  override fun retrySafeBootSkin(skinId: String) {
    _recoveryNotice.value = null
    scope.launch {
      stateMutex.withLock {
        deferredSafBootSkinId = null
        val discovered = scanSkins(lastLinkedRoots)
        _availableSkins.value = discovered
        decodedCache.remove(skinId)
        val loaded = coldBootLoadSkin(skinId, discovered)
        if (loaded != null) {
          runCatching { preferencesStore.saveActiveSkinId(loaded.id) }
        }
      }
    }
  }

  override fun revertToSkin(skinId: String) {
    _recoveryNotice.value = null
    scope.launch {
      stateMutex.withLock {
        deferredSafBootSkinId = null
        applySkinById(
          skinId = skinId,
          entries = _availableSkins.value,
          emitDiagnosticNotices = false,
        )
        runCatching { preferencesStore.saveActiveSkinId(_activeSkin.value.id) }
      }
    }
  }

  override fun dismissRecoveryNotice() {
    _recoveryNotice.value = null
  }

  private suspend fun reloadActiveSkinInPlace() {
    val activeId = _activeSkin.value.id
    if (activeId == ClassicDroidAmpSkin.id) return
    decodedCache.remove(activeId)
    applySkinById(
      skinId = activeId,
      entries = _availableSkins.value,
      emitDiagnosticNotices = false,
    )
  }

  private suspend fun coldBootLoadSkin(
    skinId: String,
    entries: List<SkinEntry>,
  ): DroidAmpSkin? {
    val entry = entries.firstOrNull { it.id == skinId }
    if (entry == null) {
      runCatching { preferencesStore.saveActiveSkinId(ClassicDroidAmpSkin.id) }
      _activeSkin.value = ClassicDroidAmpSkin
      _recoveryNotice.value =
        SkinRecoveryNotice.SafeBootReverted(
          failedSkinId = skinId,
          failedSkinName = resolveDisplayNameForId(skinId, entries),
        )
      return null
    }

    runCatching { preferencesStore.savePendingBootSkinId(skinId) }
    val decodeResult =
      withContext(ioDispatcher) {
        try {
          val bytes = readBytesForEntry(entry) ?: error("Missing skin archive bytes for ${entry.id}")
          require(WszArchiveParser.hasZipMagic(bytes)) { "Corrupt .wsz archive header for ${entry.id}" }
          val archive = WszArchiveParser.parse(bytes)
          require(
            archive.entries.isNotEmpty() ||
              archive.manifestJson() != null ||
              archive.sprites.isNotEmpty(),
          ) { "Empty .wsz archive for ${entry.id}" }
          val settings = _skinSettings.value
          val skin =
            loader.loadFromArchive(
              id = entry.id,
              displayName = entry.displayName,
              archive = archive,
              legacyRenderMode = settings.legacyRenderMode,
              contrastGuardEnabled = settings.isContrastGuardEffectiveFor(entry.id),
              preserveCallerId = true,
            )
          Result.success(skin)
        } catch (ce: CancellationException) {
          throw ce
        } catch (t: Throwable) {
          Result.failure(t)
        }
      }
    runCatching { preferencesStore.savePendingBootSkinId(null) }

    val loaded = decodeResult.getOrNull()
    return if (loaded != null) {
      decodedCache[entry.id] = loaded
      _activeSkin.value = loaded
      loaded
    } else {
      runCatching { preferencesStore.saveActiveSkinId(ClassicDroidAmpSkin.id) }
      _activeSkin.value = ClassicDroidAmpSkin
      _recoveryNotice.value =
        SkinRecoveryNotice.SafeBootReverted(
          failedSkinId = skinId,
          failedSkinName = entry.displayName,
        )
      null
    }
  }

  private suspend fun commitImportedSkinBytes(
    fileName: String,
    rawBytes: ByteArray,
  ): SkinEntry {
    val previousSkinId = _activeSkin.value.id
    val stored = storageGateway.writeImportedSkin(fileName, rawBytes)
    val id = "$IMPORTED_PREFIX${stored.fileName}"
    decodedCache.remove(id)
    metadataCache.remove(id)

    val settings = _skinSettings.value
    val decoded =
      loader.load(
        id = id,
        displayName = formatSkinName(stored.fileName),
        zipBytes = rawBytes,
        legacyRenderMode = settings.legacyRenderMode,
        contrastGuardEnabled = settings.isContrastGuardEffectiveFor(id),
        preserveCallerId = true,
      )
    decodedCache[id] = decoded
    metadataCache[id] =
      CachedHeaderMeta(
        displayName = decoded.displayName,
        author = decoded.author,
        origin = decoded.origin,
        previewPalette =
          SkinMiniPreviewPalette(
            panelBg = decoded.chassis.panelSurface,
            titleBarStart = decoded.chassis.titleBarActiveStart,
            titleBarEnd = decoded.chassis.titleBarActiveEnd,
            vfdBg = decoded.vfd.wellBackground,
            vfdPhosphor = decoded.vfd.phosphorPrimary,
            accent = decoded.vfd.ledAmber,
          ),
      )

    val refreshed = scanSkins(lastLinkedRoots)
    _availableSkins.value = refreshed
    _activeSkin.value = decoded
    runCatching { preferencesStore.saveActiveSkinId(id) }

    emitDiagnosticNoticeIfNeeded(
      decoded = decoded,
      previousSkinId = previousSkinId,
    )

    return refreshed.firstOrNull { it.id == id }
      ?: SkinEntry(
        id = id,
        displayName = decoded.displayName,
        source = SkinSource.IMPORTED,
        origin = decoded.origin,
        uriOrPath = stored.pathOrUri,
        author = decoded.author,
        previewPalette = metadataCache[id]?.previewPalette ?: SkinMiniPreviewPalette.DEFAULT,
      )
  }

  private suspend fun applySkinById(
    skinId: String,
    entries: List<SkinEntry>,
    emitDiagnosticNotices: Boolean,
    previousSkinId: String = _activeSkin.value.id,
  ) {
    if (skinId == ClassicDroidAmpSkin.id) {
      _activeSkin.value = ClassicDroidAmpSkin
      return
    }
    decodedCache[skinId]?.let { cached ->
      _activeSkin.value = cached
      if (emitDiagnosticNotices) {
        emitDiagnosticNoticeIfNeeded(decoded = cached, previousSkinId = previousSkinId)
      }
      return
    }
    val entry = entries.firstOrNull { it.id == skinId }
    if (entry == null) {
      _activeSkin.value = ClassicDroidAmpSkin
      return
    }

    val settings = _skinSettings.value
    val loaded =
      withContext(ioDispatcher) {
        try {
          if (entry.source == SkinSource.BUILT_IN) {
            ClassicDroidAmpSkin
          } else {
            val bytes = readBytesForEntry(entry) ?: return@withContext null
            if (!WszArchiveParser.hasZipMagic(bytes)) return@withContext null
            loader.load(
              id = entry.id,
              displayName = entry.displayName,
              zipBytes = bytes,
              legacyRenderMode = settings.legacyRenderMode,
              contrastGuardEnabled = settings.isContrastGuardEffectiveFor(entry.id),
              preserveCallerId = true,
            )
          }
        } catch (ce: CancellationException) {
          throw ce
        } catch (_: Throwable) {
          null
        }
      }

    if (loaded != null) {
      decodedCache[entry.id] = loaded
      _activeSkin.value = loaded
      if (emitDiagnosticNotices) {
        emitDiagnosticNoticeIfNeeded(decoded = loaded, previousSkinId = previousSkinId)
      }
    } else {
      _activeSkin.value = ClassicDroidAmpSkin
    }
  }

  private fun emitDiagnosticNoticeIfNeeded(
    decoded: DroidAmpSkin,
    previousSkinId: String,
  ) {
    val safePreviousId =
      if (previousSkinId.isBlank() || previousSkinId == decoded.id) {
        ClassicDroidAmpSkin.id
      } else {
        previousSkinId
      }
    val diag = decoded.diagnostic
    when {
      diag.fallbackUsed -> {
        _recoveryNotice.value =
          SkinRecoveryNotice.PartialSkinFallback(
            skinId = decoded.id,
            skinName = decoded.displayName,
            missingAssets = diag.missingOrCorruptAssets,
            previousSkinId = safePreviousId,
          )
      }
      diag.hasContrastAdjustments -> {
        _recoveryNotice.value =
          SkinRecoveryNotice.ContrastGuardApplied(
            skinId = decoded.id,
            skinName = decoded.displayName,
            adjustedTokenCount = diag.contrastAdjustedTokens.size,
          )
      }
      else -> {
        _recoveryNotice.value = null
      }
    }
  }

  private fun readBytesForEntry(entry: SkinEntry): ByteArray? =
    when (entry.source) {
      SkinSource.BUILT_IN -> null
      SkinSource.BUILT_IN_SHOWCASE -> {
        val assetFileName = entry.id.removePrefix(SHOWCASE_PREFIX)
        storageGateway.readBundledShowcaseSkin(assetFileName)
      }
      SkinSource.IMPORTED -> {
        val fileName = entry.id.removePrefix(IMPORTED_PREFIX)
        storageGateway.readImportedSkin(fileName)
      }
      SkinSource.SAF_FOLDER -> {
        entry.uriOrPath?.let(storageGateway::readExternalUriBytes)
      }
    }

  private suspend fun scanSkins(linkedRootUris: List<String>): List<SkinEntry> =
    withContext(ioDispatcher) {
      val list = ArrayList<SkinEntry>()
      list.add(BUILT_IN_ENTRY)

      // 1. Bundled Augmented `.wsz` showcase skins (`assets/skins/*.wsz`)
      for (assetName in storageGateway.listBundledShowcaseSkins()) {
        val id = "$SHOWCASE_PREFIX$assetName"
        val meta =
          metadataCache[id]
            ?: inspectArchiveHeader(
              fallbackName = formatSkinName(assetName),
              defaultOrigin = SkinOrigin.AUGMENTED_WSZ,
              bytesProvider = { storageGateway.readBundledShowcaseSkin(assetName) },
            ).also { metadataCache[id] = it }
        list.add(
          SkinEntry(
            id = id,
            displayName = meta.displayName,
            source = SkinSource.BUILT_IN_SHOWCASE,
            origin = meta.origin,
            uriOrPath = "assets://skins/$assetName",
            author = meta.author,
            previewPalette = meta.previewPalette,
          ),
        )
      }

      // 2. Locally imported `.wsz` archives (`filesDir/skins/*.wsz`)
      for (stored in storageGateway.listImportedSkins()) {
        list.add(buildImportedEntry(stored))
      }

      // 3. Shallow SAF-discovered `.wsz` files (depth <= 1: root + `Skins/` subfolder)
      val seenIds = list.map { it.id }.toHashSet()
      for (rootUriStr in linkedRootUris) {
        for (safSkin in storageGateway.listSafSkins(rootUriStr)) {
          val id = "$SAF_PREFIX${safSkin.pathOrUri}"
          if (seenIds.add(id)) {
            val meta =
              metadataCache[id]
                ?: inspectArchiveHeader(
                  fallbackName = formatSkinName(safSkin.fileName),
                  defaultOrigin = SkinOrigin.LEGACY_WSZ,
                  bytesProvider = { storageGateway.readExternalUriBytes(safSkin.pathOrUri) },
                ).also { metadataCache[id] = it }
            list.add(
              SkinEntry(
                id = id,
                displayName = meta.displayName,
                source = SkinSource.SAF_FOLDER,
                origin = meta.origin,
                uriOrPath = safSkin.pathOrUri,
                author = meta.author,
                previewPalette = meta.previewPalette,
              ),
            )
          }
        }
      }

      list
    }

  private fun buildImportedEntry(stored: StoredSkinFile): SkinEntry {
    val id = "$IMPORTED_PREFIX${stored.fileName}"
    val meta =
      metadataCache[id]
        ?: inspectArchiveHeader(
          fallbackName = formatSkinName(stored.fileName),
          defaultOrigin = SkinOrigin.LEGACY_WSZ,
          bytesProvider = { storageGateway.readImportedSkin(stored.fileName) },
        ).also { metadataCache[id] = it }
    return SkinEntry(
      id = id,
      displayName = meta.displayName,
      source = SkinSource.IMPORTED,
      origin = meta.origin,
      uriOrPath = stored.pathOrUri,
      author = meta.author,
      previewPalette = meta.previewPalette,
    )
  }

  private fun inspectArchiveHeader(
    fallbackName: String,
    defaultOrigin: SkinOrigin,
    bytesProvider: () -> ByteArray?,
  ): CachedHeaderMeta {
    val bytes = runCatching { bytesProvider() }.getOrNull()
    if (bytes == null || !WszArchiveParser.hasZipMagic(bytes)) {
      return CachedHeaderMeta(displayName = fallbackName, author = "DroidAmp", origin = defaultOrigin)
    }
    val archive = runCatching { WszArchiveParser.parse(bytes) }.getOrNull()
      ?: return CachedHeaderMeta(displayName = fallbackName, author = "DroidAmp", origin = defaultOrigin)
    val preview = runCatching { loader.extractPreviewPalette(archive) }.getOrDefault(SkinMiniPreviewPalette.DEFAULT)
    val manifestJson = archive.manifestJson()
    if (manifestJson == null) {
      return CachedHeaderMeta(
        displayName = fallbackName,
        author = "DroidAmp",
        origin = SkinOrigin.LEGACY_WSZ,
        previewPalette = preview,
      )
    }
    val manifest = DroidAmpSkinManifestParser.parse(manifestJson)
    return CachedHeaderMeta(
      displayName = manifest.name ?: fallbackName,
      author = manifest.author ?: "DroidAmp",
      origin = SkinOrigin.AUGMENTED_WSZ,
      previewPalette = preview,
    )
  }

  private suspend fun loadPersistedSettings(): SkinSettingsState {
    val renderMode =
      runCatching { preferencesStore.loadLegacyRenderMode() }
        .getOrDefault(SkinRenderMode.VECTOR_CHROME)
    val contrastGuard =
      runCatching { preferencesStore.loadAutoContrastGuard() }
        .getOrDefault(true)
    val rawOverrides =
      runCatching { preferencesStore.loadRawColorOverrideSkinIds() }
        .getOrDefault(emptySet())
    return SkinSettingsState(
      legacyRenderMode = renderMode,
      autoContrastGuard = contrastGuard,
      rawColorOverrideSkinIds = rawOverrides,
    )
  }

  private fun allocateUniqueImportedFileName(proposedFileName: String): String {
    val baseStem =
      proposedFileName
        .replace(WSZ_EXTENSION_REGEX, "")
        .ifBlank { "custom_skin" }
    val existingLower =
      storageGateway
        .listImportedSkins()
        .map { it.fileName.lowercase(Locale.ROOT) }
        .toHashSet()
    var suffix = 2
    while (true) {
      val candidate = "${baseStem}_$suffix.wsz"
      if (candidate.lowercase(Locale.ROOT) !in existingLower) {
        return candidate
      }
      suffix++
    }
  }

  private fun sanitizeBaseFileName(rawName: String): String {
    val withoutExt =
      if (rawName.contains('.')) rawName.substringBeforeLast('.') else rawName
    return withoutExt
      .replace(Regex("[^A-Za-z0-9._ -]"), "_")
      .trim()
      .ifEmpty { "custom_skin" }
  }

  private fun resolveDisplayNameForId(
    skinId: String,
    entries: List<SkinEntry>,
  ): String {
    entries.firstOrNull { it.id == skinId }?.let { return it.displayName }
    val tail =
      if (skinId.startsWith(SAF_PREFIX)) {
        decodePercentEscapes(skinId.removePrefix(SAF_PREFIX))
          .substringAfterLast('/')
          .substringAfterLast(':')
      } else {
        skinId.substringAfter(':')
      }
    return formatSkinName(tail)
  }

  private fun decodePercentEscapes(raw: String): String =
    buildString(raw.length) {
      var i = 0
      while (i < raw.length) {
        val ch = raw[i]
        if (ch == '%' && i + 2 < raw.length) {
          val byteVal = raw.substring(i + 1, i + 3).toIntOrNull(16)
          if (byteVal != null) {
            append(byteVal.toChar())
            i += 3
            continue
          }
        }
        append(ch)
        i++
      }
    }

  private fun formatSkinName(fileName: String): String =
    fileName
      .replace(WSZ_EXTENSION_REGEX, "")
      .replace('_', ' ')
      .trim()
      .ifEmpty { fileName }

  companion object {
    private const val SHOWCASE_PREFIX = "showcase:"
    private const val IMPORTED_PREFIX = "imported:"
    private const val SAF_PREFIX = "saf:"
    private val WSZ_EXTENSION_REGEX = Regex("\\.wsz$", RegexOption.IGNORE_CASE)

    val BUILT_IN_ENTRY =
      SkinEntry(
        id = ClassicDroidAmpSkin.id,
        displayName = ClassicDroidAmpSkin.displayName,
        source = SkinSource.BUILT_IN,
        origin = SkinOrigin.BUILT_IN,
        author = ClassicDroidAmpSkin.author,
      )
  }
}
