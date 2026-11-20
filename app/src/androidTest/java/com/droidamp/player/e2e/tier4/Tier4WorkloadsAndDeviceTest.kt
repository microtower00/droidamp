package com.droidamp.player.e2e.tier4

import android.Manifest
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidamp.player.MainActivity
import com.droidamp.player.domain.contract.AudioEngineController
import com.droidamp.player.domain.contract.AutoEqPresetResolver
import com.droidamp.player.domain.contract.EqfCodec
import com.droidamp.player.domain.contract.MusicSourceRepository
import com.droidamp.player.domain.contract.PlaylistFileCodec
import com.droidamp.player.domain.contract.Q2Codec
import com.droidamp.player.domain.contract.WinampIniCodec
import com.droidamp.player.domain.contract.WinampIniSnapshot
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.domain.model.RepeatMode
import com.droidamp.player.domain.model.ReplayGainMode
import com.droidamp.player.domain.model.SheetMode
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.domain.model.TimeDisplayMode
import com.droidamp.player.domain.model.VisualizerFrame
import com.droidamp.player.domain.model.VisualizerMode
import com.droidamp.player.domain.model.WinampEqSpec
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
import java.io.Closeable
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 4 Real-World Application Workloads (Scenarios 1B, 2, 3, 4, 5, 6, 7, 8).
 * Exercises full cross-layer end-to-end pipelines with real PCM DSP, Media3 ExoPlayer,
 * DataStore persistence, SAF/MediaStore repositories, Winamp codecs, and Compose Rack UI.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class Tier4WorkloadsAndDeviceTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val targetContext: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val eqfCodec: EqfCodec by lazy {
    loadContract("com.droidamp.player.interop.BinaryEqfCodec", EqfCodec::class.java)
  }

  private val q2Codec: Q2Codec by lazy {
    loadContract("com.droidamp.player.interop.WinampQ2Codec", Q2Codec::class.java)
  }

  private val autoEqResolver: AutoEqPresetResolver by lazy {
    loadContract("com.droidamp.player.interop.AutoEqMatcher", AutoEqPresetResolver::class.java)
  }

  private val iniCodec: WinampIniCodec by lazy {
    loadContract("com.droidamp.player.interop.WinampIniParser", WinampIniCodec::class.java)
  }

  private val playlistCodec: PlaylistFileCodec by lazy {
    loadContract("com.droidamp.player.interop.WinampPlaylistCodec", PlaylistFileCodec::class.java)
  }

  private fun <T : Any> loadContract(implClassName: String, contractClass: Class<T>): T {
    val clazz = Class.forName(implClassName)
    val instance =
      runCatching { clazz.getField("INSTANCE").get(null) }
        .getOrElse { clazz.getDeclaredConstructor().newInstance() }
    return contractClass.cast(instance)
      ?: error("$implClassName does not implement ${contractClass.name}")
  }

  private fun nextPrefsFile(dir: File, prefix: String): File =
    File(dir, "${prefix}_${PREFS_COUNTER.incrementAndGet()}.preferences_pb")

  // =========================================================================
  // Reflective DSP & Storage Helpers
  // =========================================================================

  private class ReflectiveDspChainHandle {
    val rgProcessor: AudioProcessor
    val eqProcessor: AudioProcessor
    val volBalProcessor: AudioProcessor
    val visProcessor: AudioProcessor
    val chain: Any

    init {
      val rgCls = Class.forName("com.droidamp.player.audio.dsp.ReplayGainProcessor")
      val eqCls = Class.forName("com.droidamp.player.audio.dsp.WinampTenBandEqProcessor")
      val vbCls = Class.forName("com.droidamp.player.audio.dsp.VolumeBalanceProcessor")
      val rbCls = Class.forName("com.droidamp.player.audio.dsp.LockFreeAudioRingBuffer")
      val fftCls = Class.forName("com.droidamp.player.audio.dsp.Radix2Fft")
      val visCls = Class.forName("com.droidamp.player.audio.dsp.VisualizerTapProcessor")
      val chainCls = Class.forName("com.droidamp.player.audio.service.DroidAmpDspChain")

      rgProcessor = rgCls.getDeclaredConstructor().newInstance() as AudioProcessor
      eqProcessor = eqCls.getDeclaredConstructor().newInstance() as AudioProcessor
      volBalProcessor = vbCls.getDeclaredConstructor().newInstance() as AudioProcessor
      val ringBuffer = rbCls.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(8192)
      val fft = fftCls.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(1024)
      visProcessor = visCls.getDeclaredConstructor(rbCls, fftCls).newInstance(ringBuffer, fft) as AudioProcessor
      chain = chainCls.getDeclaredConstructor(rgCls, eqCls, vbCls, visCls).newInstance(
        rgProcessor,
        eqProcessor,
        volBalProcessor,
        visProcessor,
      )
    }

    fun applyDspSettings(settings: DspSettings) {
      chain.javaClass.getMethod("applyDspSettings", DspSettings::class.java).invoke(chain, settings)
    }

    fun applyEqualizerState(state: EqualizerState) {
      chain.javaClass.getMethod("applyEqualizerState", EqualizerState::class.java).invoke(chain, state)
    }

    fun setVolume(volume0To255: Int) {
      chain.javaClass.getMethod("setVolume", Int::class.javaPrimitiveType).invoke(chain, volume0To255)
    }

    fun setBalance(balanceMinus127To127: Int) {
      chain.javaClass.getMethod("setBalance", Int::class.javaPrimitiveType).invoke(chain, balanceMinus127To127)
    }

    fun setReplayGainMetadata(rgMeta: Any) {
      val rgMetaCls = Class.forName("com.droidamp.player.audio.dsp.ReplayGainMetadata")
      chain.javaClass.getMethod("setReplayGainMetadata", rgMetaCls).invoke(chain, rgMeta)
    }

    fun readVisualizerFrame(isPlaying: Boolean): VisualizerFrame =
      chain.javaClass.getMethod("readVisualizerFrame", Boolean::class.javaPrimitiveType)
        .invoke(chain, isPlaying) as VisualizerFrame

    fun createRenderersFactory(context: Context): RenderersFactory {
      val rfCls = Class.forName("com.droidamp.player.audio.service.DroidAmpAudioRenderersFactory")
      return rfCls.getDeclaredConstructor(Context::class.java, chain.javaClass)
        .newInstance(context, chain) as RenderersFactory
    }
  }

  private fun createAudioEngineGatewayProxy(chain: ReflectiveDspChainHandle): Any {
    val gwCls = Class.forName("com.droidamp.player.audio.service.AudioEngineGateway")
    var queue: List<AudioTrack> = emptyList()
    var currentIndex = -1
    var positionMs = 0L
    var durationMs = 0L
    return Proxy.newProxyInstance(
      gwCls.classLoader,
      arrayOf(gwCls),
    ) { _, method, args ->
      when (method.name) {
        "setListener" -> Unit
        "setQueue" -> {
          @Suppress("UNCHECKED_CAST")
          queue = args?.getOrNull(0) as? List<AudioTrack> ?: emptyList()
          val startIdx = args?.getOrNull(1) as? Int ?: 0
          currentIndex = if (queue.isEmpty()) -1 else startIdx.coerceIn(0, queue.lastIndex)
          positionMs = 0L
          durationMs = queue.getOrNull(currentIndex)?.durationMs ?: 0L
          Unit
        }
        "syncQueueWithoutInterrupting" -> {
          @Suppress("UNCHECKED_CAST")
          queue = args?.getOrNull(0) as? List<AudioTrack> ?: emptyList()
          currentIndex = args?.getOrNull(1) as? Int ?: -1
          Unit
        }
        "selectTrackAt" -> {
          currentIndex = args?.getOrNull(0) as? Int ?: 0
          positionMs = 0L
          durationMs = queue.getOrNull(currentIndex)?.durationMs ?: 0L
          Unit
        }
        "play", "pause", "stop", "setRepeatMode", "setShuffleEnabled", "onCrossfadeProgressTick", "cancelCrossfade", "release" -> Unit
        "seekTo" -> {
          positionMs = args?.getOrNull(0) as? Long ?: 0L
          Unit
        }
        "applyDspSettings" -> {
          (args?.getOrNull(0) as? DspSettings)?.let { chain.applyDspSettings(it) }
          Unit
        }
        "applyEqualizerState" -> {
          (args?.getOrNull(0) as? EqualizerState)?.let { chain.applyEqualizerState(it) }
          Unit
        }
        "applyVolume" -> {
          chain.setVolume(args?.getOrNull(0) as? Int ?: PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME)
          Unit
        }
        "applyBalance" -> {
          chain.setBalance(args?.getOrNull(0) as? Int ?: 0)
          Unit
        }
        "applyReplayGainMetadata" -> {
          args?.getOrNull(0)?.let { chain.setReplayGainMetadata(it) }
          Unit
        }
        "readVisualizerFrame" -> chain.readVisualizerFrame(args?.getOrNull(0) as? Boolean ?: false)
        "resetVisualizer" -> {
          chain.javaClass.getMethod("resetVisualizer").invoke(chain.chain)
          Unit
        }
        "currentPositionMs" -> positionMs
        "currentDurationMs" -> durationMs
        "getManagesPlaylistTransitions" -> java.lang.Boolean.FALSE
        "equals" -> false
        "hashCode" -> 0
        "toString" -> "Tier4AudioEngineGatewayProxy"
        else -> null
      }
    }
  }

  private data class ReflectiveControllerSession(
    val scope: CoroutineScope,
    val prefsStore: Any,
    val chain: ReflectiveDspChainHandle,
    val controller: AudioEngineController,
  ) : Closeable {
    override fun close() {
      (controller as? Closeable)?.close()
      runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
      }
    }
  }

  private fun buildMedia3ControllerSession(
    prefsFile: File,
    chain: ReflectiveDspChainHandle = ReflectiveDspChainHandle(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
  ): ReflectiveControllerSession {
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { prefsFile })
    val prefsStoreCls = Class.forName("com.droidamp.player.audio.service.DspPreferencesStore")
    val prefsStore = prefsStoreCls.getDeclaredConstructor(DataStore::class.java).newInstance(dataStore)
    val gwProxy = createAudioEngineGatewayProxy(chain)
    val gwCls = Class.forName("com.droidamp.player.audio.service.AudioEngineGateway")
    val ctrlCls = Class.forName("com.droidamp.player.audio.service.Media3AudioEngineController")
    val controller =
      ctrlCls.getDeclaredConstructor(
        gwCls,
        prefsStoreCls,
        CoroutineScope::class.java,
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
      ).newInstance(gwProxy, prefsStore, scope, 25L, 50L) as AudioEngineController

    return ReflectiveControllerSession(scope, prefsStore, chain, controller)
  }

  private suspend fun readDspSnapshotField(prefsStore: Any, getterName: String): Any? {
    val flow = prefsStore.javaClass.getMethod("getSnapshotFlow").invoke(prefsStore) as Flow<*>
    val snapshot = flow.first() ?: error("Null DspPreferencesSnapshot")
    return snapshot.javaClass.getMethod(getterName).invoke(snapshot)
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
    return cls.getDeclaredConstructor(
      String::class.java,
      String::class.java,
      Boolean::class.javaPrimitiveType,
      String::class.java,
    ).newInstance(uriString, name, isDirectory, mimeType)
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
        "isTreeAccessible" -> java.lang.Boolean.valueOf((args?.getOrNull(0) as? String ?: "") in treesByUri)
        "getTreeRootNode" -> {
          val spec = (args?.getOrNull(0) as? String)?.let { treesByUri[it] }
          if (spec == null) null else newSafNode(spec.rootDocUri, spec.rootName, true, "vnd.android.document/directory")
        }
        "listChildren" -> {
          val treeUri = args?.getOrNull(0) as? String
          val dirUri = args?.getOrNull(1) as? String
          if (treeUri !in treesByUri) null else mutableChildren[dirUri]?.toList() ?: emptyList<Any>()
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
            val node = newSafNode(docUri, fileName, false, mimeType)
            val list = mutableChildren.getOrPut(dirUri) { mutableListOf() }
            list.removeAll { (it.javaClass.getMethod("getName").invoke(it) as? String).equals(fileName, ignoreCase = true) }
            list.add(node)
            spec.textFilesByDocUri[docUri] = content
            node
          }
        }
        "equals" -> false
        "hashCode" -> 0
        "toString" -> "Tier4SafGatewayProxy"
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
    return cls.getDeclaredConstructor(
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
    ).newInstance(mediaId, contentUriString, displayName, title, artist, album, durationMs, relativePath, dataPath, mimeType)
  }

  private fun newMediaStorePlaylistFileRecord(
    id: String,
    uriString: String,
    name: String,
    format: String,
    folderPath: String,
  ): Any {
    val cls = Class.forName("com.droidamp.player.storage.MediaStorePlaylistFileRecord")
    return cls.getDeclaredConstructor(
      String::class.java,
      String::class.java,
      String::class.java,
      String::class.java,
      String::class.java,
    ).newInstance(id, uriString, name, format, folderPath)
  }

  private fun createMediaStoreGatewayProxy(
    hasPermissionProvider: () -> Boolean,
    audioRecords: List<Any> = emptyList(),
    throwOnQueryWhenDenied: Boolean = false,
    textFiles: MutableMap<String, String> = mutableMapOf(),
  ): Any {
    val gatewayClass = Class.forName("com.droidamp.player.storage.MediaStoreGateway")
    return Proxy.newProxyInstance(
      gatewayClass.classLoader,
      arrayOf(gatewayClass),
    ) { _, method, args ->
      val granted = hasPermissionProvider()
      when (method.name) {
        "hasAudioReadPermission" -> java.lang.Boolean.valueOf(granted)
        "queryAudioTracks" -> {
          if (!granted && throwOnQueryWhenDenied) throw SecurityException("Permission denied")
          if (granted) audioRecords else emptyList<Any>()
        }
        "queryPlaylists" -> {
          if (!granted && throwOnQueryWhenDenied) throw SecurityException("Permission denied")
          emptyList<Any>()
        }
        "queryPlaylistFiles" -> emptyList<Any>()
        "readTextFile" -> textFiles[args?.getOrNull(0) as? String]
        "writeM3u8PlaylistFile" -> {
          val fileName = args?.getOrNull(0) as? String ?: "export.m3u8"
          val content = args?.getOrNull(1) as? String ?: ""
          val uri = "/app/files/playlists/$fileName"
          textFiles[uri] = content
          newMediaStorePlaylistFileRecord("ms-file:$uri", uri, fileName, "m3u8", "/app/files/playlists")
        }
        "equals" -> false
        "hashCode" -> 0
        "toString" -> "Tier4MediaStoreGatewayProxy"
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
    return cls.getDeclaredConstructor(
      String::class.java,
      String::class.java,
      String::class.java,
      Long::class.javaPrimitiveType,
      Int::class.javaPrimitiveType,
      Int::class.javaPrimitiveType,
      ChannelMode::class.java,
    ).newInstance(title, artist, album, durationMs, bitrateKbps, sampleRateHz, channelMode)
  }

  private fun createProbeProxy(metadataByUri: Map<String, Any> = emptyMap()): Any {
    val probeClass = Class.forName("com.droidamp.player.storage.AudioMetadataProbe")
    return Proxy.newProxyInstance(
      probeClass.classLoader,
      arrayOf(probeClass),
    ) { _, method, args ->
      when (method.name) {
        "probe" -> metadataByUri[args?.getOrNull(0) as? String]
        "equals" -> false
        "hashCode" -> 0
        "toString" -> "Tier4AudioMetadataProbeProxy"
        else -> null
      }
    }
  }

  private data class ReflectiveStorageStack(
    val scope: CoroutineScope,
    val safRepo: MusicSourceRepository,
    val mediaStoreRepo: MusicSourceRepository,
    val switchingRepo: MusicSourceRepository,
  ) : Closeable {
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
    val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { prefsFile })
    val prefsStoreClass = Class.forName("com.droidamp.player.storage.StoragePreferencesStore")
    val prefsStore = prefsStoreClass.getDeclaredConstructor(DataStore::class.java).newInstance(dataStore)

    val probeInterface = Class.forName("com.droidamp.player.storage.AudioMetadataProbe")
    val extractorClass = Class.forName("com.droidamp.player.storage.AudioMetadataExtractor")
    val extractor =
      extractorClass.getDeclaredConstructor(
        probeInterface,
        Int::class.javaPrimitiveType,
        CoroutineDispatcher::class.java,
      ).newInstance(probe, 128, Dispatchers.IO)

    val resolvedSafGateway =
      safGateway
        ?: Class.forName("com.droidamp.player.storage.DocumentFileSafTreeGateway")
          .getDeclaredConstructor(Context::class.java)
          .newInstance(targetContext)
    val safGatewayInterface = Class.forName("com.droidamp.player.storage.SafTreeGateway")
    val safRepoClass = Class.forName("com.droidamp.player.storage.SafMusicSourceRepository")
    val safRepo =
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
    val msRepo =
      msRepoClass.getDeclaredConstructor(
        msGatewayInterface,
        extractorClass,
        PlaylistFileCodec::class.java,
        CoroutineDispatcher::class.java,
      ).newInstance(resolvedMsGateway, extractor, playlistCodec, Dispatchers.IO)

    val switchingClass = Class.forName("com.droidamp.player.storage.SwitchingMusicSourceRepository")
    val switchingRepo =
      switchingClass.getDeclaredConstructor(
        safRepoClass,
        msRepoClass,
        prefsStoreClass,
        CoroutineScope::class.java,
      ).newInstance(safRepo, msRepo, prefsStore, scope)

    return ReflectiveStorageStack(
      scope = scope,
      safRepo = safRepo as MusicSourceRepository,
      mediaStoreRepo = msRepo as MusicSourceRepository,
      switchingRepo = switchingRepo as MusicSourceRepository,
    )
  }

  // =========================================================================
  // Audio Signal & Rack Helpers
  // =========================================================================

  private fun generateSinePcm16(
    sampleRateHz: Int,
    freqHz: Double,
    durationSec: Double,
    amplitude: Double = 0.25,
    channels: Int = 1,
  ): ShortArray {
    val frames = (sampleRateHz * durationSec).roundToInt()
    val out = ShortArray(frames * channels)
    for (f in 0 until frames) {
      val s =
        (sin(2.0 * PI * freqHz * f / sampleRateHz) * amplitude * 32767.0)
          .roundToInt()
          .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
          .toShort()
      for (c in 0 until channels) {
        out[f * channels + c] = s
      }
    }
    return out
  }

  private fun pumpPcm16(
    processors: Array<AudioProcessor>,
    input: ShortArray,
    sampleRateHz: Int = 44_100,
    channels: Int = 1,
  ): ShortArray {
    val fmt = AudioProcessor.AudioFormat(sampleRateHz, channels, C.ENCODING_PCM_16BIT)
    processors.forEach { p ->
      p.configure(fmt)
      p.flush(AudioProcessor.StreamMetadata.DEFAULT)
    }
    val chunkSamples = 512 * channels
    val out = ArrayList<Short>(input.size)
    var offset = 0
    while (offset < input.size) {
      val count = minOf(chunkSamples, input.size - offset)
      var buf = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
      for (i in 0 until count) buf.putShort(input[offset + i])
      buf.flip()
      for (p in processors) {
        if (!p.isActive) continue
        p.queueInput(buf)
        val pOut = p.output
        val next = ByteBuffer.allocateDirect(pOut.remaining()).order(ByteOrder.nativeOrder())
        next.put(pOut)
        next.flip()
        buf = next
      }
      while (buf.remaining() >= 2) out.add(buf.getShort())
      offset += count
    }
    return out.toShortArray()
  }

  private fun rmsChannel(samples: ShortArray, channels: Int, channelIndex: Int, startFrame: Int = 0): Double {
    val totalFrames = samples.size / channels
    var sumSq = 0.0
    var count = 0
    for (f in startFrame until totalFrames) {
      val v = samples[f * channels + channelIndex] / 32768.0
      sumSq += v * v
      count++
    }
    return if (count == 0) 0.0 else sqrt(sumSq / count)
  }

  private fun rmsDbDelta(before: ShortArray, after: ShortArray, channels: Int, channelIndex: Int, startFrame: Int): Double {
    val rIn = rmsChannel(before, channels, channelIndex, startFrame).coerceAtLeast(1e-12)
    val rOut = rmsChannel(after, channels, channelIndex, startFrame).coerceAtLeast(1e-12)
    return 20.0 * log10(rOut / rIn)
  }

  private inline fun <T> withDspExoPlayer(
    chain: ReflectiveDspChainHandle,
    block: (ExoPlayer) -> T,
  ): T {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val rf = chain.createRenderersFactory(targetContext)
    lateinit var player: ExoPlayer
    instrumentation.runOnMainSync {
      player = ExoPlayer.Builder(targetContext, rf).build()
    }
    try {
      return block(player)
    } finally {
      instrumentation.runOnMainSync { player.release() }
    }
  }

  private fun mountRack(viewModel: DroidAmpViewModel): E2eRackDriver {
    composeRule.setContent {
      CompositionLocalProvider(LocalDroidAmpSkin provides ClassicDroidAmpSkin) {
        DroidAmpRackScreen(viewModel = viewModel)
      }
    }
    composeRule.waitForIdle()
    return E2eRackDriver(composeRule)
  }

  // =========================================================================
  // Scenario 1B: Link Music Library -> Browse Subfolder -> Select-First -> Play
  // =========================================================================

  @Test // T4-S1B
  fun scenario01B_onboardingLinkSafRoot_browseSubfolderSelectFirstAndPlay() {
    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ALibraryRoot"
    val rootDocUri = "$treeUri/document/primary%3ALibraryRoot"
    val albumDirUri = "$treeUri/document/primary%3ALibraryRoot%2FAlbums"
    val track1Uri = "$albumDirUri%2F01_dawn.wav"
    val track2Uri = "$albumDirUri%2F02_dusk.wav"

    val safSpec = SafTreeSpec(
      treeUri = treeUri,
      rootDocUri = rootDocUri,
      rootName = "LibraryRoot",
      childrenByDirUri = mapOf(
        rootDocUri to listOf(newSafNode(albumDirUri, "Albums", true, "vnd.android.document/directory")),
        albumDirUri to listOf(
          newSafNode(track1Uri, "01_dawn.wav", false, "audio/wav"),
          newSafNode(track2Uri, "02_dusk.wav", false, "audio/wav"),
        ),
      ),
    )
    val probe = createProbeProxy(
      mapOf(
        track1Uri to newProbedMetadata("Dawn", "Solaris", "Horizons", 180_000L, 1411, 44_100, ChannelMode.STEREO),
        track2Uri to newProbedMetadata("Dusk", "Solaris", "Horizons", 210_000L, 1411, 44_100, ChannelMode.STEREO),
      ),
    )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t4_s1b").use { cache ->
      buildStorageStack(
        prefsFile = nextPrefsFile(cache.rootDir, "t4_s1b"),
        safGateway = createSafGatewayProxy(listOf(safSpec)),
        probe = probe,
      ).use { stack ->
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
          val engine = FakeAudioEngineController(uiScope, enableSimulationTicker = false)
          val vm = DroidAmpViewModel(engine, stack.switchingRepo)
          mountRack(vm)

          DroidAmpSelectors.emptyFolderTitlePeeking(composeRule).assertIsDisplayed()

          vm.onSelectMusicFolder(treeUri)
          composeRule.waitUntil(3_000L) { vm.uiState.value.folderContents.currentFolderUri != null }
          composeRule.onNode(hasText("DROIDAMP LIBRARY ▼")).assertIsDisplayed()

          DroidAmpSelectors.libraryFolderRow(composeRule, "Albums").performClick()
          composeRule.waitUntil(3_000L) { vm.uiState.value.folderContents.tracks.size == 2 }
          DroidAmpSelectors.libraryParentFolderRow(composeRule).assertIsDisplayed()

          // Select-first safety: single tap highlights without starting playback
          DroidAmpSelectors.singleTapNode(
            composeRule,
            DroidAmpSelectors.libraryTrackRow(composeRule, "Solaris - Dawn"),
          )
          assertEquals(PlaybackStatus.STOPPED, engine.playbackState.value.status)
          composeRule.onNode(hasText("▶ PLAY (1)")).assertIsDisplayed().performClick()
          composeRule.waitForIdle()

          composeRule.onNode(hasText("DROIDAMP PLAYLIST ▼")).assertIsDisplayed()
          assertEquals(PlaybackStatus.PLAYING, engine.playbackState.value.status)
          assertEquals("Dawn", engine.playbackState.value.currentTrack?.title)
          DroidAmpSelectors.marqueeText(composeRule, "SOLARIS - DAWN").assertIsDisplayed()
        } finally {
          uiScope.cancel()
        }
      }
    }
  }

  // =========================================================================
  // Scenario 2: The Doors - L.A. Woman.mp3 Playback, Live VFD & Clock Toggle
  // =========================================================================

  @Test // T4-S2
  fun scenario02_doorsLaWomanOrFallback_playsThroughRealDspChainWithLiveVfdAndClockToggle() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val perm =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
      } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
      }
    runCatching {
      instrumentation.uiAutomation.grantRuntimePermission(targetContext.packageName, perm)
    }

    SyntheticAudioFixtures.createCacheScope(targetContext, "t4_s2_doors").use { cache ->
      val deviceTrackPresent = DeviceManifestVerifier.isDoorsTestTrackPresentOnDevice(targetContext)
      val realFile = DeviceManifestVerifier.findDoorsTestTrackFile()?.takeIf { it.canRead() }
      var mediaStoreUri = DeviceManifestVerifier.findDoorsTestTrackMediaStoreUri(targetContext)
      var stagedDeviceFile: File? = null

      if (realFile == null && mediaStoreUri == null && deviceTrackPresent) {
        val scanDone = CompletableDeferred<Unit>()
        runCatching {
          MediaScannerConnection.scanFile(
            targetContext,
            arrayOf(
              DeviceManifestVerifier.DEFAULT_DOORS_SDCARD_PATH,
              "/storage/emulated/0/Music/${DeviceManifestVerifier.DOORS_TRACK_FILE_NAME}",
            ),
            arrayOf("audio/mpeg"),
          ) { _, _ -> scanDone.complete(Unit) }
          runBlocking { withTimeout(2_000L) { scanDone.await() } }
        }
        mediaStoreUri = DeviceManifestVerifier.findDoorsTestTrackMediaStoreUri(targetContext)

        if (mediaStoreUri == null) {
          val candidateCopy = File(cache.rootDir, DeviceManifestVerifier.DOORS_TRACK_FILE_NAME)
          val copied = runCatching {
            val pfd = instrumentation.uiAutomation.executeShellCommand(
              "cat \"${DeviceManifestVerifier.DEFAULT_DOORS_SDCARD_PATH}\"",
            )
            pfd.use {
              ParcelFileDescriptor.AutoCloseInputStream(it).use { input ->
                candidateCopy.outputStream().use { output -> input.copyTo(output) }
              }
            }
            candidateCopy.exists() && candidateCopy.length() > 100_000L
          }.getOrDefault(false)
          if (copied) {
            stagedDeviceFile = candidateCopy
          } else {
            candidateCopy.delete()
          }
        }
      }

      val playbackUri: Uri =
        when {
          realFile != null -> Uri.fromFile(realFile)
          mediaStoreUri != null -> mediaStoreUri
          stagedDeviceFile != null -> Uri.fromFile(stagedDeviceFile)
          else -> {
            assertFalse(
              "Doors file reported on device but neither File, MediaStore URI, nor shell copy resolved",
              deviceTrackPresent,
            )
            // Non-silent synthetic WAV fallback so VisualizerTapProcessor receives real non-zero PCM audio
            // and >= 35s duration supports seekTo(30_000L) + clock mode verification.
            val fallbackWav = cache.writeFile(
              "The Doors - L.A. Woman.wav",
              SyntheticAudioFixtures.generateWav(
                sampleRateHz = 44_100,
                channels = 2,
                durationMs = 36_000L,
                leftFrequencyHz = 440.0,
                rightFrequencyHz = 880.0,
                leftAmplitude = 0.5,
                rightAmplitude = 0.5,
              ),
            )
            Uri.fromFile(fallbackWav)
          }
        }

      // Extract real metadata via MediaMetadataRetriever & AndroidAudioMetadataProbe
      val retriever = MediaMetadataRetriever()
      val probedDurationMs: Long
      val probedTitle: String
      val probedArtist: String
      try {
        if (playbackUri.scheme == "file" && playbackUri.path != null) {
          retriever.setDataSource(playbackUri.path)
        } else {
          retriever.setDataSource(targetContext, playbackUri)
        }
        probedDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        probedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: "L.A. Woman"
        probedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: "The Doors"
      } finally {
        runCatching { retriever.release() }
      }
      assertTrue("Duration must be > 30s for clock toggle test, got $probedDurationMs", probedDurationMs >= 35_000L)

      val probeCls = Class.forName("com.droidamp.player.storage.AndroidAudioMetadataProbe")
      val probe = probeCls.getDeclaredConstructor(Context::class.java).newInstance(targetContext)
      val probedObj = probeCls.getMethod("probe", String::class.java).invoke(probe, playbackUri.toString())
      assertNotNull("AndroidAudioMetadataProbe must probe track URI", probedObj)

      // Strict AudioTrack zero-art check
      val artTokens = setOf("art", "artwork", "albumart", "embeddedart", "arturi", "artbytes", "bitmap", "cover", "thumb", "picture")
      val badFields = AudioTrack::class.java.declaredFields.filter { f ->
        val n = f.name.lowercase(Locale.ROOT)
        n in artTokens || "artwork" in n || "albumart" in n || "cover" in n || "bitmap" in n
      }
      assertTrue("AudioTrack must never contain art fields: $badFields", badFields.isEmpty())

      val doorsTrack = AudioTrack(
        id = "doors-la-woman",
        uriString = playbackUri.toString(),
        title = probedTitle,
        artist = probedArtist,
        album = "L.A. Woman",
        fileName = DeviceManifestVerifier.DOORS_TRACK_FILE_NAME,
        folderPath = "/Music",
        durationMs = probedDurationMs,
        bitrateKbps = 192,
        sampleRateHz = 44_100,
        channelMode = ChannelMode.STEREO,
      )

      val chain = ReflectiveDspChainHandle()
      chain.setVolume(204)
      val capturedFrame = MutableStateFlow(VisualizerFrame.SILENT)

      withDspExoPlayer(chain) { player ->
        val readyDeferred = CompletableDeferred<Unit>()
        val errorDeferred = CompletableDeferred<PlaybackException>()
        instrumentation.runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) readyDeferred.complete(Unit)
              }

              override fun onPlayerError(error: PlaybackException) {
                errorDeferred.complete(error)
                readyDeferred.completeExceptionally(error)
              }
            },
          )
          player.setMediaItem(MediaItem.fromUri(playbackUri))
          player.prepare()
          player.play()
        }
        runBlocking {
          withTimeout(10_000L) {
            readyDeferred.await()
            while (true) {
              val frame = chain.readVisualizerFrame(true)
              if (frame.spectrumBars.any { it > 0f } && frame.waveform.any { abs(it) > 0f }) {
                capturedFrame.value = frame
                break
              }
              delay(20L)
            }
          }
        }
        assertFalse("Unexpected ExoPlayer error on Doors track", errorDeferred.isCompleted)

        // Seek past 30s in real ExoPlayer to verify real playback timeline advancement
        instrumentation.runOnMainSync { player.seekTo(30_000L) }
        val seekPositionMs = runBlocking {
          var pos = 0L
          withTimeout(5_000L) {
            while (pos < 29_500L) {
              instrumentation.runOnMainSync { pos = player.currentPosition }
              delay(25L)
            }
          }
          pos
        }
        assertTrue("Expected ExoPlayer seek >= 29500 ms, got $seekPositionMs", seekPositionMs >= 29_500L)
        instrumentation.runOnMainSync { player.stop() }
      }

      // Wire live VisualizerFrame + Doors AudioTrack into DroidAmpRackScreen and verify VFD + Clock toggles
      val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
      try {
        val delegateEngine = FakeAudioEngineController(uiScope, enableSimulationTicker = false)
        val liveEngine = object : AudioEngineController by delegateEngine {
          override val visualizerFrame: StateFlow<VisualizerFrame> = capturedFrame.asStateFlow()
        }
        val vm = DroidAmpViewModel(liveEngine, FakeMusicSourceRepository(startLinked = true))
        val driver = mountRack(vm)
        delegateEngine.replaceQueue(listOf(doorsTrack), startIndex = 0, autoPlay = true)
        delegateEngine.seekTo(30_000L)
        composeRule.waitForIdle()

        assertTrue(capturedFrame.value.spectrumBars.any { it > 0f })
        assertTrue(capturedFrame.value.peakDots.any { it > 0f })
        assertTrue(capturedFrame.value.waveform.any { abs(it) > 0f })

        DroidAmpSelectors.visualizerWithMode(composeRule, VisualizerMode.SPECTRUM.name).assertIsDisplayed()
        driver.cycleVisualizerMode()
        DroidAmpSelectors.visualizerWithMode(composeRule, VisualizerMode.OSCILLOSCOPE.name).assertIsDisplayed()
        driver.cycleVisualizerMode()
        DroidAmpSelectors.visualizerWithMode(composeRule, VisualizerMode.OFF.name).assertIsDisplayed()
        driver.cycleVisualizerMode()
        DroidAmpSelectors.visualizerWithMode(composeRule, VisualizerMode.SPECTRUM.name).assertIsDisplayed()

        DroidAmpSelectors.clockWithTime(composeRule, "00:30").assertIsDisplayed()
        driver.toggleClockMode()
        assertEquals(TimeDisplayMode.REMAINING, delegateEngine.playbackState.value.timeDisplayMode)
        composeRule.onNode(
          androidx.compose.ui.test.hasContentDescription("Track timer -", substring = true),
        ).assertIsDisplayed()

        DeviceManifestVerifier.assertZeroInAppAlbumArt(composeRule)
      } finally {
        uiScope.cancel()
      }
    }
  }

  // =========================================================================
  // Scenario 3: Classic EQ Preset, Curve Drawing, PREAMP Isolation & AUTO
  // =========================================================================

  @Test // T4-S3
  fun scenario03_classicEqPresets_curveDrawingPreampIsolationAndAutoToggle() {
    val chain = ReflectiveDspChainHandle()
    val rules =
      q2Codec.parseAutoPresetsQ2(
        SyntheticWinampFixtures.q2Bytes(
          listOf(SyntheticWinampFixtures.Q2RuleSpec("Synthwave", ClassicWinampPresets.TECHNO)),
        ),
      )
    val synthTrack = AudioTrack(
      id = "s3-synth",
      uriString = "file:///Music/Synthwave/Pulse.mp3",
      title = "Pulse",
      artist = "Neon",
      fileName = "pulse.mp3",
      folderPath = "Music/Synthwave",
      durationMs = 150_000L,
    )

    E2eRackDriver.launchIsolatedRack(composeRule, initialQueue = listOf(synthTrack), autoPlay = true).use { driver ->
      driver.selectEqPreset(ClassicWinampPresets.CLASSICAL.name)
      chain.applyEqualizerState(driver.engine.playbackState.value.equalizer)

      val tone12k = generateSinePcm16(44_100, 12_000.0, 0.25, amplitude = 0.2, channels = 1)
      val outClassical = pumpPcm16(arrayOf(chain.eqProcessor), tone12k, 44_100, 1)
      val classicalDelta = rmsDbDelta(tone12k, outClassical, 1, 0, 4_410)
      assertTrue("CLASSICAL must attenuate 12 kHz (< -2 dB), got $classicalDelta", classicalDelta < -2.0)

      // Set PREAMP +4 dB, then draw diagonal curve 0..9 (-8 dB -> +8 dB)
      driver.setPreampDb(4.0f)
      val preampBefore = driver.engine.playbackState.value.equalizer.preampDb
      DroidAmpSelectors.drawEqCurve(composeRule, fromBand = 0, toBand = 9, startDb = -8f, endDb = 8f)
      composeRule.waitForIdle()

      val eqAfterCurve = driver.engine.playbackState.value.equalizer
      assertEquals("Curve drag must never disturb PREAMP", preampBefore, eqAfterCurve.preampDb, 0.01f)
      assertTrue("Band 0 must be negative, got ${eqAfterCurve.bandsDb[0]}", eqAfterCurve.bandsDb[0] < -2f)
      assertTrue("Band 9 must be positive, got ${eqAfterCurve.bandsDb[9]}", eqAfterCurve.bandsDb[9] > 2f)
      DeviceManifestVerifier.assertPreampGapIsolation(composeRule)

      // Toggle AUTO and apply AutoEqMatcher resolution
      driver.toggleEqAuto()
      assertTrue(driver.engine.playbackState.value.equalizer.autoEnabled)
      val resolved = autoEqResolver.resolveForTrack(synthTrack, rules, ClassicWinampPresets.ALL)!!
      driver.viewModel.applyEqPreset(resolved)
      chain.applyEqualizerState(driver.engine.playbackState.value.equalizer)

      // Toggle ON off & back on: UI bands stay intact while real processor toggles bypass
      val savedBands = driver.engine.playbackState.value.equalizer.bandsDb
      driver.toggleEqEnabled()
      chain.applyEqualizerState(driver.engine.playbackState.value.equalizer)
      assertEquals(savedBands, driver.engine.playbackState.value.equalizer.bandsDb)
      val bypassed = pumpPcm16(arrayOf(chain.eqProcessor), tone12k, 44_100, 1)
      assertArrayEquals("EQ OFF must pass PCM bit-exact", tone12k, bypassed)

      driver.toggleEqEnabled()
      assertTrue(driver.engine.playbackState.value.equalizer.enabled)
    }
  }

  // =========================================================================
  // Scenario 4: Queue Curation: Select-First, Reorder, Jump-Filter & SAVE .M3U8
  // =========================================================================

  @Test // T4-S4
  fun scenario04_multiTrackQueueCuration_selectFirstReorderJumpFilterAndSaveM3u8() {
    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ACuration"
    val rootDocUri = "$treeUri/document/primary%3ACuration"
    val subDirUri = "$treeUri/document/primary%3ACuration%2FDeep"
    val aUri = "$rootDocUri%2Fa_first.wav"
    val bUri = "$rootDocUri%2Fb_second.wav"
    val cUri = "$subDirUri%2Fc_deep.wav"
    val dUri = "$subDirUri%2Fd_ambient.wav"

    val safSpec = SafTreeSpec(
      treeUri = treeUri,
      rootDocUri = rootDocUri,
      rootName = "Curation",
      childrenByDirUri = mapOf(
        rootDocUri to listOf(
          newSafNode(subDirUri, "Deep", true, "vnd.android.document/directory"),
          newSafNode(aUri, "a_first.wav", false, "audio/wav"),
          newSafNode(bUri, "b_second.wav", false, "audio/wav"),
        ),
        subDirUri to listOf(
          newSafNode(cUri, "c_deep.wav", false, "audio/wav"),
          newSafNode(dUri, "d_ambient.wav", false, "audio/wav"),
        ),
      ),
    )
    val probe = createProbeProxy(
      mapOf(
        aUri to newProbedMetadata("First Track", "Alpha", durationMs = 120_000L),
        bUri to newProbedMetadata("Second Track", "Beta", durationMs = 130_000L),
        cUri to newProbedMetadata("Deep Cut", "Gamma", durationMs = 140_000L),
        dUri to newProbedMetadata("Ambient Horizon", "Delta", durationMs = 150_000L),
      ),
    )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t4_s4_curate").use { cache ->
      buildStorageStack(
        prefsFile = nextPrefsFile(cache.rootDir, "t4_s4"),
        safGateway = createSafGatewayProxy(listOf(safSpec)),
        probe = probe,
      ).use { stack ->
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
          val engine = FakeAudioEngineController(uiScope, enableSimulationTicker = false)
          val vm = DroidAmpViewModel(engine, stack.switchingRepo)
          val driver = mountRack(vm)

          vm.onSelectMusicFolder(treeUri)
          composeRule.waitUntil(3_000L) { vm.uiState.value.folderContents.tracks.size == 2 }

          // Single-tap track A (select-first, stopped) -> + QUEUE
          DroidAmpSelectors.singleTapNode(
            composeRule,
            DroidAmpSelectors.libraryTrackRow(composeRule, "Alpha - First Track"),
          )
          assertEquals(PlaybackStatus.STOPPED, engine.playbackState.value.status)
          DroidAmpSelectors.libraryPrimaryQueueButton(composeRule).performClick()
          composeRule.waitForIdle()
          assertEquals(1, engine.playbackState.value.queue.size)

          // Double-tap track B -> starts playing track B, switches to PLAYLIST
          DroidAmpSelectors.doubleTapNode(DroidAmpSelectors.libraryTrackRow(composeRule, "Beta - Second Track"))
          composeRule.waitForIdle()
          assertEquals(PlaybackStatus.PLAYING, engine.playbackState.value.status)
          assertEquals("Second Track", engine.playbackState.value.currentTrack?.title)

          // Return to LIBRARY and recursively enqueue "Deep" folder (2 tracks -> total 3)
          driver.ensureSheetExpanded(SheetMode.LIBRARY)
          DroidAmpSelectors.libraryFolderEnqueueButton(composeRule, "Deep").performClick()
          composeRule.waitUntil(3_000L) { engine.playbackState.value.queue.size == 3 }

          // Switch to PLAYLIST, move active track 0 -> 1 via ▲/▼, then Jump-Filter "Ambient"
          DroidAmpSelectors.libraryPlButton(composeRule).performClick()
          composeRule.waitForIdle()
          DroidAmpSelectors.singleTapNode(
            composeRule,
            DroidAmpSelectors.playlistRow(composeRule, "Beta - Second Track"),
          )
          DroidAmpSelectors.playlistMoveDownButton(composeRule, "Beta - Second Track").performClick()
          composeRule.waitForIdle()
          assertEquals(1, engine.playbackState.value.currentIndex)
          assertEquals("Second Track", engine.playbackState.value.currentTrack?.title)

          driver.setJumpFilterQuery("Ambient")
          val filtered = DroidAmpSelectors.playlistRow(composeRule, "Delta - Ambient Horizon")
          filtered.assertIsDisplayed()
          DroidAmpSelectors.doubleTapNode(filtered)
          composeRule.waitForIdle()
          assertEquals(2, engine.playbackState.value.currentIndex)
          assertEquals("Ambient Horizon", engine.playbackState.value.currentTrack?.title)
          DroidAmpSelectors.jumpFilterCloseButton(composeRule).performClick()
          composeRule.waitForIdle()

          // Trigger [LIST] -> SAVE .M3U8 and verify real M3U8 export + round-trip
          driver.clickPlaylistMenuAction(DroidAmpSelectors.playlistListButton(composeRule), "LIST OPTS", "SAVE .M3U8")
          composeRule.waitUntil(3_000L) { !vm.uiState.value.statusBanner.isNullOrBlank() }
          assertEquals(3, engine.playbackState.value.queue.size)
          assertFalse(vm.uiState.value.statusBanner.isNullOrBlank())

          val exportedM3u8 = playlistCodec.encodeM3u8(engine.playbackState.value.queue)
          val saveResult = runBlocking { stack.switchingRepo.saveM3u8Playlist("curated.m3u8", exportedM3u8) }
          assertTrue(saveResult.isSuccess)
          val reparsedEntries = playlistCodec.parseM3u(exportedM3u8)
          assertEquals(3, reparsedEntries.size)
        } finally {
          uiScope.cancel()
        }
      }
    }
  }

  // =========================================================================
  // Scenario 5: Volume Cold-Launch 204 vs Resume Preservation & Balance Sweep
  // =========================================================================

  @Test // T4-S5
  fun scenario05_independentDspSession_coldLaunch204VsResumeAndBalanceSweep() {
    val baselineAudio = DeviceManifestVerifier.queryAudioDeviceSnapshot(targetContext)

    SyntheticAudioFixtures.createCacheScope(targetContext, "t4_s5_dsp").use { cache ->
      val prefsFile = nextPrefsFile(cache.rootDir, "t4_s5")
      val vmState = mutableStateOf<DroidAmpViewModel?>(null)
      var driver: E2eRackDriver? = null

      // Session 1: set volume=50 (19%) & balance=-127 (L100) via UI sliders while STOPPED
      buildMedia3ControllerSession(prefsFile).use { s1 ->
        val vm1 = DroidAmpViewModel(s1.controller, FakeMusicSourceRepository(startLinked = true))
        vmState.value = vm1
        composeRule.setContent {
          vmState.value?.let { currentVm ->
            CompositionLocalProvider(LocalDroidAmpSkin provides ClassicDroidAmpSkin) {
              DroidAmpRackScreen(viewModel = currentVm)
            }
          }
        }
        composeRule.waitForIdle()
        val driver1 = E2eRackDriver(composeRule)
        driver = driver1

        s1.controller.setVolume(50)
        driver1.setBalanceFraction(0f)
        composeRule.waitForIdle()
        composeRule.onNode(hasText("19%")).assertIsDisplayed()
        composeRule.onNode(hasText("L100")).assertIsDisplayed()

        // OS STREAM_MUSIC must never be altered by DroidAmp internal DSP volume
        assertEquals(
          baselineAudio.musicStreamVolume,
          DeviceManifestVerifier.queryAudioDeviceSnapshot(targetContext).musicStreamVolume,
        )

        // Verify VolumeBalanceProcessor silences Right channel at -127 and applies 40 dB log taper at volume=50
        val stereo = generateSinePcm16(44_100, 1_000.0, 0.1, amplitude = 0.3, channels = 2)
        val leftOnly = pumpPcm16(arrayOf(s1.chain.volBalProcessor), stereo, 44_100, 2)
        assertTrue(rmsChannel(leftOnly, 2, 1, 500) < 1e-4)
        val leftRms = rmsChannel(leftOnly, 2, 0, 500)
        assertTrue("Expected left channel log-taper RMS in 0.002..0.005 at volume=50, was $leftRms", leftRms in 0.002..0.005)

        runBlocking {
          withTimeout(3_000L) {
            while (
              readDspSnapshotField(s1.prefsStore, "getVolume") != 50 ||
              readDspSnapshotField(s1.prefsStore, "getBalance") != -127
            ) {
              delay(15L)
            }
          }
        }
      }

      // Session 2: Cold launch while STOPPED -> volume resets 50 -> 204 (80%), balance -127 restored!
      buildMedia3ControllerSession(prefsFile).use { s2 ->
        runBlocking {
          withTimeout(3_000L) {
            while (
              s2.controller.playbackState.value.volume != 50 ||
              s2.controller.playbackState.value.balance != -127
            ) {
              delay(15L)
            }
          }
        }
        val vm2 = DroidAmpViewModel(s2.controller, FakeMusicSourceRepository(startLinked = true))
        assertEquals(204, s2.controller.playbackState.value.volume)
        assertEquals(-127, s2.controller.playbackState.value.balance)

        vmState.value = vm2
        composeRule.waitForIdle()
        val driver2 = driver!!
        composeRule.onNode(hasText("80%")).assertIsDisplayed()
        composeRule.onNode(hasText("L100")).assertIsDisplayed()

        // Balance sweep L100 -> CTR -> R100
        driver2.setBalanceFraction(0.5f)
        composeRule.onNode(hasText("CTR")).assertIsDisplayed()
        val stereo = generateSinePcm16(44_100, 1_000.0, 0.1, amplitude = 0.3, channels = 2)
        val centered = pumpPcm16(arrayOf(s2.chain.volBalProcessor), stereo, 44_100, 2)
        assertEquals(rmsChannel(centered, 2, 0, 500), rmsChannel(centered, 2, 1, 500), 0.005)

        driver2.setBalanceFraction(1f)
        composeRule.onNode(hasText("R100")).assertIsDisplayed()
        val rightOnly = pumpPcm16(arrayOf(s2.chain.volBalProcessor), stereo, 44_100, 2)
        assertTrue(rmsChannel(rightOnly, 2, 0, 500) < 1e-4)
        assertTrue(rmsChannel(rightOnly, 2, 1, 500) > 0.01)

        // PLAYING & PAUSED preservation of volume = 35
        s2.controller.replaceQueue(FakeMusicSourceRepository.DAFT_PUNK_TRACKS.take(1), 0, autoPlay = true)
        s2.controller.setVolume(35)
        val vmPlaying = DroidAmpViewModel(s2.controller, FakeMusicSourceRepository(startLinked = true))
        assertEquals(35, vmPlaying.playbackState.value.volume)

        s2.controller.pause()
        val vmPaused = DroidAmpViewModel(s2.controller, FakeMusicSourceRepository(startLinked = true))
        assertEquals(35, vmPaused.playbackState.value.volume)
      }
    }
  }

  // =========================================================================
  // Scenario 6: ReplayGain + Gapless/Crossfade + Multi-Rate Nyquist Mix
  // =========================================================================

  @Test // T4-S6
  fun scenario06_replayGainGaplessCrossfadeAndMultiSampleRateNyquistMix() {
    val chain = ReflectiveDspChainHandle()
    chain.applyDspSettings(
      DspSettings(
        gaplessEnabled = true,
        crossfadeSeconds = 0,
        replayGainMode = ReplayGainMode.TRACK,
        replayGainPreampDb = 2.0f,
      ),
    )
    chain.applyEqualizerState(
      EqualizerState(
        enabled = true,
        preampDb = ClassicWinampPresets.FULL_BASS_TREBLE.preampDb,
        bandsDb = ClassicWinampPresets.FULL_BASS_TREBLE.bandsDb,
        activePresetName = ClassicWinampPresets.FULL_BASS_TREBLE.name,
      ),
    )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t4_s6_mix").use { cache ->
      val f1 = cache.writeFile("01_22k.wav", SyntheticAudioFixtures.generateWav(22_050, 2, 350L, 440.0))
      val f2 = cache.writeFile(
        "02_44k_rg.mp3",
        SyntheticAudioFixtures.generateMp3(
          44_100,
          128,
          2,
          450L,
          SyntheticAudioFixtures.Id3MetadataSpec(
            title = "RG 44k",
            replayGainTrackGainDb = "-6.50 dB",
            replayGainTrackPeak = "0.980000",
          ),
        ),
      )
      val f3 = cache.writeFile("03_48k.wav", SyntheticAudioFixtures.generateWav(48_000, 2, 350L, 1_000.0))
      val f4 = cache.writeFile(
        "04_32k_rg.mp3",
        SyntheticAudioFixtures.generateMp3(
          32_000,
          128,
          2,
          450L,
          SyntheticAudioFixtures.Id3MetadataSpec(
            title = "RG 32k",
            replayGainTrackGainDb = "+6.00 dB",
            replayGainTrackPeak = "0.800000",
          ),
        ),
      )

      withDspExoPlayer(chain) { player ->
        val transitions = CopyOnWriteArrayList<Int>()
        val ended = CompletableDeferred<Unit>()
        val err = CompletableDeferred<PlaybackException>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitions.add(player.currentMediaItemIndex)
              }

              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended.complete(Unit)
              }

              override fun onPlayerError(error: PlaybackException) {
                err.complete(error)
                ended.completeExceptionally(error)
              }
            },
          )
          player.setMediaItems(
            listOf(f1, f2, f3, f4).map { MediaItem.fromUri(cache.fileUri(it)) },
          )
          player.prepare()
          player.play()
        }
        runBlocking { withTimeout(12_000L) { ended.await() } }
        assertFalse("Mixed-sample-rate queue must not throw ExoPlayer error", err.isCompleted)
        assertTrue("Expected transitions across all 4 mixed-rate items, got $transitions", transitions.containsAll(listOf(0, 1, 2, 3)))
      }
    }

    // Direct Nyquist guard check at 22_050 Hz & 32_000 Hz (12k/14k/16k = +12 dB must bypass safely)
    val highOnly = MutableList(WinampEqSpec.BAND_COUNT) { 0f }.apply {
      this[7] = 12f
      this[8] = 12f
      this[9] = 12f
    }
    chain.applyEqualizerState(EqualizerState(enabled = true, preampDb = 0f, bandsDb = highOnly))
    for (rate in listOf(22_050, 32_000)) {
      val input = generateSinePcm16(rate, 1_000.0, 0.2, amplitude = 0.25, channels = 1)
      val out = pumpPcm16(arrayOf(chain.eqProcessor), input, rate, 1)
      val delta = rmsDbDelta(input, out, 1, 0, (rate * 0.05).toInt())
      assertEquals("Nyquist-guarded high bands must not distort 1kHz at $rate Hz", 0.0, delta, 0.25)
    }
  }

  // =========================================================================
  // Scenario 7: Winamp Legacy Library Import (.ini + .eqf + .q1 + .q2 + .pls)
  // =========================================================================

  @Test // T4-S7
  fun scenario07_winampLegacyLibraryImport_allFiveFormatsAndM3u8ExportRoundTrip() {
    val customEqf = SyntheticWinampFixtures.eqfBytes(
      EqPreset("WarmLegacy", 2.0f, listOf(4f, 3f, 2f, 0f, -1f, 0f, 1f, 2f, 3f, 3f)),
    )
    val q1Bytes = SyntheticWinampFixtures.q1Bytes(listOf(ClassicWinampPresets.ROCK, ClassicWinampPresets.DANCE))
    val q2Bytes = SyntheticWinampFixtures.q2Bytes(
      listOf(SyntheticWinampFixtures.Q2RuleSpec("Classics", ClassicWinampPresets.ROCK)),
    )
    val iniText = SyntheticWinampFixtures.winampIni(
      WinampIniSnapshot(
        volume = 180,
        pan = 32,
        shuffle = true,
        repeat = true,
        timeRemaining = true,
        eqEnabled = true,
        eqAuto = true,
        eqPreampDb = 1.0f,
        eqBandsDb = ClassicWinampPresets.FLAT.bandsDb,
        replayGainMode = 1,
        replayGainPreampDb = -1.5f,
        crossfadeSeconds = 3,
      ),
    )

    val parsedIni = iniCodec.parse(iniText)
    val parsedEqf = eqfCodec.parseSingleEqf(customEqf)
    val parsedQ1 = eqfCodec.parsePresetLibraryQ1(q1Bytes)
    val parsedQ2 = q2Codec.parseAutoPresetsQ2(q2Bytes)

    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ALegacyWinamp"
    val rootDocUri = "$treeUri/document/primary%3ALegacyWinamp"
    val subDirUri = "$treeUri/document/primary%3ALegacyWinamp%2FClassics"
    val t1Uri = "$subDirUri%2F01_retro.mp3"
    val t2Uri = "$subDirUri%2F02_winamp.mp3"
    val plsUri = "$rootDocUri%2Flegacy_mix.pls"
    val plsText = SyntheticWinampFixtures.plsContent(
      listOf(
        SyntheticWinampFixtures.PlaylistEntrySpec("Classics\\01_retro.mp3", "Retro Mix", 180L),
        SyntheticWinampFixtures.PlaylistEntrySpec("./Classics/02_winamp.mp3", "Winamp Whip", 200L),
      ),
    )

    val safSpec = SafTreeSpec(
      treeUri = treeUri,
      rootDocUri = rootDocUri,
      rootName = "LegacyWinamp",
      childrenByDirUri = mapOf(
        rootDocUri to listOf(
          newSafNode(subDirUri, "Classics", true, "vnd.android.document/directory"),
          newSafNode(plsUri, "legacy_mix.pls", false, "audio/x-scpls"),
        ),
        subDirUri to listOf(
          newSafNode(t1Uri, "01_retro.mp3", false, "audio/mpeg"),
          newSafNode(t2Uri, "02_winamp.mp3", false, "audio/mpeg"),
        ),
      ),
      textFilesByDocUri = mutableMapOf(plsUri to plsText),
    )
    val probe = createProbeProxy(
      mapOf(
        t1Uri to newProbedMetadata("Retro Mix", "Legacy", durationMs = 180_000L),
        t2Uri to newProbedMetadata("Winamp Whip", "Legacy", durationMs = 200_000L),
      ),
    )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t4_s7_legacy").use { cache ->
      val prefs = nextPrefsFile(cache.rootDir, "t4_s7")
      buildStorageStack(prefs, safGateway = createSafGatewayProxy(listOf(safSpec)), probe = probe).use { stack ->
        buildMedia3ControllerSession(prefsFile = nextPrefsFile(cache.rootDir, "t4_s7_dsp")).use { dspSession ->
          val ctrl = dspSession.controller
          runBlocking { stack.switchingRepo.linkSafRoot(treeUri) }
          val playlists = runBlocking { stack.switchingRepo.listPlaylists() }
          assertEquals(1, playlists.size)
          val importedQueue = runBlocking { stack.switchingRepo.resolvePlaylistTracks(playlists.first().id) }
          assertEquals(2, importedQueue.size)

          val vm = DroidAmpViewModel(ctrl, stack.switchingRepo)
          mountRack(vm)

          ctrl.replaceQueue(importedQueue, startIndex = 0, autoPlay = true)
          ctrl.setVolume(parsedIni.volume)
          ctrl.setBalance(parsedIni.pan)
          if (parsedIni.shuffle != ctrl.playbackState.value.shuffle) ctrl.toggleShuffle()
          if (parsedIni.repeat) ctrl.cycleRepeatMode()
          if (parsedIni.timeRemaining) ctrl.toggleTimeDisplayMode()
          ctrl.setEqEnabled(parsedIni.eqEnabled)
          ctrl.setEqAutoEnabled(parsedIni.eqAuto)
          ctrl.setDspSettings(
            DspSettings(
              gaplessEnabled = true,
              crossfadeSeconds = parsedIni.crossfadeSeconds,
              replayGainMode = ReplayGainMode.TRACK,
              replayGainPreampDb = parsedIni.replayGainPreampDb,
            ),
          )
          val resolvedAuto = autoEqResolver.resolveForTrack(
            importedQueue[0],
            parsedQ2,
            listOf(parsedEqf) + parsedQ1 + ClassicWinampPresets.ALL,
          )!!
          ctrl.applyEqPreset(resolvedAuto)
          composeRule.waitForIdle()

          composeRule.onNode(hasText("70%")).assertIsDisplayed()
          composeRule.onNode(hasText("R25")).assertIsDisplayed()
          composeRule.onNode(hasText("REP:ALL")).assertIsDisplayed()

          val exported = playlistCodec.encodeM3u8(ctrl.playbackState.value.queue)
          val saved = runBlocking { stack.switchingRepo.saveM3u8Playlist("legacy_migrated.m3u8", exported) }
          assertTrue(saved.isSuccess)
          val reloaded = runBlocking { stack.switchingRepo.resolvePlaylistTracks(saved.getOrThrow().id) }
          assertEquals(importedQueue.map { it.id }, reloaded.map { it.id })
        }
      }
    }
  }

  // =========================================================================
  // Scenario 8: Storage Backend Switch (SAF default vs MediaStore Resilience)
  // =========================================================================

  @Test // T4-S8
  fun scenario08_storageBackendSwitch_safDefaultVsMediaStorePermissionResilience() {
    val permissions = DeviceManifestVerifier.getRequestedManifestPermissions(targetContext)
    assertTrue(
      "Manifest must declare READ_MEDIA_AUDIO",
      Manifest.permission.READ_MEDIA_AUDIO in permissions,
    )
    DeviceManifestVerifier.assertOfflineManifestPermissions(targetContext)

    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ASafMusic"
    val rootDocUri = "$treeUri/document/primary%3ASafMusic"
    val safTrackUri = "$rootDocUri%2Fsaf_only.mp3"
    val safSpec = SafTreeSpec(
      treeUri = treeUri,
      rootDocUri = rootDocUri,
      rootName = "SafMusic",
      childrenByDirUri = mapOf(
        rootDocUri to listOf(newSafNode(safTrackUri, "saf_only.mp3", false, "audio/mpeg")),
      ),
    )

    val msUri = "content://media/external/audio/media/404"
    val msRecord = newMediaStoreAudioRecord(
      404L,
      msUri,
      "device_track.mp3",
      "Device Track",
      "Device Artist",
      "Album",
      190_000L,
      "Music/",
    )
    var msPermissionGranted = false
    val persistedUris = mutableSetOf<String>()

    SyntheticAudioFixtures.createCacheScope(targetContext, "t4_s8_backend").use { cache ->
      val prefsFile = nextPrefsFile(cache.rootDir, "t4_s8")
      buildStorageStack(
        prefsFile = prefsFile,
        safGateway = createSafGatewayProxy(listOf(safSpec), persistedUris),
        mediaStoreGateway = createMediaStoreGatewayProxy(
          hasPermissionProvider = { msPermissionGranted },
          audioRecords = listOf(msRecord),
          throwOnQueryWhenDenied = true,
        ),
        probe = createProbeProxy(
          mapOf(
            safTrackUri to newProbedMetadata("SAF Only", "Saf Artist", durationMs = 160_000L),
            msUri to newProbedMetadata("Device Track", "Device Artist", durationMs = 190_000L),
          ),
        ),
      ).use { stack ->
        runBlocking {
          assertEquals(StorageBackend.SAF, stack.switchingRepo.storageBackend.value)
          stack.switchingRepo.linkSafRoot(treeUri)
          assertEquals(1, stack.switchingRepo.browseFolder(null).tracks.size)

          // Switch to MEDIA_STORE while permission denied -> graceful empty degradation (no crash)
          stack.switchingRepo.setStorageBackend(StorageBackend.MEDIA_STORE)
          assertEquals(FolderContents.EMPTY, stack.switchingRepo.browseFolder(null))
          assertTrue(stack.switchingRepo.listPlaylists().isEmpty())

          // Grant permission -> MediaStore resolves tracks and exports .m3u8
          msPermissionGranted = true
          val msRoot = stack.switchingRepo.browseFolder(null)
          assertEquals("mediastore://folders", msRoot.currentFolderUri)
          assertTrue(msRoot.folders.isNotEmpty() || msRoot.tracks.isNotEmpty())
          val msTracks = stack.switchingRepo.resolveFolderTracks(msRoot.currentFolderUri!!, recursive = true)
          assertEquals(1, msTracks.size)
          assertTrue(stack.switchingRepo.saveM3u8Playlist("ms_saved.m3u8", playlistCodec.encodeM3u8(msTracks)).isSuccess)

          // Switch back to SAF -> original SAF root & tracks restored
          stack.switchingRepo.setStorageBackend(StorageBackend.SAF)
          assertEquals(1, stack.switchingRepo.browseFolder(null).tracks.size)
        }
      }

      // Reopen fresh SwitchingMusicSourceRepository from persisted DataStore
      buildStorageStack(
        prefsFile = prefsFile,
        safGateway = createSafGatewayProxy(listOf(safSpec), persistedUris),
        mediaStoreGateway = createMediaStoreGatewayProxy({ msPermissionGranted }, listOf(msRecord)),
      ).use { stack2 ->
        runBlocking {
          withTimeout(3_000L) {
            while (stack2.switchingRepo.linkedRoots.value.isEmpty()) delay(15L)
          }
          assertEquals(StorageBackend.SAF, stack2.switchingRepo.storageBackend.value)
          assertEquals(1, stack2.switchingRepo.linkedRoots.value.size)
        }
      }
    }
  }

  companion object {
    private val PREFS_COUNTER = AtomicInteger(0)
  }
}

