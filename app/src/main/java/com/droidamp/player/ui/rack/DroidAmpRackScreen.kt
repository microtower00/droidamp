package com.droidamp.player.ui.rack

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.deck.MainDeckPanel
import com.droidamp.player.ui.eq.EqualizerPanel
import com.droidamp.player.ui.sheet.SavePlaylistDialog
import com.droidamp.player.ui.sheet.UnifiedQueueLibraryPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
  val skin = LocalDroidAmpSkin.current
  val context = LocalContext.current
  val density = LocalDensity.current
  val hasActivityResultOwner = LocalActivityResultRegistryOwner.current != null
  val scope = rememberCoroutineScope()
  val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
  val visualizerFrameState = viewModel.visualizerFrame.collectAsStateWithLifecycle()
  val linkedRoots by viewModel.linkedRoots.collectAsStateWithLifecycle()
  val storageBackend by viewModel.storageBackend.collectAsStateWithLifecycle()
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  var pendingM3u8Export by remember { mutableStateOf<Pair<String, String>?>(null) }

  val safPickerLauncher =
    if (hasActivityResultOwner) {
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
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
          viewModel.onSelectMusicFolder(uri.toString())
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

  val triggerSelectMusicFolder: () -> Unit = {
    when {
      onRequestSafFolderPicker != null -> onRequestSafFolderPicker()
      storageBackend == StorageBackend.MEDIA_STORE && hasActivityResultOwner -> {
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
      hasActivityResultOwner && safPickerLauncher != null -> {
        safPickerLauncher.launch(null)
      }
      else -> {
        viewModel.onSelectMusicFolder()
      }
    }
  }

  val hasLinkedRoots = linkedRoots.isNotEmpty()

  // ADR-020: Android Back steps up inside LIBRARY before panning back to the Deck + EQ
  BackHandler(enabled = uiState.isSheetExpanded) {
    viewModel.onBackPressedInExpandedSheet()
  }

  val isDarkSystemTheme = isSystemInDarkTheme()
  val statusBarTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxSize()
        .background(skin.chassis.windowBackground),
  ) {
    val fullHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
    val statusBarTopPx = with(density) { statusBarTopDp.toPx() }
    val minBrowserHeightPx = with(density) { 180.dp.toPx() }
    var instrumentHeightPx by remember { mutableFloatStateOf((fullHeightPx - minBrowserHeightPx).coerceAtLeast(1f)) }

    // Maximum vertical pan distance leaves the expanded browser titlebar cleanly below the status bar
    val maxPanPx =
      (instrumentHeightPx - statusBarTopPx).coerceIn(1f, (fullHeightPx - minBrowserHeightPx).coerceAtLeast(1f))

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
        val next = (panOffsetPx.value - dragDeltaY).coerceIn(0f, maxPanPx)
        panOffsetPx.snapTo(next)
      }
    }

    val onStereoPanFinished: (Float) -> Unit = { totalDragY ->
      val progress = (panOffsetPx.value / maxPanPx).coerceIn(0f, 1f)
      val shouldExpand =
        if (uiState.isSheetExpanded) {
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
        spaciousMode = false,
      )

      AnimatedVisibility(
        visible = uiState.isEqVisible,
        enter = expandVertically(),
        exit = shrinkVertically(),
      ) {
        EqualizerPanel(
          eqState = playbackState.equalizer,
          onToggleEnabled = viewModel::toggleEqEnabled,
          onToggleAuto = viewModel::toggleEqAuto,
          onPreampChange = viewModel::setPreampDb,
          onBandChange = viewModel::setEqBandDb,
          onApplyPreset = viewModel::applyEqPreset,
          availablePresets = uiState.availableEqPresets,
          autoEqStatusReadout = uiState.autoEqFeedbackMessage,
          sliderBankHeight = 132.dp,
        )
      }

      // Non-interactive chassis bezel buffer separating EQ bottom from Queue/Library titlebar
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(skin.metrics.sheetSwipeBufferHeight)
            .background(skin.chassis.panelSurfaceAlt),
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
        onMoveTrackUp = { idx -> viewModel.movePlaylistTrack(idx, idx - 1) },
        onMoveTrackDown = { idx -> viewModel.movePlaylistTrack(idx, idx + 1) },
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
