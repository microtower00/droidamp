package com.droidamp.player.ui.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.RecessedWell

/**
 * First-run SAF onboarding empty state rendered directly inside a recessed late-1990s VFD well.
 */
@Composable
fun EmptyFolderWell(
  onSelectMusicFolder: () -> Unit,
  modifier: Modifier = Modifier,
  compactPeek: Boolean = false,
) {
  val skin = LocalDroidAmpSkin.current
  RecessedWell(
    modifier = modifier.fillMaxSize(),
    backgroundColor = skin.playlist.normalBackground,
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = if (compactPeek) 8.dp else 20.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(top = if (compactPeek) 6.dp else 24.dp),
      verticalArrangement = Arrangement.Top,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = "NO MUSIC FOLDER LINKED",
        style = if (compactPeek) skin.typography.vfdInfo else skin.typography.emptyHeader,
        color = skin.vfd.phosphorPrimary,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(if (compactPeek) 4.dp else 10.dp))
      Text(
        text =
          if (compactPeek) {
            "TAP TO CHOOSE YOUR MUSIC FOLDER"
          } else {
            "Choose a music folder to browse and play.\nTip: Pick a subfolder like Music, not top-level storage."
          },
        style = skin.typography.emptyBody,
        color = skin.vfd.phosphorDim,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
      )
      if (!compactPeek) {
        Spacer(Modifier.height(18.dp))
        BeveledButton(
          label = "SELECT MUSIC FOLDER",
          onClick = onSelectMusicFolder,
          highlighted = true,
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        )
      }
    }
  }
}
