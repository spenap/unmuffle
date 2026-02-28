---
phase: 10-dependency-upgrades
plan: 01
subsystem: infra
tags: [android, ndk, whisper.cpp, cmake, gradle, sdk-35]

# Dependency graph
requires:
  - phase: 09-github-actions-ci
    provides: CI pipeline that validates builds — used to confirm upgrades pass CI
provides:
  - whisper.cpp submodule at v1.8.3 with WHISPER_VERSION compile definition
  - Android SDK 35 / NDK r27c (27.2.12479018) build configuration
  - core-ktx 1.15.0 in version catalog
  - Verified debug APK build and lint pass under all upgraded dependencies
affects: []

# Tech tracking
tech-stack:
  added:
    - NDK r27c (27.2.12479018) — 16KB page size support for Pixel 9+ and Play Store
    - whisper.cpp v1.8.3 — VAD APIs, ggml improvements
    - Android SDK Platform 35 (Android 15)
    - core-ktx 1.15.0
  patterns:
    - WHISPER_VERSION compile definition must be set manually when compiling whisper.cpp
      directly (not via upstream CMakeLists.txt); value is the semver tag string

key-files:
  created: []
  modified:
    - app/src/main/cpp/whisper.cpp  # submodule pointer updated v1.7.4 → v1.8.3
    - app/build.gradle.kts          # compileSdk/targetSdk 35, ndkVersion r27c
    - gradle/libs.versions.toml     # coreKtx 1.15.0
    - app/src/main/cpp/CMakeLists.txt  # WHISPER_VERSION="1.8.3" compile definition

key-decisions:
  - "WHISPER_VERSION define added to CMakeLists.txt — v1.8.3 references this in whisper_print_system_info(); upstream sets it via CMakeLists.txt target_compile_definitions which we bypass by compiling directly"
  - "NDK r27.2 installed via sdkmanager — only r26 was present initially; r27c needed for 16KB page size support"

patterns-established:
  - "When upgrading whisper.cpp, check for new compile definitions required by its CMakeLists.txt that our direct compilation approach must replicate"

requirements-completed: [DEP-01, DEP-02, DEP-03, DEP-04]

# Metrics
duration: 20min
completed: 2026-02-28
---

# Phase 10 Plan 01: Dependency Upgrades Summary

**whisper.cpp v1.8.3 + Android SDK 35 + NDK r27c + core-ktx 1.15.0 — all upgraded with WHISPER_VERSION compile definition fix, assembleDebug and lintDebug both pass**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-02-28T20:28:00Z
- **Completed:** 2026-02-28T20:48:00Z
- **Tasks:** 3
- **Files modified:** 4

## Accomplishments

- whisper.cpp submodule advanced from v1.7.4 to v1.8.3 (2eeeba56)
- Android compileSdk and targetSdk raised to 35 (Android 15), NDK updated to r27c for 16KB page size support
- core-ktx bumped from 1.13.1 to 1.15.0; debug APK builds and lint passes cleanly

## Task Commits

Each task was committed atomically:

1. **Task 1: Upgrade whisper.cpp submodule to v1.8.3** - `dd731ea` (chore)
2. **Task 2: Upgrade build configuration (SDK, NDK, core-ktx)** - `e00e084` (chore)
3. **Task 3: Verify native build + CMakeLists.txt fix** - `8f34668` (fix)

## Files Created/Modified

- `app/src/main/cpp/whisper.cpp` — submodule pointer updated from v1.7.4 to v1.8.3
- `app/build.gradle.kts` — compileSdk/targetSdk 34→35, ndkVersion r26→r27c
- `gradle/libs.versions.toml` — coreKtx 1.13.1→1.15.0
- `app/src/main/cpp/CMakeLists.txt` — WHISPER_VERSION="1.8.3" compile definition added

## Decisions Made

- Added `WHISPER_VERSION="1.8.3"` as a compile definition in CMakeLists.txt: v1.8.3 uses this
  identifier in `whisper_print_system_info()`. The upstream CMakeLists.txt sets it via
  `target_compile_definitions`, but since we compile `src/whisper.cpp` directly, we must supply it
  ourselves. Hardcoded to the submodule tag string.
- Installed NDK r27.2 via sdkmanager before starting: only r26 was present in the environment.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Added WHISPER_VERSION compile definition for whisper.cpp v1.8.3**
- **Found during:** Task 3 (Verify native build compiles against whisper.cpp v1.8.3)
- **Issue:** whisper.cpp v1.8.3 references `WHISPER_VERSION` in `src/whisper.cpp` at line 8972
  (`return WHISPER_VERSION;` inside `whisper_print_system_info()`). The upstream CMakeLists.txt
  sets this via `target_compile_definitions(whisper PRIVATE WHISPER_VERSION="${PROJECT_VERSION}")`,
  but our CMakeLists.txt compiles whisper.cpp directly and did not supply this define, causing a
  compile error: `error: use of undeclared identifier 'WHISPER_VERSION'`.
- **Fix:** Added `target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="1.8.3")` to
  `build_library()` in `app/src/main/cpp/CMakeLists.txt`.
- **Files modified:** `app/src/main/cpp/CMakeLists.txt`
- **Verification:** `./gradlew assembleDebug` — BUILD SUCCESSFUL; `./gradlew lintDebug` — BUILD SUCCESSFUL
- **Committed in:** `8f34668` (Task 3 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - Bug)
**Impact on plan:** Required fix — compile error blocked the build. No scope creep.

## Issues Encountered

- NDK r27.2 was not pre-installed in the environment. Installed via `sdkmanager "ndk;27.2.12479018"` before executing Task 1. The sdkmanager auto-accepted the license for Android SDK Platform 35 during the first build run.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- All four dependency targets (DEP-01 through DEP-04) are complete
- Phase 10 is the final phase in the v1.2 milestone (GitHub Readiness)
- The app builds cleanly with whisper.cpp v1.8.3, SDK 35, NDK r27c, and core-ktx 1.15.0

---
*Phase: 10-dependency-upgrades*
*Completed: 2026-02-28*
