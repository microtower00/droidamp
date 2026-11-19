# DroidAmp — Product & Architecture Design

> A strictly offline, Winamp-inspired Android local music player engineered for mobile ergonomics, late-1990s hardware skeuomorphism, and deep compatibility with classic Winamp files and skins.

- **Package / Application ID**: `com.droidamp.player`
- **Privacy / Network**: Strictly offline — zero `INTERNET` permission, zero analytics, zero telemetry.
- **In-App Purity**: Pure Winamp hardware UI inside the app (no album art clutter). Embedded / folder album art is exposed only to Android system surfaces (lockscreen, media notification, Bluetooth/Android Auto).

---

## 1. Visual Language & Skin Architecture

### Built-in Default Skin ("DroidAmp Classic")
Modeled directly after late-1990s hi-fi stereo racks, car stereos, and Winamp 2.x hardware skeuomorphism:
- **3D Pixel Beveling**: Sharp light-and-dark 1px/2px highlight & shadow borders for raised buttons, embossed chassis frames, and recessed display wells.
- **Chassis Palette**: Industrial slate blues, gunmetal grays, metallic silvers vs. pitch-black recessed display wells.
- **VFD / LED Accents**: Phosphor green (`#00FF00` / `#28E828`) for timers, bitrate/sample-rate, playlist entries, and active LEDs; warm yellow → orange → red gradients for spectrum analyzer bars and EQ slider tracks.
- **Crisp Pixel & Bitmap Typography**: Aliased micro pixel labels (all-caps: `SHUFFLE`, `REPEAT`, `PREAMP`, `PRESETS`, `AUTO`) + classic 7-segment digital clock numerals.
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

### Resting State (Hi-Fi Rack View)
Stacked vertically from top to bottom with zero gaps:
1. **Main Deck (top)**:
   - Winamp-style titlebar (`DROIDAMP`) with compact menu (`PREFS` / `CONFIG`) & window controls.
   - Recessed pitch-black VFD well: 7-segment elapsed/remaining clock (tap to toggle), live 60fps FFT Spectrum Analyzer / Oscilloscope (tap to cycle Spectrum → Oscilloscope → Off), scrolling marquee title, `kbps` / `kHz` / `MONO|STEREO` indicators.
   - Independent internal **Volume** (`0..255`) and **Balance** (`-127..+127`) sliders.
   - Scrubbable **Position Seekbar**.
   - Classic transport bar (`|<`, `▶`, `||`, `■`, `>|`, `⏏`) + `SHUFFLE` / `REPEAT` / `EQ` / `PL` LED toggle buttons.
