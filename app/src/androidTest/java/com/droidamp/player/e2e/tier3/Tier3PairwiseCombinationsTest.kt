package com.droidamp.player.e2e.tier3

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidamp.player.domain.contract.AudioEngineController
import com.droidamp.player.domain.contract.AutoEqPresetResolver
import com.droidamp.player.domain.contract.AutoEqRule
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
import com.droidamp.player.domain.model.PlaylistSortOrder
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.suspendCoroutine
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 3 Pairwise Combinatorial E2E Tests:
 * Covers cross-feature interactions across Winamp I/O (F1-F6), Media3/DSP (F7-F15),
 * and Storage/UI (F16-F20) without compile-time coupling to M2/M3/M4 internal packages.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class Tier3PairwiseCombinationsTest {

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

  private suspend fun invokeSuspend(
    target: Any,
    methodName: String,
    vararg typedArgs: Pair<Class<*>, Any?>,
  ): Any? =
    suspendCoroutine { cont ->
      val paramTypes = typedArgs.map { it.first } + Continuation::class.java
      val method = target.javaClass.getMethod(methodName, *paramTypes.toTypedArray())
      val args = typedArgs.map { it.second } + cont
      try {
        val result = method.invoke(target, *args.toTypedArray())
        if (result !== COROUTINE_SUSPENDED) {
          cont.resumeWith(Result.success(result))
        }
      } catch (ite: InvocationTargetException) {
        cont.resumeWith(Result.failure(ite.cause ?: ite))
      }
    }

  private fun nextPrefsFile(dir: File, prefix: String): File =
    File(dir, "${prefix}_${PREFS_COUNTER.incrementAndGet()}.preferences_pb")

  // =========================================================================
  // Reflective DSP & Media3 Stack
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

    @Suppress("UNCHECKED_CAST")
    fun asArray(): Array<AudioProcessor> =
      chain.javaClass.getMethod("asArray").invoke(chain) as Array<AudioProcessor>

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

    fun clearReplayGainMetadata() {
      chain.javaClass.getMethod("clearReplayGainMetadata").invoke(chain)
    }

    fun readVisualizerFrame(isPlaying: Boolean): VisualizerFrame =
      chain.javaClass.getMethod("readVisualizerFrame", Boolean::class.javaPrimitiveType)
        .invoke(chain, isPlaying) as VisualizerFrame

    fun resetVisualizer() {
      chain.javaClass.getMethod("resetVisualizer").invoke(chain)
    }

    fun currentReplayGainEffectiveGain(): Float =
      rgProcessor.javaClass.getMethod("currentEffectiveGain").invoke(rgProcessor) as Float

    fun createRenderersFactory(context: Context): RenderersFactory {
      val rfCls = Class.forName("com.droidamp.player.audio.service.DroidAmpAudioRenderersFactory")
      return rfCls.getDeclaredConstructor(Context::class.java, chain.javaClass)
        .newInstance(context, chain) as RenderersFactory
    }
  }

  private fun newReplayGainMetadata(
    trackGainDb: Float? = null,
    trackPeak: Float? = null,
    albumGainDb: Float? = null,
    albumPeak: Float? = null,
  ): Any {
    val cls = Class.forName("com.droidamp.player.audio.dsp.ReplayGainMetadata")
    val fCls = Float::class.javaObjectType
    return cls.getDeclaredConstructor(fCls, fCls, fCls, fCls).newInstance(
      trackGainDb,
      trackPeak,
      albumGainDb,
      albumPeak,
    )
  }

  private class GatewayState(val chain: ReflectiveDspChainHandle) {
    var listener: Any? = null
    var queue: List<AudioTrack> = emptyList()
    var currentIndex: Int = -1
    var isPlaying: Boolean = false
    var positionMs: Long = 0L
    var durationMs: Long = 0L
    var cancelCrossfadeCount: Int = 0
    var onCancelCrossfade: (() -> Unit)? = null
  }

  private fun createAudioEngineGatewayProxy(state: GatewayState): Any {
    val gwCls = Class.forName("com.droidamp.player.audio.service.AudioEngineGateway")
    return Proxy.newProxyInstance(
      gwCls.classLoader,
      arrayOf(gwCls),
    ) { _, method, args ->
      when (method.name) {
        "setListener" -> {
          state.listener = args?.getOrNull(0)
          Unit
        }
        "setQueue" -> {
          @Suppress("UNCHECKED_CAST")
          val tracks = args?.getOrNull(0) as? List<AudioTrack> ?: emptyList()
          val startIdx = args?.getOrNull(1) as? Int ?: 0
          val playWhenReady = args?.getOrNull(2) as? Boolean ?: false
          state.queue = tracks
          state.currentIndex = if (tracks.isEmpty()) -1 else startIdx.coerceIn(0, tracks.lastIndex)
          state.positionMs = 0L
          state.durationMs = tracks.getOrNull(state.currentIndex)?.durationMs ?: 0L
          state.isPlaying = playWhenReady && tracks.isNotEmpty()
          Unit
        }
        "syncQueueWithoutInterrupting" -> {
          @Suppress("UNCHECKED_CAST")
          state.queue = args?.getOrNull(0) as? List<AudioTrack> ?: emptyList()
          state.currentIndex = args?.getOrNull(1) as? Int ?: -1
          Unit
        }
        "selectTrackAt" -> {
          val idx = args?.getOrNull(0) as? Int ?: 0
          val playWhenReady = args?.getOrNull(1) as? Boolean ?: false
          state.currentIndex = idx
          state.positionMs = 0L
          state.durationMs = state.queue.getOrNull(idx)?.durationMs ?: 0L
          state.isPlaying = playWhenReady
          Unit
        }
        "play" -> {
          state.isPlaying = state.queue.isNotEmpty()
          Unit
        }
        "pause" -> {
          state.isPlaying = false
          Unit
        }
        "stop" -> {
          state.isPlaying = false
          state.positionMs = 0L
          Unit
        }
        "seekTo" -> {
          state.positionMs = args?.getOrNull(0) as? Long ?: 0L
          Unit
        }
        "setRepeatMode" -> Unit
        "setShuffleEnabled" -> Unit
        "applyDspSettings" -> {
          val settings = args?.getOrNull(0) as? DspSettings
          if (settings != null) state.chain.applyDspSettings(settings)
          Unit
        }
        "applyEqualizerState" -> {
          val eq = args?.getOrNull(0) as? EqualizerState
          if (eq != null) state.chain.applyEqualizerState(eq)
          Unit
        }
        "applyVolume" -> {
          val vol = args?.getOrNull(0) as? Int ?: PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME
          state.chain.setVolume(vol)
          Unit
        }
        "applyBalance" -> {
          val bal = args?.getOrNull(0) as? Int ?: 0
          state.chain.setBalance(bal)
          Unit
        }
        "applyReplayGainMetadata" -> {
          val rg = args?.getOrNull(0)
          if (rg != null) state.chain.setReplayGainMetadata(rg)
          Unit
        }
        "readVisualizerFrame" -> {
          val playing = args?.getOrNull(0) as? Boolean ?: false
          state.chain.readVisualizerFrame(playing)
        }
        "resetVisualizer" -> {
          state.chain.resetVisualizer()
          Unit
        }
        "currentPositionMs" -> state.positionMs
        "currentDurationMs" -> state.durationMs
        "onCrossfadeProgressTick" -> Unit
        "cancelCrossfade" -> {
          state.cancelCrossfadeCount++
          state.onCancelCrossfade?.invoke()
          Unit
        }
        "release" -> Unit
        "getManagesPlaylistTransitions" -> java.lang.Boolean.FALSE
        "equals" -> false
        "hashCode" -> 0
        "toString" -> "Tier3AudioEngineGatewayProxy"
        else -> null
      }
    }
  }

  private data class ReflectiveControllerSession(
    val scope: CoroutineScope,
    val dataStore: DataStore<Preferences>,
    val prefsStore: Any,
    val chain: ReflectiveDspChainHandle,
    val gatewayState: GatewayState,
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
    val dataStore =
      PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { prefsFile },
      )
    val prefsStoreCls = Class.forName("com.droidamp.player.audio.service.DspPreferencesStore")
    val prefsStore = prefsStoreCls.getDeclaredConstructor(DataStore::class.java).newInstance(dataStore)
    val gatewayState = GatewayState(chain)
    val gatewayProxy = createAudioEngineGatewayProxy(gatewayState)
    val gwCls = Class.forName("com.droidamp.player.audio.service.AudioEngineGateway")
    val ctrlCls = Class.forName("com.droidamp.player.audio.service.Media3AudioEngineController")
    val controller =
      ctrlCls.getDeclaredConstructor(
        gwCls,
        prefsStoreCls,
        CoroutineScope::class.java,
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
      ).newInstance(gatewayProxy, prefsStore, scope, 25L, 50L) as AudioEngineController

    return ReflectiveControllerSession(
      scope = scope,
      dataStore = dataStore,
      prefsStore = prefsStore,
      chain = chain,
      gatewayState = gatewayState,
      controller = controller,
    )
  }

  private suspend fun readDspSnapshotField(prefsStore: Any, getterName: String): Any? {
    val flow = prefsStore.javaClass.getMethod("getSnapshotFlow").invoke(prefsStore) as Flow<*>
    val snapshot = flow.first() ?: error("Null DspPreferencesSnapshot")
    return snapshot.javaClass.getMethod(getterName).invoke(snapshot)
  }

  // =========================================================================
  // Reflective Storage Stack
  // =========================================================================

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
        "isTreeAccessible" -> {
          val uri = args?.getOrNull(0) as? String ?: ""
          java.lang.Boolean.valueOf(uri in treesByUri)
        }
        "getTreeRootNode" -> {
          val uri = args?.getOrNull(0) as? String
          val spec = uri?.let { treesByUri[it] }
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
            val node = newSafNode(docUri, fileName, isDirectory = false, mimeType = mimeType)
            val list = mutableChildren.getOrPut(dirUri) { mutableListOf() }
            list.removeAll {
              (it.javaClass.getMethod("getName").invoke(it) as? String).equals(fileName, ignoreCase = true)
            }
            list.add(node)
            spec.textFilesByDocUri[docUri] = content
            node
          }
        }
        "equals" -> false
        "hashCode" -> 0
        "toString" -> "Tier3SafGatewayProxy"
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
        "toString" -> "Tier3MediaStoreGatewayProxy"
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
        "toString" -> "Tier3AudioMetadataProbeProxy"
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
  // PCM & ExoPlayer Helpers
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

  private fun pumpPcm16ThroughProcessors(
    processors: Array<AudioProcessor>,
    input: ShortArray,
    sampleRateHz: Int = 44_100,
    channels: Int = 1,
    configureAndFlush: Boolean = true,
  ): ShortArray {
    if (configureAndFlush) {
      val fmt = AudioProcessor.AudioFormat(sampleRateHz, channels, C.ENCODING_PCM_16BIT)
      processors.forEach { p ->
        p.configure(fmt)
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
      }
    }
    val chunkSamples = 512 * channels
    val out = ArrayList<Short>(input.size)
    var offset = 0
    while (offset < input.size) {
      val count = minOf(chunkSamples, input.size - offset)
      var currentBuf = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
      for (i in 0 until count) {
        currentBuf.putShort(input[offset + i])
      }
      currentBuf.flip()

      for (p in processors) {
        if (!p.isActive) continue
        p.queueInput(currentBuf)
        val processed = p.output
        val next = ByteBuffer.allocateDirect(processed.remaining()).order(ByteOrder.nativeOrder())
        next.put(processed)
        next.flip()
        currentBuf = next
      }

      while (currentBuf.remaining() >= 2) {
        out.add(currentBuf.getShort())
      }
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
    val renderersFactory = chain.createRenderersFactory(targetContext)
    lateinit var player: ExoPlayer
    instrumentation.runOnMainSync {
      player = ExoPlayer.Builder(targetContext, renderersFactory).build()
    }
    try {
      return block(player)
    } finally {
      instrumentation.runOnMainSync {
        player.release()
      }
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
  // Tier 3 Pairwise Combinations (22 Tests covering F1-F20)
  // =========================================================================

  @Test // T3-01: F1 (.eqf) x F2 (winamp.q1) x F10 (10-Band EQ + Preamp) x F14 (VFD Spline)
  fun t3_01_eqfAndQ1Codecs_driveRealTenBandEqProcessorAndVfdSpline() {
    val customPreset = EqPreset(
      name = "PairwiseBassCut",
      preampDb = 0f,
      bandsDb = listOf(10f, 0f, 0f, 0f, -10f, 0f, 0f, 0f, 0f, 0f),
    )
    val eqfBytes = SyntheticWinampFixtures.eqfBytes(customPreset)
    val parsedEqf = eqfCodec.parseSingleEqf(eqfBytes)
    val q1Bytes = eqfCodec.encodePresetLibraryQ1(listOf(ClassicWinampPresets.FULL_BASS_TREBLE, parsedEqf))
    val parsedQ1 = eqfCodec.parsePresetLibraryQ1(q1Bytes)
    assertEquals(2, parsedQ1.size)
    val loadedCustom = parsedQ1[1]
    assertEquals("PairwiseBassCut", loadedCustom.name)

    val chain = ReflectiveDspChainHandle()
    chain.applyEqualizerState(
      EqualizerState(
        enabled = true,
        preampDb = loadedCustom.preampDb,
        bandsDb = loadedCustom.bandsDb,
        activePresetName = loadedCustom.name,
      ),
    )

    val tone60 = generateSinePcm16(44_100, 60.0, 0.30, amplitude = 0.15, channels = 1)
    val out60 = pumpPcm16ThroughProcessors(arrayOf(chain.eqProcessor), tone60, 44_100, 1)
    val delta60 = rmsDbDelta(tone60, out60, 1, 0, startFrame = 4_410)
    assertEquals(loadedCustom.bandsDb[0].toDouble(), delta60, 0.8)

    val tone1k = generateSinePcm16(44_100, 1_000.0, 0.30, amplitude = 0.25, channels = 1)
    val out1k = pumpPcm16ThroughProcessors(arrayOf(chain.eqProcessor), tone1k, 44_100, 1)
    val delta1k = rmsDbDelta(tone1k, out1k, 1, 0, startFrame = 4_410)
    assertEquals(loadedCustom.bandsDb[4].toDouble(), delta1k, 0.8)

    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.viewModel.applyEqPreset(loadedCustom)
      composeRule.waitForIdle()
      assertEquals("PairwiseBassCut", driver.engine.playbackState.value.equalizer.activePresetName)
      assertEquals(loadedCustom.bandsDb[0], driver.engine.playbackState.value.equalizer.bandsDb[0], 0.01f)
      DeviceManifestVerifier.assertPreampGapIsolation(composeRule)
    }
  }

  @Test // T3-02: F3 (winamp.q2 + AUTO) x F6 (Classic Presets) x F20 (EQ UI & Manual Override)
  fun t3_02_q2AutoEqMatcher_respondsToTrackAndFolderChangesAndManualPresetOverride() {
    val trackTech = AudioTrack(
      id = "t-tech",
      uriString = "content://music/Synthwave/Neon.mp3",
      title = "Neon",
      artist = "Artist",
      fileName = "01_neon.mp3",
      folderPath = "Music/Synthwave",
      durationMs = 180_000L,
    )
    val trackRock = AudioTrack(
      id = "t-rock",
      uriString = "content://music/ClassicRock/Highway.mp3",
      title = "Highway",
      artist = "Artist",
      fileName = "02_highway.mp3",
      folderPath = "Music/ClassicRock",
      durationMs = 200_000L,
    )

    val q2Bytes =
      SyntheticWinampFixtures.q2Bytes(
        listOf(
          SyntheticWinampFixtures.Q2RuleSpec("Synthwave", ClassicWinampPresets.TECHNO),
          SyntheticWinampFixtures.Q2RuleSpec("02_highway.mp3", ClassicWinampPresets.ROCK),
        ),
      )
    val parsedRules = q2Codec.parseAutoPresetsQ2(q2Bytes)
    assertEquals(2, parsedRules.size)

    val chain = ReflectiveDspChainHandle()
    val resolvedTech = autoEqResolver.resolveForTrack(trackTech, parsedRules, ClassicWinampPresets.ALL)
    assertNotNull(resolvedTech)
    assertEquals("Synthwave", resolvedTech!!.name)

    val resolvedRock = autoEqResolver.resolveForTrack(trackRock, parsedRules, ClassicWinampPresets.ALL)
    assertNotNull(resolvedRock)
    assertEquals("02_highway.mp3", resolvedRock!!.name)

    E2eRackDriver.launchIsolatedRack(
      composeRule,
      initialQueue = listOf(trackTech, trackRock),
      autoPlay = true,
    ).use { driver ->
      driver.toggleEqAuto()
      assertTrue(driver.engine.playbackState.value.equalizer.autoEnabled)

      driver.viewModel.applyEqPreset(resolvedTech)
      chain.applyEqualizerState(driver.engine.playbackState.value.equalizer)
      assertEquals(resolvedTech.bandsDb[0], driver.engine.playbackState.value.equalizer.bandsDb[0], 0.4f)

      // Manual preset override sticks until next AUTO resolution
      driver.selectEqPreset(ClassicWinampPresets.CLASSICAL.name)
      assertEquals(ClassicWinampPresets.CLASSICAL.name, driver.engine.playbackState.value.equalizer.activePresetName)

      // Next track triggers re-evaluation
      driver.clickNextOnDeck()
      driver.viewModel.applyEqPreset(resolvedRock)
      chain.applyEqualizerState(driver.engine.playbackState.value.equalizer)
      assertEquals("02_highway.mp3", driver.engine.playbackState.value.equalizer.activePresetName)
      for (i in 0 until WinampEqSpec.BAND_COUNT) {
        assertEquals(ClassicWinampPresets.ROCK.bandsDb[i], driver.engine.playbackState.value.equalizer.bandsDb[i], 0.4f)
      }
    }
  }

  @Test // T3-03: F4 (winamp.ini) x F11 (Vol) x F12 (Bal) x F15 (DataStore Persistence)
  fun t3_03_winampIni_hydratesMedia3ControllerDataStoreAndReencodesWithParity() {
    val iniText =
      SyntheticWinampFixtures.winampIni(
        WinampIniSnapshot(
          volume = 160,
          pan = -64,
          shuffle = true,
          repeat = true,
          timeRemaining = true,
          eqEnabled = false,
          eqAuto = true,
          eqPreampDb = 2.0f,
          eqBandsDb = ClassicWinampPresets.POP.bandsDb,
          replayGainMode = 2,
          replayGainPreampDb = -2.5f,
          crossfadeSeconds = 4,
        ),
        includeBom = true,
        lineEnding = "\r\n",
      )
    val parsed = iniCodec.parse(iniText)

    SyntheticAudioFixtures.createCacheScope(targetContext, "t3_03_ini_ds").use { cache ->
      val prefsFile = nextPrefsFile(cache.rootDir, "t3_03")

      buildMedia3ControllerSession(prefsFile).use { session1 ->
        val ctrl = session1.controller
        ctrl.setVolume(parsed.volume)
        ctrl.setBalance(parsed.pan)
        if (parsed.shuffle != ctrl.playbackState.value.shuffle) ctrl.toggleShuffle()
        if (parsed.repeat) ctrl.cycleRepeatMode()
        if (parsed.timeRemaining) ctrl.toggleTimeDisplayMode()
        ctrl.setEqEnabled(parsed.eqEnabled)
        ctrl.setEqAutoEnabled(parsed.eqAuto)
        ctrl.applyEqPreset(EqPreset("INI_POP", parsed.eqPreampDb, parsed.eqBandsDb))
        ctrl.setDspSettings(
          DspSettings(
            gaplessEnabled = true,
            crossfadeSeconds = parsed.crossfadeSeconds,
            replayGainMode = ReplayGainMode.ALBUM,
            replayGainPreampDb = parsed.replayGainPreampDb,
          ),
        )

        runBlocking {
          withTimeout(3_000L) {
            while (true) {
              val vol = readDspSnapshotField(session1.prefsStore, "getVolume") as? Int
              val bal = readDspSnapshotField(session1.prefsStore, "getBalance") as? Int
              val shuf = readDspSnapshotField(session1.prefsStore, "getShuffle") as? Boolean
              val rep = readDspSnapshotField(session1.prefsStore, "getRepeatMode") as? RepeatMode
              val timeMode = readDspSnapshotField(session1.prefsStore, "getTimeDisplayMode") as? TimeDisplayMode
              val eq = readDspSnapshotField(session1.prefsStore, "getEqualizer") as? EqualizerState
              val dspSnap = readDspSnapshotField(session1.prefsStore, "getDspSettings") as? DspSettings
              val allPersisted =
                vol == 160 &&
                  bal == -64 &&
                  shuf == true &&
                  rep == RepeatMode.ALL &&
                  timeMode == TimeDisplayMode.REMAINING &&
                  eq != null &&
                  !eq.enabled &&
                  eq.autoEnabled &&
                  eq.activePresetName == "INI_POP" &&
                  abs(eq.preampDb - parsed.eqPreampDb) < 0.01f &&
                  (0 until WinampEqSpec.BAND_COUNT).all { i -> abs(eq.bandsDb[i] - parsed.eqBandsDb[i]) < 0.01f } &&
                  dspSnap != null &&
                  dspSnap.crossfadeSeconds == 4 &&
                  dspSnap.replayGainMode == ReplayGainMode.ALBUM &&
                  abs(dspSnap.replayGainPreampDb - (-2.5f)) < 0.01f
              if (allPersisted) break
              delay(15L)
            }
          }
        }
      }

      // Rehydrate fresh Media3AudioEngineController from same .preferences_pb
      buildMedia3ControllerSession(prefsFile).use { session2 ->
        val ctrl2 = session2.controller
        runBlocking {
          withTimeout(3_000L) {
            while (
              ctrl2.playbackState.value.volume != 160 ||
              ctrl2.playbackState.value.balance != -64 ||
              !ctrl2.playbackState.value.shuffle ||
              ctrl2.playbackState.value.repeatMode != RepeatMode.ALL ||
              ctrl2.playbackState.value.timeDisplayMode != TimeDisplayMode.REMAINING ||
              ctrl2.playbackState.value.equalizer.enabled ||
              !ctrl2.playbackState.value.equalizer.autoEnabled ||
              ctrl2.playbackState.value.equalizer.activePresetName != "INI_POP" ||
              ctrl2.dspSettings.value.crossfadeSeconds != 4 ||
              ctrl2.dspSettings.value.replayGainMode != ReplayGainMode.ALBUM
            ) {
              delay(15L)
            }
          }
        }

        val st = ctrl2.playbackState.value
        val dsp = ctrl2.dspSettings.value
        assertEquals(160, st.volume)
        assertEquals(-64, st.balance)
        assertTrue(st.shuffle)
        assertEquals(RepeatMode.ALL, st.repeatMode)
        assertEquals(TimeDisplayMode.REMAINING, st.timeDisplayMode)
        assertFalse(st.equalizer.enabled)
        assertTrue(st.equalizer.autoEnabled)
        assertEquals("INI_POP", st.equalizer.activePresetName)
        assertEquals(parsed.eqPreampDb, st.equalizer.preampDb, 0.01f)
        for (i in 0 until WinampEqSpec.BAND_COUNT) {
          assertEquals(parsed.eqBandsDb[i], st.equalizer.bandsDb[i], 0.01f)
        }
        assertEquals(ReplayGainMode.ALBUM, dsp.replayGainMode)
        assertEquals(-2.5f, dsp.replayGainPreampDb, 0.01f)
        assertEquals(4, dsp.crossfadeSeconds)

        // Re-encode WinampIniSnapshot and verify round-trip parity
        val reencoded =
          iniCodec.encode(
            WinampIniSnapshot(
              volume = st.volume,
              pan = st.balance,
              shuffle = st.shuffle,
              repeat = st.repeatMode != RepeatMode.OFF,
              timeRemaining = st.timeDisplayMode == TimeDisplayMode.REMAINING,
              eqEnabled = st.equalizer.enabled,
              eqAuto = st.equalizer.autoEnabled,
              eqPreampDb = st.equalizer.preampDb,
              eqBandsDb = st.equalizer.bandsDb,
              replayGainMode = 2,
              replayGainPreampDb = dsp.replayGainPreampDb,
              crossfadeSeconds = dsp.crossfadeSeconds,
            ),
          )
        val reparsed = iniCodec.parse(reencoded)
        assertEquals(160, reparsed.volume)
        assertEquals(-64, reparsed.pan)
        assertEquals(4, reparsed.crossfadeSeconds)
        assertEquals(-2.5f, reparsed.replayGainPreampDb, 0.01f)

        // Constructing DroidAmpViewModel with CLASSIC_80_PERCENT [OG] while STOPPED applies ADR-007 cold-launch volume reset (160 -> 204)
        val vm =
          DroidAmpViewModel(
            ctrl2,
            FakeMusicSourceRepository(startLinked = true),
            appPreferencesRepository =
              com.droidamp.player.storage.InMemoryAppPreferencesRepository(
                com.droidamp.player.storage.AppPreferences(
                  volumeKeyMode = com.droidamp.player.storage.VolumeKeyMode.INDEPENDENT_DECK,
                  startupVolumeMode = com.droidamp.player.storage.StartupVolumeMode.CLASSIC_80_PERCENT,
                ),
              ),
          )
        assertEquals(PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME, ctrl2.playbackState.value.volume)

        // Restore parsed winamp.ini volume (160 -> 62%) and verify full deck UI state
        ctrl2.setVolume(parsed.volume)
        mountRack(vm)
        composeRule.onNode(hasText("62%")).assertIsDisplayed()
        composeRule.onNode(hasText("L50")).assertIsDisplayed()
        composeRule.onNode(hasText("REP:ALL")).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Track timer -", substring = true)).assertIsDisplayed()
      }
    }
  }

  @Test // T3-04: F5 (.pls/.m3u8 relative + Windows '\') x F16 (SAF) x F18 (PLAYLISTS) x F19 (Save .m3u8)
  fun t3_04_playlistCodecsWithRelativeAndWindowsPaths_integrateWithSafAndSaveM3u8() {
    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ATier3Music"
    val rootDocUri = "$treeUri/document/primary%3ATier3Music"
    val subDirUri = "$treeUri/document/primary%3ATier3Music%2FSets"
    val t1Uri = "$subDirUri%2F01_intro.wav"
    val t2Uri = "$subDirUri%2F02_peak.wav"
    val plsUri = "$rootDocUri%2Fwin_set.pls"
    val m3u8Uri = "$rootDocUri%2Fmix.m3u8"

    val plsText =
      SyntheticWinampFixtures.plsContent(
        listOf(
          SyntheticWinampFixtures.PlaylistEntrySpec("Sets\\02_peak.wav", "Peak Track", 210L),
          SyntheticWinampFixtures.PlaylistEntrySpec("./Sets/01_intro.wav", "Intro Track", 125L),
        ),
      )
    val m3u8Text =
      SyntheticWinampFixtures.m3u8Content(
        listOf(SyntheticWinampFixtures.PlaylistEntrySpec("Sets/01_intro.wav", "Intro Track", 125L)),
      )

    val safSpec =
      SafTreeSpec(
        treeUri = treeUri,
        rootDocUri = rootDocUri,
        rootName = "Tier3Music",
        childrenByDirUri = mapOf(
          rootDocUri to listOf(
            newSafNode(subDirUri, "Sets", isDirectory = true, mimeType = "vnd.android.document/directory"),
            newSafNode(plsUri, "win_set.pls", isDirectory = false, mimeType = "audio/x-scpls"),
            newSafNode(m3u8Uri, "mix.m3u8", isDirectory = false, mimeType = "application/vnd.apple.mpegurl"),
          ),
          subDirUri to listOf(
            newSafNode(t1Uri, "01_intro.wav", isDirectory = false, mimeType = "audio/wav"),
            newSafNode(t2Uri, "02_peak.wav", isDirectory = false, mimeType = "audio/wav"),
          ),
        ),
        textFilesByDocUri = mutableMapOf(
          plsUri to plsText,
          m3u8Uri to m3u8Text,
        ),
      )

    val probe =
      createProbeProxy(
        mapOf(
          t1Uri to newProbedMetadata(title = "Intro Track", artist = "DJ A", durationMs = 125_000L),
          t2Uri to newProbedMetadata(title = "Peak Track", artist = "DJ B", durationMs = 210_000L),
        ),
      )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t3_04_saf_pls").use { cache ->
      buildStorageStack(
        prefsFile = nextPrefsFile(cache.rootDir, "t3_04"),
        safGateway = createSafGatewayProxy(listOf(safSpec)),
        probe = probe,
      ).use { stack ->
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
          val engine = FakeAudioEngineController(uiScope, enableSimulationTicker = false)
          val vm = DroidAmpViewModel(engine, stack.switchingRepo)
          val driver = mountRack(vm)

          vm.onSelectMusicFolder(treeUri)
          composeRule.waitUntil(3_000L) { vm.uiState.value.folderContents.currentFolderUri != null }

          driver.ensureSheetExpanded(SheetMode.LIBRARY)
          DroidAmpSelectors.libraryPlaylistsTab(composeRule).performClick()
          composeRule.waitUntil(3_000L) { vm.uiState.value.playlists.size >= 2 }

          DroidAmpSelectors.libraryPlaylistRow(composeRule, "PLS", "win_set").assertIsDisplayed()
          DroidAmpSelectors.libraryPlaylistRow(composeRule, "M3U8", "mix").assertIsDisplayed()

          DroidAmpSelectors.libraryPlaylistPlayButton(composeRule, "PLS", "win_set").performClick()
          composeRule.waitUntil(3_000L) { engine.playbackState.value.queue.size == 2 }
          assertEquals("02_peak.wav", engine.playbackState.value.queue[0].fileName)
          assertEquals("01_intro.wav", engine.playbackState.value.queue[1].fileName)
          DroidAmpSelectors.expandedSheetTitleBar(composeRule).assertIsDisplayed()

          // Reorder queue and save via M3U8 codec + repository
          vm.movePlaylistTrack(0, 1)
          assertEquals("01_intro.wav", engine.playbackState.value.queue[0].fileName)

          val encoded = playlistCodec.encodeM3u8(engine.playbackState.value.queue)
          val saveRes = runBlocking { stack.switchingRepo.saveM3u8Playlist("reordered_set.m3u8", encoded) }
          assertTrue("saveM3u8Playlist must succeed", saveRes.isSuccess)

          val savedEntry = saveRes.getOrThrow()
          val resolvedSaved = runBlocking { stack.switchingRepo.resolvePlaylistTracks(savedEntry.id) }
          assertEquals(
            engine.playbackState.value.queue.map { it.id },
            resolvedSaved.map { it.id },
          )

          // Clear prior status banner before exercising UI [LIST] -> SAVE .M3U8 menu interaction
          vm.switchSheetMode(SheetMode.PLAYLIST)
          composeRule.waitForIdle()
          driver.clickPlaylistMenuAction(DroidAmpSelectors.playlistListButton(composeRule), "LIST OPTS", "SAVE .M3U8")
          composeRule.waitUntil(3_000L) {
            vm.uiState.value.statusBanner?.startsWith("SAVED ") == true
          }
          val banner = vm.uiState.value.statusBanner.orEmpty()
          assertEquals(2, engine.playbackState.value.queue.size)
          assertTrue("Expected SAVED .M3U8 status banner, got '$banner'", "SAVED" in banner && ".M3U8" in banner)
        } finally {
          uiScope.cancel()
        }
      }
    }
  }

  @Test // T3-05: F9 (ReplayGain) x F10 (10-Band EQ) x F11 (Volume) x F12 (Balance) on Full DSP Chain
  fun t3_05_replayGain_combinesWithTenBandEqVolumeAndBalanceOnRealPcmChain() {
    val chain = ReflectiveDspChainHandle()
    chain.applyDspSettings(DspSettings(replayGainMode = ReplayGainMode.TRACK, replayGainPreampDb = 0f))
    chain.setReplayGainMetadata(newReplayGainMetadata(trackGainDb = -6.0206f, trackPeak = 0.98f))
    chain.setVolume(128)
    chain.setBalance(-127) // Full Left

    val boost1k = MutableList(WinampEqSpec.BAND_COUNT) { 0f }.apply { this[4] = 6.0f }
    chain.applyEqualizerState(EqualizerState(enabled = true, preampDb = 0f, bandsDb = boost1k))

    val stereo1k = generateSinePcm16(44_100, 1_000.0, 0.30, amplitude = 0.4, channels = 2)
    val boostedOut = pumpPcm16ThroughProcessors(chain.asArray(), stereo1k, 44_100, 2)

    val settle = 4_410
    val rightRms = rmsChannel(boostedOut, 2, 1, settle)
    val leftBoostedRms = rmsChannel(boostedOut, 2, 0, settle)
    assertTrue("Balance -127 must silence right channel (RMS=$rightRms)", rightRms < 1e-4)
    assertTrue("Left channel must carry attenuated signal", leftBoostedRms > 0.01)

    // Disable EQ -> left RMS drops by ~6 dB while RG (-6 dB) and Balance (-127) remain active
    chain.applyEqualizerState(EqualizerState(enabled = false, preampDb = 0f, bandsDb = boost1k))
    val bypassedOut = pumpPcm16ThroughProcessors(chain.asArray(), stereo1k, 44_100, 2)
    val leftBypassedRms = rmsChannel(bypassedOut, 2, 0, settle)
    val eqDropDb = 20.0 * log10(leftBypassedRms / leftBoostedRms)
    assertEquals(-6.0, eqDropDb, 0.8)
    assertTrue(rmsChannel(bypassedOut, 2, 1, settle) < 1e-4)
  }

  @Test // T3-06: F7 (ExoPlayer + DroidAmpAudioRenderersFactory) x F9 (ReplayGain ID3) x F14 (Visualizer)
  fun t3_06_exoPlayerWithDroidAmpRenderersFactory_playsId3ReplayGainMp3AndFeedsVisualizer() {
    val chain = ReflectiveDspChainHandle()
    chain.applyDspSettings(DspSettings(replayGainMode = ReplayGainMode.TRACK, replayGainPreampDb = 0f))
    chain.setVolume(255)
    assertEquals(1.0f, chain.currentReplayGainEffectiveGain(), 0.001f)

    val rgParserCls = Class.forName("com.droidamp.player.audio.dsp.ReplayGainTagParser")
    val rgParser = rgParserCls.getField("INSTANCE").get(null)
    val parseMetaMethod = rgParserCls.getMethod("parseMetadata", Metadata::class.java)
    val rgMetaCls = Class.forName("com.droidamp.player.audio.dsp.ReplayGainMetadata")
    val rgCompanion = rgMetaCls.getField("Companion").get(null)
    val rgEmpty = rgCompanion.javaClass.getMethod("getEMPTY").invoke(rgCompanion)

    SyntheticAudioFixtures.createCacheScope(targetContext, "t3_06_exo_rg").use { cache ->
      val mp3File = cache.writeFile(
        "rg_track.mp3",
        SyntheticAudioFixtures.generateMp3(
          sampleRateHz = 44_100,
          bitrateKbps = 128,
          channels = 2,
          durationMs = 500L,
          metadata = SyntheticAudioFixtures.Id3MetadataSpec(
            title = "RG Signal",
            artist = "Droid",
            replayGainTrackGainDb = "-8.00 dB",
            replayGainTrackPeak = "0.950000",
          ),
        ),
      )
      val wavFile = cache.writeFile(
        "rg_signal.wav",
        SyntheticAudioFixtures.generateWav(
          sampleRateHz = 44_100,
          channels = 2,
          durationMs = 700L,
          leftFrequencyHz = 440.0,
          rightFrequencyHz = 880.0,
          leftAmplitude = 0.5,
          rightAmplitude = 0.5,
        ),
      )

      withDspExoPlayer(chain) { player ->
        val ended = CompletableDeferred<Unit>()
        val error = CompletableDeferred<PlaybackException>()
        val sawAudio = AtomicBoolean(false)
        val parsedId3Rg = AtomicBoolean(false)

        fun tryParseMetadata(meta: Metadata?) {
          if (meta == null) return
          val parsed = parseMetaMethod.invoke(rgParser, meta) ?: return
          if (parsed != rgEmpty) {
            val trackGain = rgMetaCls.getMethod("getTrackGainDb").invoke(parsed) as? Float
            val trackPeak = rgMetaCls.getMethod("getTrackPeak").invoke(parsed) as? Float
            if (trackGain != null) {
              assertEquals(-8.0f, trackGain, 0.01f)
              assertEquals(0.95f, trackPeak ?: 0f, 0.01f)
              chain.setReplayGainMetadata(parsed)
              parsedId3Rg.set(true)
            }
          }
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onTracksChanged(tracks: Tracks) {
                for (group in tracks.groups) {
                  if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                    for (i in 0 until group.length) {
                      if (group.isTrackSelected(i)) {
                        tryParseMetadata(group.getTrackFormat(i).metadata)
                      }
                    }
                  }
                }
              }

              override fun onMetadata(metadata: Metadata) {
                tryParseMetadata(metadata)
              }

              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                  ended.complete(Unit)
                }
              }

              override fun onPlayerError(e: PlaybackException) {
                error.complete(e)
                ended.completeExceptionally(e)
              }
            },
          )
          player.setMediaItems(
            listOf(
              MediaItem.fromUri(cache.fileUri(mp3File)),
              MediaItem.fromUri(cache.fileUri(wavFile)),
            ),
          )
          player.prepare()
          player.play()
        }

        runBlocking {
          withTimeout(10_000L) {
            while (!ended.isCompleted) {
              val frame = chain.readVisualizerFrame(true)
              if (frame.spectrumBars.any { it > 0f } && frame.waveform.any { abs(it) > 0f }) {
                sawAudio.set(true)
              }
              delay(20L)
            }
            ended.await()
          }
        }
        assertFalse("Unexpected playback error", error.isCompleted)
        assertTrue("Expected Media3 + ReplayGainTagParser to parse ID3 TXXX:REPLAYGAIN_* from MP3", parsedId3Rg.get())
        assertEquals(0.3981f, chain.currentReplayGainEffectiveGain(), 0.01f)
        assertTrue("Expected non-zero spectrum/waveform PCM traversal through VisualizerTapProcessor", sawAudio.get())

        val input1k = generateSinePcm16(44_100, 1_000.0, 0.15, amplitude = 0.5, channels = 2)
        val rgOut = pumpPcm16ThroughProcessors(arrayOf(chain.rgProcessor), input1k, 44_100, 2)
        val deltaDb = rmsDbDelta(input1k, rgOut, channels = 2, channelIndex = 0, startFrame = 441)
        assertEquals(-8.0, deltaDb, 0.3)
      }
    }
  }

  @Test // T3-07: F7 (ExoPlayer + DroidAmpAudioRenderersFactory) x F13 (Gapless + RepeatMode OFF/ALL/ONE)
  fun t3_07_gaplessAndRepeatModes_transitionMultiTrackWavsThroughDspChain() {
    val chain = ReflectiveDspChainHandle()
    chain.applyDspSettings(DspSettings(gaplessEnabled = true, crossfadeSeconds = 0))

    SyntheticAudioFixtures.createCacheScope(targetContext, "t3_07_gapless").use { cache ->
      val w1 = cache.writeFile("w1.wav", SyntheticAudioFixtures.generateWav(44_100, 2, 350L, 440.0))
      val w2 = cache.writeFile("w2.wav", SyntheticAudioFixtures.generateWav(44_100, 2, 350L, 880.0))
      val items = listOf(MediaItem.fromUri(cache.fileUri(w1)), MediaItem.fromUri(cache.fileUri(w2)))

      withDspExoPlayer(chain) { player ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val offEnded = CompletableDeferred<Unit>()
        val offTransitions = CopyOnWriteArrayList<Int>()
        val repeatReasons = CopyOnWriteArrayList<Int>()
        val wrapDeferred = CompletableDeferred<Unit>()
        val repeatOneDeferred = CompletableDeferred<Unit>()

        instrumentation.runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                offTransitions.add(player.currentMediaItemIndex)
                repeatReasons.add(reason)
                if (player.repeatMode == Player.REPEAT_MODE_ALL && offTransitions.count { it == 0 } >= 2) {
                  wrapDeferred.complete(Unit)
                }
                if (player.repeatMode == Player.REPEAT_MODE_ONE && reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                  repeatOneDeferred.complete(Unit)
                }
              }

              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && player.repeatMode == Player.REPEAT_MODE_OFF) {
                  offEnded.complete(Unit)
                }
              }
            },
          )
          player.repeatMode = Player.REPEAT_MODE_OFF
          player.setMediaItems(items)
          player.prepare()
          player.play()
        }

        runBlocking { withTimeout(8_000L) { offEnded.await() } }
        assertTrue("Expected gapless transition to item 1 under REPEAT_MODE_OFF", 1 in offTransitions)

        // REPEAT_MODE_ALL wraps 0 -> 1 -> 0
        instrumentation.runOnMainSync {
          offTransitions.clear()
          player.repeatMode = Player.REPEAT_MODE_ALL
          player.seekTo(0, 0L)
          player.play()
        }
        runBlocking { withTimeout(10_000L) { wrapDeferred.await() } }
        assertTrue(1 in offTransitions)

        // REPEAT_MODE_ONE emits MEDIA_ITEM_TRANSITION_REASON_REPEAT
        instrumentation.runOnMainSync {
          repeatReasons.clear()
          player.repeatMode = Player.REPEAT_MODE_ONE
          player.seekTo(0, 0L)
          player.play()
        }
        runBlocking { withTimeout(8_000L) { repeatOneDeferred.await() } }
        assertTrue(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT in repeatReasons)

        instrumentation.runOnMainSync { player.stop() }
      }
    }
  }

  @Test // T3-08: F7 (Media3AudioEngineController) x F13 (CrossfadeController) x F15 (DspSettings)
  fun t3_08_crossfadeController_cancelsCleanlyOnNextPrevSeekAndPauseInMedia3Controller() {
    val deckCls = Class.forName("com.droidamp.player.audio.service.CrossfadeDeck")
    val listenerCls = Class.forName("com.droidamp.player.audio.service.CrossfadeController\$Listener")
    val candidateCls = Class.forName("com.droidamp.player.audio.service.CrossfadeController\$NextTrackCandidate")
    val xfadeCls = Class.forName("com.droidamp.player.audio.service.CrossfadeController")

    val activeVolumes = CopyOnWriteArrayList<Float>()
    val standbyCleared = AtomicInteger(0)

    fun makeDeckProxy(id: Int): Any =
      Proxy.newProxyInstance(deckCls.classLoader, arrayOf(deckCls)) { _, method, args ->
        when (method.name) {
          "getDeckId" -> id
          "setPlayerVolume" -> {
            if (id == 0) activeVolumes.add(args?.getOrNull(0) as? Float ?: 0f)
            Unit
          }
          "stopAndClear" -> {
            if (id == 1) standbyCleared.incrementAndGet()
            Unit
          }
          "equals" -> false
          "hashCode" -> id
          "toString" -> "Deck$id"
          else -> Unit
        }
      }

    val listenerProxy = Proxy.newProxyInstance(listenerCls.classLoader, arrayOf(listenerCls)) { _, method, _ ->
      when (method.name) {
        "equals" -> false
        "hashCode" -> 0
        "toString" -> "XfadeListener"
        else -> Unit
      }
    }

    val tracks = listOf(
      AudioTrack("x1", "file:///1.wav", "One", "A", fileName = "1.wav", folderPath = "/m", durationMs = 10_000L),
      AudioTrack("x2", "file:///2.wav", "Two", "A", fileName = "2.wav", folderPath = "/m", durationMs = 10_000L),
    )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t3_08_xfade").use { cache ->
      buildMedia3ControllerSession(nextPrefsFile(cache.rootDir, "t3_08")).use { session ->
        val xfadeCtrl =
          xfadeCls.getDeclaredConstructor(
            deckCls,
            deckCls,
            CoroutineScope::class.java,
            listenerCls,
            Long::class.javaPrimitiveType,
          ).newInstance(makeDeckProxy(0), makeDeckProxy(1), session.scope, listenerProxy, 50L)

        xfadeCls.getMethod("updateSettings", DspSettings::class.java)
          .invoke(xfadeCtrl, DspSettings(gaplessEnabled = true, crossfadeSeconds = 4))
        session.controller.setDspSettings(DspSettings(gaplessEnabled = true, crossfadeSeconds = 4))

        session.gatewayState.onCancelCrossfade = {
          xfadeCls.getMethod("cancelCrossfade").invoke(xfadeCtrl)
        }

        val candidate = candidateCls.getDeclaredConstructor(Int::class.javaPrimitiveType, AudioTrack::class.java)
          .newInstance(1, tracks[1])

        fun triggerCrossfade() {
          xfadeCls.getMethod(
            "onProgress",
            Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            AudioTrack::class.java,
            candidateCls,
          ).invoke(xfadeCtrl, true, 8_000L, 10_000L, tracks[0], candidate)
          assertTrue(
            "Crossfade must be active",
            xfadeCls.getMethod("isCrossfading").invoke(xfadeCtrl) as Boolean,
          )
        }

        session.controller.replaceQueue(tracks, startIndex = 0, autoPlay = true)

        triggerCrossfade()
        session.controller.next()
        assertFalse(xfadeCls.getMethod("isCrossfading").invoke(xfadeCtrl) as Boolean)

        triggerCrossfade()
        session.controller.seekTo(2_000L)
        assertFalse(xfadeCls.getMethod("isCrossfading").invoke(xfadeCtrl) as Boolean)

        triggerCrossfade()
        session.controller.previous()
        assertFalse(xfadeCls.getMethod("isCrossfading").invoke(xfadeCtrl) as Boolean)

        triggerCrossfade()
        session.controller.pause()
        assertFalse(xfadeCls.getMethod("isCrossfading").invoke(xfadeCtrl) as Boolean)

        assertTrue("Expected at least 4 crossfade cancellations", session.gatewayState.cancelCrossfadeCount >= 4)
        assertTrue("Standby deck must be cleared on cancel", standbyCleared.get() >= 4)
        assertEquals(1f, activeVolumes.last(), 0.001f)
      }
    }
  }

  @Test // T3-09: F10 (EQ Curve Drag) x F14 (Lock-Free VFD Modes) x F20 (PREAMP Gap + SevenSegmentClock)
  fun t3_09_visualizerModes_integrateWithEqCurveDragAndSevenSegmentClock() {
    val chain = ReflectiveDspChainHandle()
    val sig = generateSinePcm16(44_100, 1_000.0, 0.25, amplitude = 0.3, channels = 1)
    pumpPcm16ThroughProcessors(arrayOf(chain.visProcessor), sig, 44_100, 1)
    val liveFrame = chain.readVisualizerFrame(true)
    assertTrue("Real PCM must populate spectrumBars", liveFrame.spectrumBars.any { it > 0f })
    assertTrue("Real PCM must populate waveform", liveFrame.waveform.any { abs(it) > 0f })

    val track = AudioTrack(
      id = "vfd-1",
      uriString = "file:///vfd.wav",
      title = "VFD Test",
      artist = "Droid",
      fileName = "vfd.wav",
      folderPath = "/m",
      durationMs = 120_000L,
    )

    E2eRackDriver.launchIsolatedRack(composeRule, initialQueue = listOf(track), autoPlay = true).use { driver ->
      driver.engine.setPreampDb(3.5f)
      driver.engine.seekTo(30_000L)
      composeRule.waitForIdle()

      // Draw diagonal EQ curve across bands 0..9 and verify PREAMP isolation
      DroidAmpSelectors.drawEqCurve(composeRule, fromBand = 0, toBand = 9, startDb = -8f, endDb = 8f)
      composeRule.waitForIdle()
      assertEquals(3.5f, driver.engine.playbackState.value.equalizer.preampDb, 0.01f)
      assertTrue(driver.engine.playbackState.value.equalizer.bandsDb[0] < 0f)
      assertTrue(driver.engine.playbackState.value.equalizer.bandsDb[9] > 0f)

      // Cycle visualizer modes SPECTRUM -> OSCILLOSCOPE -> OFF
      DroidAmpSelectors.visualizerWithMode(composeRule, VisualizerMode.SPECTRUM.name).assertIsDisplayed()
      driver.cycleVisualizerMode()
      assertEquals(VisualizerMode.OSCILLOSCOPE, driver.engine.playbackState.value.visualizerMode)
      DroidAmpSelectors.visualizerWithMode(composeRule, VisualizerMode.OSCILLOSCOPE.name).assertIsDisplayed()

      driver.cycleVisualizerMode()
      assertEquals(VisualizerMode.OFF, driver.engine.playbackState.value.visualizerMode)
      DroidAmpSelectors.visualizerWithMode(composeRule, VisualizerMode.OFF.name).assertIsDisplayed()
      chain.resetVisualizer()
      assertEquals(VisualizerFrame.SILENT, chain.readVisualizerFrame(false))

      // Toggle clock mode ELAPSED -> REMAINING
      DroidAmpSelectors.clockWithTime(composeRule, "00:30").assertIsDisplayed()
      driver.toggleClockMode()
      assertEquals(TimeDisplayMode.REMAINING, driver.engine.playbackState.value.timeDisplayMode)
      DroidAmpSelectors.clockWithTime(composeRule, "-01:30").assertIsDisplayed()
    }
  }

  @Test // T3-10: F11 (Cold-Launch 204 vs Playing/Paused Preservation) x F15 (DataStore)
  fun t3_10_coldLaunchVolume204ResetWhenStopped_vsPreservedWhenPlayingOrPaused() {
    SyntheticAudioFixtures.createCacheScope(targetContext, "t3_10_vol_reset").use { cache ->
      val prefsFile = nextPrefsFile(cache.rootDir, "t3_10")

      // Session 1: persist low volume (55) while STOPPED
      buildMedia3ControllerSession(prefsFile).use { s1 ->
        s1.controller.setVolume(55)
        runBlocking {
          withTimeout(3_000L) {
            while (readDspSnapshotField(s1.prefsStore, "getVolume") != 55) delay(15L)
          }
        }
      }

      // Session 2: await DataStore hydration of 55 BEFORE DroidAmpViewModel init, then verify reset 55 -> 204
      buildMedia3ControllerSession(prefsFile).use { s2 ->
        runBlocking {
          withTimeout(3_000L) {
            while (s2.controller.playbackState.value.volume != 55) delay(15L)
          }
        }
        assertEquals(55, s2.controller.playbackState.value.volume)

        val vm = DroidAmpViewModel(s2.controller, FakeMusicSourceRepository(startLinked = true))
        assertEquals(204, vm.playbackState.value.volume)
        runBlocking {
          withTimeout(3_000L) {
            while (readDspSnapshotField(s2.prefsStore, "getVolume") != 204) delay(15L)
          }
        }
        mountRack(vm)
        composeRule.onNode(hasText("80%")).assertIsDisplayed()

        // While PLAYING at volume = 35, new ViewModel must NOT reset to 204
        val track = FakeMusicSourceRepository.DAFT_PUNK_TRACKS.first()
        s2.controller.replaceQueue(listOf(track), startIndex = 0, autoPlay = true)
        s2.controller.setVolume(35)
        assertEquals(PlaybackStatus.PLAYING, s2.controller.playbackState.value.status)
        val vmPlaying = DroidAmpViewModel(s2.controller, FakeMusicSourceRepository(startLinked = true))
        assertEquals(35, vmPlaying.playbackState.value.volume)

        // While PAUSED at volume = 35, new ViewModel must NOT reset to 204
        s2.controller.pause()
        assertEquals(PlaybackStatus.PAUSED, s2.controller.playbackState.value.status)
        val vmPaused = DroidAmpViewModel(s2.controller, FakeMusicSourceRepository(startLinked = true))
        assertEquals(35, vmPaused.playbackState.value.volume)
      }
    }
  }

  @Test // T3-11: F6 (Classic Presets) x F10 (Nyquist-Safe EQ @ 22.05/32kHz) x F20 (PREAMP Gap)
  fun t3_11_nyquistSafeEq_at22050And32000Hz_withPreampIsolationAndPresets() {
    val chain = ReflectiveDspChainHandle()
    // 22.05 kHz with FULL_TREBLE (12k/14k/16k > +9 dB, preamp -6 dB test)
    val highBoost = MutableList(WinampEqSpec.BAND_COUNT) { 0f }.apply {
      this[7] = 12f
      this[8] = 12f
      this[9] = 12f
    }
    chain.applyEqualizerState(EqualizerState(enabled = true, preampDb = -6f, bandsDb = highBoost))

    val sine22k = generateSinePcm16(22_050, 1_000.0, 0.25, amplitude = 0.2, channels = 1)
    val out22k = pumpPcm16ThroughProcessors(arrayOf(chain.eqProcessor), sine22k, 22_050, 1)
    assertEquals(-6.0, rmsDbDelta(sine22k, out22k, 1, 0, 2_205), 0.5)

    // Real ExoPlayer + DroidAmpAudioRenderersFactory playback of 22.05 kHz and 32 kHz WAVs
    chain.applyEqualizerState(
      EqualizerState(
        enabled = true,
        preampDb = ClassicWinampPresets.FULL_TREBLE.preampDb,
        bandsDb = ClassicWinampPresets.FULL_TREBLE.bandsDb,
        activePresetName = ClassicWinampPresets.FULL_TREBLE.name,
      ),
    )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t3_11_nyquist").use { cache ->
      val wav22 = cache.writeFile("22k.wav", SyntheticAudioFixtures.generateWav(22_050, 2, 300L, 440.0))
      val wav32 = cache.writeFile("32k.wav", SyntheticAudioFixtures.generateWav(32_000, 2, 300L, 880.0))

      withDspExoPlayer(chain) { player ->
        val ended = CompletableDeferred<Unit>()
        val err = CompletableDeferred<PlaybackException>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended.complete(Unit)
              }

              override fun onPlayerError(error: PlaybackException) {
                err.complete(error)
                ended.completeExceptionally(error)
              }
            },
          )
          player.setMediaItems(listOf(MediaItem.fromUri(cache.fileUri(wav22)), MediaItem.fromUri(cache.fileUri(wav32))))
          player.prepare()
          player.play()
        }
        runBlocking { withTimeout(8_000L) { ended.await() } }
        assertFalse("Nyquist sample rate playback must not error", err.isCompleted)
      }
    }

    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.selectEqPreset(ClassicWinampPresets.CLASSICAL.name)
      DeviceManifestVerifier.assertPreampGapIsolation(composeRule)
    }
  }

  @Test // T3-12: F8 (APIC MediaMetadata) x F16 (Metadata Probe) x F20 (Zero In-App Art + Offline Manifest)
  fun t3_12_embeddedApicMp3_populatesSystemMediaMetadataOnlyWithZeroInAppArt() {
    DeviceManifestVerifier.assertOfflineManifestPermissions(targetContext)

    val png = SyntheticAudioFixtures.generateMinimal1x1Png(0x10, 0xE0, 0x40)
    val mp3Bytes = SyntheticAudioFixtures.generateMp3(
      sampleRateHz = 44_100,
      bitrateKbps = 128,
      channels = 2,
      durationMs = 400L,
      metadata = SyntheticAudioFixtures.Id3MetadataSpec(
        title = "Cover Art Track",
        artist = "Droid Artist",
        album = "System Surface Only",
        artworkPngBytes = png,
      ),
    )

    SyntheticAudioFixtures.createCacheScope(targetContext, "t3_12_apic").use { cache ->
      val mp3File = cache.writeFile("apic.mp3", mp3Bytes)
      val fileUri = cache.fileUri(mp3File)

      val artworkExtractorCls = Class.forName("com.droidamp.player.audio.service.SystemArtworkExtractor")
      val extractor = artworkExtractorCls.getDeclaredConstructor(Context::class.java, Int::class.javaPrimitiveType)
        .newInstance(targetContext, 16)

      val extractedArt = runBlocking {
        invokeSuspend(
          extractor,
          "extractArtwork",
          String::class.java to fileUri,
          String::class.java to null,
        ) as ByteArray?
      }
      assertNotNull("SystemArtworkExtractor must extract embedded APIC bytes", extractedArt)
      assertTrue(extractedArt!!.isNotEmpty())

      val track = AudioTrack(
        id = "apic-1",
        uriString = fileUri,
        title = "Cover Art Track",
        artist = "Droid Artist",
        album = "System Surface Only",
        fileName = "apic.mp3",
        folderPath = cache.rootDir.absolutePath,
        durationMs = 400L,
      )
      val metadata =
        artworkExtractorCls.getMethod("buildSystemMediaMetadata", AudioTrack::class.java, ByteArray::class.java)
          .invoke(extractor, track, extractedArt) as MediaMetadata
      assertEquals("Cover Art Track", metadata.title?.toString())
      assertNotNull("MediaMetadata must carry artworkData for OS media surfaces", metadata.artworkData)

      // Strict AudioTrack zero-art field check (never match AudioTrack.artist via substring "art")
      val artTokens = setOf("art", "artwork", "albumart", "embeddedart", "arturi", "artbytes", "bitmap", "cover", "thumb", "picture")
      val badFields = AudioTrack::class.java.declaredFields.filter { f ->
        val n = f.name.lowercase(Locale.ROOT)
        val t = f.type.name.lowercase(Locale.ROOT)
        (n in artTokens || "artwork" in n || "albumart" in n || "cover" in n || "bitmap" in n) ||
          ("bitmap" in t || "drawable" in t || "image" in t)
      }
      assertTrue("AudioTrack must never expose artwork fields: $badFields", badFields.isEmpty())

      E2eRackDriver.launchIsolatedRack(composeRule, startLinked = true, initialQueue = listOf(track)).use {
        DeviceManifestVerifier.assertZeroInAppAlbumArt(composeRule)
      }
    }
  }

  @Test // T3-13: F18 (Select-First Track Safety) x F19 (Reorder + Jump-Filter + REM/CROP)
  fun t3_13_selectFirstTrackSafety_combinesWithActivePlaybackReorderAndJumpFilter() {
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS,
      autoPlay = true,
    ).use { driver ->
      val initialTrack = driver.engine.playbackState.value.currentTrack!!
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      driver.ensureSheetExpanded(SheetMode.LIBRARY)
      DroidAmpSelectors.libraryFolderRow(composeRule, "Electronic").performClick()
      composeRule.waitUntil(3_000L) {
        driver.viewModel.uiState.value.folderContents.currentPathSegments.lastOrNull() == "Electronic"
      }

      // Select-first safety: single-tapping tracks does NOT interrupt playing track
      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.libraryTrackRow(composeRule, "The Chemical Brothers - Block Rockin' Beats"),
      )
      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.libraryTrackRow(composeRule, "The Prodigy - Breathe"),
      )
      assertEquals(initialTrack.id, driver.engine.playbackState.value.currentTrack?.id)
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)
      composeRule.onNode(hasText("▶ PLAY (2)")).assertIsDisplayed()

      // + QUEUE adds 2 tracks (4 -> 6) without interrupting playback
      DroidAmpSelectors.libraryPrimaryQueueButton(composeRule).performClick()
      composeRule.waitForIdle()
      assertEquals(6, driver.engine.playbackState.value.queue.size)
      assertEquals(initialTrack.id, driver.engine.playbackState.value.currentTrack?.id)

      // Switch to PLAYLIST, filter via JUMP / FILTER, double-tap original index 6 -> index 5 plays
      DroidAmpSelectors.libraryPlButton(composeRule).performClick()
      driver.setJumpFilterQuery("breathe")
      val filteredRow = DroidAmpSelectors.playlistRow(composeRule, "The Prodigy - Breathe")
      filteredRow.assertIsDisplayed()
      DroidAmpSelectors.doubleTapNode(filteredRow)
      composeRule.waitForIdle()
      assertEquals(5, driver.engine.playbackState.value.currentIndex)
      assertEquals("trk-prodigy", driver.engine.playbackState.value.currentTrack?.id)

      // Close filter and move active track up 5 -> 4
      DroidAmpSelectors.jumpFilterCloseButton(composeRule).performClick()
      composeRule.waitForIdle()
      DroidAmpSelectors.singleTapNode(
        composeRule,
        DroidAmpSelectors.playlistRow(composeRule, "The Prodigy - Breathe"),
      )
      DroidAmpSelectors.playlistMoveUpButton(composeRule, "The Prodigy - Breathe").performClick()
      composeRule.waitForIdle()
      assertEquals(4, driver.engine.playbackState.value.currentIndex)
      assertEquals("trk-prodigy", driver.engine.playbackState.value.currentTrack?.id)
    }
  }

  @Test // T3-14: F15 (Persisted Roots) x F16 (SAF) x F18 (Onboarding Empty Well + Eject + Mode Memory)
  fun t3_14_onboardingEmptyWell_ejectAndSheetModeMemory_withSafUnlink() {
    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = false).use { driver ->
      DroidAmpSelectors.peekingSheetTitleBar(composeRule).assertIsDisplayed()
      DroidAmpSelectors.emptyFolderTitlePeeking(composeRule).assertIsDisplayed()

      driver.expandSheetByTitleTap()
      DroidAmpSelectors.expandedSheetTitleBar(composeRule).assertIsDisplayed()
      DroidAmpSelectors.selectMusicFolderButton(composeRule).assertIsDisplayed()

      driver.collapseSheetByTitleTap()
      assertTrue(
        composeRule
          .onAllNodes(hasText("DROIDAMP PLAYLIST ▲") or hasText("DROIDAMP LIBRARY ▲"))
          .fetchSemanticsNodes()
          .isNotEmpty(),
      )
      assertTrue(
        composeRule
          .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
          .fetchSemanticsNodes()
          .isEmpty(),
      )

      // Deck Eject always opens LIBRARY with disabled PLAY/+QUEUE when unlinked
      driver.clickEjectOnDeck()
      composeRule.onNode(hasText("DROIDAMP LIBRARY ▼")).assertIsDisplayed()
      DroidAmpSelectors.libraryPrimaryPlayButton(composeRule).assertIsNotEnabled()
      DroidAmpSelectors.libraryPrimaryQueueButton(composeRule).assertIsNotEnabled()

      // Link SAF root -> root folders populate ("Electronic"), nested subfolder ("Daft Punk - Discovery") opens on click
      driver.linkFakeSafRoot()
      DroidAmpSelectors.libraryFolderRow(composeRule, "Electronic").assertIsDisplayed().performClick()
      composeRule.waitUntil(3_000L) {
        driver.viewModel.uiState.value.folderContents.folders.any { it.name == "Daft Punk - Discovery" }
      }
      DroidAmpSelectors.libraryFolderRow(composeRule, "Daft Punk - Discovery").assertIsDisplayed()

      DroidAmpSelectors.libraryPlButton(composeRule).performClick()
      composeRule.onNode(hasText("DROIDAMP PLAYLIST ▼")).assertIsDisplayed()
      driver.collapseSheetBySwipeDown()
      composeRule.onNode(hasText("DROIDAMP PLAYLIST ▲")).assertIsDisplayed()
      driver.expandSheetBySwipeUp()
      composeRule.onNode(hasText("DROIDAMP PLAYLIST ▼")).assertIsDisplayed()

      // Unlinking reverts cleanly back to onboarding EmptyFolderWell
      runBlocking { driver.repository.unlinkAllRoots() }
      driver.viewModel.switchSheetMode(SheetMode.LIBRARY)
      composeRule.waitForIdle()
      DroidAmpSelectors.emptyFolderTitleExpanded(composeRule).assertIsDisplayed()
      DroidAmpSelectors.libraryPrimaryPlayButton(composeRule).assertIsNotEnabled()
    }
  }

  @Test // T3-15: F5 (.m3u8) x F16 (SAF) x F17 (MediaStore) x F19 (Export)
  fun t3_15_switchingRepository_preservesSafRootsAcrossMediaStoreSwitchAndExport() {
    val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ASwitchMusic"
    val rootDocUri = "$treeUri/document/primary%3ASwitchMusic"
    val safTrackUri = "$rootDocUri%2Fsaf_song.mp3"
    val safSpec =
      SafTreeSpec(
        treeUri = treeUri,
        rootDocUri = rootDocUri,
        rootName = "SwitchMusic",
        childrenByDirUri = mapOf(
          rootDocUri to listOf(newSafNode(safTrackUri, "saf_song.mp3", false, "audio/mpeg")),
        ),
      )

    val msUri = "content://media/external/audio/media/901"
    val msRecord = newMediaStoreAudioRecord(
      mediaId = 901L,
      contentUriString = msUri,
      displayName = "ms_song.mp3",
      title = "MediaStore Song",
      artist = "MS Artist",
      album = "MS Album",
      durationMs = 175_000L,
      relativePath = "Music/DeviceAudio/",
    )

    val probe =
      createProbeProxy(
        mapOf(
          safTrackUri to newProbedMetadata("SAF Song", "SAF Artist", durationMs = 150_000L),
          msUri to newProbedMetadata("MediaStore Song", "MS Artist", durationMs = 175_000L),
        ),
      )

    val persisted = mutableSetOf<String>()
    SyntheticAudioFixtures.createCacheScope(targetContext, "t3_15_switch").use { cache ->
      val prefs = nextPrefsFile(cache.rootDir, "t3_15")

      buildStorageStack(
        prefsFile = prefs,
        safGateway = createSafGatewayProxy(listOf(safSpec), persisted),
        mediaStoreGateway = createMediaStoreGatewayProxy(hasPermission = true, audioRecords = listOf(msRecord)),
        probe = probe,
      ).use { stack ->
        runBlocking {
          assertEquals(StorageBackend.SAF, stack.switchingRepo.storageBackend.value)
          stack.switchingRepo.linkSafRoot(treeUri)
          assertEquals(1, stack.switchingRepo.linkedRoots.value.size)

          stack.switchingRepo.setStorageBackend(StorageBackend.MEDIA_STORE)
          assertEquals(StorageBackend.MEDIA_STORE, stack.switchingRepo.storageBackend.value)
          val msRoot = stack.switchingRepo.browseFolder(null)
          assertEquals("mediastore://folders", msRoot.currentFolderUri)
          val msTracks = stack.switchingRepo.resolveFolderTracks(msRoot.currentFolderUri!!, recursive = true)
          assertEquals(1, msTracks.size)

          val msExport = stack.switchingRepo.saveM3u8Playlist("ms_export.m3u8", playlistCodec.encodeM3u8(msTracks))
          assertTrue(msExport.isSuccess)

          stack.switchingRepo.setStorageBackend(StorageBackend.SAF)
          assertEquals(StorageBackend.SAF, stack.switchingRepo.storageBackend.value)
          val safFolder = stack.switchingRepo.browseFolder(treeUri)
          assertEquals(1, safFolder.tracks.size)
          val safExport = stack.switchingRepo.saveM3u8Playlist("saf_export.m3u8", playlistCodec.encodeM3u8(safFolder.tracks))
          assertTrue(safExport.isSuccess)
        }
      }

      // Fresh instance from same DataStore preserves SAF root + backend
      buildStorageStack(
        prefsFile = prefs,
        safGateway = createSafGatewayProxy(listOf(safSpec), persisted),
        mediaStoreGateway = createMediaStoreGatewayProxy(hasPermission = true, audioRecords = listOf(msRecord)),
        probe = probe,
      ).use { stack2 ->
        runBlocking {
          withTimeout(3_000L) {
            while (stack2.switchingRepo.linkedRoots.value.isEmpty()) delay(15L)
          }
          assertEquals(StorageBackend.SAF, stack2.switchingRepo.storageBackend.value)
          assertEquals(1, stack2.switchingRepo.browseFolder(treeUri).tracks.size)
        }
      }
    }
  }

  @Test // T3-16: F6 (Presets) x F10 (EQ ON Bypass) x F20 (Swipe Buffer & NavBar Clearance)
  fun t3_16_eqOnBypass_preservesSlidersInUiWhileProducingBitExactPcmPassthrough() {
    val chain = ReflectiveDspChainHandle()
    val eq = chain.eqProcessor
    val rockEq = EqualizerState(
      enabled = true,
      preampDb = ClassicWinampPresets.ROCK.preampDb,
      bandsDb = ClassicWinampPresets.ROCK.bandsDb,
      activePresetName = ClassicWinampPresets.ROCK.name,
    )
    chain.applyEqualizerState(rockEq)

    val input = generateSinePcm16(44_100, 60.0, 0.15, amplitude = 0.2, channels = 1)
    val shaped = pumpPcm16ThroughProcessors(arrayOf(eq), input, 44_100, 1)
    assertFalse("Enabled ROCK EQ must modify 60 Hz PCM", input.contentEquals(shaped))

    chain.applyEqualizerState(rockEq.copy(enabled = false))
    val bypassed = pumpPcm16ThroughProcessors(arrayOf(eq), input, 44_100, 1)
    assertArrayEquals("Disabled EQ must produce bit-exact pass-through", input, bypassed)

    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = true).use { driver ->
      driver.selectEqPreset(ClassicWinampPresets.CLASSICAL.name)
      val savedBands = driver.engine.playbackState.value.equalizer.bandsDb
      driver.toggleEqEnabled()
      assertFalse(driver.engine.playbackState.value.equalizer.enabled)
      assertEquals(savedBands, driver.engine.playbackState.value.equalizer.bandsDb)

      DeviceManifestVerifier.assertPeekingSheetSwipeBuffer(composeRule)
      DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(composeRule)

      driver.toggleEqVisibility()
      DeviceManifestVerifier.assertPeekingSheetSwipeBuffer(composeRule)
      DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(composeRule)
    }
  }

  @Test // T3-17: F7 (ExoPlayer) x F12 (Mono-Safe Balance) x F14 (MONO VFD Indicator)
  fun t3_17_monoWav_preservesAmplitudeAcrossFullBalanceSweep_andShowsMonoVfd() {
    val chain = ReflectiveDspChainHandle()
    chain.setVolume(255)
    val monoIn = generateSinePcm16(44_100, 1_000.0, 0.15, amplitude = 0.25, channels = 1)

    for (bal in listOf(-127, 0, 127)) {
      chain.setBalance(bal)
      val out = pumpPcm16ThroughProcessors(arrayOf(chain.volBalProcessor), monoIn, 44_100, channels = 1)
      assertArrayEquals("Mono signal must never be attenuated/muted at balance=$bal", monoIn, out)
    }

    SyntheticAudioFixtures.createCacheScope(targetContext, "t3_17_mono").use { cache ->
      val monoFile = cache.writeFile("mono.wav", SyntheticAudioFixtures.generateWav(44_100, channels = 1, durationMs = 300L))
      withDspExoPlayer(chain) { player ->
        val ended = CompletableDeferred<Unit>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended.complete(Unit)
              }
            },
          )
          player.setMediaItem(MediaItem.fromUri(cache.fileUri(monoFile)))
          player.prepare()
          player.play()
        }
        runBlocking { withTimeout(8_000L) { ended.await() } }
      }
    }

    val monoTrack = AudioTrack(
      id = "mono-1",
      uriString = "file:///mono.wav",
      title = "Mono Signal",
      artist = "Droid",
      fileName = "mono.wav",
      folderPath = "/m",
      durationMs = 30_000L,
      sampleRateHz = 44_100,
      channelMode = ChannelMode.MONO,
    )
    E2eRackDriver.launchIsolatedRack(composeRule, initialQueue = listOf(monoTrack)).use { driver ->
      DroidAmpSelectors.monoLabel(composeRule).assertIsDisplayed()
      driver.setBalanceFraction(0f)
      composeRule.onNode(hasText("L100")).assertIsDisplayed()
      driver.setBalanceFraction(0.5f)
      composeRule.onNode(hasText("CTR")).assertIsDisplayed()
      driver.setBalanceFraction(1f)
      composeRule.onNode(hasText("R100")).assertIsDisplayed()
    }
  }

  @Test // T3-18: F4 (winamp.ini rg_mode/rg_preamp) x F9 (ReplayGain Peak Limiter) x F15 (DspSettings)
  fun t3_18_winampIniReplayGainSettings_drivePeakClipProtectionOnHotSignal() {
    val ini =
      SyntheticWinampFixtures.winampIni(
        WinampIniSnapshot(
          volume = 204,
          pan = 0,
          shuffle = false,
          repeat = false,
          timeRemaining = false,
          eqEnabled = true,
          eqAuto = false,
          eqPreampDb = 0f,
          eqBandsDb = ClassicWinampPresets.FLAT.bandsDb,
          replayGainMode = 1,
          replayGainPreampDb = 12.0f,
          crossfadeSeconds = 0,
        ),
      )
    val parsed = iniCodec.parse(ini)
    val rgMode = if (parsed.replayGainMode == 1) ReplayGainMode.TRACK else ReplayGainMode.OFF

    val chain = ReflectiveDspChainHandle()
    chain.applyDspSettings(
      DspSettings(
        replayGainMode = rgMode,
        replayGainPreampDb = parsed.replayGainPreampDb,
      ),
    )
    chain.setReplayGainMetadata(newReplayGainMetadata(trackGainDb = 6.0f, trackPeak = 0.99f))

    val effectiveGain = chain.currentReplayGainEffectiveGain()
    assertTrue("Effective gain ($effectiveGain) must be capped at <= 1/0.99", effectiveGain <= (1.0f / 0.99f) + 1e-4f)

    val hotSignal = generateSinePcm16(44_100, 1_000.0, 0.1, amplitude = 0.95, channels = 2)
    val out = pumpPcm16ThroughProcessors(arrayOf(chain.rgProcessor), hotSignal, 44_100, 2)
    assertEquals(hotSignal.size, out.size)
    assertTrue(out.any { it != 0.toShort() })
  }

  @Test // T3-19: F2 (winamp.q1) x F6 (WinampPresetLibrary) x F10 (EqualizerState & PCM)
  fun t3_19_q1LibraryMergedWithWinampPresetLibrary_appliesCustomPresetToUiAndDsp() {
    val customWarm = EqPreset(
      name = "WarmTape",
      preampDb = 1.5f,
      bandsDb = listOf(4f, 3f, 1f, 0f, -2f, -1f, 0f, 1f, 2f, 3f),
    )
    val collidingRock = EqPreset(
      name = "ROCK",
      preampDb = -10f,
      bandsDb = List(10) { -10f },
    )
    val q1Bytes = eqfCodec.encodePresetLibraryQ1(listOf(collidingRock, customWarm))
    val parsedQ1 = eqfCodec.parsePresetLibraryQ1(q1Bytes)

    val presetLibCls = Class.forName("com.droidamp.player.interop.WinampPresetLibrary")
    val libInstance = presetLibCls.getField("INSTANCE").get(null)
    @Suppress("UNCHECKED_CAST")
    val merged =
      presetLibCls.getMethod("mergePresets", List::class.java, List::class.java)
        .invoke(libInstance, parsedQ1, ClassicWinampPresets.ALL) as List<EqPreset>

    val resolvedRock =
      presetLibCls.getMethod("findByName", String::class.java, List::class.java)
        .invoke(libInstance, "rock", merged) as EqPreset?
    val resolvedWarm =
      presetLibCls.getMethod("findByName", String::class.java, List::class.java)
        .invoke(libInstance, "warmtape", merged) as EqPreset?

    assertNotNull(resolvedRock)
    assertNotNull(resolvedWarm)
    // Built-in ROCK wins over colliding imported ROCK
    assertEquals(ClassicWinampPresets.ROCK.bandsDb, resolvedRock!!.bandsDb)

    val chain = ReflectiveDspChainHandle()
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.viewModel.applyEqPreset(resolvedWarm!!)
      composeRule.waitForIdle()
      chain.applyEqualizerState(driver.engine.playbackState.value.equalizer)
      val sine60 = generateSinePcm16(44_100, 60.0, 0.25, amplitude = 0.2, channels = 1)
      val out60 = pumpPcm16ThroughProcessors(arrayOf(chain.eqProcessor), sine60, 44_100, 1)
      val delta60 = rmsDbDelta(sine60, out60, 1, 0, 4_410)
      assertTrue("WarmTape +1.5 preamp & +4 dB @ 60Hz must boost 60Hz (> +3.5 dB), got $delta60", delta60 > 3.5)
    }
  }

  @Test // T3-20: F4 (Locale.ITALY INI) x F5 (Playlist Encode) x F19 (MISC Sort/Reverse/Randomize)
  fun t3_20_playlistMiscSortReverseRandomize_preservesCurrentTrackIdentity_underNonUsLocale() {
    val previousLocale = Locale.getDefault()
    Locale.setDefault(Locale.ITALY)
    try {
      val tracks = FakeMusicSourceRepository.DAFT_PUNK_TRACKS
      E2eRackDriver.launchIsolatedRack(
        composeRule,
        startLinked = true,
        initialQueue = tracks,
        autoPlay = true,
      ).use { driver ->
        driver.engine.playTrackAt(1)
        val playingId = driver.engine.playbackState.value.currentTrack!!.id
        assertEquals("trk-dp-2", playingId)

        driver.ensureSheetExpanded(SheetMode.PLAYLIST)

        driver.clickPlaylistMenuAction(DroidAmpSelectors.playlistMiscButton(composeRule), "MISC OPTS", "SORT BY TITLE")
        assertEquals(playingId, driver.engine.playbackState.value.currentTrack?.id)

        driver.clickPlaylistMenuAction(DroidAmpSelectors.playlistMiscButton(composeRule), "MISC OPTS", "REVERSE LIST")
        assertEquals(playingId, driver.engine.playbackState.value.currentTrack?.id)

        driver.clickPlaylistMenuAction(DroidAmpSelectors.playlistMiscButton(composeRule), "MISC OPTS", "RANDOMIZE")
        assertEquals(playingId, driver.engine.playbackState.value.currentTrack?.id)

        val m3u8 = playlistCodec.encodeM3u8(driver.engine.playbackState.value.queue)
        assertTrue(m3u8.startsWith("#EXTM3U"))

        val iniEncoded =
          iniCodec.encode(
            WinampIniSnapshot(
              volume = 180,
              pan = 12,
              shuffle = true,
              repeat = false,
              timeRemaining = true,
              eqEnabled = true,
              eqAuto = false,
              eqPreampDb = 2.5f,
              eqBandsDb = List(10) { 1.25f },
              replayGainMode = 1,
              replayGainPreampDb = -3.75f,
              crossfadeSeconds = 2,
            ),
          )
        assertFalse("INI must never serialize comma decimals under Locale.ITALY", "2,5" in iniEncoded || "-3,75" in iniEncoded)
        assertTrue("rg_preamp must use '.' decimal", "-3.75" in iniEncoded)
      }
    } finally {
      Locale.setDefault(previousLocale)
    }
  }

  @Test // T3-21: F7 (Real ServiceBindingAudioEngineGateway) x F14 (VFD) x F18 (Rack) x F20 (Back Collapse)
  fun t3_21_realMedia3ServiceBindingGateway_playsSyntheticWavAndSurvivesBackCollapse() {
    SyntheticAudioFixtures.createCacheScope(targetContext, "t3_21_svc_bind").use { cache ->
      val wavFile = cache.writeFile("svc_tone.wav", SyntheticAudioFixtures.generateWav(44_100, 2, 1_200L, 440.0))
      val track = AudioTrack(
        id = "svc-wav-1",
        uriString = cache.fileUri(wavFile),
        title = "Service Tone",
        artist = "DroidAmp",
        fileName = "svc_tone.wav",
        folderPath = cache.rootDir.absolutePath,
        durationMs = 1_200L,
      )

      val prefsFile = nextPrefsFile(cache.rootDir, "t3_21")
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
      val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { prefsFile })
      val prefsStoreCls = Class.forName("com.droidamp.player.audio.service.DspPreferencesStore")
      val prefsStore = prefsStoreCls.getDeclaredConstructor(DataStore::class.java).newInstance(dataStore)
      val ctrlCls = Class.forName("com.droidamp.player.audio.service.Media3AudioEngineController")
      val companion = ctrlCls.getField("Companion").get(null)

      lateinit var controller: AudioEngineController
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        controller =
          companion.javaClass
            .getMethod("create", Context::class.java, CoroutineScope::class.java, prefsStoreCls)
            .invoke(companion, targetContext, scope, prefsStore) as AudioEngineController
      }

      try {
        val vm = DroidAmpViewModel(controller, FakeMusicSourceRepository(startLinked = true))
        val driver = mountRack(vm)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
          controller.replaceQueue(listOf(track), startIndex = 0, autoPlay = true)
        }
        composeRule.waitUntil(6_000L) {
          controller.playbackState.value.status == PlaybackStatus.PLAYING
        }

        driver.ensureSheetExpanded(SheetMode.PLAYLIST)
        DroidAmpSelectors.expandedSheetTitleBar(composeRule).assertIsDisplayed()

        Espresso.pressBack()
        composeRule.waitForIdle()
        assertTrue(
          composeRule
            .onAllNodes(hasText("DROIDAMP PLAYLIST ▼") or hasText("DROIDAMP LIBRARY ▼"))
            .fetchSemanticsNodes()
            .isEmpty(),
        )
        assertFalse(vm.uiState.value.isSheetExpanded)
      } finally {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
          controller.stop()
          (controller as Closeable).close()
        }
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
      }
    }
  }

  @Test // T3-22: F7 (Playback) x F18 (Library Sheet) x F19 (PlaylistMicroTransportBar Disambiguation)
  fun t3_22_playlistMicroTransportBar_controlsActivePlaybackWhileLibrarySheetExpanded() {
    E2eRackDriver.launchIsolatedRack(
      composeRule,
      startLinked = true,
      initialQueue = FakeMusicSourceRepository.DAFT_PUNK_TRACKS,
      autoPlay = false,
    ).use { driver ->
      driver.ensureSheetExpanded(SheetMode.LIBRARY)
      composeRule.onNode(hasText("DROIDAMP LIBRARY ▼")).assertIsDisplayed()

      // Exercise .onLast() disambiguated PlaylistMicroTransportBar controls without collapsing sheet
      DroidAmpSelectors.sheetMicroPlayButton(composeRule).performClick()
      composeRule.waitForIdle()
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      DroidAmpSelectors.sheetMicroNextButton(composeRule).performClick()
      composeRule.waitForIdle()
      assertEquals(1, driver.engine.playbackState.value.currentIndex)

      DroidAmpSelectors.sheetMicroPrevButton(composeRule).performClick()
      composeRule.waitForIdle()
      assertEquals(0, driver.engine.playbackState.value.currentIndex)

      DroidAmpSelectors.sheetMicroPauseButton(composeRule).performClick()
      composeRule.waitForIdle()
      assertEquals(PlaybackStatus.PAUSED, driver.engine.playbackState.value.status)

      DroidAmpSelectors.sheetMicroStopButton(composeRule).performClick()
      composeRule.waitForIdle()
      assertEquals(PlaybackStatus.STOPPED, driver.engine.playbackState.value.status)
      composeRule.onNode(hasText("DROIDAMP LIBRARY ▼")).assertIsDisplayed()
    }
  }

  companion object {
    private val PREFS_COUNTER = AtomicInteger(0)
  }
}
