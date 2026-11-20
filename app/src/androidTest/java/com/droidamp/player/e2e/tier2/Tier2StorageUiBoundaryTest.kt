package com.droidamp.player.e2e.tier2

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidamp.player.MainActivity
import com.droidamp.player.domain.contract.MusicSourceRepository
import com.droidamp.player.domain.contract.PlaylistFileCodec
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.domain.model.SheetMode
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.e2e.fixtures.SyntheticAudioFixtures
import com.droidamp.player.e2e.fixtures.SyntheticWinampFixtures
import com.droidamp.player.e2e.harness.DeviceManifestVerifier
import com.droidamp.player.e2e.harness.DroidAmpSelectors
import com.droidamp.player.e2e.harness.E2eRackDriver
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.rack.DroidAmpRackScreen
import com.droidamp.player.ui.rack.DroidAmpViewModel
import com.droidamp.player.ui.rack.FakeAudioEngineController
import com.droidamp.player.ui.rack.FakeMusicSourceRepository
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Tier2StorageUiBoundaryTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val targetContext: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val playlistCodec: PlaylistFileCodec by lazy {
    loadContract("com.droidamp.player.interop.WinampPlaylistCodec", PlaylistFileCodec::class.java)
  }

  // -------------------------------------------------------------------------
  // Reflective M3 Helpers (Zero M2–M4 Compile-Time Imports)
  // -------------------------------------------------------------------------

  private fun <T : Any> loadContract(implClassName: String, contractClass: Class<T>): T {
    val clazz = Class.forName(implClassName)
    val instance =
      runCatching { clazz.getField("INSTANCE").get(null) }
        .getOrElse { clazz.getDeclaredConstructor().newInstance() }
    return contractClass.cast(instance)
      ?: error("$implClassName does not implement ${contractClass.name}")
  }

  private data class SafTreeSpec(
    val treeUri: String,
    val rootDocUri: String,
    val rootName: String,
    val childrenByDirUri: Map<String, List<Any>>,
    val textFilesByDocUri: MutableMap<String, String> = mutableMapOf(),
  )

  private fun newSafNode(
    uriString: String,
    name: String,
    isDirectory: Boolean,
    mimeType: String? = null,
  ): Any {
    val cls = Class.forName("com.droidamp.player.storage.SafDocumentNode")
    val ctor =
      cls.getDeclaredConstructor(
        String::class.java,
        String::class.java,
        Boolean::class.javaPrimitiveType,
        String::class.java,
      )
    return ctor.newInstance(uriString, name, isDirectory, mimeType)
  }

  private fun createSafGatewayProxy(
    trees: List<SafTreeSpec>,
    persistedUris: MutableSet<String> = mutableSetOf(),
  ): Any {
    val gatewayClass = Class.forName("com.droidamp.player.storage.SafTreeGateway")
    val treesByUri = trees.associateBy { it.treeUri }
    val mutableChildren =
      trees.flatMap { it.childrenByDirUri.entries }
        .associate { (k, v) -> k to v.toMutableList() }
        .toMutableMap()

    return Proxy.newProxyInstance(
      gatewayClass.classLoader,
      arrayOf(gatewayClass),
    ) { _, method, args ->
      when (method.name) {
        "takePersistablePermission" -> {
          val uri = args?.getOrNull(0) as? String ?: ""
          val ok = uri in treesByUri
          if (ok) persistedUris.add(uri)
          java.lang.Boolean.valueOf(ok)
        }
        "releasePersistablePermission" -> {
          val uri = args?.getOrNull(0) as? String
          if (uri != null) persistedUris.remove(uri)
          Unit
        }
        "getPersistedReadTreeUris" -> persistedUris.toSet()
        "isTreeAccessible" -> {
          val uri = args?.getOrNull(0) as? String ?: ""
          java.lang.Boolean.valueOf(uri in treesByUri)
        }
        "getTreeRootNode" -> {
          val uri = args?.getOrNull(0) as? String
          val spec = uri?.let { treesByUri[it] }
          if (spec == null) {
            null
          } else {
            newSafNode(spec.rootDocUri, spec.rootName, isDirectory = true, mimeType = "vnd.android.document/directory")
          }
        }
        "listChildren" -> {
          val treeUri = args?.getOrNull(0) as? String
          val dirUri = args?.getOrNull(1) as? String
          if (treeUri !in treesByUri) {
            null
          } else {
            mutableChildren[dirUri]?.toList() ?: emptyList<Any>()
          }
        }
        "readTextFile" -> {
          val docUri = args?.getOrNull(0) as? String
          trees.firstNotNullOfOrNull { it.textFilesByDocUri[docUri] }
        }
        "writeTextFile" -> {
          val treeUri = args?.getOrNull(0) as? String
          val dirUri = args?.getOrNull(1) as? String
          val fileName = args?.getOrNull(2) as? String
          val mimeType = args?.getOrNull(3) as? String
          val content = args?.getOrNull(4) as? String
          val spec = treesByUri[treeUri]
          if (spec == null || dirUri == null || fileName == null || content == null) {
            null
          } else {
            val docUri = "$dirUri%2F$fileName"
            val node = newSafNode(docUri, fileName, isDirectory = false, mimeType = mimeType)
            mutableChildren.getOrPut(dirUri) { mutableListOf() }.add(node)
            spec.textFilesByDocUri[docUri] = content
            node
          }
        }
        "equals" -> false
        "hashCode" -> 0
        "toString" -> "SafTreeGatewayBoundaryProxy"
        else -> null
      }
    }
  }

  private fun newMediaStoreAudioRecord(
    mediaId: Long,
    contentUriString: String,
    displayName: String,
    title: String,
    artist: String,
    album: String,
    durationMs: Long,
    relativePath: String,
    dataPath: String = "",
    mimeType: String? = "audio/mpeg",
  ): Any {
    val cls = Class.forName("com.droidamp.player.storage.MediaStoreAudioRecord")
    val ctor =
      cls.getDeclaredConstructor(
        Long::class.javaPrimitiveType,
        String::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
        Long::class.javaPrimitiveType,
        String::class.java,
        String::class.java,
        String::class.java,
      )
    return ctor.newInstance(
      mediaId,
      contentUriString,
      displayName,
      title,
      artist,
      album,
      durationMs,
      relativePath,
      dataPath,
      mimeType,
    )
  }

  private fun createMediaStoreGatewayProxy(
    hasPermission: Boolean,
    throwOnQueryWhenDenied: Boolean = false,
    audioRecords: List<Any> = emptyList(),
  ): Any {
    val gatewayClass = Class.forName("com.droidamp.player.storage.MediaStoreGateway")
    return Proxy.newProxyInstance(
      gatewayClass.classLoader,
      arrayOf(gatewayClass),
    ) { _, method, _ ->
      when (method.name) {
        "hasAudioReadPermission" -> java.lang.Boolean.valueOf(hasPermission)
        "queryAudioTracks" -> {
          if (!hasPermission && throwOnQueryWhenDenied) {
            throw SecurityException("Permission denied")
          }
          if (hasPermission) audioRecords else emptyList<Any>()
        }
        "queryPlaylists" -> {
          if (!hasPermission && throwOnQueryWhenDenied) {
            throw SecurityException("Permission denied")
          }
          emptyList<Any>()
        }
        "queryPlaylistFiles" -> emptyList<Any>()
        "readTextFile" -> null
        "writeM3u8PlaylistFile" -> null
        "equals" -> false
        "hashCode" -> 0
        "toString" -> "MediaStoreGatewayBoundaryProxy"
        else -> null
      }
    }
  }

  private fun createProbeProxy(): Any {
    val probeClass = Class.forName("com.droidamp.player.storage.AudioMetadataProbe")
    return Proxy.newProxyInstance(
      probeClass.classLoader,
      arrayOf(probeClass),
    ) { _, method, _ ->
      when (method.name) {
        "probe" -> null
        "equals" -> false
        "hashCode" -> 0
        "toString" -> "AudioMetadataProbeBoundaryProxy"
        else -> null
      }
    }
  }

  private data class ReflectiveStorageStack(
    val scope: CoroutineScope,
    val safRepo: MusicSourceRepository,
    val mediaStoreRepo: MusicSourceRepository,
    val switchingRepo: MusicSourceRepository,
  ) : AutoCloseable {
    override fun close() {
      runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
      }
    }
  }

  private fun buildStorageStack(
    prefsFile: File,
    safGateway: Any? = null,
    mediaStoreGateway: Any? = null,
  ): ReflectiveStorageStack {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore =
      PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { prefsFile },
      )
    val prefsStoreClass = Class.forName("com.droidamp.player.storage.StoragePreferencesStore")
    val prefsStore = prefsStoreClass.getDeclaredConstructor(DataStore::class.java).newInstance(dataStore)

    val probeInterface = Class.forName("com.droidamp.player.storage.AudioMetadataProbe")
    val extractorClass = Class.forName("com.droidamp.player.storage.AudioMetadataExtractor")
    val extractor =
      extractorClass.getDeclaredConstructor(
        probeInterface,
        Int::class.javaPrimitiveType,
        CoroutineDispatcher::class.java,
      ).newInstance(createProbeProxy(), 128, Dispatchers.IO)

    val resolvedSafGateway =
      safGateway
        ?: Class.forName("com.droidamp.player.storage.DocumentFileSafTreeGateway")
          .getDeclaredConstructor(Context::class.java)
          .newInstance(targetContext)
    val safGatewayInterface = Class.forName("com.droidamp.player.storage.SafTreeGateway")
    val safRepoClass = Class.forName("com.droidamp.player.storage.SafMusicSourceRepository")
    val safRepoInstance =
      safRepoClass.getDeclaredConstructor(
        safGatewayInterface,
        prefsStoreClass,
        extractorClass,
        PlaylistFileCodec::class.java,
        CoroutineScope::class.java,
        CoroutineDispatcher::class.java,
        Int::class.javaPrimitiveType,
      ).newInstance(resolvedSafGateway, prefsStore, extractor, playlistCodec, scope, Dispatchers.IO, 16)

    val resolvedMsGateway =
      mediaStoreGateway
        ?: Class.forName("com.droidamp.player.storage.AndroidMediaStoreGateway")
          .getDeclaredConstructor(Context::class.java)
          .newInstance(targetContext)
    val msGatewayInterface = Class.forName("com.droidamp.player.storage.MediaStoreGateway")
    val msRepoClass = Class.forName("com.droidamp.player.storage.MediaStoreMusicSourceRepository")
    val msRepoInstance =
      msRepoClass.getDeclaredConstructor(
        msGatewayInterface,
        extractorClass,
        PlaylistFileCodec::class.java,
        CoroutineDispatcher::class.java,
      ).newInstance(resolvedMsGateway, extractor, playlistCodec, Dispatchers.IO)

    val switchingClass = Class.forName("com.droidamp.player.storage.SwitchingMusicSourceRepository")
    val switchingInstance =
      switchingClass.getDeclaredConstructor(
        safRepoClass,
        msRepoClass,
        prefsStoreClass,
        CoroutineScope::class.java,
      ).newInstance(safRepoInstance, msRepoInstance, prefsStore, scope)

    return ReflectiveStorageStack(
      scope = scope,
      safRepo = safRepoInstance as MusicSourceRepository,
      mediaStoreRepo = msRepoInstance as MusicSourceRepository,
      switchingRepo = switchingInstance as MusicSourceRepository,
    )
  }

  private fun prefsFile(scopeDir: File): File =
    File(scopeDir, "boundary_prefs_${PREFS_COUNTER.incrementAndGet()}.preferences_pb")

  private fun launchCustomRepositoryRack(repository: MusicSourceRepository): Pair<CoroutineScope, DroidAmpViewModel> {
    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val engine = FakeAudioEngineController(scope = uiScope, enableSimulationTicker = false)
    val viewModel = DroidAmpViewModel(audioEngine = engine, musicRepository = repository)
    composeRule.setContent {
      CompositionLocalProvider(LocalDroidAmpSkin provides ClassicDroidAmpSkin) {
        DroidAmpRackScreen(viewModel = viewModel)
      }
    }
    composeRule.waitForIdle()
    return uiScope to viewModel
  }

  private fun findClickableAncestor(node: SemanticsNode): SemanticsNode? {
    var cur = node.parent
    while (cur != null) {
      if (cur.config.contains(SemanticsActions.OnClick)) return cur
      cur = cur.parent
    }
    return null
  }

  // =========================================================================
  // Feature 16 Boundaries: SAF Backend Edge Cases (6 Tier 2 Tests)
  // =========================================================================

  @Test // T2-F16-1
  fun f16_tier2_unlinkedSafRepositoryReturnsEmptyFolderAndEmptyLists() {
    SyntheticAudioFixtures.createCacheScope(targetContext, "t2_f16_unlinked").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir)).use { stack ->
        runBlocking {
          assertTrue(stack.safRepo.linkedRoots.value.isEmpty())
          assertEquals(FolderContents.EMPTY, stack.safRepo.browseFolder(null))
          assertEquals(FolderContents.EMPTY, stack.safRepo.browseFolder("content://unknown/root"))
          assertTrue(stack.safRepo.listPlaylists().isEmpty())
          assertTrue(stack.safRepo.resolvePlaylistTracks("pl-missing").isEmpty())
          assertTrue(
            stack.safRepo.resolveFolderTracks(
              "content://com.android.externalstorage.documents/tree/primary%3AMusic",
              recursive = true,
            ).isEmpty(),
          )
        }
      }
    }
  }

  @Test // T2-F16-2
  fun f16_tier2_unlinkAllRootsResetsPersistedStateAcrossFreshRepositoryInstances() {
    val tree1 = "content://com.android.externalstorage.documents/tree/primary%3AMusicA"
    val doc1 = "$tree1/document/primary%3AMusicA"
    val tree2 = "content://com.android.externalstorage.documents/tree/primary%3AMusicB"
    val doc2 = "$tree2/document/primary%3AMusicB"
    val specs =
      listOf(
        SafTreeSpec(tree1, doc1, "MusicA", mapOf(doc1 to emptyList())),
        SafTreeSpec(tree2, doc2, "MusicB", mapOf(doc2 to emptyList())),
      )
    val persisted = mutableSetOf<String>()

    SyntheticAudioFixtures.createCacheScope(targetContext, "t2_f16_unlink").use { cache ->
      val pFile = prefsFile(cache.rootDir)
      buildStorageStack(pFile, safGateway = createSafGatewayProxy(specs, persisted)).use { stack1 ->
        runBlocking {
          stack1.safRepo.linkSafRoot(tree1)
          stack1.safRepo.linkSafRoot(tree2)
          assertEquals(2, stack1.safRepo.linkedRoots.value.size)

          stack1.safRepo.unlinkAllRoots()
          assertTrue(stack1.safRepo.linkedRoots.value.isEmpty())
          assertTrue(persisted.isEmpty())
          assertEquals(FolderContents.EMPTY, stack1.safRepo.browseFolder(null))
        }
      }

      // Fresh instance on same DataStore must observe cleared roots
      buildStorageStack(pFile, safGateway = createSafGatewayProxy(specs, persisted)).use { stack2 ->
        runBlocking {
          assertEquals(FolderContents.EMPTY, stack2.safRepo.browseFolder(null))
          assertTrue(stack2.safRepo.linkedRoots.value.isEmpty())
        }
      }
    }
  }

  @Test // T2-F16-3
  fun f16_tier2_emptyLinkedRootRendersEmptyFolderInUi() {
    val emptyTree = "content://com.android.externalstorage.documents/tree/primary%3AEmptyRoot"
    val emptyRootDoc = "$emptyTree/document/primary%3AEmptyRoot"
    val emptySpec = SafTreeSpec(emptyTree, emptyRootDoc, "EmptyRoot", mapOf(emptyRootDoc to emptyList()))

    SyntheticAudioFixtures.createCacheScope(targetContext, "t2_f16_empty_root").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir), safGateway = createSafGatewayProxy(listOf(emptySpec))).use { stack ->
        runBlocking { stack.safRepo.linkSafRoot(emptyTree) }
        val (uiScope, vm) = launchCustomRepositoryRack(stack.safRepo)
        try {
          composeRule.waitUntil(timeoutMillis = 3_000L) {
            vm.uiState.value.folderContents.currentFolderUri != null
          }
          E2eRackDriver(composeRule).ensureSheetExpanded(SheetMode.LIBRARY)
          composeRule.onNodeWithText("EMPTY FOLDER").assertIsDisplayed()
        } finally {
          uiScope.cancel()
        }
      }
    }
  }

  @Test // T2-F16-4
  fun f16_tier2_blankInvalidOrInaccessibleSafUrisFailGracefully() {
    val validTree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val validDoc = "$validTree/document/primary%3AMusic"
    val spec = SafTreeSpec(validTree, validDoc, "Music", mapOf(validDoc to emptyList()))

    SyntheticAudioFixtures.createCacheScope(targetContext, "t2_f16_invalid_uri").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir), safGateway = createSafGatewayProxy(listOf(spec))).use { stack ->
        runBlocking {
          stack.safRepo.linkSafRoot("")
          stack.safRepo.linkSafRoot("   ")
          stack.safRepo.linkSafRoot("content://com.android.externalstorage.documents/tree/missing%3AFake")
          assertTrue(stack.safRepo.linkedRoots.value.isEmpty())

          // Now link a valid root and query unknown subfolders
          stack.safRepo.linkSafRoot(validTree)
          assertEquals(FolderContents.EMPTY, stack.safRepo.browseFolder("content://unknown/folder"))
          assertTrue(stack.safRepo.resolveFolderTracks("content://unknown/folder", recursive = true).isEmpty())
        }
      }
    }
  }

  @Test // T2-F16-5
  fun f16_tier2_nonAudioFilesIgnoredCleanlyBySafRepository() {
    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMixed"
    val rootDoc = "$treeUri/document/primary%3AMixed"
    val spec =
      SafTreeSpec(
        treeUri = treeUri,
        rootDocUri = rootDoc,
        rootName = "Mixed",
        childrenByDirUri =
          mapOf(
            rootDoc to
              listOf(
                newSafNode("$rootDoc%2Freadme.txt", "readme.txt", isDirectory = false, mimeType = "text/plain"),
                newSafNode("$rootDoc%2Fcover.jpg", "cover.jpg", isDirectory = false, mimeType = "image/jpeg"),
                newSafNode("$rootDoc%2Fconfig.json", "config.json", isDirectory = false, mimeType = "application/json"),
                newSafNode("$rootDoc%2Freal.mp3", "real.mp3", isDirectory = false, mimeType = "audio/mpeg"),
              ),
          ),
      )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t2_f16_nonaudio").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir), safGateway = createSafGatewayProxy(listOf(spec))).use { stack ->
        runBlocking {
          stack.safRepo.linkSafRoot(treeUri)
          val contents = stack.safRepo.browseFolder(null)
          assertTrue(contents.folders.isEmpty())
          assertEquals(listOf("real.mp3"), contents.tracks.map { it.fileName })
        }
      }
    }
  }

  @Test // T2-F16-6
  fun f16_tier2_safSaveM3u8UnlinkedAndInvalidFileNamesReturnFailure() {
    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val rootDoc = "$treeUri/document/primary%3AMusic"
    val spec = SafTreeSpec(treeUri, rootDoc, "Music", mapOf(rootDoc to emptyList()))

    SyntheticAudioFixtures.createCacheScope(targetContext, "t2_f16_save_err").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir), safGateway = createSafGatewayProxy(listOf(spec))).use { stack ->
        runBlocking {
          // Unlinked root failure
          assertTrue(stack.safRepo.saveM3u8Playlist("mix.m3u8", "#EXTM3U\n").isFailure)

          // Linked root invalid filenames
          stack.safRepo.linkSafRoot(treeUri)
          assertTrue(stack.safRepo.saveM3u8Playlist("", "#EXTM3U\n").isFailure)
          assertTrue(stack.safRepo.saveM3u8Playlist("   ", "#EXTM3U\n").isFailure)
          assertTrue(stack.safRepo.saveM3u8Playlist("///...", "#EXTM3U\n").isFailure)
          assertTrue(stack.safRepo.saveM3u8Playlist(".m3u8", "#EXTM3U\n").isFailure)

          // FakeMusicSourceRepository inherits default failure
          val fakeRepo = FakeMusicSourceRepository(startLinked = true)
          assertTrue(fakeRepo.saveM3u8Playlist("mix.m3u8", "#EXTM3U\n").isFailure)
        }
      }
    }
  }

  // =========================================================================
  // Feature 17 Boundaries: MediaStore Optional Backend Edge Cases (5 Tier 2 Tests)
  // =========================================================================

  @Test // T2-F17-1
  fun f17_tier2_permissionDeniedMediaStoreDegradesToEmptyWithoutSecurityException() {
    val deniedProxy = createMediaStoreGatewayProxy(hasPermission = false, throwOnQueryWhenDenied = true)
    SyntheticAudioFixtures.createCacheScope(targetContext, "t2_f17_denied").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir), mediaStoreGateway = deniedProxy).use { stack ->
        runBlocking {
          assertEquals(FolderContents.EMPTY, stack.mediaStoreRepo.browseFolder(null))
          assertTrue(stack.mediaStoreRepo.linkedRoots.value.isEmpty())
          assertTrue(stack.mediaStoreRepo.listPlaylists().isEmpty())
          assertTrue(stack.mediaStoreRepo.resolveFolderTracks("mediastore://folders", recursive = true).isEmpty())
          assertTrue(stack.mediaStoreRepo.resolvePlaylistTracks("ms-pl:1").isEmpty())
        }
      }

      // Also verify real AndroidMediaStoreGateway never throws SecurityException
      buildStorageStack(prefsFile(cache.rootDir)).use { realStack ->
        runBlocking {
          realStack.mediaStoreRepo.browseFolder(null)
          realStack.mediaStoreRepo.listPlaylists()
        }
      }
    }
  }

  @Test // T2-F17-2
  fun f17_tier2_switchingSafToMediaStoreAndBackPreservesPersistedSafRoots() {
    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val rootDoc = "$treeUri/document/primary%3AMusic"
    val safSpec = SafTreeSpec(treeUri, rootDoc, "Music", mapOf(rootDoc to emptyList()))
    val msRecord =
      newMediaStoreAudioRecord(
        mediaId = 9L,
        contentUriString = "content://media/external/audio/media/9",
        displayName = "track.mp3",
        title = "Track",
        artist = "Artist",
        album = "Album",
        durationMs = 120_000L,
        relativePath = "Music/",
      )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t2_f17_roundtrip").use { cache ->
      buildStorageStack(
        prefsFile = prefsFile(cache.rootDir),
        safGateway = createSafGatewayProxy(listOf(safSpec)),
        mediaStoreGateway = createMediaStoreGatewayProxy(hasPermission = true, audioRecords = listOf(msRecord)),
      ).use { stack ->
        runBlocking {
          stack.switchingRepo.linkSafRoot(treeUri)
          assertEquals(StorageBackend.SAF, stack.switchingRepo.storageBackend.value)
          assertEquals(listOf("Music"), stack.switchingRepo.linkedRoots.value.map { it.name })

          stack.switchingRepo.setStorageBackend(StorageBackend.MEDIA_STORE)
          assertEquals(StorageBackend.MEDIA_STORE, stack.switchingRepo.storageBackend.value)
          assertEquals(listOf("Device Audio"), stack.switchingRepo.linkedRoots.value.map { it.name })

          // Switch back to SAF: persisted SAF root must remain intact
          stack.switchingRepo.setStorageBackend(StorageBackend.SAF)
          assertEquals(StorageBackend.SAF, stack.switchingRepo.storageBackend.value)
          assertEquals(listOf("Music"), stack.switchingRepo.linkedRoots.value.map { it.name })
        }
      }
    }
  }

  @Test // T2-F17-3
  fun f17_tier2_missingPlaylistIdAndInvalidMediaStoreUrisReturnEmpty() {
    val msProxy = createMediaStoreGatewayProxy(hasPermission = true)
    SyntheticAudioFixtures.createCacheScope(targetContext, "t2_f17_missing").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir), mediaStoreGateway = msProxy).use { stack ->
        runBlocking {
          assertEquals(FolderContents.EMPTY, stack.mediaStoreRepo.browseFolder("mediastore://folder/nonexistent"))
          assertEquals(FolderContents.EMPTY, stack.mediaStoreRepo.browseFolder("content://wrong/scheme"))
          assertTrue(stack.mediaStoreRepo.resolveFolderTracks("invalid://uri", recursive = true).isEmpty())
          assertTrue(stack.mediaStoreRepo.resolvePlaylistTracks("ms-pl:999999999").isEmpty())
          assertTrue(stack.mediaStoreRepo.resolvePlaylistTracks("unknown-playlist-id").isEmpty())
        }
      }
    }
  }

  @Test // T2-F17-4
  fun f17_tier2_mediaStoreRecursiveFalseVsTrueOnFolderWithOnlyNestedSubfolderTracks() {
    val nestedRecord =
      newMediaStoreAudioRecord(
        mediaId = 55L,
        contentUriString = "content://media/external/audio/media/55",
        displayName = "deep.mp3",
        title = "Deep Track",
        artist = "Ambient",
        album = "Sub",
        durationMs = 210_000L,
        relativePath = "Music/Albums/Deep/",
      )
    val msProxy = createMediaStoreGatewayProxy(hasPermission = true, audioRecords = listOf(nestedRecord))

    SyntheticAudioFixtures.createCacheScope(targetContext, "t2_f17_nested").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir), mediaStoreGateway = msProxy).use { stack ->
        runBlocking {
          val musicFolderUri = stack.mediaStoreRepo.browseFolder(null).folders.first().uriString
          val nonRecursive = stack.mediaStoreRepo.resolveFolderTracks(musicFolderUri, recursive = false)
          assertTrue("Direct tracks under Music should be empty, got $nonRecursive", nonRecursive.isEmpty())

          val recursive = stack.mediaStoreRepo.resolveFolderTracks(musicFolderUri, recursive = true)
          assertEquals(listOf("deep.mp3"), recursive.map { it.fileName })
        }
      }
    }
  }

  @Test // T2-F17-5
  fun f17_tier2_manifestOfflineInvariantAndMediaStoreSaveM3u8FilenameSanitization() {
    DeviceManifestVerifier.assertOfflineManifestPermissions(targetContext)

    val expectedNormalizedFile = File(File(targetContext.filesDir, "playlists"), "tier2_mix.m3u8")
    SyntheticAudioFixtures.createCacheScope(targetContext, "t2_f17_sanitize").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir)).use { stack ->
        try {
          runBlocking {
            assertTrue(stack.mediaStoreRepo.saveM3u8Playlist("", "#EXTM3U\n").isFailure)
            assertTrue(stack.mediaStoreRepo.saveM3u8Playlist("   ", "#EXTM3U\n").isFailure)
            assertTrue(stack.mediaStoreRepo.saveM3u8Playlist("///...", "#EXTM3U\n").isFailure)

            val normalized = stack.mediaStoreRepo.saveM3u8Playlist("tier2_mix.pls", "#EXTM3U\n")
            assertTrue("Expected normalized .pls -> .m3u8 save success", normalized.isSuccess)
            val entry = normalized.getOrThrow()
            assertEquals("m3u8", entry.format)
            assertEquals("tier2_mix", entry.name)
            assertTrue(expectedNormalizedFile.isFile)
          }
        } finally {
          expectedNormalizedFile.delete()
        }
      }
    }
  }

  // =========================================================================
  // Feature 18 Boundaries: Onboarding, Library & Select-First Edge Cases (4 tests here + 1 in MainActivity)
  // =========================================================================

  @Test // T2-F18-1
  fun f18_tier2_unlinkedPrimaryPlayAndQueueDisabledAndUnlinkRevertsToEmptyWell() {
    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = false).use { driver ->
      driver.clickEjectOnDeck()
      composeRule.onNodeWithText("DROIDAMP LIBRARY ▼").assertIsDisplayed()
      DroidAmpSelectors.libraryPrimaryPlayButton(composeRule).assertIsNotEnabled()
      DroidAmpSelectors.libraryPrimaryQueueButton(composeRule).assertIsNotEnabled()

      driver.linkFakeSafRoot()
      DroidAmpSelectors.libraryPrimaryPlayButton(composeRule).assertIsEnabled()
      DroidAmpSelectors.libraryPrimaryQueueButton(composeRule).assertIsEnabled()

      runBlocking { driver.repository.unlinkAllRoots() }
      // Trigger refresh by navigating or switching tabs; linkedRoots StateFlow immediately flips hasLinkedRoots=false
      composeRule.waitForIdle()
      DroidAmpSelectors.emptyFolderTitleExpanded(composeRule).assertIsDisplayed()
      DroidAmpSelectors.libraryPrimaryPlayButton(composeRule).assertIsNotEnabled()
      DroidAmpSelectors.libraryPrimaryQueueButton(composeRule).assertIsNotEnabled()
    }
  }

  @Test // T2-F18-2
  fun f18_tier2_multiSelectPreservesPlayingQueueAndTabSwitchClearsSelection() {
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS,
      autoPlay = true,
    ).use { driver ->
      driver.ensureSheetExpanded(SheetMode.LIBRARY)
      DroidAmpSelectors.libraryFolderRow(composeRule, "Electronic").performClick()
      composeRule.waitForIdle()

      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.libraryTrackRow(
          composeRule,
          "The Chemical Brothers - Block Rockin' Beats",
        ),
      )
      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.libraryTrackRow(
          composeRule,
          "The Prodigy - Breathe",
        ),
      )

      composeRule.onNodeWithText("▶ PLAY (2)").assertIsDisplayed()
      assertEquals(4, driver.engine.playbackState.value.queue.size)
      assertEquals(0, driver.engine.playbackState.value.currentIndex)
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      // Switching tabs clears selection cleanly
      DroidAmpSelectors.libraryPlaylistsTab(composeRule).performClick()
      composeRule.waitForIdle()
      DroidAmpSelectors.libraryFoldersTab(composeRule).performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithText("▶ PLAY").assertIsDisplayed()
      assertTrue(driver.viewModel.uiState.value.selectedLibraryTrackIds.isEmpty())
    }
  }

  @Test // T2-F18-3
  fun f18_tier2_emptyPlaylistsShowsMessageAndEmptySubfolderAllowsNavigateUp() {
    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val rootDoc = "$treeUri/document/primary%3AMusic"
    val emptySubDoc = "$treeUri/document/primary%3AMusic%2FEmptySub"
    val spec =
      SafTreeSpec(
        treeUri = treeUri,
        rootDocUri = rootDoc,
        rootName = "Music",
        childrenByDirUri =
          mapOf(
            rootDoc to
              listOf(
                newSafNode(emptySubDoc, "EmptySub", isDirectory = true, mimeType = "vnd.android.document/directory"),
                newSafNode("$rootDoc%2Ftrack.mp3", "track.mp3", isDirectory = false, mimeType = "audio/mpeg"),
              ),
            emptySubDoc to emptyList(),
          ),
      )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t2_f18_empty_pl").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir), safGateway = createSafGatewayProxy(listOf(spec))).use { stack ->
        runBlocking { stack.safRepo.linkSafRoot(treeUri) }
        val (uiScope, vm) = launchCustomRepositoryRack(stack.safRepo)
        try {
          composeRule.waitUntil(timeoutMillis = 3_000L) {
            vm.uiState.value.folderContents.currentFolderUri != null
          }
          val driver = E2eRackDriver(composeRule)
          driver.ensureSheetExpanded(SheetMode.LIBRARY)

          // Empty subfolder with canNavigateUp=true shows [..] PARENT FOLDER without "EMPTY FOLDER" crash
          DroidAmpSelectors.libraryFolderRow(composeRule, "EmptySub").performClick()
          composeRule.waitUntil(timeoutMillis = 3_000L) {
            vm.uiState.value.folderContents.canNavigateUp
          }
          composeRule.waitForIdle()
          DroidAmpSelectors.libraryParentFolderRow(composeRule).assertIsDisplayed()
          assertTrue(
            composeRule.onAllNodesWithText("EMPTY FOLDER").fetchSemanticsNodes().isEmpty(),
          )
          DroidAmpSelectors.libraryParentFolderRow(composeRule).performClick()
          composeRule.waitUntil(timeoutMillis = 3_000L) {
            !vm.uiState.value.folderContents.canNavigateUp
          }
          composeRule.waitForIdle()

          // Empty [PLAYLISTS] tab displays "NO .M3U / .PLS PLAYLISTS FOUND"
          DroidAmpSelectors.libraryPlaylistsTab(composeRule).performClick()
          composeRule.waitForIdle()
          composeRule.onNodeWithText("NO .M3U / .PLS PLAYLISTS FOUND").assertIsDisplayed()
        } finally {
          uiScope.cancel()
        }
      }
    }
  }

  @Test // T2-F18-4
  fun f18_tier2_peekingVsExpandedDisambiguationAndMicroTransportVsDeckPl() {
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = false,
    ).use { driver ->
      // Disambiguate peeking vs expanded EmptyFolderWell
      DroidAmpSelectors.emptyFolderTitlePeeking(composeRule).assertIsDisplayed()
      driver.expandSheetByTitleTap()
      DroidAmpSelectors.emptyFolderTitleExpanded(composeRule).assertIsDisplayed()
      DroidAmpSelectors.selectMusicFolderButton(composeRule).assertIsDisplayed()

      driver.linkFakeSafRoot()
      // Disambiguate libraryPlButton (.onLast) vs deckPlToggleButton (.onFirst)
      DroidAmpSelectors.libraryPlButton(composeRule).performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithText("DROIDAMP PLAYLIST ▼").assertIsDisplayed()

      // Switch back to LIBRARY, enqueue a track, switch to PLAYLIST, and verify sheetMicroPlayButton (.onLast)
      DroidAmpSelectors.playlistLibButton(composeRule).performClick()
      composeRule.waitForIdle()
      DroidAmpSelectors.libraryTrackEnqueueButton(
        composeRule,
        "DJ Mike Llama - DroidAmp Intro (Llama Whippin' Mix)",
      ).performClick()
      composeRule.waitForIdle()
      DroidAmpSelectors.libraryPlButton(composeRule).performClick()
      composeRule.waitForIdle()
      DroidAmpSelectors.sheetMicroPlayButton(composeRule).performClick()
      composeRule.waitForIdle()
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)
    }
  }

  // =========================================================================
  // Feature 19 Boundaries: Playlist Reorder, Jump Filter & Menu Edge Cases (5 Tier 2 Tests)
  // =========================================================================

  @Test // T2-F19-1
  fun f19_tier2_reorderOutOfBoundsClampingAndSingleTrackButtonStates() {
    val threeTracks = FakeMusicSourceRepository.DAFT_PUNK_TRACKS.take(3)
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS.take(1),
    ).use { driver ->
      // 1-track queue: both ▲ and ▼ must be disabled when selected
      driver.ensureSheetExpanded(SheetMode.PLAYLIST)
      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - One More Time"),
      )
      DroidAmpSelectors.playlistMoveUpButton(composeRule, "Daft Punk - One More Time").assertIsNotEnabled()
      DroidAmpSelectors.playlistMoveDownButton(composeRule, "Daft Punk - One More Time").assertIsNotEnabled()

      // Deselect before replacing queue with 3-track queue
      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - One More Time"),
      )

      // 3-track queue: index 0 ▲ disabled, index 2 ▼ disabled, out-of-bounds moves are no-ops
      runBlocking {
        driver.engine.replaceQueue(threeTracks, startIndex = 0, autoPlay = true)
      }
      composeRule.waitForIdle()

      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - One More Time"),
      )
      DroidAmpSelectors.playlistMoveUpButton(composeRule, "Daft Punk - One More Time").assertIsNotEnabled()
      DroidAmpSelectors.playlistMoveDownButton(composeRule, "Daft Punk - One More Time").assertIsEnabled()
      // Deselect index 0, select index 2
      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - One More Time"),
      )
      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - Digital Love"),
      )
      DroidAmpSelectors.playlistMoveUpButton(composeRule, "Daft Punk - Digital Love").assertIsEnabled()
      DroidAmpSelectors.playlistMoveDownButton(composeRule, "Daft Punk - Digital Love").assertIsNotEnabled()

      driver.viewModel.movePlaylistTrack(0, -1)
      driver.viewModel.movePlaylistTrack(2, 3)
      composeRule.waitForIdle()
      assertEquals(threeTracks.map { it.id }, driver.engine.playbackState.value.queue.map { it.id })
      assertEquals(0, driver.engine.playbackState.value.currentIndex)
    }
  }

  @Test // T2-F19-2
  fun f19_tier2_remSelectedAndCropSelectedWithEmptySelectionAreNoOps() {
    val queue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS.take(3)
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = queue,
      autoPlay = true,
    ).use { driver ->
      driver.ensureSheetExpanded(SheetMode.PLAYLIST)
      assertTrue(driver.viewModel.uiState.value.selectedPlaylistIndices.isEmpty())

      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistRemButton(composeRule),
        "REMOVE",
        "REM SELECTED",
      )
      assertEquals(3, driver.engine.playbackState.value.queue.size)
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistRemButton(composeRule),
        "REMOVE",
        "CROP SELECTED",
      )
      assertEquals(3, driver.engine.playbackState.value.queue.size)
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)
    }
  }

  @Test // T2-F19-3
  fun f19_tier2_jumpFilterNoMatchMessageAndOriginalIndexPlaybackOnDoubleTap() {
    val queue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = queue,
    ).use { driver ->
      driver.ensureSheetExpanded(SheetMode.PLAYLIST)

      driver.setJumpFilterQuery("ZZZ_NO_MATCH_999")
      composeRule.onNodeWithText("NO MATCHING TRACKS").assertIsDisplayed()
      assertTrue(
        composeRule.onAllNodesWithText("PLAYLIST EMPTY — TAP [LIB] TO ADD TRACKS")
          .fetchSemanticsNodes()
          .isEmpty(),
      )

      // Uppercase filename substring matches case-insensitively and preserves originalIndex 2 (3.)
      driver.setJumpFilterQuery("03_DIGITAL")
      DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - Digital Love").assertIsDisplayed()
      composeRule.onNodeWithText("3.").assertIsDisplayed()

      DroidAmpSelectors.doubleTapNode(
        DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - Digital Love"),
      )
      composeRule.waitForIdle()
      assertEquals(2, driver.engine.playbackState.value.currentIndex)
      assertEquals("trk-dp-3", driver.engine.playbackState.value.currentTrack?.id)
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      // Closing filter restores all 4 rows
      DroidAmpSelectors.jumpFilterCloseButton(composeRule).performClick()
      composeRule.waitForIdle()
      DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - One More Time").assertIsDisplayed()
      DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - Harder, Better, Faster, Stronger").assertIsDisplayed()
    }
  }

  @Test // T2-F19-4
  fun f19_tier2_emptyQueueSaveM3u8RemAndMiscAreSafeNoOps() {
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = emptyList(),
    ).use { driver ->
      driver.ensureSheetExpanded(SheetMode.PLAYLIST)
      composeRule.onNodeWithText("PLAYLIST EMPTY — TAP [LIB] TO ADD TRACKS").assertIsDisplayed()

      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistListButton(composeRule),
        "LIST OPTS",
        "SAVE .M3U8",
      )
      assertTrue(driver.engine.playbackState.value.queue.isEmpty())

      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistMiscButton(composeRule),
        "MISC OPTS",
        "SORT BY TITLE",
      )
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistMiscButton(composeRule),
        "MISC OPTS",
        "REVERSE LIST",
      )
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistMiscButton(composeRule),
        "MISC OPTS",
        "RANDOMIZE",
      )
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistRemButton(composeRule),
        "REMOVE",
        "CLEAR LIST",
      )

      assertTrue(driver.engine.playbackState.value.queue.isEmpty())
      composeRule.onNodeWithText("PLAYLIST EMPTY — TAP [LIB] TO ADD TRACKS").assertIsDisplayed()
    }
  }

  @Test // T2-F19-5
  fun f19_tier2_listNewListClearsQueueAndTransitionsPlaybackToStopped() {
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS.take(3),
      autoPlay = true,
    ).use { driver ->
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      driver.ensureSheetExpanded(SheetMode.PLAYLIST)
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistListButton(composeRule),
        "LIST OPTS",
        "NEW LIST",
      )

      assertTrue(driver.engine.playbackState.value.queue.isEmpty())
      assertEquals(-1, driver.engine.playbackState.value.currentIndex)
      assertEquals(PlaybackStatus.STOPPED, driver.engine.playbackState.value.status)
      composeRule.onNodeWithText("PLAYLIST EMPTY — TAP [LIB] TO ADD TRACKS").assertIsDisplayed()
    }
  }

  // =========================================================================
  // Feature 20 Boundaries: EQ, PREAMP Gap, Swipe Buffer & Gesture Bar (4 tests here + 2 in MainActivity)
  // =========================================================================

  @Test // T2-F20-1
  fun f20_tier2_eqHideShowPreservesBandsAndPeekingSwipeBufferHoldsInBothStates() {
    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = true).use { driver ->
      driver.selectEqPreset(ClassicWinampPresets.CLASSICAL.name)
      DeviceManifestVerifier.assertPeekingSheetSwipeBuffer(composeRule)

      // Hide EQ panel
      driver.toggleEqVisibility()
      assertTrue(composeRule.onAllNodesWithText("DROIDAMP EQUALIZER").fetchSemanticsNodes().isEmpty())
      assertTrue(composeRule.onAllNodesWithText("PRE").fetchSemanticsNodes().isEmpty())
      DeviceManifestVerifier.assertPeekingSheetSwipeBuffer(composeRule)

      // Show EQ panel again -> state preserved
      driver.toggleEqVisibility()
      DroidAmpSelectors.eqTitleBar(composeRule).assertIsDisplayed()
      val eq = driver.engine.playbackState.value.equalizer
      assertEquals(ClassicWinampPresets.CLASSICAL.name, eq.activePresetName)
      assertEquals(ClassicWinampPresets.CLASSICAL.bandsDb, eq.bandsDb)
      DeviceManifestVerifier.assertPeekingSheetSwipeBuffer(composeRule)
    }
  }

  @Test // T2-F20-2
  fun f20_tier2_preampAndBandExtremesClampToHalfDbAndIsolateFromEachOther() {
    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = true).use { driver ->
      DroidAmpSelectors.setPreampDb(composeRule, +15f)
      composeRule.waitForIdle()
      val highPreamp = driver.engine.playbackState.value.equalizer.preampDb
      assertTrue("Expected preamp clamped to [-12, +12], got $highPreamp", highPreamp in -12f..12f)
      assertTrue("Expected preamp near +12dB, got $highPreamp", highPreamp >= 11.0f)
      assertEquals(0f, abs(highPreamp * 2f) % 1f, 0.001f)
      assertEquals(List(10) { 0f }, driver.engine.playbackState.value.equalizer.bandsDb)

      DroidAmpSelectors.setPreampDb(composeRule, -15f)
      composeRule.waitForIdle()
      val lowPreamp = driver.engine.playbackState.value.equalizer.preampDb
      assertTrue("Expected preamp clamped to [-12, +12], got $lowPreamp", lowPreamp in -12f..12f)
      assertTrue("Expected preamp near -12dB, got $lowPreamp", lowPreamp <= -11.0f)
      assertEquals(List(10) { 0f }, driver.engine.playbackState.value.equalizer.bandsDb)

      // Drag bands 0 and 9 to extremes: preampDb must stay completely unchanged
      DroidAmpSelectors.setEqBandDb(composeRule, 0, +15f)
      DroidAmpSelectors.setEqBandDb(composeRule, 9, -15f)
      composeRule.waitForIdle()
      val eq = driver.engine.playbackState.value.equalizer
      assertEquals(lowPreamp, eq.preampDb, 0.001f)
      assertTrue("Expected band 0 in [11, 12], got ${eq.bandsDb[0]}", eq.bandsDb[0] in 11.0f..12.0f)
      assertTrue("Expected band 9 in [-12, -11], got ${eq.bandsDb[9]}", eq.bandsDb[9] in -12.0f..-11.0f)
      assertEquals(0f, abs(eq.bandsDb[0] * 2f) % 1f, 0.001f)
      assertEquals(0f, abs(eq.bandsDb[9] * 2f) % 1f, 0.001f)
    }
  }

  @Test // T2-F20-3
  fun f20_tier2_autoEqSafeOnEmptyQueueAndOnBypassPreservesPresetAndFlatReset() {
    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = true, initialQueue = emptyList()).use { driver ->
      assertTrue(driver.engine.playbackState.value.queue.isEmpty())

      // Rapid AUTO toggles on empty queue
      driver.toggleEqAuto()
      driver.toggleEqAuto()
      driver.toggleEqAuto()
      assertTrue(driver.engine.playbackState.value.equalizer.autoEnabled)

      // Apply CLASSICAL, toggle ON off -> bands & preset preserved
      driver.selectEqPreset(ClassicWinampPresets.CLASSICAL.name)
      driver.toggleEqEnabled()
      val bypassed = driver.engine.playbackState.value.equalizer
      assertFalse(bypassed.enabled)
      assertEquals(ClassicWinampPresets.CLASSICAL.name, bypassed.activePresetName)
      assertEquals(ClassicWinampPresets.CLASSICAL.bandsDb, bypassed.bandsDb)

      // Toggle ON back on and reset to FLAT via popup
      driver.toggleEqEnabled()
      assertTrue(driver.engine.playbackState.value.equalizer.enabled)
      driver.selectEqPreset(ClassicWinampPresets.FLAT.name)
      val reset = driver.engine.playbackState.value.equalizer
      assertEquals(ClassicWinampPresets.FLAT.name, reset.activePresetName)
      assertEquals(List(10) { 0f }, reset.bandsDb)
    }
  }

  @Test // T2-F20-4
  fun f20_tier2_nonInteractiveSwipeBufferAndBottomLipTapDoNotExpandSheet() {
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS,
    ).use { driver ->
      val peekingTitle =
        composeRule
          .onNode(
            hasText("DROIDAMP PLAYLIST ▲") or hasText("DROIDAMP LIBRARY ▲"),
            useUnmergedTree = true,
          )
          .assertIsDisplayed()
          .fetchSemanticsNode()
      val handleNode = findClickableAncestor(peekingTitle) ?: peekingTitle
      val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot

      val bufferCenterY =
        (handleNode.boundsInRoot.top - with(composeRule.density) { 6.dp.toPx() })
          .coerceAtLeast(1f)
      composeRule.onRoot().performTouchInput {
        click(Offset(rootBounds.center.x, bufferCenterY))
      }
      composeRule.waitForIdle()
      DroidAmpSelectors.peekingSheetTitleBar(composeRule).assertIsDisplayed()
      assertTrue(
        composeRule
          .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
          .fetchSemanticsNodes()
          .isEmpty(),
      )
      assertFalse(driver.viewModel.uiState.value.isSheetExpanded)

      // Tap inside bottom 8dp non-interactive lip
      val lipCenterY =
        (rootBounds.bottom - with(composeRule.density) { 4.dp.toPx() })
          .coerceAtLeast(1f)
      composeRule.onRoot().performTouchInput {
        click(Offset(rootBounds.center.x, lipCenterY))
      }
      composeRule.waitForIdle()
      DroidAmpSelectors.peekingSheetTitleBar(composeRule).assertIsDisplayed()
      assertTrue(
        composeRule
          .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
          .fetchSemanticsNodes()
          .isEmpty(),
      )
      assertFalse(driver.viewModel.uiState.value.isSheetExpanded)

      // Populated queue + expanded micro-transport + EQ hidden bottom-lip clearance
      driver.ensureSheetExpanded(SheetMode.PLAYLIST)
      DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(composeRule)
      driver.collapseSheetByTitleTap()
      assertTrue(
        composeRule
          .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
          .fetchSemanticsNodes()
          .isEmpty(),
      )
      assertFalse(driver.viewModel.uiState.value.isSheetExpanded)
      driver.toggleEqVisibility()
      DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(composeRule)
    }
  }

  companion object {
    private val PREFS_COUNTER = AtomicInteger(0)
  }
}

