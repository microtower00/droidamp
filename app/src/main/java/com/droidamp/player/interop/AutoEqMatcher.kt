package com.droidamp.player.interop

import com.droidamp.player.domain.contract.AutoEqPresetResolver
import com.droidamp.player.domain.contract.AutoEqRule
import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.EqPreset
import java.util.Locale

class AutoEqMatcher : AutoEqPresetResolver {
  override fun resolveForTrack(
    track: AudioTrack,
    rules: List<AutoEqRule>,
    availablePresets: List<EqPreset>,
  ): EqPreset? {
    val normUri = normalizeUriOrPath(track.uriString)
    val normDecodedUri = normalizeUriOrPath(percentDecode(track.uriString))
    val normFolder = normalizeFolderPath(track.folderPath)
    val normFileName = track.fileName.trim().replace('\\', '/').substringAfterLast('/')
      .lowercase(Locale.ROOT)
    val normFileStem = normFileName.substringBeforeLast('.')
    val normFullPath =
      when {
        normFolder.isNotEmpty() && normFileName.isNotEmpty() -> "$normFolder/$normFileName"
        normFileName.isNotEmpty() -> normFileName
        else -> ""
      }
    val normTitle = track.title.trim().lowercase(Locale.ROOT)
    val normDisplayLabel = track.displayLabel.trim().lowercase(Locale.ROOT)

    val validRules =
      rules.mapNotNull { rule ->
        val rawTitleKey = rule.pattern.trim().lowercase(Locale.ROOT)
        val rawNorm = normalizeUriOrPath(rule.pattern)
        val trimmed = rawNorm.trim('/')
        if (trimmed.isEmpty()) null else NormalizedRule(rawNorm, trimmed, rawTitleKey, rule.preset)
      }

    // Tier 1: Exact URI / full path / filename / filename stem.
    // Sub-priority 1a: Exact URI.
    if (normUri.isNotEmpty()) {
      validRules.firstOrNull { it.rawNorm == normUri }?.let { return it.preset }
      if (normDecodedUri != normUri) {
        validRules.firstOrNull { it.rawNorm == normDecodedUri }?.let { return it.preset }
      }
    }

    // Sub-priority 1b: Exact full relative path, then longest partial path suffix (order-independent).
    if (normFullPath.isNotEmpty()) {
      validRules.firstOrNull { it.trimmed == normFullPath }?.let { return it.preset }
    }

    val bestPartialPathMatch =
      validRules
        .mapIndexedNotNull { index, rule ->
          val matchesFileName = normFileName.isEmpty() || rule.trimmed.endsWith("/$normFileName")
          if (!rule.trimmed.contains('/') || !matchesFileName) return@mapIndexedNotNull null
          val matchesFullPath =
            normFullPath.isNotEmpty() && normFullPath.endsWith("/${rule.trimmed}")
          val matchesUri =
            normUri.isNotEmpty() &&
              (normUri.endsWith("/${rule.trimmed}") ||
                normDecodedUri.endsWith("/${rule.trimmed}") ||
                normDecodedUri.endsWith(":${rule.trimmed}"))
          if (!matchesFullPath && !matchesUri) return@mapIndexedNotNull null
          PathMatchCandidate(rule.trimmed.length, index, rule.preset)
        }
        .maxWithOrNull(
          compareBy<PathMatchCandidate> { it.patternLength }
            .thenByDescending { it.ruleIndex },
        )
    if (bestPartialPathMatch != null) return bestPartialPathMatch.preset

    // Sub-priority 1c: Exact filename with extension.
    if (normFileName.isNotEmpty()) {
      validRules.firstOrNull { it.trimmed == normFileName }?.let { return it.preset }
    }

    // Sub-priority 1d: Exact filename stem without extension.
    if (normFileStem.isNotEmpty()) {
      validRules.firstOrNull { it.trimmed == normFileStem }?.let { return it.preset }
    }

    // Tier 2: Folder path match (deepest / most-specific folder rule wins).
    if (normFolder.isNotEmpty()) {
      val bestFolderMatch =
        validRules
          .mapIndexedNotNull { index, rule ->
            val rank =
              when {
                normFolder == rule.trimmed -> 3
                normFolder.endsWith("/${rule.trimmed}") -> 2
                normFolder.startsWith("${rule.trimmed}/") -> 1
                else -> 0
              }
            if (rank == 0) null else FolderMatchCandidate(rank, rule.trimmed.length, index, rule.preset)
          }
          .maxWithOrNull(
            compareBy<FolderMatchCandidate> { it.rank }
              .thenBy { it.patternLength }
              .thenByDescending { it.ruleIndex },
          )
      if (bestFolderMatch != null) return bestFolderMatch.preset
    }

    // Tier 3: Title / Display label exact match in rules.
    if (normTitle.isNotEmpty() || normDisplayLabel.isNotEmpty()) {
      validRules.firstOrNull { rule ->
        val titleKey = rule.rawTitleKey
        (normTitle.isNotEmpty() && titleKey == normTitle) ||
          (normDisplayLabel.isNotEmpty() && titleKey == normDisplayLabel)
      }?.let { return it.preset }
    }

    // Tier 4: Exact folder-leaf match in availablePresets (excluding FLAT and CUSTOM).
    val folderLeaf = normFolder.substringAfterLast('/')
    if (folderLeaf.isNotEmpty()) {
      availablePresets.firstOrNull { preset ->
        val presetName = preset.name.trim().lowercase(Locale.ROOT)
        presetName != "flat" && presetName != "custom" && presetName == folderLeaf
      }?.let { return it }
    }

    return null
  }

