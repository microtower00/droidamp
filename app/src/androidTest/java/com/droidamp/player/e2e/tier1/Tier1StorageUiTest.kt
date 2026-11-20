package com.droidamp.player.e2e.tier1

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidamp.player.MainActivity
import com.droidamp.player.domain.contract.AutoEqPresetResolver
import com.droidamp.player.domain.contract.MusicSourceRepository
import com.droidamp.player.domain.contract.PlaylistFileCodec
import com.droidamp.player.domain.contract.Q2Codec
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.LibraryTab
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Tier1StorageUiTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val targetContext: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val playlistCodec: PlaylistFileCodec by lazy {
    loadContract("com.droidamp.player.interop.WinampPlaylistCodec", PlaylistFileCodec::class.java)
  }

  private val q2Codec: Q2Codec by lazy {
    loadContract("com.droidamp.player.interop.WinampQ2Codec", Q2Codec::class.java)
  }

  private val autoEqResolver: AutoEqPresetResolver by lazy {
    loadContract("com.droidamp.player.interop.AutoEqMatcher", AutoEqPresetResolver::class.java)
  }

  // -------------------------------------------------------------------------
  // Reflective M3 / M4 Wiring Helpers (Zero M2–M4 Compile-Time Imports)
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
            val list = mutableChildren.getOrPut(dirUri) { mutableListOf() }
            list.removeAll {
              val n = it.javaClass.getMethod("getName").invoke(it) as? String
              n.equals(fileName, ignoreCase = true)
            }
            list.add(node)
            spec.textFilesByDocUri[docUri] = content
            node
          }
        }
        "equals" -> false
        "hashCode" -> 0
        "toString" -> "SafTreeGatewayProxy"
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

  private fun newMediaStorePlaylistRecord(
    playlistId: Long,
    contentUriString: String,
    name: String,
    memberTrackIds: List<Long>,
    filePath: String = "",
  ): Any {
    val cls = Class.forName("com.droidamp.player.storage.MediaStorePlaylistRecord")
    val ctor =
      cls.getDeclaredConstructor(
        Long::class.javaPrimitiveType,
        String::class.java,
        String::class.java,
        List::class.java,
        String::class.java,
      )
    return ctor.newInstance(playlistId, contentUriString, name, memberTrackIds, filePath)
  }

  private fun newMediaStorePlaylistFileRecord(
    id: String,
    uriString: String,
    name: String,
    format: String,
    folderPath: String,
  ): Any {
    val cls = Class.forName("com.droidamp.player.storage.MediaStorePlaylistFileRecord")
    val ctor =
      cls.getDeclaredConstructor(
        String::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
      )
    return ctor.newInstance(id, uriString, name, format, folderPath)
  }

  private fun createMediaStoreGatewayProxy(
    hasPermission: Boolean,
    audioRecords: List<Any> = emptyList(),
    playlists: List<Any> = emptyList(),
    playlistFiles: List<Any> = emptyList(),
    textFiles: MutableMap<String, String> = mutableMapOf(),
  ): Any {
    val gatewayClass = Class.forName("com.droidamp.player.storage.MediaStoreGateway")
    return Proxy.newProxyInstance(
      gatewayClass.classLoader,
      arrayOf(gatewayClass),
    ) { _, method, args ->
      when (method.name) {
        "hasAudioReadPermission" -> java.lang.Boolean.valueOf(hasPermission)
        "queryAudioTracks" -> if (hasPermission) audioRecords else emptyList<Any>()
        "queryPlaylists" -> if (hasPermission) playlists else emptyList<Any>()
        "queryPlaylistFiles" -> playlistFiles
        "readTextFile" -> {
          val uriOrPath = args?.getOrNull(0) as? String
          textFiles[uriOrPath]
        }
        "writeM3u8PlaylistFile" -> {
          val fileName = args?.getOrNull(0) as? String ?: "export.m3u8"
          val content = args?.getOrNull(1) as? String ?: ""
          val uri = "/app/files/playlists/$fileName"
          textFiles[uri] = content
          newMediaStorePlaylistFileRecord("ms-file:$uri", uri, fileName, "m3u8", "/app/files/playlists")
        }
        "equals" -> false
        "hashCode" -> 0
        "toString" -> "MediaStoreGatewayProxy"
        else -> null
      }
    }
  }

  private fun createProbeProxy(
    metadataByUri: Map<String, Any> = emptyMap(),
  ): Any {
    val probeClass = Class.forName("com.droidamp.player.storage.AudioMetadataProbe")
    return Proxy.newProxyInstance(
      probeClass.classLoader,
      arrayOf(probeClass),
    ) { _, method, args ->
      when (method.name) {
        "probe" -> {
          val uri = args?.getOrNull(0) as? String
          metadataByUri[uri]
        }
        "equals" -> false
        "hashCode" -> 0
        "toString" -> "AudioMetadataProbeProxy"
        else -> null
      }
    }
  }

  private fun newProbedMetadata(
    title: String? = null,
    artist: String? = null,
    album: String? = null,
    durationMs: Long = 180_000L,
    bitrateKbps: Int = 320,
    sampleRateHz: Int = 44_100,
    channelMode: ChannelMode? = ChannelMode.STEREO,
  ): Any {
    val cls = Class.forName("com.droidamp.player.storage.ProbedAudioMetadata")
    val ctor =
      cls.getDeclaredConstructor(
        String::class.java,
        String::class.java,
        String::class.java,
        Long::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        ChannelMode::class.java,
      )
    return ctor.newInstance(title, artist, album, durationMs, bitrateKbps, sampleRateHz, channelMode)
  }

  private data class ReflectiveStorageStack(
    val scope: CoroutineScope,
    val safRepo: MusicSourceRepository,
    val mediaStoreRepo: MusicSourceRepository,
    val switchingRepo: MusicSourceRepository,
    val dataStore: DataStore<Preferences>,
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
    probe: Any = createProbeProxy(),
  ): ReflectiveStorageStack {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore =
      PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { prefsFile },
      )
    return buildStorageStackWithDataStore(
      scope = scope,
      dataStore = dataStore,
      safGateway = safGateway,
      mediaStoreGateway = mediaStoreGateway,
      probe = probe,
    )
  }

  private fun buildStorageStackWithDataStore(
    scope: CoroutineScope,
    dataStore: DataStore<Preferences>,
    safGateway: Any? = null,
    mediaStoreGateway: Any? = null,
    probe: Any = createProbeProxy(),
  ): ReflectiveStorageStack {
    val prefsStoreClass = Class.forName("com.droidamp.player.storage.StoragePreferencesStore")
    val prefsStore = prefsStoreClass.getDeclaredConstructor(DataStore::class.java).newInstance(dataStore)

    val probeInterface = Class.forName("com.droidamp.player.storage.AudioMetadataProbe")
    val extractorClass = Class.forName("com.droidamp.player.storage.AudioMetadataExtractor")
    val extractor =
      extractorClass.getDeclaredConstructor(
        probeInterface,
        Int::class.javaPrimitiveType,
        CoroutineDispatcher::class.java,
      ).newInstance(probe, 256, Dispatchers.IO)

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
      dataStore = dataStore,
    )
  }

  private fun prefsFile(scopeDir: File): File =
    File(scopeDir, "storage_prefs_${PREFS_COUNTER.incrementAndGet()}.preferences_pb")

  private fun sortSampleTracks(): List<AudioTrack> =
    listOf(
      AudioTrack(
        id = "sort-1",
        uriString = "content://tree/1/z_file.mp3",
        title = "Alpha Song",
        artist = "Artist C",
        fileName = "03_zulu.mp3",
        folderPath = "Music",
        durationMs = 120_000L,
      ),
      AudioTrack(
        id = "sort-2",
        uriString = "content://tree/1/a_file.mp3",
        title = "Zulu Song",
        artist = "Artist A",
        fileName = "01_alpha.mp3",
        folderPath = "Music",
        durationMs = 150_000L,
      ),
      AudioTrack(
        id = "sort-3",
        uriString = "content://tree/1/m_file.mp3",
        title = "Mid Song",
        artist = "Artist B",
        fileName = "02_mike.mp3",
        folderPath = "Music",
        durationMs = 180_000L,
      ),
      AudioTrack(
        id = "sort-4",
        uriString = "content://tree/1/b_file.mp3",
        title = "Beta Song",
        artist = "Artist D",
        fileName = "00_bravo.mp3",
        folderPath = "Music",
        durationMs = 200_000L,
      ),
    )

  // =========================================================================
  // Feature 16: MusicSourceRepository — SAF Default Backend (6 Tier 1 Tests)
  // =========================================================================

  @Test // T1-F16-1
  fun f16_tier1_safIsActiveDefaultBackendOnSafAndSwitchingRepositories() {
    SyntheticAudioFixtures.createCacheScope(targetContext, "t1_f16_default").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir)).use { stack ->
        runBlocking {
          assertEquals(StorageBackend.SAF, stack.safRepo.storageBackend.value)
          assertEquals(StorageBackend.SAF, stack.switchingRepo.storageBackend.value)
          assertTrue(stack.safRepo.linkedRoots.value.isEmpty())
          assertEquals(FolderContents.EMPTY, stack.safRepo.browseFolder(null))
          assertTrue(stack.safRepo.listPlaylists().isEmpty())
        }
      }
    }
  }

  @Test // T1-F16-2
  fun f16_tier1_linkSafRootAndMultiLevelFolderTraversalWithUpNavigation() {
    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val rootDoc = "$treeUri/document/primary%3AMusic"
    val rockDoc = "$treeUri/document/primary%3AMusic%2FRock"
    val classicDoc = "$treeUri/document/primary%3AMusic%2FRock%2FClassic"

    val spec =
      SafTreeSpec(
        treeUri = treeUri,
        rootDocUri = rootDoc,
        rootName = "Music",
        childrenByDirUri =
          mapOf(
            rootDoc to
              listOf(
                newSafNode(rockDoc, "Rock", isDirectory = true, mimeType = "vnd.android.document/directory"),
                newSafNode("$rootDoc%2Fintro.mp3", "00_intro.mp3", isDirectory = false, mimeType = "audio/mpeg"),
              ),
            rockDoc to
              listOf(
                newSafNode(classicDoc, "Classic", isDirectory = true, mimeType = "vnd.android.document/directory"),
                newSafNode("$rockDoc%2Friff.mp3", "01_riff.mp3", isDirectory = false, mimeType = "audio/mpeg"),
              ),
            classicDoc to
              listOf(
                newSafNode("$classicDoc%2Fsolo.wav", "02_solo.wav", isDirectory = false, mimeType = "audio/wav"),
              ),
          ),
      )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t1_f16_tree").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir), safGateway = createSafGatewayProxy(listOf(spec))).use { stack ->
        runBlocking {
          stack.safRepo.linkSafRoot(treeUri)
          assertEquals(1, stack.safRepo.linkedRoots.value.size)

          val rootContents = stack.safRepo.browseFolder(null)
          assertEquals(listOf("Music"), rootContents.currentPathSegments)
          assertFalse(rootContents.canNavigateUp)
          assertEquals(listOf("Rock"), rootContents.folders.map { it.name })
          assertEquals(listOf("00_intro.mp3"), rootContents.tracks.map { it.fileName })

          val rockContents = stack.safRepo.browseFolder(rootContents.folders.first().uriString)
          assertTrue(rockContents.canNavigateUp)
          assertEquals(listOf("Music", "Rock"), rockContents.currentPathSegments)
          assertEquals(listOf("Classic"), rockContents.folders.map { it.name })
          assertEquals(listOf("01_riff.mp3"), rockContents.tracks.map { it.fileName })

          val classicContents = stack.safRepo.browseFolder(rockContents.folders.first().uriString)
          assertTrue(classicContents.canNavigateUp)
          assertEquals(listOf("Music", "Rock", "Classic"), classicContents.currentPathSegments)
          assertEquals(listOf("02_solo.wav"), classicContents.tracks.map { it.fileName })

          val upToRock = stack.safRepo.browseFolder(classicContents.parentFolderUri)
          assertEquals(listOf("Music", "Rock"), upToRock.currentPathSegments)

          val upToRoot = stack.safRepo.browseFolder(upToRock.parentFolderUri)
          assertEquals(listOf("Music"), upToRoot.currentPathSegments)
          assertFalse(upToRoot.canNavigateUp)
        }
      }
    }
  }

  @Test // T1-F16-3
  fun f16_tier1_safPlaylistDiscoveryAndResolutionForM3uM3u8AndPls() {
    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val rootDoc = "$treeUri/document/primary%3AMusic"
    val subDoc = "$treeUri/document/primary%3AMusic%2FSets"
    val track1Uri = "$rootDoc%2F01_opener.mp3"
    val track2Uri = "$subDoc%2F02_peak.flac"

    val m3uText = "01_opener.mp3\nSets/02_peak.flac\n"
    val m3u8Text =
      SyntheticWinampFixtures.m3u8Content(
        listOf(
          SyntheticWinampFixtures.PlaylistEntrySpec("Sets/02_peak.flac", "Peak", 240L),
          SyntheticWinampFixtures.PlaylistEntrySpec("01_opener.mp3", "Opener", 180L),
        ),
      )
    val plsText =
      SyntheticWinampFixtures.plsContent(
        listOf(
          SyntheticWinampFixtures.PlaylistEntrySpec("01_opener.mp3", "Opener", 180L),
        ),
      )

    val spec =
      SafTreeSpec(
        treeUri = treeUri,
        rootDocUri = rootDoc,
        rootName = "Music",
        childrenByDirUri =
          mapOf(
            rootDoc to
              listOf(
                newSafNode(subDoc, "Sets", isDirectory = true, mimeType = "vnd.android.document/directory"),
                newSafNode(track1Uri, "01_opener.mp3", isDirectory = false, mimeType = "audio/mpeg"),
                newSafNode("$rootDoc%2Falpha.m3u", "alpha.m3u", isDirectory = false, mimeType = "audio/x-mpegurl"),
                newSafNode("$rootDoc%2Fbeta.m3u8", "beta.m3u8", isDirectory = false, mimeType = "application/vnd.apple.mpegurl"),
                newSafNode("$rootDoc%2Fgamma.pls", "gamma.pls", isDirectory = false, mimeType = "audio/x-scpls"),
              ),
            subDoc to
              listOf(
                newSafNode(track2Uri, "02_peak.flac", isDirectory = false, mimeType = "audio/flac"),
              ),
          ),
        textFilesByDocUri =
          mutableMapOf(
            "$rootDoc%2Falpha.m3u" to m3uText,
            "$rootDoc%2Fbeta.m3u8" to m3u8Text,
            "$rootDoc%2Fgamma.pls" to plsText,
          ),
      )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t1_f16_playlists").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir), safGateway = createSafGatewayProxy(listOf(spec))).use { stack ->
        runBlocking {
          stack.safRepo.linkSafRoot(treeUri)
          val playlists = stack.safRepo.listPlaylists()
          assertEquals(listOf("alpha", "beta", "gamma"), playlists.map { it.name })
          assertEquals(listOf("m3u", "m3u8", "pls"), playlists.map { it.format })
          assertEquals(listOf(2, 2, 1), playlists.map { it.trackCount })

          val resolvedM3u8 = stack.safRepo.resolvePlaylistTracks(playlists[1].id)
          assertEquals(listOf("02_peak.flac", "01_opener.mp3"), resolvedM3u8.map { it.fileName })

          val resolvedPls = stack.safRepo.resolvePlaylistTracks(playlists[2].id)
          assertEquals(listOf("01_opener.mp3"), resolvedPls.map { it.fileName })
        }
      }
    }
  }

  @Test // T1-F16-4
  fun f16_tier1_safResolveFolderTracksRecursiveTrueVsFalse() {
    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val rootDoc = "$treeUri/document/primary%3AMusic"
    val rockDoc = "$treeUri/document/primary%3AMusic%2FRock"
    val deepDoc = "$treeUri/document/primary%3AMusic%2FRock%2FDeep"

    val spec =
      SafTreeSpec(
        treeUri = treeUri,
        rootDocUri = rootDoc,
        rootName = "Music",
        childrenByDirUri =
          mapOf(
            rootDoc to
              listOf(
                newSafNode(rockDoc, "Rock", isDirectory = true, mimeType = "vnd.android.document/directory"),
              ),
            rockDoc to
              listOf(
                newSafNode(deepDoc, "Deep", isDirectory = true, mimeType = "vnd.android.document/directory"),
                newSafNode("$rockDoc%2Fdirect.mp3", "01_direct.mp3", isDirectory = false, mimeType = "audio/mpeg"),
              ),
            deepDoc to
              listOf(
                newSafNode("$deepDoc%2Fnested1.mp3", "02_nested1.mp3", isDirectory = false, mimeType = "audio/mpeg"),
                newSafNode("$deepDoc%2Fnested2.wav", "03_nested2.wav", isDirectory = false, mimeType = "audio/wav"),
              ),
          ),
      )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t1_f16_recursive").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir), safGateway = createSafGatewayProxy(listOf(spec))).use { stack ->
        runBlocking {
          stack.safRepo.linkSafRoot(treeUri)
          val rockUri = stack.safRepo.browseFolder(null).folders.first().uriString

          val directOnly = stack.safRepo.resolveFolderTracks(rockUri, recursive = false)
          assertEquals(listOf("01_direct.mp3"), directOnly.map { it.fileName })

          val allRecursive = stack.safRepo.resolveFolderTracks(rockUri, recursive = true)
          assertEquals(
            listOf("01_direct.mp3", "02_nested1.mp3", "03_nested2.wav"),
            allRecursive.map { it.fileName },
          )
        }
      }
    }
  }

  @Test // T1-F16-5
  fun f16_tier1_offlineMetadataExtractionOnSyntheticWavAndId3Mp3HasZeroArtFields() {
    SyntheticAudioFixtures.createCacheScope(targetContext, "t1_f16_meta").use { cache ->
      val wavBytes =
        SyntheticAudioFixtures.generateWav(
          sampleRateHz = 48_000,
          channels = 1,
          durationMs = 600L,
        )
      val mp3Bytes =
        SyntheticAudioFixtures.generateMp3(
          sampleRateHz = 44_100,
          bitrateKbps = 128,
          channels = 2,
          durationMs = 700L,
          metadata =
            SyntheticAudioFixtures.Id3MetadataSpec(
              title = "Neon Drift",
              artist = "Synth Master",
              album = "Rack 99",
              artworkPngBytes = SyntheticAudioFixtures.generateMinimal1x1Png(),
            ),
        )
      val wavFile = cache.writeFile("01_tone.wav", wavBytes)
      val mp3File = cache.writeFile("02_drift.mp3", mp3Bytes)

      val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ASynth"
      val rootDoc = "$treeUri/document/primary%3ASynth"
      val spec =
        SafTreeSpec(
          treeUri = treeUri,
          rootDocUri = rootDoc,
          rootName = "Synth",
          childrenByDirUri =
            mapOf(
              rootDoc to
                listOf(
                  newSafNode(cache.fileUri(wavFile), "01_tone.wav", isDirectory = false, mimeType = "audio/wav"),
                  newSafNode(cache.fileUri(mp3File), "02_drift.mp3", isDirectory = false, mimeType = "audio/mpeg"),
                ),
            ),
        )

      val realProbe =
        Class.forName("com.droidamp.player.storage.AndroidAudioMetadataProbe")
          .getDeclaredConstructor(Context::class.java)
          .newInstance(targetContext)

      buildStorageStack(
        prefsFile = prefsFile(cache.rootDir),
        safGateway = createSafGatewayProxy(listOf(spec)),
        probe = realProbe,
      ).use { stack ->
        runBlocking {
          stack.safRepo.linkSafRoot(treeUri)
          val contents = stack.safRepo.browseFolder(null)
          assertEquals(2, contents.tracks.size)

          val wavTrack = contents.tracks.first { it.fileName == "01_tone.wav" }
          assertEquals(48_000, wavTrack.sampleRateHz)
          assertEquals(ChannelMode.MONO, wavTrack.channelMode)
          assertTrue("WAV duration should be > 0, got ${wavTrack.durationMs}", wavTrack.durationMs > 0L)

          val mp3Track = contents.tracks.first { it.fileName == "02_drift.mp3" }
          assertEquals("Neon Drift", mp3Track.title)
          assertEquals("Synth Master", mp3Track.artist)
          assertEquals("Rack 99", mp3Track.album)
          assertEquals(44_100, mp3Track.sampleRateHz)

          val artFields =
            AudioTrack::class.java.declaredFields
              .filter { field ->
                val name = field.name.lowercase()
                val typeName = field.type.name.lowercase()
                val hasArtName =
                  name == "art" ||
                    "artwork" in name ||
                    "albumart" in name ||
                    "embeddedart" in name ||
                    "arturi" in name ||
                    "artbytes" in name ||
                    "bitmap" in name ||
                    "cover" in name ||
                    "thumb" in name ||
                    "picture" in name
                val hasArtType =
                  "bitmap" in typeName ||
                    "drawable" in typeName ||
                    "image" in typeName
                hasArtName || hasArtType
              }
              .map { "${it.name}:${it.type.simpleName}" }
          assertTrue("AudioTrack must expose zero art fields, found $artFields", artFields.isEmpty())
        }
      }
    }
  }

  @Test // T1-F16-6
  fun f16_tier1_openDocumentTreeIntentReadinessAndUnlinkAllRoots() {
    val treeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, treeIntent.action)
    val pm = targetContext.packageManager
    val resolvedActivities =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(treeIntent, PackageManager.ResolveInfoFlags.of(0L))
      } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(treeIntent, 0)
      }
    assertNotNull(resolvedActivities)

    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val rootDoc = "$treeUri/document/primary%3AMusic"
    val persisted = mutableSetOf<String>()
    val spec = SafTreeSpec(treeUri, rootDoc, "Music", mapOf(rootDoc to emptyList()))

    SyntheticAudioFixtures.createCacheScope(targetContext, "t1_f16_unlink").use { cache ->
      val pFile = prefsFile(cache.rootDir)
      buildStorageStack(pFile, safGateway = createSafGatewayProxy(listOf(spec), persisted)).use { stack ->
        runBlocking {
          stack.safRepo.linkSafRoot(treeUri)
          assertEquals(1, stack.safRepo.linkedRoots.value.size)
          assertTrue(treeUri in persisted)

          stack.safRepo.unlinkAllRoots()
          assertTrue(stack.safRepo.linkedRoots.value.isEmpty())
          assertTrue(persisted.isEmpty())
          assertEquals(FolderContents.EMPTY, stack.safRepo.browseFolder(null))
        }
      }
    }
  }

  // =========================================================================
  // Feature 17: MusicSourceRepository — MediaStore Optional Backend (5 Tier 1 Tests)
  // =========================================================================

  @Test // T1-F17-1
  fun f17_tier1_switchingSafToMediaStorePersistsAcrossInstancesAndFakeToggle() {
    SyntheticAudioFixtures.createCacheScope(targetContext, "t1_f17_switch").use { cache ->
      val pFile = prefsFile(cache.rootDir)
      buildStorageStack(pFile).use { stack1 ->
        runBlocking {
          assertEquals(StorageBackend.SAF, stack1.switchingRepo.storageBackend.value)
          stack1.switchingRepo.setStorageBackend(StorageBackend.MEDIA_STORE)
          assertEquals(StorageBackend.MEDIA_STORE, stack1.switchingRepo.storageBackend.value)
        }
      }

      // Fresh SwitchingMusicSourceRepository reading the same DataStore file
      buildStorageStack(pFile).use { stack2 ->
        runBlocking {
          stack2.switchingRepo.browseFolder(null)
          assertEquals(StorageBackend.MEDIA_STORE, stack2.switchingRepo.storageBackend.value)
        }
      }

      runBlocking {
        val fakeRepo = FakeMusicSourceRepository(startLinked = true)
        assertEquals(StorageBackend.SAF, fakeRepo.storageBackend.value)
        fakeRepo.setStorageBackend(StorageBackend.MEDIA_STORE)
        assertEquals(StorageBackend.MEDIA_STORE, fakeRepo.storageBackend.value)
      }
    }
  }

  @Test // T1-F17-2
  fun f17_tier1_mediaStoreHierarchicalFolderBrowsingAndRecursiveResolve() {
    val records =
      listOf(
        newMediaStoreAudioRecord(
          mediaId = 101L,
          contentUriString = "content://media/external/audio/media/101",
          displayName = "01_direct.mp3",
          title = "Direct Hit",
          artist = "Rock Band",
          album = "LP1",
          durationMs = 190_000L,
          relativePath = "Music/Rock/",
        ),
        newMediaStoreAudioRecord(
          mediaId = 102L,
          contentUriString = "content://media/external/audio/media/102",
          displayName = "02_live.flac",
          title = "Live Encore",
          artist = "Rock Band",
          album = "Bootleg",
          durationMs = 240_000L,
          relativePath = "Music/Rock/Live/",
        ),
        newMediaStoreAudioRecord(
          mediaId = 103L,
          contentUriString = "content://media/external/audio/media/103",
          displayName = "03_pulse.mp3",
          title = "Pulse",
          artist = "Synth Lab",
          album = "Neon",
          durationMs = 210_000L,
          relativePath = "Music/Synth/",
        ),
      )

    val msGateway = createMediaStoreGatewayProxy(hasPermission = true, audioRecords = records)
    SyntheticAudioFixtures.createCacheScope(targetContext, "t1_f17_hierarchy").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir), mediaStoreGateway = msGateway).use { stack ->
        runBlocking {
          assertEquals(StorageBackend.MEDIA_STORE, stack.mediaStoreRepo.storageBackend.value)

          val root = stack.mediaStoreRepo.browseFolder(null)
          assertFalse(root.canNavigateUp)
          assertEquals(listOf("MEDIASTORE"), root.currentPathSegments)
          assertEquals(listOf("Music"), root.folders.map { it.name })
          assertEquals(1, stack.mediaStoreRepo.linkedRoots.value.size)

          val musicFolder = stack.mediaStoreRepo.browseFolder(root.folders.first().uriString)
          assertTrue(musicFolder.canNavigateUp)
          assertEquals(listOf("MEDIASTORE", "Music"), musicFolder.currentPathSegments)
          assertEquals(listOf("Rock", "Synth"), musicFolder.folders.map { it.name })

          val rockEntry = musicFolder.folders.first { it.name == "Rock" }
          val rockContents = stack.mediaStoreRepo.browseFolder(rockEntry.uriString)
          assertEquals(listOf("Live"), rockContents.folders.map { it.name })
          assertEquals(listOf("01_direct.mp3"), rockContents.tracks.map { it.fileName })

          val nonRecursive = stack.mediaStoreRepo.resolveFolderTracks(rockEntry.uriString, recursive = false)
          assertEquals(listOf("01_direct.mp3"), nonRecursive.map { it.fileName })

          val recursive = stack.mediaStoreRepo.resolveFolderTracks(rockEntry.uriString, recursive = true)
          assertEquals(listOf("01_direct.mp3", "02_live.flac"), recursive.map { it.fileName })
        }
      }
    }
  }

  @Test // T1-F17-3
  fun f17_tier1_mediaStorePlaylistDiscoveryAndTrackResolution() {
    val audioRecords =
      listOf(
        newMediaStoreAudioRecord(
          mediaId = 1L,
          contentUriString = "content://media/external/audio/media/1",
          displayName = "track_one.mp3",
          title = "Track One",
          artist = "Artist A",
          album = "Album A",
          durationMs = 150_000L,
          relativePath = "Music/",
          dataPath = "/storage/emulated/0/Music/track_one.mp3",
        ),
        newMediaStoreAudioRecord(
          mediaId = 2L,
          contentUriString = "content://media/external/audio/media/2",
          displayName = "track_two.mp3",
          title = "Track Two",
          artist = "Artist B",
          album = "Album B",
          durationMs = 200_000L,
          relativePath = "Music/",
          dataPath = "/storage/emulated/0/Music/track_two.mp3",
        ),
      )
    val nativePl =
      newMediaStorePlaylistRecord(
        playlistId = 77L,
        contentUriString = "content://media/external/audio/playlists/77",
        name = "Roadtrip",
        memberTrackIds = listOf(2L, 1L),
      )
    val m3u8Uri = "/app/files/playlists/night_mix.m3u8"
    val filePl =
      newMediaStorePlaylistFileRecord(
        id = "ms-file:$m3u8Uri",
        uriString = m3u8Uri,
        name = "night_mix.m3u8",
        format = "m3u8",
        folderPath = "/app/files/playlists",
      )
    val m3u8Content =
      SyntheticWinampFixtures.m3u8Content(
        listOf(SyntheticWinampFixtures.PlaylistEntrySpec("Music/track_one.mp3", "Track One", 150L)),
      )

    val msGateway =
      createMediaStoreGatewayProxy(
        hasPermission = true,
        audioRecords = audioRecords,
        playlists = listOf(nativePl),
        playlistFiles = listOf(filePl),
        textFiles = mutableMapOf(m3u8Uri to m3u8Content),
      )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t1_f17_playlists").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir), mediaStoreGateway = msGateway).use { stack ->
        runBlocking {
          val listed = stack.mediaStoreRepo.listPlaylists()
          assertEquals(2, listed.size)
          assertEquals(listOf("night_mix", "Roadtrip"), listed.map { it.name })

          val roadtripTracks = stack.mediaStoreRepo.resolvePlaylistTracks("ms-pl:77")
          assertEquals(listOf("track_two.mp3", "track_one.mp3"), roadtripTracks.map { it.fileName })

          val nightMixTracks = stack.mediaStoreRepo.resolvePlaylistTracks("ms-file:$m3u8Uri")
          assertEquals(listOf("track_one.mp3"), nightMixTracks.map { it.fileName })
        }
      }
    }
  }

  @Test // T1-F17-4
  fun f17_tier1_manifestDeclaresMediaStorePermissionsWhileOfflineAndSafRemainsDefault() {
    val perms = DeviceManifestVerifier.getRequestedManifestPermissions(targetContext)
    assertTrue(
      "Merged manifest must declare READ_MEDIA_AUDIO for optional MediaStore backend",
      Manifest.permission.READ_MEDIA_AUDIO in perms,
    )
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
      assertTrue(
        "Merged manifest must declare READ_EXTERNAL_STORAGE on API <= 32",
        Manifest.permission.READ_EXTERNAL_STORAGE in perms,
      )
    }
    DeviceManifestVerifier.assertOfflineManifestPermissions(targetContext)

    SyntheticAudioFixtures.createCacheScope(targetContext, "t1_f17_default_saf").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir)).use { stack ->
        assertEquals(StorageBackend.SAF, stack.switchingRepo.storageBackend.value)
      }
    }
  }

  @Test // T1-F17-5
  fun f17_tier1_mediaStoreSaveM3u8ToAppPrivateDirAndNoOpSafRootCalls() {
    val fileName = "tier1_e2e_export_${System.currentTimeMillis()}.m3u8"
    val expectedFile = File(File(targetContext.filesDir, "playlists"), fileName)
    val content =
      SyntheticWinampFixtures.m3u8Content(
        listOf(SyntheticWinampFixtures.PlaylistEntrySpec("Music/song.mp3", "Song", 180L)),
      )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t1_f17_export").use { cache ->
      buildStorageStack(prefsFile(cache.rootDir)).use { stack ->
        try {
          runBlocking {
            val result = stack.mediaStoreRepo.saveM3u8Playlist(fileName, content)
            assertTrue("Expected saveM3u8Playlist success: $result", result.isSuccess)
            val entry = result.getOrThrow()
            assertEquals("m3u8", entry.format)
            assertTrue(expectedFile.isFile)
            assertEquals(content, expectedFile.readText(Charsets.UTF_8))

            // linkSafRoot / unlinkAllRoots are safe no-ops on MediaStore backend
            stack.mediaStoreRepo.linkSafRoot("content://com.android.externalstorage.documents/tree/primary%3AMusic")
            stack.mediaStoreRepo.unlinkAllRoots()
            assertEquals(StorageBackend.MEDIA_STORE, stack.mediaStoreRepo.storageBackend.value)
          }
        } finally {
          expectedFile.delete()
        }
      }
    }
  }

  // =========================================================================
  // Feature 18: Live Onboarding SAF Picker, [FOLDERS]/[PLAYLISTS], Select-First (4 tests here + 1 in MainActivity)
  // =========================================================================

  @Test // T1-F18-2
  fun f18_tier1_linkingSafRootPopulatesFoldersHierarchyAndParentNavigation() {
    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = false).use { driver ->
      DroidAmpSelectors.emptyFolderTitlePeeking(composeRule).assertIsDisplayed()

      driver.linkFakeSafRoot()

      composeRule.onNodeWithText("DROIDAMP LIBRARY ▼").assertIsDisplayed()
      composeRule.onNodeWithText("MUSIC").assertIsDisplayed()
      DroidAmpSelectors.libraryFolderRow(composeRule, "Electronic").assertIsDisplayed()
      DroidAmpSelectors.libraryFolderRow(composeRule, "Synthwave").assertIsDisplayed()
      DroidAmpSelectors.libraryFolderRow(composeRule, "FLAC Demos").assertIsDisplayed()
      DroidAmpSelectors.libraryTrackRow(
        composeRule,
        "DJ Mike Llama - DroidAmp Intro (Llama Whippin' Mix)",
      ).assertIsDisplayed()
      composeRule.onAllNodesWithText("[..]  PARENT FOLDER").fetchSemanticsNodes().let { nodes ->
        assertTrue("Root folder should not display [..] PARENT FOLDER", nodes.isEmpty())
      }

      DroidAmpSelectors.libraryFolderRow(composeRule, "Electronic").performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithText("MUSIC / ELECTRONIC").assertIsDisplayed()
      DroidAmpSelectors.libraryParentFolderRow(composeRule).assertIsDisplayed()
      DroidAmpSelectors.libraryTrackRow(
        composeRule,
        "The Chemical Brothers - Block Rockin' Beats",
      ).assertIsDisplayed()

      DroidAmpSelectors.libraryParentFolderRow(composeRule).performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithText("MUSIC").assertIsDisplayed()
    }
  }

  @Test // T1-F18-3
  fun f18_tier1_selectFirstTrackSafetyDoesNotDisturbActivePlayback() {
    val initialQueue = FakeMusicSourceRepository.ROOT_TRACKS
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = initialQueue,
      autoPlay = true,
    ).use { driver ->
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)
      assertEquals("trk-llama", driver.engine.playbackState.value.currentTrack?.id)

      driver.ensureSheetExpanded(SheetMode.LIBRARY)
      DroidAmpSelectors.libraryFolderRow(composeRule, "Electronic").performClick()
      composeRule.waitForIdle()

      // Single tap selects ONLY — must not stop or replace current playback
      val chemRow =
        DroidAmpSelectors.libraryTrackRow(composeRule, "The Chemical Brothers - Block Rockin' Beats")
      DroidAmpSelectors.singleTapNode(composeRule, chemRow)

      composeRule.onNodeWithText("▶ PLAY (1)").assertIsDisplayed()
      assertEquals(1, driver.engine.playbackState.value.queue.size)
      assertEquals("trk-llama", driver.engine.playbackState.value.currentTrack?.id)
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      // Tapping again deselects back to ▶ PLAY
      DroidAmpSelectors.singleTapNode(composeRule, chemRow)
      composeRule.onNodeWithText("▶ PLAY").assertIsDisplayed()
      assertEquals("trk-llama", driver.engine.playbackState.value.currentTrack?.id)
    }
  }

  @Test // T1-F18-4
  fun f18_tier1_doubleTapAndInlinePlayReplaceQueueWhileQueueEnqueuesInLibrary() {
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = FakeMusicSourceRepository.ROOT_TRACKS,
      autoPlay = true,
    ).use { driver ->
      driver.ensureSheetExpanded(SheetMode.LIBRARY)
      DroidAmpSelectors.libraryFolderRow(composeRule, "Electronic").performClick()
      composeRule.waitForIdle()

      // Inline [+] enqueues without leaving LIBRARY
      DroidAmpSelectors.libraryTrackEnqueueButton(
        composeRule,
        "The Chemical Brothers - Block Rockin' Beats",
      ).performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithText("DROIDAMP LIBRARY ▼").assertIsDisplayed()
      assertEquals(2, driver.engine.playbackState.value.queue.size)
      assertEquals("trk-llama", driver.engine.playbackState.value.currentTrack?.id)

      // Select track + [+ QUEUE] enqueues and clears selection while staying in LIBRARY
      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.libraryTrackRow(composeRule, "The Prodigy - Breathe"),
      )
      DroidAmpSelectors.libraryPrimaryQueueButton(composeRule).performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithText("DROIDAMP LIBRARY ▼").assertIsDisplayed()
      composeRule.onNodeWithText("▶ PLAY").assertIsDisplayed()
      assertEquals(3, driver.engine.playbackState.value.queue.size)

      // Inline [▶] replace-plays and switches to PLAYLIST
      DroidAmpSelectors.libraryTrackPlayButton(
        composeRule,
        "The Chemical Brothers - Block Rockin' Beats",
      ).performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithText("DROIDAMP PLAYLIST ▼").assertIsDisplayed()
      assertEquals(listOf("trk-chem"), driver.engine.playbackState.value.queue.map { it.id })
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      // Return to LIBRARY and double-tap a track -> replace-plays and switches to PLAYLIST
      DroidAmpSelectors.playlistLibButton(composeRule).performClick()
      composeRule.waitForIdle()
      DroidAmpSelectors.doubleTapNode(
        DroidAmpSelectors.libraryTrackRow(composeRule, "The Prodigy - Breathe"),
      )
      composeRule.waitForIdle()
      composeRule.onNodeWithText("DROIDAMP PLAYLIST ▼").assertIsDisplayed()
      assertEquals(listOf("trk-prodigy"), driver.engine.playbackState.value.queue.map { it.id })
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)
    }
  }

  @Test // T1-F18-5
  fun f18_tier1_playlistsTabAndSheetModeMemoryAndEjectJumpToLibrary() {
    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = true).use { driver ->
      driver.ensureSheetExpanded(SheetMode.LIBRARY)
      DroidAmpSelectors.libraryPlaylistsTab(composeRule).performClick()
      composeRule.waitForIdle()

      DroidAmpSelectors.libraryPlaylistRow(composeRule, "m3u8", "late_night_drive").assertIsDisplayed()
      DroidAmpSelectors.libraryPlaylistRow(composeRule, "pls", "hifi_rack_check").assertIsDisplayed()

      // Play saved playlist -> switches to PLAYLIST
      DroidAmpSelectors.libraryPlaylistPlayButton(composeRule, "m3u8", "late_night_drive").performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithText("DROIDAMP PLAYLIST ▼").assertIsDisplayed()
      assertEquals(5, driver.engine.playbackState.value.queue.size)

      // Collapse & expand remembers PLAYLIST
      driver.collapseSheetByTitleTap()
      composeRule.onNodeWithText("DROIDAMP PLAYLIST ▲").assertIsDisplayed()
      assertTrue(
        composeRule
          .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
          .fetchSemanticsNodes()
          .isEmpty(),
      )
      assertFalse(driver.viewModel.uiState.value.isSheetExpanded)
      driver.expandSheetByTitleTap()
      composeRule.onNodeWithText("DROIDAMP PLAYLIST ▼").assertIsDisplayed()

      // Switch to LIBRARY, collapse & expand remembers LIBRARY
      DroidAmpSelectors.playlistLibButton(composeRule).performClick()
      composeRule.waitForIdle()
      driver.collapseSheetByTitleTap()
      composeRule.onNodeWithText("DROIDAMP LIBRARY ▲").assertIsDisplayed()
      assertTrue(
        composeRule
          .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
          .fetchSemanticsNodes()
          .isEmpty(),
      )
      assertFalse(driver.viewModel.uiState.value.isSheetExpanded)
      driver.expandSheetByTitleTap()
      composeRule.onNodeWithText("DROIDAMP LIBRARY ▼").assertIsDisplayed()

      // Switch back to PLAYLIST, collapse, then Deck Eject (⏏) unconditionally jumps to LIBRARY
      DroidAmpSelectors.libraryPlButton(composeRule).performClick()
      composeRule.waitForIdle()
      driver.collapseSheetByTitleTap()
      assertTrue(
        composeRule
          .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
          .fetchSemanticsNodes()
          .isEmpty(),
      )
      assertFalse(driver.viewModel.uiState.value.isSheetExpanded)
      driver.clickEjectOnDeck()
      composeRule.onNodeWithText("DROIDAMP LIBRARY ▼").assertIsDisplayed()
    }
  }

  // =========================================================================
  // Feature 19: Live Playlist Reorder, Jump-to-Track & [LIST] -> Save (5 Tier 1 Tests)
  // =========================================================================

  @Test // T1-F19-1
  fun f19_tier1_rowSelectionAndInlineReorderMovesTrackAndPreservesPlayingIdentity() {
    val queue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS.take(3)
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = queue,
      autoPlay = true,
    ).use { driver ->
      driver.ensureSheetExpanded(SheetMode.PLAYLIST)
      assertEquals("trk-dp-1", driver.engine.playbackState.value.currentTrack?.id)
      assertEquals(0, driver.engine.playbackState.value.currentIndex)

      // Select row 1 (Aerodynamic) -> ▲ / ▼ appear
      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - Aerodynamic"),
      )
      DroidAmpSelectors.playlistMoveUpButton(composeRule, "Daft Punk - Aerodynamic").assertIsDisplayed()
      DroidAmpSelectors.playlistMoveDownButton(composeRule, "Daft Punk - Aerodynamic").assertIsDisplayed()

      // Move up -> queue becomes [Aerodynamic, One More Time, Digital Love], currentIndex updates to 1
      DroidAmpSelectors.playlistMoveUpButton(composeRule, "Daft Punk - Aerodynamic").performClick()
      composeRule.waitForIdle()
      assertEquals(
        listOf("trk-dp-2", "trk-dp-1", "trk-dp-3"),
        driver.engine.playbackState.value.queue.map { it.id },
      )
      assertEquals("trk-dp-1", driver.engine.playbackState.value.currentTrack?.id)
      assertEquals(1, driver.engine.playbackState.value.currentIndex)

      // Arbitrary reorder via movePlaylistTrack preserves currentTrack identity
      driver.viewModel.movePlaylistTrack(0, 2)
      composeRule.waitForIdle()
      assertEquals(
        listOf("trk-dp-1", "trk-dp-3", "trk-dp-2"),
        driver.engine.playbackState.value.queue.map { it.id },
      )
      assertEquals("trk-dp-1", driver.engine.playbackState.value.currentTrack?.id)
    }
  }

  @Test // T1-F19-2
  fun f19_tier1_jumpFilterMatchesTitleAndFileNameAndRestoresQueueOnClose() {
    val queue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = queue,
    ).use { driver ->
      driver.ensureSheetExpanded(SheetMode.PLAYLIST)

      // Title match
      driver.setJumpFilterQuery("aerodynamic")
      composeRule.onNodeWithText("JUMP:").assertIsDisplayed()
      DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - Aerodynamic").assertIsDisplayed()
      composeRule.onNodeWithText("2.").assertIsDisplayed()
      composeRule.onAllNodesWithText("Daft Punk - One More Time").fetchSemanticsNodes().let {
        assertTrue(it.isEmpty())
      }

      // Double-tap filtered row plays its original queue index (1)
      DroidAmpSelectors.doubleTapNode(
        DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - Aerodynamic"),
      )
      composeRule.waitForIdle()
      assertEquals(1, driver.engine.playbackState.value.currentIndex)
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      // Filename match (distinct from title)
      driver.setJumpFilterQuery("03_digital")
      DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - Digital Love").assertIsDisplayed()
      composeRule.onNodeWithText("3.").assertIsDisplayed()

      // Close filter via [X] -> restores full queue
      DroidAmpSelectors.jumpFilterCloseButton(composeRule).performClick()
      composeRule.waitForIdle()
      composeRule.onAllNodesWithText("JUMP:").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
      DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - One More Time").assertIsDisplayed()
      DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - Harder, Better, Faster, Stronger").assertIsDisplayed()
    }
  }

  @Test // T1-F19-3
  fun f19_tier1_remMenuRemoveSelectedCropSelectedAndClearList() {
    val queue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = queue,
    ).use { driver ->
      driver.ensureSheetExpanded(SheetMode.PLAYLIST)

      // REM SELECTED
      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - Aerodynamic"),
      )
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistRemButton(composeRule),
        "REMOVE",
        "REM SELECTED",
      )
      assertEquals(
        listOf("trk-dp-1", "trk-dp-3", "trk-dp-4"),
        driver.engine.playbackState.value.queue.map { it.id },
      )

      // CROP SELECTED
      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - One More Time"),
      )
      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.playlistRow(composeRule, "Daft Punk - Digital Love"),
      )
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistRemButton(composeRule),
        "REMOVE",
        "CROP SELECTED",
      )
      assertEquals(
        listOf("trk-dp-1", "trk-dp-3"),
        driver.engine.playbackState.value.queue.map { it.id },
      )

      // CLEAR LIST
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistRemButton(composeRule),
        "REMOVE",
        "CLEAR LIST",
      )
      assertTrue(driver.engine.playbackState.value.queue.isEmpty())
      composeRule.onNodeWithText("PLAYLIST EMPTY — TAP [LIB] TO ADD TRACKS").assertIsDisplayed()
    }
  }

  @Test // T1-F19-4
  fun f19_tier1_miscMenuSortByTitleSortByFileReverseAndRandomize() {
    val queue = sortSampleTracks()
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = queue,
      autoPlay = true,
    ).use { driver ->
      driver.ensureSheetExpanded(SheetMode.PLAYLIST)
      val playingTrackId = driver.engine.playbackState.value.currentTrack?.id
      assertEquals("sort-1", playingTrackId)

      // SORT BY TITLE (displayLabel ascending: Artist A - Zulu Song, Artist B - Mid Song, Artist C - Alpha Song, Artist D - Beta Song)
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistMiscButton(composeRule),
        "MISC OPTS",
        "SORT BY TITLE",
      )
      val afterTitleSort = driver.engine.playbackState.value.queue
      assertEquals(
        afterTitleSort.sortedBy { it.displayLabel.lowercase() }.map { it.id },
        afterTitleSort.map { it.id },
      )
      assertEquals(playingTrackId, driver.engine.playbackState.value.currentTrack?.id)

      // SORT BY FILE
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistMiscButton(composeRule),
        "MISC OPTS",
        "SORT BY FILE",
      )
      val afterFileSort = driver.engine.playbackState.value.queue
      assertEquals(listOf("sort-4", "sort-2", "sort-3", "sort-1"), afterFileSort.map { it.id })
      assertEquals(playingTrackId, driver.engine.playbackState.value.currentTrack?.id)

      // REVERSE LIST
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistMiscButton(composeRule),
        "MISC OPTS",
        "REVERSE LIST",
      )
      assertEquals(
        listOf("sort-1", "sort-3", "sort-2", "sort-4"),
        driver.engine.playbackState.value.queue.map { it.id },
      )
      assertEquals(playingTrackId, driver.engine.playbackState.value.currentTrack?.id)

      // RANDOMIZE
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistMiscButton(composeRule),
        "MISC OPTS",
        "RANDOMIZE",
      )
      val randomizedIds = driver.engine.playbackState.value.queue.map { it.id }.toSet()
      assertEquals(setOf("sort-1", "sort-2", "sort-3", "sort-4"), randomizedIds)
    }
  }

  @Test // T1-F19-5
  fun f19_tier1_listMenuSaveM3u8LoadPlaylistAndNewList() {
    val queue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS.take(3)
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = queue,
      autoPlay = true,
    ).use { driver ->
      driver.ensureSheetExpanded(SheetMode.PLAYLIST)

      // SAVE .M3U8 dismisses popup, preserves queue, shows non-empty status banner
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistListButton(composeRule),
        "LIST OPTS",
        "SAVE .M3U8",
      )
      assertEquals(3, driver.engine.playbackState.value.queue.size)
      val banner = driver.viewModel.uiState.value.statusBanner
      assertNotNull("Expected non-null statusBanner after SAVE .M3U8", banner)
      assertTrue("Expected non-blank statusBanner after SAVE .M3U8", banner!!.isNotBlank())

      // LOAD PLAYLIST jumps directly to LIBRARY -> PLAYLISTS
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistListButton(composeRule),
        "LIST OPTS",
        "LOAD PLAYLIST",
      )
      composeRule.onNodeWithText("DROIDAMP LIBRARY ▼").assertIsDisplayed()
      assertEquals(LibraryTab.PLAYLISTS, driver.viewModel.uiState.value.libraryTab)
      DroidAmpSelectors.libraryPlaylistRow(composeRule, "m3u8", "late_night_drive").assertIsDisplayed()

      // Switch back to PLAYLIST and invoke NEW LIST
      DroidAmpSelectors.libraryPlButton(composeRule).performClick()
      composeRule.waitForIdle()
      driver.clickPlaylistMenuAction(
        DroidAmpSelectors.playlistListButton(composeRule),
        "LIST OPTS",
        "NEW LIST",
      )
      assertTrue(driver.engine.playbackState.value.queue.isEmpty())
      assertEquals(PlaybackStatus.STOPPED, driver.engine.playbackState.value.status)
    }
  }

  // =========================================================================
  // Feature 20: EQ AUTO Matching, Presets UI, PREAMP Gap & Swipe Buffer (4 tests here + 2 in MainActivity)
  // =========================================================================

  @Test // T1-F20-1
  fun f20_tier1_autoEqToggleAndAutoEqMatcherResolveFolderAndTrackRules() {
    val rulesBytes =
      SyntheticWinampFixtures.q2Bytes(
        listOf(
          SyntheticWinampFixtures.Q2RuleSpec("Music/Synthwave", ClassicWinampPresets.TECHNO),
          SyntheticWinampFixtures.Q2RuleSpec("01_one_more_time.flac", ClassicWinampPresets.DANCE),
        ),
      )
    val parsedRules = q2Codec.parseAutoPresetsQ2(rulesBytes)
    val synthTrack = FakeMusicSourceRepository.SYNTHWAVE_TRACKS.first()
    val daftTrack = FakeMusicSourceRepository.DAFT_PUNK_TRACKS.first()

    val resolvedSynth = autoEqResolver.resolveForTrack(synthTrack, parsedRules, ClassicWinampPresets.ALL)
    assertNotNull(resolvedSynth)
    assertEquals("Music/Synthwave", resolvedSynth!!.name)
    assertEquals(ClassicWinampPresets.TECHNO.preampDb, resolvedSynth.preampDb, 0.4f)
    ClassicWinampPresets.TECHNO.bandsDb.zip(resolvedSynth.bandsDb).forEachIndexed { idx, (expected, actual) ->
      assertTrue(
        "TECHNO band $idx expected ~$expected dB, got $actual",
        abs(actual - expected) <= 0.4f,
      )
    }

    val resolvedDaft = autoEqResolver.resolveForTrack(daftTrack, parsedRules, ClassicWinampPresets.ALL)
    assertNotNull(resolvedDaft)
    assertEquals("01_one_more_time.flac", resolvedDaft!!.name)
    assertEquals(ClassicWinampPresets.DANCE.preampDb, resolvedDaft.preampDb, 0.4f)
    ClassicWinampPresets.DANCE.bandsDb.zip(resolvedDaft.bandsDb).forEachIndexed { idx, (expected, actual) ->
      assertTrue(
        "DANCE band $idx expected ~$expected dB, got $actual",
        abs(actual - expected) <= 0.4f,
      )
    }

    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = true).use { driver ->
      assertFalse(driver.engine.playbackState.value.equalizer.autoEnabled)
      driver.toggleEqAuto()
      assertTrue(driver.engine.playbackState.value.equalizer.autoEnabled)
      driver.toggleEqAuto()
      assertFalse(driver.engine.playbackState.value.equalizer.autoEnabled)
    }
  }

  @Test // T1-F20-2
  fun f20_tier1_presetsPopupSelectsClassicPresetAndUpdatesBands() {
    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = true).use { driver ->
      driver.selectEqPreset(ClassicWinampPresets.CLASSICAL.name)
      val eq = driver.engine.playbackState.value.equalizer
      assertEquals(ClassicWinampPresets.CLASSICAL.name, eq.activePresetName)
      assertEquals(ClassicWinampPresets.CLASSICAL.preampDb, eq.preampDb, 0.01f)
      assertEquals(ClassicWinampPresets.CLASSICAL.bandsDb, eq.bandsDb)
    }
  }

  @Test // T1-F20-3
  fun f20_tier1_preampGapIsolationAndHorizontalEqCurveDrawPreservesPreamp() {
    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = true).use { driver ->
      DeviceManifestVerifier.assertPreampGapIsolation(composeRule)

      driver.setPreampDb(4.0f)
      val preampBeforeCurve = driver.engine.playbackState.value.equalizer.preampDb
      assertTrue(abs(preampBeforeCurve - 4.0f) <= 1.0f)

      DroidAmpSelectors.drawEqCurve(composeRule, fromBand = 0, toBand = 9, startDb = -6f, endDb = 6f)
      composeRule.waitForIdle()

      val eqAfterCurve = driver.engine.playbackState.value.equalizer
      assertEquals(preampBeforeCurve, eqAfterCurve.preampDb, 0.01f)
      assertTrue("Expected low band < 0 dB, got ${eqAfterCurve.bandsDb[0]}", eqAfterCurve.bandsDb[0] < 0f)
      assertTrue("Expected high band > 0 dB, got ${eqAfterCurve.bandsDb[9]}", eqAfterCurve.bandsDb[9] > 0f)
    }
  }

  @Test // T1-F20-4
  fun f20_tier1_peekingSheetSwipeBufferAndSwipeUpDownGestures() {
    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = true).use { driver ->
      DeviceManifestVerifier.assertPeekingSheetSwipeBuffer(composeRule)

      driver.expandSheetBySwipeUp()
      DroidAmpSelectors.expandedSheetTitleBar(composeRule).assertIsDisplayed()

      driver.collapseSheetBySwipeDown()
      DroidAmpSelectors.peekingSheetTitleBar(composeRule).assertIsDisplayed()
      assertTrue(
        composeRule
          .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
          .fetchSemanticsNodes()
          .isEmpty(),
      )
      assertFalse(driver.viewModel.uiState.value.isSheetExpanded)
      DeviceManifestVerifier.assertPeekingSheetSwipeBuffer(composeRule)
    }
  }

  companion object {
    private val PREFS_COUNTER = AtomicInteger(0)
  }
}

