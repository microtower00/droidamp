package com.droidamp.player.ui.rack

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidamp.player.domain.contract.PlaybackRecoveryNotice
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.deck.MainDeckPanel
import com.droidamp.player.ui.eq.EqualizerPanel
import com.droidamp.player.ui.settings.SettingsHostScreen
import com.droidamp.player.ui.sheet.SavePlaylistDialog
import com.droidamp.player.ui.sheet.UnifiedQueueLibraryPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DroidAmpRackScreen(
  modifier: Modifier = Modifier,
  previewHolder: PreviewDroidAmpViewModel = viewModel(),
) {
  DroidAmpRackScreen(
    viewModel = previewHolder.delegate,
    modifier = modifier,
  )
}

@Composable
fun DroidAmpRackScreen(
  viewModel: DroidAmpViewModel,
  modifier: Modifier = Modifier,
  onRequestSafFolderPicker: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val density = LocalDensity.current
  val hasActivityResultOwner = LocalActivityResultRegistryOwner.current != null
  val scope = rememberCoroutineScope()
  val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
  val visualizerFrameState = viewModel.visualizerFrame.collectAsStateWithLifecycle()
  val linkedRoots by viewModel.linkedRoots.collectAsStateWithLifecycle()
  val managedSafFolders by viewModel.managedSafFolders.collectAsStateWithLifecycle()
  val libraryScanState by viewModel.libraryScanState.collectAsStateWithLifecycle()
  val storageBackend by viewModel.storageBackend.collectAsStateWithLifecycle()
  val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
  val dspSettings by viewModel.dspSettings.collectAsStateWithLifecycle()
  val availableSkins by viewModel.availableSkins.collectAsStateWithLifecycle()
  val activeSkin by viewModel.activeSkin.collectAsStateWithLifecycle()
  val skinSettings by viewModel.skinSettings.collectAsStateWithLifecycle()
  val skinRecoveryNotice by viewModel.skinRecoveryNotice.collectAsStateWithLifecycle()
  val playbackRecoveryNotice by viewModel.playbackRecoveryNotice.collectAsStateWithLifecycle()
  val userEqPresets by viewModel.userEqPresets.collectAsStateWithLifecycle()
  val userAutoEqRules by viewModel.userAutoEqRules.collectAsStateWithLifecycle()
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  var pendingM3u8Export by remember { mutableStateOf<Pair<String, String>?>(null) }
  var pendingBinaryExport by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
  var pendingImportSlot by remember { mutableStateOf(ImportSlot.SKIN_WSZ) }
  var pendingRegrantFolderUri by remember { mutableStateOf<String?>(null) }

  val importSkinLauncher =
    if (hasActivityResultOwner) {
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          viewModel.importSkinFromUri(uri.toString())
        }
      }
    } else {
      null
    }

  val importDocLauncher =
    if (hasActivityResultOwner) {
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val targetSlot = pendingImportSlot
        if (uri != null) {
          scope.launch(Dispatchers.IO) {
            val bytes =
              runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
              }.getOrNull()
            val resolvedName =
              runCatching {
                var display: String? = null
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                  ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                      val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                      if (idx >= 0) display = cursor.getString(idx)
                    }
                  }
                display?.takeIf { it.isNotBlank() }
              }.getOrNull()
                ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "imported_file" }
                ?: "imported_file"
            withContext(Dispatchers.Main) {
              if (bytes != null) {
                viewModel.importFileForSlot(
                  slot = targetSlot,
                  fileName = resolvedName,
                  rawBytes = bytes,
                  uriString = uri.toString(),
                )
              }
            }
          }
        }
      }
    } else {
      null
    }

  val exportBinaryDocLauncher =
    if (hasActivityResultOwner) {
      rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val pending = pendingBinaryExport
        pendingBinaryExport = null
        if (uri != null && pending != null) {
          val (_, bytes) = pending
          scope.launch(Dispatchers.IO) {
            runCatching {
              context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
              }
            }
          }
        }
      }
    } else {
      null
    }

  val safPickerLauncher =
    if (hasActivityResultOwner) {
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val regrantTarget = pendingRegrantFolderUri
        pendingRegrantFolderUri = null
        if (uri != null) {
          val rwFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
          val tookRw =
            runCatching {
              context.contentResolver.takePersistableUriPermission(uri, rwFlags)
            }.isSuccess
          if (!tookRw) {
            runCatching {
              context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
              )
            }
          }
          if (regrantTarget != null) {
            viewModel.regrantSafRoot(regrantTarget, uri.toString())
          } else {
            viewModel.onSelectMusicFolder(uri.toString())
          }
        }
      }
    } else {
      null
    }

  val mediaStorePermissionLauncher =
    if (hasActivityResultOwner) {
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onStoragePermissionResult(granted)
      }
    } else {
      null
    }

  val createM3u8Launcher =
    if (hasActivityResultOwner) {
      rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        val pending = pendingM3u8Export
        pendingM3u8Export = null
        if (uri == null || pending == null) {
          viewModel.onM3u8FallbackExportResult(
            success = false,
            displayLabel = pending?.first ?: DroidAmpViewModel.DEFAULT_SAVED_PLAYLIST_NAME,
          )
          return@rememberLauncherForActivityResult
        }
        val (fileName, content) = pending
        scope.launch(Dispatchers.IO) {
          val success =
            runCatching {
              context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
              } ?: error("Unable to open output stream")
            }.isSuccess
          withContext(Dispatchers.Main) {
            viewModel.onM3u8FallbackExportResult(success = success, displayLabel = fileName)
          }
        }
      }
    } else {
      null
    }

  val triggerAddSafFolder: () -> Unit = {
    pendingRegrantFolderUri = null
    if (onRequestSafFolderPicker != null) {
      onRequestSafFolderPicker()
    } else if (hasActivityResultOwner && safPickerLauncher != null) {
      safPickerLauncher.launch(null)
    } else {
      viewModel.onSelectMusicFolder()
    }
  }

  val triggerRequestMediaStorePermission: () -> Unit = {
    if (hasActivityResultOwner) {
      val permission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          Manifest.permission.READ_MEDIA_AUDIO
        } else {
          Manifest.permission.READ_EXTERNAL_STORAGE
        }
      val granted =
        ContextCompat.checkSelfPermission(context, permission) ==
          PackageManager.PERMISSION_GRANTED
      if (granted) {
        viewModel.onStoragePermissionResult(true)
      } else {
        mediaStorePermissionLauncher?.launch(permission)
      }
    }
  }

  val triggerSelectMusicFolder: () -> Unit = {
    when {
      onRequestSafFolderPicker != null -> onRequestSafFolderPicker()
      storageBackend == StorageBackend.MEDIA_STORE && hasActivityResultOwner ->
        triggerRequestMediaStorePermission()
      else -> triggerAddSafFolder()
    }
  }

  val hasLinkedRoots = linkedRoots.isNotEmpty()

  val renderSettingsHost: @Composable () -> Unit = {
    SettingsHostScreen(
      route = uiState.settingsRoute,
      appPreferences = appPreferences,
      dspSettings = dspSettings,
      storageBackend = storageBackend,
      managedSafFolders = managedSafFolders,
      scanState = libraryScanState,
      availableSkins = availableSkins,
      activeSkinId = activeSkin.id,
      activeSkinName = availableSkins.firstOrNull { it.id == activeSkin.id }?.displayName ?: activeSkin.displayName,
      skinSettings = skinSettings,
      userPresetCount = userEqPresets.size,
      autoEqRuleCount = userAutoEqRules.size,
      activeEqPresetName = playbackState.equalizer.activePresetName,
      userEqPresets = userEqPresets,
      autoEqRules = userAutoEqRules,
      currentTrack = playbackState.currentTrack,
      pendingMigrationDialog = uiState.pendingMigrationDialog,
      onNavigate = viewModel::navigateSettings,
      onBack = viewModel::navigateBackInSettings,
      onUpdatePreferences = viewModel::updateAppPreferences,
      onSelectMusicSource = viewModel::selectMusicSource,
      onAddMusicFolder = triggerAddSafFolder,
      onRegrantFolder = { folder ->
        pendingRegrantFolderUri = folder.uriString
        if (hasActivityResultOwner && safPickerLauncher != null) {
          safPickerLauncher.launch(null)
        } else {
          viewModel.regrantSafRoot(folder.uriString, folder.uriString)
        }
      },
      onRemoveFolder = { folder -> viewModel.removeSafRoot(folder.uriString) },
      onRetryAudioPermission = triggerRequestMediaStorePermission,
      onOpenAppPermissionSettings = {
        runCatching {
          val intent =
            Intent(
              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          context.startActivity(intent)
        }
      },
      onRescanLibrary = viewModel::rescanMusicLibrary,
      onSetTrackTransitionMode = viewModel::setTrackTransitionMode,
      onSetCrossfadeDurationSec = viewModel::setCrossfadeDurationSec,
      onSetReplayGainMode = viewModel::setReplayGainMode,
      onSetReplayGainPreampDb = viewModel::setReplayGainPreampDb,
      onApplyEqPreset = viewModel::applyEqPreset,
      onResetEqFlat = viewModel::resetEqToFlat,
      onImportEqfClick = {
        pendingImportSlot = ImportSlot.EQ_PRESET_EQF
        importDocLauncher?.launch(arrayOf("*/*"))
      },
      onExportEqfClick = {
        val baseName =
          playbackState.equalizer.activePresetName
            .ifBlank { "droidamp_eq" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val suggested = "$baseName.eqf"
        pendingBinaryExport = suggested to viewModel.encodeCurrentEqAsEqfBytes()
        exportBinaryDocLauncher?.launch(suggested)
      },
      onSaveAutoEqForTrack = viewModel::saveAutoEqRuleForTrack,
      onSaveAutoEqForFolder = viewModel::saveAutoEqRuleForFolder,
      onDeleteUserEqPreset = viewModel::deleteUserEqPreset,
      onDeleteUserAutoEqRule = viewModel::deleteUserAutoEqRule,
      onSelectSkin = viewModel::selectSkin,
      onImportSkinClick = {
        pendingImportSlot = ImportSlot.SKIN_WSZ
        importSkinLauncher?.launch(arrayOf("*/*"))
      },
      onDeleteImportedSkin = viewModel::deleteImportedSkin,
      onSetLegacyRenderMode = viewModel::setLegacySkinRenderMode,
      onSetAutoContrastGuard = viewModel::setAutoContrastGuard,
      onImportIniClick = {
        pendingImportSlot = ImportSlot.MIGRATION_INI
        importDocLauncher?.launch(arrayOf("*/*"))
      },
      onExportIniClick = {
        val fileName = "winamp.ini"
        pendingBinaryExport =
          fileName to viewModel.exportWinampIniText().toByteArray(Charsets.UTF_8)
        exportBinaryDocLauncher?.launch(fileName)
      },
      onImportQ1Click = {
        pendingImportSlot = ImportSlot.MIGRATION_Q1
        importDocLauncher?.launch(arrayOf("*/*"))
      },
      onExportQ1Click = {
        val fileName = "winamp.q1"
        pendingBinaryExport = fileName to viewModel.exportWinampQ1Bytes()
        exportBinaryDocLauncher?.launch(fileName)
      },
      onImportQ2Click = {
        pendingImportSlot = ImportSlot.MIGRATION_Q2
        importDocLauncher?.launch(arrayOf("*/*"))
      },
      onExportQ2Click = {
        val fileName = "winamp.q2"
        pendingBinaryExport = fileName to viewModel.exportWinampQ2Bytes()
        exportBinaryDocLauncher?.launch(fileName)
      },
      onConfirmIniImport = viewModel::confirmIniMigration,
      onConfirmQ1Import = viewModel::confirmQ1Migration,
      onConfirmQ2Import = viewModel::confirmQ2Migration,
      onDismissMigrationDialog = viewModel::dismissMigrationDialog,
      onResetAllSettings = viewModel::resetAllSettingsToDefaults,
    )
  }

  BackHandler(enabled = uiState.isSettingsOpen || uiState.isSheetExpanded) {
    when {
      uiState.isSettingsOpen -> viewModel.navigateBackInSettings()
      else -> viewModel.onBackPressedInExpandedSheet()
    }
  }

  val isDarkSystemTheme = isSystemInDarkTheme()
  val statusBarTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

  CompositionLocalProvider(LocalDroidAmpSkin provides activeSkin) {
    val currentSkin = LocalDroidAmpSkin.current
    BoxWithConstraints(
      modifier =
        modifier
          .fillMaxSize()
          .background(currentSkin.chassis.windowBackground),
    ) {
      val fullHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
      val statusBarTopPx = with(density) { statusBarTopDp.toPx() }
      val minBrowserHeightPx = with(density) { 180.dp.toPx() }
      var instrumentHeightPx by remember { mutableFloatStateOf((fullHeightPx - minBrowserHeightPx).coerceAtLeast(1f)) }

      // Maximum vertical pan distance leaves the expanded browser titlebar cleanly below the status bar
      val maxPanPx =
        (instrumentHeightPx - statusBarTopPx).coerceIn(1f, (fullHeightPx - minBrowserHeightPx).coerceAtLeast(1f))

      val haptics = LocalHapticFeedback.current
      val settingsBumpPx = with(density) { 160.dp.toPx() }
      var settingsPullRawPx by remember { mutableFloatStateOf(0f) }

      LaunchedEffect(uiState.isSettingsOpen) {
        if (!uiState.isSettingsOpen) {
          settingsPullRawPx = 0f
        }
      }

      val isPullingPastSettingsBump = uiState.isSettingsOpen && settingsPullRawPx >= settingsBumpPx
      LaunchedEffect(isPullingPastSettingsBump) {
        if (isPullingPastSettingsBump && appPreferences.hapticFeedbackEnabled) {
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
      }

      // Stereo-Tower Camera Pan offset:
      // 0f        = Resting (Main Deck + EQ + live Queue/Library below)
      // maxPanPx  = Expanded (Main Deck + EQ smoothly panned up behind status bar scrim)
      val panOffsetPx = remember { Animatable(0f) }

      LaunchedEffect(uiState.isSheetExpanded, maxPanPx) {
        val target = if (uiState.isSheetExpanded) maxPanPx else 0f
        panOffsetPx.animateTo(
          targetValue = target,
          animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        )
      }

      // Drag delta is screen-space Y (negative = dragging finger UP -> increases panOffsetPx)
      val onStereoPanDelta: (Float) -> Unit = { dragDeltaY ->
        scope.launch {
          val effectiveDelta =
            if (uiState.isSettingsOpen) {
              val prevRaw = settingsPullRawPx
              val nextRaw = (prevRaw + dragDeltaY).coerceAtLeast(0f)
              settingsPullRawPx = nextRaw
              if (nextRaw < settingsBumpPx && prevRaw < settingsBumpPx) {
                dragDeltaY * 0.22f
              } else {
                dragDeltaY
              }
            } else {
              dragDeltaY
            }
          val next = (panOffsetPx.value - effectiveDelta).coerceIn(0f, maxPanPx)
          panOffsetPx.snapTo(next)
        }
      }

      val onStereoPanFinished: (Float) -> Unit = { totalDragY ->
        val progress = (panOffsetPx.value / maxPanPx).coerceIn(0f, 1f)
        val shouldExpand =
          if (uiState.isSettingsOpen) {
            val crossedBump = settingsPullRawPx >= settingsBumpPx || totalDragY >= settingsBumpPx
            settingsPullRawPx = 0f
            !crossedBump
          } else if (uiState.isSheetExpanded) {
            !(progress < 0.82f || totalDragY > 80f)
          } else {
            progress > 0.18f || totalDragY < -64f
          }
        if (shouldExpand) {
          viewModel.expandSheetRememberedMode()
          scope.launch {
            panOffsetPx.animateTo(maxPanPx, spring(stiffness = Spring.StiffnessMediumLow))
          }
        } else {
          viewModel.collapseSheet()
          scope.launch {
            panOffsetPx.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
          }
        }
      }

      val currentPan = panOffsetPx.value.coerceIn(0f, maxPanPx)
      val browserTopPx = (instrumentHeightPx - currentPan).coerceAtLeast(statusBarTopPx)
      val browserHeightDp = with(density) { (fullHeightPx - browserTopPx).coerceAtLeast(minBrowserHeightPx).toDp() }

      // Top Stereo Section: status-bar spacer + Main Deck + Collapsible EQ + non-interactive swipe buffer
      // Translates smoothly UP behind the adaptive status-bar scrim as the user pans (ADR-023 / ADR-024)
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, (-currentPan).roundToInt()) }
            .onGloballyPositioned { coords ->
              val h = coords.size.height.toFloat()
              if (h > 0f) instrumentHeightPx = h
            },
      ) {
        Box(modifier = Modifier.fillMaxWidth().statusBarsPadding())
        MainDeckPanel(
          playbackState = playbackState,
          visualizerFrameProvider = { visualizerFrameState.value },
          isEqVisible = uiState.isEqVisible,
          isSheetExpanded = uiState.isSheetExpanded,
          onToggleTimeMode = viewModel::toggleTimeDisplayMode,
          onCycleVisualizerMode = viewModel::cycleVisualizerMode,
          onVolumeChange = viewModel::setVolume,
          onBalanceChange = viewModel::setBalance,
          onSeekTo = viewModel::seekTo,
          onPrev = viewModel::previous,
          onPlay = viewModel::play,
          onPause = viewModel::pause,
          onStop = viewModel::stop,
          onNext = viewModel::next,
          onEject = viewModel::onEjectPressed,
          onToggleShuffle = viewModel::toggleShuffle,
          onCycleRepeat = viewModel::cycleRepeatMode,
          onToggleEqVisible = viewModel::toggleEqVisible,
          onTogglePlaylistSheet = viewModel::onQueueButtonPressed,
        )

        AnimatedVisibility(
          visible = uiState.isEqVisible,
          enter = expandVertically(),
          exit = shrinkVertically(),
        ) {
          val userPresetNames =
            remember(userEqPresets) {
              userEqPresets.map { it.name.trim().lowercase(Locale.ROOT) }.toSet()
            }
          EqualizerPanel(
            eqState = playbackState.equalizer,
            onToggleEnabled = viewModel::toggleEqEnabled,
            onToggleAuto = viewModel::toggleEqAuto,
            onPreampChange = viewModel::setPreampDb,
            onBandChange = viewModel::setEqBandDb,
            onApplyPreset = viewModel::applyEqPreset,
            availablePresets = uiState.availableEqPresets,
            userPresetNames = userPresetNames,
            autoEqStatusReadout = uiState.autoEqFeedbackMessage,
            sliderBankHeight = 132.dp,
            onCheckPresetConflict = viewModel::checkEqPresetConflict,
            onSavePreset = viewModel::saveCurrentEqCurveAsPreset,
            onResetFlat = viewModel::resetEqToFlat,
            onManagePresetsClick = viewModel::openEqPresetsSettings,
          )
        }

        // Non-interactive chassis bezel buffer separating EQ bottom from Queue/Library titlebar
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(currentSkin.metrics.sheetSwipeBufferHeight)
              .background(currentSkin.chassis.panelSurfaceAlt),
        )
      }

      // Bottom Stereo Section: Always-live, always-interactive Queue / Library Panel
      // Docked directly beneath the bezel buffer and grows seamlessly to full height as the tower pans up
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(browserHeightDp)
            .offset { IntOffset(0, browserTopPx.roundToInt()) },
      ) {
        UnifiedQueueLibraryPanel(
          isExpanded = uiState.isSheetExpanded,
          sheetMode = uiState.sheetMode,
          hasLinkedRoots = hasLinkedRoots,
          playbackState = playbackState,
          selectedPlaylistIndices = uiState.selectedPlaylistIndices,
          showJumpFilter = uiState.showJumpFilter,
          jumpFilterQuery = uiState.jumpFilterQuery,
          activeMicroMenu = uiState.activeMicroMenu,
          showClearQueueConfirm = uiState.showClearQueueConfirm,
          libraryViewLevel = uiState.libraryViewLevel,
          folderContents = uiState.folderContents,
          playlists = uiState.playlists,
          selectedLibraryTrackIds = uiState.selectedLibraryTrackIds,
          selectedPlaylistId = uiState.selectedLibraryPlaylistId,
          libraryFeedbackBanner = uiState.libraryFeedbackBanner,
          queueFeedbackPulseCount = uiState.queueFeedbackPulseCount,
          onToggleExpand = viewModel::toggleSheetExpanded,
          onDragDelta = onStereoPanDelta,
          onDragFinished = onStereoPanFinished,
          canPullDownToCollapse = { panOffsetPx.value > 0.5f },
          isMidPan = { panOffsetPx.value > 0.5f && panOffsetPx.value < maxPanPx - 0.5f },
          onSelectMusicFolder = triggerSelectMusicFolder,
          onSwitchSheetMode = viewModel::switchSheetMode,
          onPlaylistTrackClick = viewModel::onPlaylistTrackClick,
          onPlaylistTrackDoubleClick = viewModel::onPlaylistTrackDoubleClick,
          onMoveTrackUp = { idx -> viewModel.movePlaylistTracksStep(idx, -1) },
          onMoveTrackDown = { idx -> viewModel.movePlaylistTracksStep(idx, 1) },
          onJumpFilterChange = viewModel::onJumpFilterQueryChange,
          onToggleJumpFilter = viewModel::toggleJumpFilter,
          onOpenMicroMenu = viewModel::openMicroMenu,
          onDismissMicroMenu = viewModel::dismissMicroMenu,
          onRemoveSelected = viewModel::removeSelectedPlaylistTracks,
          onCropSelected = viewModel::cropSelectedPlaylistTracks,
          onRequestClearQueue = viewModel::requestClearQueue,
          onConfirmClearQueue = viewModel::confirmClearQueue,
          onDismissClearQueueConfirm = viewModel::dismissClearQueueConfirm,
          onSortQueue = viewModel::sortPlaylist,
          onReverseQueue = viewModel::reversePlaylist,
          onRandomizeQueue = viewModel::randomizePlaylist,
          onNewQueue = viewModel::requestClearQueue,
          onSavePlaylist = viewModel::openSavePlaylistDialog,
          onLoadPlaylist = viewModel::onLoadPlaylistShortcut,
          onOpenLibraryRoot = viewModel::openLibraryRoot,
          onOpenLibraryFolders = viewModel::openLibraryFolders,
          onOpenLibraryPlaylists = viewModel::openLibraryPlaylists,
          onNavigateUp = viewModel::navigateFolderUp,
          onOpenFolder = viewModel::openFolder,
          onPlayFolder = viewModel::playFolderAsPlaylist,
          onEnqueueFolder = viewModel::enqueueFolder,
          onPlayCurrentFolder = viewModel::playCurrentFolder,
          onEnqueueCurrentFolder = viewModel::enqueueCurrentFolder,
          onToggleLibraryTrackSelection = viewModel::toggleLibraryTrackSelection,
          onPlayLibraryTrack = viewModel::playSingleLibraryTrack,
          onEnqueueLibraryTrack = viewModel::enqueueSingleLibraryTrack,
          onSelectLibraryPlaylist = viewModel::selectLibraryPlaylist,
          onPlayLibraryPlaylist = viewModel::playSavedPlaylist,
          onEnqueueLibraryPlaylist = viewModel::enqueueSavedPlaylist,
          onPlayPrimaryLibrarySelection = viewModel::playPrimaryLibrarySelection,
          onEnqueuePrimaryLibrarySelection = viewModel::enqueuePrimaryLibrarySelection,
          onClearLibrarySelection = viewModel::clearLibrarySelection,
          onPrev = viewModel::previous,
          onPlay = viewModel::play,
          onPause = viewModel::pause,
          onStop = viewModel::stop,
          onNext = viewModel::next,
          onMoveTrack = viewModel::movePlaylistTrack,
          onMoveTracksStep = viewModel::movePlaylistTracksStep,
          queueTapAction = appPreferences.queueTapAction,
          hapticFeedbackEnabled = appPreferences.hapticFeedbackEnabled,
          isSettingsOpen = uiState.isSettingsOpen,
          isExitingSettingsArmed = isPullingPastSettingsBump,
          onOpenSettings = viewModel::openSettings,
          settingsContent = { renderSettingsHost() },
        )
      }

      // Adaptive light/dark translucent scrim over the Android status bar so EQ/Deck gliding
      // behind the top bar preserves crystal-clear contrast for system icons (ADR-024).
      val scrimBase = if (isDarkSystemTheme) Color.Black else Color.White
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(statusBarTopDp + 16.dp)
            .align(Alignment.TopCenter)
            .background(
              Brush.verticalGradient(
                colorStops =
                  arrayOf(
                    0.0f to scrimBase.copy(alpha = 0.84f),
                    0.62f to scrimBase.copy(alpha = 0.52f),
                    1.0f to Color.Transparent,
                  ),
              ),
            ),
      )

      if (uiState.showSavePlaylistDialog) {
        SavePlaylistDialog(
          totalTrackCount = playbackState.queue.size,
          totalDurationMs = playbackState.totalQueueDurationMs,
          selectedTrackCount = uiState.selectedPlaylistIndices.size,
          existingPlaylistNames = uiState.playlists.map { it.name }.toSet(),
          onDismiss = viewModel::dismissSavePlaylistDialog,
          onConfirmSave = { baseName, format, selectedOnly ->
            viewModel.saveQueueAsPlaylist(
              baseName = baseName,
              format = format,
              selectedOnly = selectedOnly,
              onNeedDocumentPickerFallback =
                if (hasActivityResultOwner && createM3u8Launcher != null) {
                  { fileName, content ->
                    pendingM3u8Export = fileName to content
                    createM3u8Launcher.launch(fileName)
                  }
                } else {
                  null
                },
            )
          },
        )
      }

      val activeNotice = skinRecoveryNotice
      if (activeNotice != null) {
        SkinRecoveryDialog(
          notice = activeNotice,
          onDismiss = viewModel::dismissSkinRecoveryNotice,
          onRetrySafeBootSkin = viewModel::retrySafeBootSkin,
          onRevertPartialSkin = viewModel::revertPartialSkin,
          onUseRawOgColors = viewModel::useRawOgColorsForSkin,
          onResolveDuplicate = viewModel::resolveDuplicateSkinImport,
          onConfirmWrongSlotRedirect = viewModel::confirmWrongSlotRedirect,
        )
      }

      val activePlaybackNotice = playbackRecoveryNotice
      if (activePlaybackNotice is PlaybackRecoveryNotice.MissingTrackSkipped) {
        PlaybackRecoveryDialog(
          notice = activePlaybackNotice,
          onRemoveMissingTrack = viewModel::removeMissingTrack,
          onDismiss = viewModel::dismissPlaybackRecoveryNotice,
        )
      }
    }
  }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun DroidAmpRackPreview() {
  CompositionLocalProvider(LocalDroidAmpSkin provides ClassicDroidAmpSkin) {
    val holder = PreviewDroidAmpViewModel(FakeMusicSourceRepository(startLinked = false))
    DroidAmpRackScreen(viewModel = holder.delegate)
  }
}
