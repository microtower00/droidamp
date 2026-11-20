package com.droidamp.player.domain.contract

import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaylistSortOrder
import com.droidamp.player.domain.model.VisualizerFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val DefaultDspSettingsFlow: StateFlow<DspSettings> =
  MutableStateFlow(DspSettings()).asStateFlow()

interface AudioEngineController {
  val playbackState: StateFlow<PlaybackState>
  val visualizerFrame: StateFlow<VisualizerFrame>
  val dspSettings: StateFlow<DspSettings>
    get() = DefaultDspSettingsFlow

  fun play()
  fun pause()
  fun stop()
  fun next()
  fun previous()
  fun seekTo(positionMs: Long)
  fun playTrackAt(index: Int)

  fun replaceQueue(tracks: List<AudioTrack>, startIndex: Int = 0, autoPlay: Boolean = true)
  fun enqueueTracks(tracks: List<AudioTrack>)
  fun removeIndices(indices: Set<Int>)
  fun cropIndices(indices: Set<Int>)
  fun clearQueue()
  fun moveTrack(fromIndex: Int, toIndex: Int)
  fun sortQueue(order: PlaylistSortOrder)
  fun reverseQueue()
  fun randomizeQueue()

  fun setVolume(volume0To255: Int)
  fun setBalance(balanceMinus127To127: Int)
  fun applyColdLaunchVolumeResetIfStopped(
    defaultVolume: Int = PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME,
  )

  fun setEqEnabled(enabled: Boolean)
  fun setEqAutoEnabled(enabled: Boolean)
  fun setPreampDb(db: Float)
  fun setEqBandDb(bandIndex: Int, db: Float)
  fun setEqBands(bandsDb: List<Float>)
  fun applyEqPreset(preset: EqPreset)

  fun toggleShuffle()
  fun cycleRepeatMode()
  fun toggleTimeDisplayMode()
  fun cycleVisualizerMode()

  fun setDspSettings(settings: DspSettings) {}

  fun setReplayGainMetadata(
    trackId: String,
    trackGainDb: Float?,
    trackPeak: Float?,
    albumGainDb: Float?,
    albumPeak: Float?,
  ) {}
}
