package com.droidamp.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidamp.player.domain.model.DspSettings
import com.droidamp.player.domain.model.LibraryScanState
import com.droidamp.player.domain.model.ManagedSafFolder
import com.droidamp.player.domain.model.ReplayGainMode
import com.droidamp.player.domain.model.StorageBackend
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.storage.AppPreferences
import com.droidamp.player.storage.AudioBufferMode
import com.droidamp.player.storage.KeepScreenOnMode
import com.droidamp.player.storage.QueueTapAction
import com.droidamp.player.storage.StartupVolumeMode
import com.droidamp.player.storage.TrackTransitionMode
import com.droidamp.player.storage.VolumeKeyMode
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.SliderTrackStyle
import kotlin.math.roundToInt

enum class SettingsRoute {
  MAIN,
  SKINS,
  MANAGE_FOLDERS,
  EQ_PRESETS,
  WINAMP_MIGRATION,
  ABOUT,
}

@Composable
fun SettingsScreen(
  appPreferences: AppPreferences,
  dspSettings: DspSettings,
  storageBackend: StorageBackend,
  managedSafFolders: List<ManagedSafFolder>,
  scanState: LibraryScanState,
  activeSkinName: String,
  onUpdatePreferences: ((AppPreferences) -> AppPreferences) -> Unit,
  onSelectMusicSource: (StorageBackend) -> Unit,
  onAddMusicFolder: () -> Unit,
  onRetryAudioPermission: () -> Unit,
  onOpenAppPermissionSettings: () -> Unit,
  onRescanLibrary: () -> Unit,
  onSetTrackTransitionMode: (TrackTransitionMode) -> Unit,
  onSetCrossfadeDurationSec: (Int) -> Unit,
  onSetReplayGainMode: (ReplayGainMode) -> Unit,
  onSetReplayGainPreampDb: (Float) -> Unit,
  onNavigate: (SettingsRoute) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  showBackButton: Boolean = true,
  userPresetCount: Int = 0,
  autoEqRuleCount: Int = 0,
) {
  val skin = LocalDroidAmpSkin.current

  SettingsSubScreenScaffold(
    title = "SETTINGS",
    onBack = onBack,
    showBackButton = showBackButton,
    showSubHeader = false,
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
      SettingsSectionHeader(title = "CONTROLS")
      SettingsCardWell {
        SettingsSegmentedRow(
          title = "Hardware Volume Keys",
          subtitle = "Sync matches Android media volume; Independent controls DroidAmp's deck volume separately.",
          options =
            listOf(
              SegmentedOption(VolumeKeyMode.SYNC_SYSTEM, "Sync System"),
              SegmentedOption(VolumeKeyMode.INDEPENDENT_DECK, "Independent", isOg = true),
            ),
          selected = appPreferences.volumeKeyMode,
          onSelect = { mode -> onUpdatePreferences { it.copy(volumeKeyMode = mode) } },
        )
        SettingsDivider()
        SettingsToggleRow(
          title = "Pause on Disconnect",
          subtitle = "Pause when wired or Bluetooth headphones disconnect.",
          checked = appPreferences.pauseOnDisconnect,
          onCheckedChange = { enabled -> onUpdatePreferences { it.copy(pauseOnDisconnect = enabled) } },
        )
        SettingsDivider()
        SettingsToggleRow(
          title = "Auto-Resume on Reconnect",
          subtitle = "Resume when headphones reconnect after a pause.",
          checked = appPreferences.autoResumeOnReconnect,
          enabled = appPreferences.pauseOnDisconnect,
          onCheckedChange = { enabled -> onUpdatePreferences { it.copy(autoResumeOnReconnect = enabled) } },
        )
        SettingsDivider()
        SettingsSegmentedRow(
          title = "Keep Screen On",
          options =
            listOf(
              SegmentedOption(KeepScreenOnMode.OFF, "OFF"),
              SegmentedOption(KeepScreenOnMode.WHILE_PLAYING, "Playing"),
              SegmentedOption(KeepScreenOnMode.ALWAYS, "Always"),
            ),
          selected = appPreferences.keepScreenOnMode,
          onSelect = { mode -> onUpdatePreferences { it.copy(keepScreenOnMode = mode) } },
        )
        SettingsDivider()
        SettingsSegmentedRow(
          title = "Queue Tap Action",
          options =
            listOf(
              SegmentedOption(QueueTapAction.DOUBLE_TAP, "Double-Tap", isOg = true),
              SegmentedOption(QueueTapAction.SINGLE_TAP, "Single-Tap"),
            ),
          selected = appPreferences.queueTapAction,
          onSelect = { action -> onUpdatePreferences { it.copy(queueTapAction = action) } },
        )
        SettingsDivider()
        SettingsToggleRow(
          title = "Haptic Feedback",
          checked = appPreferences.hapticFeedbackEnabled,
          onCheckedChange = { enabled -> onUpdatePreferences { it.copy(hapticFeedbackEnabled = enabled) } },
        )
      }

      SettingsSectionHeader(title = "MUSIC LIBRARY")
      SettingsCardWell {
        SettingsSegmentedRow(
          title = "Music Source",
          subtitle = "All Device Music scans your whole device; Selected Folders scans only folders you choose.",
          options =
            listOf(
              SegmentedOption(StorageBackend.MEDIA_STORE, "All Device Music"),
              SegmentedOption(StorageBackend.SAF, "Selected Folders"),
            ),
          selected = storageBackend,
          onSelect = onSelectMusicSource,
        )

        if (storageBackend == StorageBackend.SAF) {
          SettingsDivider()
          val accessibleCount = managedSafFolders.count { it.isAccessible }
          val lostCount = managedSafFolders.count { !it.isAccessible }
          val summary =
            when {
              managedSafFolders.isEmpty() -> "No folders linked yet"
              lostCount > 0 -> "$accessibleCount active · $lostCount access lost"
              else -> "$accessibleCount folders linked"
            }
          SettingsNavRow(
            title = "Manage Folders",
            valueSummary = summary,
            badgeText = if (lostCount > 0) "ACTION NEEDED" else null,
            badgeColor = skin.vfd.ledAmber,
            onClick = { onNavigate(SettingsRoute.MANAGE_FOLDERS) },
          )
          if (managedSafFolders.isEmpty()) {
            BeveledButton(
              label = "+ ADD MUSIC FOLDER",
              onClick = onAddMusicFolder,
              highlighted = true,
              modifier = Modifier.fillMaxWidth().height(34.dp),
            )
          }
        } else if (!scanState.hasAudioPermission) {
          SettingsDivider()
          Text(
            text = "Audio permission is required to read All Device Music.",
            style = skin.typography.microLabel,
            color = skin.vfd.ledAmber,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            BeveledButton(
              label = "RETRY",
              onClick = onRetryAudioPermission,
              highlighted = true,
              modifier = Modifier.weight(1f).height(28.dp),
              contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            )
            BeveledButton(
              label = "APP SETTINGS",
              onClick = onOpenAppPermissionSettings,
              modifier = Modifier.weight(1f).height(28.dp),
              contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            )
          }
        }

        SettingsDivider()
        val scanSummary =
          if (scanState.isRefreshing) {
            "Scanning library (${scanState.cachedTrackCount} tracks)..."
          } else {
            val countLabel = if (scanState.cachedTrackCount == 1) "1 track" else "${scanState.cachedTrackCount} tracks"
            "$countLabel · Tap to rescan"
          }
        SettingsNavRow(
          title = "Rescan Library",
          valueSummary = scanSummary,
          actionLabel = "↻",
          onClick = onRescanLibrary,
        )
      }

      SettingsSectionHeader(title = "APPEARANCE")
      SettingsCardWell {
        SettingsNavRow(
          title = "Skins",
          valueSummary = activeSkinName,
          onClick = { onNavigate(SettingsRoute.SKINS) },
        )
      }

      SettingsSectionHeader(title = "AUDIO & PLAYBACK")
      SettingsCardWell {
        SettingsRadioGroup(
          title = "Track Transition",
          options =
            listOf(
              SegmentedOption(TrackTransitionMode.STANDARD, "Standard"),
              SegmentedOption(TrackTransitionMode.GAPLESS, "Gapless"),
              SegmentedOption(TrackTransitionMode.CROSSFADE, "Crossfade"),
            ),
          selected = appPreferences.trackTransitionMode,
          onSelect = onSetTrackTransitionMode,
        )

        if (appPreferences.trackTransitionMode == TrackTransitionMode.CROSSFADE) {
          SettingsDivider()
          val secRange =
            (AppPreferences.MAX_CROSSFADE_DURATION_SEC - AppPreferences.MIN_CROSSFADE_DURATION_SEC)
              .coerceAtLeast(1)
              .toFloat()
          val norm =
            (appPreferences.crossfadeDurationSec - AppPreferences.MIN_CROSSFADE_DURATION_SEC)
              .toFloat() / secRange
          SettingsSliderRow(
            title = "Crossfade Duration",
            valueLabel = "${appPreferences.crossfadeDurationSec}s",
            value0To1 = norm,
            onValueChange = { frac ->
              val sec =
                (AppPreferences.MIN_CROSSFADE_DURATION_SEC + (frac * secRange).roundToInt())
                  .coerceIn(AppPreferences.MIN_CROSSFADE_DURATION_SEC, AppPreferences.MAX_CROSSFADE_DURATION_SEC)
              onSetCrossfadeDurationSec(sec)
            },
          )
        }

        SettingsDivider()
        SettingsSegmentedRow(
          title = "ReplayGain",
          options =
            listOf(
              SegmentedOption(ReplayGainMode.OFF, "OFF"),
              SegmentedOption(ReplayGainMode.TRACK, "Track"),
              SegmentedOption(ReplayGainMode.ALBUM, "Album"),
            ),
          selected = dspSettings.replayGainMode,
          onSelect = onSetReplayGainMode,
        )

        if (dspSettings.replayGainMode != ReplayGainMode.OFF) {
          SettingsDivider()
          val clampedDb = dspSettings.replayGainPreampDb.coerceIn(-12f, 12f)
          val normPreamp = (clampedDb + 12f) / 24f
          SettingsSliderRow(
            title = "ReplayGain Preamp",
            valueLabel = "%+.1f dB".format(clampedDb),
            value0To1 = normPreamp,
            trackStyle = SliderTrackStyle.CENTER_BALANCE,
            onValueChange = { frac ->
              val snappedHalf = (((frac * 24f - 12f) * 2f).roundToInt() / 2f).coerceIn(-12f, 12f)
              onSetReplayGainPreampDb(snappedHalf)
            },
          )
        }

        SettingsDivider()
        SettingsSegmentedRow(
          title = "Audio Buffer",
          subtitle = "Low Latency (75–150 ms) makes EQ updates ~4× snappier; switch to Standard if you hear crackles.",
          options =
            listOf(
              SegmentedOption(AudioBufferMode.LOW_LATENCY, "Low Latency"),
              SegmentedOption(AudioBufferMode.STANDARD, "Standard"),
            ),
          selected = appPreferences.audioBufferMode,
          onSelect = { mode -> onUpdatePreferences { it.copy(audioBufferMode = mode) } },
        )

        SettingsDivider()
        val presetLabel = if (userPresetCount == 1) "1 custom" else "$userPresetCount custom"
        val rulesLabel = if (autoEqRuleCount == 1) "1 Auto-EQ rule" else "$autoEqRuleCount Auto-EQ rules"
        SettingsNavRow(
          title = "EQ Presets & Auto-EQ",
          valueSummary = "$presetLabel · $rulesLabel",
          onClick = { onNavigate(SettingsRoute.EQ_PRESETS) },
        )

        SettingsDivider()
        SettingsSegmentedRow(
          title = "Startup Volume",
          options =
            listOf(
              SegmentedOption(StartupVolumeMode.REMEMBER_LAST, "Remember Last"),
              SegmentedOption(StartupVolumeMode.CLASSIC_80_PERCENT, "Classic 80%", isOg = true),
            ),
          selected = appPreferences.startupVolumeMode,
          onSelect = { mode -> onUpdatePreferences { it.copy(startupVolumeMode = mode) } },
        )
      }

      SettingsSectionHeader(title = "DATA & ABOUT")
      SettingsCardWell {
        SettingsNavRow(
          title = "Winamp Migration & Backup",
          valueSummary = "Import or export .ini, .q1, .q2 · Reset defaults",
          onClick = { onNavigate(SettingsRoute.WINAMP_MIGRATION) },
        )
        SettingsDivider()
        SettingsNavRow(
          title = "About DroidAmp",
          valueSummary = "GPL-3.0-or-later · 100% Offline · Open-Source Credits",
          onClick = { onNavigate(SettingsRoute.ABOUT) },
        )
      }
    }
  }
}
