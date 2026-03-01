---
phase: 12-app-rename-material3-layout-fix
plan: 01
subsystem: build
tags: [android, kotlin, jni, proguard, manifest, gradle, rebrand]

# Dependency graph
requires:
  - phase: 11-foundation-language-jni-chain
    provides: "JNI languageCode chain — C++ symbols established before package rename to avoid renaming twice"
provides:
  - "com.simonpena.unmuffle package for all Kotlin source, build config, and ProGuard"
  - "7 JNI function signatures updated to Java_com_simonpena_unmuffle_WhisperLib_*"
  - "Manifest and theme names updated to Unmuffle throughout"
  - "All user-visible strings updated from TransChat to Unmuffle"
affects:
  - 12-02 (M3 migration — themes.xml updated, ready for M3 parent swap)
  - 12-03 (layout fixes — Kotlin source in new package location)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Atomic JNI rename: Kotlin package, JNI C++ symbols, build config, ProGuard all updated in single commit to prevent UnsatisfiedLinkError"

key-files:
  created:
    - app/src/main/java/com/simonpena/unmuffle/ (19 Kotlin files moved from transchat/)
  modified:
    - app/build.gradle.kts
    - settings.gradle.kts
    - app/src/main/cpp/whisper_jni.cpp
    - app/proguard-rules.pro
    - app/src/main/AndroidManifest.xml
    - app/src/main/res/values/themes.xml
    - app/src/main/res/layout/activity_download.xml
    - app/src/main/res/layout/activity_ready.xml

key-decisions:
  - "Single atomic commit for entire rename — JNI symbols must match Kotlin package at every commit or app crashes with UnsatisfiedLinkError"
  - "User-visible string literals (DownloadViewModel notification title, ShareReceiverActivity toast, layout text) updated inline — no string resource extraction needed at this stage"

patterns-established:
  - "JNI package rename: always change C++ function names, Kotlin package declarations, build namespace, ProGuard keeps, and manifest atomically in one commit"

requirements-completed: [REBRAND-01, REBRAND-02]

# Metrics
duration: 2min
completed: 2026-03-01
---

# Phase 12 Plan 01: App Rename (TransChat -> Unmuffle) Summary

**Atomic rename of package com.simonpena.transchat -> com.simonpena.unmuffle across 19 Kotlin files, 7 JNI C++ symbols, build config, ProGuard rules, manifest labels, and theme names in a single crash-safe commit**

## Performance

- **Duration:** 2 min
- **Started:** 2026-03-01T17:52:20Z
- **Completed:** 2026-03-01T17:54:55Z
- **Tasks:** 2
- **Files modified:** 27 (19 renamed + 8 modified)

## Accomplishments
- All 19 Kotlin files moved from `com/simonpena/transchat/` to `com/simonpena/unmuffle/` with git mv (history preserved)
- All 7 JNI function signatures in whisper_jni.cpp updated to `Java_com_simonpena_unmuffle_WhisperLib_*`
- Build config (namespace, applicationId), settings (rootProject.name), ProGuard keep rules, manifest labels, and theme names all renamed
- User-visible string literals updated in DownloadViewModel notification, ShareReceiverActivity toast, and XML layouts

## Task Commits

Tasks 1 and 2 committed atomically as required by plan (JNI safety):

1. **Tasks 1+2: Rename package and all dependent files** - `577a328` (feat)

**Plan metadata:** (to be committed with SUMMARY.md)

## Files Created/Modified
- `app/src/main/java/com/simonpena/unmuffle/*.kt` - 19 Kotlin files in new package location
- `app/build.gradle.kts` - namespace and applicationId set to com.simonpena.unmuffle
- `settings.gradle.kts` - rootProject.name = "Unmuffle"
- `app/src/main/cpp/whisper_jni.cpp` - All 7 JNI function signatures updated
- `app/proguard-rules.pro` - Header comment and all 6 keep class rules updated
- `app/src/main/AndroidManifest.xml` - Labels and theme refs updated to Unmuffle
- `app/src/main/res/values/themes.xml` - Theme names updated to Theme.Unmuffle / Theme.Unmuffle.Transparent
- `app/src/main/res/layout/activity_download.xml` - "Unmuffle needs the Whisper model..."
- `app/src/main/res/layout/activity_ready.xml` - "Unmuffle is ready."

## Decisions Made
- Single atomic commit for Task 1 + Task 2 together: JNI symbols in C++ must match Kotlin package declaration at every Git commit boundary or the app crashes with UnsatisfiedLinkError on launch.
- User-visible string literals fixed inline as part of rename (Rule 2 auto-fix): DownloadManager notification title, ShareReceiverActivity error toast, and XML layout text all referenced "TransChat" and needed updating.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Updated user-visible string literals referencing "TransChat"**
- **Found during:** Task 2 verification (grep for TransChat in source files)
- **Issue:** Four user-visible strings still said "TransChat" after the package rename: DownloadManager notification title in DownloadViewModel.kt, error toast in ShareReceiverActivity.kt, explainer text in activity_download.xml, ready text in activity_ready.xml
- **Fix:** Updated all four strings to reference "Unmuffle" to complete the brand rename for REBRAND-01/02
- **Files modified:** app/src/main/java/com/simonpena/unmuffle/DownloadViewModel.kt, ShareReceiverActivity.kt, app/src/main/res/layout/activity_download.xml, activity_ready.xml
- **Verification:** `grep -r "TransChat" app/src/` returns no matches
- **Committed in:** 577a328 (atomic task commit)

---

**Total deviations:** 1 auto-fixed (Rule 2 - missing critical completeness)
**Impact on plan:** Auto-fix necessary to fulfill REBRAND-01 (app appears as Unmuffle everywhere user-visible). No scope creep.

## Issues Encountered
- Old `app/src/main/java/com/simonpena/transchat/` directory remains as an empty OS directory after git mv. This is expected — git does not track empty directories, and the directory will disappear naturally on a fresh checkout. All 19 files are correctly tracked as renames in the index.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Package rename complete — plan 12-02 (Material 3 theme migration) can now update themes.xml parent styles
- All Kotlin source in `com/simonpena/unmuffle/` — plan 12-03 layout fixes will work against renamed source
- Zero references to "transchat" or "TransChat" remain in app source, build config, or resources (README/.planning docs handled by plan 12-02)

## Self-Check: PASSED
- FOUND: app/src/main/java/com/simonpena/unmuffle/WhisperLib.kt
- FOUND: app/src/main/cpp/whisper_jni.cpp
- FOUND: .planning/phases/12-app-rename-material3-layout-fix/12-01-SUMMARY.md
- FOUND: commit 577a328

---
*Phase: 12-app-rename-material3-layout-fix*
*Completed: 2026-03-01*
