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
