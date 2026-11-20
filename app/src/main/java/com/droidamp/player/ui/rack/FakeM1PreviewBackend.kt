package com.droidamp.player.ui.rack

import com.droidamp.player.domain.contract.AudioEngineController
import com.droidamp.player.domain.contract.MusicSourceRepository
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.FolderContents
import com.droidamp.player.domain.model.FolderEntry
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.RepeatMode
import com.droidamp.player.domain.model.SavedPlaylistEntry
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.domain.model.TimeDisplayMode
import com.droidamp.player.domain.model.VisualizerFrame
import com.droidamp.player.domain.model.VisualizerMode
import com.droidamp.player.domain.model.WinampEqSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Milestone 1 interactive preview engine.
 * Replaced by the Media3 ExoPlayer + custom PCM AudioProcessor service in Milestone 2.
 */
class FakeAudioEngineController(
  private val scope: CoroutineScope,
  private val enableSimulationTicker: Boolean = true,
  private val tickerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AudioEngineController {

  private val _playbackState = MutableStateFlow(PlaybackState())
  override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

  private val _visualizerFrame = MutableStateFlow(VisualizerFrame.SILENT)
  override val visualizerFrame: StateFlow<VisualizerFrame> = _visualizerFrame.asStateFlow()

  private val stateLock = Any()
  private var tickerJob: Job? = null
  private val peakCaps = FloatArray(VisualizerFrame.SPECTRUM_BAR_COUNT)
  private var phase = 0f

  private fun startTickerIfNeeded() {
    synchronized(stateLock) {
      if (!enableSimulationTicker || tickerJob?.isActive == true) return
      val initialState = _playbackState.value
      if (initialState.status == PlaybackStatus.PLAYING) {
        emitAnimatedVisualizerFrameLocked(initialState)
      }
      tickerJob =
        scope.launch(tickerDispatcher) {
          var tickMs = 0L
          while (isActive) {
            delay(33L) // ~30-60fps smooth VFD preview
            val shouldStopTicker =
              synchronized(stateLock) {
                val state = _playbackState.value
                if (state.status != PlaybackStatus.PLAYING) {
                  decayVisualizerLocked()
                  if (_visualizerFrame.value == VisualizerFrame.SILENT) {
                    tickerJob = null
                    true
                  } else {
                    false
                  }
                } else {
                  tickMs += 33L
                  if (tickMs >= 250L) {
                    advanceClockLocked(tickMs)
                    tickMs = 0L
                  }
                  val currentState = _playbackState.value
                  if (currentState.status == PlaybackStatus.PLAYING) {
                    emitAnimatedVisualizerFrameLocked(currentState)
                  }
                  false
                }
              }
            if (shouldStopTicker) break
          }
        }
    }
  }

  private fun advanceClockLocked(deltaMs: Long) {
    val state = _playbackState.value
    val track = state.currentTrack ?: return
    val nextPos = state.positionMs + deltaMs
    if (nextPos >= track.durationMs) {
      when (state.repeatMode) {
        RepeatMode.ONE -> _playbackState.update { it.copy(positionMs = 0L) }
        RepeatMode.ALL -> next()
        RepeatMode.OFF -> {
          if (state.currentIndex < state.queue.lastIndex) {
            next()
          } else {
            stop()
          }
        }
      }
    } else {
      _playbackState.update { it.copy(positionMs = nextPos) }
    }
  }

  private fun emitAnimatedVisualizerFrameLocked(state: PlaybackState) {
    phase += 0.24f
    val gainScale = (state.volume / 255f).coerceIn(0.15f, 1f)
    val bars = FloatArray(VisualizerFrame.SPECTRUM_BAR_COUNT)
    val peaks = FloatArray(VisualizerFrame.SPECTRUM_BAR_COUNT)
    val eq = state.equalizer

    for (i in bars.indices) {
      val bandIndex = (i * WinampEqSpec.BAND_COUNT / bars.size).coerceIn(0, WinampEqSpec.BAND_COUNT - 1)
      val eqBoost =
        if (eq.enabled) {
          ((eq.bandsDb[bandIndex] + eq.preampDb * 0.5f) / 24f + 0.5f).coerceIn(0.25f, 1.35f)
        } else {
          0.75f
        }
      val bassWeight = 1.0f - (i / bars.size.toFloat()) * 0.35f
      val wave1 = abs(sin(phase * (0.9f + i * 0.14f) + i * 0.65f))
      val wave2 = abs(sin(phase * 1.85f - i * 0.42f))
      val value = ((wave1 * 0.65f + wave2 * 0.35f) * bassWeight * eqBoost * gainScale).coerceIn(0.05f, 1f)
      bars[i] = value
      peakCaps[i] = maxOf(value, (peakCaps[i] - 0.025f).coerceAtLeast(0f))
      peaks[i] = peakCaps[i]
    }

    val waveform = FloatArray(VisualizerFrame.WAVEFORM_SAMPLE_COUNT)
    for (i in waveform.indices) {
      val t = i.toFloat() / waveform.size * 4f * PI.toFloat()
      waveform[i] =
        ((sin(t + phase * 1.9f) * 0.6f + sin(t * 2.5f - phase * 1.1f) * 0.35f) * gainScale)
          .coerceIn(-1f, 1f)
    }
    _visualizerFrame.value = VisualizerFrame(bars, peaks, waveform)
  }

  private fun decayVisualizerLocked() {
    val current = _visualizerFrame.value
    var anyActive = false
    val bars = FloatArray(current.spectrumBars.size)
    val peaks = FloatArray(current.peakDots.size)
    for (i in bars.indices) {
      bars[i] = (current.spectrumBars[i] - 0.08f).coerceAtLeast(0f)
      peakCaps[i] = (peakCaps[i] - 0.03f).coerceAtLeast(0f)
      peaks[i] = peakCaps[i]
      if (bars[i] > 0f || peaks[i] > 0f) anyActive = true
    }
    _visualizerFrame.value =
      if (anyActive) VisualizerFrame(bars, peaks, FloatArray(VisualizerFrame.WAVEFORM_SAMPLE_COUNT))
      else VisualizerFrame.SILENT
  }

  override fun play() {
    synchronized(stateLock) {
      _playbackState.update { state ->
        if (state.queue.isEmpty()) return@update state
        val targetIndex = if (state.currentIndex in state.queue.indices) state.currentIndex else 0
        state.copy(status = PlaybackStatus.PLAYING, currentIndex = targetIndex)
      }
      startTickerIfNeeded()
    }
  }

  override fun pause() {
    synchronized(stateLock) {
      _playbackState.update { state ->
        if (state.status == PlaybackStatus.PLAYING) state.copy(status = PlaybackStatus.PAUSED) else state
      }
    }
  }

  override fun stop() {
    synchronized(stateLock) {
      _playbackState.update { it.copy(status = PlaybackStatus.STOPPED, positionMs = 0L) }
      peakCaps.fill(0f)
      _visualizerFrame.value = VisualizerFrame.SILENT
    }
  }

  override fun next() {
    synchronized(stateLock) {
      _playbackState.update { state ->
        if (state.queue.isEmpty()) return@update state
        val nextIdx =
          if (state.shuffle && state.queue.size > 1) {
            val candidates = state.queue.indices.filter { it != state.currentIndex }
            candidates.random()
          } else {
            (state.currentIndex + 1) % state.queue.size
          }
        state.copy(currentIndex = nextIdx, positionMs = 0L)
      }
    }
  }

  override fun previous() {
    synchronized(stateLock) {
      _playbackState.update { state ->
        if (state.queue.isEmpty()) return@update state
        if (state.positionMs > 3_000L) {
          return@update state.copy(positionMs = 0L)
        }
        val prevIdx =
          if (state.currentIndex <= 0) state.queue.lastIndex else state.currentIndex - 1
        state.copy(currentIndex = prevIdx, positionMs = 0L)
      }
    }
  }

  override fun seekTo(positionMs: Long) {
    synchronized(stateLock) {
      _playbackState.update { state ->
        if (state.queue.isEmpty()) return@update state
        val maxMs = state.durationMs.coerceAtLeast(0L)
        state.copy(positionMs = positionMs.coerceIn(0L, maxMs))
      }
    }
  }

  override fun playTrackAt(index: Int) {
    synchronized(stateLock) {
      _playbackState.update { state ->
        if (index !in state.queue.indices) return@update state
        state.copy(currentIndex = index, positionMs = 0L, status = PlaybackStatus.PLAYING)
      }
      startTickerIfNeeded()
    }
  }

  override fun replaceQueue(tracks: List<AudioTrack>, startIndex: Int, autoPlay: Boolean) {
    synchronized(stateLock) {
      _playbackState.update { state ->
        if (tracks.isEmpty()) {
          state.copy(queue = emptyList(), currentIndex = -1, positionMs = 0L, status = PlaybackStatus.STOPPED)
        } else {
          val safeIdx = startIndex.coerceIn(0, tracks.lastIndex)
          state.copy(
            queue = tracks,
            currentIndex = safeIdx,
            positionMs = 0L,
            status = if (autoPlay) PlaybackStatus.PLAYING else PlaybackStatus.STOPPED,
          )
        }
      }
      if (autoPlay && tracks.isNotEmpty()) {
        startTickerIfNeeded()
      }
    }
  }

  override fun enqueueTracks(tracks: List<AudioTrack>) {
    if (tracks.isEmpty()) return
    synchronized(stateLock) {
      _playbackState.update { state ->
        val newQueue = state.queue + tracks
        val newIndex = if (state.currentIndex < 0) 0 else state.currentIndex
        state.copy(queue = newQueue, currentIndex = newIndex)
      }
    }
  }

  override fun removeIndices(indices: Set<Int>) {
    if (indices.isEmpty()) return
    synchronized(stateLock) {
      _playbackState.update { state ->
        val currentTrackId = state.currentTrack?.id
        val kept = state.queue.filterIndexed { idx, _ -> idx !in indices }
        if (kept.isEmpty()) {
          state.copy(queue = emptyList(), currentIndex = -1, positionMs = 0L, status = PlaybackStatus.STOPPED)
        } else {
          val newCurrent = kept.indexOfFirst { it.id == currentTrackId }.let { if (it >= 0) it else 0 }
          state.copy(queue = kept, currentIndex = newCurrent)
        }
      }
    }
  }

  override fun cropIndices(indices: Set<Int>) {
    if (indices.isEmpty()) return
    synchronized(stateLock) {
      val all = _playbackState.value.queue.indices.toSet()
      removeIndices(all - indices)
    }
  }

  override fun clearQueue() {
    synchronized(stateLock) {
      stop()
      _playbackState.update { it.copy(queue = emptyList(), currentIndex = -1, positionMs = 0L) }
    }
  }

  override fun moveTrack(fromIndex: Int, toIndex: Int) {
    synchronized(stateLock) {
      _playbackState.update { state ->
        if (fromIndex !in state.queue.indices || toIndex !in state.queue.indices || fromIndex == toIndex) {
          return@update state
        }
        val mutable = state.queue.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        val newCurrent =
          when {
            state.currentIndex == fromIndex -> toIndex
            fromIndex < state.currentIndex && toIndex >= state.currentIndex -> state.currentIndex - 1
            fromIndex > state.currentIndex && toIndex <= state.currentIndex -> state.currentIndex + 1
            else -> state.currentIndex
          }
        state.copy(queue = mutable, currentIndex = newCurrent)
      }
    }
  }

  override fun sortQueue(order: PlaylistSortOrder) {
    synchronized(stateLock) {
      _playbackState.update { state ->
        val currentId = state.currentTrack?.id
        val sorted =
          when (order) {
            PlaylistSortOrder.TITLE -> state.queue.sortedBy { it.displayLabel.lowercase() }
            PlaylistSortOrder.FILENAME -> state.queue.sortedBy { it.fileName.lowercase() }
            PlaylistSortOrder.PATH -> state.queue.sortedBy { "${it.folderPath}/${it.fileName}".lowercase() }
          }
        val newIdx = sorted.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else state.currentIndex }
        state.copy(queue = sorted, currentIndex = newIdx)
      }
    }
  }

  override fun reverseQueue() {
    synchronized(stateLock) {
      _playbackState.update { state ->
        val currentId = state.currentTrack?.id
        val reversed = state.queue.reversed()
        val newIdx = reversed.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else state.currentIndex }
        state.copy(queue = reversed, currentIndex = newIdx)
      }
    }
  }

  override fun randomizeQueue() {
    synchronized(stateLock) {
      _playbackState.update { state ->
        if (state.queue.size <= 1) return@update state
        val currentId = state.currentTrack?.id
        val shuffled = state.queue.shuffled()
        val newIdx = shuffled.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else 0 }
        state.copy(queue = shuffled, currentIndex = newIdx)
      }
    }
  }

  override fun setVolume(volume0To255: Int) {
    synchronized(stateLock) {
      _playbackState.update {
        it.copy(volume = volume0To255.coerceIn(PlaybackState.MIN_VOLUME, PlaybackState.MAX_VOLUME))
      }
    }
  }

  override fun setBalance(balanceMinus127To127: Int) {
    synchronized(stateLock) {
      _playbackState.update {
        it.copy(balance = balanceMinus127To127.coerceIn(PlaybackState.MIN_BALANCE, PlaybackState.MAX_BALANCE))
      }
    }
  }

  /**
   * ADR-007: Reset internal DSP volume ONLY on fresh cold launch when playback is stopped.
   * Never reset while audio is PLAYING or PAUSED.
   */
  override fun applyColdLaunchVolumeResetIfStopped(defaultVolume: Int) {
    synchronized(stateLock) {
      _playbackState.update { state ->
        if (state.status == PlaybackStatus.STOPPED) {
          state.copy(volume = defaultVolume.coerceIn(PlaybackState.MIN_VOLUME, PlaybackState.MAX_VOLUME))
        } else {
          state
        }
      }
    }
  }

  override fun setEqEnabled(enabled: Boolean) {
    synchronized(stateLock) {
      _playbackState.update { it.copy(equalizer = it.equalizer.copy(enabled = enabled)) }
    }
  }

  override fun setEqAutoEnabled(enabled: Boolean) {
    synchronized(stateLock) {
      _playbackState.update { it.copy(equalizer = it.equalizer.copy(autoEnabled = enabled)) }
    }
  }

  override fun setPreampDb(db: Float) {
    synchronized(stateLock) {
      _playbackState.update {
        it.copy(equalizer = it.equalizer.copy(preampDb = WinampEqSpec.clampDb(db), activePresetName = "CUSTOM"))
      }
    }
  }

  override fun setEqBandDb(bandIndex: Int, db: Float) {
    if (bandIndex !in 0 until WinampEqSpec.BAND_COUNT) return
    synchronized(stateLock) {
      _playbackState.update { state ->
        val updated = state.equalizer.bandsDb.toMutableList()
        updated[bandIndex] = WinampEqSpec.clampDb(db)
        state.copy(equalizer = state.equalizer.copy(bandsDb = updated, activePresetName = "CUSTOM"))
      }
    }
  }

  override fun setEqBands(bandsDb: List<Float>) {
    if (bandsDb.size != WinampEqSpec.BAND_COUNT) return
    synchronized(stateLock) {
      _playbackState.update { state ->
        state.copy(
          equalizer =
            state.equalizer.copy(
              bandsDb = bandsDb.map(WinampEqSpec::clampDb),
              activePresetName = "CUSTOM",
            ),
        )
      }
    }
  }

  override fun applyEqPreset(preset: EqPreset) {
    synchronized(stateLock) {
      _playbackState.update { state ->
        state.copy(
          equalizer =
            state.equalizer.copy(
              preampDb = WinampEqSpec.clampDb(preset.preampDb),
              bandsDb = preset.bandsDb.map(WinampEqSpec::clampDb),
              activePresetName = preset.name,
            ),
        )
      }
    }
  }

  override fun toggleShuffle() {
    synchronized(stateLock) {
      _playbackState.update { it.copy(shuffle = !it.shuffle) }
    }
  }

  override fun cycleRepeatMode() {
    synchronized(stateLock) {
      _playbackState.update { state ->
        val next =
          when (state.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
          }
        state.copy(repeatMode = next)
      }
    }
  }

  override fun toggleTimeDisplayMode() {
    synchronized(stateLock) {
      _playbackState.update { state ->
        val next =
          if (state.timeDisplayMode == TimeDisplayMode.ELAPSED) {
            TimeDisplayMode.REMAINING
          } else {
            TimeDisplayMode.ELAPSED
          }
        state.copy(timeDisplayMode = next)
      }
    }
  }

  override fun cycleVisualizerMode() {
    synchronized(stateLock) {
      _playbackState.update { state ->
        val next =
          when (state.visualizerMode) {
            VisualizerMode.SPECTRUM -> VisualizerMode.OSCILLOSCOPE
            VisualizerMode.OSCILLOSCOPE -> VisualizerMode.OFF
            VisualizerMode.OFF -> VisualizerMode.SPECTRUM
          }
        state.copy(visualizerMode = next)
      }
    }
  }
}

