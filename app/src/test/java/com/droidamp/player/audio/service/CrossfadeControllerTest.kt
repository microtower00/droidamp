package com.droidamp.player.audio.service

import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.RepeatMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class CrossfadeControllerTest {

  private class FakeCrossfadeDeck(
    override val deckId: Int,
  ) : CrossfadeDeck {
    var volume = 0f
    var pauseAtEnd = false
    var preparedTrack: AudioTrack? = null
    var isPlaying = false
    var stoppedAndClearedCount = 0
    val recordedVolumes = mutableListOf<Float>()

    override fun setPlayerVolume(volume0To1: Float) {
      volume = volume0To1
      recordedVolumes.add(volume0To1)
    }

    override fun setPauseAtEndOfMediaItems(pause: Boolean) {
      pauseAtEnd = pause
    }

    override fun prepareTrack(
      track: AudioTrack,
      startPositionMs: Long,
      playWhenReady: Boolean,
    ) {
      preparedTrack = track
      isPlaying = playWhenReady
    }

    override fun play() {
      isPlaying = true
    }

    override fun pause() {
      isPlaying = false
    }

    override fun stopAndClear() {
      isPlaying = false
      preparedTrack = null
      stoppedAndClearedCount++
    }
  }

  private fun track(
    id: String,
    durationMs: Long = 30_000L,
  ) = AudioTrack(
    id = id,
    uriString = "file:///music/$id.mp3",
    title = "Track $id",
    artist = "Artist",
    fileName = "$id.mp3",
    folderPath = "/music",
    durationMs = durationMs,
  )

  @Test
  fun equalPowerCurve_preservesConstantPowerAcrossRamp() {
    val (out0, in0) = CrossfadeController.computeEqualPowerGains(0f)
    assertEquals(1f, out0, 0.0001f)
    assertEquals(0f, in0, 0.0001f)

    val (outMid, inMid) = CrossfadeController.computeEqualPowerGains(0.5f)
    assertEquals(0.7071f, outMid, 0.001f)
    assertEquals(0.7071f, inMid, 0.001f)
    assertEquals(1.0f, outMid * outMid + inMid * inMid, 0.001f)

    val (out1, in1) = CrossfadeController.computeEqualPowerGains(1f)
    assertEquals(0f, out1, 0.0001f)
    assertEquals(1f, in1, 0.0001f)
  }

  @Test
  fun gaplessMode_crossfadeZero_neverActivatesStandbyDeck() = runTest {
    val deck0 = FakeCrossfadeDeck(0)
    val deck1 = FakeCrossfadeDeck(1)
    var completed = false

    val controller =
      CrossfadeController(
        initialActiveDeck = deck0,
        initialStandbyDeck = deck1,
        scope = backgroundScope,
        listener =
          object : CrossfadeController.Listener {
            override fun onCrossfadeCompleted(
              newActiveDeckId: Int,
              newStandbyDeckId: Int,
              newTrack: AudioTrack,
              newIndex: Int,
            ) {
              completed = true
            }
          },
      )

    controller.updateSettings(DspSettings(gaplessEnabled = true, crossfadeSeconds = 0))
    val t0 = track("t0", 10_000L)
    val t1 = track("t1", 10_000L)
    controller.onProgress(
      isPlaying = true,
      positionMs = 9_500L,
      durationMs = 10_000L,
      currentTrack = t0,
      nextCandidate = CrossfadeController.NextTrackCandidate(1, t1),
    )
    advanceUntilIdle()

    assertFalse(controller.isCrossfading)
    assertFalse(completed)
    assertFalse(deck0.pauseAtEnd)
    assertEquals(1f, deck0.volume, 0.001f)
    assertNull(deck1.preparedTrack)
    assertEquals(0f, deck1.volume, 0.001f)
  }

  @Test
  fun crossfade_executesEqualPowerSwap_andRespectsShortTrackClamp() = runTest {
    val deck0 = FakeCrossfadeDeck(0)
    val deck1 = FakeCrossfadeDeck(1)
    var swappedNewActiveId = -1
    var swappedTrack: AudioTrack? = null
    var swappedIndex = -1

    val controller =
      CrossfadeController(
        initialActiveDeck = deck0,
        initialStandbyDeck = deck1,
        scope = this,
        rampStepIntervalMs = 100L,
        listener =
          object : CrossfadeController.Listener {
            override fun onCrossfadeCompleted(
              newActiveDeckId: Int,
              newStandbyDeckId: Int,
              newTrack: AudioTrack,
              newIndex: Int,
            ) {
              swappedNewActiveId = newActiveDeckId
              swappedTrack = newTrack
              swappedIndex = newIndex
            }
          },
      )

    // 10s crossfade on a 6s track -> clamped to 3000ms (half track duration)
    assertEquals(3_000L, CrossfadeController.computeEffectiveFadeMs(10, 6_000L))
    assertEquals(0L, CrossfadeController.computeEffectiveFadeMs(10, 1_500L))

    controller.updateSettings(DspSettings(crossfadeSeconds = 10))
    val t0 = track("t0", 6_000L)
    val t1 = track("t1", 10_000L)

    // At 2_500ms (remaining 3_500ms > 3_000ms), crossfade must NOT start yet
    controller.onProgress(
      isPlaying = true,
      positionMs = 2_500L,
      durationMs = 6_000L,
      currentTrack = t0,
      nextCandidate = CrossfadeController.NextTrackCandidate(1, t1),
    )
    assertFalse(controller.isCrossfading)

    // At 3_000ms (remaining 3_000ms <= 3_000ms), crossfade starts
    controller.onProgress(
      isPlaying = true,
      positionMs = 3_000L,
      durationMs = 6_000L,
      currentTrack = t0,
      nextCandidate = CrossfadeController.NextTrackCandidate(1, t1),
    )
    assertTrue(controller.isCrossfading)
    assertTrue(deck0.pauseAtEnd)
    assertEquals(t1, deck1.preparedTrack)
    assertTrue(deck1.isPlaying)

    // Halfway through 3000ms ramp
    advanceTimeBy(1_500L)
    assertTrue(controller.isCrossfading)
    assertTrue(abs(deck0.volume * deck0.volume + deck1.volume * deck1.volume - 1f) < 0.05f)

    // Finish ramp
    advanceUntilIdle()
    assertFalse(controller.isCrossfading)
    assertEquals(1, swappedNewActiveId)
    assertEquals(t1, swappedTrack)
    assertEquals(1, swappedIndex)
    assertEquals(1, controller.activeDeck.deckId)
    assertEquals(0, controller.standbyDeck.deckId)
    assertEquals(0f, deck0.volume, 0.001f)
    assertFalse(deck0.isPlaying)
    assertNull(deck0.preparedTrack)
    assertEquals(1f, deck1.volume, 0.001f)
    assertFalse(deck1.pauseAtEnd)
  }

  @Test
  fun cancelCrossfade_immediatelySilencesStandbyAndRestoresActive() = runTest {
    val deck0 = FakeCrossfadeDeck(0)
    val deck1 = FakeCrossfadeDeck(1)
    var completed = false

    val controller =
      CrossfadeController(
        initialActiveDeck = deck0,
        initialStandbyDeck = deck1,
        scope = this,
        rampStepIntervalMs = 50L,
        listener =
          object : CrossfadeController.Listener {
            override fun onCrossfadeCompleted(
              newActiveDeckId: Int,
              newStandbyDeckId: Int,
              newTrack: AudioTrack,
              newIndex: Int,
            ) {
              completed = true
            }
          },
      )

    controller.updateSettings(DspSettings(crossfadeSeconds = 4))
    val t0 = track("t0", 20_000L)
    val t1 = track("t1", 20_000L)

    controller.onProgress(
      isPlaying = true,
      positionMs = 16_000L,
      durationMs = 20_000L,
      currentTrack = t0,
      nextCandidate = CrossfadeController.NextTrackCandidate(1, t1),
    )
    advanceTimeBy(1_000L)
    assertTrue(controller.isCrossfading)

    // Manual skip/seek/pause/stop cancellation
    controller.cancelCrossfade()
    advanceUntilIdle()

    assertFalse(controller.isCrossfading)
    assertFalse(completed)
    assertEquals(1f, deck0.volume, 0.001f)
    assertFalse(deck0.pauseAtEnd)
    assertEquals(0f, deck1.volume, 0.001f)
    assertFalse(deck1.isPlaying)
    assertNull(deck1.preparedTrack)
    assertTrue(deck1.stoppedAndClearedCount >= 1)
  }

  @Test
  fun resolveNextCandidate_respectsRepeatModesAndLastTrack() {
    val queue = listOf(track("a"), track("b"))

    // Repeat OFF on last track -> null (no crossfade)
    assertNull(
      CrossfadeController.resolveNextCandidate(
        queue = queue,
        currentIndex = 1,
        repeatMode = RepeatMode.OFF,
        shuffle = false,
      ),
    )

    // Repeat ALL on last track -> wraps to index 0
    val wrap =
      CrossfadeController.resolveNextCandidate(
        queue = queue,
        currentIndex = 1,
        repeatMode = RepeatMode.ALL,
        shuffle = false,
      )
    assertEquals(0, wrap?.index)
    assertEquals("a", wrap?.track?.id)

    // Repeat ONE -> self-crossfade on current index
    val repeatOne =
      CrossfadeController.resolveNextCandidate(
        queue = queue,
        currentIndex = 1,
        repeatMode = RepeatMode.ONE,
        shuffle = false,
      )
    assertEquals(1, repeatOne?.index)
    assertEquals("b", repeatOne?.track?.id)
  }
}
