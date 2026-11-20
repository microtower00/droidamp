# DroidAmp — Product & Architecture Design

> A strictly offline, Winamp-inspired Android local music player engineered for mobile ergonomics, late-1990s hardware skeuomorphism, and deep compatibility with classic Winamp files and skins.

- **Package / Application ID**: `com.droidamp.player`
- **Privacy / Network**: Strictly offline — zero `INTERNET` permission, zero analytics, zero telemetry.
- **In-App Purity**: Pure Winamp hardware UI inside the app (no album art clutter). Embedded / folder album art is exposed only to Android system surfaces (lockscreen, media notification, Bluetooth/Android Auto).
- **Decision Log**: See [DECISIONS.md](DECISIONS.md) for the rationale behind every product, UX, and architectural choice.

---

## 1. Visual Language & Skin Architecture

### Built-in Default Skin ("DroidAmp Classic")
Modeled directly after late-1990s hi-fi stereo racks, car stereos, and Winamp 2.x hardware skeuomorphism:
- **3D Pixel Beveling**: Sharp light-and-dark 1px/2px highlight & shadow borders for raised buttons, embossed chassis frames, and recessed display wells.
- **Chassis Palette**: Industrial slate blues, gunmetal grays, metallic silvers vs. pitch-black recessed display wells.
- **VFD / LED Accents**: Phosphor green (`#00FF00` / `#28F028`) for timers, bitrate/sample-rate, playlist entries, and active LEDs; warm yellow → orange → red gradients for spectrum analyzer bars and EQ slider tracks.
- **Crisp Pixel & Bitmap Typography**: Aliased micro pixel labels (all-caps: `SHUFFLE`, `REPEAT`, `PREAMP`, `PRESETS`, `AUTO`) + classic 7-segment digital clock numerals, enforcing a mobile legibility floor (~9–10sp micro-labels, 11–12sp buttons/track rows).
- **High-Density Modular Rack**: Tightly docked panels with zero wasted whitespace and tactile micro-controls sized for reliable thumb interaction.

### Future-Proof `DroidAmpSkin` Contract
Every UI component renders through an abstract `DroidAmpSkin` interface (never hardcoded Compose colors/shapes):
- **Palette Tokens**: chassis surfaces, highlight/shadow bevel pairs, VFD LCD colors, playlist colors (`Normal`, `Current`, `NormalBG`, `SelectedBG`), 24-entry visualizer color LUT (`viscolor.txt` 1:1 mapping).
- **Optional Sprite / Tile Assets**: transport button sprites (`cbuttons`), 7-segment digits (`nums_ex`), bitmap font (`text`), slider tracks/thumbs (`volume`, `balance`, `posbar`, `eqmain`), repeatable playlist frame tiles (`pledit`).
- **Two Future `.wsz` Rendering Paths**:
  1. **Adaptive `.wsz` Mode**: Extracts colors, 24-step vis LUT, sprites, and `pledit.bmp` tiles from any Winamp 2.x `.wsz` zip to skin DroidAmp's touch-friendly mobile layout (rendered with `FilterQuality.None` nearest-neighbor scaling).
  2. **Optional Classic 275×116 Deck Mode**: Renders `main.bmp` at exact classic proportions for heavily illustrated skins.

---

## 2. Screen Layout & Interaction Model

