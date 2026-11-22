package com.droidamp.player.interop

import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.contract.EqfCodec
import com.droidamp.player.domain.contract.Q2Codec
import com.droidamp.player.domain.model.ClassicWinampPresets
import com.droidamp.player.domain.model.EqPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

interface ByteStoreGateway {
  fun readBytes(fileName: String): ByteArray?
  fun writeBytes(fileName: String, bytes: ByteArray)
  fun delete(fileName: String)
}

class FileByteStoreGateway(
  private val directory: File,
) : ByteStoreGateway {
  override fun readBytes(fileName: String): ByteArray? {
    val file = File(directory, fileName)
    if (!file.isFile) return null
    return runCatching { file.readBytes() }.getOrNull()
  }

  override fun writeBytes(
    fileName: String,
    bytes: ByteArray,
  ) {
    runCatching {
      if (!directory.exists()) {
        directory.mkdirs()
      }
      val tempFile = File(directory, "$fileName.tmp")
      val targetFile = File(directory, fileName)
      tempFile.writeBytes(bytes)
      if (!tempFile.renameTo(targetFile)) {
        targetFile.writeBytes(bytes)
        tempFile.delete()
      }
    }
  }

  override fun delete(fileName: String) {
    runCatching {
      File(directory, fileName).delete()
      File(directory, "$fileName.tmp").delete()
    }
  }
}

enum class PresetConflictType {
  NONE,
  USER_PRESET,
  BUILT_IN_PRESET,
}

/**
 * Persistent storage for user-saved EQ curves (`user_presets.q1`) and user-defined Auto-EQ
 * rules (`user_autoeq.q2`) using standard Winamp binary formats under `filesDir/eq/` (`ADR-029`).
 */
