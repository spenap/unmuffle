# Phase 12: App Rename + Material 3 + Layout Fix - Research

**Researched:** 2026-03-01
**Domain:** Android package rename, JNI symbol update, Material 3 theme migration, XML layout fix
**Confidence:** HIGH

## Summary

Phase 12 is a coordinated rename + retheme. It breaks cleanly into two plans: (1) an atomic package rename
that touches the JNI C++ symbols, Kotlin namespace, applicationId, proguard rules, and string literals; and
(2) a documentation + M3 theme + layout fix plan. No new libraries need to be evaluated — the existing
`com.google.android.material:material` dependency already ships Material 3 as of the current version
(1.12.0 in use; upgrade to 1.13.0 stable as required by the roadmap). All UI work is XML Views (Compose is
explicitly out of scope per REQUIREMENTS.md).

The FIX-01 layout regression root cause: `TranscriptionActivity` uses `Theme.TransChat` which inherits from
`Theme.MaterialComponents.DayNight.DarkActionBar`. This renders a system action bar above the content area.
The `activity_transcription.xml` uses a bare `FrameLayout` root with `match_parent` height — it does not
call `supportActionBar?.hide()` or use a `NoActionBar` theme, so the action bar overlaps the top of the
content. The fix is switching `TranscriptionActivity` (and all other activities) to
`Theme.Material3.DayNight.NoActionBar`, which removes the action bar entirely and resolves the overlap.

Dynamic color (Material You, Android 12+) requires creating an `UnmuffleApplication : Application` class,
calling `DynamicColors.applyToActivitiesIfAvailable(this)` in `onCreate()`, and registering the class in
AndroidManifest. Dynamic color is gated to Android 12+ automatically by the Material library — no manual
API level check needed.

**Primary recommendation:** Rename atomically in plan 12-01 (JNI + package), then layer M3 theme +
documentation + layout fix in plan 12-02. Never split JNI and package changes across plans.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| REBRAND-01 | App name displayed as "Unmuffle" everywhere (launcher, share sheet, app bar) | Update `android:label` in AndroidManifest application element and `android:label` on ShareReceiverActivity; update `activity_ready.xml` and `activity_download.xml` hardcoded strings; update `rootProject.name` in `settings.gradle.kts` |
| REBRAND-02 | Package renamed com.simonpena.transchat → com.simonpena.unmuffle (namespace, applicationId, JNI C++ symbols) | Rename directory `app/src/main/java/com/simonpena/transchat/` → `unmuffle/`; update `namespace` and `applicationId` in `app/build.gradle.kts`; rename all 7 JNI function signatures in `whisper_jni.cpp`; update `package` declarations in all 19 Kotlin files; update proguard-rules.pro |
| REBRAND-03 | README, CI workflow, and all documentation reference "Unmuffle" | Update `README.md` (title, body, clone URL); `build.yml` has no "TransChat" references already (no change needed); update `.planning/` docs as needed |
| REBRAND-04 | GitHub repository renamed from transchat-app to unmuffle (remote URL updated) | Manual step — cannot be automated; plan must provide `git remote set-url origin` command for user to run |
| FIX-01 | Layout regression fixed — transcript text not overlapped by title, copy/share buttons fully visible | Root cause is `Theme.TransChat` (DarkActionBar) on `TranscriptionActivity`; fix via `Theme.Material3.DayNight.NoActionBar` for all activities that show UI |
| UI-01 | Material 3 theme applied (Theme.Material3.DayNight) | Replace `Theme.MaterialComponents.DayNight.DarkActionBar` parent in `themes.xml` with `Theme.Material3.DayNight.NoActionBar`; rename theme from `Theme.TransChat` to `Theme.Unmuffle` |
| UI-02 | All layouts updated to M3 component styles and attributes | Replace M2 `?attr/textAppearanceBody1` → `?attr/textAppearanceBodyLarge`; `textAppearanceBody2` → `textAppearanceBodyMedium`; `textAppearanceHeadline5` → `textAppearanceHeadlineMedium`; `textAppearanceHeadline6` → `textAppearanceTitleLarge`; `textAppearanceCaption` → `textAppearanceBodySmall`; upgrade plain `<Button>` to `<com.google.android.material.button.MaterialButton>` |
| UI-03 | Dynamic color support (Material You on Android 12+) | Create `UnmuffleApplication : Application`, call `DynamicColors.applyToActivitiesIfAvailable(this)`, register in AndroidManifest with `android:name` attribute on `<application>` |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| com.google.android.material:material | 1.13.0 | Material 3 theme, components, DynamicColors API | Already in project (1.12.0); 1.13.0 is the current stable release (2024-09-03); provides `Theme.Material3`, `DynamicColors`, M3 typography |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| (no new libraries needed) | — | — | All needed APIs are in existing material + appcompat dependencies |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Theme.Material3.DayNight.NoActionBar | Theme.Material3.DayNight | DayNight includes an action bar; NoActionBar variant is required to eliminate the action bar causing FIX-01 overlap |
| DynamicColors.applyToActivitiesIfAvailable | Per-activity DynamicColors.applyIfAvailable | App-level call is simpler and covers all current and future activities automatically |

