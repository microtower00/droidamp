package com.droidamp.player.ui.rack

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.contract.EqfCodec
import com.droidamp.player.domain.contract.Q2Codec
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.interop.BinaryEqfCodec
import com.droidamp.player.interop.WinampPresetLibrary
import com.droidamp.player.interop.WinampQ2Codec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class AutoEqCatalog(
  val rules: List<AutoEqRule> = emptyList(),
  val extraPresets: List<EqPreset> = emptyList(),
)

interface AutoEqCatalogProvider {
  suspend fun loadCatalog(
    linkedRootUris: List<String>,
    track: AudioTrack?,
  ): AutoEqCatalog
}

object EmptyAutoEqCatalogProvider : AutoEqCatalogProvider {
  override suspend fun loadCatalog(
    linkedRootUris: List<String>,
    track: AudioTrack?,
  ): AutoEqCatalog = AutoEqCatalog()
}

/**
 * Discovers `winamp.q2`, `winamp.q1`, and `*.eqf` files from app-private files,
 * linked SAF root folders, and the active track's parent folder.
 */
class AndroidAutoEqCatalogProvider(
  context: Context,
  private val q2Codec: Q2Codec = WinampQ2Codec(),
  private val eqfCodec: EqfCodec = BinaryEqfCodec(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoEqCatalogProvider {
  private val appContext = context.applicationContext
  private val resolver = appContext.contentResolver

  override suspend fun loadCatalog(
    linkedRootUris: List<String>,
    track: AudioTrack?,
  ): AutoEqCatalog =
    withContext(ioDispatcher) {
      val rules = ArrayList<AutoEqRule>()
      val extraPresets = ArrayList<EqPreset>()

      // 1. App-private directory (`filesDir`)
      scanLocalDirectory(
        dir = appContext.filesDir,
        track = track,
        folderPatternOverride = null,
        rulesOut = rules,
        presetsOut = extraPresets,
      )

      // 2. Linked SAF root folders
      for (rootUriString in linkedRootUris) {
        if (!rootUriString.startsWith("content://")) continue
        val treeUri = runCatching { Uri.parse(rootUriString) }.getOrNull() ?: continue
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
        scanSafDirectory(
          treeUri = treeUri,
          directoryDocId = rootDocId,
          track = track,
          folderPattern = rootDocId.substringAfterLast(':').substringAfterLast('/'),
          rulesOut = rules,
          presetsOut = extraPresets,
        )
      }

      // 3. Active track's parent folder (SAF document parent or local file parent)
      if (track != null && track.uriString.isNotBlank()) {
        scanTrackParentFolder(
          track = track,
          linkedRootUris = linkedRootUris,
          rulesOut = rules,
          presetsOut = extraPresets,
        )
      }

      AutoEqCatalog(
        rules = rules.distinctBy { "${it.pattern.lowercase(Locale.ROOT)}|${it.preset.name.lowercase(Locale.ROOT)}" },
        extraPresets = WinampPresetLibrary.mergePresets(extraPresets, emptyList()),
      )
    }

  private fun scanTrackParentFolder(
    track: AudioTrack,
    linkedRootUris: List<String>,
    rulesOut: MutableList<AutoEqRule>,
    presetsOut: MutableList<EqPreset>,
  ) {
    val trackUri = runCatching { Uri.parse(track.uriString) }.getOrNull() ?: return
    val scheme = trackUri.scheme?.lowercase(Locale.ROOT)
    if (scheme == null || scheme == "file") {
      val path = if (scheme == "file") trackUri.path else track.uriString
      val parentDir = path?.let(::File)?.parentFile
      if (parentDir != null && parentDir.isDirectory && parentDir != appContext.filesDir) {
        scanLocalDirectory(
          dir = parentDir,
          track = track,
          folderPatternOverride = track.folderPath.ifBlank { parentDir.name },
          rulesOut = rulesOut,
          presetsOut = presetsOut,
        )
      }
      return
    }

    if (scheme == "content") {
      val docId = runCatching { DocumentsContract.getDocumentId(trackUri) }.getOrNull() ?: return
      val colonIdx = docId.indexOf(':')
      val slashIdx = docId.lastIndexOf('/')
      if (slashIdx <= colonIdx) return // Already scanned at root if directly under root
      val parentDocId = docId.substring(0, slashIdx)
      val matchingTreeUri =
        linkedRootUris
          .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
          .firstOrNull { rootUri ->
            rootUri.authority == trackUri.authority &&
              track.uriString.startsWith(rootUri.toString().substringBefore("/document/"))
          }
          ?: return
      scanSafDirectory(
        treeUri = matchingTreeUri,
        directoryDocId = parentDocId,
        track = track,
        folderPattern = track.folderPath.ifBlank { parentDocId.substringAfterLast('/') },
        rulesOut = rulesOut,
        presetsOut = presetsOut,
      )
    }
  }

  private fun scanLocalDirectory(
    dir: File,
    track: AudioTrack?,
    folderPatternOverride: String?,
    rulesOut: MutableList<AutoEqRule>,
    presetsOut: MutableList<EqPreset>,
  ) {
    val files = runCatching { dir.listFiles() }.getOrNull() ?: return
    for (file in files) {
      if (!file.isFile) continue
      val nameLower = file.name.lowercase(Locale.ROOT)
      if (!isRelevantEqFile(nameLower)) continue
      val bytes = readBoundedBytes { file.inputStream() } ?: continue
      ingestEqBytes(
        fileName = file.name,
        bytes = bytes,
        track = track,
        folderPattern = folderPatternOverride,
        rulesOut = rulesOut,
        presetsOut = presetsOut,
      )
    }
  }

  private fun scanSafDirectory(
    treeUri: Uri,
    directoryDocId: String,
    track: AudioTrack?,
    folderPattern: String?,
    rulesOut: MutableList<AutoEqRule>,
    presetsOut: MutableList<EqPreset>,
  ) {
    val childrenUri =
      runCatching { DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryDocId) }
        .getOrNull()
        ?: return
    val projection =
      arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
      )
    val cursor =
      runCatching { resolver.query(childrenUri, projection, null, null, null) }.getOrNull()
        ?: return

    cursor.use {
      val idCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      val nameCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      val mimeCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
      if (idCol < 0 || nameCol < 0) return

      while (it.moveToNext()) {
        val docId = it.getString(idCol) ?: continue
        val name = it.getString(nameCol)?.takeIf { n -> n.isNotBlank() } ?: continue
        val mime = if (mimeCol >= 0) it.getString(mimeCol) else null
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
        if (!isRelevantEqFile(name.lowercase(Locale.ROOT))) continue

        val docUri =
          runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, docId) }
            .getOrNull()
            ?: continue
        val bytes = readBoundedBytes { resolver.openInputStream(docUri) } ?: continue
        ingestEqBytes(
          fileName = name,
          bytes = bytes,
          track = track,
          folderPattern = folderPattern,
          rulesOut = rulesOut,
          presetsOut = presetsOut,
        )
      }
    }
  }

  private fun ingestEqBytes(
    fileName: String,
    bytes: ByteArray,
    track: AudioTrack?,
    folderPattern: String?,
    rulesOut: MutableList<AutoEqRule>,
    presetsOut: MutableList<EqPreset>,
  ) {
    val nameLower = fileName.lowercase(Locale.ROOT)
    when {
      nameLower == WINAMP_Q2_NAME -> {
        runCatching { q2Codec.parseAutoPresetsQ2(bytes) }
          .getOrNull()
          ?.let { parsed -> rulesOut.addAll(parsed) }
      }
      nameLower == WINAMP_Q1_NAME -> {
        runCatching { eqfCodec.parsePresetLibraryQ1(bytes) }
          .getOrNull()
          ?.let { parsed -> presetsOut.addAll(parsed) }
      }
      nameLower.endsWith(".eqf") -> {
        val preset = runCatching { eqfCodec.parseSingleEqf(bytes) }.getOrNull() ?: return
        presetsOut.add(preset)

        val stem = fileName.substringBeforeLast('.').lowercase(Locale.ROOT)
        val trackStem = track?.fileName?.substringBeforeLast('.')?.lowercase(Locale.ROOT)
        if (!trackStem.isNullOrBlank() && stem == trackStem) {
          rulesOut.add(AutoEqRule(pattern = track.fileName, preset = preset))
        } else if (!folderPattern.isNullOrBlank()) {
          val folderLeaf = folderPattern.trim('/').substringAfterLast('/').lowercase(Locale.ROOT)
          if (stem == "winamp" || stem == "auto" || stem == "default" ||
            (folderLeaf.isNotEmpty() && stem == folderLeaf)
          ) {
            rulesOut.add(AutoEqRule(pattern = folderPattern, preset = preset))
          }
        }
      }
    }
  }

  private fun isRelevantEqFile(nameLower: String): Boolean =
    nameLower == WINAMP_Q2_NAME || nameLower == WINAMP_Q1_NAME || nameLower.endsWith(".eqf")

  private inline fun readBoundedBytes(openStream: () -> java.io.InputStream?): ByteArray? =
    runCatching {
      openStream()?.use { input ->
        val buffer = ByteArray(MAX_EQ_FILE_BYTES + 1)
        var total = 0
        while (total < buffer.size) {
          val read = input.read(buffer, total, buffer.size - total)
          if (read <= 0) break
          total += read
        }
        if (total > MAX_EQ_FILE_BYTES) null else buffer.copyOf(total)
      }
    }.getOrNull()

  private companion object {
    const val WINAMP_Q1_NAME = "winamp.q1"
    const val WINAMP_Q2_NAME = "winamp.q2"
    const val MAX_EQ_FILE_BYTES = 2 * 1024 * 1024
  }
}