/**
 * Tier 4 MainActivity Device, Onboarding, Offline Security & Ergonomic Geometry Suite
 * (Scenarios 1A, 9, 10).
 */
@RunWith(AndroidJUnit4::class)
class Tier4MainActivityDeviceTest {

  @get:Rule
  val activityRule = createAndroidComposeRule<MainActivity>()

  private fun findClickableAncestor(node: SemanticsNode): SemanticsNode? {
    var cur = node.parent
    while (cur != null) {
      if (cur.config.contains(SemanticsActions.OnClick)) return cur
      cur = cur.parent
    }
    return null
  }

  @Test // T4-S1A
  fun scenario01A_mainActivityColdLaunch_onboardingEmptyWellAndEjectContract() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    activityRule.onNode(hasText("DROIDAMP PLAYLIST ▲")).assertIsDisplayed()
    DroidAmpSelectors.emptyFolderTitlePeeking(activityRule).assertIsDisplayed()
    activityRule.onNode(hasText("TAP TO LINK YOUR MUSIC FOLDER (SAF)")).assertIsDisplayed()

    driver.expandSheetByTitleTap()
    DroidAmpSelectors.emptyFolderTitleExpanded(activityRule).assertIsDisplayed()
    DroidAmpSelectors.selectMusicFolderButton(activityRule).assertIsDisplayed().assertIsEnabled()

