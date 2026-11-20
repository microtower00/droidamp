package com.droidamp.player.audio.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.EqualizerState
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.RepeatMode
import com.droidamp.player.domain.model.ReplayGainMode
import com.droidamp.player.domain.model.TimeDisplayMode
import com.droidamp.player.domain.model.VisualizerMode
import com.droidamp.player.domain.model.WinampEqSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.Locale

data class DspPreferencesSnapshot(
  val dspSettings: DspSettings = DspSettings(),
  val equalizer: EqualizerState = EqualizerState(),
  val volume: Int = PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME,
  val balance: Int = 0,
  val shuffle: Boolean = false,
  val repeatMode: RepeatMode = RepeatMode.OFF,
  val visualizerMode: VisualizerMode = VisualizerMode.SPECTRUM,
  val timeDisplayMode: TimeDisplayMode = TimeDisplayMode.ELAPSED,
  val safRootUris: List<String> = emptyList(),
)

/**
 * DataStore Preferences persistence for DroidAmp DSP settings, EQ, mixer state,
 * deck modes, and persisted SAF root URI strings.
 *
 * Accepts a [DataStore] in its primary constructor so pure JVM unit tests can run
 * against [androidx.datastore.preferences.core.PreferenceDataStoreFactory].
 */
class DspPreferencesStore(
  private val dataStore: DataStore<Preferences>,
) {
  val snapshotFlow: Flow<DspPreferencesSnapshot> =
    dataStore.data
      .catch { exception ->
        if (exception is IOException) {
          emit(emptyPreferences())
        } else {
          throw exception
        }
      }.map(::decodeSnapshot)

  suspend fun loadSnapshot(): DspPreferencesSnapshot = snapshotFlow.first()

  suspend fun saveDspSettings(settings: DspSettings) {
    val sanitized = settings.sanitized()
    safeEdit { prefs ->
      prefs[KEY_DSP_GAPLESS_ENABLED] = sanitized.gaplessEnabled
      prefs[KEY_DSP_CROSSFADE_SECONDS] = sanitized.crossfadeSeconds
      prefs[KEY_DSP_REPLAY_GAIN_MODE] = sanitized.replayGainMode.name
      prefs[KEY_DSP_REPLAY_GAIN_PREAMP_DB] = sanitized.replayGainPreampDb
    }
  }

  suspend fun saveEqualizerState(state: EqualizerState) {
    val safePreamp =
      if (state.preampDb.isFinite()) WinampEqSpec.clampDb(state.preampDb) else 0f
    val safeBands = normalizeBands(state.bandsDb)
    val serializedBands =
      safeBands.joinToString(",") { db -> String.format(Locale.US, "%.4f", db) }
    val presetName = state.activePresetName.ifBlank { ClassicWinampPresets.FLAT.name }

    safeEdit { prefs ->
      prefs[KEY_EQ_ENABLED] = state.enabled
      prefs[KEY_EQ_AUTO_ENABLED] = state.autoEnabled
      prefs[KEY_EQ_PREAMP_DB] = safePreamp
      prefs[KEY_EQ_BANDS_DB] = serializedBands
      prefs[KEY_EQ_ACTIVE_PRESET_NAME] = presetName
    }
  }

  suspend fun saveVolume(volume0To255: Int) {
    safeEdit { prefs ->
      prefs[KEY_MIXER_VOLUME] =
        volume0To255.coerceIn(PlaybackState.MIN_VOLUME, PlaybackState.MAX_VOLUME)
    }
  }

  suspend fun saveBalance(balanceMinus127To127: Int) {
    safeEdit { prefs ->
      prefs[KEY_MIXER_BALANCE] =
        balanceMinus127To127.coerceIn(PlaybackState.MIN_BALANCE, PlaybackState.MAX_BALANCE)
    }
  }

  suspend fun saveShuffle(enabled: Boolean) {
    safeEdit { prefs -> prefs[KEY_DECK_SHUFFLE] = enabled }
  }

  suspend fun saveRepeatMode(mode: RepeatMode) {
    safeEdit { prefs -> prefs[KEY_DECK_REPEAT_MODE] = mode.name }
  }

  suspend fun saveVisualizerMode(mode: VisualizerMode) {
    safeEdit { prefs -> prefs[KEY_DECK_VISUALIZER_MODE] = mode.name }
  }

  suspend fun saveTimeDisplayMode(mode: TimeDisplayMode) {
    safeEdit { prefs -> prefs[KEY_DECK_TIME_DISPLAY_MODE] = mode.name }
  }

  suspend fun saveSafRootUris(uris: List<String>) {
    val cleaned = uris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    safeEdit { prefs ->
      prefs[KEY_SAF_ROOT_URIS_SET] = cleaned.toSet()
      prefs[KEY_SAF_ROOT_URIS_ORDERED] = cleaned.joinToString("\n")
    }
  }

  suspend fun addSafRootUri(uriString: String) {
    val trimmed = uriString.trim()
    if (trimmed.isEmpty()) return
    safeEdit { prefs ->
      val existing = decodeSafRootUris(prefs).toMutableList()
      if (trimmed !in existing) {
        existing.add(trimmed)
      }
      prefs[KEY_SAF_ROOT_URIS_SET] = existing.toSet()
      prefs[KEY_SAF_ROOT_URIS_ORDERED] = existing.joinToString("\n")
    }
  }

  suspend fun removeSafRootUri(uriString: String) {
    val trimmed = uriString.trim()
    if (trimmed.isEmpty()) return
    safeEdit { prefs ->
      val updated = decodeSafRootUris(prefs).filterNot { it == trimmed }
      prefs[KEY_SAF_ROOT_URIS_SET] = updated.toSet()
      prefs[KEY_SAF_ROOT_URIS_ORDERED] = updated.joinToString("\n")
    }
  }

  suspend fun saveAll(snapshot: DspPreferencesSnapshot) {
    val safeDsp = snapshot.dspSettings.sanitized()
    val safePreamp =
      if (snapshot.equalizer.preampDb.isFinite()) {
        WinampEqSpec.clampDb(snapshot.equalizer.preampDb)
      } else {
        0f
      }
    val safeBands = normalizeBands(snapshot.equalizer.bandsDb)
    val serializedBands =
      safeBands.joinToString(",") { db -> String.format(Locale.US, "%.4f", db) }
    val presetName =
      snapshot.equalizer.activePresetName.ifBlank { ClassicWinampPresets.FLAT.name }
    val safeUris = snapshot.safRootUris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    safeEdit { prefs ->
      prefs[KEY_DSP_GAPLESS_ENABLED] = safeDsp.gaplessEnabled
      prefs[KEY_DSP_CROSSFADE_SECONDS] = safeDsp.crossfadeSeconds
      prefs[KEY_DSP_REPLAY_GAIN_MODE] = safeDsp.replayGainMode.name
      prefs[KEY_DSP_REPLAY_GAIN_PREAMP_DB] = safeDsp.replayGainPreampDb
      prefs[KEY_EQ_ENABLED] = snapshot.equalizer.enabled
      prefs[KEY_EQ_AUTO_ENABLED] = snapshot.equalizer.autoEnabled
      prefs[KEY_EQ_PREAMP_DB] = safePreamp
      prefs[KEY_EQ_BANDS_DB] = serializedBands
      prefs[KEY_EQ_ACTIVE_PRESET_NAME] = presetName
      prefs[KEY_MIXER_VOLUME] =
        snapshot.volume.coerceIn(PlaybackState.MIN_VOLUME, PlaybackState.MAX_VOLUME)
      prefs[KEY_MIXER_BALANCE] =
        snapshot.balance.coerceIn(PlaybackState.MIN_BALANCE, PlaybackState.MAX_BALANCE)
      prefs[KEY_DECK_SHUFFLE] = snapshot.shuffle
      prefs[KEY_DECK_REPEAT_MODE] = snapshot.repeatMode.name
      prefs[KEY_DECK_VISUALIZER_MODE] = snapshot.visualizerMode.name
      prefs[KEY_DECK_TIME_DISPLAY_MODE] = snapshot.timeDisplayMode.name
      prefs[KEY_SAF_ROOT_URIS_SET] = safeUris.toSet()
      prefs[KEY_SAF_ROOT_URIS_ORDERED] = safeUris.joinToString("\n")
    }
  }

  private suspend fun safeEdit(transform: suspend (MutablePreferences) -> Unit) {
    try {
      dataStore.edit(transform)
    } catch (_: IOException) {
      // Best-effort persistence: runtime playback continues even if storage write fails.
    }
  }

  private fun decodeSnapshot(prefs: Preferences): DspPreferencesSnapshot {
    val gapless = prefs[KEY_DSP_GAPLESS_ENABLED] ?: true
    val crossfade =
      (prefs[KEY_DSP_CROSSFADE_SECONDS] ?: 0).coerceIn(
        DspSettings.MIN_CROSSFADE_SECONDS,
        DspSettings.MAX_CROSSFADE_SECONDS,
      )
    val rgMode = parseEnumOrDefault(prefs[KEY_DSP_REPLAY_GAIN_MODE], ReplayGainMode.OFF)
    val rawRgPreamp = prefs[KEY_DSP_REPLAY_GAIN_PREAMP_DB] ?: 0f
    val rgPreamp =
      if (rawRgPreamp.isFinite()) {
        rawRgPreamp.coerceIn(
          DspSettings.MIN_REPLAY_GAIN_PREAMP_DB,
          DspSettings.MAX_REPLAY_GAIN_PREAMP_DB,
        )
      } else {
        0f
      }
    val dspSettings =
      DspSettings(
        gaplessEnabled = gapless,
        crossfadeSeconds = crossfade,
        replayGainMode = rgMode,
        replayGainPreampDb = rgPreamp,
      ).sanitized()

    val eqEnabled = prefs[KEY_EQ_ENABLED] ?: true
    val eqAutoEnabled = prefs[KEY_EQ_AUTO_ENABLED] ?: false
    val rawEqPreamp = prefs[KEY_EQ_PREAMP_DB] ?: 0f
    val eqPreamp =
      if (rawEqPreamp.isFinite()) WinampEqSpec.clampDb(rawEqPreamp) else 0f
    val eqBands = decodeBands(prefs[KEY_EQ_BANDS_DB])
    val eqPresetName =
      prefs[KEY_EQ_ACTIVE_PRESET_NAME]?.takeIf { it.isNotBlank() }
        ?: ClassicWinampPresets.FLAT.name
    val eqState =
      EqualizerState(
        enabled = eqEnabled,
        autoEnabled = eqAutoEnabled,
        preampDb = eqPreamp,
        bandsDb = eqBands,
        activePresetName = eqPresetName,
      )

    val volume =
      (prefs[KEY_MIXER_VOLUME] ?: PlaybackState.DEFAULT_COLD_LAUNCH_VOLUME).coerceIn(
        PlaybackState.MIN_VOLUME,
        PlaybackState.MAX_VOLUME,
      )
    val balance =
      (prefs[KEY_MIXER_BALANCE] ?: 0).coerceIn(
        PlaybackState.MIN_BALANCE,
        PlaybackState.MAX_BALANCE,
      )
    val shuffle = prefs[KEY_DECK_SHUFFLE] ?: false
    val repeatMode = parseEnumOrDefault(prefs[KEY_DECK_REPEAT_MODE], RepeatMode.OFF)
    val visualizerMode = parseEnumOrDefault(prefs[KEY_DECK_VISUALIZER_MODE], VisualizerMode.SPECTRUM)
    val timeDisplayMode =
      parseEnumOrDefault(prefs[KEY_DECK_TIME_DISPLAY_MODE], TimeDisplayMode.ELAPSED)
    val safRootUris = decodeSafRootUris(prefs)

    return DspPreferencesSnapshot(
      dspSettings = dspSettings,
      equalizer = eqState,
      volume = volume,
      balance = balance,
      shuffle = shuffle,
      repeatMode = repeatMode,
      visualizerMode = visualizerMode,
      timeDisplayMode = timeDisplayMode,
      safRootUris = safRootUris,
    )
  }

  private fun decodeBands(raw: String?): List<Float> {
    if (raw.isNullOrBlank()) return DEFAULT_FLAT_BANDS
    val parts = raw.split(',')
    if (parts.size != WinampEqSpec.BAND_COUNT) return DEFAULT_FLAT_BANDS
    val parsed = ArrayList<Float>(WinampEqSpec.BAND_COUNT)
    for (part in parts) {
      val value = part.trim().toFloatOrNull() ?: return DEFAULT_FLAT_BANDS
      if (!value.isFinite()) return DEFAULT_FLAT_BANDS
      parsed.add(WinampEqSpec.clampDb(value))
    }
    return parsed
  }

  private fun normalizeBands(bands: List<Float>): List<Float> {
    if (bands.size != WinampEqSpec.BAND_COUNT) return DEFAULT_FLAT_BANDS
    return bands.map { value -> if (value.isFinite()) WinampEqSpec.clampDb(value) else 0f }
  }

  private fun decodeSafRootUris(prefs: Preferences): List<String> {
    val ordered =
      prefs[KEY_SAF_ROOT_URIS_ORDERED]
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        ?.toList()
        .orEmpty()
    val setUris =
      prefs[KEY_SAF_ROOT_URIS_SET]
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

    if (ordered.isEmpty()) return setUris.distinct()
    val merged = ordered.toMutableList()
    for (uri in setUris) {
      if (uri !in merged) {
        merged.add(uri)
      }
    }
    return merged
  }

  private inline fun <reified T : Enum<T>> parseEnumOrDefault(
    raw: String?,
    defaultValue: T,
  ): T {
    if (raw.isNullOrBlank()) return defaultValue
    return enumValues<T>().firstOrNull { it.name == raw.trim() } ?: defaultValue
  }

  companion object {
    private const val PREFS_NAME = "droidamp_dsp_prefs"
    private val DEFAULT_FLAT_BANDS = List(WinampEqSpec.BAND_COUNT) { 0f }

    private val KEY_DSP_GAPLESS_ENABLED = booleanPreferencesKey("dsp_gapless_enabled")
    private val KEY_DSP_CROSSFADE_SECONDS = intPreferencesKey("dsp_crossfade_seconds")
    private val KEY_DSP_REPLAY_GAIN_MODE = stringPreferencesKey("dsp_replay_gain_mode")
    private val KEY_DSP_REPLAY_GAIN_PREAMP_DB = floatPreferencesKey("dsp_replay_gain_preamp_db")

    private val KEY_EQ_ENABLED = booleanPreferencesKey("eq_enabled")
    private val KEY_EQ_AUTO_ENABLED = booleanPreferencesKey("eq_auto_enabled")
    private val KEY_EQ_PREAMP_DB = floatPreferencesKey("eq_preamp_db")
    private val KEY_EQ_BANDS_DB = stringPreferencesKey("eq_bands_db")
    private val KEY_EQ_ACTIVE_PRESET_NAME = stringPreferencesKey("eq_active_preset_name")

    private val KEY_MIXER_VOLUME = intPreferencesKey("mixer_volume_0_255")
    private val KEY_MIXER_BALANCE = intPreferencesKey("mixer_balance_minus127_127")
    private val KEY_DECK_SHUFFLE = booleanPreferencesKey("deck_shuffle_enabled")
    private val KEY_DECK_REPEAT_MODE = stringPreferencesKey("deck_repeat_mode")
    private val KEY_DECK_VISUALIZER_MODE = stringPreferencesKey("deck_visualizer_mode")
    private val KEY_DECK_TIME_DISPLAY_MODE = stringPreferencesKey("deck_time_display_mode")

    private val KEY_SAF_ROOT_URIS_SET = stringSetPreferencesKey("storage_saf_root_uris")
    private val KEY_SAF_ROOT_URIS_ORDERED = stringPreferencesKey("storage_saf_root_uris_ordered")

    private val Context.dspPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
      name = PREFS_NAME,
      corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    )

    fun create(context: Context): DspPreferencesStore =
      DspPreferencesStore(context.applicationContext.dspPreferencesDataStore)
  }
}
