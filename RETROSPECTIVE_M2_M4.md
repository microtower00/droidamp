# DroidAmp — Milestones 2–4 Teamwork Run Retrospective

- **Date**: 2026-11-20
- **Baseline**: Milestone 1 (`fb428f2`)
- **Scope**: Milestone 2 (`dsp`), Milestone 3 (`storage`), Milestone 4 (`winamp-io`), App/UI Integration (`M-integration`), and Emulator E2E Verification (`M-final-e2e` Tiers 1–4 + Live `medium_phone` QA)
- **Final State**: Ready for first real-device (user phone) test via `app/build/outputs/apk/debug/app-debug.apk` (17 MB). Stopped cleanly right after Tier 4 + Live QA passed and Tier 5 logged its findings, per user request to test on phone immediately and preserve remaining quota.

---

## 1. What Was Delivered & Verified

### Implementation Tracks (All 4 Complete & Gate-Passed)
- **Milestone 4 — `M-winamp-io` (`interop/`)**: Pure Kotlin codecs and round-trip tests for `.eqf` (299-byte layout), `winamp.q1`, `winamp.q2`, `winamp.ini`, `.m3u`/`.m3u8`/`.pls` (with relative/Windows path normalization), 18 classic Winamp EQ presets (`ClassicWinampPresets.ALL`), and 4-tier `AutoEqMatcher`. (**46/46** interop tests)
- **Milestone 2 — `M-dsp` (`audio/`)**: Media3 `DroidAmpPlaybackService` (`MediaSessionService`), system-surface-only artwork & Bluetooth AVRCP metadata (`SystemArtworkExtractor`), inline PCM `AudioProcessor` chain (`ReplayGainProcessor`, 10-band RBJ biquad `WinampTenBandEqProcessor`, logarithmic `VolumeBalanceProcessor` with cold-launch-only reset to `204` when `STOPPED`, lock-free `LockFreeAudioRingBuffer` + `Radix2Fft` + `VisualizerTapProcessor`), gapless playback (on by default), crossfade controller (off by default, `0..12s`), and `DspPreferencesStore` via DataStore. (**47/47** DSP/service tests)
- **Milestone 3 — `M-storage` (`storage/`)**: Storage-agnostic `SwitchingMusicSourceRepository` defaulting to `SafMusicSourceRepository` (`OpenDocumentTree` + `DocumentFile` + persisted URI permissions + `.m3u8` export) with optional `MediaStoreMusicSourceRepository`, `AudioMetadataExtractor`, and `StoragePreferencesStore`. (**27/27** storage tests)
- **Integration — `M-integration` (`DroidAmpAppContainer.kt`, `MainActivity.kt`, `ui/`)**: Process-scoped manual DI container, real SAF onboarding picker, live Deck/EQ/Playlist/Library wiring, `AutoEqMatcher` + `AndroidAutoEqCatalogProvider`, scrollable 18-preset `PRESETS` popup, `[LIST] -> SAVE .M3U8`, playlist drag-to-reorder, Jump-to-Track, and `.gitignore` guard for the test MP3. (**155/155** total unit tests across 28 suites)

### Verification Results
- **Unit tests (`testDebugUnitTest`)**: **155/155 PASS** (0 failures, 0 errors, 0 skipped, 0 compiler warnings)
- **Debug APK (`assembleDebug`)**: **PASS** (`app/build/outputs/apk/debug/app-debug.apk`, 17 MB)
- **Live Emulator E2E (`medium_phone`, API 36)**: **240/240 PASS**
  - `InfraSmokeTest`: **3/3**
  - Tier 1 (Core): **102/102**
  - Tier 2 (Boundary): **102/102**
  - Tier 3 (Pairwise): **22/22**
  - Tier 4 (Workloads & Device): **11/11**
- **Live `medium_phone` QA (`The Doors - L.A. Woman.mp3`)**:
  - Linked `/sdcard/Music` via real Android SAF (`ACTION_OPEN_DOCUMENT_TREE`).
  - Sustained foreground playback verified **>2m30s** (`isForeground=true foregroundId=1001`, 44,100 Hz `AudioTrack`, `PLAYING(3)` in `dumpsys media_session`, zero ANR).
  - Verified live VFD spectrum/peaks & clock, `AUTO` + `ROCK` EQ presets, `[LIST] -> SAVE .M3U8` (`/sdcard/Music/droidamp_playlist.m3u8`), zero in-app album art, visible `PREAMP` gap, swipe buffer, and gesture-bar clearance.
- **Manifest & Guardrail Hygiene**:
  - Merged APK manifest verified **zero** `INTERNET`, `ACCESS_NETWORK_STATE`, and `RECORD_AUDIO` permissions.
  - Zero extra third-party dependencies added beyond AndroidX/Media3/DocumentFile/DataStore.
  - All M2–M4 code written from scratch in Kotlin (no copied external OSS code).
  - `/The Doors - L.A. Woman.mp3` kept untracked and added to `.gitignore`.

---

