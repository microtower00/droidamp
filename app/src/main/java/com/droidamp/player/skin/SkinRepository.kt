package com.droidamp.player.skin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.droidamp.player.skin.wsz.WszArchiveParser
import com.droidamp.player.skin.wsz.WszSkinLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class SkinSource {
  BUILT_IN,
  IMPORTED,
  SAF_FOLDER,
}

data class SkinEntry(
  val id: String,
  val displayName: String,
  val source: SkinSource,
  val uriOrPath: String? = null,
)

interface SkinRepository {
  val availableSkins: StateFlow<List<SkinEntry>>
  val activeSkin: StateFlow<DroidAmpSkin>

  fun refreshSkins(linkedRootUris: List<String> = emptyList())
  fun selectSkin(skinId: String)
  suspend fun importSkinFromUri(uriString: String): Result<SkinEntry>
}

class StaticSkinRepository(
  initialSkin: DroidAmpSkin = ClassicDroidAmpSkin,
) : SkinRepository {
  private val _availableSkins =
    MutableStateFlow(
      listOf(
        SkinEntry(
          id = ClassicDroidAmpSkin.id,
          displayName = ClassicDroidAmpSkin.displayName,
          source = SkinSource.BUILT_IN,
        ),
      ),
    )
  override val availableSkins: StateFlow<List<SkinEntry>> = _availableSkins.asStateFlow()

  private val _activeSkin = MutableStateFlow(initialSkin)
  override val activeSkin: StateFlow<DroidAmpSkin> = _activeSkin.asStateFlow()

  override fun refreshSkins(linkedRootUris: List<String>) = Unit
  override fun selectSkin(skinId: String) = Unit
  override suspend fun importSkinFromUri(uriString: String): Result<SkinEntry> =
    Result.failure(UnsupportedOperationException("Skin import unavailable in preview"))
}

/**
 * Manages built-in [ClassicDroidAmpSkin], locally imported `.wsz` archives (`filesDir/skins/`),
 * and shallow SAF-discovered `.wsz` files (depth <= 1: root + `Skins/` subfolder).
 */
