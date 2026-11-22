package com.droidamp.player.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.RecessedWell

private const val REPO_URL = "https://github.com/microtower00/droidamp"

@Composable
fun AboutDroidAmpScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val skin = LocalDroidAmpSkin.current
  val context = LocalContext.current
  val versionSummary =
    remember(context) {
      runCatching {
        val pkgInfo =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
              context.packageName,
              PackageManager.PackageInfoFlags.of(0L),
            )
          } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
          }
        val code = PackageInfoCompat.getLongVersionCode(pkgInfo)
        val name = pkgInfo.versionName ?: "0.1.0-alpha.1"
        "v$name (Build $code)"
      }.getOrDefault("v0.1.0-alpha.1")
    }

  SettingsSubScreenScaffold(
    title = "ABOUT DROIDAMP",
    onBack = onBack,
    modifier = modifier,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SettingsSectionHeader(title = "APPLICATION & PRIVACY")
      SettingsCardWell {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
              text = "DROIDAMP AUDIO PLAYER",
              style = skin.typography.buttonLabel,
              color = skin.chassis.labelText,
            )
            Text(
              text = versionSummary,
              style = skin.typography.microLabel,
              color = skin.vfd.phosphorDim,
            )
          }
          RecessedWell(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp)) {
            Text(
              text = "100% OFFLINE",
              style = skin.typography.microLabel,
              color = skin.vfd.phosphorBright,
            )
          }
        }
        Text(
          text = "Zero network, telemetry, analytics, or microphone permissions.",
          style = skin.typography.microLabel,
          color = skin.chassis.labelMuted,
        )
      }

      SettingsSectionHeader(title = "LICENSE & SOURCE CODE")
      SettingsCardWell {
        Text(
          text = "GNU GENERAL PUBLIC LICENSE V3.0 OR LATER",
          style = skin.typography.buttonLabel,
          color = skin.chassis.labelText,
        )
        Text(
          text = "Free, copyleft open-source software. Classic 1999 Winamp hardware fidelity meets Android.",
          style = skin.typography.microLabel,
          color = skin.chassis.labelMuted,
        )
        SettingsDivider()
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "GITHUB REPOSITORY",
              style = skin.typography.buttonLabel,
              color = skin.chassis.labelText,
            )
            Text(
              text = "github.com/microtower00/droidamp",
              style = skin.typography.microLabel,
              color = skin.vfd.phosphorPrimary,
            )
          }
          BeveledButton(
            label = "OPEN",
            onClick = {
              runCatching {
                val intent =
                  Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                  }
                context.startActivity(intent)
              }
            },
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
          )
        }
      }

      SettingsSectionHeader(title = "OPEN-SOURCE CREDITS")
      SettingsCardWell {
        val credits =
          listOf(
            "AndroidX Core KTX & Lifecycle" to "Apache-2.0",
            "Jetpack Compose Foundation & UI" to "Apache-2.0",
            "AndroidX Media3 (ExoPlayer & MediaSession)" to "Apache-2.0",
            "Jetpack DataStore Preferences" to "Apache-2.0",
            "AndroidX DocumentFile & Activity" to "Apache-2.0",
          )
        credits.forEachIndexed { index, (lib, license) ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = lib,
              style = skin.typography.microLabel,
              color = skin.chassis.labelText,
              modifier = Modifier.weight(1f),
            )
            Text(
              text = license,
              style = skin.typography.microLabel,
              color = skin.vfd.phosphorDim,
            )
          }
          if (index < credits.lastIndex) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(skin.chassis.divider))
          }
        }
      }
    }
  }
}