  private fun normalizeFolderPath(raw: String): String =
    normalizeUriOrPath(raw).trim('/')

  private fun normalizeUriOrPath(raw: String): String {
    val trimmed = raw.trim()
    val schemeMatch = URI_SCHEME_PREFIX_REGEX.find(trimmed)
    return if (schemeMatch != null) {
      val schemeEnd = schemeMatch.range.last + 1
      val prefix = trimmed.substring(0, schemeEnd).lowercase(Locale.ROOT)
      val rest =
        MULTI_SLASH_REGEX.replace(
          trimmed.substring(schemeEnd).replace('\\', '/').lowercase(Locale.ROOT),
          "/",
        )
      prefix + rest
    } else {
      val replaced = trimmed.replace('\\', '/').lowercase(Locale.ROOT)
      MULTI_SLASH_REGEX.replace(replaced, "/")
    }
  }

  private fun percentDecode(raw: String): String {
    if (!raw.contains('%')) return raw
    val bytes = ByteArray(raw.length)
    var byteLen = 0
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
      val ch = raw[i]
      if (ch == '%' && i + 2 < raw.length) {
        val hi = hexDigit(raw[i + 1])
        val lo = hexDigit(raw[i + 2])
        if (hi >= 0 && lo >= 0) {
          bytes[byteLen++] = ((hi shl 4) or lo).toByte()
          i += 3
          continue
        }
      }
      if (byteLen > 0) {
        out.append(bytes.decodeToString(0, byteLen))
        byteLen = 0
      }
      out.append(ch)
      i++
    }
    if (byteLen > 0) {
      out.append(bytes.decodeToString(0, byteLen))
    }
    return out.toString()
  }

  private fun hexDigit(ch: Char): Int =
    when (ch) {
      in '0'..'9' -> ch - '0'
      in 'a'..'f' -> ch - 'a' + 10
      in 'A'..'F' -> ch - 'A' + 10
      else -> -1
    }

  private data class NormalizedRule(
    val rawNorm: String,
    val trimmed: String,
    val rawTitleKey: String,
    val preset: EqPreset,
  )

  private data class PathMatchCandidate(
    val patternLength: Int,
    val ruleIndex: Int,
    val preset: EqPreset,
  )

  private data class FolderMatchCandidate(
    val rank: Int,
    val patternLength: Int,
    val ruleIndex: Int,
    val preset: EqPreset,
  )

  private companion object {
    val URI_SCHEME_PREFIX_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]{1,}://")
    val MULTI_SLASH_REGEX = Regex("/+")
  }
}
