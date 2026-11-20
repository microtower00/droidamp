package com.droidamp.player.storage

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import com.droidamp.player.domain.model.StorageBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SwitchingMusicSourceRepositoryTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private class StubSafGateway : SafTreeGateway {
    val accessible = linkedSetOf<String>()
    val roots = mutableMapOf<String, SafDocumentNode>()
    val children = mutableMapOf<String, MutableList<SafDocumentNode>>()
    val textFiles = mutableMapOf<String, String>()

    fun seedTree(
      treeUri: String,
      rootDocUri: String,
      name: String,
      trackName: String,
    ) {
      accessible.add(treeUri)
      roots[treeUri] = SafDocumentNode(rootDocUri, name, true)
      children[rootDocUri] =
        mutableListOf(
          SafDocumentNode("$rootDocUri%2F$trackName", trackName, false, "audio/mpeg"),
        )
    }

    override fun takePersistablePermission(treeUriString: String): Boolean = treeUriString in accessible

    override fun releasePersistablePermission(treeUriString: String) {}

    override fun getPersistedReadTreeUris(): Set<String> = accessible.toSet()

    override fun isTreeAccessible(treeUriString: String): Boolean = treeUriString in accessible

    override fun getTreeRootNode(treeUriString: String): SafDocumentNode? = roots[treeUriString]

    override fun listChildren(
      treeUriString: String,
      directoryUriString: String,
    ): List<SafDocumentNode>? =
      if (treeUriString in accessible) children[directoryUriString]?.toList() else null

    override fun readTextFile(documentUriString: String): String? = textFiles[documentUriString]

    override fun writeTextFile(
      treeUriString: String,
      directoryUriString: String,
      fileName: String,
      mimeType: String,
      content: String,
    ): SafDocumentNode? {
      val node = SafDocumentNode("$directoryUriString%2F$fileName", fileName, false, mimeType)
      children.getOrPut(directoryUriString) { mutableListOf() }.add(node)
      textFiles[node.uriString] = content
      return node
    }
  }

  private class StubMediaStoreGateway : MediaStoreGateway {
    var granted: Boolean = true
    val records = mutableListOf<MediaStoreAudioRecord>()
    val playlistFiles = mutableListOf<MediaStorePlaylistFileRecord>()
    val textFiles = mutableMapOf<String, String>()

    override fun hasAudioReadPermission(): Boolean = granted

    override fun queryAudioTracks(): List<MediaStoreAudioRecord> =
      if (granted) records.toList() else emptyList()

    override fun queryPlaylists(): List<MediaStorePlaylistRecord> = emptyList()

    override fun queryPlaylistFiles(): List<MediaStorePlaylistFileRecord> =
      if (granted) playlistFiles.toList() else emptyList()

    override fun readTextFile(uriOrPath: String): String? = textFiles[uriOrPath]

    override fun writeM3u8PlaylistFile(
      fileName: String,
      content: String,
    ): MediaStorePlaylistFileRecord {
      val path = "/app/files/playlists/$fileName"
      val record =
        MediaStorePlaylistFileRecord(
          id = "ms-file:$path",
          uriString = path,
          name = fileName,
          format = "m3u8",
          folderPath = "/app/files/playlists",
        )
      playlistFiles.removeAll { it.id == record.id }
      playlistFiles.add(record)
      textFiles[path] = content
      return record
    }
  }

  private fun createPreferencesStore(
    file: File,
    scope: CoroutineScope,
  ): StoragePreferencesStore {
    val ds =
      PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { file },
      )
    return StoragePreferencesStore(ds)
  }

  @Test
  fun defaultsToSaf_switchesDynamically_andRestoresPersistedBackendWithoutRace() = runTest {
    val prefsFile = File(tempFolder.root, "switching.preferences_pb")
    val safGateway = StubSafGateway().apply {
      seedTree(
        treeUri = "content://tree/safMusic",
        rootDocUri = "content://tree/safMusic/document/root",
        name = "SafMusic",
        trackName = "saf_track.mp3",
      )
    }
    val msGateway = StubMediaStoreGateway().apply {
      records.add(
        MediaStoreAudioRecord(
          mediaId = 7L,
          contentUriString = "content://media/external/audio/media/7",
          displayName = "ms_track.mp3",
          title = "MS Track",
          artist = "MS Artist",
          album = "",
          durationMs = 90_000L,
          relativePath = "Music/",
        ),
      )
    }
    val extractor = AudioMetadataExtractor(probe = object : AudioMetadataProbe {
      override fun probe(uriString: String): ProbedAudioMetadata? = null
    })

    val scope1 = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val prefs1 = createPreferencesStore(prefsFile, scope1)
    val safRepo1 = SafMusicSourceRepository(safGateway, prefs1, extractor, scope = scope1)
    val msRepo1 = MediaStoreMusicSourceRepository(msGateway, extractor)
    val switching1 = SwitchingMusicSourceRepository(safRepo1, msRepo1, prefs1, scope1)

    assertEquals(StorageBackend.SAF, switching1.storageBackend.value)
    assertTrue(switching1.linkedRoots.value.isEmpty())

    // Link SAF root
    switching1.linkSafRoot("content://tree/safMusic")
    assertEquals(1, switching1.linkedRoots.value.size)
    assertEquals("SafMusic", switching1.linkedRoots.value[0].name)
    assertEquals("saf_track.mp3", switching1.browseFolder(null).tracks[0].fileName)

    val savedSafPl = switching1.saveM3u8Playlist("saf_mix", "#EXTM3U\n")
    assertTrue(savedSafPl.isSuccess)

    // Switch to MediaStore
    switching1.setStorageBackend(StorageBackend.MEDIA_STORE)
    assertEquals(StorageBackend.MEDIA_STORE, switching1.storageBackend.value)
    assertEquals("Device Audio", switching1.linkedRoots.value.first().name)
    val msRoot = switching1.browseFolder(null)
    assertEquals("Music", msRoot.folders.single().name)
    assertEquals(1, switching1.resolveFolderTracks(msRoot.folders.single().uriString, true).size)

    val savedMsPl =
      switching1.saveM3u8Playlist(
        "ms_mix",
        "#EXTM3U\ncontent://media/external/audio/media/7\n",
      ).getOrThrow()
    assertEquals(1, savedMsPl.trackCount)
    assertEquals(listOf(savedMsPl.id), switching1.listPlaylists().map { it.id })
    assertEquals(listOf("ms-7"), switching1.resolvePlaylistTracks(savedMsPl.id).map { it.id })

    scope1.coroutineContext.job.cancelAndJoin()

    // Recreate repository on fresh DataStore instance -> browseFolder(null) immediately uses persisted MEDIA_STORE without race
    val prefs2 = createPreferencesStore(prefsFile, backgroundScope)
    val safRepo2 = SafMusicSourceRepository(safGateway, prefs2, extractor, scope = backgroundScope)
    val msRepo2 = MediaStoreMusicSourceRepository(msGateway, extractor)
    val switching2 = SwitchingMusicSourceRepository(safRepo2, msRepo2, prefs2, backgroundScope)

    val restoredBrowse = switching2.browseFolder(null)
    assertEquals(StorageBackend.MEDIA_STORE, switching2.storageBackend.value)
    assertEquals(MediaStoreMusicSourceRepository.MEDIASTORE_ROOT_URI, restoredBrowse.currentFolderUri)

    // Switch back to SAF -> persisted SAF root is still intact and unlinkAllRoots clears it
    switching2.setStorageBackend(StorageBackend.SAF)
    assertEquals(StorageBackend.SAF, switching2.storageBackend.value)
    assertEquals("saf_track.mp3", switching2.browseFolder(null).tracks[0].fileName)

    switching2.unlinkAllRoots()
    assertTrue(switching2.linkedRoots.value.isEmpty())
  }
}
