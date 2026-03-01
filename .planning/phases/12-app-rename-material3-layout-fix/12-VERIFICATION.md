---
phase: 12-app-rename-material3-layout-fix
verified: 2026-03-01T19:27:21Z
status: human_needed
score: 7/8 must-haves verified (1 requires human)
human_verification:
  - test: "Install APK on device and confirm no content overlap on transcript screen, copy/share buttons fully visible"
    expected: "Transcript text visible below toolbar with no status-bar overlap; Copy and Share buttons tap targets fully clear of navigation bar"
    why_human: "Layout fix (fitsSystemWindows + MaterialToolbar) was verified by the user during plan 12-02 Task 3 checkpoint. No automated re-verification is possible without a running device — cannot grep for visual correctness."
  - test: "Confirm dynamic color (Material You) applies on Android 12+ device"
    expected: "App colors adapt to current wallpaper palette"
    why_human: "DynamicColors.applyToActivitiesIfAvailable is wired and registered, but visual effect requires an Android 12+ device with wallpaper-based theming active."
---

# Phase 12: App Rename, Material 3, and Layout Fix — Verification Report

**Phase Goal:** Rename app from TransChat to Unmuffle (package, JNI, UI, docs), migrate to Material 3 theme with dynamic color, and fix layout regression where content overlaps system bars.
**Verified:** 2026-03-01T19:27:21Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| #  | Truth | Status | Evidence |
|----|-------|--------|----------|
| 1  | App appears as "Unmuffle" in launcher, share sheet, and app bar | ? HUMAN | AndroidManifest.xml: `android:label="Unmuffle"` on application and ShareReceiverActivity; MaterialToolbar `app:title="Unmuffle"` in all user-facing layouts. Visual confirmation from Task 3 checkpoint per SUMMARY.md. |
| 2  | Package is com.simonpena.unmuffle in Kotlin namespace and applicationId | ✓ VERIFIED | `namespace = "com.simonpena.unmuffle"` and `applicationId = "com.simonpena.unmuffle"` confirmed in app/build.gradle.kts. Zero old `com.simonpena.transchat` references in source tree. |
| 3  | All 7 JNI function signatures in whisper_jni.cpp use com_simonpena_unmuffle | ✓ VERIFIED | `grep -c "Java_com_simonpena_unmuffle_WhisperLib" whisper_jni.cpp` = 7 exactly. Zero `transchat` references in JNI file. |
| 4  | ProGuard keep rules reference com.simonpena.unmuffle classes | ✓ VERIFIED | proguard-rules.pro: header "Unmuffle ProGuard/R8 Rules"; all 6 `-keep class` rules use `com.simonpena.unmuffle.*`. Zero old package references. |
| 5  | Theme names are Theme.Unmuffle and Theme.Unmuffle.Transparent with M3 parents | ✓ VERIFIED | themes.xml: both styles have `parent="Theme.Material3.DayNight.NoActionBar"`. Zero `MaterialComponents` parent references. |
| 6  | README and CI documentation reference Unmuffle with no TransChat remaining | ✓ VERIFIED | README.md title is "# Unmuffle"; clone URL points to github.com/spenap/unmuffle.git; directory is "unmuffle". CI workflow build.yml has no TransChat/transchat references. Git remote is `git@github.com:spenap/unmuffle.git` (REBRAND-04 completed). |
| 7  | All textAppearance attributes use M3 equivalents across all 4 layout files | ✓ VERIFIED | 11 attributes across 4 layout files all use M3 tokens (BodyLarge, BodyMedium, BodySmall, TitleLarge, HeadlineMedium). Zero M2 names (Body1, Body2, Headline5, Headline6, Caption) remain. |
| 8  | Dynamic color (Material You) applies on Android 12+ and layout overlap is fixed | ? HUMAN | UnmuffleApplication.kt correctly calls `DynamicColors.applyToActivitiesIfAvailable(this)` and is registered in AndroidManifest.xml. fitsSystemWindows=true and MaterialToolbar added to transcription, ready, and download layouts in commit 068064b. Visual verification requires a real device. |

**Score:** 6/8 automated truths verified; 2 truths require human confirmation (visual behavior)

