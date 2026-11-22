# DroidAmp Augmented `.wsz` Skin Tooling (`wsz-augment`)

Offline Python 3 tooling (`GPL-3.0-or-later`, zero network access, stdlib-first with automatic `Pillow` support for exotic/RLE BMPs) for upgrading 1999 Winamp 2.x `.wsz` archives into seam-free **DroidAmp Augmented `.wsz`** skins (`ADR-028`) and deterministically generating DroidAmp's built-in showcase skins.

---

## 1. Augmented `.wsz` Format (`ADR-028`)

An Augmented `.wsz` archive is a standard ZIP archive using the `.wsz` extension that retains classic Winamp files (`viscolor.txt`, `pledit.txt`, `main.bmp`, etc.) while adding:

1. **`droidamp.json`** at the archive root (`schemaVersion: 1`) defining exact DroidAmp hardware palette tokens, bevel geometry (`shapeStyle`), and optional sprite overrides.
2. **`sprites/*.png`** 8-bit RGBA PNG assets with true alpha-channel transparency (eliminating rectangular 24-bit BMP corner seams on rounded/chamfered mobile buttons and providing dedicated sprites for mobile-native controls).

### `droidamp.json` Schema (`schemaVersion: 1`)

Every palette key matches `DroidAmpSkin.kt` (`ChassisPalette`, `BevelPalette`, `VfdPalette`, `PlaylistPalette`, and 24-entry `VisualizerPalette`) 1:1. Omitted fields fall back cleanly per-token inside DroidAmp's `DroidAmpSkinManifestParser`.

```json
{
  "schemaVersion": 1,
  "id": "amber-hifi-rack",
  "name": "Amber Hi-Fi Rack",
  "author": "DroidAmp Studio",
  "inheritLegacyBmpSprites": false,
  "shapeStyle": {
    "cornerRadiusDp": 2.0,
    "bevelThicknessDp": 1.5,
    "doubleStepBevel": true
  },
  "palette": {
    "chassis": {
      "windowBackground": "#14100C",
      "panelSurface": "#2C251E",
      "panelSurfaceAlt": "#221C16",
      "titleBarActiveStart": "#3A2C1D",
      "titleBarActiveEnd": "#523E29",
      "titleBarStripeLight": "#9E825E",
      "titleBarStripeDark": "#1C150E",
      "titleBarText": "#FFE8BE",
      "divider": "#140F0A",
      "bottomLip": "#1E1813",
      "labelText": "#EAD4B2",
      "labelMuted": "#A89274"
    },
    "bevel": {
      "highlightOuter": "#7A6750",
      "highlightInner": "#564838",
      "shadowInner": "#1B1611",
      "shadowOuter": "#0C0906",
      "wellBorderDark": "#080604",
      "wellBorderLight": "#645340",
      "buttonFace": "#382F25",
      "buttonFacePressed": "#211B15",
      "buttonText": "#F4DEC0",
      "buttonTextActive": "#FFC048",
      "sliderThumbTop": "#B89B74",
      "sliderThumbFace": "#6E5B43",
      "sliderThumbGroove": "#201912"
    },
    "vfd": {
      "wellBackground": "#090603",
      "phosphorPrimary": "#FFB028",
      "phosphorBright": "#FFD878",
      "phosphorDim": "#B07618",
      "segmentUnlit": "#1E140A",
      "ledOn": "#FFB830",
      "ledOff": "#291D10",
      "ledAmber": "#FF9418",
      "eqCurveStroke": "#FFC240",
      "eqCurveFill": "#38FFB028",
      "eqCurveZeroLine": "#332412"
    },
    "playlist": {
      "normalText": "#FFB438",
      "currentText": "#FFF0CC",
      "normalBackground": "#0B0805",
      "selectedBackground": "#4A3114",
      "selectedText": "#FFF4D8",
      "indexNumberText": "#B88228",
      "folderAccentText": "#FFD268",
      "breadcrumbBackground": "#16100A",
      "breadcrumbText": "#FFD070"
    },
    "viscolor": ["#090603", "... (24 hex entries total)"]
  },
  "sprites": {
    "header_deck": "sprites/header_deck.png",
    "transport_play_normal": "sprites/transport_play_normal.png"
  }
}
```

### Canonical `sprites/*.png` Slot Reference