### Two-Plane Architecture
1. **Top Plane — The Instrument (Main Deck + Collapsible 10-Band EQ, open by default)**:
   - **Main Deck**:
     - Winamp-style titlebar (`DROIDAMP`) with window controls.
     - Recessed pitch-black VFD well: carved pixel/vector Play/Pause/Stop status glyph + 7-segment elapsed/remaining clock (tap to toggle), live 60–120fps audio-synced FFT Spectrum Analyzer / Oscilloscope (tap to cycle Spectrum → Oscilloscope → Off), scrolling marquee title, `kbps` / `kHz` / `MONO|STEREO` indicators.
     - Independent internal **Volume** (`0..255`, reset to ~80% **only on fresh cold launch when playback is stopped**) and **Balance** (`-127..+127`) sliders in classic 60/40 side-by-side layout with taller 24–26dp touch targets.
     - Scrubbable **Position Seekbar**.
     - Classic carved-glyph transport bar (`|<`, `▶`, `❚❚`, `■`, `>|`, `⏏`) + `SHUFFLE` / `REPEAT` / `EQ` / `QUEUE` LED toggle buttons (`QUEUE` opens drawer to `QUEUE`; `⏏` opens drawer directly to `LIBRARY`).
   - **10-Band Graphic Equalizer**:
     - Docked directly beneath the Deck (open by default, collapsible via `EQ`).
     - `ON` and `AUTO` LED toggles + `PRESETS` button + live EQ response spline curve with transient VFD self-narration on drag or `AUTO` EQ toggle/match (e.g., `AUTO EQ: ROCK`, `3KHZ +4.5DB`).
     - `PREAMP` separated by a clear physical gap from the 10 frequency bands (**60Hz–16kHz**, -12dB to +12dB).
     - **Touch & Gesture Ergonomics**: Supports **EQ curve drawing** horizontally across the 10-band bank, wrapped in Android `systemGestureExclusion()` + horizontal bezel padding so edge drags never trigger Android Back.
2. **Bottom Plane — Unified Stereo-Tower Drawer (`QUEUE` & `LIBRARY`)**:
   - Docked beneath the EQ with a dedicated **non-interactive bezel buffer** so upward drags never clip EQ sliders.
   - **Stereo-Tower Camera Pan (1:1 Continuous Motion)**: Rather than an overlay covering the Deck, the entire stereo stack (`Main Deck` + `EQ` + live `QUEUE / LIBRARY` browser) pans upward together 1:1 as one physical unit—moving the viewport smoothly from the top of the stereo down to the full-screen browser.
   - **Resting State (Balanced Rack + Live 2-Tab Browser)**: Shows `Main Deck` + `EQ` (open by default) above the **live, scrollable, interactive** `QUEUE / LIBRARY` panel (all queued tracks scrollable; Library fully browsable) with the bottom **`[QUEUE (N)] | [LIBRARY]`** tabs anchored above the gesture lip.
   - **Expanded (Full-Screen) State**: `Main Deck` & `EQ` pan smoothly out of view; dragging down from the titlebar **or** pulling down at scroll-top pans the stereo tower back down 1:1.
   - **Navigation & Thumb-Zone Bottom Stack** (ordered top → bottom above `navigationBarsPadding()` + bottom lip):
     1. **Context Actions** (shown when expanded):
        - **`QUEUE` mode (3 buttons)**: `[REM]` · `[MISC]` · `[LIST]` (strict Winamp menus, retro confirmation dialog on `CLEAR QUEUE`, auto-focusing `JUMP` filter with keyboard, and `SAVE AS PLAYLIST...` naming dialog for `.m3u8` / `.pls`).
        - **`LIBRARY` mode**: Hidden when 0 items are selected; appears when 1+ items are selected (`[▶ PLAY (N)]` · `[+ QUEUE (N)]` · `[✕]`). Whole-folder play/queue lives on folder rows & the fixed-height breadcrumb header.
     2. **Primary Tabs**: **`[QUEUE (N)]`** \| **`[LIBRARY]`** (also switchable via horizontal swipe between pages).
     3. **Bottom transport micro-bar** (shown when expanded): Compact carved-glyph `|< ▶ ❚❚ ■ >|` + `elapsed / total` time counter (no duplicate `⏏`).

