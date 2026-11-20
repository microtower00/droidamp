package com.droidamp.player.audio.service

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.droidamp.player.audio.dsp.ReplayGainMetadata
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.RepeatMode
import com.droidamp.player.domain.model.ReplayGainMode
import com.droidamp.player.domain.model.VisualizerFrame
import com.droidamp.player.domain.model.VisualizerMode
import com.droidamp.player.domain.model.WinampEqSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class Media3AudioEngineControllerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private class FakeAudioEngineGateway(
    val dspChain: DroidAmpDspChain = DroidAmpDspChain(),
    override val managesPlaylistTransitions: Boolean = false,
  ) : AudioEngineGateway {
    var gatewayListener: AudioEngineGateway.Listener? = null
    var queue: List<AudioTrack> = emptyList()
    var currentIndex: Int = -1
    var positionMs: Long = 0L
    var durationMs: Long = 0L
    var isPlaying: Boolean = false
    var currentRepeatMode: RepeatMode = RepeatMode.OFF
    var currentShuffleEnabled: Boolean = false
    var lastAppliedDspSettings: DspSettings = DspSettings()
    var lastAppliedEq: EqualizerState = EqualizerState()
    var lastAppliedVolume: Int = PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME
    var lastAppliedBalance: Int = 0
    var lastAppliedReplayGain: ReplayGainMetadata = ReplayGainMetadata.EMPTY
    var cancelCrossfadeCount = 0
    var setQueueCount = 0
    var syncQueueCount = 0
    var selectTrackCount = 0
    var seekToCount = 0

    override fun setListener(listener: AudioEngineGateway.Listener?) {
      this.gatewayListener = listener
    }

    override fun setQueue(
      tracks: List<AudioTrack>,
      startIndex: Int,
      playWhenReady: Boolean,
    ) {
      setQueueCount++
      queue = tracks
      currentIndex = if (tracks.isEmpty()) -1 else startIndex.coerceIn(0, tracks.lastIndex)
      positionMs = 0L
      durationMs = tracks.getOrNull(currentIndex)?.durationMs ?: 0L
      isPlaying = playWhenReady && tracks.isNotEmpty()
    }

    override fun syncQueueWithoutInterrupting(
      tracks: List<AudioTrack>,
      currentIndex: Int,
    ) {
      syncQueueCount++
      queue = tracks
      this.currentIndex = currentIndex
    }

    override fun selectTrackAt(
      index: Int,
      playWhenReady: Boolean,
    ) {
      selectTrackCount++
      currentIndex = index
      positionMs = 0L
      durationMs = queue.getOrNull(index)?.durationMs ?: 0L
      isPlaying = playWhenReady
    }

    override fun play() {
      isPlaying = queue.isNotEmpty()
    }

    override fun pause() {
      isPlaying = false
    }

    override fun stop() {
      isPlaying = false
      positionMs = 0L
    }

    override fun seekTo(positionMs: Long) {
      seekToCount++
      this.positionMs = positionMs
    }

    override fun setRepeatMode(mode: RepeatMode) {
      currentRepeatMode = mode
    }

    override fun setShuffleEnabled(enabled: Boolean) {
      currentShuffleEnabled = enabled
    }

    override fun applyDspSettings(settings: DspSettings) {
      lastAppliedDspSettings = settings
      dspChain.applyDspSettings(settings)
    }

    override fun applyEqualizerState(state: EqualizerState) {
      lastAppliedEq = state
      dspChain.applyEqualizerState(state)
    }

    override fun applyVolume(volume0To255: Int) {
      lastAppliedVolume = volume0To255
      dspChain.setVolume(volume0To255)
    }

    override fun applyBalance(balanceMinus127To127: Int) {
      lastAppliedBalance = balanceMinus127To127
      dspChain.setBalance(balanceMinus127To127)
    }

    override fun applyReplayGainMetadata(metadata: ReplayGainMetadata) {
      lastAppliedReplayGain = metadata
      dspChain.setReplayGainMetadata(metadata)
    }

    override fun readVisualizerFrame(isPlaying: Boolean): VisualizerFrame =
      dspChain.readVisualizerFrame(isPlaying)

    override fun resetVisualizer() {
      dspChain.resetVisualizer()
    }

    override fun currentPositionMs(): Long = positionMs

    override fun currentDurationMs(): Long = durationMs

    override fun onCrossfadeProgressTick(
      isPlaying: Boolean,
      positionMs: Long,
      durationMs: Long,
      currentTrack: AudioTrack?,
      nextCandidate: CrossfadeController.NextTrackCandidate?,
    ) {
      // No-op in gateway unit tests unless overridden
    }

    override fun cancelCrossfade() {
      cancelCrossfadeCount++
    }
  }

  private fun track(
    id: String,
    title: String = "Title $id",
    artist: String = "Artist $id",
    fileName: String = "$id.mp3",
    folderPath: String = "/music",
    durationMs: Long = 180_000L,
  ) = AudioTrack(
    id = id,
    uriString = "file://$folderPath/$fileName",
    title = title,
    artist = artist,
    album = "Album",
    fileName = fileName,
    folderPath = folderPath,
    durationMs = durationMs,
  )

  private fun createPreferencesStore(
    file: File,
    scope: kotlinx.coroutines.CoroutineScope,
  ): DspPreferencesStore {
    val dataStore =
      PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { file },
      )
    return DspPreferencesStore(dataStore)
  }

  @Test
  fun coldLaunch_resetsPersistedVolumeTo204WhenStopped_evenWithAsyncHydrationRace() = runTest {
    val file = File(tempFolder.root, "race.preferences_pb")
    val preScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val preStore = createPreferencesStore(file, preScope)
    preStore.saveVolume(40)
    preStore.saveBalance(-30)
    preScope.coroutineContext.job.cancelAndJoin()

    // Wrap DataStore so initial emission is delayed until after applyColdLaunchVolumeResetIfStopped(204)
    val realDataStore =
      PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = backgroundScope,
        produceFile = { file },
      )
    val gate = CompletableDeferred<Unit>()
    val delayedDataStore =
      object : DataStore<Preferences> {
        override val data: Flow<Preferences> =
          flow {
            gate.await()
            realDataStore.data.collect { emit(it) }
          }

        override suspend fun updateData(
          transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = realDataStore.updateData(transform)
      }

    val gateway = FakeAudioEngineGateway()
    val controller =
      Media3AudioEngineController(
        gateway = gateway,
        preferencesStore = DspPreferencesStore(delayedDataStore),
        scope = backgroundScope,
      )

    // Synchronous ViewModel.init cold-launch reset BEFORE DataStore finishes hydrating!
    controller.applyColdLaunchVolumeResetIfStopped(PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME)
    assertEquals(204, controller.playbackState.value.volume)
    assertEquals(204, gateway.lastAppliedVolume)

    // Now unblock DataStore hydration (which holds stale volume=40, balance=-30)
    gate.complete(Unit)
    controller.awaitInitialHydration()
    advanceUntilIdle()

    // Volume MUST stay 204 (not overwritten by 40!), while balance -30 IS hydrated
    assertEquals(204, controller.playbackState.value.volume)
    assertEquals(-30, controller.playbackState.value.balance)
    assertEquals(204, gateway.lastAppliedVolume)
    assertEquals(-30, gateway.lastAppliedBalance)
    assertEquals(204, DspPreferencesStore(realDataStore).loadSnapshot().volume)

    controller.close()
  }

  @Test
  fun coldLaunch_preservesVolumeWhenPlayingOrPaused_andResetsOnlyOnce() = runTest {
    val store = createPreferencesStore(File(tempFolder.root, "once.preferences_pb"), backgroundScope)
    store.saveVolume(40)

    // 1. Fresh controller where persisted volume starts at 40 -> cold-launch reset -> 204
    val gateway2 = FakeAudioEngineGateway()
    val controller2 = Media3AudioEngineController(gateway2, store, backgroundScope)
    controller2.awaitInitialHydration()
    advanceUntilIdle()
    assertEquals(40, controller2.playbackState.value.volume)

    controller2.applyColdLaunchVolumeResetIfStopped(204)
    assertEquals(204, controller2.playbackState.value.volume)

    // User lowers volume to 90 while STOPPED -> second call in same cold launch is a no-op
    controller2.setVolume(90)
    controller2.applyColdLaunchVolumeResetIfStopped(204)
    assertEquals(90, controller2.playbackState.value.volume)
    controller2.close()

    // 2. When PLAYING or PAUSED, applyColdLaunchVolumeResetIfStopped must NEVER reset volume
    val gateway3 = FakeAudioEngineGateway()
    val controller3 = Media3AudioEngineController(gateway3, store, backgroundScope)
    controller3.awaitInitialHydration()
    advanceUntilIdle()
    controller3.replaceQueue(listOf(track("a"), track("b")), startIndex = 0, autoPlay = true)
    controller3.setVolume(35)
    assertEquals(PlaybackStatus.PLAYING, controller3.playbackState.value.status)

    controller3.applyColdLaunchVolumeResetIfStopped(204)
    assertEquals(35, controller3.playbackState.value.volume)

    controller3.pause()
    assertEquals(PlaybackStatus.PAUSED, controller3.playbackState.value.status)
    controller3.applyColdLaunchVolumeResetIfStopped(204)
    assertEquals(35, controller3.playbackState.value.volume)
    controller3.close()
  }

  @Test
  fun transport_previousThreeSecondThreshold_nextWrapShuffle_andNaturalEndRepeatModes() = runTest {
    val store = createPreferencesStore(File(tempFolder.root, "transport.preferences_pb"), backgroundScope)
    val gateway = FakeAudioEngineGateway()
    val controller = Media3AudioEngineController(gateway, store, backgroundScope)
    advanceUntilIdle()

    val tracks = listOf(track("t0"), track("t1"), track("t2"))
    controller.replaceQueue(tracks, startIndex = 0, autoPlay = true)

    // > 3_000L -> previous() seeks to 0L on current track
    gateway.positionMs = 4_200L
    controller.previous()
    assertEquals(0, controller.playbackState.value.currentIndex)
    assertEquals(0L, controller.playbackState.value.positionMs)
    assertEquals(0L, gateway.positionMs)

    // <= 3_000L at index 0 -> wraps to lastIndex (2)
    gateway.positionMs = 1_500L
    controller.previous()
    assertEquals(2, controller.playbackState.value.currentIndex)

    // Manual next() at lastIndex wraps to 0 even when RepeatMode.OFF
    assertEquals(RepeatMode.OFF, controller.playbackState.value.repeatMode)
    controller.next()
    assertEquals(0, controller.playbackState.value.currentIndex)

    // Shuffle next() always picks index != currentIndex when size > 1
    controller.toggleShuffle()
    assertTrue(controller.playbackState.value.shuffle)
    val before = controller.playbackState.value.currentIndex
    controller.next()
    assertNotEquals(before, controller.playbackState.value.currentIndex)
    controller.toggleShuffle()

    // Natural track end under RepeatMode.ONE -> stays at index, resets position to 0
    controller.playTrackAt(1)
    gateway.positionMs = 50_000L
    controller.cycleRepeatMode() // OFF -> ALL
    controller.cycleRepeatMode() // ALL -> ONE
    assertEquals(RepeatMode.ONE, controller.playbackState.value.repeatMode)
    gateway.gatewayListener?.onPlaybackStatusChanged(isPlaying = false, isEnded = true)
    assertEquals(1, controller.playbackState.value.currentIndex)
    assertEquals(0L, controller.playbackState.value.positionMs)
    assertEquals(PlaybackStatus.PLAYING, controller.playbackState.value.status)

    // Natural track end under RepeatMode.ALL at lastIndex -> wraps to 0
    controller.cycleRepeatMode() // ONE -> OFF
    controller.cycleRepeatMode() // OFF -> ALL
    controller.playTrackAt(2)
    gateway.gatewayListener?.onPlaybackStatusChanged(isPlaying = false, isEnded = true)
    assertEquals(0, controller.playbackState.value.currentIndex)
    assertEquals(PlaybackStatus.PLAYING, controller.playbackState.value.status)

    // Natural track end under RepeatMode.OFF at lastIndex -> stops
    controller.cycleRepeatMode() // ALL -> ONE
    controller.cycleRepeatMode() // ONE -> OFF
    controller.playTrackAt(2)
    gateway.gatewayListener?.onPlaybackStatusChanged(isPlaying = false, isEnded = true)
    assertEquals(PlaybackStatus.STOPPED, controller.playbackState.value.status)
    assertEquals(0L, controller.playbackState.value.positionMs)

    controller.close()
  }

  @Test
  fun queueOperations_preservePlayingTrackIdentity_andSyncGatewayWithoutGlitch() = runTest {
    val store = createPreferencesStore(File(tempFolder.root, "queue.preferences_pb"), backgroundScope)
    val gateway = FakeAudioEngineGateway()
    val controller = Media3AudioEngineController(gateway, store, backgroundScope)
    advanceUntilIdle()

    val a = track("a", title = "Charlie", artist = "Artist", fileName = "03_c.mp3", folderPath = "/z")
    val b = track("b", title = "Alpha", artist = "Artist", fileName = "01_a.mp3", folderPath = "/a")
    val c = track("c", title = "Bravo", artist = "Artist", fileName = "02_b.mp3", folderPath = "/m")
    controller.replaceQueue(listOf(a, b, c), startIndex = 1, autoPlay = true) // playing "b"

    val d = track("d", title = "Delta", artist = "Artist", fileName = "04_d.mp3", folderPath = "/d")
    controller.enqueueTracks(listOf(d))
    assertEquals(listOf("a", "b", "c", "d"), controller.playbackState.value.queue.map { it.id })
    assertEquals(1, controller.playbackState.value.currentIndex)

    // Move track 0 -> 2: "b" shifts from index 1 -> 0
    controller.moveTrack(0, 2)
    assertEquals("b", controller.playbackState.value.currentTrack?.id)
    assertEquals(0, controller.playbackState.value.currentIndex)

    // Sort by TITLE -> Alpha (b), Bravo (c), Charlie (a), Delta (d)
    controller.sortQueue(PlaylistSortOrder.TITLE)
    assertEquals(listOf("b", "c", "a", "d"), controller.playbackState.value.queue.map { it.id })
    assertEquals("b", controller.playbackState.value.currentTrack?.id)
    assertEquals(0, controller.playbackState.value.currentIndex)

    // Reverse -> d, a, c, b
    controller.reverseQueue()
    assertEquals(listOf("d", "a", "c", "b"), controller.playbackState.value.queue.map { it.id })
    assertEquals("b", controller.playbackState.value.currentTrack?.id)
    assertEquals(3, controller.playbackState.value.currentIndex)

    // Remove index 0 ("d") -> "b" stays playing at index 2
    controller.removeIndices(setOf(0))
    assertEquals(listOf("a", "c", "b"), controller.playbackState.value.queue.map { it.id })
    assertEquals("b", controller.playbackState.value.currentTrack?.id)
    assertEquals(2, controller.playbackState.value.currentIndex)

    // Crop to indices {1, 2} -> c, b
    controller.cropIndices(setOf(1, 2))
    assertEquals(listOf("c", "b"), controller.playbackState.value.queue.map { it.id })
    assertEquals("b", controller.playbackState.value.currentTrack?.id)
    assertEquals(1, controller.playbackState.value.currentIndex)

    // Randomize preserves current track identity
    controller.randomizeQueue()
    assertEquals("b", controller.playbackState.value.currentTrack?.id)

    // Clear stops and empties queue
    controller.clearQueue()
    assertTrue(controller.playbackState.value.queue.isEmpty())
    assertEquals(-1, controller.playbackState.value.currentIndex)
    assertEquals(PlaybackStatus.STOPPED, controller.playbackState.value.status)

    controller.close()
  }

  @Test
  fun replayGainAndFormatUpdates_propagateToDspChainAndCurrentTrack() = runTest {
    val store = createPreferencesStore(File(tempFolder.root, "rg.preferences_pb"), backgroundScope)
    val chain = DroidAmpDspChain()
    val gateway = FakeAudioEngineGateway(chain)
    val controller = Media3AudioEngineController(gateway, store, backgroundScope)
    advanceUntilIdle()

    val t0 = track("t0")
    val t1 = track("t1")
    controller.replaceQueue(listOf(t0, t1), startIndex = 0, autoPlay = true)

    controller.setDspSettings(
      DspSettings(
        gaplessEnabled = true,
        crossfadeSeconds = 3,
        replayGainMode = ReplayGainMode.TRACK,
        replayGainPreampDb = 0f,
      ),
    )

    // Parsed tags from Media3 Metadata update ReplayGainProcessor gain (-6 dB -> ~0.501)
    gateway.gatewayListener?.onReplayGainMetadataParsed(
      trackId = "t0",
      metadata = ReplayGainMetadata(trackGainDb = -6.0f, trackPeak = 0.8f),
    )
    assertEquals(0.501187f, chain.replayGainProcessor.currentEffectiveGain(), 0.01f)

    // Explicit setReplayGainMetadata override for active track updates gain immediately
    controller.setReplayGainMetadata(
      trackId = "t0",
      trackGainDb = -12.0f,
      trackPeak = 0.5f,
      albumGainDb = null,
      albumPeak = null,
    )
    assertEquals(0.251188f, chain.replayGainProcessor.currentEffectiveGain(), 0.01f)

    // Transition to t1 cleanly resets ReplayGain to unity (1.0f) so t0's gain never leaks
    controller.next()
    assertEquals(1.0f, chain.replayGainProcessor.currentEffectiveGain(), 0.001f)

    // Live decoded Format updates current track telemetry
    gateway.gatewayListener?.onAudioFormatUpdated(
      bitrateKbps = 984,
      sampleRateHz = 96_000,
      channelMode = ChannelMode.MONO,
      durationMs = 245_000L,
    )
    val updated = controller.playbackState.value.currentTrack!!
    assertEquals(984, updated.bitrateKbps)
    assertEquals(96_000, updated.sampleRateHz)
    assertEquals(96, updated.sampleRateKhz)
    assertEquals(ChannelMode.MONO, updated.channelMode)
    assertEquals(245_000L, updated.durationMs)

    controller.close()
  }

  @Test
  @androidx.annotation.OptIn(UnstableApi::class)
  fun visualizer60fpsPolling_emitsLiveSpectrumAndAutoParksToSilentOnPause() = runTest {
    val store = createPreferencesStore(File(tempFolder.root, "vis.preferences_pb"), backgroundScope)
    val chain = DroidAmpDspChain()
    val gateway = FakeAudioEngineGateway(chain)
    // Pass foreground TestScope (`this`) to prove polling auto-parks cleanly on pause without hanging!
    val controller = Media3AudioEngineController(gateway, store, this)
    controller.awaitInitialHydration()
    advanceUntilIdle()

    // Configure VisualizerTapProcessor with 1kHz PCM_16BIT sine wave
    chain.visualizerTapProcessor.configure(
      AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT),
    )
    chain.visualizerTapProcessor.flush(AudioProcessor.StreamMetadata.DEFAULT)
    val pcmBuffer = ByteBuffer.allocateDirect(2048 * 4).order(ByteOrder.nativeOrder())
    for (i in 0 until 2048) {
      val s = (sin(2.0 * PI * 1000.0 * i / 44_100.0) * 24_000.0).roundToInt().toShort()
      pcmBuffer.putShort(s)
      pcmBuffer.putShort(s)
    }
    pcmBuffer.flip()
    chain.visualizerTapProcessor.queueInput(pcmBuffer)

    controller.replaceQueue(listOf(track("vis")), startIndex = 0, autoPlay = true)
    advanceTimeBy(32L)
    runCurrent()

    val liveFrame = controller.visualizerFrame.value
    assertTrue(liveFrame.spectrumBars.any { it > 0.05f })
    assertTrue(liveFrame.waveform.any { it != 0f })

    // Pausing decays smoothly down to SILENT and auto-parks polling job so advanceUntilIdle() terminates!
    controller.pause()
    advanceUntilIdle()
    assertEquals(VisualizerFrame.SILENT, controller.visualizerFrame.value)

    // Cycling VisualizerMode to OFF clears visualizer immediately
    controller.play()
    advanceTimeBy(32L)
    runCurrent()
    controller.cycleVisualizerMode() // SPECTRUM -> OSCILLOSCOPE
    assertEquals(VisualizerMode.OSCILLOSCOPE, controller.playbackState.value.visualizerMode)
    controller.cycleVisualizerMode() // OSCILLOSCOPE -> OFF
    assertEquals(VisualizerMode.OFF, controller.playbackState.value.visualizerMode)
    assertEquals(VisualizerFrame.SILENT, controller.visualizerFrame.value)

    controller.close()
    advanceUntilIdle()
  }

  @Test
  fun eqAndServiceSnapshotRecovery_updateDspChainAndPreservePlayingVolume() = runTest {
    val store = createPreferencesStore(File(tempFolder.root, "eq.preferences_pb"), backgroundScope)
    val chain = DroidAmpDspChain()
    val gateway = FakeAudioEngineGateway(chain)
    val controller = Media3AudioEngineController(gateway, store, backgroundScope)
    advanceUntilIdle()

    controller.applyEqPreset(ClassicWinampPresets.ROCK)
    assertEquals("ROCK", controller.playbackState.value.equalizer.activePresetName)
    assertEquals(ClassicWinampPresets.ROCK.bandsDb, gateway.lastAppliedEq.bandsDb)

    controller.setEqBandDb(0, 20f) // clamped to 12f
    assertEquals(WinampEqSpec.MAX_DB, controller.playbackState.value.equalizer.bandsDb[0], 0.001f)
    assertEquals("CUSTOM", controller.playbackState.value.equalizer.activePresetName)

    // Simulate reconnecting to an already-PLAYING DroidAmpPlaybackService at volume 35
    gateway.gatewayListener?.onServiceSnapshotRecovered(
      ServiceRuntimeSnapshot(
        status = PlaybackStatus.PLAYING,
        queue = listOf(track("live")),
        currentIndex = 0,
        positionMs = 12_000L,
        volume = 35,
        balance = 15,
        shuffle = true,
        repeatMode = RepeatMode.ALL,
        equalizer = EqualizerState(activePresetName = "POP"),
        dspSettings = DspSettings(crossfadeSeconds = 5),
      ),
    )
    controller.applyColdLaunchVolumeResetIfStopped(204)
    assertEquals(35, controller.playbackState.value.volume)
    assertEquals(PlaybackStatus.PLAYING, controller.playbackState.value.status)
    assertEquals(5, controller.dspSettings.value.crossfadeSeconds)

    controller.close()
  }

  @Test
  fun naturalTrackEnd_repeatOffWithManagedPlaylistTransitionsStopsEvenBeforeLastIndex() = runTest {
    val store = createPreferencesStore(File(tempFolder.root, "managed_end.preferences_pb"), backgroundScope)
    val gateway = FakeAudioEngineGateway(managesPlaylistTransitions = true)
    val controller = Media3AudioEngineController(gateway, store, backgroundScope)
    advanceUntilIdle()

    controller.replaceQueue(listOf(track("t0"), track("t1"), track("t2")), startIndex = 0, autoPlay = true)
    controller.toggleShuffle()
    assertEquals(RepeatMode.OFF, controller.playbackState.value.repeatMode)
    assertTrue(controller.playbackState.value.shuffle)

    // When ExoPlayer finishes the final shuffled track (e.g. index 0 < lastIndex), controller must STOP, not loop.
    gateway.gatewayListener?.onPlaybackStatusChanged(isPlaying = false, isEnded = true)

    assertEquals(PlaybackStatus.STOPPED, controller.playbackState.value.status)
    assertEquals(0L, controller.playbackState.value.positionMs)
    assertEquals(0, gateway.selectTrackCount)

    controller.close()
  }

  @Test
  fun seekTo_emptyQueueIsNoOp() = runTest {
    val store = createPreferencesStore(File(tempFolder.root, "empty_seek.preferences_pb"), backgroundScope)
    val gateway = FakeAudioEngineGateway()
    val controller = Media3AudioEngineController(gateway, store, backgroundScope)
    advanceUntilIdle()

    assertTrue(controller.playbackState.value.queue.isEmpty())
    controller.seekTo(15_000L)

    assertEquals(0L, controller.playbackState.value.positionMs)
    assertEquals(0, gateway.seekToCount)
    assertEquals(0, gateway.cancelCrossfadeCount)

    controller.close()
  }
}