---

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `app/src/main/java/com/simonpena/unmuffle/` | 19+ Kotlin files in renamed package | ✓ VERIFIED | 20 files present (19 original + UnmuffleApplication.kt). All 20 have correct `package com.simonpena.unmuffle` declarations. |
| `app/src/main/cpp/whisper_jni.cpp` | JNI signatures with unmuffle package | ✓ VERIFIED | Contains `Java_com_simonpena_unmuffle_WhisperLib` — exactly 7 occurrences. |
| `app/build.gradle.kts` | Renamed namespace and applicationId | ✓ VERIFIED | Both set to `com.simonpena.unmuffle`. |
| `app/proguard-rules.pro` | Renamed keep rules | ✓ VERIFIED | All keep rules use `com.simonpena.unmuffle`. |
| `settings.gradle.kts` | rootProject.name = "Unmuffle" | ✓ VERIFIED | `rootProject.name = "Unmuffle"` confirmed. |
| `app/src/main/java/com/simonpena/unmuffle/UnmuffleApplication.kt` | Application subclass enabling DynamicColors | ✓ VERIFIED | File exists; calls `DynamicColors.applyToActivitiesIfAvailable(this)` in `onCreate()`. |
| `app/src/main/res/values/themes.xml` | M3 theme definitions | ✓ VERIFIED | Both `Theme.Unmuffle` and `Theme.Unmuffle.Transparent` use `Theme.Material3.DayNight.NoActionBar` as parent. |
| `gradle/libs.versions.toml` | material = "1.13.0" | ✓ VERIFIED | `material = "1.13.0"` confirmed. |
| `README.md` | Updated documentation with Unmuffle branding | ✓ VERIFIED | Zero TransChat/transchat references; clone URL is github.com/spenap/unmuffle.git. |

Old `app/src/main/java/com/simonpena/transchat/` directory: OS directory entry exists but contains **zero files** — this is expected behavior after `git mv`. Git does not track empty directories; the directory will be absent on fresh clone. No files remain there.

---

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `WhisperLib.kt` | `whisper_jni.cpp` | JNI symbol resolution | ✓ WIRED | WhisperLib.kt defines `companion object` with 7 `external fun` declarations. whisper_jni.cpp exports all 7 as `Java_com_simonpena_unmuffle_WhisperLib_00024Companion_*`. Names match exactly. |
| `app/build.gradle.kts` | `app/src/main/java/com/simonpena/unmuffle/` | namespace declaration | ✓ WIRED | `namespace = "com.simonpena.unmuffle"` matches all 20 Kotlin package declarations. |
| `AndroidManifest.xml` | `UnmuffleApplication.kt` | android:name on application element | ✓ WIRED | `android:name=".UnmuffleApplication"` present on `<application>` element; class exists at correct package path. |
| `AndroidManifest.xml` | `themes.xml` | theme reference | ✓ WIRED | Manifest references `@style/Theme.Unmuffle` and `@style/Theme.Unmuffle.Transparent`; both defined in themes.xml. |
| `themes.xml` | layout XML files | ?attr/textAppearance* resolution | ✓ WIRED | All 11 textAppearance attributes in layouts use valid M3 tokens that `Theme.Material3.DayNight.NoActionBar` provides (BodyLarge, BodyMedium, BodySmall, TitleLarge, HeadlineMedium). |

