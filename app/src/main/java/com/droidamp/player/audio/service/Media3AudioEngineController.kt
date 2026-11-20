package com.droidamp.player.audio.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.droidamp.player.audio.dsp.ReplayGainMetadata
import com.droidamp.player.domain.contract.AudioEngineController
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.RepeatMode
import com.droidamp.player.domain.model.TimeDisplayMode
import com.droidamp.player.domain.model.VisualizerFrame
import com.droidamp.player.domain.model.VisualizerMode
import com.droidamp.player.domain.model.WinampEqSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable

enum class RemoteTransportAction {
  PLAY,
  PAUSE,
  STOP,
  NEXT,
  PREVIOUS,
}

data class ServiceRuntimeSnapshot(
  val status: PlaybackStatus,
  val queue: List<AudioTrack>,
  val currentIndex: Int,
  val positionMs: Long,
  val volume: Int,
  val balance: Int,
  val shuffle: Boolean,
  val repeatMode: RepeatMode,
  val equalizer: EqualizerState,
  val dspSettings: DspSettings,
)

/**
 * Pure-Kotlin gateway decoupling [Media3AudioEngineController] from Android's
 * Media3 ExoPlayer/MediaSessionService so 100% of controller logic runs in pure JVM tests.
 */
interface AudioEngineGateway {
  interface Listener {
    fun onPlaybackStatusChanged(isPlaying: Boolean, isEnded: Boolean)
    fun onGaplessTrackChanged(newIndex: Int, trackId: String?)
    fun onAudioFormatUpdated(
      bitrateKbps: Int?,
      sampleRateHz: Int?,
      channelMode: ChannelMode?,
      durationMs: Long?,
    )
    fun onReplayGainMetadataParsed(trackId: String?, metadata: ReplayGainMetadata)
    fun onCrossfadeTrackAdvanced(newIndex: Int, newTrack: AudioTrack)
    fun onRemoteTransportAction(action: RemoteTransportAction)
    fun onRemoteSeekTo(positionMs: Long)
    fun onServiceSnapshotRecovered(snapshot: ServiceRuntimeSnapshot) {}
  }

  /**
   * True when the underlying player manages queue transitions (including shuffled/repeat
   * exhaustion) internally and only emits `isEnded = true` after the full playlist finishes.
   */
  val managesPlaylistTransitions: Boolean
    get() = false

  fun setListener(listener: Listener?)
  fun setQueue(tracks: List<AudioTrack>, startIndex: Int, playWhenReady: Boolean)
  fun syncQueueWithoutInterrupting(tracks: List<AudioTrack>, currentIndex: Int)
  fun selectTrackAt(index: Int, playWhenReady: Boolean)
  fun play()
  fun pause()
  fun stop()
  fun seekTo(positionMs: Long)
  fun setRepeatMode(mode: RepeatMode)
  fun setShuffleEnabled(enabled: Boolean)
  fun applyDspSettings(settings: DspSettings)
  fun applyEqualizerState(state: EqualizerState)
  fun applyVolume(volume0To255: Int)
  fun applyBalance(balanceMinus127To127: Int)
  fun applyReplayGainMetadata(metadata: ReplayGainMetadata)
  fun readVisualizerFrame(isPlaying: Boolean): VisualizerFrame
  fun resetVisualizer()
  fun currentPositionMs(): Long
  fun currentDurationMs(): Long
  fun onCrossfadeProgressTick(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    currentTrack: AudioTrack?,
    nextCandidate: CrossfadeController.NextTrackCandidate?,
  )
  fun cancelCrossfade()
  fun release() {}
}

/**
 * Production [AudioEngineController] backed by [AudioEngineGateway] (connected to
 * [DroidAmpPlaybackService] on Android) and [DspPreferencesStore] for DataStore persistence.
 */