DroidAmp auto-discovers canonical paths under `sprites/` even when omitted from `"sprites"` in `droidamp.json`. Note **All-or-None Header Consistency (`ADR-028`)**: custom panel header bitmaps render if and only if all 4 header PNGs (`header_deck`, `header_eq`, `header_drawer_collapsed`, `header_drawer_expanded`) are present.

| Category | Canonical Manifest Key(s) | Archive Path (`sprites/*.png`) | Recommended Aspect / Size |
| :--- | :--- | :--- | :--- |
| **Panel Headers** *(all 4 required together)* | `header_deck`, `header_eq`, `header_drawer_collapsed`, `header_drawer_expanded` | `sprites/header_{deck\|eq\|drawer_collapsed\|drawer_expanded}.png` | `275×22` (`▲` collapsed / `▼` expanded) |
| **Transport** *(normal & pressed)* | `transport_{prev\|play\|pause\|stop\|next\|eject}_{normal\|pressed}` | `sprites/transport_{prev\|play\|pause\|stop\|next\|eject}_{normal\|pressed}.png` | `48×36` (or `23×18` legacy) |
| **Deck Toggles** *(off & on)* | `shuffle_{off\|on}`, `repeat_{off\|on}`, `eq_btn_{off\|on}`, `queue_btn_{off\|on}` | `sprites/{shuffle\|repeat\|eq_btn\|queue_btn}_{off\|on}.png` | `96×28`, `80×28`, `60×28`, `68×28` |
| **EQ Controls** | `eq_on_{off\|on}`, `eq_auto_{off\|on}`, `eq_presets_{normal\|pressed}` | `sprites/eq_{on\|auto}_{off\|on}.png`, `sprites/eq_presets_{normal\|pressed}.png` | `46×28`, `56×28`, `74×28` |
| **Mobile Drawer Tabs** | `mobile_tab_queue_{off\|on}`, `mobile_tab_library_{off\|on}` | `sprites/mobile_tab_{queue\|library}_{off\|on}.png` | `140×30` |
| **Mobile Queue Bar** | `mobile_{rem\|misc\|list}_{normal\|pressed}` | `sprites/mobile_{rem\|misc\|list}_{normal\|pressed}.png` | `96×32` |
| **Mobile Inline Row** | `mobile_row_{play\|enqueue}_{normal\|pressed}` | `sprites/mobile_row_{play\|enqueue}_{normal\|pressed}.png` | `32×28` |
| **Settings Gear** | `mobile_settings_gear_{normal\|pressed}` | `sprites/mobile_settings_gear_{normal\|pressed}.png` | `32×32` |
| **Optional Aux / Sliders** | `vfd_{play\|pause\|stop}`, `{mono\|stereo}_{active\|inactive}`, `seekbar_{track\|thumb}`, `{volume\|balance\|eq}_thumb` | `sprites/<key>.png` | Winamp 2.x native bounds |

---

## 2. Converting Legacy `.wsz` Skins (`wsz_augment.py`)

```bash
# Palette + shape augmentation only (crisp vector hardware chrome, zero BMP seams):
python3 tools/wsz-augment/wsz_augment.py ClassicSkin.wsz \
  -o ClassicSkin_Augmented.wsz \
  --id classic-skin \
  --name "Classic Skin" \
  --author "Original Author" \
  --corner-radius 2.0

# Also slice Winamp 2.x BMP controls + synthesize all 4 consistent headers with
# #FF00FF chroma-keying and outer-corner background flood transparency:
python3 tools/wsz-augment/wsz_augment.py Microchip_2.wsz \
  -o Microchip_2_Augmented.wsz \
  --extract-sprites \
  --chroma-tolerance 12 \
  --corner-radius 3.0
```

---

## 3. Regenerating Built-In Showcase Skins (`generate_showcase_skins.py`)

Generates DroidAmp's two deterministic built-in showcase skins (`amber_hifi_rack.wsz` and `cyber_ice_99.wsz`) directly into `app/src/main/assets/skins/` (or a custom `--out-dir`):

```bash
python3 tools/wsz-augment/generate_showcase_skins.py
# Or into a custom output directory:
python3 tools/wsz-augment/generate_showcase_skins.py --out-dir /tmp/skins_preview
```

---

## 4. Running Unit Tests

```bash
python3 -m unittest discover -s tools/wsz-augment -p "test_*.py" -v
```