/**
 * Milestone 1 SAF-first preview repository.
 * Starts unlinked (First-Run SAF Empty State); linking a folder populates a realistic nested
 * directory tree & classic `.m3u8`/`.pls` playlists for tactile M1 testing.
 */
class FakeMusicSourceRepository(
  startLinked: Boolean = false,
) : MusicSourceRepository {

  private val _storageBackend = MutableStateFlow(StorageBackend.SAF)
  override val storageBackend: StateFlow<StorageBackend> = _storageBackend.asStateFlow()

  private val _linkedRoots = MutableStateFlow(if (startLinked) listOf(ROOT_MUSIC_FOLDER) else emptyList())
  override val linkedRoots: StateFlow<List<FolderEntry>> = _linkedRoots.asStateFlow()

  override suspend fun setStorageBackend(backend: StorageBackend) {
    _storageBackend.value = backend
  }

  override suspend fun linkSafRoot(treeUriString: String) {
    _linkedRoots.value = listOf(ROOT_MUSIC_FOLDER.copy(uriString = treeUriString))
  }

  override suspend fun unlinkAllRoots() {
    _linkedRoots.value = emptyList()
  }

  override suspend fun browseFolder(folderUriString: String?): FolderContents {
    val root = _linkedRoots.value.firstOrNull() ?: return FolderContents.EMPTY
    val target = folderUriString ?: root.uriString
    return when (target) {
      root.uriString, ROOT_URI ->
        FolderContents(
          currentFolderUri = root.uriString,
          currentPathSegments = listOf("Music"),
          canNavigateUp = false,
          parentFolderUri = null,
          folders = listOf(FOLDER_ELECTRONIC, FOLDER_SYNTHWAVE, FOLDER_FLAC_DEMOS),
          tracks = ROOT_TRACKS,
        )
      FOLDER_ELECTRONIC.uriString ->
        FolderContents(
          currentFolderUri = FOLDER_ELECTRONIC.uriString,
          currentPathSegments = listOf("Music", "Electronic"),
          canNavigateUp = true,
          parentFolderUri = root.uriString,
          folders = listOf(FOLDER_DAFT_PUNK),
          tracks = ELECTRONIC_TRACKS,
        )
      FOLDER_DAFT_PUNK.uriString ->
        FolderContents(
          currentFolderUri = FOLDER_DAFT_PUNK.uriString,
          currentPathSegments = listOf("Music", "Electronic", "Daft Punk - Discovery"),
          canNavigateUp = true,
          parentFolderUri = FOLDER_ELECTRONIC.uriString,
          folders = emptyList(),
          tracks = DAFT_PUNK_TRACKS,
        )
      FOLDER_SYNTHWAVE.uriString ->
        FolderContents(
          currentFolderUri = FOLDER_SYNTHWAVE.uriString,
          currentPathSegments = listOf("Music", "Synthwave"),
          canNavigateUp = true,
          parentFolderUri = root.uriString,
          folders = emptyList(),
          tracks = SYNTHWAVE_TRACKS,
        )
      FOLDER_FLAC_DEMOS.uriString ->
        FolderContents(
          currentFolderUri = FOLDER_FLAC_DEMOS.uriString,
          currentPathSegments = listOf("Music", "FLAC Demos"),
          canNavigateUp = true,
          parentFolderUri = root.uriString,
          folders = emptyList(),
          tracks = FLAC_DEMO_TRACKS,
        )
      else -> FolderContents.EMPTY
    }
  }

  override suspend fun resolveFolderTracks(folderUriString: String, recursive: Boolean): List<AudioTrack> {
    if (_linkedRoots.value.isEmpty()) return emptyList()
    return when (folderUriString) {
      FOLDER_ELECTRONIC.uriString ->
        if (recursive) ELECTRONIC_TRACKS + DAFT_PUNK_TRACKS else ELECTRONIC_TRACKS + DAFT_PUNK_TRACKS
      FOLDER_DAFT_PUNK.uriString -> DAFT_PUNK_TRACKS
      FOLDER_SYNTHWAVE.uriString -> SYNTHWAVE_TRACKS
      FOLDER_FLAC_DEMOS.uriString -> FLAC_DEMO_TRACKS
      else -> ROOT_TRACKS + DAFT_PUNK_TRACKS + SYNTHWAVE_TRACKS
    }
  }

  override suspend fun listPlaylists(): List<SavedPlaylistEntry> {
    if (_linkedRoots.value.isEmpty()) return emptyList()
    return SAMPLE_PLAYLISTS
  }

  override suspend fun resolvePlaylistTracks(playlistId: String): List<AudioTrack> {
    if (_linkedRoots.value.isEmpty()) return emptyList()
    return when (playlistId) {
      "pl-late-night" -> DAFT_PUNK_TRACKS.take(2) + SYNTHWAVE_TRACKS
      "pl-hifi-test" -> FLAC_DEMO_TRACKS + ROOT_TRACKS
      else -> DAFT_PUNK_TRACKS
    }
  }

  companion object {
    const val ROOT_URI = "content://com.android.externalstorage.documents/tree/primary:Music"

    private val ROOT_MUSIC_FOLDER =
      FolderEntry(
        uriString = ROOT_URI,
        name = "Music",
        pathSegments = listOf("Music"),
        trackCount = 1,
        subfolderCount = 3,
      )

    private val FOLDER_ELECTRONIC =
      FolderEntry(
        uriString = "$ROOT_URI/Electronic",
        name = "Electronic",
        pathSegments = listOf("Music", "Electronic"),
        trackCount = 2,
        subfolderCount = 1,
      )

    private val FOLDER_DAFT_PUNK =
      FolderEntry(
        uriString = "$ROOT_URI/Electronic/DaftPunk",
        name = "Daft Punk - Discovery",
        pathSegments = listOf("Music", "Electronic", "Daft Punk - Discovery"),
        trackCount = 4,
        subfolderCount = 0,
      )

    private val FOLDER_SYNTHWAVE =
      FolderEntry(
        uriString = "$ROOT_URI/Synthwave",
        name = "Synthwave",
        pathSegments = listOf("Music", "Synthwave"),
        trackCount = 3,
        subfolderCount = 0,
      )

    private val FOLDER_FLAC_DEMOS =
      FolderEntry(
        uriString = "$ROOT_URI/FlacDemos",
        name = "FLAC Demos",
        pathSegments = listOf("Music", "FLAC Demos"),
        trackCount = 2,
        subfolderCount = 0,
      )

    val ROOT_TRACKS =
      listOf(
        AudioTrack(
          id = "trk-llama",
          uriString = "$ROOT_URI/demo.mp3",
          title = "DroidAmp Intro (Llama Whippin' Mix)",
          artist = "DJ Mike Llama",
          album = "Classic Demos",
          fileName = "00_demo.mp3",
          folderPath = "Music",
          durationMs = 15_000L,
          bitrateKbps = 128,
          sampleRateHz = 44_100,
          channelMode = ChannelMode.STEREO,
        ),
      )

    val ELECTRONIC_TRACKS =
      listOf(
        AudioTrack(
          id = "trk-chem",
          uriString = "$ROOT_URI/Electronic/block_rockin.mp3",
          title = "Block Rockin' Beats",
          artist = "The Chemical Brothers",
          album = "Dig Your Own Hole",
          fileName = "01_block_rockin.mp3",
          folderPath = "Music/Electronic",
          durationMs = 314_000L,
          bitrateKbps = 320,
          sampleRateHz = 44_100,
        ),
        AudioTrack(
          id = "trk-prodigy",
          uriString = "$ROOT_URI/Electronic/smack.mp3",
          title = "Breathe",
          artist = "The Prodigy",
          album = "The Fat of the Land",
          fileName = "02_breathe.mp3",
          folderPath = "Music/Electronic",
          durationMs = 335_000L,
          bitrateKbps = 256,
          sampleRateHz = 44_100,
        ),
      )

    val DAFT_PUNK_TRACKS =
      listOf(
        AudioTrack(
          id = "trk-dp-1",
          uriString = "$ROOT_URI/Electronic/DaftPunk/01_one_more_time.flac",
          title = "One More Time",
          artist = "Daft Punk",
          album = "Discovery",
          fileName = "01_one_more_time.flac",
          folderPath = "Music/Electronic/Daft Punk - Discovery",
          durationMs = 320_000L,
          bitrateKbps = 984,
          sampleRateHz = 44_100,
        ),
        AudioTrack(
          id = "trk-dp-2",
          uriString = "$ROOT_URI/Electronic/DaftPunk/02_aerodynamic.flac",
          title = "Aerodynamic",
          artist = "Daft Punk",
          album = "Discovery",
          fileName = "02_aerodynamic.flac",
          folderPath = "Music/Electronic/Daft Punk - Discovery",
          durationMs = 207_000L,
          bitrateKbps = 1012,
          sampleRateHz = 44_100,
        ),
        AudioTrack(
          id = "trk-dp-3",
          uriString = "$ROOT_URI/Electronic/DaftPunk/03_digital_love.flac",
          title = "Digital Love",
          artist = "Daft Punk",
          album = "Discovery",
          fileName = "03_digital_love.flac",
          folderPath = "Music/Electronic/Daft Punk - Discovery",
          durationMs = 298_000L,
          bitrateKbps = 945,
          sampleRateHz = 44_100,
        ),
        AudioTrack(
          id = "trk-dp-4",
          uriString = "$ROOT_URI/Electronic/DaftPunk/04_harder_better.flac",
          title = "Harder, Better, Faster, Stronger",
          artist = "Daft Punk",
          album = "Discovery",
          fileName = "04_harder_better.flac",
          folderPath = "Music/Electronic/Daft Punk - Discovery",
          durationMs = 224_000L,
          bitrateKbps = 960,
          sampleRateHz = 44_100,
        ),
      )

    val SYNTHWAVE_TRACKS =
      listOf(
        AudioTrack(
          id = "trk-sw-1",
          uriString = "$ROOT_URI/Synthwave/nightcall.mp3",
          title = "Nightcall",
          artist = "Kavinsky",
          album = "OutRun",
          fileName = "01_nightcall.mp3",
          folderPath = "Music/Synthwave",
          durationMs = 258_000L,
          bitrateKbps = 320,
          sampleRateHz = 44_100,
        ),
        AudioTrack(
          id = "trk-sw-2",
          uriString = "$ROOT_URI/Synthwave/tech_noir.mp3",
          title = "Tech Noir",
          artist = "GUNSHIP",
          album = "GUNSHIP",
          fileName = "02_tech_noir.mp3",
          folderPath = "Music/Synthwave",
          durationMs = 297_000L,
          bitrateKbps = 320,
          sampleRateHz = 48_000,
        ),
        AudioTrack(
          id = "trk-sw-3",
          uriString = "$ROOT_URI/Synthwave/turbo_killer.mp3",
          title = "Turbo Killer",
          artist = "Carpenter Brut",
          album = "Trilogy",
          fileName = "03_turbo_killer.mp3",
          folderPath = "Music/Synthwave",
          durationMs = 208_000L,
          bitrateKbps = 320,
          sampleRateHz = 44_100,
        ),
      )

    val FLAC_DEMO_TRACKS =
      listOf(
        AudioTrack(
          id = "trk-flac-1",
          uriString = "$ROOT_URI/FlacDemos/sine_sweep.flac",
          title = "24-Bit Hi-Fi Reference Sweep",
          artist = "DroidAmp Lab",
          album = "Test Signals",
          fileName = "01_reference_sweep.flac",
          folderPath = "Music/FLAC Demos",
          durationMs = 149_000L,
          bitrateKbps = 1411,
          sampleRateHz = 96_000,
        ),
        AudioTrack(
          id = "trk-flac-2",
          uriString = "$ROOT_URI/FlacDemos/mono_check.wav",
          title = "Mono Phase Calibration",
          artist = "DroidAmp Lab",
          album = "Test Signals",
          fileName = "02_mono_check.wav",
          folderPath = "Music/FLAC Demos",
          durationMs = 95_000L,
          bitrateKbps = 705,
          sampleRateHz = 44_100,
          channelMode = ChannelMode.MONO,
        ),
      )

    private val SAMPLE_PLAYLISTS =
      listOf(
        SavedPlaylistEntry(
          id = "pl-late-night",
          uriString = "$ROOT_URI/late_night_drive.m3u8",
          name = "late_night_drive",
          format = "m3u8",
          trackCount = 5,
          totalDurationMs = 1_290_000L,
        ),
        SavedPlaylistEntry(
          id = "pl-hifi-test",
          uriString = "$ROOT_URI/hifi_rack_check.pls",
          name = "hifi_rack_check",
          format = "pls",
          trackCount = 3,
          totalDurationMs = 259_000L,
        ),
      )
  }
}
