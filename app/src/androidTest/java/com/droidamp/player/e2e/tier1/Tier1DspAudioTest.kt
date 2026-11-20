package com.droidamp.player.e2e.tier1

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidamp.player.MainActivity
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.ChannelMode
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.domain.model.RepeatMode
import com.droidamp.player.domain.model.SheetMode
import com.droidamp.player.domain.model.TimeDisplayMode
import com.droidamp.player.domain.model.VisualizerFrame
import com.droidamp.player.domain.model.VisualizerMode
import com.droidamp.player.domain.model.WinampEqSpec
import com.droidamp.player.domain.model.formatWinampDuration
import com.droidamp.player.e2e.fixtures.SyntheticAudioFixtures
import com.droidamp.player.e2e.harness.DeviceManifestVerifier
import com.droidamp.player.e2e.harness.DroidAmpSelectors
import com.droidamp.player.e2e.harness.E2eRackDriver
import com.droidamp.player.ui.rack.DroidAmpViewModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
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
class Tier1DspAudioTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun sampleTracks(count: Int = 3): List<AudioTrack> =
    (1..count).map { idx ->
      AudioTrack(
        id = "track_$idx",
        uriString = "file:///Music/track_$idx.mp3",
        title = "Track $idx",
        artist = "Artist $idx",
        album = "Album $idx",
        fileName = "0${idx}_track.mp3",
        folderPath = "/Music",
        durationMs = idx * 60_000L,
        bitrateKbps = 192,
        sampleRateHz = 44_100,
        channelMode = ChannelMode.STEREO,
      )
    }

  private fun resolveMediaSessionServiceComponent(context: Context): ComponentName {
    val pm = context.packageManager
    val intent = Intent("androidx.media3.session.MediaSessionService").setPackage(context.packageName)
    val services =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
      } else {
        @Suppress("DEPRECATION")
        pm.queryIntentServices(intent, 0)
      }
    val info = services.firstOrNull()?.serviceInfo
      ?: error("No MediaSessionService resolved for ${context.packageName}")
    return ComponentName(info.packageName, info.name)
  }

  private inline fun <T> withMediaController(
    context: Context,
    block: (MediaController) -> T,
  ): T {
    val component = resolveMediaSessionServiceComponent(context)
    val token = SessionToken(context, component)
    val future = MediaController.Builder(context, token).buildAsync()
    val controller = future.get(5, TimeUnit.SECONDS)
    try {
      return block(controller)
    } finally {
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        MediaController.releaseFuture(future)
      }
    }
  }

  /**
   * Builds a standalone Media3 [ExoPlayer] on the Main thread to verify container/ID3/PCM
   * decoding and Media3 gapless/repeat transitions pre-`M-integration`.
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

  // =========================================================================
  // Feature 7: Media3 MediaSessionService & ExoPlayer Core — 4 Tests (+1 in MainActivity)
  // =========================================================================

  @Test // T1-F7-1
  fun service_declaredAsMediaSessionServiceInManifestAndConnectableViaMediaController() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assertTrue(
      "AndroidManifest must declare a MediaSessionService",
      DeviceManifestVerifier.hasDeclaredMediaSessionService(context),
    )

    withMediaController(context) { controller ->
      var connected = false
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        connected = controller.isConnected
      }
      assertTrue("MediaController must connect to DroidAmpPlaybackService", connected)
    }
  }

  @Test // T1-F7-2
  fun exoPlayer_playsSyntheticWavAndMp3ViaMedia3Decoder_reachesPlayingState() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    SyntheticAudioFixtures.createCacheScope(context, "t1_f7_2").use { scope ->
      val wavFile = scope.writeFile("tone.wav", SyntheticAudioFixtures.generateWav(durationMs = 1_200L))
      val mp3File = scope.writeFile(
        "tone.mp3",
        SyntheticAudioFixtures.generateMp3(
          durationMs = 1_200L,
          metadata = SyntheticAudioFixtures.Id3MetadataSpec(title = "Playable MP3", artist = "Droid"),
        ),
      )

      withExoPlayer(context) { player ->
        val playingDeferred = CompletableDeferred<Unit>()
        val errorDeferred = CompletableDeferred<PlaybackException>()
        var observedDuration = 0L

        instrumentation.runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && player.playbackState == Player.STATE_READY) {
                  observedDuration = player.duration
                  playingDeferred.complete(Unit)
                }
              }

              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && player.isPlaying) {
                  observedDuration = player.duration
                  playingDeferred.complete(Unit)
                }
              }

              override fun onPlayerError(error: PlaybackException) {
                errorDeferred.complete(error)
                playingDeferred.completeExceptionally(error)
              }
            },
          )
          player.setMediaItems(
            listOf(
              MediaItem.fromUri(scope.fileUri(wavFile)),
              MediaItem.fromUri(scope.fileUri(mp3File)),
            ),
          )
          player.prepare()
          player.play()
        }

        runBlocking {
          withTimeout(8_000L) {
            playingDeferred.await()
          }
        }
        assertFalse("Unexpected playback error", errorDeferred.isCompleted)
        assertTrue("Expected positive duration for synthetic WAV, got $observedDuration", observedDuration > 0L)

        instrumentation.runOnMainSync {
          player.stop()
        }
      }
    }
  }

  @Test // T1-F7-3
  fun deckTransport_playPauseStopNextPrevAndSeek_updatePlaybackStateAndUi() {
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = sampleTracks(3),
      autoPlay = false,
    ).use { driver ->
      driver.clickPlayOnDeck()
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      driver.clickPauseOnDeck()
      assertEquals(PlaybackStatus.PAUSED, driver.engine.playbackState.value.status)

      driver.seekToFraction(0.5f)
      assertTrue("Seek must advance positionMs > 0", driver.engine.playbackState.value.positionMs > 0L)

      driver.clickStopOnDeck()
      assertEquals(PlaybackStatus.STOPPED, driver.engine.playbackState.value.status)
      assertEquals(0L, driver.engine.playbackState.value.positionMs)

      driver.clickNextOnDeck()
      assertEquals(1, driver.engine.playbackState.value.currentIndex)
    }
  }

  @Test // T1-F7-4
  fun deckTransport_previousThreshold3Seconds_restartsOrGoesToPreviousTrack() {
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = sampleTracks(3),
      autoPlay = false,
    ).use { driver ->
      runBlocking {
        driver.engine.playTrackAt(1)
        driver.engine.seekTo(4_500L)
      }
      composeRule.waitForIdle()
      assertEquals(1, driver.engine.playbackState.value.currentIndex)

      driver.clickPrevOnDeck()
      assertEquals("Prev > 3s must restart current track", 1, driver.engine.playbackState.value.currentIndex)
      assertEquals(0L, driver.engine.playbackState.value.positionMs)

      runBlocking {
        driver.engine.seekTo(1_000L)
      }
      composeRule.waitForIdle()
      driver.clickPrevOnDeck()
      assertEquals("Prev <= 3s must step to previous track", 0, driver.engine.playbackState.value.currentIndex)
    }
  }

  @Test // T1-F7-5
  fun deckTransport_shuffleAndRepeatCycle_offAllOneOff() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      assertFalse(driver.engine.playbackState.value.shuffle)
      driver.toggleShuffle()
      assertTrue(driver.engine.playbackState.value.shuffle)
      driver.toggleShuffle()
      assertFalse(driver.engine.playbackState.value.shuffle)

      assertEquals(RepeatMode.OFF, driver.engine.playbackState.value.repeatMode)
      composeRule.onNodeWithText("REPEAT").assertIsDisplayed()

      driver.cycleRepeatMode()
      assertEquals(RepeatMode.ALL, driver.engine.playbackState.value.repeatMode)
      composeRule.onNodeWithText("REP:ALL").assertIsDisplayed()

      driver.cycleRepeatMode()
      assertEquals(RepeatMode.ONE, driver.engine.playbackState.value.repeatMode)
      composeRule.onNodeWithText("REP:1").assertIsDisplayed()

      driver.cycleRepeatMode()
      assertEquals(RepeatMode.OFF, driver.engine.playbackState.value.repeatMode)
      composeRule.onNodeWithText("REPEAT").assertIsDisplayed()
    }
  }

  // =========================================================================
  // Feature 8: Lockscreen/Notification Artwork & BT Metadata — 4 Tests (+1 in MainActivity)
  // =========================================================================

  @Test // T1-F8-2
  fun domainModel_audioTrackHasZeroArtworkFieldsAndFormatsDisplayLabel() {
    val forbiddenTokens = listOf("artwork", "albumart", "cover", "bitmap", "image", "thumb")
    val declaredFields = AudioTrack::class.java.declaredFields
    declaredFields.forEach { field ->
      val nameLower = field.name.lowercase()
      val isForbiddenArtName =
        forbiddenTokens.any { token -> nameLower.contains(token) } ||
          nameLower == "art" ||
          (nameLower.endsWith("art") && nameLower != "artist") ||
          (nameLower.startsWith("art") && !nameLower.startsWith("artist"))
      assertFalse(
        "AudioTrack must not contain artwork field '${field.name}'",
        isForbiddenArtName,
      )
    }
    assertTrue(
      "AudioTrack must not contain any Bitmap artwork field",
      declaredFields.none { it.type == android.graphics.Bitmap::class.java },
    )
    assertTrue(
      "AudioTrack must retain 'artist' metadata field",
      declaredFields.any { it.name == "artist" && it.type == String::class.java },
    )

    val full = AudioTrack("1", "", "Neon Track", "DroidSynth", "Vol 1", "01.mp3", "/m", 65_000L, sampleRateHz = 48_000)
    val titleOnly = AudioTrack("2", "", "Solo Title", "", "", "02.mp3", "/m", 65_000L)
    val fileOnly = AudioTrack("3", "", "", "", "", "03_instrumental.flac", "/m", 65_000L)

    assertEquals("Neon Track", full.title)
    assertEquals("DroidSynth", full.artist)
    assertEquals("Vol 1", full.album)
    assertEquals("01.mp3", full.fileName)
    assertEquals("/m", full.folderPath)
    assertEquals("DroidSynth - Neon Track", full.displayLabel)
    assertEquals(48, full.sampleRateKhz)
    assertEquals("Solo Title", titleOnly.displayLabel)
    assertEquals("03_instrumental", fileOnly.displayLabel)
  }

  @Test // T1-F8-3
  fun media3Decoder_exposesId3TitleArtistAlbumAndApicArtworkOnMediaMetadata() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val pngBytes = SyntheticAudioFixtures.generateMinimal1x1Png(red = 0x10, green = 0xE0, blue = 0x30)
    val mp3Bytes = SyntheticAudioFixtures.generateMp3(
      durationMs = 1_000L,
      metadata = SyntheticAudioFixtures.Id3MetadataSpec(
        title = "Neon Track",
        artist = "DroidSynth",
        album = "Rack Vol 1",
        artworkPngBytes = pngBytes,
      ),
    )

    SyntheticAudioFixtures.createCacheScope(context, "t1_f8_3").use { scope ->
      val mp3File = scope.writeFile("meta_art.mp3", mp3Bytes)

      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(mp3File.absolutePath)
        assertEquals("Neon Track", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
        assertEquals("DroidSynth", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
        assertEquals("Rack Vol 1", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
        val embeddedPic = retriever.embeddedPicture
        assertNotNull("Embedded APIC artwork bytes must be extractable for system media surfaces", embeddedPic)
        assertTrue(embeddedPic!!.isNotEmpty())
      } finally {
        retriever.release()
      }

      withExoPlayer(context) { player ->
        val metaDeferred = CompletableDeferred<MediaMetadata>()
        val readyDeferred = CompletableDeferred<Unit>()
        instrumentation.runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                if (mediaMetadata.title != null && mediaMetadata.artworkData != null) {
                  metaDeferred.complete(mediaMetadata)
                }
              }

              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                  readyDeferred.complete(Unit)
                  val m = player.mediaMetadata
                  if (m.title != null && m.artworkData != null) {
                    metaDeferred.complete(m)
                  }
                }
              }

              override fun onPlayerError(error: PlaybackException) {
                readyDeferred.completeExceptionally(error)
                metaDeferred.completeExceptionally(error)
              }
            },
          )
          player.setMediaItem(MediaItem.fromUri(scope.fileUri(mp3File)))
          player.prepare()
          player.play()
        }

        val metadata = runBlocking {
          withTimeout(8_000L) {
            readyDeferred.await()
            metaDeferred.await()
          }
        }
        assertEquals("Neon Track", metadata.title?.toString())
        assertEquals("DroidSynth", metadata.artist?.toString())
        assertEquals("Rack Vol 1", metadata.albumTitle?.toString())
        assertNotNull(metadata.artworkData)
        assertTrue(metadata.artworkData!!.isNotEmpty())
      }
    }
  }

  @Test // T1-F8-4
  fun service_activePlatformMediaSessionMatchesAppPackageAndZeroInAppArt() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    withMediaController(context) { controller ->
      var connected = false
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        connected = controller.isConnected
      }
      assertTrue(connected)

      val sessions = DeviceManifestVerifier.queryAppMediaSessions(context)
      if (sessions.isNotEmpty()) {
        assertTrue(
          "Active platform MediaSession must belong to ${context.packageName}",
          sessions.any { it.packageName == context.packageName },
        )
      }
    }

    E2eRackDriver.launchIsolatedRack(composeRule, initialQueue = sampleTracks(1), autoPlay = true).use {
      DeviceManifestVerifier.assertZeroInAppAlbumArt(composeRule)
    }
  }

  @Test // T1-F8-5
  fun inAppUi_remainsArtFreeWhenPlayingTrackWithMetadataAndArtwork() {
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      startLinked = true,
      initialQueue = sampleTracks(2),
      autoPlay = true,
    ).use { driver ->
      DeviceManifestVerifier.assertZeroInAppAlbumArt(composeRule)
      driver.cycleVisualizerMode()
      DeviceManifestVerifier.assertZeroInAppAlbumArt(composeRule)
      driver.ensureSheetExpanded(SheetMode.PLAYLIST)
      DeviceManifestVerifier.assertZeroInAppAlbumArt(composeRule)
      driver.ensureSheetExpanded(SheetMode.LIBRARY)
      DeviceManifestVerifier.assertZeroInAppAlbumArt(composeRule)
      driver.ensureSheetCollapsed()
      DeviceManifestVerifier.assertZeroInAppAlbumArt(composeRule)
    }
  }

  // =========================================================================
  // Feature 9: ReplayGain PCM AudioProcessor — 5 Tests
  // =========================================================================

  @Test // T1-F9-1
  fun dspSettings_defaultValuesAndReplayGainModes_conformToSpec() {
    val defaults = DspSettings()
    assertEquals(com.droidamp.player.domain.model.ReplayGainMode.OFF, defaults.replayGainMode)
    assertEquals(0f, defaults.replayGainPreampDb, 0f)
    assertEquals(
      listOf(
        com.droidamp.player.domain.model.ReplayGainMode.OFF,
        com.droidamp.player.domain.model.ReplayGainMode.TRACK,
        com.droidamp.player.domain.model.ReplayGainMode.ALBUM,
      ),
      com.droidamp.player.domain.model.ReplayGainMode.entries,
    )
  }

  @Test // T1-F9-2
  fun audioEngine_acceptsTrackAndAlbumReplayGainSettingsAndMetadataWithoutError() {
    val queue = sampleTracks(2)
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = queue,
      autoPlay = true,
    ).use { driver ->
      driver.engine.setDspSettings(
        DspSettings(
          replayGainMode = com.droidamp.player.domain.model.ReplayGainMode.TRACK,
          replayGainPreampDb = 3.5f,
        ),
      )
      driver.engine.setReplayGainMetadata(
        trackId = queue[0].id,
        trackGainDb = -6.5f,
        trackPeak = 0.98f,
        albumGainDb = -5.2f,
        albumPeak = 1.0f,
      )
      driver.engine.setDspSettings(
        DspSettings(replayGainMode = com.droidamp.player.domain.model.ReplayGainMode.ALBUM),
      )
      driver.engine.setDspSettings(
        DspSettings(replayGainMode = com.droidamp.player.domain.model.ReplayGainMode.OFF),
      )
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)
    }
  }

  @Test // T1-F9-3
  fun media3Decoder_decodesMp3WithId3ReplayGainTxxxTags_cleanlyWithoutError() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val mp3Bytes = SyntheticAudioFixtures.generateMp3(
      durationMs = 800L,
      metadata = SyntheticAudioFixtures.Id3MetadataSpec(
        title = "RG Track",
        replayGainTrackGainDb = "-6.50 dB",
        replayGainTrackPeak = "0.988123",
        replayGainAlbumGainDb = "-5.20 dB",
        replayGainAlbumPeak = "1.000000",
      ),
    )

    SyntheticAudioFixtures.createCacheScope(context, "t1_f9_3").use { scope ->
      val file = scope.writeFile("rg_tags.mp3", mp3Bytes)
      withExoPlayer(context) { player ->
        val playing = CompletableDeferred<Unit>()
        var error: PlaybackException? = null
        instrumentation.runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) playing.complete(Unit)
              }

              override fun onPlayerError(e: PlaybackException) {
                error = e
                playing.completeExceptionally(e)
              }
            },
          )
          player.setMediaItem(MediaItem.fromUri(scope.fileUri(file)))
          player.prepare()
          player.play()
        }
        runBlocking { withTimeout(8_000L) { playing.await() } }
        assertNull(error)
      }
    }
  }

  @Test // T1-F9-4
  fun media3Decoder_decodesMp3WithPositiveReplayGainTxxxTags_cleanly() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val mp3Bytes = SyntheticAudioFixtures.generateMp3(
      durationMs = 800L,
      metadata = SyntheticAudioFixtures.Id3MetadataSpec(
        title = "RG Positive",
        replayGainTrackGainDb = "+12.00 dB",
        replayGainTrackPeak = "0.500000",
      ),
    )

    SyntheticAudioFixtures.createCacheScope(context, "t1_f9_4").use { scope ->
      val file = scope.writeFile("rg_pos.mp3", mp3Bytes)
      withExoPlayer(context) { player ->
        val ended = CompletableDeferred<Unit>()
        var error: PlaybackException? = null
        instrumentation.runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended.complete(Unit)
              }

              override fun onPlayerError(e: PlaybackException) {
                error = e
                ended.completeExceptionally(e)
              }
            },
          )
          player.setMediaItem(MediaItem.fromUri(scope.fileUri(file)))
          player.prepare()
          player.play()
        }
        runBlocking { withTimeout(8_000L) { ended.await() } }
        assertNull(error)
      }
    }
  }

  @Test // T1-F9-5
  fun dspSettings_sanitizedPreservesValidReplayGainPreampWithin15Db() {
    val input = DspSettings(
      replayGainMode = com.droidamp.player.domain.model.ReplayGainMode.ALBUM,
      replayGainPreampDb = -7.5f,
    )
    val sanitized = input.sanitized()
    assertEquals(-7.5f, sanitized.replayGainPreampDb, 0f)
    assertEquals(com.droidamp.player.domain.model.ReplayGainMode.ALBUM, sanitized.replayGainMode)
  }

  // =========================================================================
  // Feature 10: 10-Band Biquad Peaking EQ + Preamp — 4 Tests (+1 in MainActivity)
  // =========================================================================

  @Test // T1-F10-2
  fun eq_singleBandAndPreampSliders_adjustIndependently() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.setPreampDb(4.0f)
      val afterPreamp = driver.viewModel.playbackState.value.equalizer
      assertEquals(4.0f, afterPreamp.preampDb, 0.5f)
      assertEquals(List(10) { 0f }, afterPreamp.bandsDb)

      driver.setEqBandDb(4, -6.0f)
      val afterBand = driver.viewModel.playbackState.value.equalizer
      assertEquals(-6.0f, afterBand.bandsDb[4], 0.5f)
      assertEquals(afterPreamp.preampDb, afterBand.preampDb, 0f)
      assertEquals("CUSTOM", afterBand.activePresetName)
    }
  }

  @Test // T1-F10-3
  fun eq_horizontalCurveDrawing_updatesMultipleBandsWithoutTouchingPreamp() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.viewModel.setPreampDb(2.0f)
      composeRule.waitForIdle()

      driver.ensureSheetCollapsed()
      DroidAmpSelectors.drawEqCurve(
        rule = composeRule,
        fromBand = 0,
        toBand = 9,
        startDb = 8f,
        endDb = -8f,
      )
      composeRule.waitForIdle()

      val eq = driver.viewModel.playbackState.value.equalizer
      assertEquals("Curve drawing across bands must not alter PREAMP", 2.0f, eq.preampDb, 0f)
      assertTrue("Low band 0 should be boosted (> 0 dB), got ${eq.bandsDb[0]}", eq.bandsDb[0] > 0f)
      assertTrue("High band 9 should be attenuated (< 0 dB), got ${eq.bandsDb[9]}", eq.bandsDb[9] < 0f)
    }
  }

  @Test // T1-F10-4
  fun eq_onBypassToggle_preservesBandValuesWhileTogglingEnabled() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.viewModel.applyEqPreset(ClassicWinampPresets.ROCK)
      composeRule.waitForIdle()

      assertTrue(driver.viewModel.playbackState.value.equalizer.enabled)
      driver.toggleEqEnabled()
      var eq = driver.viewModel.playbackState.value.equalizer
      assertFalse(eq.enabled)
      assertEquals(ClassicWinampPresets.ROCK.bandsDb, eq.bandsDb)

      driver.toggleEqEnabled()
      eq = driver.viewModel.playbackState.value.equalizer
      assertTrue(eq.enabled)
      assertEquals(ClassicWinampPresets.ROCK.bandsDb, eq.bandsDb)
    }
  }

  @Test // T1-F10-5
  fun eq_deckEqButton_collapsesAndRestoresEqualizerPanel() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      DroidAmpSelectors.eqTitleBar(composeRule).assertIsDisplayed()

      driver.toggleEqVisibility()
      assertFalse(driver.viewModel.uiState.value.isEqVisible)
      DroidAmpSelectors.eqTitleBar(composeRule).assertDoesNotExist()

      driver.toggleEqVisibility()
      assertTrue(driver.viewModel.uiState.value.isEqVisible)
      DroidAmpSelectors.eqTitleBar(composeRule).assertIsDisplayed()
    }
  }

  // =========================================================================
  // Feature 11: Independent Volume (0..255) & Cold-Launch-Only Reset — 4 Tests (+1 in MainActivity)
  // =========================================================================

  @Test // T1-F11-2
  fun volume_coldLaunchResetTo204AppliesWhenStopped() {
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialVolume = 40,
      autoPlay = false,
    ).use { driver ->
      // DroidAmpViewModel.init already ran applyColdLaunchVolumeResetIfStopped(204) on STOPPED engine
      assertEquals(204, driver.engine.playbackState.value.volume)

      // Also verify fresh DroidAmpViewModel creation resets STOPPED engine volume to 204
      driver.engine.setVolume(40)
      DroidAmpViewModel(audioEngine = driver.engine, musicRepository = driver.repository)
      assertEquals(204, driver.engine.playbackState.value.volume)
    }
  }

  @Test // T1-F11-3
  fun volume_coldLaunchPreservesVolumeWhenPlayingOrPaused() {
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = sampleTracks(1),
      autoPlay = true,
      initialVolume = 35,
    ).use { driver ->
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)
      assertEquals(35, driver.engine.playbackState.value.volume)

      DroidAmpViewModel(audioEngine = driver.engine, musicRepository = driver.repository)
      assertEquals("Cold-launch VM must preserve volume while PLAYING", 35, driver.engine.playbackState.value.volume)

      driver.clickPauseOnDeck()
      assertEquals(PlaybackStatus.PAUSED, driver.engine.playbackState.value.status)

      DroidAmpViewModel(audioEngine = driver.engine, musicRepository = driver.repository)
      assertEquals("Cold-launch VM must preserve volume while PAUSED", 35, driver.engine.playbackState.value.volume)
    }
  }

  @Test // T1-F11-4
  fun volume_sliderInteractionUpdatesInternalVolumeWithoutChangingSystemStreamVolume() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val beforeStream = DeviceManifestVerifier.queryAudioDeviceSnapshot(context).musicStreamVolume

    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.setVolumeFraction(0.25f)
      val state = driver.engine.playbackState.value
      assertTrue("Internal volume must drop below 100, got ${state.volume}", state.volume < 100)
      composeRule.onNodeWithText("${state.volumePercent}%").assertIsDisplayed()

      val afterStream = DeviceManifestVerifier.queryAudioDeviceSnapshot(context).musicStreamVolume
      assertEquals("Internal DSP volume must not modify AudioManager STREAM_MUSIC", beforeStream, afterStream)
    }
  }

  @Test // T1-F11-5
  fun volume_programmaticSetUpdatesVolumePercentLinearly() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.viewModel.setVolume(0)
      composeRule.waitForIdle()
      assertEquals(0, driver.engine.playbackState.value.volumePercent)
      composeRule.onNodeWithText("0%").assertIsDisplayed()

      driver.viewModel.setVolume(128)
      composeRule.waitForIdle()
      assertEquals(50, driver.engine.playbackState.value.volumePercent)
      composeRule.onNodeWithText("50%").assertIsDisplayed()

      driver.viewModel.setVolume(255)
      composeRule.waitForIdle()
      assertEquals(100, driver.engine.playbackState.value.volumePercent)
      composeRule.onNodeWithText("100%").assertIsDisplayed()
    }
  }

  // =========================================================================
  // Feature 12: Stereo Balance (-127..+127) — 4 Tests (+1 in MainActivity)
  // =========================================================================

  @Test // T1-F12-2
  fun balance_sliderPanLeftAndRightUpdatesReadoutsAndState() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.setBalanceFraction(0.15f)
      val leftBal = driver.engine.playbackState.value.balance
      assertTrue("Expected leftBal < -50, got $leftBal", leftBal < -50)
      composeRule.onNodeWithText("L${(-leftBal * 100 / 127)}").assertIsDisplayed()

      driver.setBalanceFraction(0.85f)
      val rightBal = driver.engine.playbackState.value.balance
      assertTrue("Expected rightBal > 50, got $rightBal", rightBal > 50)
      composeRule.onNodeWithText("R${(rightBal * 100 / 127)}").assertIsDisplayed()
    }
  }

  @Test // T1-F12-3
  fun balance_uiCenterSnapZoneWithinMinus8ToPlus8SnapsToZero() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.viewModel.setBalance(80)
      composeRule.waitForIdle()

      // Inject touch near center corresponding to raw ~ +5 (inside -8..+8 snap zone)
      driver.setBalanceFraction(0.5f + 5f / 254f)
      assertEquals(0, driver.engine.playbackState.value.balance)
      composeRule.onNodeWithText("CTR").assertIsDisplayed()
    }
  }

  @Test // T1-F12-4
  fun balance_programmaticValuesOutsideSnapZoneDisplayExactLeftRightLabels() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      driver.viewModel.setBalance(-64)
      composeRule.waitForIdle()
      composeRule.onNodeWithText("L50").assertIsDisplayed()

      driver.viewModel.setBalance(64)
      composeRule.waitForIdle()
      composeRule.onNodeWithText("R50").assertIsDisplayed()

      driver.viewModel.setBalance(0)
      composeRule.waitForIdle()
      composeRule.onNodeWithText("CTR").assertIsDisplayed()
    }
  }

  @Test // T1-F12-5
  fun exoPlayer_decodesMonoWavCleanlyAndRackBalanceExtremesRemainActive() {
    val monoTrack = AudioTrack(
      id = "mono_1",
      uriString = "file:///Music/mono.wav",
      title = "Mono Sine",
      artist = "Droid",
      fileName = "mono.wav",
      folderPath = "/Music",
      durationMs = 5_000L,
      bitrateKbps = 705,
      sampleRateHz = 44_100,
      channelMode = ChannelMode.MONO,
    )

    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = listOf(monoTrack),
      autoPlay = true,
    ).use { driver ->
      DroidAmpSelectors.monoLabel(composeRule).assertIsDisplayed()
      driver.viewModel.setBalance(-127)
      composeRule.waitForIdle()
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      driver.viewModel.setBalance(127)
      composeRule.waitForIdle()
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)
    }

    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    SyntheticAudioFixtures.createCacheScope(context, "t1_f12_5_mono").use { scope ->
      val monoWav = scope.writeFile(
        "mono.wav",
        SyntheticAudioFixtures.generateWav(channels = 1, durationMs = 400L),
      )
      withExoPlayer(context) { player ->
        val ended = CompletableDeferred<Unit>()
        instrumentation.runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended.complete(Unit)
              }

              override fun onPlayerError(error: PlaybackException) {
                ended.completeExceptionally(error)
              }
            },
          )
          player.setMediaItem(MediaItem.fromUri(scope.fileUri(monoWav)))
          player.prepare()
          player.play()
        }
        runBlocking { withTimeout(8_000L) { ended.await() } }
      }
    }
  }

  // =========================================================================
  // Feature 13: Gapless Playback & Crossfade (0..12s) — 5 Tests
  // =========================================================================

  @Test // T1-F13-1
  fun gaplessAndCrossfade_defaultsAreGaplessOnAndCrossfadeZeroOff() {
    val defaults = DspSettings()
    assertTrue(defaults.gaplessEnabled)
    assertEquals(0, defaults.crossfadeSeconds)
  }

  @Test // T1-F13-2
  fun exoPlayer_twoSyntheticWavTracksAdvanceSeamlesslyGapless() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    SyntheticAudioFixtures.createCacheScope(context, "t1_f13_2").use { scope ->
      val wav1 = scope.writeFile("part1.wav", SyntheticAudioFixtures.generateWav(durationMs = 600L, leftFrequencyHz = 440.0))
      val wav2 = scope.writeFile("part2.wav", SyntheticAudioFixtures.generateWav(durationMs = 600L, leftFrequencyHz = 660.0))

      withExoPlayer(context) { player ->
        val transitionedToSecond = CompletableDeferred<String?>()
        val ended = CompletableDeferred<Unit>()
        instrumentation.runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player.currentMediaItemIndex == 1) {
                  transitionedToSecond.complete(mediaItem?.mediaId)
                }
              }

              override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended.complete(Unit)
              }

              override fun onPlayerError(error: PlaybackException) {
                transitionedToSecond.completeExceptionally(error)
                ended.completeExceptionally(error)
              }
            },
          )
          player.setMediaItems(
            listOf(
              MediaItem.Builder().setMediaId("wav_1").setUri(scope.fileUri(wav1)).build(),
              MediaItem.Builder().setMediaId("wav_2").setUri(scope.fileUri(wav2)).build(),
            ),
          )
          player.prepare()
          player.play()
        }

        val secondId = runBlocking {
          withTimeout(10_000L) {
            val id = transitionedToSecond.await()
            ended.await()
            id
          }
        }
        assertEquals("wav_2", secondId)
      }
    }
  }

  @Test // T1-F13-3
  fun exoPlayer_repeatAllAndRepeatOneLoopWithoutStall() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    SyntheticAudioFixtures.createCacheScope(context, "t1_f13_3").use { scope ->
      val wav1 = scope.writeFile("loop1.wav", SyntheticAudioFixtures.generateWav(durationMs = 400L))
      val wav2 = scope.writeFile("loop2.wav", SyntheticAudioFixtures.generateWav(durationMs = 400L))

      withExoPlayer(context) { player ->
        val wrappedToZero = CompletableDeferred<Unit>()
        val repeatedOne = CompletableDeferred<Unit>()
        val transitions = CopyOnWriteArrayList<Int>()

        instrumentation.runOnMainSync {
          player.addListener(
            object : Player.Listener {
              override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitions.add(player.currentMediaItemIndex)
                if (player.repeatMode == Player.REPEAT_MODE_ALL &&
                  transitions.size >= 3 &&
                  player.currentMediaItemIndex == 0
                ) {
                  wrappedToZero.complete(Unit)
                } else if (player.repeatMode == Player.REPEAT_MODE_ONE &&
                  reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                ) {
                  repeatedOne.complete(Unit)
                }
              }

              override fun onPlayerError(error: PlaybackException) {
                wrappedToZero.completeExceptionally(error)
                repeatedOne.completeExceptionally(error)
              }
            },
          )
          player.repeatMode = Player.REPEAT_MODE_ALL
          player.setMediaItems(
            listOf(
              MediaItem.fromUri(scope.fileUri(wav1)),
              MediaItem.fromUri(scope.fileUri(wav2)),
            ),
          )
          player.prepare()
          player.play()
        }

        runBlocking { withTimeout(10_000L) { wrappedToZero.await() } }
        assertTrue("Expected transitions 0 -> 1 -> 0, got $transitions", transitions.containsAll(listOf(0, 1)))

        instrumentation.runOnMainSync {
          player.repeatMode = Player.REPEAT_MODE_ONE
        }
        runBlocking { withTimeout(10_000L) { repeatedOne.await() } }
      }
    }
  }

  @Test // T1-F13-4
  fun dspSettings_validCrossfadeRange0To12Accepted() {
    for (seconds in listOf(0, 1, 6, 12)) {
      val settings = DspSettings(gaplessEnabled = false, crossfadeSeconds = seconds).sanitized()
      assertFalse(settings.gaplessEnabled)
      assertEquals(seconds, settings.crossfadeSeconds)
    }
  }

  @Test // T1-F13-5
  fun manualSkipAndSeek_workCleanlyWithCrossfadeConfigured() {
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = sampleTracks(3),
      autoPlay = false,
    ).use { driver ->
      driver.engine.setDspSettings(DspSettings(crossfadeSeconds = 5))
      driver.clickPlayOnDeck()
      assertEquals(PlaybackStatus.PLAYING, driver.engine.playbackState.value.status)

      driver.viewModel.seekTo(5_000L)
      assertEquals(5_000L, driver.engine.playbackState.value.positionMs)

      driver.clickNextOnDeck()
      assertEquals(1, driver.engine.playbackState.value.currentIndex)

      driver.clickPauseOnDeck()
      assertEquals(PlaybackStatus.PAUSED, driver.engine.playbackState.value.status)

      driver.clickStopOnDeck()
      assertEquals(PlaybackStatus.STOPPED, driver.engine.playbackState.value.status)
    }
  }

  // =========================================================================
  // Feature 14: Lock-Free FFT & Waveform Ring Buffer + VFD — 4 Tests (+1 in MainActivity)
  // =========================================================================

  @Test // T1-F14-2
  fun visualizerFrame_hasFixed19Spectrum19PeaksAnd64WaveformDimensions() {
    val frame = VisualizerFrame()
    val silent = VisualizerFrame.SILENT
    assertEquals(19, frame.spectrumBars.size)
    assertEquals(19, frame.peakDots.size)
    assertEquals(64, frame.waveform.size)
    assertTrue(silent.spectrumBars.all { it == 0f })
    assertTrue(silent.peakDots.all { it == 0f })
    assertTrue(silent.waveform.all { it == 0f })
    assertEquals(frame, silent)
    assertEquals(frame.hashCode(), silent.hashCode())
  }

  @Test // T1-F14-3
  fun vfd_tappingVisualizerCyclesSpectrumOscilloscopeOff() {
    E2eRackDriver.launchIsolatedRack(composeRule).use { driver ->
      DroidAmpSelectors.visualizerWithMode(composeRule, "SPECTRUM").assertIsDisplayed()
      assertEquals(VisualizerMode.SPECTRUM, driver.engine.playbackState.value.visualizerMode)

      driver.cycleVisualizerMode()
      DroidAmpSelectors.visualizerWithMode(composeRule, "OSCILLOSCOPE").assertIsDisplayed()
      assertEquals(VisualizerMode.OSCILLOSCOPE, driver.engine.playbackState.value.visualizerMode)

      driver.cycleVisualizerMode()
      DroidAmpSelectors.visualizerWithMode(composeRule, "OFF").assertIsDisplayed()
      assertEquals(VisualizerMode.OFF, driver.engine.playbackState.value.visualizerMode)

      driver.cycleVisualizerMode()
      DroidAmpSelectors.visualizerWithMode(composeRule, "SPECTRUM").assertIsDisplayed()
      assertEquals(VisualizerMode.SPECTRUM, driver.engine.playbackState.value.visualizerMode)
    }
  }

  @Test // T1-F14-4
  fun vfd_livePlaybackProducesNonZeroAnimatedSpectrumAndWaveformFrames() {
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      enableSimulationTicker = true,
      initialQueue = sampleTracks(1),
      autoPlay = true,
    ).use { driver ->
      composeRule.waitUntil(timeoutMillis = 3_000L) {
        val frame = driver.engine.visualizerFrame.value
        frame.spectrumBars.any { it > 0f } && frame.waveform.any { it != 0f }
      }
      val live = driver.engine.visualizerFrame.value
      assertTrue(live.spectrumBars.all { it in 0f..1f })
      assertTrue(live.peakDots.all { it in 0f..1f })
      assertTrue(live.waveform.all { it in -1f..1f })
    }
  }

  @Test // T1-F14-5
  fun vfd_sevenSegmentClockTogglesElapsedAndRemainingModes() {
    val track = sampleTracks(1).first().copy(durationMs = 120_000L)
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      initialQueue = listOf(track),
      autoPlay = false,
    ).use { driver ->
      driver.viewModel.seekTo(30_000L)
      composeRule.waitForIdle()
      assertEquals(TimeDisplayMode.ELAPSED, driver.engine.playbackState.value.timeDisplayMode)
      DroidAmpSelectors.clockWithTime(composeRule, "00:30").assertIsDisplayed()

      driver.toggleClockMode()
      assertEquals(TimeDisplayMode.REMAINING, driver.engine.playbackState.value.timeDisplayMode)
      DroidAmpSelectors.clockWithTime(composeRule, "-01:30").assertIsDisplayed()

      driver.toggleClockMode()
      assertEquals(TimeDisplayMode.ELAPSED, driver.engine.playbackState.value.timeDisplayMode)
      DroidAmpSelectors.clockWithTime(composeRule, "00:30").assertIsDisplayed()
    }
  }

  // =========================================================================
  // Feature 15: DSP Defaults & Persistence — 4 Tests (+1 in MainActivity)
  // =========================================================================

  @Test // T1-F15-1
  fun defaults_playbackStateAndEqualizerStateMatchNormativeColdLaunchDefaults() {
    val pb = PlaybackState()
    val eq = EqualizerState()
    val dsp = DspSettings()

    assertEquals(204, pb.volume)
    assertEquals(80, pb.volumePercent)
    assertEquals(0, pb.balance)
    assertFalse(pb.shuffle)
    assertEquals(RepeatMode.OFF, pb.repeatMode)
    assertEquals(TimeDisplayMode.ELAPSED, pb.timeDisplayMode)
    assertEquals(VisualizerMode.SPECTRUM, pb.visualizerMode)

    assertTrue(eq.enabled)
    assertFalse(eq.autoEnabled)
    assertEquals(0f, eq.preampDb, 0f)
    assertEquals(List(10) { 0f }, eq.bandsDb)
    assertEquals("FLAT", eq.activePresetName)

    assertTrue(dsp.gaplessEnabled)
    assertEquals(0, dsp.crossfadeSeconds)
    assertEquals(com.droidamp.player.domain.model.ReplayGainMode.OFF, dsp.replayGainMode)
    assertEquals(0f, dsp.replayGainPreampDb, 0f)
  }

  @Test // T1-F15-3
  fun uiSession_sheetModeMemoryAndDeckTogglesRetainedAcrossCollapseExpand() {
    E2eRackDriver.launchIsolatedRack(
      rule = composeRule,
      startLinked = true,
    ).use { driver ->
      driver.ensureSheetExpanded(SheetMode.LIBRARY)
      DroidAmpSelectors.expandedSheetTitleBar(composeRule).assertIsDisplayed()

      driver.collapseSheetByTitleTap()
      driver.toggleShuffle()
      driver.cycleRepeatMode()
      assertTrue(driver.engine.playbackState.value.shuffle)
      assertEquals(RepeatMode.ALL, driver.engine.playbackState.value.repeatMode)

      driver.expandSheetByTitleTap()
      composeRule.onNodeWithText("DROIDAMP LIBRARY ▼").assertIsDisplayed()
      driver.collapseSheetByTitleTap()
      composeRule.onNodeWithText("REP:ALL").assertIsDisplayed()
    }
  }

  @Test // T1-F15-4
  fun dspSettings_sanitizedPreservesAllValidFields() {
    val custom = DspSettings(
      gaplessEnabled = false,
      crossfadeSeconds = 8,
      replayGainMode = com.droidamp.player.domain.model.ReplayGainMode.TRACK,
      replayGainPreampDb = 4.5f,
    )
    assertEquals(custom, custom.sanitized())
  }

  @Test // T1-F15-5
  fun audioTrack_formatWinampDurationAndDisplayLabelFallbacks() {
    val tFull = AudioTrack("1", "", "Title", "Artist", fileName = "a.mp3", folderPath = "/m", durationMs = 0L)
    val tTitle = AudioTrack("2", "", "Title", "", fileName = "b.mp3", folderPath = "/m", durationMs = 320_000L)
    val tStem = AudioTrack("3", "", "", "", fileName = "file_stem.ogg", folderPath = "/m", durationMs = 5_999_000L)

    assertEquals("Artist - Title", tFull.displayLabel)
    assertEquals("Title", tTitle.displayLabel)
    assertEquals("file_stem", tStem.displayLabel)

    assertEquals("00:00", formatWinampDuration(0L))
    assertEquals("05:20", formatWinampDuration(320_000L))
    assertEquals("99:59", formatWinampDuration(5_999_000L))
  }
}

@RunWith(AndroidJUnit4::class)
class Tier1DspMainActivityTest {

  @get:Rule
  val activityRule = createAndroidComposeRule<MainActivity>()

  @Test // T1-F8-1
  fun inAppUi_zeroAlbumArtOnMainActivityColdLaunchAndExpandedSheets() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)

    driver.ensureSheetExpanded(SheetMode.PLAYLIST)
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)

    driver.ensureSheetExpanded(SheetMode.LIBRARY)
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)

    driver.ensureSheetCollapsed()
    DeviceManifestVerifier.assertZeroInAppAlbumArt(activityRule)
  }

  @Test // T1-F10-1
  fun eq_uiDisplaysAll10WinampBandsPreampGapAndDspSpecs() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    DroidAmpSelectors.eqPreampLabel(activityRule).assertIsDisplayed()
    activityRule.onNodeWithText("+12").assertIsDisplayed()
    activityRule.onNodeWithText("0DB").assertIsDisplayed()
    activityRule.onNodeWithText("-12").assertIsDisplayed()
    for (idx in 0 until WinampEqSpec.BAND_COUNT) {
      DroidAmpSelectors.eqBandLabel(activityRule, idx).assertIsDisplayed()
    }
    DeviceManifestVerifier.assertPreampGapIsolation(activityRule)
    assertEquals(
      listOf(60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000),
      WinampEqSpec.BAND_FREQUENCIES_HZ.toList(),
    )
  }

  @Test // T1-F11-1
  fun volume_coldLaunchDefaultIs204And80PercentOnDeck() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    DroidAmpSelectors.volumeLabel(activityRule).assertIsDisplayed()
    activityRule.onNodeWithText("80%").assertIsDisplayed()
    assertEquals(204, PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME)
    assertEquals(80, PlaybackState().volumePercent)
  }

  @Test // T1-F12-1
  fun balance_defaultIsCenterZeroAndDisplaysCtr() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    DroidAmpSelectors.balanceLabel(activityRule).assertIsDisplayed()
    activityRule.onNodeWithText("CTR").assertIsDisplayed()
    assertEquals(0, PlaybackState().balance)
  }

  @Test // T1-F14-1
  fun manifest_containsZeroRecordAudioInternetOrAccessNetworkState() {
    DeviceManifestVerifier.assertOfflineManifestPermissions(activityRule.activity)
  }

  @Test // T1-F15-2
  fun defaults_mainActivityColdLaunchRendersNormativeDefaults() {
    val driver = E2eRackDriver.fromMainActivity(activityRule)
    driver.waitForIdle()

    activityRule.onNodeWithText("80%").assertIsDisplayed()
    activityRule.onNodeWithText("CTR").assertIsDisplayed()
    DroidAmpSelectors.clockWithTime(activityRule, "00:00").assertIsDisplayed()
    DroidAmpSelectors.visualizerWithMode(activityRule, "SPECTRUM").assertIsDisplayed()
    activityRule.onNodeWithText("REPEAT").assertIsDisplayed()
    DroidAmpSelectors.eqTitleBar(activityRule).assertIsDisplayed()
  }
}