2. **10-Band Graphic Equalizer (middle)**:
   - Docked directly beneath the Deck (collapsible via the Deck's `EQ` button).
   - `ON` and `AUTO` LED toggles + `PRESETS` button (load/save presets & `.eqf`/`winamp.q1` import/export) + live EQ response spline curve.
   - `PREAMP` + classic Winamp 10 frequency bands: **60Hz, 170Hz, 310Hz, 600Hz, 1kHz, 3kHz, 6kHz, 12kHz, 14kHz, 16kHz** (-12dB to +12dB).
3. **Peeking Playlist / Library Sheet (bottom)**:
   - Top titlebar (`DROIDAMP PLAYLIST`) + peeking top rows sit visibly below the EQ.
   - **Expand to full-screen**: Tap the peeking panel, drag it upward, or press **`⏏` Eject** (opens straight to Library) / **`PL`**.

### Expanded State (Full-Screen Playlist / Library Sheet)
- Covers both Deck and EQ, giving full vertical space to track/folder browsing.
- **Top**: Winamp-style titlebar (`DROIDAMP PLAYLIST` or `DROIDAMP LIBRARY`) — swipe down from titlebar, drag down, or press Android **Back** to return smoothly to the resting Deck + EQ rack.
- **Bottom Chrome**:
  - **5 crisp beveled Winamp buttons**: `[LIB/PL]` (toggle between Active Playlist and Library), `[+ADD]`, `[REM]`, `[MISC]`, `[LIST]`.
  - Tapping `REM`, `MISC`, or `LIST` opens classic Winamp-style **stacked retro pop-up micro-menus** anchored directly above the button (e.g., `REM`: Remove Selected / Crop / Clear; `MISC`: Sort by Name/Path/Track#, Reverse, Randomize, Jump/Filter; `LIST`: Save `.m3u8`/`.pls`, Load Playlist, New).
  - **Bottom transport micro-bar**: Classic Winamp playlist mini-transport (`|< ▶ || ■ >| ⏏`) + current/total playlist time counter.

### Library View (`LIB`) & Winamp-Faithful Folder/Playlist Workflow
- **Top-Level Switcher**: **`[FOLDERS]`** | **`[PLAYLISTS]`** (`.m3u`, `.m3u8`, `.pls`, saved playlists).
- **Inside `[FOLDERS]`**:
  - Displays parent navigation (`../`), subfolders, and individual audio tracks in the current directory.
  - **Folder row**:
    - **Tap**: Explore into folder (view subfolders & tracks).
    - **Inline beveled `[▶]`**: Replace active playlist with folder's tracks and start playing immediately.
    - **Inline beveled `[+]`**: Enqueue folder's tracks at the end of the active playlist.
    - *(Plus long-press / swipe context actions for recursive play/enqueue.)*
  - **Single Track row (Winamp-faithful behavior)**:
    - **Tap / `[▶]`**: Replace active playlist with that single track and start playing (strict Winamp `+ FILE` / Play behavior).
    - **Inline `[+]`**: Enqueue that single track into the active playlist without interrupting playback.
- **Inside `[PLAYLISTS]`**:
  - Lists `.m3u` / `.m3u8` / `.pls` files discovered in storage or imported, with tap to inspect, `[▶]` to load & play, `[+]` to enqueue.

---

## 3. Storage & Library Discovery

- **Toggleable Backend in `PREFS`**:
  - **SAF (Storage Access Framework) — Default**: User picks one or more root music folders (e.g., `/Music`, SD card) via `ACTION_OPEN_DOCUMENT_TREE` with persisted URI permissions. Reads directory hierarchy, audio files, `.m3u`/`.m3u8`/`.pls`, `.eqf`, and `.wsz` directly without broad media permissions.
  - **MediaStore — Optional Toggle**: Uses `READ_MEDIA_AUDIO` (or `READ_EXTERNAL_STORAGE` on API 24–32) to auto-discover all audio folders on the device immediately.
- Both backends implement a shared `MusicSourceRepository` interface so the Library UI, Playlist Engine, and Playback Service remain completely storage-agnostic.

---

## 4. Audio Engine & DSP Pipeline

- **Playback Core**: **AndroidX Media3 (ExoPlayer)** hosted inside a foreground `MediaSessionService` for rock-solid background playback, gapless transitions, lockscreen/notification artwork & controls, Bluetooth AVRCP metadata, audio focus, and noisy-audio (headphone unplug) handling.
- **Custom PCM `AudioProcessor` Chain** (runs inline on decoded float/16-bit PCM — **no `RECORD_AUDIO` permission needed**):
  1. **ReplayGain Processor**: Applies Track/Album ReplayGain metadata (ID3 `TXXX:REPLAYGAIN_*` / Vorbis comments) + configurable RG preamp & clipping prevention.
  2. **10-Band Biquad Peaking EQ + Preamp**: True Winamp center frequencies (`60, 170, 310, 600, 1k, 3k, 6k, 12k, 14k, 16k Hz`), ±12dB range, bypassable via `ON`, auto-switched per folder/track when `AUTO` is enabled.
  3. **Internal Gain & Stereo Balance Processor**: Independent Winamp volume (`0..255` logarithmic taper, separate from Android hardware media volume) + L/R stereo balance (`-127..+127`).
  4. **Crossfade Controller**: Smooth configurable overlap/fade between tracks (0–12s) when enabled, cleanly bypassed for gapless album playback.
  5. **Lock-Free FFT & Waveform Tap**: Feeds a ring buffer for the Compose Canvas VFD visualizer (60fps 19-band Winamp-style spectrum analyzer with falling peak dots + real-time oscilloscope).

---

## 5. Winamp Format Import & Export Compatibility

DroidAmp aims for maximum file-level interoperability with desktop Winamp 2.x/5.x:

| Format | Direction | What It Carries |
| :--- | :--- | :--- |
| **`.eqf`** | Import & Export | Single Winamp EQ preset (299-byte binary: `"Winamp EQ library file v1.1"`, 257-byte name, 10 bands + preamp mapped `0..63` ↔ `+12..-12 dB`). |
| **`winamp.q1`** | Import & Export | Full Winamp EQ preset library (concatenated 299-byte records). |
| **`winamp.q2`** | Import & Export | Per-track / auto-load EQ presets (for the `AUTO` EQ toggle). |
| **`winamp.ini`** | Import & Export | Core `[Winamp]` preferences subset: `volume` (`0..255`), `pan` (`-127..127`), `shuffle`, `repeat`, `timemode`, `eq_use`, `eq_auto`, `eq_data`, `rg_mode`, `rg_preamp`, `crossfade`. |
| **`.m3u` / `.m3u8` / `.pls`** | Import & Export | Classic Winamp playlists (`#EXTM3U`/`#EXTINF` and INI-style `.pls`), resolving relative paths against the active music root. |
| **`.wsz`** *(Phase 2)* | Import | Classic Winamp 2.x skin archives (`*.bmp`, `pledit.txt`, `viscolor.txt`, `region.txt`) mapped onto `DroidAmpSkin`. |

---

## 6. Phased Implementation Roadmap

1. **Milestone 1 — Foundation, Skin Contract & Retro Hardware UI Shell**:
   - Add Media3 + DataStore + Coroutines dependencies.
   - Implement `DroidAmpSkin` contract + built-in **DroidAmp Classic** skeuomorphic design system (3D bevel primitives, 7-segment clock, bitmap-style typography, VFD spectrum/oscilloscope canvas, custom hardware sliders).
   - Build the interactive **Deck + Collapsible EQ + Expandable Full-Screen Playlist/Library Sheet** shell with gestures, micro-menus, and bottom transport micro-bar.
2. **Milestone 2 — Media3 Audio Service & Custom DSP Pipeline**:
   - Foreground `MediaSessionService` with lockscreen/notification artwork & Bluetooth controls.
   - Custom PCM `AudioProcessor` (10-band Winamp EQ + Preamp, independent Volume/Balance, ReplayGain, lock-free FFT/oscilloscope tap, gapless & crossfade).
3. **Milestone 3 — Storage (SAF Default + MediaStore Toggle) & Live Playlist/Library**:
   - `MusicSourceRepository` with SAF tree scanner and MediaStore scanner.
   - Folder & Playlist browser (`[FOLDERS]` | `[PLAYLISTS]`) with `[▶]` / `[+]` actions, drag-to-reorder playlist editor, `REM`/`MISC`/`LIST` micro-menus, and Jump-to-Track filter.
4. **Milestone 4 — Winamp Config & Preset Interop + `PREFS` Window**:
   - `.eqf` / `winamp.q1` / `winamp.q2`, `winamp.ini`, and `.m3u`/`.m3u8`/`.pls` parsers & exporters.
   - Built-in classic Winamp EQ presets + `AUTO` per-folder/track preset matching.
   - Retro `PREFS` window (SAF/MediaStore toggle, ReplayGain, Crossfade, Gapless, Import/Export Winamp Configs).
5. **Milestone 5 — `.wsz` Skin Importer (Customizability Unlock)**:
   - Deterministic `.wsz` zip parser slicing BMP sprites + `pledit.txt` + `viscolor.txt` directly into `DroidAmpSkin` (Adaptive Mobile mode + optional Classic Deck mode).
