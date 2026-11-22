package com.droidamp.player.skin

import com.droidamp.player.interop.DetectedWinampFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SkinSource {
  BUILT_IN,
  BUILT_IN_SHOWCASE,
  IMPORTED,
  SAF_FOLDER,
  ;

  val canDelete: Boolean get() = this == IMPORTED
}

data class SkinEntry(
  val id: String,
  val displayName: String,
  val source: SkinSource,
  val origin: SkinOrigin = SkinOrigin.BUILT_IN,
  val uriOrPath: String? = null,
  val author: String = "DroidAmp",
)

data class SkinSettingsState(
  val legacyRenderMode: SkinRenderMode = SkinRenderMode.VECTOR_CHROME,
  val autoContrastGuard: Boolean = true,
  val rawColorOverrideSkinIds: Set<String> = emptySet(),
) {
  fun isContrastGuardEffectiveFor(skinId: String): Boolean =
    autoContrastGuard && skinId !in rawColorOverrideSkinIds
}

/**
 * Explicit user-facing recovery & override notifications (`ADR-029` "Inform + Allow Overriding").
 * Never fail silently or strip user agency: every fallback or wrong-slot file offers clear actions.
 */
sealed interface SkinRecoveryNotice {
  data class SafeBootReverted(
    val failedSkinId: String,
    val failedSkinName: String,
  ) : SkinRecoveryNotice

  data class WrongSlotFileDetected(
    val uriString: String,
    val fileName: String,
    val detectedFormat: DetectedWinampFormat,
    val rawBytes: ByteArray,
  ) : SkinRecoveryNotice

  data class DuplicateSkinConflict(
    val uriString: String,
    val existingEntry: SkinEntry,
    val proposedFileName: String,
    val rawBytes: ByteArray,
  ) : SkinRecoveryNotice

  data class PartialSkinFallback(
    val skinId: String,
    val skinName: String,
    val missingAssets: List<String>,
    val previousSkinId: String,
  ) : SkinRecoveryNotice

  data class ContrastGuardApplied(
    val skinId: String,
    val skinName: String,
    val adjustedTokenCount: Int,
  ) : SkinRecoveryNotice

  data class ActiveSkinDeletedReverted(
    val deletedSkinName: String,
  ) : SkinRecoveryNotice

  data class InvalidSkinError(
    val fileName: String,
    val reason: String,
  ) : SkinRecoveryNotice
}

interface SkinRepository {
  val availableSkins: StateFlow<List<SkinEntry>>
  val activeSkin: StateFlow<DroidAmpSkin>
  val skinSettings: StateFlow<SkinSettingsState>
  val recoveryNotice: StateFlow<SkinRecoveryNotice?>

  fun refreshSkins(linkedRootUris: List<String> = emptyList())
  fun selectSkin(skinId: String)

  /**
   * Inspects [uriString] via [com.droidamp.player.interop.DroidAmpFileSniffer]:
   * - Returns `Success(SkinEntry)` when a valid `.wsz` is imported & activated cleanly.
   * - Returns `Success(null)` when paused on a [SkinRecoveryNotice] (`WrongSlotFileDetected`,
   *   `DuplicateSkinConflict`, or `InvalidSkinError`) awaiting user decision.
   */
  suspend fun importSkinFromUri(uriString: String): Result<SkinEntry?>

  suspend fun resolveDuplicateImport(
    conflict: SkinRecoveryNotice.DuplicateSkinConflict,
    replaceExisting: Boolean,
  ): Result<SkinEntry>

  suspend fun deleteImportedSkin(skinId: String): Boolean

  fun setLegacyRenderMode(mode: SkinRenderMode)
  fun setAutoContrastGuard(enabled: Boolean)
  fun setSkinRawColorsOverride(skinId: String, useRawColors: Boolean)
  fun retrySafeBootSkin(skinId: String)
  fun revertToSkin(skinId: String)
  fun dismissRecoveryNotice()
}

class StaticSkinRepository(
  initialSkin: DroidAmpSkin = ClassicDroidAmpSkin,
) : SkinRepository {
  private val _availableSkins =
    MutableStateFlow(
      listOf(
        SkinEntry(
          id = ClassicDroidAmpSkin.id,
          displayName = ClassicDroidAmpSkin.displayName,
          source = SkinSource.BUILT_IN,
          origin = SkinOrigin.BUILT_IN,
        ),
      ),
    )
  override val availableSkins: StateFlow<List<SkinEntry>> = _availableSkins.asStateFlow()

  private val _activeSkin = MutableStateFlow(initialSkin)
  override val activeSkin: StateFlow<DroidAmpSkin> = _activeSkin.asStateFlow()

  private val _skinSettings = MutableStateFlow(SkinSettingsState())
  override val skinSettings: StateFlow<SkinSettingsState> = _skinSettings.asStateFlow()

  private val _recoveryNotice = MutableStateFlow<SkinRecoveryNotice?>(null)
  override val recoveryNotice: StateFlow<SkinRecoveryNotice?> = _recoveryNotice.asStateFlow()

  override fun refreshSkins(linkedRootUris: List<String>) = Unit
  override fun selectSkin(skinId: String) = Unit
  override suspend fun importSkinFromUri(uriString: String): Result<SkinEntry?> =
    Result.failure(UnsupportedOperationException("Skin import unavailable in preview"))

  override suspend fun resolveDuplicateImport(
    conflict: SkinRecoveryNotice.DuplicateSkinConflict,
    replaceExisting: Boolean,
  ): Result<SkinEntry> =
    Result.failure(UnsupportedOperationException("Skin import unavailable in preview"))

  override suspend fun deleteImportedSkin(skinId: String): Boolean = false
  override fun setLegacyRenderMode(mode: SkinRenderMode) {
    _skinSettings.value = _skinSettings.value.copy(legacyRenderMode = mode)
  }

  override fun setAutoContrastGuard(enabled: Boolean) {
    _skinSettings.value = _skinSettings.value.copy(autoContrastGuard = enabled)
  }

  override fun setSkinRawColorsOverride(skinId: String, useRawColors: Boolean) {
    val updated =
      if (useRawColors) {
        _skinSettings.value.rawColorOverrideSkinIds + skinId
      } else {
        _skinSettings.value.rawColorOverrideSkinIds - skinId
      }
    _skinSettings.value = _skinSettings.value.copy(rawColorOverrideSkinIds = updated)
  }

  override fun retrySafeBootSkin(skinId: String) = Unit
  override fun revertToSkin(skinId: String) = Unit
  override fun dismissRecoveryNotice() {
    _recoveryNotice.value = null
  }
}