## Architecture Patterns

### Recommended Project Structure (after rename)

```
app/src/main/
├── java/com/simonpena/unmuffle/   # renamed from transchat/
│   ├── UnmuffleApplication.kt     # NEW — Application subclass for DynamicColors
│   ├── MainActivity.kt
│   ├── DownloadActivity.kt
│   ├── ReadyActivity.kt
│   ├── ShareReceiverActivity.kt
│   ├── TranscriptionActivity.kt
│   ├── SmokeTestActivity.kt
│   ├── TranscriptionViewModel.kt
│   ├── DownloadViewModel.kt
│   ├── WhisperLib.kt
│   ├── WhisperContext.kt           # (inside WhisperLib.kt)
│   ├── WhisperEngine.kt
│   ├── WhisperModel.kt
│   ├── SettingsRepository.kt
│   ├── ModelManager.kt
│   ├── AudioCopier.kt
│   ├── AudioDecoder.kt
│   ├── PcmResampler.kt
│   ├── WavWriter.kt
│   ├── HallucinationFilter.kt
│   └── WhisperCpuConfig.kt
├── cpp/
│   └── whisper_jni.cpp             # 7 JNI signatures: transchat → unmuffle
└── res/
    └── values/
        └── themes.xml              # Theme.TransChat → Theme.Unmuffle; parent → Material3
```

### Pattern 1: Atomic Package Rename
**What:** All files that encode the package name must change together in a single commit.
**When to use:** Any time a JNI package is involved — a partially-renamed state would cause `UnsatisfiedLinkError` at runtime because the C++ function names would not match the Kotlin class.
**Files that must change atomically:**
- `app/build.gradle.kts` — `namespace` and `applicationId`
- `app/proguard-rules.pro` — all `-keep class com.simonpena.transchat.*` rules
- `app/src/main/cpp/whisper_jni.cpp` — all 7 `Java_com_simonpena_transchat_WhisperLib_*` signatures
- All 19 Kotlin `.kt` files — `package com.simonpena.transchat` declarations
- `app/src/main/java/com/simonpena/transchat/` directory — renamed to `unmuffle/`
- `AndroidManifest.xml` — `android:label` strings, theme references (theme names change in plan 12-01 or 12-02; label changes in 12-01)
- `settings.gradle.kts` — `rootProject.name`
- `app/src/main/res/values/themes.xml` — theme names (Theme.TransChat → Theme.Unmuffle)
- Layout files — any hardcoded "TransChat" strings

### Pattern 2: JNI Symbol Renaming
**What:** JNI function names encode the fully-qualified class name with `_` replacing `.` and `$` encoded as `_00024`.
**Current signatures (7 total):**
```
Java_com_simonpena_transchat_WhisperLib_00024Companion_initContext
Java_com_simonpena_transchat_WhisperLib_00024Companion_fullTranscribe
Java_com_simonpena_transchat_WhisperLib_00024Companion_getTextSegmentCount
Java_com_simonpena_transchat_WhisperLib_00024Companion_getTextSegment
Java_com_simonpena_transchat_WhisperLib_00024Companion_freeContext
Java_com_simonpena_transchat_WhisperLib_00024Companion_getSystemInfo
Java_com_simonpena_transchat_WhisperLib_00024Companion_getProgress
```
**After rename (substitute `transchat` → `unmuffle`):**
```
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_initContext
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_fullTranscribe
... (same pattern for all 7)
```
**Rule:** Only the package segment changes (`transchat` → `unmuffle`). The class name `WhisperLib` and inner class encoding `_00024Companion` do NOT change — the C++ class is staying named `WhisperLib`.

