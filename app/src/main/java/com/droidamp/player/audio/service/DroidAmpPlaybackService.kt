package com.droidamp.player.audio.service

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.droidamp.player.MainActivity
import com.droidamp.player.R
import com.droidamp.player.audio.dsp.ReplayGainMetadata
import com.droidamp.player.audio.dsp.ReplayGainTagParser
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.domain.model.RepeatMode
import com.droidamp.player.domain.model.VisualizerFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Foreground Media3 [MediaSessionService] hosting dual [ExoPlayer] decks (each with its own
 * [DroidAmpDspChain] for SPSC ring-buffer safety), equal-power crossfade + gapless playback,
 * system-surface-only [MediaMetadata] + artwork extraction, and live decoded format/ReplayGain
 * telemetry.
 */
@OptIn(UnstableApi::class)
class DroidAmpPlaybackService : MediaSessionService() {

  inner class LocalBinder : Binder() {
    val service: DroidAmpPlaybackService get() = this@DroidAmpPlaybackService
    val gateway: AudioEngineGateway get() = serviceGateway
  }

  private val localBinder = LocalBinder()
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private lateinit var artworkExtractor: SystemArtworkExtractor
  private lateinit var chain0: DroidAmpDspChain
  private lateinit var chain1: DroidAmpDspChain
  private lateinit var player0: ExoPlayer
  private lateinit var player1: ExoPlayer
  private lateinit var deck0: ExoCrossfadeDeck
  private lateinit var deck1: ExoCrossfadeDeck
  private lateinit var crossfadeController: CrossfadeController
  private lateinit var forwardingPlayer: WinampForwardingPlayer
  private var mediaSession: MediaSession? = null

  private val musicAudioAttributes: AudioAttributes =
    AudioAttributes.Builder()
      .setUsage(C.USAGE_MEDIA)
      .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
      .build()

  @Volatile
  private var activeDeckId: Int = 0

  private var gatewayListener: AudioEngineGateway.Listener? = null
  private var currentTracks: List<AudioTrack> = emptyList()
  private var currentMediaItems: List<MediaItem> = emptyList()
  private var currentDspSettings: DspSettings = DspSettings()
  private var currentEqualizerState: EqualizerState = EqualizerState()
  private var currentVolume: Int = PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME
  private var currentBalance: Int = 0
  private var currentRepeatMode: RepeatMode = RepeatMode.OFF
  private var currentShuffle: Boolean = false
  private var currentReplayGain: ReplayGainMetadata = ReplayGainMetadata.EMPTY

  private var artworkJob: Job? = null

  private val serviceGateway = ServiceGatewayImpl()

