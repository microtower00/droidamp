package com.droidamp.player.storage

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.StorageBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SafMusicSourceRepositoryTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private class FakeSafTreeGateway : SafTreeGateway {
    val accessibleTrees = linkedSetOf<String>()
    val persistedReadUris = linkedSetOf<String>()
    val rejectedPermissionTrees = mutableSetOf<String>()
    val releasedUris = mutableListOf<String>()
    val rootNodes = mutableMapOf<String, SafDocumentNode>()
    val childrenByDirUri = mutableMapOf<String, MutableList<SafDocumentNode>>()
    val textFiles = mutableMapOf<String, String>()
    var failWrites: Boolean = false

    fun addTree(
      treeUri: String,
      rootDocUri: String,
      displayName: String,
    ) {
      accessibleTrees.add(treeUri)
      persistedReadUris.add(treeUri)
      rootNodes[treeUri] =
        SafDocumentNode(
          uriString = rootDocUri,
          name = displayName,
          isDirectory = true,
          mimeType = "vnd.android.document/directory",
        )
      childrenByDirUri.getOrPut(rootDocUri) { mutableListOf() }
    }

    fun addChild(
      parentDirUri: String,
      node: SafDocumentNode,
      textContent: String? = null,
    ) {
      childrenByDirUri.getOrPut(parentDirUri) { mutableListOf() }.add(node)
      if (node.isDirectory) {
        childrenByDirUri.getOrPut(node.uriString) { mutableListOf() }
      } else if (textContent != null) {
        textFiles[node.uriString] = textContent
      }
    }

    override fun takePersistablePermission(treeUriString: String): Boolean {
      if (treeUriString in rejectedPermissionTrees) return false
      persistedReadUris.add(treeUriString)
      return true
    }

    override fun releasePersistablePermission(treeUriString: String) {
      releasedUris.add(treeUriString)
      persistedReadUris.remove(treeUriString)
    }

    override fun getPersistedReadTreeUris(): Set<String> = persistedReadUris.toSet()

    override fun isTreeAccessible(treeUriString: String): Boolean = treeUriString in accessibleTrees

    override fun getTreeRootNode(treeUriString: String): SafDocumentNode? =
      if (treeUriString in accessibleTrees) rootNodes[treeUriString] else null

    override fun listChildren(
      treeUriString: String,
      directoryUriString: String,
    ): List<SafDocumentNode>? {
      if (treeUriString !in accessibleTrees) return null
      return childrenByDirUri[directoryUriString]?.toList()
    }

    override fun readTextFile(documentUriString: String): String? = textFiles[documentUriString]

    override fun writeTextFile(
      treeUriString: String,
      directoryUriString: String,
      fileName: String,
      mimeType: String,
      content: String,
    ): SafDocumentNode? {
      if (failWrites || treeUriString !in accessibleTrees) return null
      val list = childrenByDirUri.getOrPut(directoryUriString) { mutableListOf() }
      val existing = list.firstOrNull { !it.isDirectory && it.name.equals(fileName, ignoreCase = true) }
      val node =
        existing ?: SafDocumentNode(
          uriString = "$directoryUriString%2F$fileName",
          name = fileName,
          isDirectory = false,
          mimeType = mimeType,
        ).also { list.add(it) }
      textFiles[node.uriString] = content
      return node
    }
  }

  private fun createPreferencesStore(
    name: String,
    scope: CoroutineScope,
  ): StoragePreferencesStore {
    val ds =
      PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { File(tempFolder.root, name) },
      )
    return StoragePreferencesStore(ds)
  }

  private fun createExtractor(durations: Map<String, Long> = emptyMap()): AudioMetadataExtractor =
    AudioMetadataExtractor(
      probe =
        object : AudioMetadataProbe {
          override fun probe(uriString: String): ProbedAudioMetadata? =
            ProbedAudioMetadata(durationMs = durations[uriString] ?: 180_000L)
        },
    )

  @Test
  fun unlinkedInitialState_returnsEmptyRootsAndFolderContents() = runTest {
    val gateway = FakeSafTreeGateway()
    val prefs = createPreferencesStore("saf_empty.preferences_pb", backgroundScope)
    val repo = SafMusicSourceRepository(gateway, prefs, createExtractor(), scope = backgroundScope)

    assertEquals(StorageBackend.SAF, repo.storageBackend.value)
    assertTrue(repo.linkedRoots.value.isEmpty())
    assertEquals(FolderContents.EMPTY, repo.browseFolder(null))
    assertTrue(repo.listPlaylists().isEmpty())
  }

  @Test
  fun singleAndMultiRootNavigation_supportsParentNavigationAndExcludesPlaylistFilesFromTracks() = runTest {
    val gateway = FakeSafTreeGateway()
    val prefs = createPreferencesStore("saf_nav.preferences_pb", backgroundScope)

    val tree1 = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val root1Doc = "$tree1/document/primary%3AMusic"
    val elecDoc = "$tree1/document/primary%3AMusic%2FElectronic"
    val track1Uri = "$root1Doc%2FThe%20Doors%20-%20L.A.%20Woman.mp3"
    val track2Uri = "$elecDoc%2F01%20-%20Nightcall.mp3"
    val m3uPlaylistUri = "$root1Doc%2Fmix.m3u"

    gateway.addTree(tree1, root1Doc, "Music")
    gateway.addChild(root1Doc, SafDocumentNode(track1Uri, "The Doors - L.A. Woman.mp3", false, "audio/mpeg"))
    gateway.addChild(root1Doc, SafDocumentNode(m3uPlaylistUri, "mix.m3u", false, "audio/x-mpegurl"), "Electronic/01 - Nightcall.mp3\n")
    gateway.addChild(root1Doc, SafDocumentNode(elecDoc, "Electronic", true))
    gateway.addChild(elecDoc, SafDocumentNode(track2Uri, "01 - Nightcall.mp3", false, "audio/mpeg"))

    val repo = SafMusicSourceRepository(gateway, prefs, createExtractor(), scope = backgroundScope)
    repo.linkSafRoot(tree1)

    // Single root opens directly
    val singleRootContents = repo.browseFolder(null)
    assertEquals(tree1, singleRootContents.currentFolderUri)
    assertEquals(listOf("Music"), singleRootContents.currentPathSegments)
    assertFalse(singleRootContents.canNavigateUp)
    assertNull(singleRootContents.parentFolderUri)
    assertEquals(1, singleRootContents.folders.size)
    assertEquals(1, singleRootContents.tracks.size)
    assertEquals("The Doors - L.A. Woman.mp3", singleRootContents.tracks[0].fileName)

    val subContents = repo.browseFolder(elecDoc)
    assertTrue(subContents.canNavigateUp)
    assertEquals(tree1, subContents.parentFolderUri)
    assertEquals(listOf("Music", "Electronic"), subContents.currentPathSegments)
    assertEquals(1, subContents.tracks.size)
    assertEquals("Nightcall", subContents.tracks[0].title)
    assertEquals("Music/Electronic", subContents.tracks[0].folderPath)

    // Recursive vs non-recursive resolution
    assertEquals(1, repo.resolveFolderTracks(tree1, recursive = false).size)
    assertEquals(2, repo.resolveFolderTracks(tree1, recursive = true).size)

    // Link a second root -> browseFolder(null) shows virtual roots, entering root1 has non-null parentFolderUri
    val tree2 = "content://com.android.externalstorage.documents/tree/sd%3AFLAC"
    val root2Doc = "$tree2/document/sd%3AFLAC"
    gateway.addTree(tree2, root2Doc, "FLAC")
    repo.linkSafRoot(tree2)

    val multiRootOverview = repo.browseFolder(null)
    assertNull(multiRootOverview.currentFolderUri)
    assertTrue(multiRootOverview.currentPathSegments.isEmpty())
    assertFalse(multiRootOverview.canNavigateUp)
    assertEquals(2, multiRootOverview.folders.size)

    val enteredRoot1 = repo.browseFolder(tree1)
    assertTrue(enteredRoot1.canNavigateUp)
    assertEquals(SafMusicSourceRepository.SAF_VIRTUAL_ROOT_URI, enteredRoot1.parentFolderUri)

    val backToVirtualRoots = repo.browseFolder(enteredRoot1.parentFolderUri)
    assertEquals(2, backToVirtualRoots.folders.size)
  }

  @Test
  fun playlistsDiscoveryAndResolution_handlesM3uPlsRelativeWindowsAndDropsMissing() = runTest {
    val gateway = FakeSafTreeGateway()
    val prefs = createPreferencesStore("saf_playlists.preferences_pb", backgroundScope)

    val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val rootDoc = "$tree/document/primary%3AMusic"
    val playlistsDir = "$tree/document/primary%3AMusic%2FPlaylists"
    val rockDir = "$tree/document/primary%3AMusic%2FRock"
    val trackA = "$rockDir%2F01_roadhouse.mp3"
    val trackB = "$rootDoc%2F00_intro.mp3"

    gateway.addTree(tree, rootDoc, "Music")
    gateway.addChild(rootDoc, SafDocumentNode(trackB, "00_intro.mp3", false, "audio/mpeg"))
    gateway.addChild(rootDoc, SafDocumentNode(rockDir, "Rock", true))
    gateway.addChild(rockDir, SafDocumentNode(trackA, "01_roadhouse.mp3", false, "audio/mpeg"))
    gateway.addChild(rootDoc, SafDocumentNode(playlistsDir, "Playlists", true))

    val m3u8Content =
      """
      #EXTM3U
      ../Rock/01_roadhouse.mp3
      C:\Users\winamp\Music\00_intro.mp3
      ../Missing/99_missing_target.mp3
      """.trimIndent()
    val plsContent =
      """
      [playlist]
      File2=$trackB
      File1=../Rock/01_roadhouse.mp3
      File3=nonexistent.mp3
      NumberOfEntries=3
      Version=2
      """.trimIndent()

    val m3u8Uri = "$playlistsDir%2Froad.m3u8"
    val plsUri = "$playlistsDir%2Ffavorites.pls"
    gateway.addChild(playlistsDir, SafDocumentNode(m3u8Uri, "road.m3u8", false, "audio/x-mpegurl"), m3u8Content)
    gateway.addChild(playlistsDir, SafDocumentNode(plsUri, "favorites.pls", false, "audio/x-scpls"), plsContent)

    val repo =
      SafMusicSourceRepository(
        gateway = gateway,
        preferencesStore = prefs,
        metadataExtractor = createExtractor(mapOf(trackA to 200_000L, trackB to 50_000L)),
        scope = backgroundScope,
      )
    repo.linkSafRoot(tree)

    val playlists = repo.listPlaylists()
    assertEquals(2, playlists.size)

    val plsEntry = playlists.first { it.name == "favorites" }
    assertEquals("pls", plsEntry.format)
    assertEquals(2, plsEntry.trackCount)
    assertEquals(250_000L, plsEntry.totalDurationMs)

    val m3u8Entry = playlists.first { it.name == "road" }
    assertEquals("m3u8", m3u8Entry.format)
    assertEquals(2, m3u8Entry.trackCount)
    assertEquals(250_000L, m3u8Entry.totalDurationMs)

    val plsTracks = repo.resolvePlaylistTracks(plsEntry.id)
    assertEquals(listOf(trackA, trackB), plsTracks.map { it.uriString })

    val m3u8Tracks = repo.resolvePlaylistTracks(m3u8Entry.id)
    assertEquals(listOf(trackA, trackB), m3u8Tracks.map { it.uriString })
  }

  @Test
  fun saveM3u8Playlist_sanitizesOverwritesAndRejectsUnlinked() = runTest {
    val gateway = FakeSafTreeGateway()
    val prefs = createPreferencesStore("saf_save.preferences_pb", backgroundScope)
    val repo = SafMusicSourceRepository(gateway, prefs, createExtractor(), scope = backgroundScope)

    // Unlinked failure
    assertTrue(repo.saveM3u8Playlist("mix", "#EXTM3U\n").isFailure)

    val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val rootDoc = "$tree/document/primary%3AMusic"
    val trackUri = "$rootDoc%2Fsong.mp3"
    gateway.addTree(tree, rootDoc, "Music")
    gateway.addChild(rootDoc, SafDocumentNode(trackUri, "song.mp3", false, "audio/mpeg"))
    repo.linkSafRoot(tree)

    // Invalid blank name rejected
    assertTrue(repo.saveM3u8Playlist("///..", "#EXTM3U\n").isFailure)

    val saved = repo.saveM3u8Playlist("Night/Mix", "#EXTM3U\n#EXTINF:180,song\n$trackUri\n")
    assertTrue(saved.isSuccess)
    val entry = saved.getOrThrow()
    assertEquals("NightMix", entry.name)
    assertEquals("m3u8", entry.format)
    assertEquals(1, entry.trackCount)
    assertEquals(180_000L, entry.totalDurationMs)
    assertEquals(1, repo.listPlaylists().size)
    assertEquals(listOf(trackUri), repo.resolvePlaylistTracks(entry.id).map { it.uriString })
  }

  @Test
  fun cycleAndDepthGuards_preventInfiniteTraversal() = runTest {
    val gateway = FakeSafTreeGateway()
    val prefs = createPreferencesStore("saf_cycle.preferences_pb", backgroundScope)

    val tree = "content://tree/cycle"
    val rootDoc = "$tree/document/root"
    val dirA = "$tree/document/a"
    val dirB = "$tree/document/b"
    gateway.addTree(tree, rootDoc, "Cycle")
    gateway.addChild(rootDoc, SafDocumentNode(dirA, "A", true))
    gateway.addChild(dirA, SafDocumentNode(dirB, "B", true))
    gateway.addChild(dirB, SafDocumentNode(dirA, "A-loop", true)) // cycle back to A
    gateway.addChild(dirA, SafDocumentNode("$dirA/one.mp3", "one.mp3", false, "audio/mpeg"))
    gateway.addChild(dirB, SafDocumentNode("$dirB/two.mp3", "two.mp3", false, "audio/mpeg"))

    val repo =
      SafMusicSourceRepository(
        gateway = gateway,
        preferencesStore = prefs,
        metadataExtractor = createExtractor(),
        scope = backgroundScope,
        maxTraversalDepth = 4,
      )
    repo.linkSafRoot(tree)

    val resolved = repo.resolveFolderTracks(tree, recursive = true)
    assertEquals(2, resolved.size)
  }

  @Test
  fun revokedPermissionsAndRejectedLink_degradeGracefullyAndPruneStore() = runTest {
    val gateway = FakeSafTreeGateway()
    val prefs = createPreferencesStore("saf_revoked.preferences_pb", backgroundScope)

    val validTree = "content://tree/valid"
    val revokedTree = "content://tree/revoked"
    val rejectedTree = "content://tree/rejected"

    gateway.addTree(validTree, "$validTree/document/root", "Valid")
    gateway.rejectedPermissionTrees.add(rejectedTree)

    // Pre-populate store with both valid and revoked roots
    prefs.addSafRoot(validTree, "Valid")
    prefs.addSafRoot(revokedTree, "Revoked")

    val repo = SafMusicSourceRepository(gateway, prefs, createExtractor(), scope = backgroundScope)
    repo.linkSafRoot(rejectedTree) // ignored because permission rejected

    val contents = repo.browseFolder(null)
    assertEquals(validTree, contents.currentFolderUri)
    assertEquals(1, repo.linkedRoots.value.size)
    assertEquals(listOf(PersistedSafRoot(validTree, "Valid")), prefs.loadSnapshot().safRoots)

    // Mid-session revocation
    gateway.accessibleTrees.remove(validTree)
    assertEquals(FolderContents.EMPTY, repo.browseFolder(validTree))
    assertTrue(repo.linkedRoots.value.isEmpty())
    assertTrue(prefs.loadSnapshot().safRoots.isEmpty())

    // Re-link and unlinkAllRoots
    gateway.accessibleTrees.add(validTree)
    repo.linkSafRoot(validTree)
    assertEquals(1, repo.linkedRoots.value.size)
    repo.unlinkAllRoots()
    assertTrue(repo.linkedRoots.value.isEmpty())
    assertTrue(gateway.releasedUris.contains(validTree))
  }

  @Test
  fun playlistResolution_prefersLongestSuffixAndFullPath_rejectsUnrelatedPosixAndTies_andSupportsFileUri() = runTest {
    val gateway = FakeSafTreeGateway()
    val prefs = createPreferencesStore("saf_abs_priority.preferences_pb", backgroundScope)

    val tree1 = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val root1Doc = "$tree1/document/primary%3AMusic"
    val bestOfDir = "$root1Doc%2FBestOf"
    val popDir = "$root1Doc%2FPop"
    val popBestOfDir = "$popDir%2FBestOf"
    val live1Dir = "$root1Doc%2FLive"
    val trackShort = "$bestOfDir%2F01.mp3"
    val trackLong = "$popBestOfDir%2F01.mp3"
    val trackLive1 = "$live1Dir%2Fjam.mp3"
    val playlistUri = "$root1Doc%2Fdesktop.m3u8"

    gateway.addTree(tree1, root1Doc, "Music")
    gateway.addChild(root1Doc, SafDocumentNode(bestOfDir, "BestOf", true))
    gateway.addChild(bestOfDir, SafDocumentNode(trackShort, "01.mp3", false, "audio/mpeg"))
    gateway.addChild(root1Doc, SafDocumentNode(popDir, "Pop", true))
    gateway.addChild(popDir, SafDocumentNode(popBestOfDir, "BestOf", true))
    gateway.addChild(popBestOfDir, SafDocumentNode(trackLong, "01.mp3", false, "audio/mpeg"))
    gateway.addChild(root1Doc, SafDocumentNode(live1Dir, "Live", true))
    gateway.addChild(live1Dir, SafDocumentNode(trackLive1, "jam.mp3", false, "audio/mpeg"))

    val tree2 = "content://com.android.externalstorage.documents/tree/sd%3AArchive"
    val root2Doc = "$tree2/document/sd%3AArchive"
    val live2Dir = "$root2Doc%2FLive"
    val trackLive2 = "$live2Dir%2Fjam.mp3"

    gateway.addTree(tree2, root2Doc, "Archive")
    gateway.addChild(root2Doc, SafDocumentNode(live2Dir, "Live", true))
    gateway.addChild(live2Dir, SafDocumentNode(trackLive2, "jam.mp3", false, "audio/mpeg"))

    val playlistContent =
      """
      #EXTM3U
      C:\Windows\Path\Pop\BestOf\01.mp3
      /storage/sdcard/Archive/Live/jam.mp3
      file:///storage/sdcard/Archive/Live/jam.mp3
      /Completely/UnrelatedMount/BestOf/01.mp3
      C:\Windows\Path\Live\jam.mp3
      """.trimIndent()
    gateway.addChild(root1Doc, SafDocumentNode(playlistUri, "desktop.m3u8", false, "audio/x-mpegurl"), playlistContent)

    val repo = SafMusicSourceRepository(gateway, prefs, createExtractor(), scope = backgroundScope)
    repo.linkSafRoot(tree1)
    repo.linkSafRoot(tree2)

    val resolved = repo.resolvePlaylistTracks(playlistUri)
    assertEquals(listOf(trackLong, trackLive2, trackLive2), resolved.map { it.uriString })
  }

  @Test
  fun saveM3u8Playlist_acrossMultipleRoots_reportsAccurateTrackCountAndDuration() = runTest {
    val gateway = FakeSafTreeGateway()
    val prefs = createPreferencesStore("saf_multiroot_save.preferences_pb", backgroundScope)

    val tree1 = "content://tree/music"
    val root1Doc = "$tree1/document/music"
    val track1 = "$root1Doc%2Fone.mp3"
    gateway.addTree(tree1, root1Doc, "Music")
    gateway.addChild(root1Doc, SafDocumentNode(track1, "one.mp3", false, "audio/mpeg"))

    val tree2 = "content://tree/archive"
    val root2Doc = "$tree2/document/archive"
    val track2 = "$root2Doc%2Ftwo.mp3"
    gateway.addTree(tree2, root2Doc, "Archive")
    gateway.addChild(root2Doc, SafDocumentNode(track2, "two.mp3", false, "audio/mpeg"))

    val extractor =
      createExtractor(
        durations = mapOf(track1 to 120_000L, track2 to 240_000L),
      )
    val repo = SafMusicSourceRepository(gateway, prefs, extractor, scope = backgroundScope)
    repo.linkSafRoot(tree1)
    repo.linkSafRoot(tree2)

    val saved =
      repo.saveM3u8Playlist(
        "CrossRoot",
        "#EXTM3U\n$track1\n$track2\n",
      ).getOrThrow()

    assertEquals(2, saved.trackCount)
    assertEquals(360_000L, saved.totalDurationMs)

    val listed = repo.listPlaylists().single()
    assertEquals(saved.trackCount, listed.trackCount)
    assertEquals(saved.totalDurationMs, listed.totalDurationMs)
    assertEquals(listOf(track1, track2), repo.resolvePlaylistTracks(saved.id).map { it.uriString })
  }

  @Test
  fun browseFolder_respectsMaxTraversalDepthWithoutExposingDeadEndFolders() = runTest {
    val gateway = FakeSafTreeGateway()
    val prefs = createPreferencesStore("saf_depth.preferences_pb", backgroundScope)

    val tree = "content://tree/deep"
    val rootDoc = "$tree/document/root"
    val d1 = "$tree/document/d1"
    val d2 = "$tree/document/d2"
    val d3 = "$tree/document/d3"
    val d4 = "$tree/document/d4"
    val t3 = "$d3/three.mp3"
    val t4 = "$d4/four.mp3"

    gateway.addTree(tree, rootDoc, "Deep")
    gateway.addChild(rootDoc, SafDocumentNode(d1, "D1", true))
    gateway.addChild(d1, SafDocumentNode(d2, "D2", true))
    gateway.addChild(d2, SafDocumentNode(d3, "D3", true))
    gateway.addChild(d3, SafDocumentNode(d4, "D4", true))
    gateway.addChild(d3, SafDocumentNode(t3, "three.mp3", false, "audio/mpeg"))
    gateway.addChild(d4, SafDocumentNode(t4, "four.mp3", false, "audio/mpeg"))

    val repo =
      SafMusicSourceRepository(
        gateway = gateway,
        preferencesStore = prefs,
        metadataExtractor = createExtractor(),
        scope = backgroundScope,
        maxTraversalDepth = 3,
      )
    repo.linkSafRoot(tree)

    val level0 = repo.browseFolder(null)
    assertEquals(listOf("D1"), level0.folders.map { it.name })
    assertEquals(1, level0.folders[0].subfolderCount)

    val level1 = repo.browseFolder(level0.folders[0].uriString)
    assertNotNull(level1.currentFolderUri)
    assertEquals(listOf("D2"), level1.folders.map { it.name })
    assertEquals(1, level1.folders[0].subfolderCount)

    val level2 = repo.browseFolder(level1.folders[0].uriString)
    assertNotNull(level2.currentFolderUri)
    assertEquals(listOf("D3"), level2.folders.map { it.name })
    assertEquals(0, level2.folders[0].subfolderCount)

    val level3 = repo.browseFolder(level2.folders[0].uriString)
    assertEquals(d3, level3.currentFolderUri)
    assertTrue(level3.canNavigateUp)
    assertEquals(d2, level3.parentFolderUri)
    assertTrue(level3.folders.isEmpty())
    assertEquals(listOf("three.mp3"), level3.tracks.map { it.fileName })
  }

  @Test
  fun storageSupportedFormats_sanitizesPlaylistNamesAndExcludesPlsMimeTypes() {
    assertNull(StorageSupportedFormats.sanitizePlaylistFileName(".m3u8"))
    assertNull(StorageSupportedFormats.sanitizePlaylistFileName(" .m3u8 "))
    assertNull(StorageSupportedFormats.sanitizePlaylistFileName(".../.m3u8"))
    assertNull(StorageSupportedFormats.sanitizePlaylistFileName(".m3u"))
    assertNull(StorageSupportedFormats.sanitizePlaylistFileName(".pls"))
    assertNull(StorageSupportedFormats.sanitizePlaylistFileName("///.."))
    assertEquals("mix.m3u8", StorageSupportedFormats.sanitizePlaylistFileName("..mix.m3u8."))
    assertEquals("mix.m3u8", StorageSupportedFormats.sanitizePlaylistFileName("mix.M3U"))

    assertFalse(StorageSupportedFormats.isAudioFile("noext", "audio/x-pls"))
    assertFalse(StorageSupportedFormats.isAudioFile("noext", "audio/pls"))
    assertFalse(StorageSupportedFormats.isAudioFile("noext", "audio/pls+xml"))
    assertFalse(StorageSupportedFormats.isAudioFile("spoof.mp3", "audio/x-mpegurl"))
    assertTrue(StorageSupportedFormats.isAudioFile("noext", "audio/mpeg"))
  }

  @Test
  fun playlistResolution_decodesUtf8PercentEncodingAcrossFileAndContentUrisAndPreservesPlus() = runTest {
    val gateway = FakeSafTreeGateway()
    val prefs = createPreferencesStore("saf_utf8_pct.preferences_pb", backgroundScope)

    val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val rootDoc = "$tree/document/primary%3AMusic"
    val cafeDir = "$rootDoc%2FCaf%C3%A9"
    val cafeTrackEncodedUri = "$cafeDir%2F01%20-%20L'%C3%A9t%C3%A9.mp3"
    val literalUtf8TrackUri = "$rootDoc%2FCafé/02 - Crème.mp3"
    val plusTrackUri = "$rootDoc%2FBones%2BAll.mp3"

    gateway.addTree(tree, rootDoc, "Music")
    gateway.addChild(rootDoc, SafDocumentNode(cafeDir, "Café", true))
    gateway.addChild(cafeDir, SafDocumentNode(cafeTrackEncodedUri, "01 - L'été.mp3", false, "audio/mpeg"))
    gateway.addChild(rootDoc, SafDocumentNode(literalUtf8TrackUri, "02 - Crème.mp3", false, "audio/mpeg"))
    gateway.addChild(rootDoc, SafDocumentNode(plusTrackUri, "Bones+All.mp3", false, "audio/mpeg"))

    val playlistUri = "$rootDoc%2Futf8.m3u8"
    val content =
      """
      #EXTM3U
      file:///storage/emulated/0/Music/Caf%C3%A9/01%20-%20L'%C3%A9t%C3%A9.mp3
      $rootDoc%2FCafé%2F01 - L'été.mp3
      $rootDoc%2FCaf%C3%A9/02%20-%20Cr%C3%A8me.mp3
      file:///storage/emulated/0/Music/Bones%2BAll.mp3
      file:///storage/emulated/0/Music/Bones+All.mp3
      file:///storage/emulated/0/Music/Bad%ZZPath.mp3
      """.trimIndent()
    gateway.addChild(rootDoc, SafDocumentNode(playlistUri, "utf8.m3u8", false, "audio/x-mpegurl"), content)

    val repo = SafMusicSourceRepository(gateway, prefs, createExtractor(), scope = backgroundScope)
    repo.linkSafRoot(tree)

    val resolved = repo.resolvePlaylistTracks(playlistUri)
    assertEquals(
      listOf(
        cafeTrackEncodedUri,
        cafeTrackEncodedUri,
        literalUtf8TrackUri,
        plusTrackUri,
        plusTrackUri,
      ),
      resolved.map { it.uriString },
    )
  }
}
