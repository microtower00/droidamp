# DroidAmp — Architectural & UX Decision Log

This log records the key product, UX, and engineering decisions made during the initial design and critique sessions for **DroidAmp**.

---

## ADR-001: Project Identity & Package Namespace
- **Date**: 2026-11-20
- **Status**: Accepted
- **Decision**: Rename the project from `PixelAmp` (`com.example.pixelamp`) to **DroidAmp** (`com.droidamp.player`) in `/home/michele/Documents/droidamp`.
- **Rationale**: Better reflects an Android-native Winamp tribute with maximum Winamp ecosystem compatibility, with a clean production application ID from the start.

## ADR-002: Strictly Offline & Pure In-App Hardware Aesthetic
- **Date**: 2026-11-19
- **Status**: Accepted
- **Decision**:
  - Declare zero network (`INTERNET`) permissions, zero analytics, and zero telemetry.
  - Keep the in-app Winamp interface free of album art clutter; expose embedded/folder album art only to Android system media surfaces (lockscreen, notification shade, Bluetooth/Android Auto).
- **Rationale**: Preserves the high-density utilitarian Winamp hardware experience inside the app while still behaving like a first-class Android media player outside it.

## ADR-003: Late-1990s Hardware Skeuomorphism + `DroidAmpSkin` Contract
- **Date**: 2026-11-19
- **Status**: Accepted
- **Decision**:
  - Build the default **DroidAmp Classic** skin in authentic late-1990s hardware skeuomorphism (3D pixel beveling, industrial slate/gunmetal chrome, pitch-black VFD wells, phosphor green readouts, yellow→orange→red spectrum/EQ bars, crisp aliased pixel typography + 7-segment clock).
  - Route every visual component through an abstract `DroidAmpSkin` token + optional sprite/tile contract rather than hardcoding Compose styling.
- **Rationale**: Delivers a mobile-first, thumb-friendly experience in v1 while making future `.wsz` Winamp 2.x skin imports (both Adaptive Mobile mode and optional Classic 275×116 mode) a clean plug-in instead of a UI rewrite.

## ADR-004: Resting Hi-Fi Rack → Full-Screen Expandable Playlist/Library Sheet
- **Date**: 2026-11-19
- **Status**: Accepted
- **Decision**:
  - **Resting state**: Vertical hi-fi rack with **Main Deck** on top, **10-Band EQ** docked directly beneath it, and the top of the **Playlist/Library sheet** peeking at the bottom.
  - **Expanded state**: Tapping the peeking sheet, dragging it up, or pressing `⏏` Eject expands the Playlist/Library **full-screen** (covering Deck and EQ), displaying its own Winamp titlebar and bottom transport micro-bar. Swiping down from the titlebar, dragging down, or pressing Android Back returns to the Deck + EQ rack.
- **Rationale**: Translates Winamp's stacked windows cleanly to portrait phone ergonomics while maximizing vertical scrolling space when managing playlists or browsing folders.

## ADR-005: 10-Band EQ Touch Ergonomics & Swipe Dead-Zone
- **Date**: 2026-11-20
- **Status**: Accepted
- **Decision**:
  - Support **curve drawing** across the 10 frequency bands (`60Hz`–`16kHz`) in addition to single-band vertical adjustment, paired with a live VFD readout tooltip during drag.
  - Maintain a visual/physical separation between `PREAMP` and the 10 bands so curve drawing does not accidentally move `PREAMP`.
  - Leave a dedicated non-interactive bezel buffer between the EQ bottom and the peeking Playlist pull-up handle.
- **Rationale**: Solves narrow 11-column touch accuracy on 360–390dp phone widths and prevents accidental EQ spikes when swiping up to open the Playlist sheet.

## ADR-006: Media3 (ExoPlayer) + Custom In-Process PCM `AudioProcessor`
- **Date**: 2026-11-19
- **Status**: Accepted
- **Decision**: Use AndroidX Media3 (`ExoPlayer` + `MediaSessionService`) with a custom inline PCM `AudioProcessor` chain handling ReplayGain, 10-band biquad EQ + Preamp, independent Volume/Balance, Crossfade, and a lock-free FFT/waveform tap.
- **Rationale**: Provides true 10-band Winamp EQ frequencies and smooth 60fps spectrum/oscilloscope visuals without requiring `RECORD_AUDIO` permission or fighting inconsistent OEM `AudioEffect` limits, while keeping full Android lockscreen/Bluetooth/gapless support.