### Pattern 3: Material 3 Theme Migration (XML Views)
**What:** Replace the `themes.xml` parent theme and rename theme styles; update `?attr/textAppearance*` attributes in all layouts.

**themes.xml before:**
```xml
<style name="Theme.TransChat" parent="Theme.MaterialComponents.DayNight.DarkActionBar" />
<style name="Theme.TransChat.Transparent" parent="Theme.MaterialComponents.DayNight.NoActionBar">
    <!-- transparent window items -->
</style>
```

**themes.xml after:**
```xml
<style name="Theme.Unmuffle" parent="Theme.Material3.DayNight.NoActionBar" />
<style name="Theme.Unmuffle.Transparent" parent="Theme.Material3.DayNight.NoActionBar">
    <!-- same transparent window items -->
</style>
```

**Key point:** `Theme.Material3.DayNight.NoActionBar` for the main theme eliminates the action bar entirely, which is the FIX-01 root cause fix. The `ReadyActivity` and `DownloadActivity` (which show content) also benefit from no action bar — they currently rely on the implicit action bar showing "TransChat" as title. After rename, the app bar title would show "Unmuffle" automatically through `android:label`, but removing the action bar is cleaner for these simple single-screen activities.

### Pattern 4: M2 → M3 textAppearance Attribute Mapping
**What:** Replace deprecated M2 `?attr/textAppearance*` names with M3 equivalents in all layout XMLs.

**Complete mapping for this codebase:**

| M2 Attribute | M3 Attribute | Used In |
|--------------|-------------|---------|
| `?attr/textAppearanceBody1` | `?attr/textAppearanceBodyLarge` | activity_transcription.xml (×2), activity_ready.xml, activity_download.xml |
| `?attr/textAppearanceBody2` | `?attr/textAppearanceBodyMedium` | activity_transcription.xml |
| `?attr/textAppearanceHeadline5` | `?attr/textAppearanceHeadlineMedium` | activity_ready.xml |
| `?attr/textAppearanceHeadline6` | `?attr/textAppearanceTitleLarge` | activity_transcription.xml, activity_smoke_test.xml |
| `?attr/textAppearanceCaption` | `?attr/textAppearanceBodySmall` | activity_download.xml (×2) |

**Confidence:** HIGH — verified from material-components-android `typography/res/values/styles.xml`.

### Pattern 5: Dynamic Color (Material You)
**What:** One-call app-wide opt-in using `DynamicColors` from material library.

**UnmuffleApplication.kt:**
```kotlin
package com.simonpena.unmuffle

import android.app.Application
import com.google.android.material.color.DynamicColors

class UnmuffleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
```

**AndroidManifest.xml application element:**
```xml
<application
    android:name=".UnmuffleApplication"
    android:label="Unmuffle"
    ...>
```

**Behavior:** On Android 12+ devices, the wallpaper-derived color palette overrides the fixed colors in the theme. On Android 11 and below, the static Material 3 colors apply unchanged. No API level guard needed — `applyToActivitiesIfAvailable` handles this internally.

### Pattern 6: REBRAND-04 Manual Step
**What:** GitHub repository rename cannot be automated — it requires the user to act on GitHub.com.
**Guide to include in plan 12-02:**
1. Go to github.com/spenap/transchat-app → Settings → Repository name → change to `unmuffle` → Rename
2. Run locally: `git remote set-url origin https://github.com/spenap/unmuffle.git`
3. Verify: `git remote -v`

