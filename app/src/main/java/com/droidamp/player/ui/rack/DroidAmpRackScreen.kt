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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.deck.MainDeckPanel
import com.droidamp.player.ui.eq.EqualizerPanel
import com.droidamp.player.ui.sheet.ExpandedPlaylistLibrarySheet
import com.droidamp.player.ui.sheet.PeekingPlaylistSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

  BackHandler(enabled = uiState.isSheetExpanded) {
    viewModel.collapseSheet()
  }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(skin.chassis.windowBackground)
        .statusBarsPadding(),
  ) {
    // Resting State: Modular Hi-Fi Rack (Main Deck + Collapsible 10-Band EQ + Peeking Sheet)
    Column(modifier = Modifier.fillMaxSize()) {
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
        onTogglePlaylistSheet = viewModel::toggleSheetExpanded,
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
        )
      }

      PeekingPlaylistSheet(
        sheetMode = uiState.sheetMode,
        hasLinkedRoots = hasLinkedRoots,
        playbackState = playbackState,
        onExpand = viewModel::expandSheetRememberedMode,
        onSelectMusicFolder = triggerSelectMusicFolder,
        modifier = Modifier.fillMaxWidth().weight(1f),
      )
    }

    // Expanded State: Full-Screen Playlist / Library Sheet covering both Deck & EQ (ADR-004)
    AnimatedVisibility(
      visible = uiState.isSheetExpanded,
      enter =
        slideInVertically(
          animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
          initialOffsetY = { it },
        ),
      exit =
        slideOutVertically(
          animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
          targetOffsetY = { it },
        ),
      modifier = Modifier.fillMaxSize(),
    ) {
      ExpandedPlaylistLibrarySheet(
        sheetMode = uiState.sheetMode,
        hasLinkedRoots = hasLinkedRoots,
        playbackState = playbackState,
        selectedPlaylistIndices = uiState.selectedPlaylistIndices,
        showJumpFilter = uiState.showJumpFilter,
        jumpFilterQuery = uiState.jumpFilterQuery,
        activeMicroMenu = uiState.activeMicroMenu,
        statusBanner = uiState.statusBanner,
        libraryTab = uiState.libraryTab,
        folderContents = uiState.folderContents,
        playlists = uiState.playlists,
        selectedLibraryTrackIds = uiState.selectedLibraryTrackIds,
        selectedPlaylistId = uiState.selectedLibraryPlaylistId,
        onCollapse = viewModel::collapseSheet,
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
        onClearQueue = viewModel::clearPlaylist,
        onSortQueue = viewModel::sortPlaylist,
        onReverseQueue = viewModel::reversePlaylist,
        onRandomizeQueue = viewModel::randomizePlaylist,
        onNewPlaylist = viewModel::clearPlaylist,
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
        onSelectLibraryTab = viewModel::selectLibraryTab,
        onNavigateUp = viewModel::navigateFolderUp,
        onOpenFolder = viewModel::openFolder,
        onPlayFolder = viewModel::playFolderAsPlaylist,
        onEnqueueFolder = viewModel::enqueueFolder,
        onToggleLibraryTrackSelection = viewModel::toggleLibraryTrackSelection,
        onPlayLibraryTrack = viewModel::playSingleLibraryTrack,
        onEnqueueLibraryTrack = viewModel::enqueueSingleLibraryTrack,
        onSelectLibraryPlaylist = viewModel::selectLibraryPlaylist,
        onPlayLibraryPlaylist = viewModel::playSavedPlaylist,
        onEnqueueLibraryPlaylist = viewModel::enqueueSavedPlaylist,
        onPlayPrimaryLibrarySelection = viewModel::playPrimaryLibrarySelection,
        onEnqueuePrimaryLibrarySelection = viewModel::enqueuePrimaryLibrarySelection,
        onPrev = viewModel::previous,
        onPlay = viewModel::play,
        onPause = viewModel::pause,
        onStop = viewModel::stop,
        onNext = viewModel::next,
        onMoveTrack = viewModel::movePlaylistTrack,
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
