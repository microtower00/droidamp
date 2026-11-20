package com.droidamp.player.e2e.harness

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidamp.player.MainActivity
import com.droidamp.player.domain.contract.WinampIniSnapshot
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.e2e.fixtures.SyntheticAudioFixtures
import com.droidamp.player.e2e.fixtures.SyntheticWinampFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compile and sanity smoke test exercising @RunWith(AndroidJUnit4::class),
 * createAndroidComposeRule<MainActivity>(), and all 5 E2E infrastructure modules.
 */
@RunWith(AndroidJUnit4::class)
class InfraSmokeTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun smoke_manifestIsOffline_andAudioManagerReadable() {
    DeviceManifestVerifier.assertOfflineManifestPermissions(composeTestRule.activity)
    val snapshot = DeviceManifestVerifier.queryAudioDeviceSnapshot(composeTestRule.activity)
    assertTrue("Expected positive music stream max volume", snapshot.musicStreamMaxVolume > 0)
  }

  @Test
  fun smoke_syntheticFixtures_generateValidBytesAndCleanup() {
    val wav = SyntheticAudioFixtures.generateWav(sampleRateHz = 44_100, channels = 2, durationMs = 100L)
    assertTrue("WAV header + samples must exceed 44 bytes", wav.size > 44)

    val png = SyntheticAudioFixtures.generateMinimal1x1Png()
    val mp3 = SyntheticAudioFixtures.generateMp3(
      sampleRateHz = 44_100,
      bitrateKbps = 128,
      channels = 2,
      durationMs = 200L,
      metadata = SyntheticAudioFixtures.Id3MetadataSpec(
        title = "Smoke Tone",
        artist = "DroidAmp",
        album = "E2E Infra",
        replayGainTrackGainDb = "-6.50 dB",
        replayGainTrackPeak = "0.980000",
        artworkPngBytes = png,
      ),
    )
    assertTrue("MP3 with ID3 tag must be non-empty", mp3.size > 128)

    SyntheticAudioFixtures.createCacheScope(composeTestRule.activity, "infra_smoke_test").use { scope ->
      val wavFile = scope.writeFile("tone.wav", wav)
      assertTrue("Materialized WAV fixture must exist", wavFile.exists())
      assertTrue("File URI must use file scheme", scope.fileUri(wavFile).startsWith("file://"))
    }

    val eqf = SyntheticWinampFixtures.eqfBytes(ClassicWinampPresets.ROCK)
    assertEquals(SyntheticWinampFixtures.EQF_RECORD_SIZE, eqf.size)

    val q1 = SyntheticWinampFixtures.q1Bytes(ClassicWinampPresets.ALL.take(3))
    assertEquals(3 * SyntheticWinampFixtures.EQF_RECORD_SIZE, q1.size)

    val q2 = SyntheticWinampFixtures.q2Bytes(
      listOf(SyntheticWinampFixtures.Q2RuleSpec("Electronic", ClassicWinampPresets.TECHNO)),
    )
    assertEquals(SyntheticWinampFixtures.EQF_RECORD_SIZE, q2.size)

    val ini = SyntheticWinampFixtures.winampIni(
      WinampIniSnapshot(
        volume = 204,
        pan = 0,
        shuffle = false,
        repeat = false,
        timeRemaining = false,
        eqEnabled = true,
        eqAuto = false,
        eqPreampDb = 0f,
        eqBandsDb = ClassicWinampPresets.FLAT.bandsDb,
      ),
    )
    assertTrue("INI must include [Winamp] section", ini.contains("[Winamp]"))

    val entries = SyntheticWinampFixtures.relativeAndWindowsPathPlaylistEntries()
    assertTrue(SyntheticWinampFixtures.m3u8Content(entries).contains("#EXTM3U"))
    assertTrue(SyntheticWinampFixtures.plsContent(entries).contains("[playlist]"))
  }

  @Test
  fun smoke_coldLaunchRackGeometryAndZeroInAppArt() {
    val driver = E2eRackDriver.fromMainActivity(composeTestRule)
    driver.waitForIdle()

    DroidAmpSelectors.deckTitleBar(composeTestRule).assertIsDisplayed()
    val volBounds = DroidAmpSelectors.volumeSliderBounds(composeTestRule)
    val balBounds = DroidAmpSelectors.balanceSliderBounds(composeTestRule)
    assertTrue("VOL slider width must be positive", volBounds.width > 0f)
    assertTrue("BAL slider width must be positive", balBounds.width > 0f)

    DeviceManifestVerifier.assertPreampGapIsolation(composeTestRule)
    DeviceManifestVerifier.assertPeekingSheetSwipeBuffer(composeTestRule)
    DeviceManifestVerifier.assertNavigationBarAndBottomLipClearance(composeTestRule)
    DeviceManifestVerifier.assertZeroInAppAlbumArt(composeTestRule)
  }
}
