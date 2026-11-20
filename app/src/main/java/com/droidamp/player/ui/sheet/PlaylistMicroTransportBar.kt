package com.droidamp.player.ui.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.PlaybackState
import com.droidamp.player.domain.model.PlaybackStatus
import com.droidamp.player.domain.model.formatWinampDuration
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.RecessedWell

/**
 * Compact Winamp bottom transport micro-bar (`|< ▶ || ■ >|` + elapsed/total time).
 * Duplicate `⏏` Eject button intentionally omitted per ADR-010.
 */
@Composable
fun PlaylistMicroTransportBar(
  playbackState: PlaybackState,
  onPrev: () -> Unit,
  onPlay: () -> Unit,
  onPause: () -> Unit,
  onStop: () -> Unit,
  onNext: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  val currentTime = formatWinampDuration(playbackState.positionMs)
  val totalTime = formatWinampDuration(playbackState.totalQueueDurationMs)

  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BeveledButton(
      label = "|<",
      onClick = onPrev,
      modifier = Modifier.weight(1f).height(28.dp),
      contentPadding = PaddingValues(0.dp),
    )
    BeveledButton(
      label = "▶",
      onClick = onPlay,
      highlighted = playbackState.status == PlaybackStatus.PLAYING,
      modifier = Modifier.weight(1f).height(28.dp),
      contentPadding = PaddingValues(0.dp),
    )
    BeveledButton(
      label = "||",
      onClick = onPause,
      highlighted = playbackState.status == PlaybackStatus.PAUSED,
      modifier = Modifier.weight(1f).height(28.dp),
      contentPadding = PaddingValues(0.dp),
    )
    BeveledButton(
      label = "■",
      onClick = onStop,
      modifier = Modifier.weight(1f).height(28.dp),
      contentPadding = PaddingValues(0.dp),
    )
    BeveledButton(
      label = ">|",
      onClick = onNext,
      modifier = Modifier.weight(1f).height(28.dp),
      contentPadding = PaddingValues(0.dp),
    )

    RecessedWell(
      modifier = Modifier.height(28.dp),
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
      Text(
        text = "$currentTime/$totalTime",
        style = skin.typography.vfdInfo,
        color = skin.vfd.phosphorPrimary,
        maxLines = 1,
        modifier = Modifier.align(Alignment.Center),
      )
    }
  }
}
