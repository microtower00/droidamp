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

## 1. Model & Orchestrator Selection Guide

Use the lightest model that reliably nails the task, and take advantage of the **two separate quota pools** in your `agy` setup (**Gemini / Orchestrator pool** vs. **Claude / GPT-OSS pool**) for parallel `/teamwork` runs and cross-family code reviews.

### Quick Role-to-Model Matrix

| Task / Role | Best Fit | Effort Tier | Why |
| :--- | :--- | :--- | :--- |
| **Multi-agent Coordinator (`/teamwork`)** | **Orchestrator (Neon)** *(alternatives: Orchestrator Argon / Carbon)* | — | Purpose-built for long-horizon task decomposition, worker coordination, branch merging, and milestone tracking. |
| **Architecture, `DroidAmpSkin` Contract & Custom Compose Hardware UI (M1)** | **Gemini 4 Neon** or **Claude Opus 5.5** | `High` *(or `XHigh` for Neon on tricky gesture physics)* | Strong spatial/Compose reasoning for 3D pixel bevels, EQ curve drawing + sheet swipe conflict resolution, and clean interfaces. |
| **Audio DSP, Biquad 10-Band EQ, Lock-Free FFT & Crossfade (M2)** | **Gemini 4 Neon**, **Gemini 4 Hydrogen**, or **Claude Opus 5.5** | `High` / `XHigh` | Real-time PCM math, IIR filter stability, concurrency/ring-buffer safety, and Media3 lifecycle edge cases reward deep reasoning. |
| **Storage (`SAF` + `MediaStore`), Playlist Queue & Library UI (M3)** | **Claude Sonnet 5.5** or **Gemini 4 Argon / Neon** | `Medium` / `High` | Fast, reliable Android/Kotlin implementation with great adherence to select-first and repository contracts. |
| **Winamp Binary/Text Parsers (`.eqf`, `q1/q2`, `winamp.ini`, `.m3u`, `.pls`) (M4)** | **Claude Sonnet 5.5** or **Gemini 4 Carbon / Argon** | `Medium` | Clean, well-scoped specs with deterministic round-trip unit tests; no need to burn top-tier reasoning quota. |
| **`.wsz` Skin Parser & Sprite Slicer (M5)** | **Gemini 4 Neon** or **Claude Opus 5.5** | `High` | Handles BMP coordinate tables, malformed community `.wsz` edge cases, `viscolor.txt`/`pledit.txt`, and crisp nearest-neighbor Compose rendering. |
| **Settings UI & UX Polish (M6)** | **Claude Sonnet 5.5** or **Gemini 4 Neon** | `Medium` / `High` | Clean UI integration + `/grill-me` design pass before wiring. |
| **Visual QA (`android screenshot` & `android layout`)** | **Gemini 4 Neon** *(multimodal)* | `Medium` / `High` | Strong vision inspection for 1px bevel alignment, Preamp gap, VFD phosphor contrast, and gesture-bar clearance. |
| **Cross-Family Code & ADR Review (`run_workflow`)** | **Opposite family of the author** (e.g., author = Neon → reviewer = **Claude Opus/Sonnet 5.5**, or vice versa) | `High` / `Medium` | Different model families catch each other's blind spots and balance load across both quota pools. |
| **Read-Only Research, Docs Search & Boilerplate Fan-Out** | **Gemini 3.8 Flash** or **GPT-OSS 120B (Medium)** | `Low` / `Medium` | Ultra-fast and cheap for `android docs search`, exploring files, generating test fixtures, and parallel lint/check passes. |

### Recommended `/teamwork` Pinning for Milestones 2–4
To maximize speed and avoid exhausting one quota pool:
- **Coordinator**: `Orchestrator (Neon)`
- **`dsp` worker**: `Gemini 4 Neon (High)` *(or `Claude Opus 5.5 (High)`)*
- **`storage` worker**: `Claude Sonnet 5.5 (High)`
- **`winamp-io` worker**: `Gemini 4 Argon (Medium)` *(or `Gemini 3.8 Flash (High)` for fast TDD iterations)*
- **`qa` worker (post-merge visual + test check)**: `Gemini 4 Neon (Medium)`

> **Tip**: If a swarm worker hits quota mid-task, switch its model in place (`swarm model`) instead of killing it — it keeps its entire conversation context and resumes cleanly.

---

## 2. Milestone-by-Milestone Orchestration Plan

### Milestone 1 — Foundation, `DroidAmpSkin` Contract & Hardware UI Shell
- **Recommended `agy` mode**: `/plan` (or `/boost` for deeper architectural verification).
- **Why single-agent first**: Milestone 1 establishes the shared Kotlin interfaces (`DroidAmpSkin`, `PlaybackState`, `MusicSourceRepository`, `AudioEngineController`) and the core skeuomorphic Compose primitives that Milestones 2–4 build against.
- **Suggested prompt**:
  > Follow `DESIGN.md` and `DECISIONS.md` to implement Milestone 1: add Media3/DataStore dependencies, define the `DroidAmpSkin` contract + shared repository/engine interfaces, build the `DroidAmp Classic` skeuomorphic Compose primitives, and wire the interactive Deck + curve-drawable 10-band EQ + full-screen expandable Playlist/Library sheet (with dummy preview data + first-run SAF empty state).
- **Verification**: `./gradlew assembleDebug testDebugUnitTest` + `android-cli` emulator screenshot check (Section 4).

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

## 3. Dynamic Python Workflows (`run_workflow`) for Fan-Out & Reviews

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

## 4. Visual QA Loop (`android-cli`)

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
