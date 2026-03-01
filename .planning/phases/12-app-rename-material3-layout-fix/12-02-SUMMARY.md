---
phase: 12-app-rename-material3-layout-fix
plan: "02"
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
  - FIX-01 resolved: action bar overlap eliminated + MaterialToolbar with Unmuffle title added to all user-facing screens
  - System window insets handled via fitsSystemWindows=true + MaterialToolbar in transcription, ready, and download layouts
affects:
  - phase: 13-language-selection-dialog
  - phase: 14-settings-activity

# Tech tracking
tech-stack:
  added:
    - com.google.android.material:material:1.13.0 (upgraded from 1.12.0)
    - DynamicColors.applyToActivitiesIfAvailable (Material 3 dynamic color API)
    - MaterialToolbar (added to all user-facing activity layouts)
  patterns:
    - Application subclass pattern for global Material You color theming
    - Theme.Material3.DayNight.NoActionBar as universal base theme (no per-activity action bar management needed)
    - M3 textAppearance token naming: BodyLarge/BodyMedium/BodySmall/HeadlineMedium/TitleLarge (replaces M2 Body1/Body2/Headline5/Headline6/Caption)
    - MaterialButton instead of plain Button for all button widgets
    - fitsSystemWindows=true on root layout + MaterialToolbar handles status bar and navigation bar insets for NoActionBar theme

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
  - "MaterialToolbar added to all user-facing layouts post-Task 1 fix — NoActionBar theme requires explicit toolbar and fitsSystemWindows handling for correct system window insets"

patterns-established:
  - "M3 textAppearance tokens: BodyLarge/BodyMedium/BodySmall/HeadlineMedium/TitleLarge"
  - "Application subclass for global DynamicColors registration"
  - "NoActionBar theme scaffold: root layout fitsSystemWindows=true + MaterialToolbar at top — required for correct status bar and navigation bar inset handling"

requirements-completed: [REBRAND-03, REBRAND-04, FIX-01, UI-01, UI-02, UI-03]

# Metrics
duration: ~75min
completed: "2026-03-01"
---

# Phase 12 Plan 02: App Rename, Material 3, and Layout Fix Summary

**Material 3 theme with dynamic color (Android 12+), MaterialToolbar scaffold fixing system window insets, and full TransChat to Unmuffle rename in README — app displays as "Unmuffle" everywhere with no content overlap behind status or navigation bar.**

## Performance

- **Duration:** ~75 min (including device verification iteration and layout fix)
- **Started:** 2026-03-01T17:57:25Z
- **Completed:** 2026-03-01T19:08:35Z
- **Tasks:** 3 (2 auto + 1 human-verify checkpoint — approved after fix)
- **Files modified:** 9

## Accomplishments

- M3 theme migration: both Theme.Unmuffle parents changed to Theme.Material3.DayNight.NoActionBar, eliminating FIX-01 action bar overlap
- DynamicColors enabled via new UnmuffleApplication.kt, registered in AndroidManifest, applies Material You colors on Android 12+ to all activities
- 11 textAppearance attribute replacements across 4 layout files — all M2 tokens (Body1/Body2/Headline5/Headline6/Caption) replaced with M3 equivalents
- Plain Button replaced with MaterialButton in activity_download.xml and activity_smoke_test.xml
- material library bumped 1.12.0 to 1.13.0
- README fully renamed: title, privacy section, How It Works section, clone URL (spenap/unmuffle), directory name (unmuffle)
- MaterialToolbar with "Unmuffle" title and fitsSystemWindows=true added to all user-facing layouts (transcription, ready, download) to fix system window inset issue found on device

## Task Commits

Each task was committed atomically:

1. **Task 1: M3 theme migration, dynamic color, layout fix, and material version bump** - `14b9890` (feat)
2. **Task 2: Rename documentation references and provide REBRAND-04 guide** - `b7a6dbd` (docs)
3. **Task 3: Device verification** - Checkpoint approved by user (after deviation fix below)

**Deviation fix (post-Task 1, pre-approval):** `068064b` (fix: add toolbar and fitsSystemWindows to all user-facing layouts)

**Plan metadata:** `7de4bfd` (docs: complete M3 theme migration and documentation rename plan)

## Files Created/Modified

