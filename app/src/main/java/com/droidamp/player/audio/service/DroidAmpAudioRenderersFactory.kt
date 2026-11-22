package com.droidamp.player.audio.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider

/**
 * Custom Media3 RenderersFactory injecting DroidAmp's inline PCM AudioProcessor chain
 * into DefaultAudioSink so all audio passes through ReplayGain, 10-band EQ,
 * Volume/Balance, and the lock-free Visualizer tap.
 */
@OptIn(UnstableApi::class)
class DroidAmpAudioRenderersFactory @JvmOverloads constructor(
  context: Context,
  private val dspChain: DroidAmpDspChain,
  private val lowLatencyPcmBuffer: Boolean = false,
) : DefaultRenderersFactory(context) {
  @Suppress("DEPRECATION")
  override fun buildAudioSink(
    context: Context,
    enableFloatOutput: Boolean,
    enableAudioOutputPlaybackParams: Boolean,
  ): AudioSink {
    val builder =
      DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
        .setAudioProcessors(dspChain.asArray())
    if (lowLatencyPcmBuffer) {
      builder.setAudioTrackBufferSizeProvider(
        DefaultAudioTrackBufferSizeProvider.Builder()
          .setMinPcmBufferDurationUs(75_000)
          .setMaxPcmBufferDurationUs(150_000)
          .setPcmBufferMultiplicationFactor(2)
          .build(),
      )
    }
    return builder.build()
  }
}