### Anti-Patterns to Avoid
- **Partial JNI rename:** Renaming Kotlin package without updating `whisper_jni.cpp` (or vice versa) causes a silent `UnsatisfiedLinkError` at runtime — the `.so` is built with the old symbols, but the JVM looks for the new ones.
- **Using Theme.Material3.DayNight (with action bar):** The `DayNight` variant (without `NoActionBar`) still includes an action bar, which would perpetuate FIX-01. Always use `NoActionBar` unless an action bar is explicitly needed.
- **Using `?attr/textAppearanceHeadline6` after M3 theme switch:** M2 attribute names resolve to different sizes under M3 themes. They may still compile but render incorrectly. Replace all M2 names.
- **Forgetting proguard-rules.pro:** After package rename, the `-keep class com.simonpena.transchat.*` rules no longer protect the correct classes. The `.so` JNI lookup would succeed (C++ is renamed too), but sealed class hierarchies used in `when()` expressions could be stripped.
- **Renaming Kotlin source directory without updating namespace:** Android's `namespace` in `build.gradle.kts` controls generated `R` class package; `applicationId` controls the install package. Both must match.
- **Forgetting `settings.gradle.kts`:** `rootProject.name = "TransChat"` affects the project-level build output name and IDE display. Change to `"Unmuffle"`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Dynamic color wallpaper extraction | Custom WallpaperManager palette extraction | `DynamicColors.applyToActivitiesIfAvailable()` | Material library handles Android version gating, applies theme overlay, integrates with all M3 components automatically |
| Dark/light mode management | Manual day/night switching logic | `Theme.Material3.DayNight.NoActionBar` | DayNight parent theme delegates to system dark mode preference automatically |

**Key insight:** The M3 migration is purely a configuration change — no Java/Kotlin business logic changes, no new APIs to integrate beyond `DynamicColors`. All the complexity is in correctly mapping old attributes to new names.

## Common Pitfalls

### Pitfall 1: Split JNI + Package Rename Across Commits
**What goes wrong:** App crashes with `UnsatisfiedLinkError` on launch because `System.loadLibrary("whisper")` succeeds but `WhisperLib.initContext()` cannot be found — the `.so` exports `Java_com_simonpena_unmuffle_...` but the JVM asks for `Java_com_simonpena_transchat_...` (or vice versa).
**Why it happens:** The C++ `.so` is compiled from `whisper_jni.cpp` at build time. If `whisper_jni.cpp` is updated but the Kotlin class still uses the old package (or vice versa), the symbols don't match.
**How to avoid:** Change `whisper_jni.cpp`, all Kotlin `package` declarations, `namespace`, and `applicationId` in a single commit. Run a full build before committing anything else.
**Warning signs:** `UnsatisfiedLinkError: No implementation found for ... WhisperLib.initContext()`.

### Pitfall 2: TextAppearance M2/M3 Attribute Mix
**What goes wrong:** After switching to `Theme.Material3`, some text elements render at wrong sizes/weights because M2 attribute names (e.g., `textAppearanceHeadline6`) resolve to M3 equivalents that are scaled differently.
**Why it happens:** Material 3 remaps the M2 attribute names internally, but the mapping is not 1:1 in visual outcome. `textAppearanceHeadline6` maps to `TitleLarge` in M3, not `HeadlineSmall`.
**How to avoid:** Replace all `?attr/textAppearance*` usages to the explicit M3 name at migration time. The full mapping is documented above.
**Warning signs:** Text looks slightly too large or too small compared to design intent after theme switch.

### Pitfall 3: Theme Name References in Manifest After Rename
**What goes wrong:** After renaming themes from `Theme.TransChat` to `Theme.Unmuffle` in `themes.xml`, the manifest still references `@style/Theme.TransChat` — results in a `Resources$NotFoundException` crash at launch.
**Why it happens:** Android manifest `android:theme` attributes are resolved at runtime against the resource system. Renaming in `themes.xml` without updating manifest causes the style to be unfindable.
**How to avoid:** Update `themes.xml` and `AndroidManifest.xml` in the same edit pass. There are 3 theme references in the current manifest: application-level, MainActivity, ShareReceiverActivity, TranscriptionActivity.
**Warning signs:** App crashes immediately on launch with `android.content.res.Resources$NotFoundException: Resource ID #0x...`.

### Pitfall 4: Forgetting Package Directory Rename on Filesystem
**What goes wrong:** Kotlin files with `package com.simonpena.unmuffle` declarations are placed in `java/com/simonpena/transchat/` directory — Gradle still compiles them but R8 and lint may emit warnings; more importantly, it's inconsistent and future developer confusion is high.
**Why it happens:** Kotlin doesn't enforce that directory structure matches package name (unlike Java strict mode), so this doesn't fail the build, but Android Gradle Plugin expects the conventional layout.
**How to avoid:** Rename the directory `app/src/main/java/com/simonpena/transchat/` → `app/src/main/java/com/simonpena/unmuffle/` as part of plan 12-01.

