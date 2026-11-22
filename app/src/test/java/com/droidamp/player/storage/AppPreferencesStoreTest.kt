package com.droidamp.player.storage

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesStoreTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createStore(
    file: File,
    scope: CoroutineScope,
  ): AppPreferencesStore {
    val ds =
      PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { file },
      )
    return AppPreferencesStore(ds)
  }

  @Test
  fun defaultsAndRoundTripPersistence_workAcrossStoreInstances() = runTest {
    val file = File(tempFolder.root, "app_prefs.preferences_pb")
    val scope1 = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val store1 = createStore(file, scope1)

    val initial = store1.loadPreferences()
    assertEquals(VolumeKeyMode.SYNC_SYSTEM, initial.volumeKeyMode)
    assertTrue(initial.pauseOnDisconnect)
    assertFalse(initial.autoResumeOnReconnect)
    assertEquals(KeepScreenOnMode.OFF, initial.keepScreenOnMode)
    assertEquals(QueueTapAction.DOUBLE_TAP, initial.queueTapAction)
    assertTrue(initial.hapticFeedbackEnabled)
    assertEquals(TrackTransitionMode.GAPLESS, initial.trackTransitionMode)
    assertEquals(4, initial.crossfadeDurationSec)
    assertEquals(StartupVolumeMode.REMEMBER_LAST, initial.startupVolumeMode)
    assertEquals(AudioBufferMode.LOW_LATENCY, initial.audioBufferMode)

    // Custom crossfadeDurationSec stays persisted even when switching back to GAPLESS / STANDARD
    store1.saveAll(
      AppPreferences(
        volumeKeyMode = VolumeKeyMode.INDEPENDENT_DECK,
        pauseOnDisconnect = false,
        autoResumeOnReconnect = true,
        keepScreenOnMode = KeepScreenOnMode.WHILE_PLAYING,
        queueTapAction = QueueTapAction.SINGLE_TAP,
        hapticFeedbackEnabled = false,
        trackTransitionMode = TrackTransitionMode.CROSSFADE,
        crossfadeDurationSec = 9,
        startupVolumeMode = StartupVolumeMode.CLASSIC_80_PERCENT,
        audioBufferMode = AudioBufferMode.STANDARD,
      ),
    )
    store1.saveAll(
      store1.loadPreferences().copy(trackTransitionMode = TrackTransitionMode.GAPLESS),
    )
    scope1.coroutineContext.job.cancelAndJoin()

    val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val store2 = createStore(file, scope2)
    val restored = store2.loadPreferences()
    assertEquals(VolumeKeyMode.INDEPENDENT_DECK, restored.volumeKeyMode)
    assertFalse(restored.pauseOnDisconnect)
    assertTrue(restored.autoResumeOnReconnect)
    assertEquals(KeepScreenOnMode.WHILE_PLAYING, restored.keepScreenOnMode)
    assertEquals(QueueTapAction.SINGLE_TAP, restored.queueTapAction)
    assertFalse(restored.hapticFeedbackEnabled)
    assertEquals(TrackTransitionMode.GAPLESS, restored.trackTransitionMode)
    assertEquals(9, restored.crossfadeDurationSec)
    assertEquals(StartupVolumeMode.CLASSIC_80_PERCENT, restored.startupVolumeMode)
    assertEquals(AudioBufferMode.STANDARD, restored.audioBufferMode)

    store2.resetToDefaults()
    assertEquals(AppPreferences(), store2.loadPreferences())
    scope2.coroutineContext.job.cancelAndJoin()
  }

  @Test
  fun crossfadeDurationIsClampedToValidRange() = runTest {
    val file = File(tempFolder.root, "clamp_prefs.preferences_pb")
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val store = createStore(file, scope)

    store.saveAll(AppPreferences(crossfadeDurationSec = 0))
    assertEquals(AppPreferences.MIN_CROSSFADE_DURATION_SEC, store.loadPreferences().crossfadeDurationSec)

    store.saveAll(AppPreferences(crossfadeDurationSec = 99))
    assertEquals(AppPreferences.MAX_CROSSFADE_DURATION_SEC, store.loadPreferences().crossfadeDurationSec)
    scope.coroutineContext.job.cancelAndJoin()
  }
}