class Media3AudioEngineController(
  private val gateway: AudioEngineGateway,
  private val preferencesStore: DspPreferencesStore,
  private val scope: CoroutineScope,
  private val visualizerPollIntervalMs: Long = VISUALIZER_POLL_INTERVAL_MS,
  private val positionPollIntervalMs: Long = POSITION_POLL_INTERVAL_MS,
) : AudioEngineController, AudioEngineGateway.Listener, Closeable {

  private val _playbackState = MutableStateFlow(PlaybackState())
  override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

  private val _visualizerFrame = MutableStateFlow(VisualizerFrame.SILENT)
  override val visualizerFrame: StateFlow<VisualizerFrame> = _visualizerFrame.asStateFlow()

  private val _dspSettings = MutableStateFlow(DspSettings())
  override val dspSettings: StateFlow<DspSettings> = _dspSettings.asStateFlow()

  private val explicitReplayGainByTrackId = LinkedHashMap<String, ReplayGainMetadata>()
  private var parsedReplayGainForCurrentTrack: ReplayGainMetadata = ReplayGainMetadata.EMPTY

  private var coldLaunchResetApplied = false
  private var volumeModifiedInSession = false
  private var balanceModifiedInSession = false
  private var equalizerModifiedInSession = false
  private var dspSettingsModifiedInSession = false
  private var shuffleModifiedInSession = false
  private var repeatModeModifiedInSession = false
  private var visualizerModeModifiedInSession = false
  private var timeDisplayModeModifiedInSession = false
  private var sessionRecoveredFromRunningService = false

  private var pendingShuffledNextIndex: Int? = null
  private var visualizerJob: Job? = null
  private var positionJob: Job? = null
  private var hydrationJob: Job? = null
  private var closed = false

  init {
    gateway.setListener(this)
    hydrationJob =
      scope.launch {
        val snapshot = preferencesStore.loadSnapshot()
        if (closed) return@launch
        applyHydratedPreferences(snapshot)
      }
  }

  private suspend fun applyHydratedPreferences(snapshot: DspPreferencesSnapshot) {
    if (sessionRecoveredFromRunningService) {
      return
    }

    val current = _playbackState.value
    val isStopped = current.status == PlaybackStatus.STOPPED
    val shouldPreserveRuntimeVolume =
      coldLaunchResetApplied || volumeModifiedInSession || !isStopped

    val resolvedVolume =
      if (shouldPreserveRuntimeVolume) {
        current.volume
      } else {
        snapshot.volume
      }
    val resolvedBalance =
      if (balanceModifiedInSession) current.balance else snapshot.balance
    val resolvedShuffle =
      if (shuffleModifiedInSession) current.shuffle else snapshot.shuffle
    val resolvedRepeat =
      if (repeatModeModifiedInSession) current.repeatMode else snapshot.repeatMode
    val resolvedVisualizer =
      if (visualizerModeModifiedInSession) current.visualizerMode else snapshot.visualizerMode
    val resolvedTimeMode =
      if (timeDisplayModeModifiedInSession) current.timeDisplayMode else snapshot.timeDisplayMode
    val resolvedEq =
      if (equalizerModifiedInSession) current.equalizer else snapshot.equalizer
    val resolvedDsp =
      if (dspSettingsModifiedInSession) _dspSettings.value else snapshot.dspSettings

    _dspSettings.value = resolvedDsp.sanitized()
    _playbackState.update { state ->
      state.copy(
        volume = resolvedVolume,
        balance = resolvedBalance,
        shuffle = resolvedShuffle,
        repeatMode = resolvedRepeat,
        visualizerMode = resolvedVisualizer,
        timeDisplayMode = resolvedTimeMode,
        equalizer = resolvedEq,
      )
    }

    gateway.applyDspSettings(_dspSettings.value)
    gateway.applyEqualizerState(resolvedEq)
    gateway.applyVolume(resolvedVolume)
    gateway.applyBalance(resolvedBalance)
    gateway.setShuffleEnabled(resolvedShuffle)
    gateway.setRepeatMode(resolvedRepeat)

    if (coldLaunchResetApplied && isStopped && snapshot.volume != resolvedVolume) {
      preferencesStore.saveVolume(resolvedVolume)
    }
  }

  override fun play() {
    val state = _playbackState.value
    if (state.queue.isEmpty()) return
    val targetIndex = if (state.currentIndex in state.queue.indices) state.currentIndex else 0
    val indexChanged = targetIndex != state.currentIndex
    _playbackState.update {
      it.copy(status = PlaybackStatus.PLAYING, currentIndex = targetIndex)
    }
    if (indexChanged) {
      resetReplayGainForTrack(state.queue[targetIndex].id)
      gateway.selectTrackAt(targetIndex, playWhenReady = true)
    } else {
      gateway.play()
    }
    ensurePollingJobs()
  }

  override fun pause() {
    gateway.cancelCrossfade()
    val wasPlaying = _playbackState.value.status == PlaybackStatus.PLAYING
    if (!wasPlaying) return
    _playbackState.update { it.copy(status = PlaybackStatus.PAUSED) }
    gateway.pause()
    positionJob?.cancel()
    positionJob = null
    // Keep visualizerJob running until readVisualizerFrame(false) decays to SILENT.
    startVisualizerPollingIfNeeded()
  }

  override fun stop() {
    gateway.cancelCrossfade()
    _playbackState.update { it.copy(status = PlaybackStatus.STOPPED, positionMs = 0L) }
    positionJob?.cancel()
    positionJob = null
    visualizerJob?.cancel()
    visualizerJob = null
    gateway.resetVisualizer()
    _visualizerFrame.value = VisualizerFrame.SILENT
    gateway.stop()
  }

  override fun next() {
    gateway.cancelCrossfade()
    val state = _playbackState.value
    if (state.queue.isEmpty()) return
    val nextIdx = computeManualNextIndex(state)
    pendingShuffledNextIndex = null
    val playWhenReady = state.status == PlaybackStatus.PLAYING
    _playbackState.update { it.copy(currentIndex = nextIdx, positionMs = 0L) }
    resetReplayGainForTrack(state.queue[nextIdx].id)
    gateway.selectTrackAt(nextIdx, playWhenReady = playWhenReady)
  }

  override fun previous() {
    gateway.cancelCrossfade()
    val state = _playbackState.value
    if (state.queue.isEmpty()) return
    val livePos = maxOf(state.positionMs, gateway.currentPositionMs())
    if (livePos > PREVIOUS_RESTART_THRESHOLD_MS) {
      _playbackState.update { it.copy(positionMs = 0L) }
      gateway.seekTo(0L)
      return
    }
    val prevIdx = if (state.currentIndex <= 0) state.queue.lastIndex else state.currentIndex - 1
    pendingShuffledNextIndex = null
    val playWhenReady = state.status == PlaybackStatus.PLAYING
    _playbackState.update { it.copy(currentIndex = prevIdx, positionMs = 0L) }
    resetReplayGainForTrack(state.queue[prevIdx].id)
    gateway.selectTrackAt(prevIdx, playWhenReady = playWhenReady)
  }

  override fun seekTo(positionMs: Long) {
    val state = _playbackState.value
    if (state.queue.isEmpty()) return
    gateway.cancelCrossfade()
    val duration = maxOf(state.durationMs, gateway.currentDurationMs()).coerceAtLeast(0L)
    val clamped =
      if (duration > 0L) {
        positionMs.coerceIn(0L, duration)
      } else {
        positionMs.coerceAtLeast(0L)
      }
    _playbackState.update { it.copy(positionMs = clamped) }
    gateway.seekTo(clamped)
  }

  override fun playTrackAt(index: Int) {
    gateway.cancelCrossfade()
    val state = _playbackState.value
    if (index !in state.queue.indices) return
    pendingShuffledNextIndex = null
    _playbackState.update {
      it.copy(currentIndex = index, positionMs = 0L, status = PlaybackStatus.PLAYING)
    }
    resetReplayGainForTrack(state.queue[index].id)
    gateway.selectTrackAt(index, playWhenReady = true)
    ensurePollingJobs()
  }

  override fun replaceQueue(
    tracks: List<AudioTrack>,
    startIndex: Int,
    autoPlay: Boolean,
  ) {
    gateway.cancelCrossfade()
    pendingShuffledNextIndex = null
    if (tracks.isEmpty()) {
      _playbackState.update {
        it.copy(
          queue = emptyList(),
          currentIndex = -1,
          positionMs = 0L,
          status = PlaybackStatus.STOPPED,
        )
      }
      positionJob?.cancel()
      positionJob = null
      visualizerJob?.cancel()
      visualizerJob = null
      gateway.resetVisualizer()
      _visualizerFrame.value = VisualizerFrame.SILENT
      parsedReplayGainForCurrentTrack = ReplayGainMetadata.EMPTY
      gateway.clearReplayGainIfEmpty()
      gateway.setQueue(emptyList(), -1, false)
      return
    }

    val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
    val nextStatus = if (autoPlay) PlaybackStatus.PLAYING else PlaybackStatus.STOPPED
    _playbackState.update {
      it.copy(
        queue = tracks,
        currentIndex = safeIndex,
        positionMs = 0L,
        status = nextStatus,
      )
    }
    resetReplayGainForTrack(tracks[safeIndex].id)
    gateway.setQueue(tracks, safeIndex, autoPlay)
    if (autoPlay) {
      ensurePollingJobs()
    } else {
      positionJob?.cancel()
      positionJob = null
    }
  }

  override fun enqueueTracks(tracks: List<AudioTrack>) {
    if (tracks.isEmpty()) return
    var updatedQueue: List<AudioTrack> = emptyList()
    var updatedIndex = -1
    var wasUnindexed = false
    _playbackState.update { state ->
      val newQueue = state.queue + tracks
      val newIndex = if (state.currentIndex < 0) 0 else state.currentIndex
      wasUnindexed = state.currentIndex < 0
      updatedQueue = newQueue
      updatedIndex = newIndex
      state.copy(queue = newQueue, currentIndex = newIndex)
    }
    if (wasUnindexed && updatedQueue.isNotEmpty()) {
      resetReplayGainForTrack(updatedQueue[updatedIndex].id)
      gateway.setQueue(updatedQueue, updatedIndex, playWhenReady = false)
    } else {
      gateway.syncQueueWithoutInterrupting(updatedQueue, updatedIndex)
    }
  }

  override fun removeIndices(indices: Set<Int>) {
    if (indices.isEmpty()) return
    var previousCurrentId: String? = null
    var newQueue: List<AudioTrack> = emptyList()
    var newIndex = -1
    var newStatus = PlaybackStatus.STOPPED
    var trackReplaced = false

    _playbackState.update { state ->
      previousCurrentId = state.currentTrack?.id
      val kept = state.queue.filterIndexed { idx, _ -> idx !in indices }
      if (kept.isEmpty()) {
        newQueue = emptyList()
        newIndex = -1
        newStatus = PlaybackStatus.STOPPED
        trackReplaced = true
        state.copy(
          queue = emptyList(),
          currentIndex = -1,
          positionMs = 0L,
          status = PlaybackStatus.STOPPED,
        )
      } else {
        val preservedIndex = kept.indexOfFirst { it.id == previousCurrentId }
        val resolvedIndex = if (preservedIndex >= 0) preservedIndex else 0
        trackReplaced = preservedIndex < 0
        newQueue = kept
        newIndex = resolvedIndex
        newStatus = state.status
        state.copy(
          queue = kept,
          currentIndex = resolvedIndex,
          positionMs = if (trackReplaced) 0L else state.positionMs,
        )
      }
    }

    pendingShuffledNextIndex = null
    if (newQueue.isEmpty()) {
      gateway.cancelCrossfade()
      positionJob?.cancel()
      positionJob = null
      visualizerJob?.cancel()
      visualizerJob = null
      gateway.resetVisualizer()
      _visualizerFrame.value = VisualizerFrame.SILENT
      parsedReplayGainForCurrentTrack = ReplayGainMetadata.EMPTY
      gateway.clearReplayGainIfEmpty()
      gateway.setQueue(emptyList(), -1, false)
    } else if (trackReplaced) {
      gateway.cancelCrossfade()
      resetReplayGainForTrack(newQueue[newIndex].id)
      gateway.setQueue(newQueue, newIndex, playWhenReady = newStatus == PlaybackStatus.PLAYING)
    } else {
      gateway.syncQueueWithoutInterrupting(newQueue, newIndex)
    }
  }

  override fun cropIndices(indices: Set<Int>) {
    if (indices.isEmpty()) return
    val all = _playbackState.value.queue.indices.toSet()
    removeIndices(all - indices)
  }

  override fun clearQueue() {
    stop()
    pendingShuffledNextIndex = null
    _playbackState.update { it.copy(queue = emptyList(), currentIndex = -1, positionMs = 0L) }
    parsedReplayGainForCurrentTrack = ReplayGainMetadata.EMPTY
    gateway.clearReplayGainIfEmpty()
    gateway.setQueue(emptyList(), -1, false)
  }

  override fun moveTrack(
    fromIndex: Int,
    toIndex: Int,
  ) {
    var movedQueue: List<AudioTrack>? = null
    var newIndex = -1
    _playbackState.update { state ->
      if (fromIndex !in state.queue.indices || toIndex !in state.queue.indices || fromIndex == toIndex) {
        return@update state
      }
      val mutable = state.queue.toMutableList()
      val moved = mutable.removeAt(fromIndex)
      mutable.add(toIndex, moved)
      val adjustedIndex =
        when {
          state.currentIndex == fromIndex -> toIndex
          fromIndex < state.currentIndex && toIndex >= state.currentIndex -> state.currentIndex - 1
          fromIndex > state.currentIndex && toIndex <= state.currentIndex -> state.currentIndex + 1
          else -> state.currentIndex
        }
      movedQueue = mutable
      newIndex = adjustedIndex
      state.copy(queue = mutable, currentIndex = adjustedIndex)
    }
    movedQueue?.let {
      pendingShuffledNextIndex = null
      gateway.syncQueueWithoutInterrupting(it, newIndex)
    }
  }

  override fun sortQueue(order: PlaylistSortOrder) {
    var updatedQueue: List<AudioTrack> = emptyList()
    var updatedIndex = -1
    _playbackState.update { state ->
      val currentId = state.currentTrack?.id
      val sorted =
        when (order) {
          PlaylistSortOrder.TITLE -> state.queue.sortedBy { it.displayLabel.lowercase() }
          PlaylistSortOrder.FILENAME -> state.queue.sortedBy { it.fileName.lowercase() }
          PlaylistSortOrder.PATH ->
            state.queue.sortedBy { "${it.folderPath}/${it.fileName}".lowercase() }
        }
      val newIdx = sorted.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else state.currentIndex }
      updatedQueue = sorted
      updatedIndex = newIdx
      state.copy(queue = sorted, currentIndex = newIdx)
    }
    pendingShuffledNextIndex = null
    gateway.syncQueueWithoutInterrupting(updatedQueue, updatedIndex)
  }

  override fun reverseQueue() {
    var updatedQueue: List<AudioTrack> = emptyList()
    var updatedIndex = -1
    _playbackState.update { state ->
      val currentId = state.currentTrack?.id
      val reversed = state.queue.reversed()
      val newIdx = reversed.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else state.currentIndex }
      updatedQueue = reversed
      updatedIndex = newIdx
      state.copy(queue = reversed, currentIndex = newIdx)
    }
    pendingShuffledNextIndex = null
    gateway.syncQueueWithoutInterrupting(updatedQueue, updatedIndex)
  }

  override fun randomizeQueue() {
    var updatedQueue: List<AudioTrack>? = null
    var updatedIndex = -1
    _playbackState.update { state ->
      if (state.queue.size <= 1) return@update state
      val currentId = state.currentTrack?.id
      val shuffled = state.queue.shuffled()
      val newIdx = shuffled.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else 0 }
      updatedQueue = shuffled
      updatedIndex = newIdx
      state.copy(queue = shuffled, currentIndex = newIdx)
    }
    updatedQueue?.let {
      pendingShuffledNextIndex = null
      gateway.syncQueueWithoutInterrupting(it, updatedIndex)
    }
  }

  override fun setVolume(volume0To255: Int) {
    val clamped = volume0To255.coerceIn(PlaybackState.MIN_VOLUME, PlaybackState.MAX_VOLUME)
    volumeModifiedInSession = true
    _playbackState.update { it.copy(volume = clamped) }
    gateway.applyVolume(clamped)
    persistAsync { preferencesStore.saveVolume(clamped) }
  }

  override fun setBalance(balanceMinus127To127: Int) {
    val clamped = balanceMinus127To127.coerceIn(PlaybackState.MIN_BALANCE, PlaybackState.MAX_BALANCE)
    balanceModifiedInSession = true
    _playbackState.update { it.copy(balance = clamped) }
    gateway.applyBalance(clamped)
    persistAsync { preferencesStore.saveBalance(clamped) }
  }

  /**
   * ADR-007: Reset internal DSP volume ONLY once per fresh cold launch when playback is STOPPED.
   * Never resets while audio is PLAYING or PAUSED, and guards against late DataStore hydration.
   */
  override fun applyColdLaunchVolumeResetIfStopped(defaultVolume: Int) {
    if (coldLaunchResetApplied) return
    coldLaunchResetApplied = true

    val clamped = defaultVolume.coerceIn(PlaybackState.MIN_VOLUME, PlaybackState.MAX_VOLUME)
    var applied = false
    _playbackState.update { state ->
      if (state.status == PlaybackStatus.STOPPED) {
        applied = true
        state.copy(volume = clamped)
      } else {
        state
      }
    }
    if (applied) {
      gateway.applyVolume(clamped)
      persistAsync { preferencesStore.saveVolume(clamped) }
    }
  }

  override fun setEqEnabled(enabled: Boolean) {
    equalizerModifiedInSession = true
    var nextEq: EqualizerState? = null
    _playbackState.update {
      val updated = it.equalizer.copy(enabled = enabled)
      nextEq = updated
      it.copy(equalizer = updated)
    }
    nextEq?.let { eq ->
      gateway.applyEqualizerState(eq)
      persistAsync { preferencesStore.saveEqualizerState(eq) }
    }
  }

  override fun setEqAutoEnabled(enabled: Boolean) {
    equalizerModifiedInSession = true
    var nextEq: EqualizerState? = null
    _playbackState.update {
      val updated = it.equalizer.copy(autoEnabled = enabled)
      nextEq = updated
      it.copy(equalizer = updated)
    }
    nextEq?.let { eq ->
      persistAsync { preferencesStore.saveEqualizerState(eq) }
    }
  }

  override fun setPreampDb(db: Float) {
    val safeDb = if (db.isFinite()) WinampEqSpec.clampDb(db) else 0f
    equalizerModifiedInSession = true
    var nextEq: EqualizerState? = null
    _playbackState.update {
      val updated = it.equalizer.copy(preampDb = safeDb, activePresetName = "CUSTOM")
      nextEq = updated
      it.copy(equalizer = updated)
    }
    nextEq?.let { eq ->
      gateway.applyEqualizerState(eq)
      persistAsync { preferencesStore.saveEqualizerState(eq) }
    }
  }

  override fun setEqBandDb(
    bandIndex: Int,
    db: Float,
  ) {
    if (bandIndex !in 0 until WinampEqSpec.BAND_COUNT) return
    val safeDb = if (db.isFinite()) WinampEqSpec.clampDb(db) else 0f
    equalizerModifiedInSession = true
    var nextEq: EqualizerState? = null
    _playbackState.update { state ->
      val updatedBands = state.equalizer.bandsDb.toMutableList()
      updatedBands[bandIndex] = safeDb
      val updated = state.equalizer.copy(bandsDb = updatedBands, activePresetName = "CUSTOM")
      nextEq = updated
      state.copy(equalizer = updated)
    }
    nextEq?.let { eq ->
      gateway.applyEqualizerState(eq)
      persistAsync { preferencesStore.saveEqualizerState(eq) }
    }
  }

  override fun setEqBands(bandsDb: List<Float>) {
    if (bandsDb.size != WinampEqSpec.BAND_COUNT) return
    val safeBands = bandsDb.map { if (it.isFinite()) WinampEqSpec.clampDb(it) else 0f }
    equalizerModifiedInSession = true
    var nextEq: EqualizerState? = null
    _playbackState.update { state ->
      val updated = state.equalizer.copy(bandsDb = safeBands, activePresetName = "CUSTOM")
      nextEq = updated
      state.copy(equalizer = updated)
    }
    nextEq?.let { eq ->
      gateway.applyEqualizerState(eq)
      persistAsync { preferencesStore.saveEqualizerState(eq) }
    }
  }

  override fun applyEqPreset(preset: EqPreset) {
    val safePreamp = if (preset.preampDb.isFinite()) WinampEqSpec.clampDb(preset.preampDb) else 0f
    val safeBands = preset.bandsDb.map { if (it.isFinite()) WinampEqSpec.clampDb(it) else 0f }
    equalizerModifiedInSession = true
    var nextEq: EqualizerState? = null
    _playbackState.update { state ->
      val updated =
        state.equalizer.copy(
          preampDb = safePreamp,
          bandsDb = safeBands,
          activePresetName = preset.name,
        )
      nextEq = updated
      state.copy(equalizer = updated)
    }
    nextEq?.let { eq ->
      gateway.applyEqualizerState(eq)
      persistAsync { preferencesStore.saveEqualizerState(eq) }
    }
  }

  override fun toggleShuffle() {
    shuffleModifiedInSession = true
    pendingShuffledNextIndex = null
    var nextShuffle = false
    _playbackState.update {
      nextShuffle = !it.shuffle
      it.copy(shuffle = nextShuffle)
    }
    gateway.setShuffleEnabled(nextShuffle)
    persistAsync { preferencesStore.saveShuffle(nextShuffle) }
  }

  override fun cycleRepeatMode() {
    repeatModeModifiedInSession = true
    var nextMode = RepeatMode.OFF
    _playbackState.update { state ->
      nextMode =
        when (state.repeatMode) {
          RepeatMode.OFF -> RepeatMode.ALL
          RepeatMode.ALL -> RepeatMode.ONE
          RepeatMode.ONE -> RepeatMode.OFF
        }
      state.copy(repeatMode = nextMode)
    }
    gateway.setRepeatMode(nextMode)
    persistAsync { preferencesStore.saveRepeatMode(nextMode) }
  }

  override fun toggleTimeDisplayMode() {
    timeDisplayModeModifiedInSession = true
    var nextMode = TimeDisplayMode.ELAPSED
    _playbackState.update { state ->
      nextMode =
        if (state.timeDisplayMode == TimeDisplayMode.ELAPSED) {
          TimeDisplayMode.REMAINING
        } else {
          TimeDisplayMode.ELAPSED
        }
      state.copy(timeDisplayMode = nextMode)
    }
    persistAsync { preferencesStore.saveTimeDisplayMode(nextMode) }
  }

  override fun cycleVisualizerMode() {
    visualizerModeModifiedInSession = true
    var nextMode = VisualizerMode.SPECTRUM
    _playbackState.update { state ->
      nextMode =
        when (state.visualizerMode) {
          VisualizerMode.SPECTRUM -> VisualizerMode.OSCILLOSCOPE
          VisualizerMode.OSCILLOSCOPE -> VisualizerMode.OFF
          VisualizerMode.OFF -> VisualizerMode.SPECTRUM
        }
      state.copy(visualizerMode = nextMode)
    }
    if (nextMode == VisualizerMode.OFF) {
      visualizerJob?.cancel()
      visualizerJob = null
      gateway.resetVisualizer()
      _visualizerFrame.value = VisualizerFrame.SILENT
    } else if (_playbackState.value.status == PlaybackStatus.PLAYING) {
      startVisualizerPollingIfNeeded()
    }
    persistAsync { preferencesStore.saveVisualizerMode(nextMode) }
  }

  override fun setDspSettings(settings: DspSettings) {
    val sanitized = settings.sanitized()
    dspSettingsModifiedInSession = true
    _dspSettings.value = sanitized
    gateway.applyDspSettings(sanitized)
    persistAsync { preferencesStore.saveDspSettings(sanitized) }
  }

  override fun setReplayGainMetadata(
    trackId: String,
    trackGainDb: Float?,
    trackPeak: Float?,
    albumGainDb: Float?,
    albumPeak: Float?,
  ) {
    val overrideMeta =
      ReplayGainMetadata(
        trackGainDb = trackGainDb?.takeIf { it.isFinite() },
        trackPeak = trackPeak?.takeIf { it.isFinite() && it > 0f },
        albumGainDb = albumGainDb?.takeIf { it.isFinite() },
        albumPeak = albumPeak?.takeIf { it.isFinite() && it > 0f },
      )
    explicitReplayGainByTrackId[trackId] = overrideMeta
    if (_playbackState.value.currentTrack?.id == trackId) {
      gateway.applyReplayGainMetadata(mergeReplayGain(parsedReplayGainForCurrentTrack, overrideMeta))
    }
  }

  // --- AudioEngineGateway.Listener Callbacks ---

  override fun onPlaybackStatusChanged(
    isPlaying: Boolean,
    isEnded: Boolean,
  ) {
    if (isEnded) {
      handleNaturalTrackEnd()
      return
    }
    val currentStatus = _playbackState.value.status
    if (isPlaying && currentStatus != PlaybackStatus.PLAYING) {
      _playbackState.update { it.copy(status = PlaybackStatus.PLAYING) }
      ensurePollingJobs()
    } else if (!isPlaying && currentStatus == PlaybackStatus.PLAYING) {
      _playbackState.update { it.copy(status = PlaybackStatus.PAUSED) }
      positionJob?.cancel()
      positionJob = null
      startVisualizerPollingIfNeeded()
    }
  }

  override fun onGaplessTrackChanged(
    newIndex: Int,
    trackId: String?,
  ) {
    val state = _playbackState.value
    val resolvedIndex =
      when {
        newIndex in state.queue.indices &&
          (trackId == null || state.queue[newIndex].id == trackId) -> newIndex
        trackId != null -> state.queue.indexOfFirst { it.id == trackId }
        else -> -1
      }
    if (resolvedIndex !in state.queue.indices) return
    if (resolvedIndex == state.currentIndex && state.positionMs < 1_000L) {
      // Ensure ReplayGain still resets on RepeatMode.ONE loop or fresh transition.
      resetReplayGainForTrack(state.queue[resolvedIndex].id)
      return
    }
    pendingShuffledNextIndex = null
    _playbackState.update { it.copy(currentIndex = resolvedIndex, positionMs = 0L) }
    resetReplayGainForTrack(state.queue[resolvedIndex].id)
  }

  override fun onAudioFormatUpdated(
    bitrateKbps: Int?,
    sampleRateHz: Int?,
    channelMode: ChannelMode?,
    durationMs: Long?,
  ) {
    _playbackState.update { state ->
      val idx = state.currentIndex
      val track = state.queue.getOrNull(idx) ?: return@update state
      val nextBitrate = bitrateKbps?.takeIf { it > 0 } ?: track.bitrateKbps
      val nextSampleRate = sampleRateHz?.takeIf { it > 0 } ?: track.sampleRateHz
      val nextChannelMode = channelMode ?: track.channelMode
      val nextDuration =
        if (durationMs != null && durationMs > 0L && (track.durationMs <= 0L || durationMs != track.durationMs)) {
          durationMs
        } else {
          track.durationMs
        }

      if (
        nextBitrate == track.bitrateKbps &&
        nextSampleRate == track.sampleRateHz &&
        nextChannelMode == track.channelMode &&
        nextDuration == track.durationMs
      ) {
        return@update state
      }

      val updatedQueue = state.queue.toMutableList()
      updatedQueue[idx] =
        track.copy(
          bitrateKbps = nextBitrate,
          sampleRateHz = nextSampleRate,
          channelMode = nextChannelMode,
          durationMs = nextDuration,
        )
      state.copy(queue = updatedQueue)
    }
  }

  override fun onReplayGainMetadataParsed(
    trackId: String?,
    metadata: ReplayGainMetadata,
  ) {
    val activeTrackId = _playbackState.value.currentTrack?.id ?: return
    if (trackId != null && trackId != activeTrackId) return

    parsedReplayGainForCurrentTrack = mergeReplayGain(parsedReplayGainForCurrentTrack, metadata)
    val explicit = explicitReplayGainByTrackId[activeTrackId]
    gateway.applyReplayGainMetadata(mergeReplayGain(parsedReplayGainForCurrentTrack, explicit))
  }

  override fun onCrossfadeTrackAdvanced(
    newIndex: Int,
    newTrack: AudioTrack,
  ) {
    pendingShuffledNextIndex = null
    val state = _playbackState.value
    val resolvedIndex =
      if (newIndex in state.queue.indices && state.queue[newIndex].id == newTrack.id) {
        newIndex
      } else {
        state.queue.indexOfFirst { it.id == newTrack.id }
      }
    if (resolvedIndex !in state.queue.indices) return
    _playbackState.update {
      it.copy(
        currentIndex = resolvedIndex,
        positionMs = 0L,
        status = PlaybackStatus.PLAYING,
      )
    }
    resetReplayGainForTrack(newTrack.id)
  }

  override fun onRemoteTransportAction(action: RemoteTransportAction) {
    when (action) {
      RemoteTransportAction.PLAY -> play()
      RemoteTransportAction.PAUSE -> pause()
      RemoteTransportAction.STOP -> stop()
      RemoteTransportAction.NEXT -> next()
      RemoteTransportAction.PREVIOUS -> previous()
    }
  }

  override fun onRemoteSeekTo(positionMs: Long) {
    seekTo(positionMs)
  }

  override fun onServiceSnapshotRecovered(snapshot: ServiceRuntimeSnapshot) {
    if (snapshot.status == PlaybackStatus.STOPPED && snapshot.queue.isEmpty()) return
    sessionRecoveredFromRunningService = true
    _dspSettings.value = snapshot.dspSettings.sanitized()
    _playbackState.update { current ->
      current.copy(
        status = snapshot.status,
        queue = snapshot.queue,
        currentIndex = snapshot.currentIndex,
        positionMs = snapshot.positionMs,
        volume = snapshot.volume,
        balance = snapshot.balance,
        shuffle = snapshot.shuffle,
        repeatMode = snapshot.repeatMode,
        equalizer = snapshot.equalizer,
      )
    }
    if (snapshot.status == PlaybackStatus.PLAYING) {
      ensurePollingJobs()
    }
  }

  private fun handleNaturalTrackEnd() {
    val state = _playbackState.value
    if (state.queue.isEmpty()) {
      stop()
      return
    }
    when (state.repeatMode) {
      RepeatMode.ONE -> {
        _playbackState.update { it.copy(positionMs = 0L, status = PlaybackStatus.PLAYING) }
        resetReplayGainForTrack(state.currentTrack?.id)
        gateway.seekTo(0L)
        gateway.play()
        ensurePollingJobs()
      }
      RepeatMode.ALL -> {
        val nextIdx = computeManualNextIndex(state)
        pendingShuffledNextIndex = null
        _playbackState.update {
          it.copy(currentIndex = nextIdx, positionMs = 0L, status = PlaybackStatus.PLAYING)
        }
        resetReplayGainForTrack(state.queue[nextIdx].id)
        gateway.selectTrackAt(nextIdx, playWhenReady = true)
        ensurePollingJobs()
      }
      RepeatMode.OFF -> {
        if (!gateway.managesPlaylistTransitions && state.currentIndex < state.queue.lastIndex) {
          val nextIdx = computeManualNextIndex(state)
          pendingShuffledNextIndex = null
          _playbackState.update {
            it.copy(currentIndex = nextIdx, positionMs = 0L, status = PlaybackStatus.PLAYING)
          }
          resetReplayGainForTrack(state.queue[nextIdx].id)
          gateway.selectTrackAt(nextIdx, playWhenReady = true)
          ensurePollingJobs()
        } else {
          stop()
        }
      }
    }
  }

  private fun computeManualNextIndex(state: PlaybackState): Int {
    if (state.queue.isEmpty()) return -1
    if (state.shuffle && state.queue.size > 1) {
      val preselected =
        pendingShuffledNextIndex?.takeIf { it in state.queue.indices && it != state.currentIndex }
      if (preselected != null) return preselected
      val candidates = state.queue.indices.filter { it != state.currentIndex }
      return candidates.random()
    }
    return (state.currentIndex + 1) % state.queue.size
  }

  private fun resolveNextCandidateForCrossfade(state: PlaybackState): CrossfadeController.NextTrackCandidate? {
    if (state.shuffle && state.queue.size > 1 && state.repeatMode != RepeatMode.ONE) {
      if (pendingShuffledNextIndex !in state.queue.indices || pendingShuffledNextIndex == state.currentIndex) {
        pendingShuffledNextIndex = state.queue.indices.filter { it != state.currentIndex }.random()
      }
    } else {
      pendingShuffledNextIndex = null
    }
    return CrossfadeController.resolveNextCandidate(
      queue = state.queue,
      currentIndex = state.currentIndex,
      repeatMode = state.repeatMode,
      shuffle = state.shuffle,
      shuffledNextIndex = pendingShuffledNextIndex,
    )
  }

  private fun resetReplayGainForTrack(trackId: String?) {
    parsedReplayGainForCurrentTrack = ReplayGainMetadata.EMPTY
    val overrideMeta = trackId?.let { explicitReplayGainByTrackId[it] } ?: ReplayGainMetadata.EMPTY
    gateway.applyReplayGainMetadata(overrideMeta)
  }

  private fun AudioEngineGateway.clearReplayGainIfEmpty() {
    applyReplayGainMetadata(ReplayGainMetadata.EMPTY)
  }

  private fun mergeReplayGain(
    base: ReplayGainMetadata,
    override: ReplayGainMetadata?,
  ): ReplayGainMetadata {
    if (override == null) return base
    return ReplayGainMetadata(
      trackGainDb = override.trackGainDb ?: base.trackGainDb,
      trackPeak = override.trackPeak ?: base.trackPeak,
      albumGainDb = override.albumGainDb ?: base.albumGainDb,
      albumPeak = override.albumPeak ?: base.albumPeak,
    )
  }

  private fun ensurePollingJobs() {
    startPositionPollingIfNeeded()
    startVisualizerPollingIfNeeded()
  }

  private fun startPositionPollingIfNeeded() {
    if (closed || positionJob?.isActive == true) return
    positionJob =
      scope.launch {
        while (isActive && !closed) {
          val state = _playbackState.value
          if (state.status != PlaybackStatus.PLAYING) {
            break
          }
          val livePos = gateway.currentPositionMs().coerceAtLeast(0L)
          val liveDur =
            maxOf(state.durationMs, gateway.currentDurationMs()).coerceAtLeast(0L)
          val clampedPos = if (liveDur > 0L) livePos.coerceAtMost(liveDur) else livePos
          if (clampedPos != state.positionMs) {
            _playbackState.update { it.copy(positionMs = clampedPos) }
          }
          val dsp = _dspSettings.value
          if (dsp.crossfadeSeconds > 0) {
            val nextCandidate = resolveNextCandidateForCrossfade(state)
            gateway.onCrossfadeProgressTick(
              isPlaying = true,
              positionMs = clampedPos,
              durationMs = liveDur,
              currentTrack = state.currentTrack,
              nextCandidate = nextCandidate,
            )
          }
          delay(positionPollIntervalMs)
        }
        positionJob = null
      }
  }

  private fun startVisualizerPollingIfNeeded() {
    if (closed || visualizerJob?.isActive == true) return
    if (_playbackState.value.visualizerMode == VisualizerMode.OFF) {
      _visualizerFrame.value = VisualizerFrame.SILENT
      return
    }
    visualizerJob =
      scope.launch {
        while (isActive && !closed) {
          val state = _playbackState.value
          if (state.visualizerMode == VisualizerMode.OFF) {
            gateway.resetVisualizer()
            _visualizerFrame.value = VisualizerFrame.SILENT
            break
          }
          val isPlaying = state.status == PlaybackStatus.PLAYING
          val frame = gateway.readVisualizerFrame(isPlaying)
          _visualizerFrame.value = frame
          if (!isPlaying && frame == VisualizerFrame.SILENT) {
            // Auto-park cleanly once decayed to SILENT so background/tests don't spin idle loops.
            break
          }
          delay(visualizerPollIntervalMs)
        }
        visualizerJob = null
      }
  }

  private fun persistAsync(block: suspend () -> Unit) {
    if (closed) return
    scope.launch { block() }
  }

  internal suspend fun awaitInitialHydration() {
    hydrationJob?.join()
  }

  fun release() {
    close()
  }

  override fun close() {
    if (closed) return
    closed = true
    hydrationJob?.cancel()
    positionJob?.cancel()
    visualizerJob?.cancel()
    hydrationJob = null
    positionJob = null
    visualizerJob = null
    gateway.cancelCrossfade()
    gateway.setListener(null)
    gateway.release()
  }

  companion object {
    const val VISUALIZER_POLL_INTERVAL_MS = 16L
    const val POSITION_POLL_INTERVAL_MS = 100L
    const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L

    fun create(
      context: Context,
      scope: CoroutineScope,
      preferencesStore: DspPreferencesStore = DspPreferencesStore.create(context),
    ): Media3AudioEngineController {
      val gateway = ServiceBindingAudioEngineGateway(context.applicationContext)
      return Media3AudioEngineController(
        gateway = gateway,
        preferencesStore = preferencesStore,
        scope = scope,
      )
    }
  }
}

