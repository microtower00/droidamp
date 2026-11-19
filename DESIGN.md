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

### Resting State (Hi-Fi Rack View)
Stacked vertically from top to bottom with zero wasted space:
1. **Main Deck (top)**:
   - Winamp-style titlebar (`DROIDAMP`) with window controls.
   - Recessed pitch-black VFD well: 7-segment elapsed/remaining clock (tap to toggle), live 60fps FFT Spectrum Analyzer / Oscilloscope (tap to cycle Spectrum → Oscilloscope → Off), scrolling marquee title, `kbps` / `kHz` / `MONO|STEREO` indicators.
   - Independent internal **Volume** (`0..255`, reset to a familiar default such as ~80% **only on fresh cold launch when playback is stopped**, never on resume while audio is playing) and **Balance** (`-127..+127`) sliders.
   - Scrubbable **Position Seekbar**.
   - Classic transport bar (`|<`, `▶`, `||`, `■`, `>|`, `⏏`) + `SHUFFLE` / `REPEAT` / `EQ` / `PL` LED toggle buttons.
2. **10-Band Graphic Equalizer (middle)**:
   - Docked directly beneath the Deck (collapsible via the Deck's `EQ` button).
   - `ON` and `AUTO` LED toggles + `PRESETS` button + live EQ response spline curve.
   - `PREAMP` separated by a clear physical gap from the classic Winamp 10 frequency bands: **60Hz, 170Hz, 310Hz, 600Hz, 1kHz, 3kHz, 6kHz, 12kHz, 14kHz, 16kHz** (-12dB to +12dB).
   - **Touch Ergonomics**: Supports **EQ curve drawing** (dragging horizontally across the 10-band bank smoothly paints the curve without clipping `PREAMP`), vertical single-band fine-tuning, and a transient VFD value readout (e.g., `3KHZ +4.5DB`) while dragging so the thumb never occludes the value.
3. **Peeking Playlist / Library Sheet (bottom)**:
   - Separated from the EQ bottom by a dedicated **non-interactive bezel buffer + tactile pull-up handle** so upward swipes never accidentally drag an EQ slider.
   - Displays the sheet titlebar + peeking top rows at the bottom of the resting view.
   - **Expand to full-screen**: Tap the peeking panel, drag it upward, press `PL` (reopens last mode), or press **`⏏` Eject** (jumps straight to `LIBRARY`).
   - **Sheet Memory**: Remembers whether the user was last in `PLAYLIST` or `LIBRARY` when collapsed and reopened.

### Expanded State (Full-Screen Playlist / Library Sheet)
- Covers both Deck and EQ, giving full vertical space to track/folder browsing.
- **Top Chrome**:
  - Distinct Winamp titlebar (`DROIDAMP PLAYLIST` vs. `DROIDAMP LIBRARY`) — swipe down from titlebar, drag down, or press Android **Back** to return smoothly to the resting Deck + EQ rack.
  - In `LIBRARY` mode, immediately beneath the titlebar: top-level switcher **`[FOLDERS]` | `[PLAYLISTS]`** + current folder breadcrumb strip (e.g., `MUSIC / DAFT PUNK / DISCOVERY`) for instant mode recognition.
- **Mode-Specific Trimmed Bottom Chrome (Zero Dead Buttons)**:
  - **`PLAYLIST` mode (4 beveled buttons)**: `[LIB]` · `[REM]` · `[MISC]` · `[LIST]` (no redundant `[+ADD]`). Tapping `REM`, `MISC`, or `LIST` pops up classic Winamp-style stacked retro micro-menus directly above the button (`REM`: Remove Selected / Crop / Clear; `MISC`: Sort / Reverse / Randomize / Jump-Filter; `LIST`: Save / Load / New), alongside drag-to-reorder on track rows.
  - **`LIBRARY` mode (3 beveled buttons)**: `[PL]` · `[▶ PLAY]` · `[+ QUEUE]` (acting on selected items or current folder).
  - **Bottom transport micro-bar**: Compact `|< ▶ || ■ >|` + `elapsed / total` time counter (no duplicate `⏏` button).
  - **Android Gesture Safety**: Strictly inset above `navigationBarsPadding()` with a non-interactive chassis bottom bezel lip so bottom controls never collide with Android's Home/Recents gesture pill.

### Library View (`LIBRARY`) & Winamp-Faithful Select-First Interaction
- **Inside `[FOLDERS]`**:
  - Displays parent navigation (`../`), subfolders, and individual audio tracks in the current directory.
  - **Folder row**:
    - **Single-tap**: Explore into folder (view subfolders & tracks).
    - **Inline beveled `[▶]`**: Replace active playlist with folder's tracks and start playing.
    - **Inline beveled `[+]`**: Enqueue folder's tracks at the end of the active playlist.
  - **Single Track row (Winamp-faithful + touch-safe)**:
    - **Single-tap**: **Select / highlight** the track (safe while scrolling; supports multi-select without accidentally nuking the active queue).
    - **Double-tap or `[▶]`**: Replace active playlist with selected track(s) and start playing (strict Winamp behavior).
    - **Inline `[+]` / `[+ QUEUE]`**: Enqueue selected track(s) into the active playlist without interrupting playback.
- **Inside `[PLAYLISTS]`**:
  - Lists `.m3u` / `.m3u8` / `.pls` files discovered in storage or saved internally, with tap to inspect/select, `[▶]` to load & play, `[+]` to enqueue.

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