@RunWith(AndroidJUnit4::class)
class Tier1StorageMainActivityTest {

  @get:Rule
  val activityRule = createAndroidComposeRule<MainActivity>()

  @Test // T1-F18-1
  fun f18_tier1_mainActivityColdLaunchShowsUnlinkedOnboardingAndEjectToLibrary() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    activityRule.onNodeWithText("DROIDAMP PLAYLIST ▲").assertIsDisplayed()
    DroidAmpSelectors.emptyFolderTitlePeeking(activityRule).assertIsDisplayed()
    activityRule.onNodeWithText("TAP TO LINK YOUR MUSIC FOLDER (SAF)").assertIsDisplayed()

    driver.expandSheetByTitleTap()
    activityRule.onNodeWithText("DROIDAMP PLAYLIST ▼").assertIsDisplayed()
    DroidAmpSelectors.selectMusicFolderButton(activityRule)
      .assertIsDisplayed()
      .assertHasClickAction()
      .assertIsEnabled()

    // Collapse and press Deck Eject (⏏) -> LIBRARY with disabled ▶ PLAY / + QUEUE
    driver.collapseSheetByTitleTap()
    assertTrue(
      activityRule
        .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
        .fetchSemanticsNodes()
        .isEmpty(),
    )
    driver.clickEjectOnDeck()
    activityRule.onNodeWithText("DROIDAMP LIBRARY ▼").assertIsDisplayed()
    DroidAmpSelectors.libraryFoldersTab(activityRule).assertIsDisplayed()
    DroidAmpSelectors.libraryPlaylistsTab(activityRule).assertIsDisplayed()
    DroidAmpSelectors.libraryPrimaryPlayButton(activityRule).assertIsNotEnabled()
    DroidAmpSelectors.libraryPrimaryQueueButton(activityRule).assertIsNotEnabled()
  }

  @Test // T1-F20-5
  fun f20_tier1_mainActivityNavigationBarAndBottomLipClearancePeekingAndExpanded() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    DeviceManifestVerifier.assertPreampGapIsolation(activityRule)
    DeviceManifestVerifier.assertPeekingSheetSwipeBuffer(activityRule)
    DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(activityRule)

    driver.ensureSheetExpanded(SheetMode.PLAYLIST)
    DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(activityRule)
  }

  @Test // T1-F20-6
  fun f20_tier1_mainActivityZeroInAppAlbumArtAndOfflineManifest() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    DeviceManifestVerifier.assertOfflineManifestPermissions(activityRule.activity)
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)

    driver.ensureSheetExpanded(SheetMode.PLAYLIST)
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)

    driver.ensureSheetExpanded(SheetMode.LIBRARY)
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)
  }
}