- `app/src/main/java/com/simonpena/unmuffle/UnmuffleApplication.kt` - NEW: Application subclass calling DynamicColors.applyToActivitiesIfAvailable
- `gradle/libs.versions.toml` - material version bumped to 1.13.0
- `app/src/main/res/values/themes.xml` - Both theme parents changed to Theme.Material3.DayNight.NoActionBar
- `app/src/main/AndroidManifest.xml` - android:name=".UnmuffleApplication" added to application element
- `app/src/main/res/layout/activity_transcription.xml` - 5 M2->M3 textAppearance replacements + MaterialToolbar + fitsSystemWindows
- `app/src/main/res/layout/activity_ready.xml` - 2 M2->M3 textAppearance replacements + MaterialToolbar + fitsSystemWindows
- `app/src/main/res/layout/activity_download.xml` - 3 M2->M3 textAppearance replacements + Button->MaterialButton + MaterialToolbar + fitsSystemWindows
- `app/src/main/res/layout/activity_smoke_test.xml` - 1 M2->M3 textAppearance replacement + Button->MaterialButton
- `README.md` - Full TransChat->Unmuffle rename; clone URL updated to spenap/unmuffle

## Decisions Made

- Theme.Material3.DayNight.NoActionBar used for both themes — single parent eliminates FIX-01 without per-activity windowActionBar flags
- DynamicColors registered via Application.onCreate — applies to all current and future activities automatically
- REBRAND-04 (GitHub repo rename) is a manual user action documented as instructions after automated tasks complete
- MaterialToolbar added to all user-facing layouts after device testing revealed system window inset regression — the NoActionBar theme requires explicit toolbar and fitsSystemWindows handling

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Added MaterialToolbar and fitsSystemWindows to fix system window inset regression**
- **Found during:** Task 3 (device verification checkpoint) — user reported content drawing behind the status bar (camera hole cutout) and buttons obscured behind the navigation bar; no app title visible
- **Issue:** Switching to Theme.Material3.DayNight.NoActionBar removes the system-managed action bar. Without an explicit MaterialToolbar and fitsSystemWindows=true on the root layout, the window renders behind system bars — content overlaps the status bar and bottom buttons go behind the navigation bar.
- **Fix:** Added MaterialToolbar with android:title="Unmuffle" at the top of the root layout, and android:fitsSystemWindows="true" on the root layout in activity_transcription.xml, activity_ready.xml, and activity_download.xml. (activity_smoke_test.xml is a developer/internal screen, left without toolbar.)
- **Files modified:** app/src/main/res/layout/activity_transcription.xml, app/src/main/res/layout/activity_ready.xml, app/src/main/res/layout/activity_download.xml
- **Verification:** User confirmed on device — no overlap, buttons fully visible and tappable, "Unmuffle" title shows in toolbar
- **Committed in:** 068064b (separate fix commit after device feedback)

---

**Total deviations:** 1 auto-fixed (Rule 1 - Bug: system window inset handling missing after NoActionBar migration)
**Impact on plan:** Fix was necessary for correct visual behavior on real devices. The M3 NoActionBar theme requires explicit toolbar and inset handling that automated checks cannot detect. Device verification (Task 3 checkpoint) caught the issue. No scope creep.

## Issues Encountered

- Initial Task 1 implementation correctly migrated the theme and all textAppearance attributes, but did not add a toolbar or inset handling. This was only discoverable on a real device — the build succeeded and all automated grep checks passed. The Task 3 human-verify checkpoint caught it. After the fix commit (068064b), the user approved.

## User Setup Required

REBRAND-04: GitHub Repository Rename (Manual Step)

1. Go to https://github.com/spenap/transchat-app
2. Click Settings (gear icon)
3. Under "Repository name", change to: unmuffle
4. Click "Rename"
5. Then run locally: git remote set-url origin https://github.com/spenap/unmuffle.git
6. Verify: git remote -v

## Next Phase Readiness

- Phase 13 (Language Selection Dialog) can proceed: M3 theme in place, MaterialToolbar scaffold established in all user-facing layouts, DynamicColors active
- Phase 14 (Settings Activity) can proceed for same reasons, plus SettingsRepository from Phase 11
- REBRAND-04 (GitHub rename) is a separate manual action that does not block Phase 13 or 14

---
*Phase: 12-app-rename-material3-layout-fix*
*Completed: 2026-03-01*
