# DroidAmp — Settings Roadmap (Milestone 6)

> **Guiding Philosophy**: Let every user dial DroidAmp along a clear spectrum from **Classic 1:1 Winamp Authenticity** to **Modern Mobile-Optimized Ergonomics** — exposing every backend capability already built in Milestones 2–4 alongside mobile-specific hardware controls.

---

## Quick Presets (Top of Settings)

| Preset | Philosophy | What It Sets |
| :--- | :--- | :--- |
| **Classic Winamp (1:1)** | Maximum late-1990s fidelity | Independent internal volume (`0..255`), cold-launch volume reset (`204`), Classic `.wsz` Deck proportions when skin loaded, ReplayGain OFF, Crossfade OFF. |
| **Mobile-Optimized** | Best one-handed phone ergonomics | Hardware Volume Rocker linked to internal app volume, preserve last volume across launches, Adaptive status-bar scrim, Adaptive `.wsz` skin layout. |

---

## 1. Audio & Volume (Backend Ready in Milestone 2)

| Setting | Range / Options | Default | Backend Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Hardware Volume Rocker Mode** | `Independent (Winamp 1:1)` \| `Linked to App Volume` | `Independent` | *Planned (M6)* | When linked, phone volume buttons drive DroidAmp's `0..255` internal slider & flash `VOLUME: XX%` in VFD (foreground `KeyEvent`; optional `VolumeProviderCompat` consideration for lockscreen/Bluetooth). |
| **Cold-Launch Volume Reset** | `Reset to Default (0..255)` \| `Preserve Last Volume` | `Reset to 204 (~80%)` | Wired (`Media3AudioEngineController`) | Only applies on cold start when playback is `STOPPED` (ADR-007). |
| **Gapless Playback** | `ON` \| `OFF` | `ON` | Wired (`DspSettings`, `DroidAmpPlaybackService`) | Seamless album transitions; bypassed automatically when Crossfade > 0. |
| **Crossfade Duration** | `0s (Off)` .. `12s` | `0s` | Wired (`CrossfadeController`, `DspSettings`) | Dual-deck smooth equal-power crossfade. |
| **ReplayGain Mode** | `OFF` \| `TRACK` \| `ALBUM` | `OFF` | Wired (`ReplayGainProcessor`, `ReplayGainTagParser`) | Reads ID3 `TXXX:REPLAYGAIN_*` and Vorbis comments inline in PCM DSP. |
| **ReplayGain Preamp** | `-15.0 dB` .. `+15.0 dB` | `0.0 dB` | Wired (`DspSettings`) | Plus optional clipping prevention toggle. |

---

## 2. Interface & Authenticity

| Setting | Options | Default | Backend Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Winamp Skins (`.wsz`)** | Built-in `DroidAmp Classic` + imported `.wsz` archives | `DroidAmp Classic` | Contract ready (`DroidAmpSkin`); parser in **Milestone 5** | Supports *Adaptive Mobile Mode* and optional *Classic 275×116 Deck Mode* for heavily illustrated OG Winamp skins (ADR-022). |
| **Status Bar Treatment** | `Adaptive Scrim Gradient` \| `Solid Hardware Bezel` | `Adaptive Scrim` | Implemented (UX Round 4) | Adaptive light/dark translucent scrim as EQ pans behind status bar vs. crisp clip below status bar. |
| **Visualizer Mode & Style** | `Spectrum` \| `Oscilloscope` \| `Off` | `Spectrum` | Wired (`VisualizerTapProcessor`) | Also cycleable directly by tapping the VFD well. |
| **Time Display Default** | `Elapsed` \| `Remaining` | `Elapsed` | Wired (`TimeDisplayMode`) | Also toggleable directly by tapping the 7-segment clock. |

---

## 3. Storage & Library (Backend Ready in Milestone 3)

| Setting | Options | Default | Backend Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Storage Engine** | `SAF (Folder Picker)` \| `MediaStore (Auto-Scan)` | `SAF` | Wired (`SwitchingMusicSourceRepository`) | SAF requires zero broad media permissions; MediaStore requests `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`. |
| **Linked SAF Folders** | Add Folder · Remove Folder · Re-scan Library | User-selected | Wired (`SafMusicSourceRepository`, `StoragePreferencesStore`) | Post-onboarding folder management and multi-root support. |

---

## 4. Winamp File Interoperability (Codecs Ready in Milestone 4)

| Format | Direction | Backend Status | User Action in Settings |
| :--- | :--- | :--- | :--- |
| **`.eqf`** | Import & Export | Ready (`BinaryEqfCodec`) | Import single classic Winamp EQ preset or export current EQ curve. |
| **`winamp.q1`** | Import & Export | Ready (`BinaryEqfCodec`) | Import/export full Winamp EQ preset library. |
| **`winamp.q2`** | Import & Export | Ready (`WinampQ2Codec`, `AutoEqMatcher`) | Import/export per-track & per-folder `AUTO` EQ rules. |
| **`winamp.ini`** | Import & Export | Ready (`WinampIniParser`) | Migrate Winamp preferences (`volume`, `pan`, `shuffle`, `repeat`, `eq_*`, `rg_*`, `crossfade`). |
| **`.m3u` / `.m3u8` / `.pls`** | Import & Export | Ready (`WinampPlaylistCodec`, `SavePlaylistDialog`) | Already live in `QUEUE → [LIST]` & `LIBRARY → PLAYLISTS`; Settings can expose default export format & folder. |

---

## 5. Future Milestone Backlog — Multi-Touch Deck Gesture System

> **Goal**: Enable eyes-free, two-finger transport gestures across the Main and Equalizer decks without interfering with single-finger fader or EQ curve precision.

- **2-Finger Horizontal Swipe over Main/EQ Decks**: Swipe right = `FWD` (`Next` / seek forward); Swipe left = `BWD` (`Previous` / seek backward).
- **2-Finger Double-Tap over Main/EQ Decks**: Toggle `Play / Pause`.
- **Multi-Touch Disambiguation**: Watching `pointerCount == 2` cleanly cancels in-flight 1-finger slider/EQ curve drags before committing to the multi-touch gesture, plus a future toggle under `CONTROLS` in Settings to enable or disable deck multi-touch gestures.
