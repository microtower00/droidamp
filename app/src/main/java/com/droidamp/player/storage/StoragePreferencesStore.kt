package com.droidamp.player.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.droidamp.player.domain.model.StorageBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.Locale

data class PersistedSafRoot(
  val uriString: String,
  val displayName: String = "",
)

data class StoragePreferencesSnapshot(
  val storageBackend: StorageBackend = StorageBackend.SAF,
  val safRoots: List<PersistedSafRoot> = emptyList(),
)

/**
 * DataStore Preferences persistence for active [StorageBackend] and linked SAF roots.
 *
 * Uses a dedicated DataStore file ([PREFS_NAME]) separate from `DspPreferencesStore`, and
 * accepts a [DataStore] in its primary constructor for pure-JVM unit tests.
 */
class StoragePreferencesStore(
  private val dataStore: DataStore<Preferences>,
) {
  val snapshotFlow: Flow<StoragePreferencesSnapshot> =
    dataStore.data
      .catch { exception ->
        if (exception is IOException) {
          emit(emptyPreferences())
        } else {
          throw exception
        }
      }.map(::decodeSnapshot)

  suspend fun loadSnapshot(): StoragePreferencesSnapshot = snapshotFlow.first()

  suspend fun setStorageBackend(backend: StorageBackend) {
    safeEdit { prefs ->
      prefs[KEY_STORAGE_BACKEND] = backend.name
    }
  }

  suspend fun addSafRoot(
    uriString: String,
    displayName: String = "",
  ) {
    val trimmedUri = uriString.trim()
    if (trimmedUri.isEmpty()) return
    val trimmedName = displayName.trim()

    safeEdit { prefs ->
      val current = decodeSafRootRecords(prefs[KEY_SAF_ROOT_RECORDS]).toMutableList()
      val existingIndex = current.indexOfFirst { it.uriString == trimmedUri }
      if (existingIndex >= 0) {
        if (trimmedName.isNotEmpty()) {
          current[existingIndex] = PersistedSafRoot(trimmedUri, trimmedName)
        }
      } else {
        current.add(PersistedSafRoot(trimmedUri, trimmedName))
      }
      prefs[KEY_SAF_ROOT_RECORDS] = encodeSafRootRecords(current)
    }
  }

  suspend fun saveSafRoots(roots: List<PersistedSafRoot>) {
    val normalized = normalizeRoots(roots)
    safeEdit { prefs ->
      prefs[KEY_SAF_ROOT_RECORDS] = encodeSafRootRecords(normalized)
    }
  }

  suspend fun removeSafRoot(uriString: String) {
    val trimmedUri = uriString.trim()
    if (trimmedUri.isEmpty()) return
    safeEdit { prefs ->
      val updated = decodeSafRootRecords(prefs[KEY_SAF_ROOT_RECORDS]).filterNot { it.uriString == trimmedUri }
      prefs[KEY_SAF_ROOT_RECORDS] = encodeSafRootRecords(updated)
    }
  }

  suspend fun retainSafRoots(validUriStrings: Set<String>) {
    val normalizedValid = validUriStrings.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    safeEdit { prefs ->
      val updated =
        decodeSafRootRecords(prefs[KEY_SAF_ROOT_RECORDS]).filter { it.uriString in normalizedValid }
      prefs[KEY_SAF_ROOT_RECORDS] = encodeSafRootRecords(updated)
    }
  }

  suspend fun clearSafRoots() {
    safeEdit { prefs ->
      prefs[KEY_SAF_ROOT_RECORDS] = ""
    }
  }

  private suspend fun safeEdit(transform: suspend (MutablePreferences) -> Unit) {
    try {
      dataStore.edit(transform)
    } catch (_: IOException) {
      // Best-effort persistence: runtime browsing continues even if disk write fails.
    }
  }

  private fun decodeSnapshot(prefs: Preferences): StoragePreferencesSnapshot {
    val backend = parseStorageBackendOrDefault(prefs[KEY_STORAGE_BACKEND])
    val safRoots = decodeSafRootRecords(prefs[KEY_SAF_ROOT_RECORDS])
    return StoragePreferencesSnapshot(
      storageBackend = backend,
      safRoots = safRoots,
    )
  }

  private fun parseStorageBackendOrDefault(raw: String?): StorageBackend {
    if (raw.isNullOrBlank()) return StorageBackend.SAF
    val normalized = raw.trim()
    return StorageBackend.entries.firstOrNull { it.name == normalized } ?: StorageBackend.SAF
  }

  private fun normalizeRoots(roots: List<PersistedSafRoot>): List<PersistedSafRoot> {
    val byUri = LinkedHashMap<String, PersistedSafRoot>()
    for (root in roots) {
      val trimmedUri = root.uriString.trim()
      if (trimmedUri.isEmpty()) continue
      val trimmedName = root.displayName.trim()
      val existing = byUri[trimmedUri]
      if (existing == null || trimmedName.isNotEmpty()) {
        byUri[trimmedUri] = PersistedSafRoot(trimmedUri, trimmedName.ifEmpty { existing?.displayName.orEmpty() })
      }
    }
    return byUri.values.toList()
  }

  private fun encodeSafRootRecords(roots: List<PersistedSafRoot>): String =
    normalizeRoots(roots).joinToString(separator = "\n") { root ->
      "${escapeField(root.uriString)}\t${escapeField(root.displayName)}"
    }

  private fun decodeSafRootRecords(raw: String?): List<PersistedSafRoot> {
    if (raw.isNullOrBlank()) return emptyList()
    val decoded = ArrayList<PersistedSafRoot>()
    for (line in raw.lineSequence()) {
      if (line.isEmpty()) continue
      val tabIndex = line.indexOf('\t')
      val rawUri = if (tabIndex >= 0) line.substring(0, tabIndex) else line
      val rawName = if (tabIndex >= 0) line.substring(tabIndex + 1) else ""
      val uri = unescapeField(rawUri).trim()
      if (uri.isEmpty()) continue
      val name = unescapeField(rawName).trim()
      decoded.add(PersistedSafRoot(uriString = uri, displayName = name))
    }
    return normalizeRoots(decoded)
  }

  private fun escapeField(value: String): String =
    buildString(value.length) {
      for (ch in value) {
        when (ch) {
          '%' -> append("%25")
          '\t' -> append("%09")
          '\n' -> append("%0A")
          '\r' -> append("%0D")
          else -> append(ch)
        }
      }
    }

  private fun unescapeField(value: String): String {
    if ('%' !in value) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
      val ch = value[i]
      if (ch == '%' && i + 2 < value.length) {
        val code = value.substring(i, i + 3).uppercase(Locale.ROOT)
        when (code) {
          "%25" -> {
            out.append('%')
            i += 3
            continue
          }
          "%09" -> {
            out.append('\t')
            i += 3
            continue
          }
          "%0A" -> {
            out.append('\n')
            i += 3
            continue
          }
          "%0D" -> {
            out.append('\r')
            i += 3
            continue
          }
        }
      }
      out.append(ch)
      i += 1
    }
    return out.toString()
  }

  companion object {
    const val PREFS_NAME = "droidamp_storage_prefs"

    private val KEY_STORAGE_BACKEND = stringPreferencesKey("storage_backend")
    private val KEY_SAF_ROOT_RECORDS = stringPreferencesKey("storage_saf_root_records")

    private val Context.storagePreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
      name = PREFS_NAME,
      corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    )

    fun create(context: Context): StoragePreferencesStore =
      StoragePreferencesStore(context.applicationContext.storagePreferencesDataStore)
  }
}