class UserEqPresetStore(
  private val gateway: ByteStoreGateway,
  private val eqfCodec: EqfCodec = BinaryEqfCodec(),
  private val q2Codec: Q2Codec = WinampQ2Codec(),
) {
  private val stateMutex = Mutex()
  private var loaded = false

  private val _userPresets = MutableStateFlow<List<EqPreset>>(emptyList())
  val userPresets: StateFlow<List<EqPreset>> = _userPresets.asStateFlow()

  private val _userAutoEqRules = MutableStateFlow<List<AutoEqRule>>(emptyList())
  val userAutoEqRules: StateFlow<List<AutoEqRule>> = _userAutoEqRules.asStateFlow()

  suspend fun ensureLoaded() {
    stateMutex.withLock {
      ensureLoadedLocked()
    }
  }

  fun hasPresetConflict(name: String): PresetConflictType {
    val normalized = name.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return PresetConflictType.NONE
    if (_userPresets.value.any { it.name.trim().lowercase(Locale.ROOT) == normalized }) {
      return PresetConflictType.USER_PRESET
    }
    if (ClassicWinampPresets.ALL.any { it.name.trim().lowercase(Locale.ROOT) == normalized }) {
      return PresetConflictType.BUILT_IN_PRESET
    }
    return PresetConflictType.NONE
  }

  suspend fun savePreset(
    preset: EqPreset,
    overwrite: Boolean = true,
  ): Result<EqPreset> =
    stateMutex.withLock {
      ensureLoadedLocked()
      val trimmedName = preset.name.trim()
      if (trimmedName.isEmpty()) {
        return@withLock Result.failure(IllegalArgumentException("Preset name cannot be blank."))
      }
      val sanitized = preset.copy(name = trimmedName)
      val key = trimmedName.lowercase(Locale.ROOT)
      val current = _userPresets.value.toMutableList()
      val existingIdx = current.indexOfFirst { it.name.trim().lowercase(Locale.ROOT) == key }
      if (existingIdx >= 0 && !overwrite) {
        return@withLock Result.failure(IllegalStateException("Preset '$trimmedName' already exists."))
      }
      if (existingIdx >= 0) {
        current[existingIdx] = sanitized
      } else {
        current.add(sanitized)
      }
      persistUserPresetsLocked(current)
      Result.success(sanitized)
    }

  suspend fun deletePreset(name: String): Boolean =
    stateMutex.withLock {
      ensureLoadedLocked()
      val target = name.trim().lowercase(Locale.ROOT)
      if (target.isEmpty()) return@withLock false
      val updated = _userPresets.value.filterNot { it.name.trim().lowercase(Locale.ROOT) == target }
      if (updated.size == _userPresets.value.size) return@withLock false
      persistUserPresetsLocked(updated)
      true
    }

  suspend fun importPresets(
    incoming: List<EqPreset>,
    replaceAll: Boolean,
  ): Int =
    stateMutex.withLock {
      ensureLoadedLocked()
      val cleaned =
        incoming.mapNotNull { p ->
          val n = p.name.trim()
          if (n.isEmpty()) null else p.copy(name = n)
        }
      val next =
        if (replaceAll) {
          deduplicatePresets(cleaned)
        } else {
          deduplicatePresets(_userPresets.value + cleaned)
        }
      persistUserPresetsLocked(next)
      cleaned.size
    }

  suspend fun saveAutoRule(rule: AutoEqRule) {
    stateMutex.withLock {
      ensureLoadedLocked()
      val pattern = rule.pattern.trim()
      if (pattern.isEmpty()) return@withLock
      val sanitized = rule.copy(pattern = pattern)
      val key = pattern.lowercase(Locale.ROOT)
      val updated = _userAutoEqRules.value.toMutableList()
      val idx = updated.indexOfFirst { it.pattern.trim().lowercase(Locale.ROOT) == key }
      if (idx >= 0) {
        updated[idx] = sanitized
      } else {
        updated.add(sanitized)
      }
      persistUserRulesLocked(updated)
    }
  }

  suspend fun deleteAutoRule(pattern: String): Boolean =
    stateMutex.withLock {
      ensureLoadedLocked()
      val key = pattern.trim().lowercase(Locale.ROOT)
      if (key.isEmpty()) return@withLock false
      val updated = _userAutoEqRules.value.filterNot { it.pattern.trim().lowercase(Locale.ROOT) == key }
      if (updated.size == _userAutoEqRules.value.size) return@withLock false
      persistUserRulesLocked(updated)
      true
    }

  suspend fun importAutoRules(
    incoming: List<AutoEqRule>,
    replaceAll: Boolean,
  ): Int =
    stateMutex.withLock {
      ensureLoadedLocked()
      val cleaned =
        incoming.mapNotNull { r ->
          val pat = r.pattern.trim()
          if (pat.isEmpty()) null else r.copy(pattern = pat)
        }
      val combined =
        if (replaceAll) {
          cleaned
        } else {
          cleaned + _userAutoEqRules.value
        }
      val deduped = combined.distinctBy { it.pattern.trim().lowercase(Locale.ROOT) }
      persistUserRulesLocked(deduped)
      cleaned.size
    }

  fun exportUserPresetsQ1Bytes(): ByteArray = eqfCodec.encodePresetLibraryQ1(_userPresets.value)

  fun exportUserAutoEqQ2Bytes(): ByteArray = q2Codec.encodeAutoPresetsQ2(_userAutoEqRules.value)

  private fun ensureLoadedLocked() {
    if (loaded) return
    loaded = true
    _userPresets.value =
      gateway.readBytes(PRESETS_Q1_FILE)
        ?.let { bytes -> runCatching { eqfCodec.parsePresetLibraryQ1(bytes) }.getOrNull() }
        .orEmpty()
    _userAutoEqRules.value =
      gateway.readBytes(AUTO_EQ_Q2_FILE)
        ?.let { bytes -> runCatching { q2Codec.parseAutoPresetsQ2(bytes) }.getOrNull() }
        .orEmpty()
  }

  private fun persistUserPresetsLocked(presets: List<EqPreset>) {
    _userPresets.value = presets
    if (presets.isEmpty()) {
      gateway.delete(PRESETS_Q1_FILE)
    } else {
      gateway.writeBytes(PRESETS_Q1_FILE, eqfCodec.encodePresetLibraryQ1(presets))
    }
  }

  private fun persistUserRulesLocked(rules: List<AutoEqRule>) {
    _userAutoEqRules.value = rules
    if (rules.isEmpty()) {
      gateway.delete(AUTO_EQ_Q2_FILE)
    } else {
      gateway.writeBytes(AUTO_EQ_Q2_FILE, q2Codec.encodeAutoPresetsQ2(rules))
    }
  }

  private fun deduplicatePresets(list: List<EqPreset>): List<EqPreset> {
    val map = LinkedHashMap<String, EqPreset>()
    for (p in list) {
      val key = p.name.trim().lowercase(Locale.ROOT)
      if (key.isNotEmpty()) {
        map[key] = p
      }
    }
    return map.values.toList()
  }

  companion object {
    const val PRESETS_Q1_FILE = "user_presets.q1"
    const val AUTO_EQ_Q2_FILE = "user_autoeq.q2"
  }
}
