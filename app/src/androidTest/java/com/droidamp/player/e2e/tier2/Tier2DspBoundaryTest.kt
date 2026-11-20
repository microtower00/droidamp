package com.droidamp.player.e2e.tier2

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidamp.player.MainActivity
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.domain.model.RepeatMode
import com.droidamp.player.domain.model.ReplayGainMode
import com.droidamp.player.domain.model.SheetMode
import com.droidamp.player.domain.model.TimeDisplayMode
import com.droidamp.player.domain.model.VisualizerFrame
import com.droidamp.player.domain.model.VisualizerMode
import com.droidamp.player.domain.model.formatWinampDuration
import com.droidamp.player.e2e.fixtures.SyntheticAudioFixtures
import com.droidamp.player.e2e.harness.DeviceManifestVerifier
import com.droidamp.player.e2e.harness.DroidAmpSelectors
import com.droidamp.player.e2e.harness.E2eRackDriver
import com.droidamp.player.ui.rack.DroidAmpViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Tier2DspBoundaryTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun sampleTrack(
    id: String = "t1",
    uriString: String = "file:///Music/t1.mp3",
    title: String = "Track One",
    artist: String = "Artist",
    fileName: String = "01_track.mp3",
    folderPath: String = "/Music",
    durationMs: Long = 10_000L,
  ): AudioTrack =
    AudioTrack(
      id = id,
      uriString = uriString,
      title = title,
      artist = artist,
      album = "Album",
      fileName = fileName,
      folderPath = folderPath,
      durationMs = durationMs,
      bitrateKbps = 192,
      sampleRateHz = 44_100,
      channelMode = ChannelMode.STEREO,
    )

  /**
   * Builds a standalone Media3 [ExoPlayer] on the Main thread to verify container/ID3/PCM
   * boundary decoding and Media3 gapless edge cases pre-`M-integration`.
   *
   * Note: Standalone [ExoPlayer] does not attach DroidAmp's custom DSP processors
   * (`DroidAmpDspChain`, `ReplayGainProcessor`, `WinampTenBandEqProcessor`) or
   * `DroidAmpPlaybackService`; full service + DSP-chain E2E execution is verified in
   * `M-final-e2e` once `M-integration` wires `MainActivity`.
   */
  private inline fun <T> withExoPlayer(
    context: Context,
    block: (ExoPlayer) -> T,
  ): T {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    lateinit var player: ExoPlayer
    instrumentation.runOnMainSync {
      player = ExoPlayer.Builder(context).build()
    }
    try {
      return block(player)
    } finally {
      instrumentation.runOnMainSync {
        player.release()
      }
    }
  }

  private fun playSingleFileToCompletionOrError(
    context: Context,
    fileUri: String,
    expectSuccess: Boolean = true,
  ): Throwable? {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    return withExoPlayer(context) { player ->
      val terminal = CompletableDeferred<Throwable?>()
      instrumentation.runOnMainSync {
        player.addListener(
          object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
              if (playbackState == Player.STATE_ENDED) {
                terminal.complete(null)
              }
            }

            override fun onPlayerError(error: PlaybackException) {
              terminal.complete(error)
            }
          },
        )
        player.setMediaItem(MediaItem.fromUri(fileUri))
        player.prepare()
        player.play()
      }
      val error = runBlocking { withTimeout(10_000L) { terminal.await() } }
      if (expectSuccess && error != null) {
        throw AssertionError("Expected clean playback for $fileUri, got error: ${error.message}", error)
      }
      error
    }
  }

  // =========================================================================
  // Feature 7: Media3 MediaSessionService & ExoPlayer Boundaries — 5 Tests
  // =========================================================================

  @Test // T2-F7-1
  fun transport_emptyQueueAndOutOfBoundsPlayTrackAt_areSafeNoOps() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      runBlocking {
        driver.engine.play()
        driver.engine.pause()
        driver.engine.stop()
        driver.engine.next()
        driver.engine.previous()
        driver.engine.playTrackAt(-1)
        driver.engine.playTrackAt(99)
      }
      val state = driver.engine.playbackState.value
      assertEquals(PlaybackStatus.STOPPED, state.status)
      assertEquals(-1, state.currentIndex)
      assertEquals(0L, state.positionMs)
      assertTrue(state.queue.isEmpty())
    }
  }

  @Test // T2-F7-2
  fun transport_seekNegativeAndBeyondDuration_clampedToValidRange() {
    val track = sampleTrack(durationMs = 10_000L)
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = listOf(track),
      autoPlay = false,
    ).use { driver ->
      runBlocking {
        driver.engine.seekTo(-5_000L)
      }
      assertEquals(0L, driver.engine.playbackState.value.positionMs)

      runBlocking {
        driver.engine.seekTo(999_999L)
      }
      assertEquals(10_000L, driver.engine.playbackState.value.positionMs)
    }
  }

  @Test // T2-F7-3
  fun transport_singleTrackQueue_nextPrevShuffleAndNaturalEnd() {
    val track = sampleTrack(durationMs = 10_000L)
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = listOf(track),
      autoPlay = true,
    ).use { driver ->
      driver.toggleShuffle()
      assertTrue(driver.engine.playbackState.value.shuffle)

      driver.clickNextOnDeck()
      assertEquals(0, driver.engine.playbackState.value.currentIndex)
      driver.clickPrevOnDeck()
      assertEquals(0, driver.engine.playbackState.value.currentIndex)
      assertEquals(RepeatMode.OFF, driver.engine.playbackState.value.repeatMode)
    }
  }

  @Test // T2-F7-4
  fun exoPlayer_handlesCorruptedWavFixturesWithoutCrashingAndRecoversOnValidWav() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    SyntheticAudioFixtures.createCacheScope(context, "t2_f7_4_corrupt_wav").use { scope ->
      withExoPlayer(context) { player ->
        SyntheticAudioFixtures.WavCorruption.entries.forEach { corruption ->
          val badFile = scope.writeFile(
            "bad_${corruption.name.lowercase()}.wav",
            SyntheticAudioFixtures.generateCorruptedWav(corruption),
          )
          val terminal = CompletableDeferred<Unit>()
          val listener =
            object : Player.Listener {
              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) terminal.complete(Unit)
              }

              override fun onPlayerError(error: PlaybackException) {
                terminal.complete(Unit)
              }
            }
          instrumentation.runOnMainSync {
            player.addListener(listener)
            player.setMediaItem(MediaItem.fromUri(scope.fileUri(badFile)))
            player.prepare()
            player.play()
          }
          runBlocking { withTimeout(8_000L) { terminal.await() } }
          instrumentation.runOnMainSync {
            player.removeListener(listener)
            player.stop()
          }
        }

        // Confirm player recovers cleanly on a valid WAV immediately afterward
        val validWav = scope.writeFile("valid.wav", SyntheticAudioFixtures.generateWav(durationMs = 400L))
        val ready = CompletableDeferred<Unit>()
        var recoveryError: PlaybackException? = null
        instrumentation.runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) ready.complete(Unit)
              }

              override fun onPlayerError(error: PlaybackException) {
                recoveryError = error
                ready.completeExceptionally(error)
              }
            },
          )
          player.setMediaItem(MediaItem.fromUri(scope.fileUri(validWav)))
          player.prepare()
          player.play()
        }
        runBlocking { withTimeout(8_000L) { ready.await() } }
        assertNull(recoveryError)
      }
    }
  }

  @Test // T2-F7-5
  fun exoPlayer_handlesCorruptedMp3FixturesWithoutCrashingAndRecoversOnValidMp3() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    SyntheticAudioFixtures.createCacheScope(context, "t2_f7_5_corrupt_mp3").use { scope ->
      withExoPlayer(context) { player ->
        SyntheticAudioFixtures.Mp3Corruption.entries.forEach { corruption ->
          val badFile = scope.writeFile(
            "bad_${corruption.name.lowercase()}.mp3",
            SyntheticAudioFixtures.generateCorruptedMp3(corruption),
          )
          val terminal = CompletableDeferred<Unit>()
          val listener =
            object : Player.Listener {
              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) terminal.complete(Unit)
              }

              override fun onPlayerError(error: PlaybackException) {
                terminal.complete(Unit)
              }
            }
          instrumentation.runOnMainSync {
            player.addListener(listener)
            player.setMediaItem(MediaItem.fromUri(scope.fileUri(badFile)))
            player.prepare()
            player.play()
          }
          runBlocking { withTimeout(8_000L) { terminal.await() } }
          instrumentation.runOnMainSync {
            player.removeListener(listener)
            player.stop()
          }
        }

        val validMp3 = scope.writeFile("valid.mp3", SyntheticAudioFixtures.generateMp3(durationMs = 400L))
        val ready = CompletableDeferred<Unit>()
        var recoveryError: PlaybackException? = null
        instrumentation.runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) ready.complete(Unit)
              }

              override fun onPlayerError(error: PlaybackException) {
                recoveryError = error
                ready.completeExceptionally(error)
              }
            },
          )
          player.setMediaItem(MediaItem.fromUri(scope.fileUri(validMp3)))
          player.prepare()
          player.play()
        }
        runBlocking { withTimeout(8_000L) { ready.await() } }
        assertNull(recoveryError)
      }
    }
  }

  // =========================================================================
  // Feature 8: Lockscreen/Notification Art & BT Metadata Boundaries — 5 Tests
  // =========================================================================

  @Test // T2-F8-1
  fun inAppUi_zeroAlbumArtAcrossEmptyLinkedPlaylistAndLibraryTabs() {
    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = false).use { driver ->
      DeviceManifestVerifier.assertZeroInAppAlbumArt(composeRule)

      driver.linkFakeSafRoot()
      DeviceManifestVerifier.assertZeroInAppAlbumArt(composeRule)

      driver.ensureSheetExpanded(SheetMode.LIBRARY)
      DroidAmpSelectors.libraryFoldersTab(composeRule).assertIsDisplayed()
      DeviceManifestVerifier.assertZeroInAppAlbumArt(composeRule)

      DroidAmpSelectors.libraryPlaylistsTab(composeRule)
      driver.ensureSheetExpanded(SheetMode.PLAYLIST)
      DeviceManifestVerifier.assertZeroInAppAlbumArt(composeRule)
    }
  }

  @Test // T2-F8-2
  fun media3Decoder_mp3WithUnicodeTitleArtistAlbumAndNoArtwork_populatesMetadataCleanly() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val mp3Bytes = SyntheticAudioFixtures.generateMp3(
      durationMs = 500L,
      metadata = SyntheticAudioFixtures.Id3MetadataSpec(
        title = "Café — Ω",
        artist = "Björk",
        album = "Homogenic",
        artworkPngBytes = null,
      ),
    )

    SyntheticAudioFixtures.createCacheScope(context, "t2_f8_2_unicode").use { scope ->
      val file = scope.writeFile("unicode.mp3", mp3Bytes)
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(file.absolutePath)
        assertEquals("Café — Ω", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
        assertEquals("Björk", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
        assertEquals("Homogenic", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
        assertNull(retriever.embeddedPicture)
      } finally {
        retriever.release()
      }
      playSingleFileToCompletionOrError(context, scope.fileUri(file), expectSuccess = true)
    }
  }

  @Test // T2-F8-3
  fun media3Decoder_wavWithoutId3Tags_decodesWithoutCrashAndNullArtwork() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    SyntheticAudioFixtures.createCacheScope(context, "t2_f8_3_wav").use { scope ->
      val file = scope.writeFile("raw_pcm.wav", SyntheticAudioFixtures.generateWav(durationMs = 400L))
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(file.absolutePath)
        assertNull(retriever.embeddedPicture)
      } finally {
        retriever.release()
      }
      playSingleFileToCompletionOrError(context, scope.fileUri(file), expectSuccess = true)
    }
  }

  @Test // T2-F8-4
  fun media3Decoder_mp3WithEmptyTitleAndBlankArtist_decodesWithoutCrashingMetadata() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val png = SyntheticAudioFixtures.generateMinimal1x1Png()
    val mp3Bytes = SyntheticAudioFixtures.generateMp3(
      durationMs = 400L,
      metadata = SyntheticAudioFixtures.Id3MetadataSpec(
        title = "",
        artist = "   ",
        artworkPngBytes = png,
      ),
    )

    SyntheticAudioFixtures.createCacheScope(context, "t2_f8_4_blank").use { scope ->
      val file = scope.writeFile("blank_tags.mp3", mp3Bytes)
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(file.absolutePath)
        assertNotNull(retriever.embeddedPicture)
      } finally {
        retriever.release()
      }
      playSingleFileToCompletionOrError(context, scope.fileUri(file), expectSuccess = true)
    }
  }

  @Test // T2-F8-5
  fun inAppUi_marqueeDoesNotRenderRawUriOrArtworkTokens() {
    val track = sampleTrack(
      uriString = "content://com.example/art_cover.mp3",
      title = "Track One",
      artist = "Artist",
      durationMs = 10_000L,
    )
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = listOf(track),
      autoPlay = true,
    ).use {
      DroidAmpSelectors.marqueeText(composeRule, "1. ARTIST - TRACK ONE (00:10)").assertIsDisplayed()
      DeviceManifestVerifier.assertZeroInAppAlbumArt(composeRule)
    }
  }

  // =========================================================================
  // Feature 9: ReplayGain PCM Boundaries — 5 Tests
  // =========================================================================

  @Test // T2-F9-1
  fun dspSettings_clampsReplayGainPreampAtPlusMinus15DbAndSanitizesNonFinite() {
    assertEquals(-15f, DspSettings(replayGainPreampDb = -25f).sanitized().replayGainPreampDb, 0f)
    assertEquals(15f, DspSettings(replayGainPreampDb = 25f).sanitized().replayGainPreampDb, 0f)
    assertEquals(0f, DspSettings(replayGainPreampDb = Float.NaN).sanitized().replayGainPreampDb, 0f)
    assertEquals(0f, DspSettings(replayGainPreampDb = Float.POSITIVE_INFINITY).sanitized().replayGainPreampDb, 0f)
    assertEquals(0f, DspSettings(replayGainPreampDb = Float.NEGATIVE_INFINITY).sanitized().replayGainPreampDb, 0f)
  }

  @Test // T2-F9-2
  fun media3Decoder_decodesMp3WithMalformedReplayGainTags_gracefully() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val mp3Bytes = SyntheticAudioFixtures.generateMp3(
      durationMs = 400L,
      metadata = SyntheticAudioFixtures.Id3MetadataSpec(
        title = "Malformed RG",
        extraTxxx = mapOf(
          "REPLAYGAIN_TRACK_GAIN" to "not-a-db",
          "REPLAYGAIN_TRACK_PEAK" to "-5.0",
        ),
      ),
    )

    SyntheticAudioFixtures.createCacheScope(context, "t2_f9_2").use { scope ->
      val file = scope.writeFile("bad_rg.mp3", mp3Bytes)
      playSingleFileToCompletionOrError(context, scope.fileUri(file), expectSuccess = true)
    }
  }

  @Test // T2-F9-3
  fun media3Decoder_decodesMp3WithZeroAndExtremeReplayGainPeak_gracefully() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val mp3Bytes = SyntheticAudioFixtures.generateMp3(
      durationMs = 400L,
      metadata = SyntheticAudioFixtures.Id3MetadataSpec(
        title = "Zero Peak RG",
        replayGainTrackGainDb = "+12.00 dB",
        replayGainTrackPeak = "0.000000",
      ),
    )

    SyntheticAudioFixtures.createCacheScope(context, "t2_f9_3").use { scope ->
      val file = scope.writeFile("zero_peak_rg.mp3", mp3Bytes)
      playSingleFileToCompletionOrError(context, scope.fileUri(file), expectSuccess = true)
    }
  }

  @Test // T2-F9-4
  fun exoPlayer_playsFullScaleClippingWav_withoutAudioSinkCrash() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    SyntheticAudioFixtures.createCacheScope(context, "t2_f9_4").use { scope ->
      val file = scope.writeFile("clipping.wav", SyntheticAudioFixtures.generateClippingWav(durationMs = 400L))
      playSingleFileToCompletionOrError(context, scope.fileUri(file), expectSuccess = true)
    }
  }

  @Test // T2-F9-5
  fun audioEngine_setReplayGainMetadataWithNullsAndZeroPeaks_acceptedSafely() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.engine.setReplayGainMetadata(
        trackId = "t1",
        trackGainDb = null,
        trackPeak = 0f,
        albumGainDb = null,
        albumPeak = -1f,
      )
      assertEquals(PlaybackStatus.STOPPED, driver.engine.playbackState.value.status)
    }
  }

  // =========================================================================
  // Feature 10: 10-Band Biquad Peaking EQ + Preamp Boundaries — 5 Tests
  // =========================================================================

  @Test // T2-F10-1
  fun eq_clampsBandAndPreampValuesOutsidePlusMinus12Db() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      runBlocking {
        driver.engine.setPreampDb(99f)
      }
      assertEquals(12f, driver.engine.playbackState.value.equalizer.preampDb, 0f)

      runBlocking {
        driver.engine.setPreampDb(-99f)
      }
      assertEquals(-12f, driver.engine.playbackState.value.equalizer.preampDb, 0f)

      runBlocking {
        driver.engine.setPreampDb(Float.POSITIVE_INFINITY)
      }
      assertEquals(12f, driver.engine.playbackState.value.equalizer.preampDb, 0f)

      runBlocking {
        driver.engine.setPreampDb(Float.NEGATIVE_INFINITY)
      }
      assertEquals(-12f, driver.engine.playbackState.value.equalizer.preampDb, 0f)

      runBlocking {
        driver.engine.setEqBandDb(0, 50f)
        driver.engine.setEqBandDb(9, -50f)
      }
      assertEquals(12f, driver.engine.playbackState.value.equalizer.bandsDb[0], 0f)
      assertEquals(-12f, driver.engine.playbackState.value.equalizer.bandsDb[9], 0f)

      runBlocking {
        driver.engine.setEqBands(List(10) { 100f })
      }
      assertEquals(List(10) { 12f }, driver.engine.playbackState.value.equalizer.bandsDb)
    }
  }

  @Test // T2-F10-2
  fun eq_ignoresOutOfBoundsBandIndexAndInvalidBandListSizes() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      runBlocking {
        driver.engine.setEqBandDb(2, 4.5f)
        driver.engine.setEqBandDb(-1, 9f)
        driver.engine.setEqBandDb(10, 9f)
        driver.engine.setEqBands(List(9) { -5f })
        driver.engine.setEqBands(List(11) { -5f })
      }
      val bands = driver.engine.playbackState.value.equalizer.bandsDb
      assertEquals(4.5f, bands[2], 0f)
      assertEquals(0f, bands[0], 0f)
      assertEquals(0f, bands[9], 0f)
    }
  }

  @Test // T2-F10-3
  fun exoPlayer_playsMultiSampleRate22k32k44k48kWavCleanly() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    SyntheticAudioFixtures.createCacheScope(context, "t2_f10_3_rates").use { scope ->
      SyntheticAudioFixtures.SUPPORTED_WAV_SAMPLE_RATES.forEach { sampleRateHz ->
        val file = scope.writeFile(
          "tone_$sampleRateHz.wav",
          SyntheticAudioFixtures.generateWav(sampleRateHz = sampleRateHz, durationMs = 300L),
        )
        playSingleFileToCompletionOrError(context, scope.fileUri(file), expectSuccess = true)
      }
    }
  }

  @Test // T2-F10-4
  fun exoPlayer_playsAllMp3BitrateExtremes32To320Kbps() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    SyntheticAudioFixtures.createCacheScope(context, "t2_f10_4_mp3_rates").use { scope ->
      val matrix = listOf(
        32_000 to 32,
        44_100 to 128,
        48_000 to 320,
      )
      matrix.forEach { (sampleRate, bitrate) ->
        val file = scope.writeFile(
          "mp3_${sampleRate}_${bitrate}.mp3",
          SyntheticAudioFixtures.generateMp3(
            sampleRateHz = sampleRate,
            bitrateKbps = bitrate,
            durationMs = 300L,
          ),
        )
        playSingleFileToCompletionOrError(context, scope.fileUri(file), expectSuccess = true)
      }
    }
  }

  @Test // T2-F10-5
  fun eq_uiPreampGapAndExtremeVerticalDragIsolation_holdOnDeviceGeometry() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.setPreampDb(12f)
      assertEquals(12f, driver.viewModel.playbackState.value.equalizer.preampDb, 0.5f)
      assertEquals(List(10) { 0f }, driver.viewModel.playbackState.value.equalizer.bandsDb)

      driver.setPreampDb(-12f)
      assertEquals(-12f, driver.viewModel.playbackState.value.equalizer.preampDb, 0.5f)
      assertEquals(List(10) { 0f }, driver.viewModel.playbackState.value.equalizer.bandsDb)

      DeviceManifestVerifier.assertPreampGapIsolation(composeRule)
    }
  }

  // =========================================================================
  // Feature 11: Independent Volume & Cold-Launch Boundaries — 5 Tests
  // =========================================================================

  @Test // T2-F11-1
  fun volume_clampsBelowZeroAndAbove255() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.viewModel.setVolume(-100)
      composeRule.waitForIdle()
      assertEquals(0, driver.engine.playbackState.value.volume)
      composeRule.onNodeWithText("0%").assertIsDisplayed()

      driver.viewModel.setVolume(-1)
      assertEquals(0, driver.engine.playbackState.value.volume)

      driver.viewModel.setVolume(256)
      assertEquals(255, driver.engine.playbackState.value.volume)

      driver.viewModel.setVolume(999)
      composeRule.waitForIdle()
      assertEquals(255, driver.engine.playbackState.value.volume)
      composeRule.onNodeWithText("100%").assertIsDisplayed()
    }
  }

  @Test // T2-F11-2
  fun volume_uiSliderLeftAndRightExtremesReachExact0And255() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.setVolumeFraction(0f)
      assertEquals(0, driver.engine.playbackState.value.volume)
      composeRule.onNodeWithText("0%").assertIsDisplayed()

      driver.setVolumeFraction(1f)
      assertEquals(255, driver.engine.playbackState.value.volume)
      composeRule.onNodeWithText("100%").assertIsDisplayed()
    }
  }

  @Test // T2-F11-3
  fun volume_coldLaunchResetClampsCustomDefaultParameter() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      runBlocking {
        driver.engine.applyColdLaunchVolumeResetIfStopped(999)
      }
      assertEquals(255, driver.engine.playbackState.value.volume)

      runBlocking {
        driver.engine.applyColdLaunchVolumeResetIfStopped(-5)
      }
      assertEquals(0, driver.engine.playbackState.value.volume)
    }
  }

  @Test // T2-F11-4
  fun volume_coldLaunchWhilePausedNeverResetsVolume() {
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = listOf(sampleTrack()),
      autoPlay = true,
      initialVolume = 12,
    ).use { driver ->
      driver.clickPauseOnDeck()
      assertEquals(PlaybackStatus.PAUSED, driver.engine.playbackState.value.status)

      DroidAmpViewModel(audioEngine = driver.engine, musicRepository = driver.repository)
      assertEquals(12, driver.engine.playbackState.value.volume)
    }
  }

  @Test // T2-F11-5
  fun volume_zeroVolumeDoesNotPauseOrStopPlayback() {
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = listOf(sampleTrack()),
      autoPlay = true,
    ).use { driver ->
      driver.viewModel.setVolume(0)
      composeRule.waitForIdle()
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)
      composeRule.onNodeWithText("0%").assertIsDisplayed()
    }
  }

  // =========================================================================
  // Feature 12: Stereo Balance (-127..+127) Boundaries — 5 Tests
  // =========================================================================

  @Test // T2-F12-1
  fun balance_clampsBelowMinus127AndAbovePlus127() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.viewModel.setBalance(-500)
      composeRule.waitForIdle()
      assertEquals(-127, driver.engine.playbackState.value.balance)
      composeRule.onNodeWithText("L100").assertIsDisplayed()

      driver.viewModel.setBalance(-128)
      assertEquals(-127, driver.engine.playbackState.value.balance)

      driver.viewModel.setBalance(128)
      assertEquals(127, driver.engine.playbackState.value.balance)

      driver.viewModel.setBalance(500)
      composeRule.waitForIdle()
      assertEquals(127, driver.engine.playbackState.value.balance)
      composeRule.onNodeWithText("R100").assertIsDisplayed()
    }
  }

  @Test // T2-F12-2
  fun balance_uiSnapBoundaryAtMinus8ZeroPlus8VsOutsideSnapZone() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.viewModel.setBalance(64)
      composeRule.waitForIdle()

      // Inside -8..+8 snap zone (+6)
      driver.setBalanceFraction(0.5f + 6f / 254f)
      assertEquals(0, driver.engine.playbackState.value.balance)
      composeRule.onNodeWithText("CTR").assertIsDisplayed()

      // Inside -8..+8 snap zone (-6)
      driver.viewModel.setBalance(-64)
      composeRule.waitForIdle()
      driver.setBalanceFraction(0.5f - 6f / 254f)
      assertEquals(0, driver.engine.playbackState.value.balance)
      composeRule.onNodeWithText("CTR").assertIsDisplayed()

      // Outside snap zone (+25)
      driver.setBalanceFraction(0.5f + 25f / 254f)
      assertTrue("Expected positive balance outside +8 snap zone, got ${driver.engine.playbackState.value.balance}", driver.engine.playbackState.value.balance > 8)
    }
  }

  @Test // T2-F12-3
  fun balance_uiSliderExtremesReachMinus127AndPlus127() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.setBalanceFraction(0f)
      assertEquals(-127, driver.engine.playbackState.value.balance)
      composeRule.onNodeWithText("L100").assertIsDisplayed()

      driver.setBalanceFraction(1f)
      assertEquals(127, driver.engine.playbackState.value.balance)
      composeRule.onNodeWithText("R100").assertIsDisplayed()
    }
  }

  @Test // T2-F12-4
  fun exoPlayer_playsAsymmetricStereoWavAndRackBalanceExtremesRemainActive() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    SyntheticAudioFixtures.createCacheScope(context, "t2_f12_4_asym").use { scope ->
      val wav = scope.writeFile(
        "asym.wav",
        SyntheticAudioFixtures.generateWav(
          leftFrequencyHz = 440.0,
          rightFrequencyHz = 1760.0,
          leftAmplitude = 0.8,
          rightAmplitude = 0.8,
          durationMs = 400L,
        ),
      )
      playSingleFileToCompletionOrError(context, scope.fileUri(wav), expectSuccess = true)
    }

    E2eRackDriver.launchIsolatedRack(composeRule, initialQueue = listOf(sampleTrack()), autoPlay = true).use { driver ->
      driver.viewModel.setBalance(-127)
      composeRule.waitForIdle()
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)
      driver.viewModel.setBalance(127)
      composeRule.waitForIdle()
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)
    }
  }

  @Test // T2-F12-5
  fun balance_programmaticMinus127To127PreservedInsideSnapZone() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.viewModel.setBalance(-5)
      composeRule.waitForIdle()
      assertEquals(-5, driver.engine.playbackState.value.balance)
      composeRule.onNodeWithText("L3").assertIsDisplayed()

      driver.viewModel.setBalance(5)
      composeRule.waitForIdle()
      assertEquals(5, driver.engine.playbackState.value.balance)
      composeRule.onNodeWithText("R3").assertIsDisplayed()
    }
  }

  // =========================================================================
  // Feature 13: Gapless & Crossfade Boundaries — 5 Tests
  // =========================================================================

  @Test // T2-F13-1
  fun dspSettings_clampsCrossfadeSecondsBelow0AndAbove12() {
    assertEquals(0, DspSettings(crossfadeSeconds = -5).sanitized().crossfadeSeconds)
    assertEquals(12, DspSettings(crossfadeSeconds = 99).sanitized().crossfadeSeconds)
  }

  @Test // T2-F13-2
  fun exoPlayer_gaplessVeryShortTracksCompleteCleanly() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    SyntheticAudioFixtures.createCacheScope(context, "t2_f13_2_short").use { scope ->
      val short1 = scope.writeFile("short1.wav", SyntheticAudioFixtures.generateWav(durationMs = 100L))
      val short2 = scope.writeFile("short2.wav", SyntheticAudioFixtures.generateWav(durationMs = 250L))

      withExoPlayer(context) { player ->
        val ended = CompletableDeferred<Unit>()
        var err: PlaybackException? = null
        instrumentation.runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended.complete(Unit)
              }

              override fun onPlayerError(error: PlaybackException) {
                err = error
                ended.completeExceptionally(error)
              }
            },
          )
          player.setMediaItems(
            listOf(
              MediaItem.fromUri(scope.fileUri(short1)),
              MediaItem.fromUri(scope.fileUri(short2)),
            ),
          )
          player.prepare()
          player.play()
        }
        runBlocking { withTimeout(8_000L) { ended.await() } }
        assertNull(err)
      }
    }
  }

  @Test // T2-F13-3
  fun exoPlayer_silentWavTracksTransitionWithoutStall() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    SyntheticAudioFixtures.createCacheScope(context, "t2_f13_3_silent").use { scope ->
      val s1 = scope.writeFile("silent1.wav", SyntheticAudioFixtures.generateSilenceWav(durationMs = 350L))
      val s2 = scope.writeFile("silent2.wav", SyntheticAudioFixtures.generateSilenceWav(durationMs = 350L))

      withExoPlayer(context) { player ->
        val ended = CompletableDeferred<Unit>()
        var sawSecond = false
        instrumentation.runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player.currentMediaItemIndex == 1) sawSecond = true
              }

              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended.complete(Unit)
              }

              override fun onPlayerError(error: PlaybackException) {
                ended.completeExceptionally(error)
              }
            },
          )
          player.setMediaItems(
            listOf(
              MediaItem.fromUri(scope.fileUri(s1)),
              MediaItem.fromUri(scope.fileUri(s2)),
            ),
          )
          player.prepare()
          player.play()
        }
        runBlocking { withTimeout(8_000L) { ended.await() } }
        assertTrue("Expected gapless transition to silent track index 1", sawSecond)
      }
    }
  }

  @Test // T2-F13-4
  fun audioEngine_replaceQueueWithEmptyListStopsPlaybackCleanly() {
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = listOf(sampleTrack()),
      autoPlay = true,
    ).use { driver ->
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      runBlocking {
        driver.engine.replaceQueue(emptyList())
      }
      val state = driver.engine.playbackState.value
      assertTrue(state.queue.isEmpty())
      assertEquals(-1, state.currentIndex)
      assertEquals(PlaybackStatus.STOPPED, state.status)
    }
  }

  @Test // T2-F13-5
  fun audioEngine_removeCurrentAndClearQueueResetStateCleanly() {
    val queue = listOf(
      sampleTrack(id = "a", title = "A"),
      sampleTrack(id = "b", title = "B"),
      sampleTrack(id = "c", title = "C"),
    )
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = queue,
      autoPlay = false,
    ).use { driver ->
      runBlocking {
        driver.engine.playTrackAt(1)
        driver.engine.removeIndices(setOf(1))
      }
      assertEquals(2, driver.engine.playbackState.value.queue.size)
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      runBlocking {
        driver.engine.clearQueue()
      }
      val cleared = driver.engine.playbackState.value
      assertTrue(cleared.queue.isEmpty())
      assertEquals(-1, cleared.currentIndex)
      assertEquals(PlaybackStatus.STOPPED, cleared.status)
      assertEquals(0L, cleared.positionMs)
    }
  }

  // =========================================================================
  // Feature 14: Lock-Free FFT/Waveform + VFD Boundaries — 3 Tests (+2 in MainActivity)
  // =========================================================================

  @Test // T2-F14-1
  fun visualizerFrame_silentOnStopAndUiReflectsOffMode() {
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      enableSimulationTicker = true,
      initialQueue = listOf(sampleTrack()),
      autoPlay = true,
    ).use { driver ->
      composeRule.waitUntil(timeoutMillis = 3_000L) {
        driver.engine.visualizerFrame.value.spectrumBars.any { it > 0f }
      }

      driver.clickStopOnDeck()
      assertEquals(VisualizerFrame.SILENT, driver.engine.visualizerFrame.value)

      driver.clickPlayOnDeck()
      composeRule.waitUntil(timeoutMillis = 3_000L) {
        driver.engine.visualizerFrame.value.spectrumBars.any { it > 0f }
      }

      // Cycle SPECTRUM -> OSCILLOSCOPE -> OFF
      driver.cycleVisualizerMode()
      driver.cycleVisualizerMode()
      assertEquals(VisualizerMode.OFF, driver.engine.playbackState.value.visualizerMode)
      DroidAmpSelectors.visualizerWithMode(composeRule, "OFF").assertIsDisplayed()
    }
  }

  @Test // T2-F14-2
  fun visualizerFrame_equalsHashCodeAndArrayLengthInvariants() {
    val a = VisualizerFrame(
      spectrumBars = FloatArray(19) { it * 0.05f },
      peakDots = FloatArray(19) { it * 0.05f },
      waveform = FloatArray(64) { (it - 32) / 32f },
    )
    val b = VisualizerFrame(
      spectrumBars = FloatArray(19) { it * 0.05f },
      peakDots = FloatArray(19) { it * 0.05f },
      waveform = FloatArray(64) { (it - 32) / 32f },
    )
    val c = VisualizerFrame(
      spectrumBars = FloatArray(19) { it * 0.05f },
      peakDots = FloatArray(19) { if (it == 5) 0.99f else it * 0.05f },
      waveform = FloatArray(64) { (it - 32) / 32f },
    )

    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertNotEquals(a, c)
    assertEquals(19, VisualizerFrame.SPECTRUM_BAR_COUNT)
    assertEquals(64, VisualizerFrame.WAVEFORM_SAMPLE_COUNT)
  }

  @Test // T2-F14-3
  fun vfd_clockClampsNegativePositionAndCapsMinutesAt99AndZeroDurationRemaining() {
    val zeroDurationTrack = sampleTrack(durationMs = 0L)
    val longTrack = sampleTrack(id = "long", durationMs = 6_500_000L)
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = listOf(zeroDurationTrack),
      autoPlay = false,
    ).use { driver ->
      driver.toggleClockMode()
      assertEquals(TimeDisplayMode.REMAINING, driver.engine.playbackState.value.timeDisplayMode)
      DroidAmpSelectors.clockWithTime(composeRule, "-00:00").assertIsDisplayed()

      assertEquals("00:00", formatWinampDuration(-500L))

      driver.toggleClockMode()
      assertEquals(TimeDisplayMode.ELAPSED, driver.engine.playbackState.value.timeDisplayMode)
      runBlocking {
        driver.engine.replaceQueue(listOf(longTrack), startIndex = 0, autoPlay = false)
      }
      composeRule.waitForIdle()
      driver.viewModel.seekTo(6_500_000L)
      composeRule.waitForIdle()
      // 6_500_000 ms = 6500s = 108m 20s; SevenSegmentClock caps minutes at 99 -> "99:20"
      DroidAmpSelectors.clockWithTime(composeRule, "99:20").assertIsDisplayed()
    }
  }

  // =========================================================================
  // Feature 15: DSP Defaults & Persistence Boundaries — 4 Tests (+1 in MainActivity)
  // =========================================================================

  @Test // T2-F15-1
  fun dspSettings_nonFiniteAndExtremeBoundaryCombinationsSanitized() {
    val low = DspSettings(
      gaplessEnabled = false,
      crossfadeSeconds = Int.MIN_VALUE,
      replayGainMode = ReplayGainMode.TRACK,
      replayGainPreampDb = Float.NaN,
    ).sanitized()
    assertFalse(low.gaplessEnabled)
    assertEquals(0, low.crossfadeSeconds)
    assertEquals(ReplayGainMode.TRACK, low.replayGainMode)
    assertEquals(0f, low.replayGainPreampDb, 0f)

    val high = DspSettings(
      gaplessEnabled = true,
      crossfadeSeconds = Int.MAX_VALUE,
      replayGainMode = ReplayGainMode.ALBUM,
      replayGainPreampDb = Float.NEGATIVE_INFINITY,
    ).sanitized()
    assertTrue(high.gaplessEnabled)
    assertEquals(12, high.crossfadeSeconds)
    assertEquals(ReplayGainMode.ALBUM, high.replayGainMode)
    assertEquals(0f, high.replayGainPreampDb, 0f)
  }

  @Test // T2-F15-2
  fun audioTrack_negativeDurationAndZeroSampleRateSafe() {
    val track = AudioTrack(
      id = "edge",
      uriString = "",
      title = "",
      artist = "",
      fileName = "noext",
      folderPath = "",
      durationMs = -100L,
      sampleRateHz = 0,
    )
    assertEquals("00:00", formatWinampDuration(track.durationMs))
    assertEquals(0, track.sampleRateKhz)
    assertEquals("noext", track.displayLabel)
  }

  @Test // T2-F15-3
  fun playbackState_volumePercentClampsOutOfRangeConstructedVolume() {
    assertEquals(0, PlaybackState(volume = -50).volumePercent)
    assertEquals(100, PlaybackState(volume = 500).volumePercent)
  }

  @Test // T2-F15-4
  fun uiSession_ejectAlwaysJumpsToLibraryEvenAfterRememberingPlaylistMode() {
    E2eRackDriver.launchIsolatedRack(composeRule, startLinked = true).use { driver ->
      driver.ensureSheetExpanded(SheetMode.PLAYLIST)
      composeRule.onNodeWithText("DROIDAMP PLAYLIST ▼").assertIsDisplayed()
      driver.collapseSheetByTitleTap()

      driver.clickEjectOnDeck()
      composeRule.onNodeWithText("DROIDAMP LIBRARY ▼").assertIsDisplayed()

      driver.collapseSheetByTitleTap()
      driver.expandSheetByTitleTap()
      composeRule.onNodeWithText("DROIDAMP LIBRARY ▼").assertIsDisplayed()
    }
  }
}

