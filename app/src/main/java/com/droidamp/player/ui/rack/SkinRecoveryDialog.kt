package com.droidamp.player.ui.rack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.droidamp.player.domain.contract.PlaybackRecoveryNotice
import com.droidamp.player.interop.DetectedWinampFormat
import com.droidamp.player.skin.LocalDroidAmpSkin
import com.droidamp.player.skin.SkinRecoveryNotice
import com.droidamp.player.ui.components.BeveledButton
import com.droidamp.player.ui.components.ChassisTitleBar
import com.droidamp.player.ui.components.HardwarePanel
import com.droidamp.player.ui.components.RecessedWell

/**
 * Hardware-beveled recovery & wrong-slot redirect modal (`ADR-029` "Inform + Allow Overriding").
 *
 * Ensures no skin load fallback, contrast adjustment, duplicate conflict, crash safe-boot revert,
 * or wrong-slot Winamp file selection ever fails silently or strips user agency.
 */
@Composable
fun SkinRecoveryDialog(
  notice: SkinRecoveryNotice,
  onDismiss: () -> Unit,
  onRetrySafeBootSkin: (skinId: String) -> Unit,
  onRevertPartialSkin: (previousSkinId: String) -> Unit,
  onUseRawOgColors: (skinId: String) -> Unit,
  onResolveDuplicate: (conflict: SkinRecoveryNotice.DuplicateSkinConflict, replaceExisting: Boolean) -> Unit,
  onConfirmWrongSlotRedirect: (notice: SkinRecoveryNotice.WrongSlotFileDetected) -> Unit,
) {
  val skin = LocalDroidAmpSkin.current

  Dialog(onDismissRequest = onDismiss) {
    HardwarePanel(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth()) {
        when (notice) {
          is SkinRecoveryNotice.SafeBootReverted -> {
            ChassisTitleBar(title = "SKIN SAFE-BOOT RECOVERY")
            DialogBody(
              headline = "REVERTED TO DROIDAMP CLASSIC",
              headlineColor = skin.vfd.ledAmber,
              detail =
                "Skin \"${notice.failedSkinName}\" did not finish loading on the previous launch. " +
                  "DroidAmp reverted to DroidAmp Classic to keep startup safe.",
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                BeveledButton(
                  label = "KEEP CLASSIC",
                  onClick = onDismiss,
                  modifier = Modifier.weight(1f).height(34.dp),
                )
                BeveledButton(
                  label = "RETRY SKIN",
                  onClick = { onRetrySafeBootSkin(notice.failedSkinId) },
                  highlighted = true,
                  modifier = Modifier.weight(1f).height(34.dp),
                )
              }
            }
          }

          is SkinRecoveryNotice.WrongSlotFileDetected -> {
            val redirectSpec = wrongSlotRedirectSpec(notice)
            ChassisTitleBar(title = redirectSpec.title)
            DialogBody(
              headline = redirectSpec.headline,
              headlineColor =
                if (redirectSpec.canRedirect) skin.vfd.phosphorBright else skin.vfd.ledAmber,
              detail = redirectSpec.detail,
            ) {
              if (redirectSpec.canRedirect) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  BeveledButton(
                    label = "CANCEL",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(34.dp),
                  )
                  BeveledButton(
                    label = redirectSpec.actionLabel,
                    onClick = { onConfirmWrongSlotRedirect(notice) },
                    highlighted = true,
                    modifier = Modifier.weight(1.25f).height(34.dp),
                  )
                }
              } else {
                BeveledButton(
                  label = "DISMISS",
                  onClick = onDismiss,
                  highlighted = true,
                  modifier = Modifier.fillMaxWidth().height(34.dp),
                )
              }
            }
          }

          is SkinRecoveryNotice.DuplicateSkinConflict -> {
            ChassisTitleBar(title = "DUPLICATE SKIN CONFLICT")
            DialogBody(
              headline = "ALREADY INSTALLED: ${notice.existingEntry.displayName.uppercase()}",
              headlineColor = skin.vfd.ledAmber,
              detail =
                "\"${notice.proposedFileName}\" matches an existing imported skin. " +
                  "Replace the installed version or keep both copies?",
            ) {
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  BeveledButton(
                    label = "KEEP BOTH",
                    onClick = { onResolveDuplicate(notice, false) },
                    modifier = Modifier.weight(1f).height(34.dp),
                  )
                  BeveledButton(
                    label = "REPLACE",
                    onClick = { onResolveDuplicate(notice, true) },
                    highlighted = true,
                    modifier = Modifier.weight(1f).height(34.dp),
                  )
                }
                BeveledButton(
                  label = "CANCEL",
                  onClick = onDismiss,
                  modifier = Modifier.fillMaxWidth().height(30.dp),
                )
              }
            }
          }

          is SkinRecoveryNotice.PartialSkinFallback -> {
            val previewMissing = notice.missingAssets.take(4).joinToString(", ")
            val overflowCount = (notice.missingAssets.size - 4).coerceAtLeast(0)
            val missingSummary =
              when {
                previewMissing.isEmpty() -> "Some optional assets fell back to default styling."
                overflowCount > 0 -> "Fallback assets: $previewMissing (+$overflowCount more)."
                else -> "Fallback assets: $previewMissing."
              }
            ChassisTitleBar(title = "PARTIAL SKIN FALLBACK")
            DialogBody(
              headline = "${notice.skinName.uppercase()} LOADED WITH FALLBACKS",
              headlineColor = skin.vfd.ledAmber,
              detail =
                "Missing or unreadable skin assets fell back to default styling. $missingSummary",
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                BeveledButton(
                  label = "REVERT",
                  onClick = { onRevertPartialSkin(notice.previousSkinId) },
                  modifier = Modifier.weight(1f).height(34.dp),
                )
                BeveledButton(
                  label = "KEEP SKIN",
                  onClick = onDismiss,
                  highlighted = true,
                  modifier = Modifier.weight(1f).height(34.dp),
                )
              }
            }
          }

          is SkinRecoveryNotice.ContrastGuardApplied -> {
            ChassisTitleBar(title = "AUTO-CONTRAST GUARD")
            DialogBody(
              headline = "ADJUSTED ${notice.adjustedTokenCount} LOW-CONTRAST COLORS",
              headlineColor = skin.vfd.phosphorBright,
              detail =
                "DroidAmp nudged ${notice.adjustedTokenCount} palette colors in \"${notice.skinName}\" " +
                  "for mobile legibility. Keep readable contrast or override with exact 1999 OG colors?",
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                BeveledButton(
                  label = "USE RAW OG",
                  onClick = { onUseRawOgColors(notice.skinId) },
                  modifier = Modifier.weight(1f).height(34.dp),
                )
                BeveledButton(
                  label = "KEEP GUARD",
                  onClick = onDismiss,
                  highlighted = true,
                  modifier = Modifier.weight(1f).height(34.dp),
                )
              }
            }
          }

          is SkinRecoveryNotice.ActiveSkinDeletedReverted -> {
            ChassisTitleBar(title = "ACTIVE SKIN REMOVED")
            DialogBody(
              headline = "REVERTED TO DROIDAMP CLASSIC",
              headlineColor = skin.vfd.phosphorPrimary,
              detail =
                "Deleted active skin \"${notice.deletedSkinName}\" and restored the built-in DroidAmp Classic rack.",
            ) {
              BeveledButton(
                label = "OK",
                onClick = onDismiss,
                highlighted = true,
                modifier = Modifier.fillMaxWidth().height(34.dp),
              )
            }
          }

          is SkinRecoveryNotice.InvalidSkinError -> {
            ChassisTitleBar(title = "SKIN IMPORT ERROR")
            DialogBody(
              headline = "UNABLE TO IMPORT \"${notice.fileName.uppercase()}\"",
              headlineColor = skin.vfd.ledAmber,
              detail = notice.reason,
            ) {
              BeveledButton(
                label = "DISMISS",
                onClick = onDismiss,
                highlighted = true,
                modifier = Modifier.fillMaxWidth().height(34.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DialogBody(
  headline: String,
  headlineColor: Color,
  detail: String,
  actions: @Composable () -> Unit,
) {
  val skin = LocalDroidAmpSkin.current
  Column(
    modifier = Modifier.fillMaxWidth().padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    RecessedWell(
      modifier = Modifier.fillMaxWidth(),
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
          text = headline,
          style = skin.typography.vfdInfo,
          color = headlineColor,
        )
        Text(
          text = detail,
          style = skin.typography.emptyBody,
          color = skin.vfd.phosphorPrimary,
        )
      }
    }
    actions()
  }
}

private data class WrongSlotRedirectSpec(
  val title: String,
  val headline: String,
  val detail: String,
  val actionLabel: String,
  val canRedirect: Boolean,
)

private fun wrongSlotRedirectSpec(
  notice: SkinRecoveryNotice.WrongSlotFileDetected,
): WrongSlotRedirectSpec =
  when (val format = notice.detectedFormat) {
    DetectedWinampFormat.EqfSinglePreset ->
      WrongSlotRedirectSpec(
        title = "EQ PRESET DETECTED (.EQF)",
        headline = "${notice.fileName.uppercase()} IS A WINAMP EQ PRESET",
        detail =
          "This file contains a single 10-band Winamp Equalizer curve rather than a .wsz skin. " +
            "Apply it to the Equalizer and add it to PRESETS?",
        actionLabel = "APPLY PRESET",
        canRedirect = true,
      )

    DetectedWinampFormat.EqLibraryQ1 ->
      WrongSlotRedirectSpec(
        title = "EQ LIBRARY DETECTED (.Q1)",
        headline = "${notice.fileName.uppercase()} IS A WINAMP .Q1 LIBRARY",
        detail =
          "This file contains a multi-preset Winamp Equalizer library (winamp.q1) rather than a .wsz skin. " +
            "Import all presets into your Equalizer?",
        actionLabel = "IMPORT .Q1",
        canRedirect = true,
      )

    DetectedWinampFormat.AutoEqQ2 ->
      WrongSlotRedirectSpec(
        title = "AUTO-EQ TABLE DETECTED (.Q2)",
        headline = "${notice.fileName.uppercase()} IS A WINAMP .Q2 TABLE",
        detail =
          "This file contains Winamp Auto-EQ track/folder rules (winamp.q2) rather than a .wsz skin. " +
            "Import these Auto-EQ rules now?",
        actionLabel = "IMPORT .Q2",
        canRedirect = true,
      )

    DetectedWinampFormat.WinampIni ->
      WrongSlotRedirectSpec(
        title = "WINAMP.INI CONFIG DETECTED",
        headline = "${notice.fileName.uppercase()} IS A WINAMP CONFIG",
        detail =
          "This file contains Winamp deck, EQ, ReplayGain, and Crossfade settings rather than a .wsz skin. " +
            "Apply these settings to DroidAmp?",
        actionLabel = "APPLY .INI",
        canRedirect = true,
      )

    DetectedWinampFormat.PlaylistM3u ->
      WrongSlotRedirectSpec(
        title = "PLAYLIST DETECTED (.M3U)",
        headline = "${notice.fileName.uppercase()} IS A .M3U PLAYLIST",
        detail =
          "This file is an M3U/M3U8 track playlist rather than a .wsz skin. " +
            "Match tracks from your linked music library and load Queue?",
        actionLabel = "LOAD QUEUE",
        canRedirect = true,
      )

    DetectedWinampFormat.PlaylistPls ->
      WrongSlotRedirectSpec(
        title = "PLAYLIST DETECTED (.PLS)",
        headline = "${notice.fileName.uppercase()} IS A .PLS PLAYLIST",
        detail =
          "This file is a Winamp .pls playlist rather than a .wsz skin. " +
            "Match tracks from your linked music library and load Queue?",
        actionLabel = "LOAD QUEUE",
        canRedirect = true,
      )

    is DetectedWinampFormat.Unsupported ->
      WrongSlotRedirectSpec(
        title = "UNSUPPORTED FILE FORMAT",
        headline = "NOT A VALID .WSZ SKIN",
        detail = format.reason,
        actionLabel = "DISMISS",
        canRedirect = false,
      )

    is DetectedWinampFormat.WszSkin ->
      WrongSlotRedirectSpec(
        title = "SKIN ARCHIVE DETECTED (.WSZ)",
        headline = "${notice.fileName.uppercase()} IS A .WSZ SKIN",
        detail =
          "This archive contains a ${if (format.isAugmented) "DroidAmp Augmented" else "Winamp"} skin rather than the requested file type. " +
            "Import and apply this skin now?",
        actionLabel = "IMPORT .WSZ",
        canRedirect = true,
      )
  }

@Composable
fun PlaybackRecoveryDialog(
  notice: PlaybackRecoveryNotice.MissingTrackSkipped,
  onRemoveMissingTrack: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val skin = LocalDroidAmpSkin.current
  Dialog(onDismissRequest = onDismiss) {
    HardwarePanel(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth()) {
        ChassisTitleBar(title = "UNREADABLE TRACK SKIPPED")
        DialogBody(
          headline = "MISSING OR UNREADABLE FILE",
          headlineColor = skin.vfd.ledAmber,
          detail =
            buildString {
              append("Unable to play \"${notice.track.displayLabel}\" (${notice.reason}). ")
              if (notice.autoSkippedToNext) {
                append("Skipped to next playable track. Remove this track from your queue, or keep it if storage is temporarily disconnected?")
              } else {
                append("Remove this missing track from your queue, or keep it for later?")
              }
            },
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            BeveledButton(
              label = "REMOVE MISSING",
              onClick = { onRemoveMissingTrack(notice.track.uriString) },
              modifier = Modifier.weight(1.2f).height(34.dp),
            )
            BeveledButton(
              label = "KEEP",
              onClick = onDismiss,
              highlighted = true,
              modifier = Modifier.weight(1f).height(34.dp),
            )
          }
        }
      }
    }
  }
}
