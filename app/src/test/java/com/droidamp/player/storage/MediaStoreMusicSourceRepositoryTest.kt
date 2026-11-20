package com.droidamp.player.storage

import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.StorageBackend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaStoreMusicSourceRepositoryTest {

  private class FakeMediaStoreGateway(
    var granted: Boolean = true,
  ) : MediaStoreGateway {
    val audioRecords = mutableListOf<MediaStoreAudioRecord>()
    val playlists = mutableListOf<MediaStorePlaylistRecord>()
    val playlistFiles = mutableListOf<MediaStorePlaylistFileRecord>()
    val textFiles = mutableMapOf<String, String>()

    override fun hasAudioReadPermission(): Boolean = granted

    override fun queryAudioTracks(): List<MediaStoreAudioRecord> =
      if (granted) audioRecords.toList() else emptyList()

    override fun queryPlaylists(): List<MediaStorePlaylistRecord> =
      if (granted) playlists.toList() else emptyList()

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

  private fun createExtractor(): AudioMetadataExtractor =
    AudioMetadataExtractor(
      probe =
        object : AudioMetadataProbe {
          override fun probe(uriString: String): ProbedAudioMetadata? = null
        },
    )

  @Test
  fun permissionDenied_degradesGracefullyToEmptyState() = runTest {
    val gateway = FakeMediaStoreGateway(granted = false)
    gateway.audioRecords.add(
      MediaStoreAudioRecord(
        mediaId = 1L,
        contentUriString = "content://media/external/audio/media/1",
        displayName = "song.mp3",
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationMs = 120_000L,
        relativePath = "Music/",
      ),
    )
    val repo = MediaStoreMusicSourceRepository(gateway, createExtractor())

    assertEquals(StorageBackend.MEDIA_STORE, repo.storageBackend.value)
    assertEquals(FolderContents.EMPTY, repo.browseFolder(null))
    assertTrue(repo.linkedRoots.value.isEmpty())
    assertTrue(repo.resolveFolderTracks(MediaStoreMusicSourceRepository.MEDIASTORE_ROOT_URI, true).isEmpty())
    assertTrue(repo.listPlaylists().isEmpty())
    assertTrue(repo.resolvePlaylistTracks("ms-pl:1").isEmpty())
  }

  @Test
  fun virtualFolderHierarchy_supportsRelativePathLegacyDataAndUnknownSanitization() = runTest {
    val gateway = FakeMediaStoreGateway(granted = true)
    gateway.audioRecords.addAll(
      listOf(
        MediaStoreAudioRecord(
          mediaId = 10L,
          contentUriString = "content://media/external/audio/media/10",
          displayName = "The Doors - L.A. Woman.mp3",
          title = "",
          artist = "<unknown>",
          album = "<unknown>",
          durationMs = 469_000L,
          relativePath = "Music/Rock/",
        ),
        MediaStoreAudioRecord(
          mediaId = 20L,
          contentUriString = "content://media/external/audio/media/20",
          displayName = "01 - Nightcall.mp3",
          title = "Nightcall",
          artist = "Kavinsky",
          album = "OutRun",
          durationMs = 258_000L,
          relativePath = "",
          dataPath = "/storage/emulated/0/Music/Synthwave/01 - Nightcall.mp3",
        ),
      ),
    )

    val repo = MediaStoreMusicSourceRepository(gateway, createExtractor())
    val root = repo.browseFolder(null)
    assertEquals(MediaStoreMusicSourceRepository.MEDIASTORE_ROOT_URI, root.currentFolderUri)
    assertEquals(listOf("MEDIASTORE"), root.currentPathSegments)
    assertFalse(root.canNavigateUp)
    assertNull(root.parentFolderUri)
    assertEquals(1, repo.linkedRoots.value.size)
    assertEquals(1, root.folders.size)
    assertEquals("Music", root.folders[0].name)

    val musicFolder = repo.browseFolder(root.folders[0].uriString)
    assertTrue(musicFolder.canNavigateUp)
    assertEquals(MediaStoreMusicSourceRepository.MEDIASTORE_ROOT_URI, musicFolder.parentFolderUri)
    assertEquals(listOf("MEDIASTORE", "Music"), musicFolder.currentPathSegments)
    assertEquals(listOf("Rock", "Synthwave"), musicFolder.folders.map { it.name })

    val rockFolder = repo.browseFolder(musicFolder.folders.first { it.name == "Rock" }.uriString)
    assertEquals(musicFolder.currentFolderUri, rockFolder.parentFolderUri)
    assertEquals(1, rockFolder.tracks.size)
    val track = rockFolder.tracks[0]
    assertEquals("The Doors", track.artist)
    assertEquals("L.A. Woman", track.title)
    assertEquals("Music/Rock", track.folderPath)

    // Recursive vs non-recursive
    val musicUri = requireNotNull(musicFolder.currentFolderUri)
    assertEquals(0, repo.resolveFolderTracks(musicUri, recursive = false).size)
    assertEquals(2, repo.resolveFolderTracks(musicUri, recursive = true).size)
  }

  @Test
  fun playlistsAndM3u8Save_resolveMediaStoreAndFilePlaylists() = runTest {
    val gateway = FakeMediaStoreGateway(granted = true)
    gateway.audioRecords.addAll(
      listOf(
        MediaStoreAudioRecord(
          mediaId = 1L,
          contentUriString = "content://media/external/audio/media/1",
          displayName = "one.mp3",
          title = "One",
          artist = "Artist",
          album = "Album",
          durationMs = 100_000L,
          relativePath = "Music/Rock/",
          dataPath = "/storage/emulated/0/Music/Rock/one.mp3",
        ),
        MediaStoreAudioRecord(
          mediaId = 2L,
          contentUriString = "content://media/external/audio/media/2",
          displayName = "two.mp3",
          title = "Two",
          artist = "Artist",
          album = "Album",
          durationMs = 200_000L,
          relativePath = "Music/Jazz/",
          dataPath = "/storage/emulated/0/Music/Jazz/two.mp3",
        ),
      ),
    )
    gateway.playlists.add(
      MediaStorePlaylistRecord(
        playlistId = 55L,
        contentUriString = "content://media/external/audio/playlists/55",
        name = "MediaStore Favs",
        memberTrackIds = listOf(2L, 1L, 999L),
      ),
    )
    val plsPath = "/storage/emulated/0/Music/road.pls"
    gateway.playlistFiles.add(
      MediaStorePlaylistFileRecord(
        id = "ms-plfile:9",
        uriString = plsPath,
        name = "road.pls",
        format = "pls",
        folderPath = "/storage/emulated/0/Music",
      ),
    )
    gateway.textFiles[plsPath] =
      """
      [playlist]
      File1=Rock/one.mp3
      File2=Missing/ghost.mp3
      File3=Jazz/two.mp3
      """.trimIndent()

    val repo = MediaStoreMusicSourceRepository(gateway, createExtractor())
    val playlists = repo.listPlaylists()
    assertEquals(2, playlists.size)

    val msEntry = playlists.first { it.id == "ms-pl:55" }
    assertEquals(2, msEntry.trackCount)
    assertEquals(300_000L, msEntry.totalDurationMs)
    assertEquals(listOf("ms-2", "ms-1"), repo.resolvePlaylistTracks(msEntry.id).map { it.id })

    val plsEntry = playlists.first { it.id == "ms-plfile:9" }
    assertEquals("pls", plsEntry.format)
    assertEquals(2, plsEntry.trackCount)
    assertEquals(listOf("ms-1", "ms-2"), repo.resolvePlaylistTracks(plsEntry.id).map { it.id })

    val saveResult =
      repo.saveM3u8Playlist(
        "Exported",
        "#EXTM3U\ncontent://media/external/audio/media/1\n",
      )
    assertTrue(saveResult.isSuccess)
    val saved = saveResult.getOrThrow()
    assertEquals("Exported", saved.name)
    assertEquals(1, saved.trackCount)
    assertEquals(100_000L, saved.totalDurationMs)
    assertNotNull(repo.listPlaylists().firstOrNull { it.id == saved.id })
  }

  @Test
  fun emulatedStorageRootAudioWithEmptyRelativePath_isPlacedAtRootNotSpuriousFolders() = runTest {
    val gateway = FakeMediaStoreGateway(granted = true)
    gateway.audioRecords.addAll(
      listOf(
        MediaStoreAudioRecord(
          mediaId = 11L,
          contentUriString = "content://media/external/audio/media/11",
          displayName = "emulated_root.mp3",
          title = "Emulated Root",
          artist = "Artist",
          album = "",
          durationMs = 60_000L,
          relativePath = "",
          dataPath = "/storage/emulated/0/emulated_root.mp3",
        ),
        MediaStoreAudioRecord(
          mediaId = 12L,
          contentUriString = "content://media/external/audio/media/12",
          displayName = "sdcard_root.mp3",
          title = "SDCard Root",
          artist = "Artist",
          album = "",
          durationMs = 60_000L,
          relativePath = "",
          dataPath = "/sdcard/sdcard_root.mp3",
        ),
      ),
    )

    val repo = MediaStoreMusicSourceRepository(gateway, createExtractor())
    val root = repo.browseFolder(null)
    assertTrue(root.folders.isEmpty())
    assertEquals(listOf("emulated_root.mp3", "sdcard_root.mp3"), root.tracks.map { it.fileName })
    assertTrue(root.tracks.all { it.folderPath == "MEDIASTORE" })
  }

  @Test
  fun playlistResolution_prefersExactAndLongestSuffixMatchOverFirstShorterSuffix() = runTest {
    val gateway = FakeMediaStoreGateway(granted = true)
    gateway.audioRecords.addAll(
      listOf(
        MediaStoreAudioRecord(
          mediaId = 101L,
          contentUriString = "content://media/external/audio/media/101",
          displayName = "intro.mp3",
          title = "Rock Intro",
          artist = "Rock",
          album = "",
          durationMs = 100_000L,
          relativePath = "Rock/",
          dataPath = "/storage/emulated/0/Rock/intro.mp3",
        ),
        MediaStoreAudioRecord(
          mediaId = 102L,
          contentUriString = "content://media/external/audio/media/102",
          displayName = "intro.mp3",
          title = "Pop Rock Intro",
          artist = "Pop",
          album = "",
          durationMs = 150_000L,
          relativePath = "Pop/Rock/",
          dataPath = "/storage/emulated/0/Pop/Rock/intro.mp3",
        ),
      ),
    )

    val repo = MediaStoreMusicSourceRepository(gateway, createExtractor())
    val content =
      """
      #EXTM3U
      Pop/Rock/intro.mp3
      C:\Users\me\Pop\Rock\intro.mp3
      file:///storage/emulated/0/Pop/Rock/intro.mp3
      """.trimIndent()
    val saved = repo.saveM3u8Playlist("collision", content).getOrThrow()

    assertEquals(3, saved.trackCount)
    assertEquals(450_000L, saved.totalDurationMs)
    assertEquals(listOf("ms-102", "ms-102", "ms-102"), repo.resolvePlaylistTracks(saved.id).map { it.id })
  }

  @Test
  fun playlistResolution_decodesUtf8PercentEncoding_stripsMountAliases_andDropsNonFileUrls() = runTest {
    val gateway = FakeMediaStoreGateway(granted = true)
    gateway.audioRecords.addAll(
      listOf(
        MediaStoreAudioRecord(
          mediaId = 104L,
          contentUriString = "content://media/external/audio/media/104",
          displayName = "résumé.mp3",
          title = "Résumé",
          artist = "Café",
          album = "",
          durationMs = 120_000L,
          relativePath = "Music/Café/",
          dataPath = "/storage/emulated/0/Music/Café/résumé.mp3",
        ),
        MediaStoreAudioRecord(
          mediaId = 105L,
          contentUriString = "content://media/external/audio/media/105",
          displayName = "emulated_root.mp3",
          title = "Emulated Root",
          artist = "Artist",
          album = "",
          durationMs = 60_000L,
          relativePath = "",
          dataPath = "/storage/emulated/0/emulated_root.mp3",
        ),
        MediaStoreAudioRecord(
          mediaId = 106L,
          contentUriString = "content://media/external/audio/media/106",
          displayName = "Bones+All.mp3",
          title = "Bones+All",
          artist = "Artist",
          album = "",
          durationMs = 90_000L,
          relativePath = "Music/",
          dataPath = "/storage/emulated/0/Music/Bones+All.mp3",
        ),
        MediaStoreAudioRecord(
          mediaId = 107L,
          contentUriString = "content://media/external/audio/media/107",
          displayName = "intro.mp3",
          title = "Pop Rock Intro",
          artist = "Pop",
          album = "",
          durationMs = 150_000L,
          relativePath = "Pop/Rock/",
          dataPath = "/storage/emulated/0/Pop/Rock/intro.mp3",
        ),
      ),
    )

    val repo = MediaStoreMusicSourceRepository(gateway, createExtractor())
    val content =
      """
      #EXTM3U
      file:///storage/emulated/0/Music/Caf%C3%A9/r%C3%A9sum%C3%A9.mp3
      file:///storage/self/primary/emulated_root.mp3
      file:///mnt/sdcard/emulated_root.mp3
      file:///storage/self/primary/Pop/Rock/intro.mp3
      file:///storage/emulated/0/Music/Bones%2BAll.mp3
      file:///storage/emulated/0/Music/Bones+All.mp3
      https://cdn.example.com/Pop/Rock/intro.mp3
      content://media/external/audio/media/999
      """.trimIndent()
    val saved = repo.saveM3u8Playlist("utf8_and_mounts", content).getOrThrow()

    assertEquals(6, saved.trackCount)
    assertEquals(570_000L, saved.totalDurationMs)
    assertEquals(
      listOf("ms-104", "ms-105", "ms-105", "ms-107", "ms-106", "ms-106"),
      repo.resolvePlaylistTracks(saved.id).map { it.id },
    )
  }
}
