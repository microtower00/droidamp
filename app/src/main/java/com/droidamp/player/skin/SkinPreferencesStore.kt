package com.droidamp.player.skin

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface SkinPreferencesStore {
  suspend fun loadActiveSkinId(): String
  suspend fun saveActiveSkinId(skinId: String)

  companion object {
    fun create(context: Context): SkinPreferencesStore =
      DataStoreSkinPreferencesStore(context.applicationContext)
  }
}

class InMemorySkinPreferencesStore(
  private var activeId: String = ClassicDroidAmpSkin.id,
) : SkinPreferencesStore {
  override suspend fun loadActiveSkinId(): String = activeId
  override suspend fun saveActiveSkinId(skinId: String) {
    activeId = skinId
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

  companion object {
    private const val STORE_NAME = "droidamp_skin_prefs"
    private val KEY_ACTIVE_SKIN_ID = stringPreferencesKey("active_skin_id")
  }
}