  override fun onCreate() {
    super.onCreate()
    artworkExtractor = SystemArtworkExtractor(this)

    chain0 = DroidAmpDspChain()
    chain1 = DroidAmpDspChain()

    player0 = buildExoPlayer(chain0, handleAudioFocus = true)
    player1 = buildExoPlayer(chain1, handleAudioFocus = false)

    attachDeckListeners(deckId = 0, player = player0)
    attachDeckListeners(deckId = 1, player = player1)

    deck0 = ExoCrossfadeDeck(deckId = 0, player = player0)
    deck1 = ExoCrossfadeDeck(deckId = 1, player = player1)

    crossfadeController =
      CrossfadeController(
        initialActiveDeck = deck0,
        initialStandbyDeck = deck1,
        scope = serviceScope,
        listener =
          object : CrossfadeController.Listener {
            override fun onCrossfadeStarted(
              incomingDeckId: Int,
              fromTrack: AudioTrack,
              toTrack: AudioTrack,
              toIndex: Int,
            ) {
              // Ensure incoming chain has synced config and clean ReplayGain state before ramp.
              chainForDeck(incomingDeckId).clearReplayGainMetadata()
            }

            override fun onCrossfadeCompleted(
              newActiveDeckId: Int,
              newStandbyDeckId: Int,
              newTrack: AudioTrack,
              newIndex: Int,
            ) {
              activeDeckId = newActiveDeckId
              val newActivePlayer = playerForDeck(newActiveDeckId)
              val newStandbyPlayer = playerForDeck(newStandbyDeckId)

              newStandbyPlayer.setAudioAttributes(musicAudioAttributes, false)
              newActivePlayer.setAudioAttributes(musicAudioAttributes, true)
              newActivePlayer.repeatMode = toMedia3RepeatMode(currentRepeatMode)
              newActivePlayer.shuffleModeEnabled = currentShuffle

              forwardingPlayer.swapDelegate(newActivePlayer)
              refreshSystemMediaMetadata(newTrack)
              gatewayListener?.onCrossfadeTrackAdvanced(newIndex, newTrack)
              emitFormatTelemetry(newActivePlayer.audioFormat, newActivePlayer.duration)
              for (group in newActivePlayer.currentTracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                  for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                      val format = group.getTrackFormat(i)
                      emitFormatTelemetry(format, newActivePlayer.duration)
                      emitReplayGainFromMetadata(format.metadata, newTrack.id)
                      break
                    }
                  }
                  break
                }
              }
            }
          },
      )

    forwardingPlayer = WinampForwardingPlayer(player0)

    val notificationProvider =
      DefaultMediaNotificationProvider.Builder(this)
        .setChannelId(NOTIFICATION_CHANNEL_ID)
        .setChannelName(R.string.app_name)
        .setNotificationId(NOTIFICATION_ID)
        .build()
    setMediaNotificationProvider(notificationProvider)

    val launchIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val sessionActivity =
      PendingIntent.getActivity(
        this,
        0,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val createdSession =
      MediaSession.Builder(this, forwardingPlayer)
        .setId(MEDIA_SESSION_ID)
        .setSessionActivity(sessionActivity)
        .build()
    mediaSession = createdSession
    addSession(createdSession)
  }

  override fun onBind(intent: Intent?): IBinder? {
    if (intent?.action == ACTION_LOCAL_BIND) {
      return localBinder
    }
    return super.onBind(intent)
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
    mediaSession

  override fun onTaskRemoved(rootIntent: Intent?) {
    val player = activePlayer()
    if (!player.playWhenReady || player.mediaItemCount == 0) {
      stopSelf()
    }
  }

  override fun onDestroy() {
    artworkJob?.cancel()
    crossfadeController.cancelCrossfade()
    mediaSession?.run {
      runCatching { removeSession(this) }
      release()
      mediaSession = null
    }
    player0.release()
    player1.release()
    serviceScope.cancel()
    super.onDestroy()
  }

  internal fun currentRuntimeSnapshot(): ServiceRuntimeSnapshot? {
    if (currentTracks.isEmpty()) return null
    val player = activePlayer()
    val status =
      when {
        player.isPlaying || player.playWhenReady && player.playbackState == Player.STATE_BUFFERING ->
          PlaybackStatus.PLAYING
        player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING ->
          PlaybackStatus.PAUSED
        else -> PlaybackStatus.STOPPED
      }
    return ServiceRuntimeSnapshot(
      status = status,
      queue = currentTracks,
      currentIndex = player.currentMediaItemIndex.coerceIn(0, currentTracks.lastIndex),
      positionMs = player.currentPosition.coerceAtLeast(0L),
      volume = currentVolume,
      balance = currentBalance,
      shuffle = currentShuffle,
      repeatMode = currentRepeatMode,
      equalizer = currentEqualizerState,
      dspSettings = currentDspSettings,
    )
  }

  private fun buildExoPlayer(
    dspChain: DroidAmpDspChain,
    handleAudioFocus: Boolean,
  ): ExoPlayer =
    ExoPlayer.Builder(this, DroidAmpAudioRenderersFactory(this, dspChain))
      .setAudioAttributes(musicAudioAttributes, handleAudioFocus)
      .setHandleAudioBecomingNoisy(true)
      .setWakeMode(C.WAKE_MODE_LOCAL)
      .setPauseAtEndOfMediaItems(false)
      .build()

  private fun attachDeckListeners(
    deckId: Int,
    player: ExoPlayer,
  ) {
    player.addListener(
      object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
          if (deckId != activeDeckId) return
          if (player.playbackState == Player.STATE_ENDED) return
          gatewayListener?.onPlaybackStatusChanged(isPlaying = isPlaying, isEnded = false)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
          if (deckId != activeDeckId) return
          if (playbackState == Player.STATE_ENDED) {
            gatewayListener?.onPlaybackStatusChanged(isPlaying = false, isEnded = true)
          } else if (playbackState == Player.STATE_READY) {
            emitFormatTelemetry(player.audioFormat, player.duration)
          }
        }

        override fun onMediaItemTransition(
          mediaItem: MediaItem?,
          reason: Int,
        ) {
          if (deckId != activeDeckId) return
          chainForDeck(deckId).clearReplayGainMetadata()
          val newIndex = player.currentMediaItemIndex
          val track = currentTracks.getOrNull(newIndex)
          if (track != null) {
            refreshSystemMediaMetadata(track)
          }
          gatewayListener?.onGaplessTrackChanged(newIndex, mediaItem?.mediaId)
        }

        override fun onTracksChanged(tracks: Tracks) {
          if (deckId != activeDeckId) return
          for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
              for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                  val format = group.getTrackFormat(i)
                  emitFormatTelemetry(format, player.duration)
                  emitReplayGainFromMetadata(format.metadata, player.currentMediaItem?.mediaId)
                  return
                }
              }
            }
          }
        }

        override fun onMetadata(metadata: Metadata) {
          if (deckId != activeDeckId) return
          emitReplayGainFromMetadata(metadata, player.currentMediaItem?.mediaId)
        }
      },
    )

    player.addAnalyticsListener(
      object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
          eventTime: AnalyticsListener.EventTime,
          format: Format,
          decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
          if (deckId != activeDeckId) return
          emitFormatTelemetry(format, player.duration)
          emitReplayGainFromMetadata(format.metadata, player.currentMediaItem?.mediaId)
        }
      },
    )
  }

  private fun emitFormatTelemetry(
    format: Format?,
    durationMs: Long,
  ) {
    val bitrateKbps =
      format?.let { f ->
        val raw = if (f.bitrate > 0) f.bitrate else f.averageBitrate
        if (raw > 0) (raw + 500) / 1000 else null
      }
    val sampleRateHz = format?.sampleRate?.takeIf { it > 0 }
    val channelMode =
      format?.channelCount?.takeIf { it > 0 }?.let { count ->
        if (count == 1) ChannelMode.MONO else ChannelMode.STEREO
      }
    val validDuration = durationMs.takeIf { it != C.TIME_UNSET && it > 0L }
    if (bitrateKbps != null || sampleRateHz != null || channelMode != null || validDuration != null) {
      gatewayListener?.onAudioFormatUpdated(
        bitrateKbps = bitrateKbps,
        sampleRateHz = sampleRateHz,
        channelMode = channelMode,
        durationMs = validDuration,
      )
    }
  }

  private fun emitReplayGainFromMetadata(
    metadata: Metadata?,
    trackId: String?,
  ) {
    if (metadata == null) return
    val parsed = ReplayGainTagParser.parseMetadata(metadata)
    if (parsed != ReplayGainMetadata.EMPTY) {
      gatewayListener?.onReplayGainMetadataParsed(trackId, parsed)
    }
  }

  private fun refreshSystemMediaMetadata(track: AudioTrack) {
    val baseMetadata = artworkExtractor.buildSystemMediaMetadata(track, artworkBytes = null)
    forwardingPlayer.updateSystemMetadata(baseMetadata)

    artworkJob?.cancel()
    artworkJob =
      serviceScope.launch {
        val artworkBytes = artworkExtractor.extractArtwork(track.uriString)
        val currentIdx = activePlayer().currentMediaItemIndex
        val currentActiveTrack = currentTracks.getOrNull(currentIdx)
        if (currentActiveTrack?.id == track.id) {
          val enriched = artworkExtractor.buildSystemMediaMetadata(track, artworkBytes)
          forwardingPlayer.updateSystemMetadata(enriched)
        }
      }
  }

  private fun activePlayer(): ExoPlayer = if (activeDeckId == 0) player0 else player1

  private fun playerForDeck(deckId: Int): ExoPlayer = if (deckId == 0) player0 else player1

  private fun activeChain(): DroidAmpDspChain = if (activeDeckId == 0) chain0 else chain1

  private fun chainForDeck(deckId: Int): DroidAmpDspChain = if (deckId == 0) chain0 else chain1

  private fun buildMediaItem(track: AudioTrack): MediaItem {
    val metadata = artworkExtractor.buildSystemMediaMetadata(track, artworkBytes = null)
    return MediaItem.Builder()
      .setMediaId(track.id)
      .setUri(Uri.parse(track.uriString))
      .setMediaMetadata(metadata)
      .build()
  }

  private fun toMedia3RepeatMode(mode: RepeatMode): Int =
    when (mode) {
      RepeatMode.OFF -> Player.REPEAT_MODE_OFF
      RepeatMode.ALL -> Player.REPEAT_MODE_ALL
      RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

  private inner class ExoCrossfadeDeck(
    override val deckId: Int,
    private val player: ExoPlayer,
  ) : CrossfadeDeck {
    override fun setPlayerVolume(volume0To1: Float) {
      player.volume = volume0To1.coerceIn(0f, 1f)
    }

    override fun setPauseAtEndOfMediaItems(pause: Boolean) {
      player.pauseAtEndOfMediaItems = pause
    }

    override fun prepareTrack(
      track: AudioTrack,
      startPositionMs: Long,
      playWhenReady: Boolean,
    ) {
      val candidateIndex =
        currentTracks.indexOfFirst { it.id == track.id }
      if (candidateIndex in currentMediaItems.indices) {
        player.setMediaItems(currentMediaItems, candidateIndex, startPositionMs)
      } else {
        player.setMediaItem(buildMediaItem(track), startPositionMs)
      }
      player.prepare()
      player.playWhenReady = playWhenReady
    }

    override fun play() {
      player.play()
    }

    override fun pause() {
      player.pause()
    }

    override fun stopAndClear() {
      player.stop()
      player.clearMediaItems()
    }
  }

  private inner class WinampForwardingPlayer(
    initialDelegate: ExoPlayer,
  ) : ForwardingPlayer(initialDelegate) {

    private val trackedListeners = CopyOnWriteArraySet<Player.Listener>()

    @Volatile
    private var overrideMetadata: MediaMetadata? = null

    fun swapDelegate(newDelegate: ExoPlayer) {
      val oldListeners = trackedListeners.toList()
      val prev = wrappedPlayer
      for (listener in oldListeners) {
        prev.removeListener(listener)
        newDelegate.addListener(listener)
      }
      // ForwardingPlayer stores wrappedPlayer in a private field in Media3; update MediaSession via setPlayer
      // if delegate reference must change.
      if (prev !== newDelegate) {
        val nextWrapper = WinampForwardingPlayer(newDelegate).also { wrapper ->
          wrapper.overrideMetadata = this.overrideMetadata
          wrapper.trackedListeners.addAll(oldListeners)
        }
        forwardingPlayer = nextWrapper
        mediaSession?.setPlayer(nextWrapper)
      }
    }

    fun updateSystemMetadata(metadata: MediaMetadata) {
      overrideMetadata = metadata
      for (listener in trackedListeners) {
        listener.onMediaMetadataChanged(metadata)
      }
    }

    override fun addListener(listener: Player.Listener) {
      trackedListeners.add(listener)
      super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
      trackedListeners.remove(listener)
      super.removeListener(listener)
    }

    override fun getMediaMetadata(): MediaMetadata = overrideMetadata ?: super.getMediaMetadata()

    override fun getAvailableCommands(): Player.Commands =
      super.getAvailableCommands()
        .buildUpon()
        .addAll(
          Player.COMMAND_PLAY_PAUSE,
          Player.COMMAND_STOP,
          Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
          Player.COMMAND_SEEK_TO_NEXT,
          Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
          Player.COMMAND_SEEK_TO_PREVIOUS,
          Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        .build()

    override fun play() {
      val listener = gatewayListener
      if (listener != null) {
        listener.onRemoteTransportAction(RemoteTransportAction.PLAY)
      } else {
        super.play()
      }
    }

    override fun pause() {
      val listener = gatewayListener
      if (listener != null) {
        listener.onRemoteTransportAction(RemoteTransportAction.PAUSE)
      } else {
        super.pause()
      }
    }

    override fun stop() {
      val listener = gatewayListener
      if (listener != null) {
        listener.onRemoteTransportAction(RemoteTransportAction.STOP)
      } else {
        crossfadeController.cancelCrossfade()
        super.pause()
        super.seekTo(0L)
        super.stop()
      }
    }

    override fun seekTo(positionMs: Long) {
      val listener = gatewayListener
      if (listener != null) {
        listener.onRemoteSeekTo(positionMs)
      } else {
        super.seekTo(positionMs)
      }
    }

    override fun seekToNext() {
      handleRemoteNext()
    }

    override fun seekToNextMediaItem() {
      handleRemoteNext()
    }

    override fun seekToPrevious() {
      handleRemotePrevious()
    }

    override fun seekToPreviousMediaItem() {
      handleRemotePrevious()
    }

    private fun handleRemoteNext() {
      val listener = gatewayListener
      if (listener != null) {
        listener.onRemoteTransportAction(RemoteTransportAction.NEXT)
      } else if (mediaItemCount > 0) {
        crossfadeController.cancelCrossfade()
        val nextIdx = (currentMediaItemIndex + 1) % mediaItemCount
        super.seekTo(nextIdx, 0L)
      }
    }

    private fun handleRemotePrevious() {
      val listener = gatewayListener
      if (listener != null) {
        listener.onRemoteTransportAction(RemoteTransportAction.PREVIOUS)
      } else if (mediaItemCount > 0) {
        crossfadeController.cancelCrossfade()
        if (currentPosition > Media3AudioEngineController.PREVIOUS_RESTART_THRESHOLD_MS) {
          super.seekTo(0L)
        } else {
          val prevIdx = if (currentMediaItemIndex <= 0) mediaItemCount - 1 else currentMediaItemIndex - 1
          super.seekTo(prevIdx, 0L)
        }
      }
    }
  }

  private inner class ServiceGatewayImpl : AudioEngineGateway {
    override val managesPlaylistTransitions: Boolean
      get() = true

    override fun setListener(listener: AudioEngineGateway.Listener?) {
      gatewayListener = listener
    }

    override fun setQueue(
      tracks: List<AudioTrack>,
      startIndex: Int,
      playWhenReady: Boolean,
    ) {
      crossfadeController.cancelCrossfade()
      currentTracks = tracks
      currentMediaItems = tracks.map(::buildMediaItem)
      val player = activePlayer()

      if (currentMediaItems.isEmpty()) {
        player.stop()
        player.clearMediaItems()
        return
      }

      val safeIdx = startIndex.coerceIn(0, currentMediaItems.lastIndex)
      player.repeatMode = toMedia3RepeatMode(currentRepeatMode)
      player.shuffleModeEnabled = currentShuffle
      player.setMediaItems(currentMediaItems, safeIdx, 0L)
      player.prepare()
      player.playWhenReady = playWhenReady
      refreshSystemMediaMetadata(tracks[safeIdx])
    }

    override fun syncQueueWithoutInterrupting(
      tracks: List<AudioTrack>,
      currentIndex: Int,
    ) {
      currentTracks = tracks
      currentMediaItems = tracks.map(::buildMediaItem)
      val player = activePlayer()

      if (currentMediaItems.isEmpty()) {
        crossfadeController.cancelCrossfade()
        player.stop()
        player.clearMediaItems()
        return
      }

      val safeIdx = currentIndex.coerceIn(0, currentMediaItems.lastIndex)
      val oldIdx = player.currentMediaItemIndex
      val count = player.mediaItemCount
      if (oldIdx in 0 until count && player.getMediaItemAt(oldIdx).mediaId == tracks[safeIdx].id) {
        if (oldIdx + 1 < count) {
          player.removeMediaItems(oldIdx + 1, count)
        }
        if (oldIdx > 0) {
          player.removeMediaItems(0, oldIdx)
        }
        if (safeIdx > 0) {
          player.addMediaItems(0, currentMediaItems.subList(0, safeIdx))
        }
        if (safeIdx + 1 < currentMediaItems.size) {
          player.addMediaItems(currentMediaItems.subList(safeIdx + 1, currentMediaItems.size))
        }
      } else {
        player.setMediaItems(currentMediaItems, safeIdx, 0L)
        player.prepare()
      }
    }

    override fun selectTrackAt(
      index: Int,
      playWhenReady: Boolean,
    ) {
      crossfadeController.cancelCrossfade()
      if (index !in currentTracks.indices) return
      val player = activePlayer()
      if (player.mediaItemCount != currentMediaItems.size) {
        player.setMediaItems(currentMediaItems, index, 0L)
        player.prepare()
      } else {
        player.seekTo(index, 0L)
      }
      player.playWhenReady = playWhenReady
      if (playWhenReady && player.playbackState == Player.STATE_IDLE) {
        player.prepare()
      }
      refreshSystemMediaMetadata(currentTracks[index])
    }

    override fun play() {
      val player = activePlayer()
      if (player.mediaItemCount == 0 && currentMediaItems.isNotEmpty()) {
        player.setMediaItems(currentMediaItems, 0, 0L)
      }
      if (player.playbackState == Player.STATE_IDLE) {
        player.prepare()
      }
      player.play()
    }

    override fun pause() {
      crossfadeController.cancelCrossfade()
      activePlayer().pause()
    }

    override fun stop() {
      crossfadeController.cancelCrossfade()
      val player = activePlayer()
      player.pause()
      if (player.mediaItemCount > 0) {
        player.seekTo(player.currentMediaItemIndex.coerceIn(0, player.mediaItemCount - 1), 0L)
      }
      player.stop()
    }

    override fun seekTo(positionMs: Long) {
      crossfadeController.cancelCrossfade()
      activePlayer().seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setRepeatMode(mode: RepeatMode) {
      currentRepeatMode = mode
      val m3Mode = toMedia3RepeatMode(mode)
      player0.repeatMode = m3Mode
      player1.repeatMode = m3Mode
    }

    override fun setShuffleEnabled(enabled: Boolean) {
      currentShuffle = enabled
      player0.shuffleModeEnabled = enabled
      player1.shuffleModeEnabled = enabled
    }

    override fun applyDspSettings(settings: DspSettings) {
      currentDspSettings = settings.sanitized()
      chain0.applyDspSettings(currentDspSettings)
      chain1.applyDspSettings(currentDspSettings)
      crossfadeController.updateSettings(currentDspSettings)
    }

    override fun applyEqualizerState(state: EqualizerState) {
      currentEqualizerState = state
      chain0.applyEqualizerState(state)
      chain1.applyEqualizerState(state)
    }

    override fun applyVolume(volume0To255: Int) {
      currentVolume = volume0To255.coerceIn(PlaybackState.MIN_VOLUME, PlaybackState.MAX_VOLUME)
      chain0.setVolume(currentVolume)
      chain1.setVolume(currentVolume)
    }

    override fun applyBalance(balanceMinus127To127: Int) {
      currentBalance = balanceMinus127To127.coerceIn(PlaybackState.MIN_BALANCE, PlaybackState.MAX_BALANCE)
      chain0.setBalance(currentBalance)
      chain1.setBalance(currentBalance)
    }

    override fun applyReplayGainMetadata(metadata: ReplayGainMetadata) {
      currentReplayGain = metadata
      activeChain().setReplayGainMetadata(metadata)
    }

    override fun readVisualizerFrame(isPlaying: Boolean): VisualizerFrame =
      activeChain().readVisualizerFrame(isPlaying)

    override fun resetVisualizer() {
      chain0.resetVisualizer()
      chain1.resetVisualizer()
    }

    override fun currentPositionMs(): Long = activePlayer().currentPosition.coerceAtLeast(0L)

    override fun currentDurationMs(): Long {
      val raw = activePlayer().duration
      return if (raw == C.TIME_UNSET || raw < 0L) 0L else raw
    }

    override fun onCrossfadeProgressTick(
      isPlaying: Boolean,
      positionMs: Long,
      durationMs: Long,
      currentTrack: AudioTrack?,
      nextCandidate: CrossfadeController.NextTrackCandidate?,
    ) {
      crossfadeController.onProgress(
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        currentTrack = currentTrack,
        nextCandidate = nextCandidate,
      )
    }

    override fun cancelCrossfade() {
      crossfadeController.cancelCrossfade()
    }
  }

  companion object {
    const val ACTION_LOCAL_BIND = "com.droidamp.player.audio.service.LOCAL_BIND"
    private const val MEDIA_SESSION_ID = "droidamp_media_session"
    private const val NOTIFICATION_CHANNEL_ID = "droidamp_playback_channel"
    private const val NOTIFICATION_ID = 1001
  }
}