### Pitfall 5: Missing android:name on Application Element for DynamicColors
**What goes wrong:** `UnmuffleApplication.kt` exists and compiles, but `DynamicColors.applyToActivitiesIfAvailable` is never called — dynamic color never applies.
**Why it happens:** Android only instantiates a custom Application subclass if `android:name=".UnmuffleApplication"` is present on the `<application>` element in `AndroidManifest.xml`. Without it, the default `Application` class is used.
**How to avoid:** Add `android:name=".UnmuffleApplication"` to the `<application>` tag in AndroidManifest.xml as part of the same plan that adds `UnmuffleApplication.kt`.

### Pitfall 6: Plain `<Button>` Elements Not Styled by M3
**What goes wrong:** After M3 theme switch, `<Button>` in `activity_download.xml` renders with AppCompat button style, not Material 3 FilledButton style, producing visual inconsistency.
**Why it happens:** The plain `<Button>` widget resolves to `AppCompatButton` via the AppCompat interceptor. The M3 theme does define a default button style, but the visual behavior may differ from explicit `MaterialButton`.
**How to avoid:** Replace `<Button>` with `<com.google.android.material.button.MaterialButton>` in `activity_download.xml` and `activity_smoke_test.xml` (both have plain `<Button>` elements).

## Code Examples

Verified patterns from official sources:

### JNI Signature After Rename (whisper_jni.cpp)
```cpp
// Before (transchat):
JNIEXPORT jlong JNICALL
Java_com_simonpena_transchat_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject /* thiz */, jstring modelPathStr) { ... }

// After (unmuffle) — only the package segment changes:
JNIEXPORT jlong JNICALL
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject /* thiz */, jstring modelPathStr) { ... }
```
Source: JNI naming spec — `Java_<pkg_with_underscores>_<ClassName>_<methodName>`; `$` encoded as `_00024`.

### Material 3 Theme (themes.xml)
```xml
<!-- Source: developer.android.com/develop/ui/views/theming/dynamic-colors -->
<resources>
    <style name="Theme.Unmuffle" parent="Theme.Material3.DayNight.NoActionBar">
    </style>

    <!-- Transparent theme for headless activities -->
    <style name="Theme.Unmuffle.Transparent" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="android:windowIsTranslucent">true</item>
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowContentOverlay">@null</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowIsFloating">true</item>
        <item name="android:backgroundDimEnabled">false</item>
    </style>
</resources>
```

### Dynamic Color Application class
```kotlin
// Source: developer.android.com/develop/ui/views/theming/dynamic-colors
package com.simonpena.unmuffle

import android.app.Application
import com.google.android.material.color.DynamicColors

class UnmuffleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
```

### build.gradle.kts after rename
```kotlin
android {
    namespace = "com.simonpena.unmuffle"  // was "com.simonpena.transchat"
    // ...
    defaultConfig {
        applicationId = "com.simonpena.unmuffle"  // was "com.simonpena.transchat"
        // ...
    }
}
```