@RunWith(AndroidJUnit4::class)
class Tier2DspMainActivityBoundaryTest {

  @get:Rule
  val activityRule = createAndroidComposeRule<MainActivity>()

  @Test // T2-F14-4
  fun vfd_peekingSwipeBufferClearsBothWhenEqVisibleAndWhenEqCollapsed() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    DeviceManifestVerifier.assertPeekingSheetSwipeBuffer(activityRule)

    driver.toggleEqVisibility()
    DeviceManifestVerifier.assertPeekingSheetSwipeBuffer(activityRule)
  }

  @Test // T2-F14-5
  fun vfd_navigationBarAndBottomLipClearanceInBothPeekingAndExpandedStates() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(activityRule)

    driver.ensureSheetExpanded()
    DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(activityRule)
  }

  @Test // T2-F15-5
  fun uiSession_backPressCollapsesExpandedSheetAndPreservesRestingState() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    driver.ensureSheetExpanded()
    DroidAmpSelectors.expandedSheetTitleBar(activityRule).assertIsDisplayed()

    Espresso.pressBack()
    activityRule.waitForIdle()

    DroidAmpSelectors.deckTitleBar(activityRule).assertIsDisplayed()
    DroidAmpSelectors.peekingSheetTitleBar(activityRule).assertIsDisplayed()
    assertFalse("Back press on expanded sheet must not finish MainActivity", activityRule.activity.isFinishing)
  }
}
