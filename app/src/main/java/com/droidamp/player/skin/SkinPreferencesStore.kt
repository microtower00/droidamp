package com.droidamp.player.skin

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface SkinPreferencesStore {
  suspend fun loadActiveSkinId(): String
  suspend fun saveActiveSkinId(skinId: String)

  suspend fun loadLegacyRenderMode(): SkinRenderMode = SkinRenderMode.VECTOR_CHROME
  suspend fun saveLegacyRenderMode(mode: SkinRenderMode) = Unit

  suspend fun loadAutoContrastGuard(): Boolean = true
  suspend fun saveAutoContrastGuard(enabled: Boolean) = Unit

  suspend fun loadRawColorOverrideSkinIds(): Set<String> = emptySet()
  suspend fun saveRawColorOverrideSkinIds(skinIds: Set<String>) = Unit

  suspend fun loadPendingBootSkinId(): String? = null
  suspend fun savePendingBootSkinId(skinId: String?) = Unit

  companion object {
    fun create(context: Context): SkinPreferencesStore =
      DataStoreSkinPreferencesStore(context.applicationContext)
  }
}

class InMemorySkinPreferencesStore(
  private var activeId: String = ClassicDroidAmpSkin.id,
  private var legacyRenderMode: SkinRenderMode = SkinRenderMode.VECTOR_CHROME,
  private var autoContrastGuard: Boolean = true,
  private var rawColorOverrideSkinIds: Set<String> = emptySet(),
  private var pendingBootSkinId: String? = null,
) : SkinPreferencesStore {
  override suspend fun loadActiveSkinId(): String = activeId

  override suspend fun saveActiveSkinId(skinId: String) {
    activeId = skinId
  }

  override suspend fun loadLegacyRenderMode(): SkinRenderMode = legacyRenderMode

  override suspend fun saveLegacyRenderMode(mode: SkinRenderMode) {
    legacyRenderMode = mode
  }

  override suspend fun loadAutoContrastGuard(): Boolean = autoContrastGuard

  override suspend fun saveAutoContrastGuard(enabled: Boolean) {
    autoContrastGuard = enabled
  }

  override suspend fun loadRawColorOverrideSkinIds(): Set<String> = rawColorOverrideSkinIds

  override suspend fun saveRawColorOverrideSkinIds(skinIds: Set<String>) {
    rawColorOverrideSkinIds = skinIds.toSet()
  }

  override suspend fun loadPendingBootSkinId(): String? = pendingBootSkinId

  override suspend fun savePendingBootSkinId(skinId: String?) {
    pendingBootSkinId = skinId
  }
}

private class DataStoreSkinPreferencesStore(
  context: Context,
) : SkinPreferencesStore {
  private val dataStore: DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
      corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
      produceFile = { context.preferencesDataStoreFile(STORE_NAME) },
    )

  override suspend fun loadActiveSkinId(): String =
    dataStore.data
      .map { prefs -> prefs[KEY_ACTIVE_SKIN_ID] ?: ClassicDroidAmpSkin.id }
      .first()

  override suspend fun saveActiveSkinId(skinId: String) {
    dataStore.edit { prefs ->
      prefs[KEY_ACTIVE_SKIN_ID] = skinId
    }
  }

  override suspend fun loadLegacyRenderMode(): SkinRenderMode =
    dataStore.data
      .map { prefs ->
        val raw = prefs[KEY_LEGACY_RENDER_MODE]
        SkinRenderMode.entries.firstOrNull { it.name == raw } ?: SkinRenderMode.VECTOR_CHROME
      }
      .first()

  override suspend fun saveLegacyRenderMode(mode: SkinRenderMode) {
    dataStore.edit { prefs ->
      prefs[KEY_LEGACY_RENDER_MODE] = mode.name
    }
  }

  override suspend fun loadAutoContrastGuard(): Boolean =
    dataStore.data
      .map { prefs -> prefs[KEY_AUTO_CONTRAST_GUARD] ?: true }
      .first()

  override suspend fun saveAutoContrastGuard(enabled: Boolean) {
    dataStore.edit { prefs ->
      prefs[KEY_AUTO_CONTRAST_GUARD] = enabled
    }
  }

  override suspend fun loadRawColorOverrideSkinIds(): Set<String> =
    dataStore.data
      .map { prefs -> prefs[KEY_RAW_COLOR_OVERRIDE_SKIN_IDS] ?: emptySet() }
      .first()

  override suspend fun saveRawColorOverrideSkinIds(skinIds: Set<String>) {
    dataStore.edit { prefs ->
      prefs[KEY_RAW_COLOR_OVERRIDE_SKIN_IDS] = skinIds
    }
  }

  override suspend fun loadPendingBootSkinId(): String? =
    dataStore.data
      .map { prefs -> prefs[KEY_PENDING_BOOT_SKIN_ID] }
      .first()

  override suspend fun savePendingBootSkinId(skinId: String?) {
    dataStore.edit { prefs ->
      if (skinId == null) {
        prefs.remove(KEY_PENDING_BOOT_SKIN_ID)
      } else {
        prefs[KEY_PENDING_BOOT_SKIN_ID] = skinId
      }
    }
  }

  companion object {
    private const val STORE_NAME = "droidamp_skin_prefs"
    private val KEY_ACTIVE_SKIN_ID = stringPreferencesKey("active_skin_id")
    private val KEY_LEGACY_RENDER_MODE = stringPreferencesKey("legacy_render_mode")
    private val KEY_AUTO_CONTRAST_GUARD = booleanPreferencesKey("auto_contrast_guard")
    private val KEY_RAW_COLOR_OVERRIDE_SKIN_IDS = stringSetPreferencesKey("raw_color_override_skin_ids")
    private val KEY_PENDING_BOOT_SKIN_ID = stringPreferencesKey("pending_boot_skin_id")
  }
}