## 2. What Went Well
1. **Milestone 1 shared contracts paid off immediately**: Exclusive file ownership and frozen M1 contracts (`AudioEngineController`, `MusicSourceRepository`, `WinampFormatCodec`) allowed `M-winamp-io`, `M-dsp`, and the E2E test infrastructure to develop in parallel with zero merge conflicts.
2. **Adversarial verification caught real bugs before integration**:
   - `M-winamp-io`: 9 edge-case bugs in `AutoEqMatcher`, `WinampIniParser`, and `WinampPlaylistCodec`.
   - `M-storage`: 8 edge-case bugs including UTF-8 `percentDecode` (`%C3%A9`) and `storage/self/primary` / `mnt/sdcard` mount aliases.
   - `M-integration`: `CancellationException` preservation, `onSelectMusicFolder` root selection, and `PlaylistLibrarySheet` `bottomLip` clickability.
3. **Live emulator QA caught a real Android ForegroundService ANR**:
   - During live playback of `The Doors - L.A. Woman.mp3`, Android raised a `Context.startForegroundService() did not then call Service.startForeground()` ANR after ~42s because `DroidAmpPlaybackService.onCreate()` did not call `addSession(createdSession)` while `Media3AudioEngineController` manually invoked `startForegroundService()`.
   - Fixed in `DroidAmpPlaybackService.kt` + `Media3AudioEngineController.kt` and confirmed with >2m30s uninterrupted foreground playback (`foregroundId=1001`).
4. **Crash & disconnect resilience**: Across two server restarts (laptop sleep + terminal crash) and one ~44-minute network drop, zero code was lost and every resumed track picked up from saved state.

---

## 3. What Didn't Go Well & Lessons Learned
1. **Model selection overhead in `/teamwork`**:
   - `/teamwork` subagents inherited the parent model across every role (including read-only surveys, E2E test scaffolding, and mechanical verification waves) rather than using `WORKFLOW.md`'s dual-pool named model matrix (`swarm add --model ...`), increasing quota usage and wall-clock time.
   - **Action for M5 & M6**: Use `swarm` with explicit model pinning (lighter/faster models for surveys, boilerplate, and mechanical audits) or tighter single-agent plans for focused milestones.
2. **Heavy per-tier verification loops**:
   - Running full 5-agent verification waves across multiple iterations for every E2E test-authoring sub-tier stretched total runtime significantly and risked quota exhaustion before finishing.
   - **Action for M5 & M6**: Keep full adversarial gates for core implementation milestones, and use streamlined single-pass verification for test authoring and UI polish.
3. **Unattended `BypassSandbox` prompt timeouts**:
   - Live emulator QA paused twice when `BypassSandbox: true` commands timed out at 60s waiting for approval while the user was away.
   - **Action for M5 & M6**: Use clean, prefix-matchable commands (`./gradlew`, `adb`, `android`) and pre-approve them before stepping away.

---

## 4. Known Tier 5 Backlog (Saved for Post-Phone-Test Cleanup)
Right before stopping the run for phone testing, `challenger_tier5` logged **6 genuine edge-case bugs** and **3 untested surfaces**. Because crossfade defaults to **OFF (`0s`)**, items 1, 3, and 4 do not impact default phone playback:

### Bugs to Fix Next
1. **[Critical — Crossfade only]** `DroidAmpPlaybackService.kt` / `Media3AudioEngineController.kt`: Outgoing deck `pauseAtEndOfMediaItems` can fire `onIsPlayingChanged(false)` during crossfade, cancelling `positionJob` permanently after swap.
2. **[High]** `DroidAmpPlaybackService.kt` / `Media3AudioEngineController.kt`: Missing `Player.Listener.onPlayerError` override leaves UI stuck in `PLAYING` with 60fps `visualizerJob` spinning if a corrupt or deleted file fails `prepare()`.
3. **[High — Crossfade only]** `DroidAmpPlaybackService.kt`: `onCrossfadeStarted` clears incoming deck ReplayGain and non-active deck `onMetadata` events are dropped during crossfade ramps.
4. **[Medium — Crossfade only]** `DroidAmpPlaybackService.kt`: `syncQueueWithoutInterrupting` mutates only the active deck during an active crossfade, desynchronizing the incoming deck after swap.
5. **[Medium]** `DroidAmpViewModel.kt`: `playPrimaryLibrarySelection()` does not clear stale `selectedPlaylistIndices`.
6. **[Medium]** `Media3AudioEngineController.kt` / `DroidAmpPlaybackService.kt`: Duplicate track IDs in the same playlist resolve via `indexOfFirst { it.id == ... }` during `removeIndices`, sort/reverse/randomize, and crossfade preparation.

### Test Coverage Gaps to Close Next
- `AndroidAutoEqCatalogProvider` (`ui/rack/AutoEqCatalogProvider.kt`)
- `WinampForwardingPlayer` / `ServiceBindingAudioEngineGateway` pre-bind command flushing and remote `MediaController` commands
- `PlaylistEditorView` touch `detectDragGesturesAfterLongPress` drag-to-reorder gesture tests
