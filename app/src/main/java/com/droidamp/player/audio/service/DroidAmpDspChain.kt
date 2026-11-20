package com.droidamp.player.audio.service

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.droidamp.player.audio.dsp.ReplayGainMetadata
import com.droidamp.player.audio.dsp.ReplayGainProcessor
import com.droidamp.player.audio.dsp.VisualizerTapProcessor
import com.droidamp.player.audio.dsp.VolumeBalanceProcessor
import com.droidamp.player.audio.dsp.WinampTenBandEqProcessor
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.VisualizerFrame

/**
 * Per-player inline PCM DSP chain in fixed Winamp processing order:
 * ReplayGainProcessor -> WinampTenBandEqProcessor -> VolumeBalanceProcessor -> VisualizerTapProcessor.
 *
 * Each ExoPlayer deck MUST own a dedicated instance so each VisualizerTapProcessor's
 * SPSC LockFreeAudioRingBuffer has strictly one audio render producer thread.
 */
@OptIn(UnstableApi::class)
class DroidAmpDspChain(
  val replayGainProcessor: ReplayGainProcessor = ReplayGainProcessor(),
  val eqProcessor: WinampTenBandEqProcessor = WinampTenBandEqProcessor(),
  val volumeBalanceProcessor: VolumeBalanceProcessor = VolumeBalanceProcessor(),
  val visualizerTapProcessor: VisualizerTapProcessor = VisualizerTapProcessor(),
) {
  fun asArray(): Array<AudioProcessor> =
    arrayOf(
      replayGainProcessor,
      eqProcessor,
      volumeBalanceProcessor,
      visualizerTapProcessor,
    )

  fun applyDspSettings(settings: DspSettings) {
    val safe = settings.sanitized()
    replayGainProcessor.setConfig(safe.replayGainMode, safe.replayGainPreampDb)
  }

  fun applyEqualizerState(state: EqualizerState) {
    eqProcessor.updateEqualizerState(state)
  }

  fun setVolume(volume0To255: Int) {
    volumeBalanceProcessor.setVolume(volume0To255)
  }

  fun setBalance(balanceMinus127To127: Int) {
    volumeBalanceProcessor.setBalance(balanceMinus127To127)
  }

  fun setReplayGainMetadata(metadata: ReplayGainMetadata) {
    replayGainProcessor.setTrackMetadata(metadata)
  }

  fun clearReplayGainMetadata() {
    replayGainProcessor.clearTrackMetadata()
  }

  fun readVisualizerFrame(isPlaying: Boolean): VisualizerFrame =
    visualizerTapProcessor.readVisualizerFrame(isPlaying)

  fun resetVisualizer() {
    visualizerTapProcessor.resetVisualizer()
  }
}
