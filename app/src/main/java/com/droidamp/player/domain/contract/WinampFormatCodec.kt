package com.droidamp.player.domain.contract

import com.droidamp.player.domain.model.AudioTrack
import com.droidamp.player.domain.model.EqPreset

data class WinampIniSnapshot(
  val volume: Int,
  val pan: Int,
  val shuffle: Boolean,
  val repeat: Boolean,
  val timeRemaining: Boolean,
  val eqEnabled: Boolean,
  val eqAuto: Boolean,
  val eqPreampDb: Float,
  val eqBandsDb: List<Float>,
  val replayGainMode: Int = 0,
  val replayGainPreampDb: Float = 0f,
  val crossfadeSeconds: Int = 0,
)

interface EqfCodec {
  fun parseSingleEqf(bytes: ByteArray): EqPreset
  fun encodeSingleEqf(preset: EqPreset): ByteArray
  fun parsePresetLibraryQ1(bytes: ByteArray): List<EqPreset>
  fun encodePresetLibraryQ1(presets: List<EqPreset>): ByteArray
}

interface WinampIniCodec {
  fun parse(iniText: String): WinampIniSnapshot
  fun encode(snapshot: WinampIniSnapshot): String
}

interface PlaylistFileCodec {
  fun parseM3u(content: String): List<String>
  fun encodeM3u8(tracks: List<AudioTrack>): String
  fun parsePls(content: String): List<String>
  fun encodePls(tracks: List<AudioTrack>): String
  fun resolveEntryPath(rawEntry: String, playlistFolderPath: String): String
}

data class AutoEqRule(
  val pattern: String,
  val preset: EqPreset,
)

interface Q2Codec {
  fun parseAutoPresetsQ2(bytes: ByteArray): List<AutoEqRule>
  fun encodeAutoPresetsQ2(rules: List<AutoEqRule>): ByteArray
}

interface AutoEqPresetResolver {
  fun resolveForTrack(
    track: AudioTrack,
    rules: List<AutoEqRule>,
    availablePresets: List<EqPreset>,
  ): EqPreset?
}
