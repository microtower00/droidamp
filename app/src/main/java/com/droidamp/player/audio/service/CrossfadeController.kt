package com.droidamp.player.audio.service

import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure-Kotlin deck abstraction enabling deterministic JVM unit testing of dual-deck
 * equal-power crossfade and single-deck gapless playback without Robolectric.
 */
interface CrossfadeDeck {
  val deckId: Int
  fun setPlayerVolume(volume0To1: Float)
  fun setPauseAtEndOfMediaItems(pause: Boolean)
  fun prepareTrack(track: AudioTrack, startPositionMs: Long, playWhenReady: Boolean)
  fun play()
  fun pause()
  fun stopAndClear()
}

/**
 * Manages gapless playback (`crossfadeSeconds == 0`) and dual-deck equal-power crossfades
 * (`1..12s`) via `Player.setVolume(float)`, completely orthogonal to Winamp's internal
 * [com.droidamp.player.audio.dsp.VolumeBalanceProcessor] (`0..255`).
 */
class CrossfadeController(
  initialActiveDeck: CrossfadeDeck,
  initialStandbyDeck: CrossfadeDeck,
  private val scope: CoroutineScope,
  private val listener: Listener,
  private val rampStepIntervalMs: Long = DEFAULT_RAMP_STEP_INTERVAL_MS,
) {
  interface Listener {
    fun onCrossfadeStarted(
      incomingDeckId: Int,
      fromTrack: AudioTrack,
      toTrack: AudioTrack,
      toIndex: Int,
    ) {}

    fun onCrossfadeCompleted(
      newActiveDeckId: Int,
      newStandbyDeckId: Int,
      newTrack: AudioTrack,
      newIndex: Int,
    )
  }

  data class NextTrackCandidate(
    val index: Int,
    val track: AudioTrack,
  )

  var activeDeck: CrossfadeDeck = initialActiveDeck
    private set

  var standbyDeck: CrossfadeDeck = initialStandbyDeck
    private set

  var isCrossfading: Boolean = false
    private set

  private var settings: DspSettings = DspSettings()
  private var crossfadeJob: Job? = null

  init {
    activeDeck.setPlayerVolume(1f)
    activeDeck.setPauseAtEndOfMediaItems(false)
    standbyDeck.setPlayerVolume(0f)
    standbyDeck.setPauseAtEndOfMediaItems(false)
  }

  fun updateSettings(newSettings: DspSettings) {
    val sanitized = newSettings.sanitized()
    settings = sanitized
    if (sanitized.crossfadeSeconds <= 0) {
      cancelCrossfade()
    }
    if (!isCrossfading) {
      // Even when gaplessEnabled == false and crossfadeSeconds == 0, keep pauseAtEndOfMediaItems = false
      // so queued tracks continue advancing rather than stalling after every song.
      activeDeck.setPauseAtEndOfMediaItems(false)
    }
  }

  fun onProgress(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    currentTrack: AudioTrack?,
    nextCandidate: NextTrackCandidate?,
  ) {
    if (isCrossfading) return
    if (!isPlaying || currentTrack == null || nextCandidate == null) return

    val effectiveFadeMs = computeEffectiveFadeMs(settings.crossfadeSeconds, durationMs)
    if (effectiveFadeMs <= 0L) return

    val remainingMs = durationMs - positionMs
    if (remainingMs in 1L..effectiveFadeMs) {
      startCrossfade(
        outgoingTrack = currentTrack,
        incoming = nextCandidate,
        fadeDurationMs = remainingMs.coerceAtLeast(rampStepIntervalMs),
      )
    }
  }

  fun cancelCrossfade() {
    crossfadeJob?.cancel()
    crossfadeJob = null
    isCrossfading = false

    standbyDeck.setPlayerVolume(0f)
    standbyDeck.pause()
    standbyDeck.stopAndClear()

    activeDeck.setPlayerVolume(1f)
    activeDeck.setPauseAtEndOfMediaItems(false)
  }

  private fun startCrossfade(
    outgoingTrack: AudioTrack,
    incoming: NextTrackCandidate,
    fadeDurationMs: Long,
  ) {
    crossfadeJob?.cancel()
    isCrossfading = true

    val outgoing = activeDeck
    val incomingDeck = standbyDeck

    // Prevent outgoing deck from advancing its internal playlist behind the incoming deck.
    outgoing.setPauseAtEndOfMediaItems(true)
    outgoing.setPlayerVolume(1f)

    incomingDeck.setPauseAtEndOfMediaItems(false)
    incomingDeck.setPlayerVolume(0f)
    incomingDeck.prepareTrack(incoming.track, startPositionMs = 0L, playWhenReady = true)
    incomingDeck.play()

    listener.onCrossfadeStarted(
      incomingDeckId = incomingDeck.deckId,
      fromTrack = outgoingTrack,
      toTrack = incoming.track,
      toIndex = incoming.index,
    )

    val stepMs = rampStepIntervalMs.coerceAtLeast(1L)
    val totalSteps = (fadeDurationMs / stepMs).toInt().coerceAtLeast(1)

    crossfadeJob =
      scope.launch {
        for (step in 1..totalSteps) {
          delay(stepMs)
          val progress = step.toFloat() / totalSteps.toFloat()
          val (outGain, inGain) = computeEqualPowerGains(progress)
          outgoing.setPlayerVolume(outGain)
          incomingDeck.setPlayerVolume(inGain)
        }

        // Complete transition & swap active/standby decks.
        outgoing.setPlayerVolume(0f)
        outgoing.pause()
        outgoing.stopAndClear()
        outgoing.setPauseAtEndOfMediaItems(false)

        incomingDeck.setPlayerVolume(1f)
        incomingDeck.setPauseAtEndOfMediaItems(false)

        activeDeck = incomingDeck
        standbyDeck = outgoing
        isCrossfading = false
        crossfadeJob = null

        listener.onCrossfadeCompleted(
          newActiveDeckId = incomingDeck.deckId,
          newStandbyDeckId = outgoing.deckId,
          newTrack = incoming.track,
          newIndex = incoming.index,
        )
      }
  }

  companion object {
    const val DEFAULT_RAMP_STEP_INTERVAL_MS = 25L
    const val MIN_TRACK_DURATION_FOR_CROSSFADE_MS = 2_000L

    fun computeEqualPowerGains(progress0To1: Float): Pair<Float, Float> {
      val clamped = progress0To1.coerceIn(0f, 1f).toDouble()
      val angle = clamped * (PI / 2.0)
      val outGain = cos(angle).toFloat().coerceIn(0f, 1f)
      val inGain = sin(angle).toFloat().coerceIn(0f, 1f)
      return outGain to inGain
    }

    fun computeEffectiveFadeMs(
      crossfadeSeconds: Int,
      trackDurationMs: Long,
    ): Long {
      val clampedSeconds =
        crossfadeSeconds.coerceIn(
          DspSettings.MIN_CROSSFADE_SECONDS,
          DspSettings.MAX_CROSSFADE_SECONDS,
        )
      if (clampedSeconds <= 0 || trackDurationMs < MIN_TRACK_DURATION_FOR_CROSSFADE_MS) {
        return 0L
      }
      return minOf(clampedSeconds * 1000L, trackDurationMs / 2L)
    }

    fun resolveNextCandidate(
      queue: List<AudioTrack>,
      currentIndex: Int,
      repeatMode: RepeatMode,
      shuffle: Boolean,
      shuffledNextIndex: Int? = null,
    ): NextTrackCandidate? {
      if (queue.isEmpty() || currentIndex !in queue.indices) return null
      return when (repeatMode) {
        RepeatMode.ONE -> NextTrackCandidate(currentIndex, queue[currentIndex])
        RepeatMode.ALL -> {
          val nextIndex =
            if (shuffle && queue.size > 1) {
              shuffledNextIndex?.takeIf { it in queue.indices && it != currentIndex }
                ?: queue.indices.first { it != currentIndex }
            } else {
              (currentIndex + 1) % queue.size
            }
          NextTrackCandidate(nextIndex, queue[nextIndex])
        }
        RepeatMode.OFF -> {
          if (shuffle && queue.size > 1) {
            val nextIndex =
              shuffledNextIndex?.takeIf { it in queue.indices && it != currentIndex }
                ?: queue.indices.first { it != currentIndex }
            NextTrackCandidate(nextIndex, queue[nextIndex])
          } else if (currentIndex < queue.lastIndex) {
            val nextIndex = currentIndex + 1
            NextTrackCandidate(nextIndex, queue[nextIndex])
          } else {
            null
          }
        }
      }
    }
  }
}
