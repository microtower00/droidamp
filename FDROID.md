# Submitting DroidAmp to F-Droid

DroidAmp is prepped for F-Droid under **GPL-3.0-or-later** with:
- Canonical `LICENSE` in the repository root.
- `dependenciesInfo { includeInApk = false; includeInBundle = false }` in `app/build.gradle.kts`.
- Strictly offline manifest (zero `INTERNET`, `ACCESS_NETWORK_STATE`, or `RECORD_AUDIO`).
- Fastlane store metadata under `fastlane/metadata/android/en-US/`.

## `fdroiddata` Metadata (`metadata/com.droidamp.player.yml`)

Fork `https://gitlab.com/fdroid/fdroiddata`, add `metadata/com.droidamp.player.yml` with the template below, and open a Merge Request:

```yaml
Categories:
  - Multimedia
License: GPL-3.0-or-later
SourceCode: https://github.com/microtower00/droidamp
IssueTracker: https://github.com/microtower00/droidamp/issues
Changelog: https://github.com/microtower00/droidamp/releases

AutoName: DroidAmp

RepoType: git
Repo: https://github.com/microtower00/droidamp.git

Builds:
  - versionName: 0.1.0-alpha.1
    versionCode: 1
    commit: v0.1.0-alpha.1
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.1.0-alpha.1
CurrentVersionCode: 1
```
