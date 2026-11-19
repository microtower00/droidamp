# DroidAmp — Agentic Implementation Workflow (`agy`)

This playbook documents how to build **DroidAmp** efficiently across sessions using **Antigravity (`agy`)** agentic workflows, matching the right orchestration pattern to each milestone in [DESIGN.md](DESIGN.md).

---

## 0. Session Setup & Hygiene

1. **Always launch `agy` inside the repository root**:
   ```bash
   cd ~/Documents/droidamp && agy
   ```
   Running inside `~/Documents/droidamp` makes the repo the primary workspace so Gradle, Git, and Android CLI commands execute smoothly inside the default sandbox, and `agy` automatically loads [AGENTS.md](AGENTS.md).
2. **Keep context sharp across milestones**:
   - Run `/compact` after completing and committing each milestone.
   - Run `/learn` whenever you correct a convention so it persists across sessions.
   - Add specialized reference specs (e.g., exact `.eqf` / `winamp.q1` / `.wsz` byte layouts) as on-demand project skills under `.agents/skills/<skill-name>/SKILL.md` so they only load when relevant.

---

## 1. Milestone-by-Milestone Orchestration Plan

### Milestone 1 — Foundation, `DroidAmpSkin` Contract & Hardware UI Shell
- **Recommended `agy` mode**: `/plan` (or `/boost` for deeper architectural verification).
- **Why single-agent first**: Milestone 1 establishes the shared Kotlin interfaces (`DroidAmpSkin`, `PlaybackState`, `MusicSourceRepository`, `AudioEngineController`) and the core skeuomorphic Compose primitives that Milestones 2–4 build against.
- **Suggested prompt**:
  > Follow `DESIGN.md` and `DECISIONS.md` to implement Milestone 1: add Media3/DataStore dependencies, define the `DroidAmpSkin` contract + shared repository/engine interfaces, build the `DroidAmp Classic` skeuomorphic Compose primitives, and wire the interactive Deck + curve-drawable 10-band EQ + full-screen expandable Playlist/Library sheet (with dummy preview data + first-run SAF empty state).
- **Verification**: `./gradlew assembleDebug testDebugUnitTest` + `android-cli` emulator screenshot check (Section 3).

---

### Milestones 2, 3 & 4 — Parallel Multi-Agent Execution (`/teamwork`)
Once Milestone 1's shared interfaces are committed on `master`, Milestones 2, 3, and 4 touch largely disjoint packages and can run **in parallel** using **`/teamwork`** (swarm workers with `workspace: "share"` so each worker edits and builds in its own working copy without file conflicts):

| Worker Name | Workspace | Owns | Definition of Done |
| :--- | :--- | :--- | :--- |
| **`dsp`** | `share` | `audio/` — Media3 `MediaSessionService`, custom PCM `AudioProcessor` (ReplayGain, 10-band biquad EQ + Preamp, independent Volume/Balance with cold-launch reset, Crossfade, lock-free FFT/oscilloscope tap) | Compiles, unit tests verify EQ gain/volume reset/FFT buffer, lockscreen/notification metadata wired |
| **`storage`** | `share` | `storage/` & `playlist/` — `MusicSourceRepository` (SAF default + MediaStore engine), Folder/Playlist browser state, select-first track safety, drag-reorder, micro-menus (`REM`/`MISC`/`LIST`), Jump-to-Track filter | Compiles, repository & playlist queue unit tests pass |
| **`winamp-io`** | `share` | `interop/` — Pure Kotlin `.eqf`, `winamp.q1`, `winamp.q2`, `winamp.ini`, `.m3u`/`.m3u8`/`.pls` parsers & serializers + classic Winamp EQ presets (backend only, no UI yet) | Round-trip binary/text unit tests pass 100% |
| **`qa`** | `inherit` *(after merge)* | Integration build, emulator deployment (`android run`), layout dump (`android layout`), screenshot inspection (`android screenshot`) against `DESIGN.md` & `DECISIONS.md` | Clean APK build, all tests pass, visual checklist verified |

- **Coordinator role**: Merges `dsp`, `storage`, and `winamp-io` branches into the main working tree, wires them into the UI ViewModels, resolves any interface mismatches, and triggers `qa`.

---

### Milestone 5 — `.wsz` Skin Engine (Customizability Unlock)
- **Recommended `agy` mode**: `/goal` (thorough TDD run until all parser & sprite-slicing tests pass).
- **Scope**: Deterministic `.wsz` zip parser slicing fixed-coordinate BMP sprites + `pledit.txt` + `viscolor.txt` directly into `DroidAmpSkin` (Adaptive Mobile mode + optional Classic 275×116 Deck mode), using nearest-neighbor (`FilterQuality.None`) pixel scaling.
- **Tip**: Create `.agents/skills/wsz-format/SKILL.md` first with exact Winamp 2.x sprite coordinate tables so the agent has an authoritative local spec.

---

### Milestone 6 — Settings UI & Final Integration
- **Recommended `agy` mode**: `/grill-me` (short UX pass on the Settings layout) → `/plan` → implement.
- **Scope**: Wire post-onboarding folder management, SAF/MediaStore toggle, ReplayGain/Crossfade/Gapless controls, cold-launch volume default, Winamp config/EQ import & export UI triggers, and `.wsz` skin selector into a cohesive retro Settings window.

---

## 2. Dynamic Python Workflows (`run_workflow`) for Fan-Out & Reviews

Use `agy`'s `run_workflow` tool whenever orchestration is structured fan-out or a repeatable review pipeline:

### Recipe A — Parallel Winamp Format Parser Fan-Out (Milestone 4)
Fan out one `agent(..., workspace="branch")` per format (`.eqf`, `winamp.q1`/`q2`, `winamp.ini`, `.m3u`/`.m3u8`, `.pls`) with strict round-trip unit tests, then aggregate and run `./gradlew testDebugUnitTest`.

### Recipe B — Multi-Angle Milestone Gate Review (Run After Every Milestone)
Before committing/finishing a milestone, ask `agy` to run a parallel review workflow across 4 independent reviewer lenses:
1. **Architecture & Skin Purity**: No hardcoded Compose colors/shapes bypassing `DroidAmpSkin`; strict offline check (zero `INTERNET` permission).
2. **UX & ADR Compliance**: Diff checked against every ADR in [DECISIONS.md](DECISIONS.md) (cold-launch-only volume reset, select-first track safety, Preamp gap, swipe buffer, `navigationBarsPadding()`).
3. **Audio & Lifecycle Safety**: Thread safety on PCM ring buffers, proper `MediaSessionService` lifecycle, audio focus & noisy-audio handling.
4. **Test & Build Hygiene**: Meaningful unit tests, zero compiler warnings, clean Gradle build.

---

## 3. Visual QA Loop (`android-cli`)

Because DroidAmp's identity relies on crisp late-1990s hardware bevels, legible pixel labels, and thumb-safe spacing, always close UI milestones with the `android-cli` loop:

1. **Build & Test**:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
2. **Deploy & Inspect**:
   - Start AVD: `android emulator start <avd-name>`
   - Install & launch: `android run --apks=app/build/outputs/apk/debug/app-debug.apk`
   - Fast hierarchy check: `android layout --pretty`
   - Visual verification: `android screenshot` — verify:
     - Resting rack fits cleanly (Deck + 10-band EQ + peeking Playlist handle with swipe buffer).
     - `PREAMP` is visibly separated from the 10 EQ frequency bands.
     - Expanded Playlist/Library sheet bottom chrome sits safely above Android's system gesture bar.
     - VFD phosphor green readouts and pixel labels remain crisp and readable.