/**
 * Process-local [AudioEngineGateway] bridging [Media3AudioEngineController] to
 * [DroidAmpPlaybackService.LocalBinder], queueing pre-bind DSP/queue commands safely
 * and adopting running service state on reconnect.
 */
internal class ServiceBindingAudioEngineGateway(
  private val context: Context,
) : AudioEngineGateway, AudioEngineGateway.Listener {

  override val managesPlaylistTransitions: Boolean
    get() = true

  private var controllerListener: AudioEngineGateway.Listener? = null
  private var boundService: DroidAmpPlaybackService? = null
  private var serviceDelegate: AudioEngineGateway? = null
  private var bindRequested = false
  private var released = false

  // Cached latest state for pre-bind calls
  private var pendingDspSettings: DspSettings? = null
  private var pendingEqState: EqualizerState? = null
  private var pendingVolume: Int? = null
  private var pendingBalance: Int? = null
  private var pendingRepeatMode: RepeatMode? = null
  private var pendingShuffle: Boolean? = null
  private var pendingReplayGain: ReplayGainMetadata? = null
  private var pendingQueue: Triple<List<AudioTrack>, Int, Boolean>? = null
  private var pendingSeekMs: Long? = null
  private var pendingPlayRequest = false

  private val serviceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(
        name: ComponentName?,
        service: IBinder?,
      ) {
        val binder = service as? DroidAmpPlaybackService.LocalBinder ?: return
        if (released) return
        val playbackService = binder.service
        val gateway = binder.gateway
        boundService = playbackService
        serviceDelegate = gateway
        gateway.setListener(this@ServiceBindingAudioEngineGateway)

        val snapshot = playbackService.currentRuntimeSnapshot()
        if (snapshot != null && (snapshot.status != PlaybackStatus.STOPPED || snapshot.queue.isNotEmpty())) {
          controllerListener?.onServiceSnapshotRecovered(snapshot)
        } else {
          flushPendingStateTo(gateway)
        }
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        serviceDelegate?.setListener(null)
        serviceDelegate = null
        boundService = null
      }
    }

  init {
    ensureBound()
  }

  private fun ensureBound() {
    if (released || bindRequested) return
    val intent =
      Intent(context, DroidAmpPlaybackService::class.java)
        .setAction(DroidAmpPlaybackService.ACTION_LOCAL_BIND)
    bindRequested = runCatching {
      context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }.getOrDefault(false)
  }

  private fun ensureForegroundServiceStarted() {
    // MediaSessionService's MediaNotificationManager promotes the service via
    // startForegroundService() + startForeground() once the registered session starts playing.
    ensureBound()
  }

  private fun flushPendingStateTo(gateway: AudioEngineGateway) {
    pendingDspSettings?.let(gateway::applyDspSettings)
    pendingEqState?.let(gateway::applyEqualizerState)
    pendingVolume?.let(gateway::applyVolume)
    pendingBalance?.let(gateway::applyBalance)
    pendingRepeatMode?.let(gateway::setRepeatMode)
    pendingShuffle?.let(gateway::setShuffleEnabled)
    pendingReplayGain?.let(gateway::applyReplayGainMetadata)
    pendingQueue?.let { (tracks, startIdx, autoPlay) ->
      if (autoPlay) ensureForegroundServiceStarted()
      gateway.setQueue(tracks, startIdx, autoPlay)
    }
    pendingSeekMs?.let(gateway::seekTo)
    if (pendingPlayRequest) {
      ensureForegroundServiceStarted()
      gateway.play()
    }
    pendingQueue = null
    pendingSeekMs = null
    pendingPlayRequest = false
  }

  override fun setListener(listener: AudioEngineGateway.Listener?) {
    controllerListener = listener
  }

  override fun setQueue(
    tracks: List<AudioTrack>,
    startIndex: Int,
    playWhenReady: Boolean,
  ) {
    if (playWhenReady && tracks.isNotEmpty()) {
      ensureForegroundServiceStarted()
    }
    val delegate = serviceDelegate
    if (delegate != null) {
      delegate.setQueue(tracks, startIndex, playWhenReady)
    } else {
      pendingQueue = Triple(tracks, startIndex, playWhenReady)
      pendingSeekMs = null
      pendingPlayRequest = false
      ensureBound()
    }
  }

  override fun syncQueueWithoutInterrupting(
    tracks: List<AudioTrack>,
    currentIndex: Int,
  ) {
    val delegate = serviceDelegate
    if (delegate != null) {
      delegate.syncQueueWithoutInterrupting(tracks, currentIndex)
    } else {
      val preserveAutoPlay = pendingQueue?.third == true && tracks.isNotEmpty()
      pendingQueue = Triple(tracks, currentIndex, preserveAutoPlay)
    }
  }

  override fun selectTrackAt(
    index: Int,
    playWhenReady: Boolean,
  ) {
    if (playWhenReady) ensureForegroundServiceStarted()
    serviceDelegate?.selectTrackAt(index, playWhenReady) ?: run {
      pendingQueue = pendingQueue?.let { Triple(it.first, index, playWhenReady) }
      pendingPlayRequest = playWhenReady
      ensureBound()
    }
  }

  override fun play() {
    ensureForegroundServiceStarted()
    serviceDelegate?.play() ?: run {
      pendingPlayRequest = true
      ensureBound()
    }
  }

  override fun pause() {
    pendingPlayRequest = false
    serviceDelegate?.pause()
  }

  override fun stop() {
    pendingPlayRequest = false
    serviceDelegate?.stop()
  }

  override fun seekTo(positionMs: Long) {
    serviceDelegate?.seekTo(positionMs) ?: run {
      pendingSeekMs = positionMs
    }
  }

  override fun setRepeatMode(mode: RepeatMode) {
    pendingRepeatMode = mode
    serviceDelegate?.setRepeatMode(mode)
  }

  override fun setShuffleEnabled(enabled: Boolean) {
    pendingShuffle = enabled
    serviceDelegate?.setShuffleEnabled(enabled)
  }

  override fun applyDspSettings(settings: DspSettings) {
    pendingDspSettings = settings
    serviceDelegate?.applyDspSettings(settings)
  }

  override fun applyEqualizerState(state: EqualizerState) {
    pendingEqState = state
    serviceDelegate?.applyEqualizerState(state)
  }

  override fun applyVolume(volume0To255: Int) {
    pendingVolume = volume0To255
    serviceDelegate?.applyVolume(volume0To255)
  }

  override fun applyBalance(balanceMinus127To127: Int) {
    pendingBalance = balanceMinus127To127
    serviceDelegate?.applyBalance(balanceMinus127To127)
  }

  override fun applyReplayGainMetadata(metadata: ReplayGainMetadata) {
    pendingReplayGain = metadata
    serviceDelegate?.applyReplayGainMetadata(metadata)
  }

  override fun readVisualizerFrame(isPlaying: Boolean): VisualizerFrame =
    serviceDelegate?.readVisualizerFrame(isPlaying) ?: VisualizerFrame.SILENT

  override fun resetVisualizer() {
    serviceDelegate?.resetVisualizer()
  }

  override fun currentPositionMs(): Long = serviceDelegate?.currentPositionMs() ?: 0L

  override fun currentDurationMs(): Long = serviceDelegate?.currentDurationMs() ?: 0L

  override fun onCrossfadeProgressTick(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    currentTrack: AudioTrack?,
    nextCandidate: CrossfadeController.NextTrackCandidate?,
  ) {
    serviceDelegate?.onCrossfadeProgressTick(
      isPlaying = isPlaying,
      positionMs = positionMs,
      durationMs = durationMs,
      currentTrack = currentTrack,
      nextCandidate = nextCandidate,
    )
  }

  override fun cancelCrossfade() {
    serviceDelegate?.cancelCrossfade()
  }

  override fun release() {
    if (released) return
    released = true
    serviceDelegate?.setListener(null)
    serviceDelegate = null
    boundService = null
    if (bindRequested) {
      runCatching { context.unbindService(serviceConnection) }
      bindRequested = false
    }
  }

  // Forward callbacks from service delegate -> Media3AudioEngineController
  override fun onPlaybackStatusChanged(isPlaying: Boolean, isEnded: Boolean) {
    controllerListener?.onPlaybackStatusChanged(isPlaying, isEnded)
  }

  override fun onGaplessTrackChanged(newIndex: Int, trackId: String?) {
    controllerListener?.onGaplessTrackChanged(newIndex, trackId)
  }

  override fun onAudioFormatUpdated(
    bitrateKbps: Int?,
    sampleRateHz: Int?,
    channelMode: ChannelMode?,
    durationMs: Long?,
  ) {
    controllerListener?.onAudioFormatUpdated(bitrateKbps, sampleRateHz, channelMode, durationMs)
  }

  override fun onReplayGainMetadataParsed(trackId: String?, metadata: ReplayGainMetadata) {
    controllerListener?.onReplayGainMetadataParsed(trackId, metadata)
  }

  override fun onCrossfadeTrackAdvanced(newIndex: Int, newTrack: AudioTrack) {
    controllerListener?.onCrossfadeTrackAdvanced(newIndex, newTrack)
  }

  override fun onRemoteTransportAction(action: RemoteTransportAction) {
    controllerListener?.onRemoteTransportAction(action)
  }

  override fun onRemoteSeekTo(positionMs: Long) {
    controllerListener?.onRemoteSeekTo(positionMs)
  }
}
