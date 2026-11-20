package com.droidamp.player.ui.rack

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidamp.player.skin.ClassicDroidAmpSkin
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.deck.MainDeckPanel
import com.droidamp.player.ui.eq.EqualizerPanel
import com.droidamp.player.ui.sheet.ExpandedPlaylistLibrarySheet
import com.droidamp.player.ui.sheet.PeekingPlaylistSheet
import androidx.compose.runtime.CompositionLocalProvider

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
) {
  val skin = LocalDroidAmpSkin.current
  val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
  val visualizerFrame by viewModel.visualizerFrame.collectAsStateWithLifecycle()
  val linkedRoots by viewModel.linkedRoots.collectAsStateWithLifecycle()
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
        visualizerFrame = visualizerFrame,
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
        )
      }

      PeekingPlaylistSheet(
        sheetMode = uiState.sheetMode,
        hasLinkedRoots = hasLinkedRoots,
        playbackState = playbackState,
        onExpand = viewModel::expandSheetRememberedMode,
        onSelectMusicFolder = { viewModel.onSelectMusicFolder() },
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
        onSelectMusicFolder = { viewModel.onSelectMusicFolder() },
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
        onSavePlaylist = viewModel::onSavePlaylistStub,
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