class AndroidSkinRepository(
  private val appContext: Context,
  private val preferencesStore: SkinPreferencesStore,
  private val scope: CoroutineScope,
  private val loader: WszSkinLoader = WszSkinLoader(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SkinRepository {
  private val skinsDir = File(appContext.filesDir, "skins")
  private val decodedCache = LinkedHashMap<String, DroidAmpSkin>()

  private val _availableSkins = MutableStateFlow(listOf(BUILT_IN_ENTRY))
  override val availableSkins: StateFlow<List<SkinEntry>> = _availableSkins.asStateFlow()

  private val _activeSkin = MutableStateFlow<DroidAmpSkin>(ClassicDroidAmpSkin)
  override val activeSkin: StateFlow<DroidAmpSkin> = _activeSkin.asStateFlow()

  private var lastLinkedRoots: List<String> = emptyList()

  init {
    scope.launch {
      val savedId = runCatching { preferencesStore.loadActiveSkinId() }.getOrDefault(ClassicDroidAmpSkin.id)
      val discovered = scanSkins(lastLinkedRoots)
      _availableSkins.value = discovered
      applySkinById(savedId, discovered)
    }
  }

  override fun refreshSkins(linkedRootUris: List<String>) {
    lastLinkedRoots = linkedRootUris
    scope.launch {
      val discovered = scanSkins(linkedRootUris)
      _availableSkins.value = discovered
      val savedId = runCatching { preferencesStore.loadActiveSkinId() }.getOrDefault(_activeSkin.value.id)
      if (_activeSkin.value.id != savedId && discovered.any { it.id == savedId }) {
        applySkinById(savedId, discovered)
      }
    }
  }

  override fun selectSkin(skinId: String) {
    scope.launch {
      val list = _availableSkins.value
      applySkinById(skinId, list)
      runCatching { preferencesStore.saveActiveSkinId(_activeSkin.value.id) }
    }
  }

  override suspend fun importSkinFromUri(uriString: String): Result<SkinEntry> =
    withContext(ioDispatcher) {
      runCatching {
        val uri = Uri.parse(uriString)
        val rawBytes =
          appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to read selected skin file")
        require(WszArchiveParser.hasZipMagic(rawBytes)) {
          "Selected file is not a valid .wsz / .zip archive"
        }

        val rawName = resolveDisplayFileName(uri) ?: "imported_skin.wsz"
        val cleanBase =
          rawName
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .ifEmpty { "custom_skin" }
        val fileName = "$cleanBase.wsz"
        if (!skinsDir.exists()) skinsDir.mkdirs()
        val destFile = File(skinsDir, fileName)
        destFile.writeBytes(rawBytes)

        val id = "imported:$fileName"
        val decoded = loader.load(id = id, displayName = formatSkinName(fileName), zipBytes = rawBytes)
        decodedCache[id] = decoded

        val refreshed = scanSkins(lastLinkedRoots)
        _availableSkins.value = refreshed
        _activeSkin.value = decoded
        preferencesStore.saveActiveSkinId(id)

        refreshed.firstOrNull { it.id == id }
          ?: SkinEntry(
            id = id,
            displayName = decoded.displayName,
            source = SkinSource.IMPORTED,
            uriOrPath = destFile.absolutePath,
          )
      }
    }

  private suspend fun applySkinById(
    skinId: String,
    entries: List<SkinEntry>,
  ) {
    if (skinId == ClassicDroidAmpSkin.id) {
      _activeSkin.value = ClassicDroidAmpSkin
      return
    }
    decodedCache[skinId]?.let { cached ->
      _activeSkin.value = cached
      return
    }
    val entry = entries.firstOrNull { it.id == skinId }
    if (entry == null) {
      _activeSkin.value = ClassicDroidAmpSkin
      return
    }

    val loaded =
      withContext(ioDispatcher) {
        runCatching {
          val bytes =
            when (entry.source) {
              SkinSource.BUILT_IN -> return@runCatching ClassicDroidAmpSkin
              SkinSource.IMPORTED -> entry.uriOrPath?.let { File(it).takeIf(File::isFile)?.readBytes() }
              SkinSource.SAF_FOLDER ->
                entry.uriOrPath?.let { uriStr ->
                  appContext.contentResolver.openInputStream(Uri.parse(uriStr))?.use { it.readBytes() }
                }
            } ?: return@runCatching null
          if (!WszArchiveParser.hasZipMagic(bytes)) return@runCatching null
          loader.load(id = entry.id, displayName = entry.displayName, zipBytes = bytes)
        }.getOrNull()
      }

    if (loaded != null) {
      decodedCache[entry.id] = loaded
      _activeSkin.value = loaded
    } else {
      _activeSkin.value = ClassicDroidAmpSkin
    }
  }

  private suspend fun scanSkins(linkedRootUris: List<String>): List<SkinEntry> =
    withContext(ioDispatcher) {
      val list = ArrayList<SkinEntry>()
      list.add(BUILT_IN_ENTRY)

      // 1. Locally imported skins in filesDir/skins/
      if (skinsDir.isDirectory) {
        skinsDir
          .listFiles { f -> f.isFile && f.name.lowercase(Locale.ROOT).endsWith(".wsz") }
          ?.sortedBy { it.name.lowercase(Locale.ROOT) }
          ?.forEach { file ->
            list.add(
              SkinEntry(
                id = "imported:${file.name}",
                displayName = formatSkinName(file.name),
                source = SkinSource.IMPORTED,
                uriOrPath = file.absolutePath,
              ),
            )
          }
      }

      // 2. Shallow SAF discovery (depth <= 1: root + optional Skins/ subfolder)
      val seenIds = list.map { it.id }.toHashSet()
      for (rootUriStr in linkedRootUris) {
        runCatching {
          val rootDoc = DocumentFile.fromTreeUri(appContext, Uri.parse(rootUriStr)) ?: return@runCatching
          val dirsToCheck = ArrayList<DocumentFile>()
          dirsToCheck.add(rootDoc)
          rootDoc.listFiles().firstOrNull { it.isDirectory && it.name.equals("skins", ignoreCase = true) }?.let {
            dirsToCheck.add(it)
          }
          for (dir in dirsToCheck) {
            dir.listFiles().forEach { child ->
              val name = child.name ?: return@forEach
              if (!child.isDirectory && name.lowercase(Locale.ROOT).endsWith(".wsz")) {
                val id = "saf:${child.uri}"
                if (seenIds.add(id)) {
                  list.add(
                    SkinEntry(
                      id = id,
                      displayName = formatSkinName(name),
                      source = SkinSource.SAF_FOLDER,
                      uriOrPath = child.uri.toString(),
                    ),
                  )
                }
              }
            }
          }
        }
      }

      list
    }

  private fun resolveDisplayFileName(uri: Uri): String? {
    runCatching {
      appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
          val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) return c.getString(idx)
        }
      }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
  }

  private fun formatSkinName(fileName: String): String =
    fileName
      .removeSuffix(".wsz")
      .removeSuffix(".WSZ")
      .replace('_', ' ')
      .trim()
      .ifEmpty { fileName }

  companion object {
    val BUILT_IN_ENTRY =
      SkinEntry(
        id = ClassicDroidAmpSkin.id,
        displayName = ClassicDroidAmpSkin.displayName,
        source = SkinSource.BUILT_IN,
      )
  }
}
