package com.droidamp.player.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

enum class VolumeKeyMode {
  SYNC_SYSTEM,
  INDEPENDENT_DECK,
}

enum class KeepScreenOnMode {
  OFF,
  WHILE_PLAYING,
  ALWAYS,
}

enum class QueueTapAction {
  DOUBLE_TAP,
  SINGLE_TAP,
}

enum class TrackTransitionMode {
  STANDARD,
  GAPLESS,
  CROSSFADE,
}

enum class StartupVolumeMode {
  REMEMBER_LAST,
  CLASSIC_80_PERCENT,
}

enum class AudioBufferMode {
  LOW_LATENCY,
  STANDARD,
}

/**
 * Milestone 6 application preferences (`ADR-029`), covering Phone & Controls,
 * Audio & Playback transitions, Audio Buffer latency, and Startup Volume.
 */
data class AppPreferences(
  val volumeKeyMode: VolumeKeyMode = VolumeKeyMode.SYNC_SYSTEM,
  val pauseOnDisconnect: Boolean = true,
  val autoResumeOnReconnect: Boolean = false,
  val keepScreenOnMode: KeepScreenOnMode = KeepScreenOnMode.OFF,
  val queueTapAction: QueueTapAction = QueueTapAction.DOUBLE_TAP,
  val hapticFeedbackEnabled: Boolean = true,
  val trackTransitionMode: TrackTransitionMode = TrackTransitionMode.GAPLESS,
  val crossfadeDurationSec: Int = DEFAULT_CROSSFADE_DURATION_SEC,
  val startupVolumeMode: StartupVolumeMode = StartupVolumeMode.REMEMBER_LAST,
  val audioBufferMode: AudioBufferMode = AudioBufferMode.LOW_LATENCY,
) {
  fun sanitized(): AppPreferences =
    copy(
      crossfadeDurationSec =
        crossfadeDurationSec.coerceIn(MIN_CROSSFADE_DURATION_SEC, MAX_CROSSFADE_DURATION_SEC),
    )

  companion object {
    const val MIN_CROSSFADE_DURATION_SEC = 1
    const val MAX_CROSSFADE_DURATION_SEC = 12
    const val DEFAULT_CROSSFADE_DURATION_SEC = 4
  }
}

interface AppPreferencesRepository {
  val preferences: StateFlow<AppPreferences>
  suspend fun update(transform: (AppPreferences) -> AppPreferences)
  suspend fun resetToDefaults()
}

class InMemoryAppPreferencesRepository(
  initial: AppPreferences = AppPreferences(),
) : AppPreferencesRepository {
  private val _preferences = MutableStateFlow(initial.sanitized())
  override val preferences: StateFlow<AppPreferences> = _preferences.asStateFlow()

  override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
    _preferences.update { current -> transform(current).sanitized() }
  }

  override suspend fun resetToDefaults() {
    _preferences.value = AppPreferences()
  }
}

class DataStoreAppPreferencesRepository(
  private val store: AppPreferencesStore,
  scope: CoroutineScope,
) : AppPreferencesRepository {
  private val updateMutex = Mutex()
  private val _preferences = MutableStateFlow(AppPreferences())
  override val preferences: StateFlow<AppPreferences> = _preferences.asStateFlow()

  init {
    scope.launch {
      store.preferencesFlow.collect { latest ->
        _preferences.value = latest.sanitized()
      }
    }
  }

  override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
    updateMutex.withLock {
      val next = transform(_preferences.value).sanitized()
      _preferences.value = next
      store.saveAll(next)
    }
  }

  override suspend fun resetToDefaults() {
    updateMutex.withLock {
      val defaults = AppPreferences()
      _preferences.value = defaults
      store.resetToDefaults()
    }
  }
}

/**
 * Pure-JVM-testable DataStore Preferences store for [AppPreferences].
 */
