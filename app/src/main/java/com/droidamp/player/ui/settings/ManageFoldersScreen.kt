package com.droidamp.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.ManagedSafFolder
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.RecessedWell
import com.droidamp.player.ui.components.recessedBevel

@Composable
fun ManageFoldersScreen(
  folders: List<ManagedSafFolder>,
  onAddFolder: () -> Unit,
  onRegrantFolder: (ManagedSafFolder) -> Unit,
  onRemoveFolder: (ManagedSafFolder) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  var acknowledgedInaccessibleUris by remember { mutableStateOf<Set<String>>(emptySet()) }

  SettingsSubScreenScaffold(
    title = "MANAGE MUSIC FOLDERS",
    onBack = onBack,
    modifier = modifier,
  ) {
    LazyColumn(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item(key = "add-folder-cta") {
        BeveledButton(
          label = "+ ADD MUSIC FOLDER",
          onClick = onAddFolder,
          highlighted = true,
          modifier = Modifier.fillMaxWidth().height(34.dp),
        )
      }

      if (folders.isEmpty()) {
        item(key = "empty-folders") {
          SettingsCardWell {
            Text(
              text = "NO FOLDERS LINKED YET",
              style = skin.typography.vfdInfo,
              color = skin.vfd.ledAmber,
            )
            Text(
              text = "Add a local or SD card folder to include its music in your library.",
              style = skin.typography.microLabel,
              color = skin.chassis.labelMuted,
            )
          }
        }
      } else {
        items(items = folders, key = { it.uriString }) { folder ->
          SettingsCardWell {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = folder.displayName,
                  style = skin.typography.buttonLabel,
                  color = skin.chassis.labelText,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                if (folder.isAccessible) {
                  Text(
                    text = "${folder.trackCount} tracks · ${folder.subfolderCount} subfolders",
                    style = skin.typography.microLabel,
                    color = skin.vfd.phosphorDim,
                  )
                } else {
                  Text(
                    text = "Folder permission lost or SD card unmounted.",
                    style = skin.typography.microLabel,
                    color = skin.vfd.ledAmber,
                  )
                }
              }

              if (!folder.isAccessible) {
                Box(
                  modifier =
                    Modifier
                      .background(skin.vfd.wellBackground)
                      .recessedBevel(skin, thickness = 1.dp)
                      .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                  Text(
                    text = "ACCESS LOST",
                    style = skin.typography.microLabel,
                    color = skin.vfd.ledAmber,
                  )
                }
              }
            }

            if (folder.isAccessible) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
              ) {
                BeveledButton(
                  label = "REMOVE",
                  onClick = { onRemoveFolder(folder) },
                  modifier = Modifier.height(26.dp),
                  contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                )
              }
            } else {
              if (folder.uriString in acknowledgedInaccessibleUris) {
                RecessedWell(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                  Text(
                    text = "Kept in list until storage reconnects or permission is re-granted.",
                    style = skin.typography.microLabel,
                    color = skin.vfd.phosphorDim,
                  )
                }
              }
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                BeveledButton(
                  label = "RE-GRANT",
                  onClick = { onRegrantFolder(folder) },
                  highlighted = true,
                  modifier = Modifier.weight(1f).height(28.dp),
                  contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                )
                BeveledButton(
                  label = "KEEP",
                  onClick = {
                    acknowledgedInaccessibleUris = acknowledgedInaccessibleUris + folder.uriString
                  },
                  modifier = Modifier.weight(0.9f).height(28.dp),
                  contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                )
                BeveledButton(
                  label = "REMOVE",
                  onClick = { onRemoveFolder(folder) },
                  modifier = Modifier.weight(0.9f).height(28.dp),
                  contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}