## ADR-007: Independent Internal DSP Volume with Cold-Launch-Only Reset
- **Date**: 2026-11-20
- **Status**: Accepted
- **Decision**:
  - Keep the Winamp Volume slider (`0..255`) as independent internal DSP gain (separate from Android hardware media stream volume), alongside Balance (`-127..+127`).
  - Reset internal volume to a familiar default level (e.g., ~80%) **only on fresh cold launch when playback is stopped**, never when resuming an active playback session.
- **Rationale**: Preserves desktop Winamp's independent gain mixer while preventing both the "forgotten quiet internal slider" trap on fresh open and accidental ear-blasts when returning to a quietly playing session.

## ADR-008: Mode-Specific Playlist & Library Views with Sheet Memory
- **Date**: 2026-11-20
- **Status**: Accepted
- **Decision**:
  - Give `PLAYLIST` and `LIBRARY` their own tailored controls (zero dead/disabled buttons across modes).
  - Remember which mode (`PLAYLIST` vs. `LIBRARY`) the user left the sheet in when collapsing and reopening (`⏏` Eject on the Deck explicitly opens `LIBRARY`).
  - Differentiate modes visually via distinct Winamp titlebars (`DROIDAMP PLAYLIST` vs. `DROIDAMP LIBRARY`) plus a folder breadcrumb bar in `LIBRARY`.
- **Rationale**: Eliminates mode confusion after unlocking the phone and keeps bottom thumb controls purposeful in both views.

## ADR-009: Winamp-Faithful Select-First Safety in `LIBRARY → FOLDERS`
- **Date**: 2026-11-20
- **Status**: Accepted
- **Decision**:
  - **Top-level Library switcher**: User chooses between `[FOLDERS]` and `[PLAYLISTS]` (`.m3u`/`.m3u8`/`.pls`).
  - **Folder rows**: Single-tap explores into the folder; inline beveled `[▶]` replaces active playlist & plays; inline `[+]` enqueues folder.
  - **Track rows**: Single-tap **selects/highlights** first (never destructive on stray scroll-taps); double-tap or `[▶]` replaces active playlist with selected track(s) and plays (mimicking Winamp); inline `[+]` enqueues.
- **Rationale**: Faithfully mirrors Winamp's folder and single-track playback semantics while protecting users' curated active playlists from accidental touchscreen taps.

## ADR-010: Trimmed Bottom Chrome & Android Gesture Bar Protection
- **Date**: 2026-11-20
- **Status**: Accepted
- **Decision**:
  - Trim redundant bottom controls: `PLAYLIST` uses 4 beveled buttons (`[LIB]`, `[REM]`, `[MISC]`, `[LIST]` — dropping `[+ADD]`), `LIBRARY` uses 3 (`[PL]`, `[▶ PLAY]`, `[+ QUEUE]`), and the bottom transport micro-bar drops the duplicate `⏏` icon.
  - Keep the Playlist bottom transport micro-bar for now, strictly padded above `navigationBarsPadding()` with a non-interactive chassis bottom lip.
- **Rationale**: Prevents accidental Android Home/Recents gesture triggers at the bottom edge while giving every remaining hardware button wider, safer touch targets.

## ADR-011: Dual Storage Architecture (SAF Default + MediaStore Toggle) & Retro Empty State
- **Date**: 2026-11-20
- **Status**: Accepted
- **Decision**:
  - Support both **SAF** (default) and **MediaStore** behind a shared `MusicSourceRepository` interface.
  - Guide first-run users through initial folder linking via a simple late-90s VFD empty state (with a short hint about selecting a Music folder rather than storage root).
- **Rationale**: Maximizes privacy and direct access to folders/`.m3u`/`.eqf`/`.wsz` files by default while smoothing over Android SAF cold-start friction.