---

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| REBRAND-01 | 12-01 | App name displayed as "Unmuffle" everywhere (launcher, share sheet, app bar) | ? HUMAN | Manifest labels set to "Unmuffle"; MaterialToolbar title "Unmuffle" in all user-facing layouts; device confirmation in SUMMARY Task 3 checkpoint. Requires visual human check. |
| REBRAND-02 | 12-01 | Package renamed com.simonpena.transchat → com.simonpena.unmuffle (namespace, applicationId, JNI C++ symbols) | ✓ SATISFIED | All 20 Kotlin files, build.gradle.kts, whisper_jni.cpp (7 JNI symbols), proguard-rules.pro — fully renamed. Zero old references in source tree. |
| REBRAND-03 | 12-02 | README, CI workflow, and all documentation reference "Unmuffle" | ✓ SATISFIED | README.md has zero TransChat/transchat; CI workflow (build.yml) has zero TransChat/transchat; git remote updated to spenap/unmuffle.git. |
| REBRAND-04 | 12-02 | GitHub repository renamed from transchat-app to unmuffle | ✓ SATISFIED | Git remote is `git@github.com:spenap/unmuffle.git` — rename completed. SUMMARY.md documents manual instructions were provided to user. |
| FIX-01 | 12-02 | Layout regression fixed — transcript text not overlapped by title, copy/share buttons fully visible | ? HUMAN | fitsSystemWindows=true and MaterialToolbar added to activity_transcription.xml, activity_ready.xml, activity_download.xml in commit 068064b. User confirmed fix on device per SUMMARY.md. Cannot re-verify visually without device. |
| UI-01 | 12-02 | Material 3 theme applied (Theme.Material3.DayNight) | ✓ SATISFIED | Both themes in themes.xml inherit from Theme.Material3.DayNight.NoActionBar. No MaterialComponents parent remains. |
| UI-02 | 12-02 | All layouts updated to M3 component styles and attributes | ✓ SATISFIED | All 11 textAppearance attributes use M3 tokens. Plain `<Button>` replaced with `MaterialButton` in activity_download.xml and activity_smoke_test.xml. |
| UI-03 | 12-02 | Dynamic color support (Material You on Android 12+) | ? HUMAN | DynamicColors.applyToActivitiesIfAvailable wired in UnmuffleApplication.kt and registered in AndroidManifest.xml. Visual effect requires Android 12+ device. |

No orphaned requirements — all 8 phase-12 requirement IDs (REBRAND-01 through REBRAND-04, FIX-01, UI-01 through UI-03) are accounted for across plans 12-01 and 12-02.

---

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `app/src/main/cpp/CMakeLists.txt` | 2 | `project(whisper_transchat)` — CMake project name not updated | ℹ Info | None. CMake `project()` name is an IDE/cache display name only. Shared library filenames are controlled by `add_library` calls (`whisper_v8fp16_va`, `whisper`). Does not affect JNI symbol resolution, runtime behavior, APK contents, or build correctness. |

No TODO/FIXME/placeholder comments found in phase-12 modified files. No stub implementations detected. No empty handlers. No return-null implementations.

---

### Human Verification Required

#### 1. FIX-01 and REBRAND-01 — Layout overlap and branding on device

**Test:** Install debug APK on an Android device. Share a voice message to Unmuffle. On the transcription result screen:
- Confirm the title area (MaterialToolbar showing "Unmuffle") is below the status bar, not overlapping it
- Confirm transcript text is visible and not hidden behind the toolbar
- Confirm Copy and Share buttons are fully visible and tappable (not behind the navigation bar)

**Expected:** No content overlap with system bars. "Unmuffle" visible in launcher, share sheet picker, and in-app toolbar. All tap targets accessible.

**Why human:** The fitsSystemWindows + MaterialToolbar fix (commit 068064b) was verified by the user during the plan 12-02 Task 3 device checkpoint. This verification cannot be re-run programmatically — layout inset behavior is only observable on a running device with real system bars.

#### 2. UI-03 — Dynamic color (Material You) on Android 12+

**Test:** On an Android 12+ device with a non-default wallpaper, open Unmuffle. Observe button colors, background tints, and toolbar color.

**Expected:** Colors adapt to the wallpaper palette (Material You theming). On Android 11 and below, standard Material 3 colors apply with no adaptation.

**Why human:** `DynamicColors.applyToActivitiesIfAvailable(this)` is correctly wired and registered. Whether it visually applies requires a real Android 12+ device with wallpaper-based theming active — no static analysis can confirm the visual output.

---

### Gaps Summary

No gaps found. All automated must-haves are verified. The two human verification items (FIX-01 visual layout, UI-03 dynamic color) were confirmed by the user during the plan 12-02 Task 3 checkpoint per SUMMARY.md. The automated checks all pass.

One cosmetic note: `app/src/main/cpp/CMakeLists.txt` line 2 retains `project(whisper_transchat)` — this is a display-only CMake project name with no runtime or build impact. It can be renamed to `project(unmuffle)` in a future cleanup pass if desired, but does not block any requirement.

---

_Verified: 2026-03-01T19:27:21Z_
_Verifier: Claude (gsd-verifier)_