@RunWith(AndroidJUnit4::class)
class Tier2StorageUiMainActivityBoundaryTest {

  @get:Rule
  val activityRule = createAndroidComposeRule<MainActivity>()

  @Test // T2-F18-5
  fun f18_tier2_mainActivityBackPressCollapsesSheetPreservesModeAndDoesNotFinish() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    driver.clickEjectOnDeck()
    activityRule.onNodeWithText("DROIDAMP LIBRARY ▼").assertIsDisplayed()

    Espresso.pressBack()
    activityRule.waitForIdle()

    assertFalse(activityRule.activity.isFinishing)
    activityRule.onNodeWithText("DROIDAMP LIBRARY ▲").assertIsDisplayed()
    assertTrue(
      activityRule
        .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
        .fetchSemanticsNodes()
        .isEmpty(),
    )

    // Re-expand remembers LIBRARY mode
    driver.expandSheetByTitleTap()
    activityRule.onNodeWithText("DROIDAMP LIBRARY ▼").assertIsDisplayed()
  }

  @Test // T2-F20-5
  fun f20_tier2_mainActivityGestureBarClearancePeekingAndExpandedAcrossBothModes() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    // Peeking state
    DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(activityRule)

    // Expanded PLAYLIST empty-well state (with micro-transport bar)
    driver.ensureSheetExpanded(SheetMode.PLAYLIST)
    DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(activityRule)

    // Expanded LIBRARY state
    driver.ensureSheetExpanded(SheetMode.LIBRARY)
    DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(activityRule)
  }

  @Test // T2-F20-6
  fun f20_tier2_mainActivityOfflineManifestZeroArtAcrossAllModesAndPopupBackDismiss() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    DeviceManifestVerifier.assertOfflineManifestPermissions(activityRule.activity)
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)

    // Open PRESETS popup, dismiss with Back without changing preset
    DroidAmpSelectors.eqPresetsButton(activityRule).performClick()
    activityRule.waitForIdle()
    DroidAmpSelectors.popupTitle(activityRule, "EQ PRESETS").assertIsDisplayed()
    Espresso.pressBack()
    activityRule.waitForIdle()
    assertTrue(
      activityRule.onAllNodesWithText("EQ PRESETS").fetchSemanticsNodes().isEmpty(),
    )

    // EQ hidden peeking
    driver.toggleEqVisibility()
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)
    driver.toggleEqVisibility()

    // Expanded PLAYLIST & LIBRARY tabs
    driver.ensureSheetExpanded(SheetMode.PLAYLIST)
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)

    driver.ensureSheetExpanded(SheetMode.LIBRARY)
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)
    DroidAmpSelectors.libraryPlaylistsTab(activityRule).performClick()
    activityRule.waitForIdle()
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)
  }
}
