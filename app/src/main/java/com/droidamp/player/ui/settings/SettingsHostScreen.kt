package com.droidamp.player.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.EqPreset
import com.droidamp.player.domain.model.LibraryScanState
import com.droidamp.player.domain.model.ManagedSafFolder
import com.droidamp.player.domain.model.ReplayGainMode
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.interop.IniAnalysisResult
import com.droidamp.player.interop.Q1AnalysisResult
import com.droidamp.player.interop.Q2AnalysisResult
import com.droidamp.player.skin.SkinEntry
import com.droidamp.player.skin.SkinRenderMode
import com.droidamp.player.skin.SkinSettingsState
import com.droidamp.player.storage.AppPreferences
import com.droidamp.player.storage.TrackTransitionMode

@Composable
fun SettingsHostScreen(
  route: SettingsRoute,
  appPreferences: AppPreferences,
  dspSettings: DspSettings,
  storageBackend: StorageBackend,
  managedSafFolders: List<ManagedSafFolder>,
  scanState: LibraryScanState,
  availableSkins: List<SkinEntry>,
  activeSkinId: String,
  activeSkinName: String,
  skinSettings: SkinSettingsState,
  userPresetCount: Int,
  autoEqRuleCount: Int,
  activeEqPresetName: String,
  userEqPresets: List<EqPreset>,
  autoEqRules: List<AutoEqRule>,
  currentTrack: AudioTrack?,
  pendingMigrationDialog: PendingMigrationDialog?,
  onNavigate: (SettingsRoute) -> Unit,
  onBack: () -> Unit,
  onUpdatePreferences: ((AppPreferences) -> AppPreferences) -> Unit,
  onSelectMusicSource: (StorageBackend) -> Unit,
  onAddMusicFolder: () -> Unit,
  onRegrantFolder: (ManagedSafFolder) -> Unit,
  onRemoveFolder: (ManagedSafFolder) -> Unit,
  onRetryAudioPermission: () -> Unit,
  onOpenAppPermissionSettings: () -> Unit,
  onRescanLibrary: () -> Unit,
  onSetTrackTransitionMode: (TrackTransitionMode) -> Unit,
  onSetCrossfadeDurationSec: (Int) -> Unit,
  onSetReplayGainMode: (ReplayGainMode) -> Unit,
  onSetReplayGainPreampDb: (Float) -> Unit,
  onApplyEqPreset: (EqPreset) -> Unit,
  onResetEqFlat: () -> Unit,
  onImportEqfClick: () -> Unit,
  onExportEqfClick: () -> Unit,
  onSaveAutoEqForTrack: (AudioTrack) -> Unit,
  onSaveAutoEqForFolder: (AudioTrack) -> Unit,
  onDeleteUserEqPreset: (String) -> Unit,
  onDeleteUserAutoEqRule: (String) -> Unit,
  onSelectSkin: (String) -> Unit,
  onImportSkinClick: () -> Unit,
  onDeleteImportedSkin: (SkinEntry) -> Unit,
  onSetLegacyRenderMode: (SkinRenderMode) -> Unit,
  onSetAutoContrastGuard: (Boolean) -> Unit,
  onImportIniClick: () -> Unit,
  onExportIniClick: () -> Unit,
  onImportQ1Click: () -> Unit,
  onExportQ1Click: () -> Unit,
  onImportQ2Click: () -> Unit,
  onExportQ2Click: () -> Unit,
  onConfirmIniImport: (IniAnalysisResult) -> Unit,
  onConfirmQ1Import: (Q1AnalysisResult, Boolean) -> Unit,
  onConfirmQ2Import: (Q2AnalysisResult, Boolean) -> Unit,
  onDismissMigrationDialog: () -> Unit,
  onResetAllSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  when (route) {
    SettingsRoute.MAIN ->
      SettingsScreen(
        appPreferences = appPreferences,
        dspSettings = dspSettings,
        storageBackend = storageBackend,
        managedSafFolders = managedSafFolders,
        scanState = scanState,
        activeSkinName = activeSkinName,
        onUpdatePreferences = onUpdatePreferences,
        onSelectMusicSource = onSelectMusicSource,
        onAddMusicFolder = onAddMusicFolder,
        onRetryAudioPermission = onRetryAudioPermission,
        onOpenAppPermissionSettings = onOpenAppPermissionSettings,
        onRescanLibrary = onRescanLibrary,
        onSetTrackTransitionMode = onSetTrackTransitionMode,
        onSetCrossfadeDurationSec = onSetCrossfadeDurationSec,
        onSetReplayGainMode = onSetReplayGainMode,
        onSetReplayGainPreampDb = onSetReplayGainPreampDb,
        onNavigate = onNavigate,
        onBack = onBack,
        showBackButton = true,
        userPresetCount = userPresetCount,
        autoEqRuleCount = autoEqRuleCount,
        modifier = modifier,
      )

    SettingsRoute.SKINS ->
      SkinBrowserScreen(
        availableSkins = availableSkins,
        activeSkinId = activeSkinId,
        skinSettings = skinSettings,
        onSelectSkin = onSelectSkin,
        onImportSkinClick = onImportSkinClick,
        onDeleteImportedSkin = onDeleteImportedSkin,
        onSetLegacyRenderMode = onSetLegacyRenderMode,
        onSetAutoContrastGuard = onSetAutoContrastGuard,
        onBack = onBack,
        modifier = modifier,
      )

    SettingsRoute.MANAGE_FOLDERS ->
      ManageFoldersScreen(
        folders = managedSafFolders,
        onAddFolder = onAddMusicFolder,
        onRegrantFolder = onRegrantFolder,
        onRemoveFolder = onRemoveFolder,
        onBack = onBack,
        modifier = modifier,
      )

    SettingsRoute.EQ_PRESETS ->
      EqPresetsSettingsScreen(
        activePresetName = activeEqPresetName,
        userPresets = userEqPresets,
        autoEqRules = autoEqRules,
        currentTrack = currentTrack,
        pendingMigrationDialog = pendingMigrationDialog,
        onApplyPreset = onApplyEqPreset,
        onResetFlat = onResetEqFlat,
        onImportEqfClick = onImportEqfClick,
        onExportEqfClick = onExportEqfClick,
        onSaveAutoLoadForTrack = onSaveAutoEqForTrack,
        onSaveAutoLoadForFolder = onSaveAutoEqForFolder,
        onDeleteUserPreset = onDeleteUserEqPreset,
        onDeleteAutoRule = onDeleteUserAutoEqRule,
        onImportQ1Click = onImportQ1Click,
        onExportQ1Click = onExportQ1Click,
        onImportQ2Click = onImportQ2Click,
        onExportQ2Click = onExportQ2Click,
        onConfirmIniImport = onConfirmIniImport,
        onConfirmQ1Import = onConfirmQ1Import,
        onConfirmQ2Import = onConfirmQ2Import,
        onDismissMigrationDialog = onDismissMigrationDialog,
        onBack = onBack,
        modifier = modifier,
      )

    SettingsRoute.WINAMP_MIGRATION ->
      WinampMigrationScreen(
        userPresetCount = userPresetCount,
        autoEqRuleCount = autoEqRuleCount,
        pendingDialog = pendingMigrationDialog,
        onImportIniClick = onImportIniClick,
        onExportIniClick = onExportIniClick,
        onImportQ1Click = onImportQ1Click,
        onExportQ1Click = onExportQ1Click,
        onImportQ2Click = onImportQ2Click,
        onExportQ2Click = onExportQ2Click,
        onConfirmIniImport = onConfirmIniImport,
        onConfirmQ1Import = onConfirmQ1Import,
        onConfirmQ2Import = onConfirmQ2Import,
        onDismissMigrationDialog = onDismissMigrationDialog,
        onResetAllSettings = onResetAllSettings,
        onBack = onBack,
        modifier = modifier,
      )

    SettingsRoute.ABOUT ->
      AboutDroidAmpScreen(
        onBack = onBack,
        modifier = modifier,
      )
  }
}
