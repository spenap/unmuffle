---
phase: 04-r8-release-configuration
plan: 01
subsystem: build
tags: [r8, proguard, shrinking, release, apk-size]

# Dependency graph
requires:
  - phase: 03-end-to-end-pipeline-and-output
    provides: Working release build with blanket ProGuard keep rule
provides:
  - Surgical R8 keep rules for JNI and sealed class hierarchies
  - Resource shrinking enabled in release build
  - Commented ProgressCallback placeholder for Phase 5
  - 8.7% APK size reduction via tree-shaking
affects: [05-transcription-progress-bar, 06-fdroid-distribution-prep]

# Tech tracking
tech-stack:
  added: []
  patterns: [surgical-r8-keeps, resource-shrinking]

key-files:
  created: []
  modified:
    - app/proguard-rules.pro
    - app/build.gradle.kts

key-decisions:
  - "Used -dontobfuscate to keep readable class names for crash reports and F-Droid transparency"
  - "Kept sealed class hierarchies defensively despite R8 tracing capability — negligible cost, prevents ClassNotFoundException risk"
  - "Added commented ProgressCallback placeholder so Phase 5 can uncomment without modifying rule structure"

patterns-established:
  - "R8 keep rules: JNI classes get full -keep, sealed hierarchies use wildcard subclass patterns (State$*)"
  - "ProGuard file: structured sections with clear headers (Global flags, JNI bridge, Sealed hierarchies, Future placeholders, Notes)"

requirements-completed: [REL-01, REL-02]

# Metrics
duration: 8min
completed: 2026-02-27
---

# Phase 4 Plan 1: R8 Release Configuration Summary

**Surgical R8 keep rules replacing blanket ProGuard keep, with resource shrinking enabled -- 8.7% APK size reduction verified on physical device**

## Performance

- **Duration:** ~8 min (across two sessions with device verification checkpoint)
- **Started:** 2026-02-27T16:42:00Z
- **Completed:** 2026-02-27T16:50:07Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Replaced blanket `-keep class com.simonpena.transchat.** { *; }` with targeted keeps for WhisperLib JNI class, WhisperLib$Companion, and 3 sealed class hierarchies
- Enabled resource shrinking (`isShrinkResources = true`) in release build configuration
- APK size reduced from 6,249,938 bytes (6.0 MB) to 5,706,624 bytes (5.4 MB) -- a savings of 543,314 bytes (8.7%)
- Full user flow verified on physical arm64-v8a device: install, launch, model download, share voice message, transcription, result screen, Copy/Share buttons
- Added commented ProgressCallback placeholder keep rule for Phase 5

## APK Size Comparison

| Metric | Before (blanket keep) | After (surgical keeps) | Delta |
|--------|----------------------|----------------------|-------|
| APK size | 6,249,938 bytes (6.0 MB) | 5,706,624 bytes (5.4 MB) | -543,314 bytes (-8.7%) |

## Task Commits

Each task was committed atomically:

1. **Task 1: Replace blanket ProGuard keep with surgical R8 rules and enable resource shrinking** - `ebcc9de` (feat)
2. **Task 2: Verify release APK on physical device** - checkpoint:human-verify (approved, no commit needed)

## Files Created/Modified
- `app/proguard-rules.pro` - Surgical R8 keep rules: -dontobfuscate, WhisperLib + Companion JNI keeps, 3 sealed hierarchy keeps, commented ProgressCallback placeholder
- `app/build.gradle.kts` - Added `isShrinkResources = true` to release block

## Decisions Made
- Used `-dontobfuscate` to keep readable class names for crash reports and F-Droid open-source transparency
- Kept sealed class hierarchies defensively (DownloadViewModel.State, UiState, AudioCopier.CopyResult) despite R8's ability to trace them -- negligible size cost, prevents ClassNotFoundException risk
- Added commented ProgressCallback placeholder so Phase 5 can uncomment it without restructuring the rules file
- Did NOT add keeps for Activities (R8 auto-keeps via AndroidManifest.xml), ViewModels (lifecycle-viewmodel ships consumer rules), or native methods (proguard-android-optimize.txt covers these)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- R8 surgical keeps in place, ready for Phase 5 to uncomment ProgressCallback placeholder
- Resource shrinking verified, ready for Phase 6 F-Droid distribution prep
- Release build confirmed working on physical device with full user flow

## Self-Check: PASSED

- [x] app/proguard-rules.pro exists
- [x] app/build.gradle.kts exists
- [x] 04-01-SUMMARY.md exists
- [x] Commit ebcc9de found in git log
- [x] Blanket keep rule removed (0 matches for `com.simonpena.transchat.**`)
- [x] `-dontobfuscate` present in proguard-rules.pro
- [x] `isShrinkResources = true` present in build.gradle.kts
- [x] ProgressCallback placeholder present in proguard-rules.pro

---
*Phase: 04-r8-release-configuration*
*Completed: 2026-02-27*