## ADR-012: Winamp Config Interop Backend-First; Settings UI Deferred to Final Milestone
- **Date**: 2026-11-20
- **Status**: Accepted
- **Decision**:
  - Implement Winamp import/export engines (`.eqf`, `winamp.q1`/`q2`, `winamp.ini`, `.m3u`/`.m3u8`/`.pls`) cleanly in the domain/data layer without exposing them in the UI yet.
  - Defer the **Settings** UI (post-onboarding folder re-selection, SAF/MediaStore toggle, ReplayGain/Crossfade/Gapless controls, Winamp import/export triggers, `.wsz` skin selector) to the final milestone.
- **Rationale**: Keeps early milestones laser-focused on core audio playback, DSP fidelity, and the hardware rack UX before designing a cohesive Settings interface once all underlying capabilities exist.

## ADR-013: Dependencies & Open-Source Licensing Guardrail
- **Date**: 2026-11-20
- **Status**: Accepted
- **Decision**:
  - Never add third-party libraries beyond the already-specified AndroidX/Media3/DocumentFile/DataStore stack without explicit user approval.
  - Reuse open-source code only when permitted by its license, and record source/license attribution for credits (DroidAmp is non-monetized).
  - All Milestone 2–4 DSP, storage, and Winamp codec implementations were written from scratch in pure Kotlin with zero additional dependencies.
- **Rationale**: Keeps the dependency footprint minimal, offline-auditable, and legally clean.

## ADR-014: Milestone 4 Wiring into Existing M1 Controls (Refines ADR-012)
- **Date**: 2026-11-20
- **Status**: Accepted
- **Decision**:
  - Wire Milestone 4's `AUTO` EQ matching (`AutoEqMatcher` + `AndroidAutoEqCatalogProvider`), expanded 18 classic Winamp presets (`ClassicWinampPresets.ALL`), and `[LIST] -> SAVE .M3U8` export into the existing Milestone 1 Deck/EQ/Playlist buttons immediately.
  - Keep the dedicated **Settings** UI deferred to Milestone 6.
- **Rationale**: Eliminates dead stubs on already-built Milestone 1 controls (`AUTO`, `PRESETS`, `[LIST] -> Save`) so users can exercise real Winamp interop immediately without prematurely building Settings chrome.

## ADR-015: Pre-Settings DSP Defaults & DataStore Persistence Scope
- **Date**: 2026-11-20
- **Status**: Accepted *(Autonomous team decision authorized during M2–M4 interview)*
- **Decision**:
  - **Default `DspSettings`**: `gaplessEnabled = true`, `crossfadeSeconds = 0` (off; configurable `0..12s`), `replayGainMode = ReplayGainMode.OFF`, `replayGainPreampDb = 0f` (`-15..+15 dB`), `DEFAULT_COLD_LAUNCH_VOLUME = 204` (~80% on 40 dB logarithmic taper).
  - **Persistence via DataStore Preferences** (`DspPreferencesStore` & `StoragePreferencesStore`, with `ReplaceFileCorruptionHandler` fallback): persist SAF tree URIs, DSP settings, EQ state, balance, shuffle, repeat mode, visualizer mode, and time display mode across restarts. Keep the active playback queue in-memory until Milestone 6; continue resetting internal volume to `204` only on cold launch when `STOPPED`.
- **Rationale**: Delivers safe, studio-neutral audio out of the box (gapless on, no surprise crossfading or ReplayGain shifts) and remembers user preferences across restarts without over-engineering queue serialization before Milestone 6.

## ADR-016: Process-Scoped Manual DI Container & Media3 Foreground Service Ownership
- **Date**: 2026-11-20
- **Status**: Accepted *(Autonomous engineering decision during M2–M4)*
- **Decision**:
  - Use a lightweight process-scoped `DroidAmpAppContainer` singleton (no Hilt/Dagger dependency) to own `Media3AudioEngineController`, `SwitchingMusicSourceRepository`, and Winamp codecs across Activity recreations.
  - Declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `READ_MEDIA_AUDIO`, and `READ_EXTERNAL_STORAGE (maxSdkVersion="32")` in `AndroidManifest.xml` while retaining `ACCESS_NETWORK_STATE tools:node="remove"`.
  - Register `MediaSession` via `addSession(createdSession)` in `DroidAmpPlaybackService.onCreate()` and delegate foreground promotion solely to Media3's `MediaNotificationManager` (avoiding manual `ContextCompat.startForegroundService()` calls that caused a foreground-service ANR on device).
  - Add `/The Doors - L.A. Woman.mp3` to `.gitignore` so the local verification fixture stays untracked.