### Library View (`LIBRARY`) — iPod-Style Hierarchy & Select-First Safety
- **iPod-Style Root Menu**: Top-level choices **`> [DIR] FOLDERS`** and **`> [LST] PLAYLISTS`** with last-location memory. Android **Back** steps up one level (`DOORS` → `FOLDERS` → `LIBRARY` root), then collapses the drawer at root (or anywhere in `QUEUE`).
- **Inside `FOLDERS`**:
  - Breadcrumb header (`LIBRARY / FOLDERS / ...`) + parent `[..]`, subfolders, and audio tracks.
  - **Folder row**: Single-tap explores; inline `[▶]` replace-plays & switches to `QUEUE`; inline `[+]` enqueues & stays in `LIBRARY`.
  - **Track row**: Single-tap **selects** (multi-select safe); double-tap or `[▶]` **strict Winamp single-track replace-play** & switches to `QUEUE`; inline `[+]` enqueues & stays in `LIBRARY` with 3 in-drawer feedback cues (bottom `[QUEUE (N)]` tab counter flash, 2s breadcrumb confirmation, `[Q×N]` row badge + haptic tick).
- **Inside `PLAYLISTS`**:
  - Lists `.m3u` / `.m3u8` / `.pls` files discovered in storage or saved via `QUEUE → [LIST] → SAVE AS PLAYLIST...`, with tap to select, `[▶]` to load & play, `[+]` to enqueue.

### First-Run Onboarding (Empty State)
- On cold start before any folder is linked, the recessed VFD/Playlist well displays a simple late-90s hardware empty state (`NO MUSIC FOLDER LINKED`, one-line hint reminding the user to select their Music folder rather than Android storage root, and a single beveled **`[ SELECT MUSIC FOLDER ]`** button triggering SAF `ACTION_OPEN_DOCUMENT_TREE`).

---

## 3. Storage & Library Discovery

- **Toggleable Backend Architecture**:
  - **SAF (Storage Access Framework) — Default**: User picks root music folder(s) via `ACTION_OPEN_DOCUMENT_TREE` with persisted URI permissions. Reads directory hierarchy, audio files, `.m3u`/`.m3u8`/`.pls`, `.eqf`, and `.wsz` directly without broad media permissions.
  - **MediaStore — Optional Backend**: Uses `READ_MEDIA_AUDIO` (or `READ_EXTERNAL_STORAGE` on API 24–32) to auto-discover all device audio folders.
- Both backends implement a shared `MusicSourceRepository` interface so Library, Playlist, and Playback remain storage-agnostic.
- Post-onboarding folder management and the SAF/MediaStore toggle will live in the **Settings** UI (designed and wired in the final milestone).

---

## 4. Audio Engine & DSP Pipeline

- **Playback Core**: **AndroidX Media3 (ExoPlayer)** hosted inside a foreground `MediaSessionService` for background playback, gapless transitions, lockscreen/notification artwork & controls, Bluetooth AVRCP metadata, audio focus, and headphone-unplug handling.
- **Custom PCM `AudioProcessor` Chain** (inline on decoded PCM — **no `RECORD_AUDIO` permission needed**):
  1. **ReplayGain Processor**: Applies Track/Album ReplayGain metadata (ID3 `TXXX:REPLAYGAIN_*` / Vorbis comments) + preamp & clipping prevention.
  2. **10-Band Biquad Peaking EQ + Preamp**: True Winamp center frequencies (`60, 170, 310, 600, 1k, 3k, 6k, 12k, 14k, 16k Hz`), ±12dB range, bypassable via `ON`, auto-switched per folder/track when `AUTO` is enabled.
  3. **Internal Gain & Stereo Balance Processor**: Independent Winamp volume (`0..255` logarithmic taper, cold-launch-only reset when stopped) + L/R stereo balance (`-127..+127`).
  4. **Crossfade Controller**: Smooth configurable overlap/fade (0–12s) when enabled, cleanly bypassed for gapless playback.
  5. **Lock-Free FFT & Waveform Tap**: Ring buffer feeding the Compose Canvas VFD visualizer (60fps 19-band Winamp-style spectrum analyzer with falling peak dots + real-time oscilloscope).

---

## 5. Winamp Format Import & Export Compatibility