    driver.collapseSheetByTitleTap()
    driver.clickEjectOnDeck()
    activityRule.onNode(hasText("DROIDAMP LIBRARY ▼")).assertIsDisplayed()
    DroidAmpSelectors.libraryPrimaryPlayButton(activityRule).assertIsNotEnabled()
    DroidAmpSelectors.libraryPrimaryQueueButton(activityRule).assertIsNotEnabled()
  }

  @Test // T4-S9
  fun scenario09_offlineManifestAndZeroInAppAlbumArtAcrossAllReachableStates() {
    DeviceManifestVerifier.assertOfflineManifestPermissions(activityRule.activity)
    assertTrue(
      "AndroidManifest must declare MediaSessionService",
      DeviceManifestVerifier.hasDeclaredMediaSessionService(activityRule.activity),
    )

    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    // 1. Cold-launch peeking
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)

    // 2. EQ hidden & restored
    driver.toggleEqVisibility()
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)
    driver.toggleEqVisibility()

    // 3. EQ PRESETS popup open & dismissed
    DroidAmpSelectors.eqPresetsButton(activityRule).performClick()
    activityRule.waitForIdle()
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)
    Espresso.pressBack()
    activityRule.waitForIdle()

    // 4. Expanded PLAYLIST, LIBRARY -> FOLDERS, LIBRARY -> PLAYLISTS
    driver.ensureSheetExpanded(SheetMode.PLAYLIST)
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)

    driver.ensureSheetExpanded(SheetMode.LIBRARY)
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)

    DroidAmpSelectors.libraryPlaylistsTab(activityRule).performClick()
    activityRule.waitForIdle()
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)

    // 5. Embedded APIC MP3 -> AudioTrack reflection audit (excluding artist)
    val artTokens = setOf("art", "artwork", "albumart", "embeddedart", "arturi", "artbytes", "bitmap", "cover", "thumb", "picture")
    val badFields = AudioTrack::class.java.declaredFields.filter { f ->
      val n = f.name.lowercase(Locale.ROOT)
      val t = f.type.name.lowercase(Locale.ROOT)
      (n in artTokens || "artwork" in n || "albumart" in n || "cover" in n || "bitmap" in n) ||
        ("bitmap" in t || "drawable" in t || "image" in t)
    }
    assertTrue("AudioTrack must never carry artwork fields: $badFields", badFields.isEmpty())
  }

  @Test // T4-S10
  fun scenario10_mainActivityErgonomicGeometry_preampGapSwipeBufferAndNavBarClearance() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    // PREAMP >= 18dp gap & vertical +12/0DB/-12 scale
    DeviceManifestVerifier.assertPreampGapIsolation(activityRule)

    // Swipe buffer & nav-bar clearance with EQ visible and EQ hidden
    DeviceManifestVerifier.assertPeekingSheetSwipeBuffer(activityRule)
    DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(activityRule)

    driver.toggleEqVisibility()
    DeviceManifestVerifier.assertPeekingSheetSwipeBuffer(activityRule)
    DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(activityRule)
    driver.toggleEqVisibility()

    // Non-interactive swipe buffer tap must NOT expand sheet
    val peekingTitle =
      activityRule
        .onNode(hasText("DROIDAMP PLAYLIST ▲") or hasText("DROIDAMP LIBRARY ▲"), useUnmergedTree = true)
        .assertIsDisplayed()
        .fetchSemanticsNode()
    val handleNode = findClickableAncestor(peekingTitle) ?: peekingTitle
    val rootBounds = activityRule.onRoot().fetchSemanticsNode().boundsInRoot
    val bufferCenterY = (handleNode.boundsInRoot.top - with(activityRule.density) { 6.dp.toPx() }).coerceAtLeast(1f)
    activityRule.onRoot().performTouchInput { click(Offset(rootBounds.center.x, bufferCenterY)) }
    activityRule.waitForIdle()
    DroidAmpSelectors.peekingSheetTitleBar(activityRule).assertIsDisplayed()
    assertTrue(
      activityRule
        .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
        .fetchSemanticsNodes()
        .isEmpty(),
    )

    // Non-interactive bottom 8dp lip tap must NOT expand sheet
    val lipCenterY = (rootBounds.bottom - with(activityRule.density) { 4.dp.toPx() }).coerceAtLeast(1f)
    activityRule.onRoot().performTouchInput { click(Offset(rootBounds.center.x, lipCenterY)) }
    activityRule.waitForIdle()
    DroidAmpSelectors.peekingSheetTitleBar(activityRule).assertIsDisplayed()
    assertTrue(
      activityRule
        .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
        .fetchSemanticsNodes()
        .isEmpty(),
    )

    // Swipe up expands, expanded PLAYLIST & LIBRARY clear navigation bar, swipe down collapses cleanly
    driver.expandSheetBySwipeUp()
    DroidAmpSelectors.expandedSheetTitleBar(activityRule).assertIsDisplayed()
    DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(activityRule)

    driver.ensureSheetExpanded(SheetMode.LIBRARY)
    DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(activityRule)

    driver.collapseSheetBySwipeDown()
    DroidAmpSelectors.peekingSheetTitleBar(activityRule).assertIsDisplayed()
    assertTrue(
      activityRule
        .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
        .fetchSemanticsNodes()
        .isEmpty(),
    )
  }
}
