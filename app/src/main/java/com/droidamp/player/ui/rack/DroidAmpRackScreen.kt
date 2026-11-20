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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidamp.player.domain.model.RestingLayoutVariant
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.deck.MainDeckPanel
import com.droidamp.player.ui.eq.EqualizerPanel
import com.droidamp.player.ui.sheet.ExpandedPlaylistLibrarySheet
import com.droidamp.player.ui.sheet.PeekingDrawerSurface
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
  val isOptionA = uiState.restingLayoutVariant == RestingLayoutVariant.OPTION_A_SLIM_LIP

  // ADR-020: Android Back steps up inside LIBRARY before collapsing the drawer
  BackHandler(enabled = uiState.isSheetExpanded) {
    viewModel.onBackPressedInExpandedSheet()
  }

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxSize()
        .background(skin.chassis.windowBackground)
        .statusBarsPadding(),
  ) {
    val fullHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
    var instrumentHeightPx by remember { mutableFloatStateOf(fullHeightPx * 0.65f) }
    val minSlimLipPx = with(density) { 108.dp.toPx() }
    val minTabPeekPx = with(density) { 176.dp.toPx() }

    val collapsedTopPx =
      if (isOptionA) {
        maxOf(instrumentHeightPx, fullHeightPx - minSlimLipPx).coerceIn(0f, fullHeightPx - minSlimLipPx)
      } else {
        instrumentHeightPx.coerceIn(0f, (fullHeightPx - minTabPeekPx).coerceAtLeast(0f))
      }

    val sheetOffsetY = remember { Animatable(collapsedTopPx) }

    LaunchedEffect(uiState.isSheetExpanded, collapsedTopPx) {
      val target = if (uiState.isSheetExpanded) 0f else collapsedTopPx
      sheetOffsetY.animateTo(
        targetValue = target,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
      )
    }

    val onDrawerDragDelta: (Float) -> Unit = { delta ->
      scope.launch {
        val next = (sheetOffsetY.value + delta).coerceIn(0f, collapsedTopPx)
        sheetOffsetY.snapTo(next)
      }
    }

    val onDrawerDragFinished: (Float) -> Unit = { totalDrag ->
      val progress =
        if (collapsedTopPx > 1f) 1f - (sheetOffsetY.value / collapsedTopPx) else 1f
      val shouldExpand =
        if (uiState.isSheetExpanded) {
          // Collapse if dragged down > 18% of travel or > 96px
          !(progress < 0.82f || totalDrag > 96f)
        } else {
          // Expand if dragged up > 18% of travel or < -72px
          progress > 0.18f || totalDrag < -72f
        }
      if (shouldExpand) {
        viewModel.expandSheetRememberedMode()
        scope.launch {
          sheetOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        }
      } else {
        viewModel.collapseSheet()
        scope.launch {
          sheetOffsetY.animateTo(collapsedTopPx, spring(stiffness = Spring.StiffnessMediumLow))
        }
      }
    }

    // Background Plane: The Instrument (Main Deck + Collapsible 10-Band EQ, open by default)
    Column(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
              val h = coords.size.height.toFloat()
              if (h > 0f) instrumentHeightPx = h
            },
      ) {
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
          spaciousMode = isOptionA,
          layoutVariantLabel = if (isOptionA) "UI:A" else "UI:B",
          onToggleLayoutVariant = viewModel::toggleRestingLayoutVariant,
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
            sliderBankHeight = if (isOptionA) 152.dp else 132.dp,
          )
        }
      }
      Spacer(modifier = Modifier.weight(1f))
    }

    // Foreground Plane: 1:1 Finger-Tracking Drawer (ADR-018)
    val expansionFraction =
      if (collapsedTopPx > 1f) (1f - (sheetOffsetY.value / collapsedTopPx)).coerceIn(0f, 1f) else 1f
    val showExpandedContent = uiState.isSheetExpanded || expansionFraction > 0.12f

    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .offset { IntOffset(0, sheetOffsetY.value.roundToInt()) },
    ) {
      if (showExpandedContent) {
        ExpandedPlaylistLibrarySheet(
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
          onCollapse = viewModel::collapseSheet,
          onDragDelta = onDrawerDragDelta,
          onDragFinished = onDrawerDragFinished,
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
          onSavePlaylist = {
            viewModel.savePlaylistAsM3u8(
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
      } else {
        val peekHeightDp = with(density) { (fullHeightPx - collapsedTopPx).coerceAtLeast(minSlimLipPx).toDp() }
        PeekingDrawerSurface(
          layoutVariant = uiState.restingLayoutVariant,
          sheetMode = uiState.sheetMode,
          libraryViewLevel = uiState.libraryViewLevel,
          folderContents = uiState.folderContents,
          playlists = uiState.playlists,
          hasLinkedRoots = hasLinkedRoots,
          playbackState = playbackState,
          onExpand = viewModel::expandSheetRememberedMode,
          onSwitchSheetMode = viewModel::switchSheetMode,
          onSelectMusicFolder = triggerSelectMusicFolder,
          onDragDelta = onDrawerDragDelta,
          onDragFinished = onDrawerDragFinished,
          modifier = Modifier.fillMaxWidth().height(peekHeightDp),
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