Core parsers and serializers are implemented in the domain/data layer early (thoroughly unit-tested), with user-facing UI triggers deferred to the final **Settings** milestone:

| Format | Direction | What It Carries |
| :--- | :--- | :--- |
| **`.eqf`** | Import & Export | Single Winamp EQ preset (299-byte binary: `"Winamp EQ library file v1.1"`, 257-byte name, 10 bands + preamp mapped `0..63` ↔ `+12..-12 dB`). |
| **`winamp.q1`** | Import & Export | Full Winamp EQ preset library (concatenated 299-byte records). |
| **`winamp.q2`** | Import & Export | Per-track / auto-load EQ presets (for the `AUTO` EQ toggle). |
| **`winamp.ini`** | Import & Export | Core `[Winamp]` preferences subset: `volume` (`0..255`), `pan` (`-127..127`), `shuffle`, `repeat`, `timemode`, `eq_use`, `eq_auto`, `eq_data`, `rg_mode`, `rg_preamp`, `crossfade`. |
| **`.m3u` / `.m3u8` / `.pls`** | Import & Export | Classic Winamp playlists (`#EXTM3U`/`#EXTINF` and INI-style `.pls`), resolving relative paths against the active music root. |
| **`.wsz`** *(Skin Milestone)* | Import | Classic Winamp 2.x skin archives (`*.bmp`, `pledit.txt`, `viscolor.txt`, `region.txt`) mapped onto `DroidAmpSkin`. |

---

## 6. Phased Implementation Roadmap

1. **Milestone 1 — Foundation, Skin Contract & Retro Hardware UI Shell**:
   - Add Media3, DataStore, and Coroutines dependencies.
   - Implement `DroidAmpSkin` contract + built-in **DroidAmp Classic** skeuomorphic primitives (3D bevels, 7-segment clock, pixel labels, VFD spectrum/oscilloscope canvas, curve-drawable 10-band EQ with preamp gap & swipe buffer).
   - Build the interactive **Deck + Collapsible EQ + Expandable Full-Screen Playlist/Library Sheet** shell with mode-specific trimmed toolbars, micro-menus, safe gesture insets, and first-run empty state.
2. **Milestone 2 — Media3 Audio Service & Custom DSP Pipeline**:
   - Foreground `MediaSessionService` with lockscreen/notification artwork & Bluetooth controls.
   - Custom PCM `AudioProcessor` (10-band Winamp EQ + Preamp, independent Volume/Balance with cold-start-only reset, ReplayGain, lock-free FFT/oscilloscope tap, gapless & crossfade).
3. **Milestone 3 — Storage (SAF Default + MediaStore Engine) & Live Playlist/Library**:
   - `MusicSourceRepository` with SAF tree scanner and MediaStore scanner.
   - Folder & Playlist browser (`[FOLDERS]` | `[PLAYLISTS]`) with select-first track safety, `[▶]` / `[+]` actions, drag-to-reorder playlist editor, `REM`/`MISC`/`LIST` micro-menus, and Jump-to-Track filter.
4. **Milestone 4 — Winamp Format Engines (Backend Only, Unit-Tested)**:
   - Pure Kotlin `.eqf`, `winamp.q1`/`q2`, `winamp.ini`, and `.m3u`/`.m3u8`/`.pls` importers & exporters + built-in classic Winamp EQ presets + `AUTO` preset matching.
5. **Milestone 5 — `.wsz` Skin Engine (Customizability Unlock)**:
   - Deterministic `.wsz` zip parser slicing BMP sprites + `pledit.txt` + `viscolor.txt` directly into `DroidAmpSkin` (Adaptive Mobile mode + optional Classic Deck mode).
6. **Milestone 6 — Settings UI & Final Integration**:
   - Design and implement the dedicated **Settings** UI (post-onboarding folder picker, SAF/MediaStore toggle, ReplayGain/Crossfade/Gapless controls, cold-launch volume default, Winamp config/EQ import & export UI, and `.wsz` skin chooser).
