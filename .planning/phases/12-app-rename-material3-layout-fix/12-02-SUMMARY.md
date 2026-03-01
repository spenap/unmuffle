---
phase: 12-app-rename-material3-layout-fix
plan: 02
subsystem: ui
tags: [material3, dynamic-color, android, rebrand, unmuffle, themes, layout]

# Dependency graph
requires:
  - phase: 12-01
    provides: Package renamed from com.simonpena.transchat to com.simonpena.unmuffle; theme names already updated to Theme.Unmuffle; manifest and JNI updated
provides:
  - M3 theme (Theme.Material3.DayNight.NoActionBar) replacing M2 MaterialComponents parents
  - UnmuffleApplication.kt with DynamicColors.applyToActivitiesIfAvailable for Android 12+ dynamic color
  - All M2 textAppearance attrs replaced with M3 equivalents across 4 layout files
  - Plain Button replaced with MaterialButton in activity_download.xml and activity_smoke_test.xml
  - material library version 1.13.0
  - README fully renamed from TransChat to Unmuffle; clone URL points to github.com/spenap/unmuffle
  - REBRAND-04 manual instructions provided for GitHub repo rename
  - FIX-01 resolved: action bar overlap eliminated by switching to NoActionBar parent for all themes
affects:
  - phase: 13 (final release)
  - phase: 14 (model manager cleanup)

# Tech tracking
tech-stack:
  added:
    - com.google.android.material:material:1.13.0 (upgraded from 1.12.0)
    - DynamicColors.applyToActivitiesIfAvailable (Material 3 dynamic color API)
  patterns:
    - Application subclass pattern for global Material You color theming
    - Theme.Material3.DayNight.NoActionBar as universal base theme (no per-activity action bar management needed)
    - M3 textAppearance token naming: BodyLarge/BodyMedium/BodySmall/HeadlineMedium/TitleLarge (replaces M2 Body1/Body2/Headline5/Headline6/Caption)
    - MaterialButton instead of plain Button for all button widgets

key-files:
  created:
    - app/src/main/java/com/simonpena/unmuffle/UnmuffleApplication.kt
  modified:
    - gradle/libs.versions.toml
    - app/src/main/res/values/themes.xml
    - app/src/main/AndroidManifest.xml
    - app/src/main/res/layout/activity_transcription.xml
    - app/src/main/res/layout/activity_ready.xml
    - app/src/main/res/layout/activity_download.xml
    - app/src/main/res/layout/activity_smoke_test.xml
    - README.md

key-decisions:
  - "Theme.Material3.DayNight.NoActionBar used for both Theme.Unmuffle and Theme.Unmuffle.Transparent — single parent eliminates FIX-01 overlap without per-activity windowActionBar flags"
  - "DynamicColors registered via Application.onCreate rather than per-activity — applies to all current and future activities automatically"
  - "REBRAND-04 (GitHub repo rename) is a manual user action — documented as instructions after automated tasks complete"

patterns-established:
  - "M3 textAppearance tokens: BodyLarge/BodyMedium/BodySmall/HeadlineMedium/TitleLarge"
  - "Application subclass for global DynamicColors registration"

requirements-completed: [REBRAND-03, REBRAND-04, FIX-01, UI-01, UI-02, UI-03]

# Metrics
duration: 3min
completed: 2026-03-01
---

# Phase 12 Plan 02: App Rename, Material 3, and Layout Fix Summary

**Material 3 theme migration with dynamic color (Android 12+), M2->M3 textAppearance token replacement across 4 layouts, UnmuffleApplication for DynamicColors, and full README rename from TransChat to Unmuffle**

## Performance

- **Duration:** ~3 min
- **Started:** 2026-03-01T17:57:25Z
- **Completed:** 2026-03-01T17:59:55Z
- **Tasks:** 2 of 3 (Task 3 is checkpoint:human-verify awaiting device confirmation)
- **Files modified:** 9

## Accomplishments
- M3 theme migration: both Theme.Unmuffle parents changed to Theme.Material3.DayNight.NoActionBar, eliminating FIX-01 action bar overlap on TranscriptionActivity
- DynamicColors enabled via new UnmuffleApplication.kt, registered in AndroidManifest, applies Material You colors on Android 12+ to all activities
- 11 textAppearance attribute replacements across activity_transcription.xml (5), activity_ready.xml (2), activity_download.xml (3), activity_smoke_test.xml (1) — all M2 tokens replaced with M3 equivalents
- Plain Button replaced with MaterialButton in activity_download.xml and activity_smoke_test.xml
- material library bumped 1.12.0 -> 1.13.0
- README fully renamed: title, privacy section, How It Works section, clone URL (spenap/unmuffle), directory name (unmuffle)
- Build successful (assembleDebug: BUILD SUCCESSFUL in 30s)

## Task Commits

Each task was committed atomically:

1. **Task 1: M3 theme migration, dynamic color, layout fix, and material version bump** - `14b9890` (feat)
2. **Task 2: Rename documentation references and provide REBRAND-04 guide** - `b7a6dbd` (docs)
3. **Task 3: Device verification** - Pending checkpoint

## Files Created/Modified
- `app/src/main/java/com/simonpena/unmuffle/UnmuffleApplication.kt` - NEW: Application subclass calling DynamicColors.applyToActivitiesIfAvailable
- `gradle/libs.versions.toml` - material version bumped to 1.13.0
- `app/src/main/res/values/themes.xml` - Both theme parents changed to Theme.Material3.DayNight.NoActionBar
- `app/src/main/AndroidManifest.xml` - android:name=".UnmuffleApplication" added to application element
- `app/src/main/res/layout/activity_transcription.xml` - 5 M2->M3 textAppearance replacements
- `app/src/main/res/layout/activity_ready.xml` - 2 M2->M3 textAppearance replacements
- `app/src/main/res/layout/activity_download.xml` - 3 M2->M3 textAppearance replacements + Button->MaterialButton
- `app/src/main/res/layout/activity_smoke_test.xml` - 1 M2->M3 textAppearance replacement + Button->MaterialButton
- `README.md` - Full TransChat->Unmuffle rename; clone URL updated to spenap/unmuffle

## Decisions Made
- Theme.Material3.DayNight.NoActionBar used for both themes — single parent eliminates FIX-01 without per-activity windowActionBar flags
- DynamicColors registered via Application.onCreate — applies to all current and future activities automatically
- REBRAND-04 (GitHub repo rename) is a manual user action documented as instructions after automated tasks complete

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required

REBRAND-04: GitHub Repository Rename (Manual Step)
==================================================
1. Go to https://github.com/spenap/transchat-app
2. Click Settings (gear icon)
3. Under "Repository name", change to: unmuffle
4. Click "Rename"
5. Then run locally:
   git remote set-url origin https://github.com/spenap/unmuffle.git
6. Verify:
   git remote -v

## Next Phase Readiness
- M3 theme migration complete; all layout files use M3 textAppearance tokens
- UnmuffleApplication registered; dynamic color active on Android 12+
- FIX-01 (action bar overlap) resolved at theme level — no per-activity code needed
- Task 3 (device verification) blocked on human-verify checkpoint — user must confirm visual correctness on device
- After user confirms Task 3, proceed to Phase 12 final verification then Phase 13 (release)

---
*Phase: 12-app-rename-material3-layout-fix*
*Completed: 2026-03-01*
