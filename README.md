# DroidAmp

A strictly offline, mobile-first Android local music player inspired by late-1990s Winamp hardware skeuomorphism, architected for Winamp file interoperability and future `.wsz` skin support.

## Core Principles

- **Strictly Offline**: Zero `INTERNET`, `ACCESS_NETWORK_STATE`, or `RECORD_AUDIO` permissions. No analytics, no telemetry, no cloud services.
- **Pure In-App Winamp Aesthetic**: Late-1990s beveled gunmetal chassis, pitch-black recessed VFD wells, phosphor green readouts, yellow→orange→red spectrum & EQ bars, and 7-segment clock. Zero album art inside the app — embedded/folder artwork is exposed only to Android system media surfaces (lockscreen, notification shade, Bluetooth/Android Auto).
- **Skin-Ready Architecture (`DroidAmpSkin`)**: Every UI element consumes abstract `DroidAmpSkin` tokens and optional sprite slots so future `.wsz` Winamp 2.x skins plug in cleanly.

## Implemented Features (Milestones 1–4)

### Hardware Rack & UX (Milestone 1)
- **Main Deck**: Recessed VFD visualizer (60fps spectrum analyzer / oscilloscope / off), 7-segment elapsed/remaining clock, scrolling marquee, bitrate/sample-rate/channel telemetry, independent Volume (`0..255`) and Balance (`-127..+127`) sliders, transport deck, Shuffle, and Repeat.
- **10-Band Graphic EQ (`60Hz`–`16kHz`)**: Horizontal curve drawing across bands, isolated `PREAMP` column, live VFD dB readout on drag, `ON` / `AUTO` toggles, scrollable 18-preset `PRESETS` menu, and a non-interactive bezel buffer above the peeking Playlist sheet.
- **Full-Screen Playlist & Library Sheet**: Remembers last active mode (`PLAYLIST` vs. `LIBRARY`), `⏏` Eject jumps straight to `LIBRARY`, select-first track safety in `LIBRARY → FOLDERS`, drag-to-reorder, Jump-to-Track, and gesture-bar clearance (`navigationBarsPadding()` + bottom lip).

### Media3 Audio Service & Custom DSP (Milestone 2)
- AndroidX Media3 `ExoPlayer` + foreground `MediaSessionService` (`DroidAmpPlaybackService`) with lockscreen/notification artwork and Bluetooth AVRCP metadata.
- Custom in-process PCM `AudioProcessor` pipeline:
  - **ReplayGain** (ID3 `TXXX`/`TXX`, Vorbis, MP4, LAME, RFC 7845 `R128_*` + peak clipping protection)
  - **10-Band RBJ Biquad Peaking EQ + Preamp** (±12 dB)
  - **Independent Logarithmic Volume (`0..255`)** resetting to `204` (~80%) only on fresh cold launch when stopped + **Stereo Balance (`-127..+127`)**
  - **Gapless Playback** (on by default) & **Crossfade** (`0..12s`, off by default)
  - **Lock-Free SPSC Ring Buffer & Radix-2 FFT** feeding the 60fps VFD visualizer without `RECORD_AUDIO`

### Local Storage Backends (Milestone 3)
- Storage-agnostic `MusicSourceRepository` defaulting to **SAF** (`ACTION_OPEN_DOCUMENT_TREE` + `DocumentFile` + persisted URI permissions) with an optional **MediaStore** backend (`SwitchingMusicSourceRepository`).
- Offline metadata extraction (`AudioMetadataExtractor`) and first-run retro VFD onboarding flow.

### Winamp Interoperability Engines (Milestone 4)
- Pure Kotlin parsers and serializers with round-trip tests for:
  - `.eqf` (299-byte preset) & `winamp.q1` (preset library)
  - `winamp.q2` (auto-load EQ rules) & 4-tier `AutoEqMatcher` wired to EQ `AUTO`
  - `winamp.ini` configuration snapshots
  - `.m3u`, `.m3u8`, and `.pls` playlists (with relative/Windows path normalization and live `[LIST] → SAVE .M3U8` export)
- 18 classic built-in Winamp EQ presets.

## Roadmap

- [x] **Milestone 1**: `DroidAmpSkin` contract, shared domain contracts, skeuomorphic hardware rack UI
- [x] **Milestone 2**: Media3 `MediaSessionService`, inline PCM DSP pipeline, lock-free FFT
- [x] **Milestone 3**: SAF (default) & MediaStore storage backends, live library/playlist wiring
- [x] **Milestone 4**: Pure Kotlin Winamp format codecs (`.eqf`, `winamp.q1`/`q2`, `winamp.ini`, `.m3u`/`.m3u8`/`.pls`) & `AUTO` EQ
- [ ] **Milestone 5**: `.wsz` Winamp 2.x skin archive parser & sprite slicer
- [ ] **Milestone 6**: Dedicated Settings UI (folder management, SAF/MediaStore toggle, DSP controls, Winamp import/export triggers, `.wsz` skin selector)

## Build, Test & Install

```bash
# Run JVM unit tests (155 tests)
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Install on a connected Android device via USB
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Run Android instrumented E2E suites (240 tests across Tiers 1–4)
./gradlew connectedDebugAndroidTest
```

## Versioning & Releases

DroidAmp follows **Semantic Versioning 2.0.0** (`vMAJOR.MINOR.PATCH[-alpha.N|-beta.N|-rc.N]`) paired with a strictly monotonic Android `versionCode` in [`app/build.gradle.kts`](app/build.gradle.kts) (see [ADR-025](DECISIONS.md)).

- **Pre-releases (`alpha` / `beta` / `rc`)**: Published under [GitHub Releases](https://github.com/microtower00/droidamp/releases) with a sideloadable APK attached.
- **Current Alpha**: `v0.1.0-alpha.1` (`versionCode = 1`).

## Documentation

- [DESIGN.md](DESIGN.md) — Product, UX, visual, and technical architecture specification
- [DECISIONS.md](DECISIONS.md) — Architectural & UX Decision Log (ADR-001 through ADR-025)
- [SETTINGS_ROADMAP.md](SETTINGS_ROADMAP.md) — Milestone 6 Settings roadmap (Classic Winamp 1:1 ↔ Mobile-Optimized)
- [WORKFLOW.md](WORKFLOW.md) — Agentic development workflow & verification playbook
- [RETROSPECTIVE_M2_M4.md](RETROSPECTIVE_M2_M4.md) — Milestones 2–4 build & verification retrospective
- [FDROID.md](FDROID.md) — F-Droid submission metadata & checklist

## License

DroidAmp is free software licensed under the **GNU General Public License v3.0 or later** ([GPL-3.0-or-later](LICENSE)).