class AppPreferencesStore(
  private val dataStore: DataStore<Preferences>,
) {
  val preferencesFlow: Flow<AppPreferences> =
    dataStore.data
      .catch { exception ->
        if (exception is IOException) {
          emit(emptyPreferences())
        } else {
          throw exception
        }
      }.map(::decodePreferences)

  suspend fun loadPreferences(): AppPreferences = preferencesFlow.first()

  suspend fun saveAll(prefsValue: AppPreferences) {
    val safe = prefsValue.sanitized()
    safeEdit { prefs ->
      prefs[KEY_VOLUME_KEY_MODE] = safe.volumeKeyMode.name
      prefs[KEY_PAUSE_ON_DISCONNECT] = safe.pauseOnDisconnect
      prefs[KEY_AUTO_RESUME_ON_RECONNECT] = safe.autoResumeOnReconnect
      prefs[KEY_KEEP_SCREEN_ON_MODE] = safe.keepScreenOnMode.name
      prefs[KEY_QUEUE_TAP_ACTION] = safe.queueTapAction.name
      prefs[KEY_HAPTIC_FEEDBACK_ENABLED] = safe.hapticFeedbackEnabled
      prefs[KEY_TRACK_TRANSITION_MODE] = safe.trackTransitionMode.name
      prefs[KEY_CROSSFADE_DURATION_SEC] = safe.crossfadeDurationSec
      prefs[KEY_STARTUP_VOLUME_MODE] = safe.startupVolumeMode.name
      prefs[KEY_AUDIO_BUFFER_MODE] = safe.audioBufferMode.name
    }
  }

  suspend fun resetToDefaults() {
    saveAll(AppPreferences())
  }

  private suspend fun safeEdit(transform: suspend (MutablePreferences) -> Unit) {
    try {
      dataStore.edit(transform)
    } catch (_: IOException) {
      // Best-effort persistence: runtime UI continues cleanly if disk write fails.
    }
  }

  private fun decodePreferences(prefs: Preferences): AppPreferences {
    val defaultPrefs = AppPreferences()
    return AppPreferences(
      volumeKeyMode = parseEnumOrDefault(prefs[KEY_VOLUME_KEY_MODE], defaultPrefs.volumeKeyMode),
      pauseOnDisconnect = prefs[KEY_PAUSE_ON_DISCONNECT] ?: defaultPrefs.pauseOnDisconnect,
      autoResumeOnReconnect =
        prefs[KEY_AUTO_RESUME_ON_RECONNECT] ?: defaultPrefs.autoResumeOnReconnect,
      keepScreenOnMode =
        parseEnumOrDefault(prefs[KEY_KEEP_SCREEN_ON_MODE], defaultPrefs.keepScreenOnMode),
      queueTapAction = parseEnumOrDefault(prefs[KEY_QUEUE_TAP_ACTION], defaultPrefs.queueTapAction),
      hapticFeedbackEnabled =
        prefs[KEY_HAPTIC_FEEDBACK_ENABLED] ?: defaultPrefs.hapticFeedbackEnabled,
      trackTransitionMode =
        parseEnumOrDefault(prefs[KEY_TRACK_TRANSITION_MODE], defaultPrefs.trackTransitionMode),
      crossfadeDurationSec =
        (prefs[KEY_CROSSFADE_DURATION_SEC] ?: defaultPrefs.crossfadeDurationSec).coerceIn(
          AppPreferences.MIN_CROSSFADE_DURATION_SEC,
          AppPreferences.MAX_CROSSFADE_DURATION_SEC,
        ),
      startupVolumeMode =
        parseEnumOrDefault(prefs[KEY_STARTUP_VOLUME_MODE], defaultPrefs.startupVolumeMode),
      audioBufferMode =
        parseEnumOrDefault(prefs[KEY_AUDIO_BUFFER_MODE], defaultPrefs.audioBufferMode),
    ).sanitized()
  }

  private inline fun <reified T : Enum<T>> parseEnumOrDefault(
    raw: String?,
    defaultValue: T,
  ): T {
    if (raw.isNullOrBlank()) return defaultValue
    val normalized = raw.trim()
    return enumValues<T>().firstOrNull { it.name == normalized } ?: defaultValue
  }

  companion object {
    const val PREFS_NAME = "droidamp_app_prefs"

    private val KEY_VOLUME_KEY_MODE = stringPreferencesKey("app_volume_key_mode")
    private val KEY_PAUSE_ON_DISCONNECT = booleanPreferencesKey("app_pause_on_disconnect")
    private val KEY_AUTO_RESUME_ON_RECONNECT = booleanPreferencesKey("app_auto_resume_on_reconnect")
    private val KEY_KEEP_SCREEN_ON_MODE = stringPreferencesKey("app_keep_screen_on_mode")
    private val KEY_QUEUE_TAP_ACTION = stringPreferencesKey("app_queue_tap_action")
    private val KEY_HAPTIC_FEEDBACK_ENABLED = booleanPreferencesKey("app_haptic_feedback_enabled")
    private val KEY_TRACK_TRANSITION_MODE = stringPreferencesKey("app_track_transition_mode")
    private val KEY_CROSSFADE_DURATION_SEC = intPreferencesKey("app_crossfade_duration_sec")
    private val KEY_STARTUP_VOLUME_MODE = stringPreferencesKey("app_startup_volume_mode")
    private val KEY_AUDIO_BUFFER_MODE = stringPreferencesKey("app_audio_buffer_mode")

    private val Context.appPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
      name = PREFS_NAME,
      corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    )

    fun create(context: Context): AppPreferencesStore =
      AppPreferencesStore(context.applicationContext.appPreferencesDataStore)
  }
}