### proguard-rules.pro after rename (pattern)
```
# JNI bridge — C++ resolves by JNI name (Java_com_simonpena_unmuffle_WhisperLib_00024Companion_*)
-keep class com.simonpena.unmuffle.WhisperLib { *; }
-keep class com.simonpena.unmuffle.WhisperLib$Companion { *; }

-keep class com.simonpena.unmuffle.DownloadViewModel$State { *; }
-keep class com.simonpena.unmuffle.DownloadViewModel$State$* { *; }
-keep class com.simonpena.unmuffle.UiState { *; }
-keep class com.simonpena.unmuffle.UiState$* { *; }
-keep class com.simonpena.unmuffle.AudioCopier$CopyResult { *; }
-keep class com.simonpena.unmuffle.AudioCopier$CopyResult$* { *; }
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Theme.MaterialComponents (M1/M2) | Theme.Material3 | Material library 1.5.0+ | M3 provides Material You color system, updated type scale, revised component styles |
| `?attr/textAppearanceHeadline6` etc. | `?attr/textAppearanceTitleLarge` etc. | M3 typography redesign | Different size/weight scales; M2 names still resolve but render differently |
| No Application class | `UnmuffleApplication` with `DynamicColors` | Android 12 (API 31) | Dynamic color from wallpaper applies to all Activities automatically |
| MaterialComponents.DayNight.DarkActionBar | Material3.DayNight.NoActionBar | M3 preference | Cleaner fullscreen layouts; eliminates action bar overlap |

**Deprecated/outdated:**
- `Theme.MaterialComponents.DayNight.DarkActionBar`: Still functional but M1/M2 era. Replace with `Theme.Material3.DayNight.NoActionBar`.
- M2 `?attr/textAppearanceBody1`, `textAppearanceBody2`, `textAppearanceHeadline5/6`, `textAppearanceCaption`: Deprecated in M3; replace with M3 equivalents.

## Open Questions

1. **Does `activity_transcription.xml` need a title bar or toolbar added?**
   - What we know: `TranscriptionActivity` currently has `Theme.TransChat` (DarkActionBar), which creates an implicit action bar showing "TransChat". After switching to NoActionBar, there is no visual header at all.
   - What's unclear: The roadmap says "app bar" shows "Unmuffle" (success criterion 1) — this may refer to the launcher/share sheet, not requiring a visual toolbar in TranscriptionActivity.
   - Recommendation: No toolbar needed in TranscriptionActivity — it's a transient full-screen sheet. The app bar criterion is satisfied by `android:label="Unmuffle"` in the launcher context (ReadyActivity/DownloadActivity). If a title is desired in TranscriptionActivity, it could be a simple `TextView` in the layout — but the plan description does not require this.

2. **Should `activity_smoke_test.xml`'s plain `<Button>` be replaced with MaterialButton?**
   - What we know: `SmokeTestActivity` is a developer tool, not user-facing.
   - What's unclear: Whether UI-02 applies to developer-only screens.
   - Recommendation: Yes — update it anyway for consistency. The M3 theme should apply uniformly across all Activities in the binary.

3. **Material library version: 1.13.0 vs 1.12.0**
   - What we know: Current version is 1.12.0; roadmap says "material 1.13.0 bump" in plan 12-02. 1.13.0 is confirmed stable (September 3, 2024).
   - What's unclear: Whether there are any breaking API changes between 1.12.0 and 1.13.0 relevant to this app.
   - Recommendation: Bump to 1.13.0 as the roadmap specifies. The 1.13.0 release notes show additions (new components) not removals relevant to existing code. Safe upgrade.

## Sources

### Primary (HIGH confidence)
- `app/src/main/cpp/whisper_jni.cpp` — 7 JNI signatures confirmed, `Java_com_simonpena_transchat_WhisperLib_00024Companion_*` pattern
- `app/src/main/res/values/themes.xml` — current theme: `Theme.MaterialComponents.DayNight.DarkActionBar`
- `app/src/main/AndroidManifest.xml` — theme assignments per Activity; labels
- `app/build.gradle.kts` — current `namespace = "com.simonpena.transchat"`, `applicationId = "com.simonpena.transchat"`, `material = "1.12.0"`
- `gradle/libs.versions.toml` — confirmed material version 1.12.0
- `app/proguard-rules.pro` — all keep rules use `com.simonpena.transchat`
- `app/src/main/res/layout/activity_transcription.xml` — FrameLayout root, result_group LinearLayout with layout_weight usage
- [GitHub: material-components-android typography/styles.xml](https://github.com/material-components/material-components-android/blob/master/lib/java/com/google/android/material/typography/res/values/styles.xml) — M2→M3 textAppearance mapping verified

### Secondary (MEDIUM confidence)
- [developer.android.com: Dynamic Colors](https://developer.android.com/develop/ui/views/theming/dynamic-colors) — `DynamicColors.applyToActivitiesIfAvailable(this)` API pattern
- [GitHub: material-components-android releases](https://github.com/material-components/material-components-android/releases) — 1.13.0 stable confirmed September 3, 2024

### Tertiary (LOW confidence)
- None

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — material library already in project; 1.13.0 stable release confirmed
- Architecture: HIGH — all source files read directly; JNI conventions well understood
- Pitfalls: HIGH — root cause of FIX-01 confirmed by reading themes.xml + manifest + layout files; JNI mismatch is a known Android NDK pitfall
- M3 textAppearance mapping: HIGH — verified from material-components-android source

**Research date:** 2026-03-01
**Valid until:** 2026-06-01 (material library stable releases are slow-moving)
