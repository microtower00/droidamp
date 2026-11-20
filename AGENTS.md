# DroidAmp — Agent Guidelines

Read [DESIGN.md](DESIGN.md) and [DECISIONS.md](DECISIONS.md) before making architectural, UX, or visual changes. Follow [WORKFLOW.md](WORKFLOW.md) for milestone orchestration and verification.

## Non-Negotiable Invariants

1. **Strictly Offline**: Never add `INTERNET` or network permissions, analytics, crash-reporting SDKs, or telemetry.
2. **Pure In-App Winamp Aesthetic**: No album art inside the app UI. Expose embedded/folder album art only to Android system media surfaces (MediaSession lockscreen, notification, Bluetooth/Android Auto).
3. **`DroidAmpSkin` Contract**: Never hardcode visual colors, bevel palettes, visualizer gradients, or button chrome in feature composables. Every UI element must consume `DroidAmpSkin` tokens/assets so future `.wsz` skins plug in cleanly.
4. **Hardware Skeuomorphism**: Follow the late-1990s hardware spec in `DESIGN.md` — 3D pixel bevels, slate/gunmetal chassis, pitch-black recessed VFD wells, phosphor green readouts, yellow→orange→red spectrum & EQ bars, crisp aliased pixel typography + 7-segment clock (minimum legibility ~9–10sp micro-labels, 11–12sp buttons/tracks).
5. **Audio & DSP Architecture**:
   - Use AndroidX Media3 (`ExoPlayer` + `MediaSessionService`) with custom in-process PCM `AudioProcessor` stages (no `RECORD_AUDIO` permission for visualizer; no reliance on OEM `AudioEffect` EQ).
   - Winamp Volume (`0..255`) is independent internal DSP gain; reset to default **only on fresh cold launch when playback is stopped**, never while resuming active playback.
6. **UX Safety Rules**:
   - **EQ**: Open by default; support horizontal curve drawing across the 10 frequency bands (`60Hz`–`16kHz`) with `Modifier.systemGestureExclusion()` + horizontal bezel padding, keep a physical gap separating `PREAMP`, show a live VFD readout on drag and `AUTO` EQ toggle/match, and keep a non-interactive bezel buffer above the peeking drawer handle.
   - **Queue/Library Drawer**: 1:1 finger-tracking sheet; non-scrollable peek reflecting last active tab (`QUEUE` vs `LIBRARY`); full-screen when expanded with scroll-top pull-down collapse, bottom thumb-zone `[QUEUE (N)] | [LIBRARY]` tabs + horizontal swipe above the micro-transport bar; Deck `QUEUE` opens to `QUEUE`, `⏏` opens directly to `LIBRARY`; `QUEUE` uses `[REM] [MISC] [LIST]` (+ Clear Queue confirmation & Save Playlist dialog); `LIBRARY` uses iPod-style root (`FOLDERS` / `PLAYLISTS`) with Android Back step-up and contextual `[▶ PLAY (N)] [+ QUEUE (N)]` bar only when 1+ items are selected.
   - **Select-First Track Safety**: In `LIBRARY → FOLDERS`, single-tap on a folder explores it; single-tap on a track **selects** it (never destructive); double-tap or `[▶]` strict Winamp single-track replace-play (switches to `QUEUE`); `[+]` enqueues & stays in `LIBRARY` with visible in-drawer feedback.
   - **System Gesture Safety**: Strictly pad bottom controls above `navigationBarsPadding()` with a non-interactive chassis bottom lip.
7. **Storage & Settings Scoping**:
   - Keep `MusicSourceRepository` storage-agnostic with **SAF as default** and **MediaStore** as an optional backend.
   - Implement Winamp format parsers/exporters (`.eqf`, `winamp.q1`/`q2`, `winamp.ini`, `.m3u`/`.m3u8`/`.pls`) cleanly in the domain/data layer first; keep Settings UI deferred until Milestone 6.
8. **Dependencies & Open-Source Licensing**:
   - Never add third-party libraries beyond the already-specified AndroidX/Media3/DocumentFile/DataStore stack without asking the user for explicit permission first.
   - Open-source code may only be copied or reused if permitted by its license; record source and license attribution so proper credits can be given (DroidAmp is a non-monetized project).
9. **Branching, CI & Release Hygiene**:
   - Never commit new feature/fix work directly to `master`. Always work on a `feat/<slug>`, `fix/<slug>`, or `chore/<slug>` branch, and merge into `master` only after `./gradlew testDebugUnitTest assembleDebug` passes.
   - Cut releases from the dedicated `release` branch: merge `master` → `release`, bump version in `app/build.gradle.kts` (`ADR-025`), **capture fresh screenshots and update `README.md` whenever UI screens changed**, tag `v<MAJOR>.<MINOR>.<PATCH>[-alpha.N|-beta.N|-rc.N]`, and push so GitHub Actions (`.github/workflows/release.yml`) publishes the Release automatically.

## Project Identity & Commands

- **Application ID / Namespace**: `com.droidamp.player`
- **Unit tests**: `./gradlew testDebugUnitTest`
- **Debug build**: `./gradlew assembleDebug`
- **Visual/device verification**: Use the `android-cli` skill (`android emulator`, `android run`, `android layout`, `android screenshot`, `android docs search`).