- **Rationale**: Keeps dependency count at zero beyond AndroidX/Media3, ensures MediaStore is ready behind the storage abstraction while SAF stays default, and guarantees ANR-free Android foreground media playback.

## ADR-017: Copy Split — `QUEUE` (Active Playback) vs. `PLAYLISTS` (Saved Files)
- **Date**: 2026-11-20
- **Status**: Accepted *(User Testing #1 on Pixel 11 Pro)*
- **Decision**:
  - Rename the active playback list throughout the UI from `PLAYLIST` / `PL` to **`QUEUE`** (`DROIDAMP QUEUE`, Deck `QUEUE` toggle, bottom `[QUEUE (N)]` tab).
  - Reserve **`PLAYLISTS`** exclusively for saved `.m3u` / `.m3u8` / `.pls` files inside `LIBRARY`.
- **Rationale**: Eliminates terminology collision surfaced in User Testing #1 where users confused the live playback queue with saved `.m3u8`/`.pls` playlist files.

## ADR-018: 1:1 Finger-Tracking Drawer Physics & Option A/B Resting Prototype
- **Date**: 2026-11-20
- **Status**: Accepted *(User Testing #1 + `/grill-me`)*
- **Decision**:
  - Replace threshold-only `AnimatedVisibility` sheet transitions with a continuous 1:1 finger-tracking vertical sheet (`Animatable` + velocity fling).
  - Keep the collapsed peek surface **non-scrollable** so the entire peek area drags 1:1 with zero drag-vs-scroll conflict, and always reflect where the user left off (`QUEUE` vs. `LIBRARY`).
  - When expanded, allow collapsing 1:1 by dragging down from the titlebar **or** pulling down at scroll-top.
  - Prototype both **Option A (Slim Glance Lip)** and **Option B (2-Tab Glance Peek)** behind a temporary titlebar switch (`A/B`) for User Test #2, keeping **EQ open by default** in both.
- **Rationale**: Restores physical skeuomorphic trust, eliminates the confusing snap-back to queue when closing from Library, and enables rapid real-device A/B validation.

## ADR-019: Bottom Thumb-Zone `[QUEUE (N)] | [LIBRARY]` Tabs + Horizontal Swipe
- **Date**: 2026-11-20
- **Status**: Accepted *(`/grill-me`)*
- **Decision**:
  - Place the primary **`[QUEUE (N)]` | `[LIBRARY]`** tab switcher in the **bottom thumb zone** of the expanded drawer and wire `HorizontalPager` so swiping left/right switches pages.
  - Stack the expanded drawer bottom chrome top-to-bottom above `navigationBarsPadding()` + bottom lip as: **(1) Context Actions** → **(2) `[QUEUE (N)] | [LIBRARY]` tabs** → **(3) Compact Micro-transport bar**.
  - Rename the Deck's 4th LED toggle to **`QUEUE`** (opens drawer to `QUEUE`), while **`⏏`** opens directly to `LIBRARY`.
- **Rationale**: Optimizes one-handed reachability on tall modern phones while removing redundant `[LIB]`/`[PL]` buttons from the action bars.

## ADR-020: iPod-Style Library Root, Android Back Step-Up, Contextual Selection & In-Drawer Feedback
- **Date**: 2026-11-20
- **Status**: Accepted *(User Testing #1 + `/grill-me`)*
- **Decision**:
  - Replace nested double tab bars with an **iPod-style `LIBRARY` root** (`> [DIR] FOLDERS`, `> [LST] PLAYLISTS`) that remembers the user's last drill-down location.
  - Pressing Android **Back** inside `LIBRARY` steps **up one level** before collapsing the drawer at `LIBRARY` root (or anywhere in `QUEUE`).
  - Hide/disable Library's bottom selection bar when 0 items are selected; show `[▶ PLAY (N)] [+ QUEUE (N)] [✕]` contextually when 1+ items are selected.
  - Keep strict Winamp single-track `[▶]` / double-tap replace-play (switching to `QUEUE`); keep `+` in `LIBRARY` with 3 in-drawer feedback cues (bottom `[QUEUE (N)]` tab counter flash, 2s breadcrumb confirmation, `[Q×N]` track row badge + haptic tick).
  - Add a retro confirmation dialog on `QUEUE → [REM] → CLEAR QUEUE` (no extra LCD bar in Queue) and a retro VFD `Save Queue as Playlist` naming dialog (`.m3u8` / `.pls`) from `QUEUE → [LIST] → SAVE AS PLAYLIST...`.
- **Rationale**: Prevents accidental bulk-folder queuing, prevents duplicate `+` taps when the Deck VFD is covered, and delivers a clean Winamp-faithful queue/playlist creation loop.

## ADR-021: Carved Hardware Glyphs, Taller 60/40 Sliders, EQ Back-Gesture Exclusion & Smooth Visualizer
- **Date**: 2026-11-20
- **Status**: Accepted *(User Testing #1 + `/grill-me`)*
- **Decision**:
  - Replace Unicode text transport & VFD status glyphs with pixel-aligned Canvas hardware glyphs routed through `DroidAmpSkin`.
  - Keep Winamp's classic 60/40 side-by-side `VOL` + `BAL` layout with taller 24–26dp slider tracks/thumbs.
  - Apply `Modifier.systemGestureExclusion()` + horizontal bezel padding to the EQ slider bank and add transient VFD self-narration for `AUTO` EQ toggle/matches.
  - Upgrade `LockFreeAudioRingBuffer` + `VisualizerTapProcessor` to a cursor-tracked playback window so the VFD waveform/spectrum updates smoothly at 60–120fps across ExoPlayer decode bursts.
- **Rationale**: Directly resolves every hardware-fidelity and gesture ergonomics defect reported on Pixel 11 Pro.

## ADR-022: OG Winamp `.wsz` Skin Component Reuse Goal (Deferred to Milestone 5)
- **Date**: 2026-11-20
- **Status**: Logged *(Milestone 5 requirement)*
- **Decision**:
  - Users should be able to load real classic Winamp 2.x `.wsz` archives and reuse most of their visual components (sprites, `pledit.txt`, `viscolor.txt`, chassis/slider assets), accepting light mobile layout adaptation where needed.
- **Rationale**: Logged early so all UX v2 changes keep `DroidAmpSkin` contracts and classic proportions `.wsz`-compatible without prematurely implementing Milestone 5.

## ADR-023: Stereo-Tower Camera Pan, Locked Option B Layout & Zero-Shift Micro-Polish
- **Date**: 2026-11-20
- **Status**: Accepted *(User Testing #2 on Pixel 11 Pro)*
- **Decision**:
  - **Lock Option B** (Balanced Rack + Live 2-Tab Bottom Panel) and remove the temporary `UI:A/UI:B` prototype toggle.
  - **Stereo-Tower Camera Pan**: Replace the overlay sheet with a single continuous vertical stereo rack where `Main Deck` + `EQ` + live `QUEUE / LIBRARY` browser translate upward together 1:1 (eliminating mid-drag unmounting bugs, making both Queue and Library 100% live & scrollable in resting state, and matching the physical sensation of panning from the top to the bottom of a hi-fi stereo).
  - **Zero Layout Shift & Ergonomics**: Lock Library breadcrumb/LCD feedback row to fixed `32.dp` height, lock Queue track rows to fixed `38.dp` height, standardize inline `[▶]` and `[+]` buttons to identical `32×28dp` dimensions, and auto-focus + open the soft keyboard when activating `JUMP` filter.
- **Rationale**: Directly resolves every visual jump, gesture interruption, and resting-state usability issue surfaced in User Testing #2.
