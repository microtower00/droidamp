package com.droidamp.player.ui.rack

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import com.droidamp.player.DroidAmpAppContainer
import com.droidamp.player.audio.dsp.ReplayGainMetadata
import com.droidamp.player.audio.service.AudioEngineGateway
import com.droidamp.player.audio.service.CrossfadeController
import com.droidamp.player.audio.service.DroidAmpDspChain
import com.droidamp.player.audio.service.DspPreferencesStore
import com.droidamp.player.audio.service.Media3AudioEngineController
import com.droidamp.player.audio.service.ServiceRuntimeSnapshot
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.domain.model.RepeatMode
import com.droidamp.player.domain.model.VisualizerFrame
import com.droidamp.player.interop.AutoEqMatcher
import com.droidamp.player.interop.WinampPlaylistCodec
import com.droidamp.player.storage.AudioMetadataExtractor
import com.droidamp.player.storage.AudioMetadataProbe
import com.droidamp.player.storage.MediaStoreAudioRecord
import com.droidamp.player.storage.MediaStoreGateway
import com.droidamp.player.storage.MediaStoreMusicSourceRepository
import com.droidamp.player.storage.MediaStorePlaylistFileRecord
import com.droidamp.player.storage.MediaStorePlaylistRecord
import com.droidamp.player.storage.ProbedAudioMetadata
import com.droidamp.player.storage.SafDocumentNode
import com.droidamp.player.storage.SafMusicSourceRepository
import com.droidamp.player.storage.SafTreeGateway
import com.droidamp.player.storage.StoragePreferencesStore
import com.droidamp.player.storage.SwitchingMusicSourceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DroidAmpAppContainerWiringTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private class InMemoryAudioGateway(
    val dspChain: DroidAmpDspChain = DroidAmpDspChain(),
  ) : AudioEngineGateway {
    var gatewayListener: AudioEngineGateway.Listener? = null
    var lastAppliedEq: EqualizerState = EqualizerState()
    var lastAppliedVolume: Int = 204
    var queue: List<AudioTrack> = emptyList()
    var currentIndex: Int = -1

    override fun setListener(listener: AudioEngineGateway.Listener?) {
      this.gatewayListener = listener
    }

    override fun setQueue(tracks: List<AudioTrack>, startIndex: Int, playWhenReady: Boolean) {
      queue = tracks
      currentIndex = if (tracks.isEmpty()) -1 else startIndex.coerceIn(0, tracks.lastIndex)
    }

    override fun syncQueueWithoutInterrupting(tracks: List<AudioTrack>, currentIndex: Int) {
      queue = tracks
      this.currentIndex = currentIndex
    }

    override fun selectTrackAt(index: Int, playWhenReady: Boolean) {
      currentIndex = index
    }

    override fun play() = Unit
    override fun pause() = Unit
    override fun stop() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setRepeatMode(mode: RepeatMode) = Unit
    override fun setShuffleEnabled(enabled: Boolean) = Unit
    override fun applyDspSettings(settings: DspSettings) = dspChain.applyDspSettings(settings)

    override fun applyEqualizerState(state: EqualizerState) {
      lastAppliedEq = state
      dspChain.applyEqualizerState(state)
    }

    override fun applyVolume(volume0To255: Int) {
      lastAppliedVolume = volume0To255
      dspChain.setVolume(volume0To255)
    }

    override fun applyBalance(balanceMinus127To127: Int) = dspChain.setBalance(balanceMinus127To127)
    override fun applyReplayGainMetadata(metadata: ReplayGainMetadata) = dspChain.setReplayGainMetadata(metadata)
    override fun readVisualizerFrame(isPlaying: Boolean): VisualizerFrame = dspChain.readVisualizerFrame(isPlaying)
    override fun resetVisualizer() = dspChain.resetVisualizer()
    override fun currentPositionMs(): Long = 0L
    override fun currentDurationMs(): Long = queue.getOrNull(currentIndex)?.durationMs ?: 0L
    override fun onCrossfadeProgressTick(
      isPlaying: Boolean,
      positionMs: Long,
      durationMs: Long,
      currentTrack: AudioTrack?,
      nextCandidate: CrossfadeController.NextTrackCandidate?,
    ) = Unit

    override fun cancelCrossfade() = Unit
  }

  private class InMemorySafTreeGateway : SafTreeGateway {
    val accessibleTrees = linkedSetOf<String>()
    val persistedReadUris = linkedSetOf<String>()
    val rootNodes = mutableMapOf<String, SafDocumentNode>()
    val childrenByDirUri = mutableMapOf<String, MutableList<SafDocumentNode>>()
    val textFiles = mutableMapOf<String, String>()

    fun addTree(treeUri: String, rootDocUri: String, displayName: String) {
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

    fun addChild(parentDirUri: String, node: SafDocumentNode, textContent: String? = null) {
      childrenByDirUri.getOrPut(parentDirUri) { mutableListOf() }.add(node)
      if (node.isDirectory) {
        childrenByDirUri.getOrPut(node.uriString) { mutableListOf() }
      } else if (textContent != null) {
        textFiles[node.uriString] = textContent
      }
    }

    override fun takePersistablePermission(treeUriString: String): Boolean {
      persistedReadUris.add(treeUriString)
      return treeUriString in accessibleTrees
    }

    override fun releasePersistablePermission(treeUriString: String) {
      persistedReadUris.remove(treeUriString)
    }

    override fun getPersistedReadTreeUris(): Set<String> = persistedReadUris.toSet()
    override fun isTreeAccessible(treeUriString: String): Boolean = treeUriString in accessibleTrees
    override fun getTreeRootNode(treeUriString: String): SafDocumentNode? = rootNodes[treeUriString]

    override fun listChildren(treeUriString: String, directoryUriString: String): List<SafDocumentNode>? =
      if (treeUriString in accessibleTrees) childrenByDirUri[directoryUriString]?.toList() else null

    override fun readTextFile(documentUriString: String): String? = textFiles[documentUriString]

    override fun writeTextFile(
      treeUriString: String,
      directoryUriString: String,
      fileName: String,
      mimeType: String,
      content: String,
    ): SafDocumentNode? {
      if (treeUriString !in accessibleTrees) return null
      val list = childrenByDirUri.getOrPut(directoryUriString) { mutableListOf() }
      val node =
        list.firstOrNull { !it.isDirectory && it.name.equals(fileName, ignoreCase = true) }
          ?: SafDocumentNode(
            uriString = "$directoryUriString%2F$fileName",
            name = fileName,
            isDirectory = false,
            mimeType = mimeType,
          ).also { list.add(it) }
      textFiles[node.uriString] = content
      return node
    }
  }

  private object EmptyMediaStoreGateway : MediaStoreGateway {
    override fun hasAudioReadPermission(): Boolean = false
    override fun queryAudioTracks(): List<MediaStoreAudioRecord> = emptyList()
    override fun queryPlaylists(): List<MediaStorePlaylistRecord> = emptyList()
    override fun queryPlaylistFiles(): List<MediaStorePlaylistFileRecord> = emptyList()
    override fun readTextFile(uriOrPath: String): String? = null
    override fun writeM3u8PlaylistFile(fileName: String, content: String): MediaStorePlaylistFileRecord? = null
  }

  private class InMemoryPreferencesDataStore(
    initial: Preferences = emptyPreferences(),
  ) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
      val updated = transform(state.value)
      state.value = updated
      return updated
    }
  }

  private fun createDspStore(name: String, scope: CoroutineScope): DspPreferencesStore =
    DspPreferencesStore(
      PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { File(tempFolder.root, name) },
      ),
    )

  private fun createStorageStore(name: String, scope: CoroutineScope): StoragePreferencesStore =
    StoragePreferencesStore(InMemoryPreferencesDataStore())

  private fun createSwitchingRepo(
    safGateway: SafTreeGateway,
    storageStore: StoragePreferencesStore,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = testDispatcher,
  ): SwitchingMusicSourceRepository {
    val probe =
      object : AudioMetadataProbe {
        override fun probe(uriString: String): ProbedAudioMetadata =
          ProbedAudioMetadata(
            title = uriString.substringAfterLast('/').substringBeforeLast('.'),
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 180_000L,
            bitrateKbps = 320,
            sampleRateHz = 44_100,
            channelMode = ChannelMode.STEREO,
          )
      }
    val extractor = AudioMetadataExtractor(probe = probe, ioDispatcher = ioDispatcher)
    val codec = WinampPlaylistCodec()
    val safRepo =
      SafMusicSourceRepository(
        gateway = safGateway,
        preferencesStore = storageStore,
        metadataExtractor = extractor,
        playlistCodec = codec,
        scope = scope,
        ioDispatcher = ioDispatcher,
      )
    val mediaRepo =
      MediaStoreMusicSourceRepository(
        gateway = EmptyMediaStoreGateway,
        metadataExtractor = extractor,
        playlistCodec = codec,
        ioDispatcher = ioDispatcher,
      )
    return SwitchingMusicSourceRepository(safRepo, mediaRepo, storageStore, scope)
  }

  @Test
  fun coldLaunch_media3ControllerResetsVolumeTo204OnlyWhenStopped_andPreservesWhenPlaying() = runTest(testDispatcher) {
    val dspFile = File(tempFolder.root, "dsp_cold.preferences_pb")
    val preScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    createDspStore("dsp_cold.preferences_pb", preScope).saveVolume(45)
    preScope.coroutineContext.job.cancelAndJoin()

    val dspStore =
      DspPreferencesStore(
        PreferenceDataStoreFactory.create(
          corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
          scope = backgroundScope,
          produceFile = { dspFile },
        ),
      )
    val storageStore = createStorageStore("storage_cold.preferences_pb", backgroundScope)
    val repo = createSwitchingRepo(InMemorySafTreeGateway(), storageStore, backgroundScope)
    val gateway = InMemoryAudioGateway()
    val controller = Media3AudioEngineController(gateway, dspStore, backgroundScope)

    val factory = DroidAmpAppContainer.createViewModelFactory(controller, repo)
    val vm1 = factory.create(DroidAmpViewModel::class.java)
    controller.awaitInitialHydration()
    advanceUntilIdle()

    // Fresh cold launch while STOPPED -> volume forced to 204 even though DataStore had 45
    assertEquals(204, vm1.playbackState.value.volume)
    assertEquals(204, gateway.lastAppliedVolume)

    // Simulate active playback at volume 55 -> recreating ViewModel (Activity reopen) must preserve 55!
    val sampleTrack =
      AudioTrack(
        id = "trk-1",
        uriString = "content://tree/music/01.mp3",
        title = "01",
        artist = "Artist",
        album = "Album",
        fileName = "01.mp3",
        folderPath = "Music",
        durationMs = 180_000L,
      )
    controller.replaceQueue(listOf(sampleTrack), startIndex = 0, autoPlay = true)
    vm1.setVolume(55)
    assertEquals(PlaybackStatus.PLAYING, controller.playbackState.value.status)

    val vm2 = factory.create(DroidAmpViewModel::class.java)
    advanceUntilIdle()
    assertEquals(55, vm2.playbackState.value.volume)
    assertNotEquals(204, vm2.playbackState.value.volume)

    controller.close()
  }

  @Test
  fun coldLaunch_serviceSnapshotRecoveredPreservesPlayingVolumeOverDefault204() = runTest(testDispatcher) {
    val dspStore = createDspStore("dsp_snap.preferences_pb", backgroundScope)
    val storageStore = createStorageStore("storage_snap.preferences_pb", backgroundScope)
    val repo = createSwitchingRepo(InMemorySafTreeGateway(), storageStore, backgroundScope)
    val gateway = InMemoryAudioGateway()
    val controller = Media3AudioEngineController(gateway, dspStore, backgroundScope)

    val track =
      AudioTrack(
        id = "live-1",
        uriString = "content://tree/music/live.mp3",
        title = "Live",
        artist = "Artist",
        album = "Album",
        fileName = "live.mp3",
        folderPath = "Music",
        durationMs = 240_000L,
      )
    gateway.gatewayListener?.onServiceSnapshotRecovered(
      ServiceRuntimeSnapshot(
        status = PlaybackStatus.PLAYING,
        queue = listOf(track),
        currentIndex = 0,
        positionMs = 12_500L,
        volume = 72,
        balance = 0,
        shuffle = false,
        repeatMode = RepeatMode.OFF,
        equalizer = EqualizerState(),
        dspSettings = DspSettings(),
      ),
    )

    val vm = DroidAmpAppContainer.createViewModelFactory(controller, repo).create(DroidAmpViewModel::class.java)
    advanceUntilIdle()
    assertEquals(72, vm.playbackState.value.volume)
    assertEquals(PlaybackStatus.PLAYING, vm.playbackState.value.status)

    controller.close()
  }

  @Test
  fun endToEndWiring_safLinkBrowseSaveM3u8AndLoadSavedPlaylist() = runTest(testDispatcher) {
    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val rootDocUri = "$treeUri/document/primary%3AMusic"
    val rockDirUri = "$treeUri/document/primary%3AMusic%2FRock"
    val safGateway =
      InMemorySafTreeGateway().apply {
        addTree(treeUri, rootDocUri, "Music")
        addChild(
          rootDocUri,
          SafDocumentNode(
            uriString = rockDirUri,
            name = "Rock",
            isDirectory = true,
            mimeType = "vnd.android.document/directory",
          ),
        )
        addChild(
          rockDirUri,
          SafDocumentNode(
            uriString = "$treeUri/document/primary%3AMusic%2FRock%2F01_la_woman.mp3",
            name = "01_la_woman.mp3",
            isDirectory = false,
            mimeType = "audio/mpeg",
          ),
        )
        addChild(
          rockDirUri,
          SafDocumentNode(
            uriString = "$treeUri/document/primary%3AMusic%2FRock%2F02_riders.mp3",
            name = "02_riders.mp3",
            isDirectory = false,
            mimeType = "audio/mpeg",
          ),
        )
      }

    val dspStore = createDspStore("dsp_e2e.preferences_pb", backgroundScope)
    val storageStore = createStorageStore("storage_e2e.preferences_pb", backgroundScope)
    val repo = createSwitchingRepo(safGateway, storageStore, backgroundScope)
    val gateway = InMemoryAudioGateway()
    val controller = Media3AudioEngineController(gateway, dspStore, backgroundScope)
    val vm =
      DroidAmpAppContainer.createViewModelFactory(
        audioEngine = controller,
        musicRepository = repo,
        autoEqResolver = AutoEqMatcher(),
        playlistCodec = WinampPlaylistCodec(),
      ).create(DroidAmpViewModel::class.java)
    advanceUntilIdle()

    // Link SAF root and play Rock folder
    vm.onSelectMusicFolder(treeUri)
    advanceUntilIdle()
    assertFalse(vm.linkedRoots.value.isEmpty())
    val rockFolder = vm.uiState.value.folderContents.folders.first()
    vm.playFolderAsPlaylist(rockFolder)
    advanceUntilIdle()
    assertEquals(2, vm.playbackState.value.queue.size)

    // Save current queue as .m3u8 via real SafMusicSourceRepository
    vm.savePlaylistAsM3u8("doors_mix.m3u8")
    advanceUntilIdle()

    assertEquals(1, vm.uiState.value.playlists.size)
    val savedEntry = vm.uiState.value.playlists.first()
    assertEquals("doors_mix", savedEntry.name)
    assertTrue(safGateway.textFiles.values.any { it.startsWith("#EXTM3U") && it.contains("01_la_woman.mp3") })

    // Clear queue, then load back saved .m3u8 playlist into Media3AudioEngineController
    vm.clearPlaylist()
    assertTrue(vm.playbackState.value.queue.isEmpty())

    vm.playSavedPlaylist(savedEntry)
    advanceUntilIdle()
    assertEquals(2, vm.playbackState.value.queue.size)
    assertEquals(PlaybackStatus.PLAYING, vm.playbackState.value.status)

    controller.close()
  }

  @Test
  fun endToEndWiring_autoEqTriggersOnGaplessAndCrossfadeTrackTransitions() = runTest(testDispatcher) {
    val dspStore = createDspStore("dsp_autoeq.preferences_pb", backgroundScope)
    val storageStore = createStorageStore("storage_autoeq.preferences_pb", backgroundScope)
    val repo = createSwitchingRepo(InMemorySafTreeGateway(), storageStore, backgroundScope)
    val gateway = InMemoryAudioGateway()
    val controller = Media3AudioEngineController(gateway, dspStore, backgroundScope)
    val vm =
      DroidAmpAppContainer.createViewModelFactory(
        audioEngine = controller,
        musicRepository = repo,
        autoEqResolver = AutoEqMatcher(),
      ).create(DroidAmpViewModel::class.java)

    val rockTrack =
      AudioTrack(
        id = "trk-rock",
        uriString = "content://tree/Rock/song1.mp3",
        title = "Song 1",
        artist = "Band",
        album = "Album",
        fileName = "song1.mp3",
        folderPath = "Music/Rock",
        durationMs = 180_000L,
      )
    val classicalTrack =
      AudioTrack(
        id = "trk-class",
        uriString = "content://tree/Classical/song2.mp3",
        title = "Song 2",
        artist = "Orch",
        album = "Album",
        fileName = "song2.mp3",
        folderPath = "Music/Classical",
        durationMs = 200_000L,
      )
    val technoTrack =
      AudioTrack(
        id = "trk-tech",
        uriString = "content://tree/Techno/song3.mp3",
        title = "Song 3",
        artist = "DJ",
        album = "Album",
        fileName = "song3.mp3",
        folderPath = "Music/Techno",
        durationMs = 210_000L,
      )

    controller.replaceQueue(listOf(rockTrack, classicalTrack, technoTrack), startIndex = 0, autoPlay = true)
    vm.toggleEqAuto()
    advanceUntilIdle()
    assertEquals(ClassicWinampPresets.ROCK.name, controller.playbackState.value.equalizer.activePresetName)
    assertEquals(ClassicWinampPresets.ROCK.bandsDb, gateway.lastAppliedEq.bandsDb)

    // Gapless transition -> Classical
    gateway.gatewayListener?.onGaplessTrackChanged(1, classicalTrack.id)
    advanceUntilIdle()
    assertEquals(ClassicWinampPresets.CLASSICAL.name, controller.playbackState.value.equalizer.activePresetName)
    assertEquals(ClassicWinampPresets.CLASSICAL.bandsDb, gateway.lastAppliedEq.bandsDb)

    // Crossfade transition -> Techno
    gateway.gatewayListener?.onCrossfadeTrackAdvanced(2, technoTrack)
    advanceUntilIdle()
    assertEquals(ClassicWinampPresets.TECHNO.name, controller.playbackState.value.equalizer.activePresetName)
    assertEquals(ClassicWinampPresets.TECHNO.bandsDb, gateway.lastAppliedEq.bandsDb)

    controller.close()
  }

  @Test
  fun endToEndWiring_asyncSafRootHydrationPopulatesLibraryOnColdStart() = runTest(testDispatcher) {
    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    val rootDocUri = "$treeUri/document/primary%3AMusic"
    val safGateway =
      InMemorySafTreeGateway().apply {
        addTree(treeUri, rootDocUri, "Music")
        addChild(
          rootDocUri,
          SafDocumentNode(
            uriString = "$treeUri/document/primary%3AMusic%2Ftrack.mp3",
            name = "track.mp3",
            isDirectory = false,
            mimeType = "audio/mpeg",
          ),
        )
      }
    val storageFile = File(tempFolder.root, "storage_hydrate.preferences_pb")
    val preScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    StoragePreferencesStore(
      PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = preScope,
        produceFile = { storageFile },
      ),
    ).addSafRoot(treeUri, "Music")
    preScope.coroutineContext.job.cancelAndJoin()

    val storageStore =
      StoragePreferencesStore(
        PreferenceDataStoreFactory.create(
          corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
          scope = backgroundScope,
          produceFile = { storageFile },
        ),
      )
    val dspStore = createDspStore("dsp_hydrate.preferences_pb", backgroundScope)
    val repo = createSwitchingRepo(safGateway, storageStore, backgroundScope)
    val controller = Media3AudioEngineController(InMemoryAudioGateway(), dspStore, backgroundScope)
    val vm = DroidAmpAppContainer.createViewModelFactory(controller, repo).create(DroidAmpViewModel::class.java)

    // After async DataStore hydration completes, linkedRoots observer must populate folderContents automatically
    advanceUntilIdle()
    assertEquals(1, vm.linkedRoots.value.size)
    assertEquals(1, vm.uiState.value.folderContents.tracks.size)
    assertEquals("track.mp3", vm.uiState.value.folderContents.tracks.first().fileName)

    controller.close()
  }

  @Test
  fun appContainer_factoryProvidesSingletonEngineAndPreservesVmStateAcrossRecreate() = runTest(testDispatcher) {
    val dspStore = createDspStore("dsp_singleton.preferences_pb", backgroundScope)
    val storageStore = createStorageStore("storage_singleton.preferences_pb", backgroundScope)
    val repo = createSwitchingRepo(InMemorySafTreeGateway(), storageStore, backgroundScope)
    val controller = Media3AudioEngineController(InMemoryAudioGateway(), dspStore, backgroundScope)
    val factory = DroidAmpAppContainer.createViewModelFactory(controller, repo)

    val vmA = factory.create(DroidAmpViewModel::class.java)
    vmA.setBalance(-42)
    val vmB = factory.create(DroidAmpViewModel::class.java)
    assertEquals(-42, vmB.playbackState.value.balance)

    controller.close()
    advanceUntilIdle()
  }
}
