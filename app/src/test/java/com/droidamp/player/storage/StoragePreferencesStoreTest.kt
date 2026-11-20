package com.droidamp.player.storage

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.droidamp.player.domain.model.StorageBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StoragePreferencesStoreTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun createStore(
    file: File,
    scope: CoroutineScope,
  ): Pair<StoragePreferencesStore, DataStore<Preferences>> {
    val dataStore =
      PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = { file },
      )
    return StoragePreferencesStore(dataStore) to dataStore
  }

  @Test
  fun freshStore_defaultsToSafAndEmptyRoots() = runTest {
    val file = File(tempFolder.root, "fresh.preferences_pb")
    val (store, _) = createStore(file, backgroundScope)

    val snapshot = store.loadSnapshot()
    assertEquals(StorageBackend.SAF, snapshot.storageBackend)
    assertTrue(snapshot.safRoots.isEmpty())
  }

  @Test
  fun roundTripPersistence_preservesBackendOrderEscapingAndDeduplication() = runTest {
    val file = File(tempFolder.root, "roundtrip.preferences_pb")
    val scope1 = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val (store1, _) = createStore(file, scope1)

    val root1 =
      PersistedSafRoot(
        uriString = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FRock",
        displayName = "Rock\t100%\nMix\r2",
      )
    val root2 =
      PersistedSafRoot(
        uriString = "content://com.android.externalstorage.documents/tree/sd%3ALossless",
        displayName = "Lossless",
      )

    store1.setStorageBackend(StorageBackend.MEDIA_STORE)
    store1.addSafRoot(root1.uriString, root1.displayName)
    store1.addSafRoot(root2.uriString, "")
    store1.addSafRoot(root2.uriString, root2.displayName) // updates displayName without duplicating
    store1.addSafRoot("content://tree/extra", "Extra")
    store1.removeSafRoot("content://tree/extra")
    scope1.coroutineContext.job.cancelAndJoin()

    val (store2, _) = createStore(file, backgroundScope)
    val restored = store2.loadSnapshot()
    assertEquals(StorageBackend.MEDIA_STORE, restored.storageBackend)
    assertEquals(listOf(root1, root2), restored.safRoots)

    store2.retainSafRoots(setOf(root2.uriString))
    assertEquals(listOf(root2), store2.loadSnapshot().safRoots)

    store2.clearSafRoots()
    assertTrue(store2.loadSnapshot().safRoots.isEmpty())
  }

  @Test
  fun corruptionHandler_recoversToSafDefaults() = runTest {
    val file = File(tempFolder.root, "corrupt.preferences_pb")
    file.writeBytes(byteArrayOf(0x7F, 0x01, 0x22, 0x55, 0x7E))

    val (store, _) = createStore(file, backgroundScope)
    val recovered = store.loadSnapshot()
    assertEquals(StorageBackend.SAF, recovered.storageBackend)
    assertTrue(recovered.safRoots.isEmpty())

    store.addSafRoot("content://tree/primary:Music", "Music")
    assertEquals(1, store.loadSnapshot().safRoots.size)
  }

  @Test
  fun malformedBackendValue_fallsBackToSaf() = runTest {
    val file = File(tempFolder.root, "malformed.preferences_pb")
    val (store, rawDataStore) = createStore(file, backgroundScope)

    rawDataStore.edit { prefs ->
      prefs[stringPreferencesKey("storage_backend")] = "UNKNOWN_BACKEND"
      prefs[stringPreferencesKey("storage_saf_root_records")] = "  \ncontent://tree/ok\tMusic\n\tIgnored"
    }

    val snapshot = store.loadSnapshot()
    assertEquals(StorageBackend.SAF, snapshot.storageBackend)
    assertEquals(listOf(PersistedSafRoot("content://tree/ok", "Music")), snapshot.safRoots)
  }
}
