package com.droidamp.player.audio.service

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.RepeatMode
import com.droidamp.player.domain.model.ReplayGainMode
import com.droidamp.player.domain.model.TimeDisplayMode
import com.droidamp.player.domain.model.VisualizerMode
import com.droidamp.player.domain.model.WinampEqSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class DspPreferencesStoreTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var originalLocale: Locale

  @Before
  fun setUp() {
    originalLocale = Locale.getDefault()
  }

  @After
  fun tearDown() {
    Locale.setDefault(originalLocale)
  }

  private fun createStore(
    file: File,
    scope: CoroutineScope,
  ): Pair<DspPreferencesStore, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>> {
    val dataStore =
      PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { file },
      )
    return DspPreferencesStore(dataStore) to dataStore
  }

  @Test
  fun freshStore_returnsExpectedDefaults() = runTest {
    val file = File(tempFolder.root, "fresh.preferences_pb")
    val (store, _) = createStore(file, backgroundScope)

    val snapshot = store.loadSnapshot()
    assertTrue(snapshot.dspSettings.gaplessEnabled)
    assertEquals(0, snapshot.dspSettings.crossfadeSeconds)
    assertEquals(ReplayGainMode.OFF, snapshot.dspSettings.replayGainMode)
    assertEquals(0f, snapshot.dspSettings.replayGainPreampDb, 0.001f)
    assertEquals(PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME, snapshot.volume)
    assertEquals(0, snapshot.balance)
    assertFalse(snapshot.shuffle)
    assertEquals(RepeatMode.OFF, snapshot.repeatMode)
    assertEquals(VisualizerMode.SPECTRUM, snapshot.visualizerMode)
    assertEquals(TimeDisplayMode.ELAPSED, snapshot.timeDisplayMode)
    assertEquals(EqualizerState(), snapshot.equalizer)
    assertTrue(snapshot.safRootUris.isEmpty())
  }

  @Test
  fun roundTripPersistence_acrossInstances_andLocaleItaly() = runTest {
    Locale.setDefault(Locale.ITALY)
    val file = File(tempFolder.root, "roundtrip.preferences_pb")
    val scope1 = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val (store1, _) = createStore(file, scope1)

    val expected =
      DspPreferencesSnapshot(
        dspSettings =
          DspSettings(
            gaplessEnabled = false,
            crossfadeSeconds = 7,
            replayGainMode = ReplayGainMode.ALBUM,
            replayGainPreampDb = -3.25f,
          ),
        equalizer =
          EqualizerState(
            enabled = false,
            autoEnabled = true,
            preampDb = -2.5f,
            bandsDb = listOf(4.8f, 3.0f, -3.4f, -4.8f, -2.0f, 2.4f, 5.4f, 6.8f, 6.8f, 6.8f),
            activePresetName = ClassicWinampPresets.ROCK.name,
          ),
        volume = 111,
        balance = -42,
        shuffle = true,
        repeatMode = RepeatMode.ALL,
        visualizerMode = VisualizerMode.OSCILLOSCOPE,
        timeDisplayMode = TimeDisplayMode.REMAINING,
        safRootUris = listOf("content://tree/primary:Music", "content://tree/sd:Lossless"),
      )

    store1.saveAll(expected)
    store1.addSafRootUri("content://tree/primary:Music") // duplicate ignored
    store1.addSafRootUri("content://tree/usb:Flac")
    store1.removeSafRootUri("content://tree/sd:Lossless")
    scope1.coroutineContext.job.cancelAndJoin()

    // Reopen from same file using a fresh DataStore instance
    val (store2, _) = createStore(file, backgroundScope)
    val restored = store2.loadSnapshot()

    assertEquals(expected.dspSettings, restored.dspSettings)
    assertEquals(expected.equalizer.enabled, restored.equalizer.enabled)
    assertEquals(expected.equalizer.autoEnabled, restored.equalizer.autoEnabled)
    assertEquals(expected.equalizer.preampDb, restored.equalizer.preampDb, 0.001f)
    assertEquals(expected.equalizer.activePresetName, restored.equalizer.activePresetName)
    for (i in 0 until WinampEqSpec.BAND_COUNT) {
      assertEquals(expected.equalizer.bandsDb[i], restored.equalizer.bandsDb[i], 0.001f)
    }
    assertEquals(111, restored.volume)
    assertEquals(-42, restored.balance)
    assertTrue(restored.shuffle)
    assertEquals(RepeatMode.ALL, restored.repeatMode)
    assertEquals(VisualizerMode.OSCILLOSCOPE, restored.visualizerMode)
    assertEquals(TimeDisplayMode.REMAINING, restored.timeDisplayMode)
    assertEquals(
      listOf("content://tree/primary:Music", "content://tree/usb:Flac"),
      restored.safRootUris,
    )
  }

  @Test
  fun corruptionHandler_recoversWithDefaults_whenFileCorrupted() = runTest {
    val file = File(tempFolder.root, "corrupt.preferences_pb")
    file.writeBytes(byteArrayOf(0x7F, 0x00, 0x11, 0x22, 0x55, 0x7E))

    val (store, _) = createStore(file, backgroundScope)
    val recovered = store.loadSnapshot()

    assertEquals(PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME, recovered.volume)
    assertTrue(recovered.dspSettings.gaplessEnabled)
    assertEquals(EqualizerState(), recovered.equalizer)

    // Subsequent writes work cleanly after corruption recovery
    store.saveVolume(88)
    assertEquals(88, store.loadSnapshot().volume)
  }

  @Test
  fun decodeSnapshot_clampsOutOfRangeAndFallsBackOnMalformedValues() = runTest {
    val file = File(tempFolder.root, "malformed.preferences_pb")
    val (store, rawDataStore) = createStore(file, backgroundScope)

    rawDataStore.edit { prefs ->
      prefs[booleanPreferencesKey("dsp_gapless_enabled")] = false
      prefs[intPreferencesKey("dsp_crossfade_seconds")] = 99
      prefs[stringPreferencesKey("dsp_replay_gain_mode")] = "INVALID_MODE"
      prefs[floatPreferencesKey("dsp_replay_gain_preamp_db")] = Float.POSITIVE_INFINITY
      prefs[floatPreferencesKey("eq_preamp_db")] = Float.NaN
      prefs[stringPreferencesKey("eq_bands_db")] = "1.0,2.0,3.0" // wrong band count
      prefs[stringPreferencesKey("eq_active_preset_name")] = "   "
      prefs[intPreferencesKey("mixer_volume_0_255")] = 999
      prefs[intPreferencesKey("mixer_balance_minus127_127")] = -999
      prefs[stringPreferencesKey("deck_repeat_mode")] = "BAD_REPEAT"
      prefs[stringPreferencesKey("deck_visualizer_mode")] = "BAD_VIS"
      prefs[stringPreferencesKey("deck_time_display_mode")] = "BAD_TIME"
      prefs[stringSetPreferencesKey("storage_saf_root_uris")] = setOf("", "  ", "content://tree/ok")
    }

    val snapshot = store.loadSnapshot()
    assertFalse(snapshot.dspSettings.gaplessEnabled)
    assertEquals(DspSettings.MAX_CROSSFADE_SECONDS, snapshot.dspSettings.crossfadeSeconds)
    assertEquals(ReplayGainMode.OFF, snapshot.dspSettings.replayGainMode)
    assertEquals(0f, snapshot.dspSettings.replayGainPreampDb, 0.001f)
    assertEquals(0f, snapshot.equalizer.preampDb, 0.001f)
    assertEquals(List(WinampEqSpec.BAND_COUNT) { 0f }, snapshot.equalizer.bandsDb)
    assertEquals(ClassicWinampPresets.FLAT.name, snapshot.equalizer.activePresetName)
    assertEquals(PlaybackState.MAX_VOLUME, snapshot.volume)
    assertEquals(PlaybackState.MIN_BALANCE, snapshot.balance)
    assertEquals(RepeatMode.OFF, snapshot.repeatMode)
    assertEquals(VisualizerMode.SPECTRUM, snapshot.visualizerMode)
    assertEquals(TimeDisplayMode.ELAPSED, snapshot.timeDisplayMode)
    assertEquals(listOf("content://tree/ok"), snapshot.safRootUris)
  }
}
