# Technology Stack

**Project:** Unmuffle v1.3 — Language Selection, Settings Page, Model Choice, UI Polish, App Rename
**Researched:** 2026-03-01
**Research Mode:** Milestone-scoped (v1.3 additions only)
**Confidence:** HIGH for all primary recommendations

**Scope note:** v1.0–v1.2 stack is validated and locked. This document covers ONLY what changes or is newly required for v1.3. Do not re-research: whisper.cpp v1.8.3, Kotlin, NDK r27.2, MediaCodec, ViewModel + StateFlow, coroutines, R8, GitHub Actions.

---

## What Is NOT Changing

The following decisions from prior milestones are locked:

- whisper.cpp v1.8.3 via JNI (working, production-verified)
- NDK r27c (27.2.12479018) — 16KB page size support
- compileSdk/targetSdk 35, minSdk 26
- arm64-v8a only ABI filter
- Kotlin + AndroidViewModel + StateFlow pattern
- MediaCodec + linear interpolation resampler
- newSingleThreadExecutor for whisper.cpp serialization
- AtomicInteger progress polling (200ms interval)
- R8 with surgical proguard-rules.pro
- AGP 8.7.3, Kotlin 2.0.21, lifecycle 2.8.7, activity 1.9.3, coroutines 1.9.0

The existing UI is **View-based XML** (not Jetpack Compose). `TranscriptionActivity` uses `setContentView()`, `findViewById()`, `MaterialButton`, and `CircularProgressIndicator`. This is confirmed from the source. The milestone context's mention of "Jetpack Compose" was incorrect — the codebase uses XML Views throughout. All v1.3 UI work stays in XML Views.

---

## Feature 1: Persistent Preferences (Language Default, Model Choice)

### Recommended: `androidx.datastore:datastore-preferences:1.2.0`

**Why DataStore over SharedPreferences:**
DataStore is Google's official replacement for SharedPreferences. It runs on Kotlin Coroutines + Flow — meaning reads/writes happen on IO dispatcher without blocking the UI thread. SharedPreferences' `apply()` has a documented race condition on process death (writes can be lost). DataStore has no such race. For v1.3, preferences are simple key-value (language code string, model name string) — Preferences DataStore (not Proto DataStore) is the right tier.

**Version:** `1.2.0` — stable, released November 19, 2025. Bug-fix notable: fixes `java.lang.UnsatisfiedLinkError` in R8-optimized apps. This matters because the project uses R8 shrinking. No alpha or snapshot versions needed.

**Dependency to add to `libs.versions.toml`:**

```toml
[versions]
datastore = "1.2.0"

[libraries]
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
```

**Add to `app/build.gradle.kts`:**

```kotlin
implementation(libs.androidx.datastore.preferences)
```

**Usage pattern (fits existing ViewModel + coroutines pattern):**

```kotlin
// In Application or singleton — create once
val Context.dataStore by preferencesDataStore(name = "settings")

// Keys
val PREF_LANGUAGE = stringPreferencesKey("language")
val PREF_MODEL    = stringPreferencesKey("model")

// Read (Flow, collect in ViewModel)
context.dataStore.data.map { prefs -> prefs[PREF_LANGUAGE] ?: "auto" }

// Write (from coroutine)
context.dataStore.edit { prefs -> prefs[PREF_LANGUAGE] = "es" }
```

**Integration with existing ViewModel:** `TranscriptionViewModel` already extends `AndroidViewModel(app)` — it has `Application` context available. Reading preferences via `viewModelScope.launch` + collecting the DataStore Flow is a natural fit.

**What NOT to use:** `PreferenceFragmentCompat` (androidx.preference library). It is tightly coupled to SharedPreferences, has no native DataStore support, and the settings screen for v1.3 is simple enough (2 preferences) that a hand-written Activity with `MaterialAlertDialogBuilder` pickers is cleaner, faster to build, and does not require an additional dependency.

**Confidence: HIGH** — confirmed from official Android Developers documentation and verified version 1.2.0 as latest stable (released November 2025).

---

## Feature 2: Language Selection Dialog on Share Intent

### No New Library — Use Existing `MaterialAlertDialogBuilder`

The app already depends on `com.google.android.material:material:1.12.0` (confirmed in `libs.versions.toml`). `MaterialAlertDialogBuilder` with `setSingleChoiceItems()` is the correct, already-available pattern for a language picker.

**Upgrade material to 1.13.0:**
Material 1.13.0 is the latest stable (released September 2024 per GitHub releases). It adds `Theme.Material3.DayNight.NoActionBar` as the canonical M3 theme parent for XML views, and includes fixes for dialog styling in M3 contexts. Since v1.3 also upgrades the theme to Material 3, bump material to 1.13.0 at the same time.

```toml
material = "1.13.0"
```

**Language picker pattern in `ShareReceiverActivity`:**

```kotlin
val languages = arrayOf("Auto-detect", "English", "Spanish", "French", "German", "Portuguese", "Italian")
val languageCodes = arrayOf("auto", "en", "es", "fr", "de", "pt", "it")

// Read saved preference first (blocking OK in onCreate — DataStore has synchronous workaround via runBlocking or read from ViewModel)
val savedCode = runBlocking { context.dataStore.data.map { it[PREF_LANGUAGE] ?: "auto" }.first() }
val checkedItem = languageCodes.indexOf(savedCode).coerceAtLeast(0)

MaterialAlertDialogBuilder(this)
    .setTitle("Transcription Language")
    .setSingleChoiceItems(languages, checkedItem) { dialog, which ->
        selectedLanguage = languageCodes[which]
    }
    .setPositiveButton("Transcribe") { _, _ ->
        // Optionally save choice, then launch TranscriptionActivity
    }
    .setNegativeButton("Cancel") { _, _ -> finish() }
    .show()
```

**"Remember my choice" checkbox:** Add a `CheckBox` to a custom dialog layout via `setView()`, or use a second `MaterialAlertDialogBuilder` with `setMultiChoiceItems()` for the checkbox. Simpler: just always save the selection to DataStore on confirm — the dialog pre-selects the saved value on next open, which is the implicit "remember" behavior.

**Threading note:** `runBlocking` in `onCreate` for a DataStore read is acceptable here because: (a) it reads from a file on first access (~1ms after initial creation, near-zero thereafter since DataStore caches in memory), and (b) `ShareReceiverActivity` is already doing a synchronous `AudioCopier.copyToCache()` in `onCreate` — the precedent for sync work in `onCreate` is established. On subsequent opens, DataStore holds the prefs in memory so the read is effectively instant.

**What NOT to use:** A `Spinner` widget in a Fragment, or `ExposedDropdownMenuBox` from Material Design — both introduce Activity lifecycle complexity for what is fundamentally a one-shot modal choice. `setSingleChoiceItems` is purpose-built for this and dismisses naturally.

**Confidence: HIGH** — `MaterialAlertDialogBuilder.setSingleChoiceItems` is Android's standard approach, documented at developer.android.com/develop/ui/views/components/dialogs.

---

## Feature 3: Model Selection (Tiny vs. Small)

### No New Library — JNI Bridge Already Handles This

Model selection is a pure configuration change: store the model name in DataStore, load the appropriate file at runtime. The JNI bridge (`WhisperLib`, `WhisperContext`, `WhisperEngine`) already accepts an arbitrary file path via `loadModel(absoluteFilePath: String)` — no JNI changes are required.

**Available models (confirmed from HuggingFace ggerganov/whisper.cpp):**

| Model | File | Size | Use Case |
|-------|------|------|----------|
| tiny q5_1 multilingual | `ggml-tiny-q5_1.bin` | 32.2 MB | Fast, lower accuracy, good for short clips |
| small q5_1 multilingual | `ggml-small-q5_1.bin` | 190 MB | Current default, balanced accuracy/speed |

**Download URL for tiny:**
`https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin`

**`ModelManager` changes required:**
- Add `MODEL_TINY_FILENAME`, `MODEL_TINY_URL`, `MODEL_TINY_SHA256`, `MODEL_TINY_SIZE_BYTES` constants
- Add `modelFile(context, modelName)` overload that accepts the model name
- `DownloadViewModel` and `DownloadActivity` will need to know which model to fetch

**Language parameter for whisper_jni.cpp:**
The `params.language` field in `fullTranscribe` is currently hardcoded to `"auto"`. For v1.3, pass the language code from Kotlin through JNI:

```cpp
// Extend the JNI function signature:
JNIEXPORT void JNICALL
Java_com_simonpena_transchat_WhisperLib_00024Companion_fullTranscribe(
    JNIEnv *env, jobject, jlong contextPtr, jint numThreads,
    jfloatArray audioData, jstring languageCode)  // add this param
{
    const char* lang = env->GetStringUTFChars(languageCode, nullptr);
    params.language = lang;
    // ... rest unchanged ...
    env->ReleaseStringUTFChars(languageCode, lang);
}
```

Valid language codes for whisper.cpp: `"auto"`, `"en"`, `"es"`, `"fr"`, `"de"`, `"pt"`, `"it"`, `"zh"`, `"ja"`, `"ko"`, `"ru"`, `"ar"` — full list in whisper.cpp source at `src/whisper.cpp` around the `LANGUAGES` map. Use `"auto"` to let the multilingual model detect.

**What NOT to do:** Download both models at first launch. Keep the download model selection in `DownloadActivity` — user picks which model during onboarding. Switching models after install means downloading the new one on demand (or presenting a warning). Keep it simple: one active model at a time, new download required when switching to a larger model.

**Confidence: HIGH** — model file names and URLs verified directly from HuggingFace repository. JNI language parameter is confirmed in whisper_jni.cpp source (currently `params.language = "auto"`).

---

## Feature 4: Material 3 / Modern Android UI Polish

### Theme Migration: MaterialComponents → Material3

**Current theme (confirmed from `app/src/main/res/values/themes.xml`):**

```xml
<style name="Theme.TransChat" parent="Theme.MaterialComponents.DayNight.DarkActionBar">
```

**Target theme for v1.3:**

```xml
<style name="Theme.Unmuffle" parent="Theme.Material3.DayNight.NoActionBar">
```

**Why `Theme.Material3.DayNight.NoActionBar`:**
- Confirmed M3 theme parent for XML/View-based apps (not Compose) from MDC-Android getting-started docs
- `NoActionBar` because the app uses no Toolbar/ActionBar — existing activities are fullscreen or near-fullscreen
- `DayNight` for automatic dark/light mode switching — no additional code required
- Requires `com.google.android.material >= 1.5.0` (already at 1.12.0, upgrading to 1.13.0)

**Material3 introduces Dynamic Color (Android 12+):** On Android 12+ devices, `DynamicColors.applyToActivitiesIfAvailable(this)` in `Application.onCreate()` makes the app pick up the user's wallpaper-derived color scheme. This is a one-line addition in a custom Application class. Optional but recommended for modern polish.

**Layout regression fix for `activity_transcription.xml`:** The current layout uses `FrameLayout` as root with multiple `LinearLayout` children set to `match_parent` height. When all four groups (progress, result, no-speech, error) are `match_parent`, the result group's buttons at the bottom can be occluded by system navigation bars. The fix:
- Use `WindowCompat.setDecorFitsSystemWindows(window, false)` + `ViewCompat.setOnApplyWindowInsetsListener` on the root view, OR
- Simply change the result group from `android:layout_height="match_parent"` to `android:layout_height="wrap_content"` with a `ScrollView` wrapping the full result section

**Specific view attributes that change in M3:**
- `?attr/textAppearanceBody1` → `?attr/textAppearanceBodyLarge` (M3 renamed the attr)
- `?attr/textAppearanceBody2` → `?attr/textAppearanceBodyMedium`
- `?attr/textAppearanceHeadline6` → `?attr/textAppearanceTitleLarge`
- `?attr/materialButtonOutlinedStyle` → still valid in M3 (no change needed)
- `CircularProgressIndicator` — still valid in M3 (no change needed)

**No new Gradle dependencies needed for Material 3** — bumping `material` from 1.12.0 → 1.13.0 in `libs.versions.toml` is sufficient. Material3 themes and components are part of the same `com.google.android.material` artifact.

**Dynamic Color (optional one-liner):**

```kotlin
// In Application class (create UnmuffleApplication if not present):
class UnmuffleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
```

Then register in `AndroidManifest.xml`:
```xml
<application android:name=".UnmuffleApplication" ...>
```

Import: `com.google.android.material.color.DynamicColors` — included in `material:1.13.0`, no additional dependency.

**Confidence: HIGH** — theme parent name confirmed from MDC-Android docs. Material 1.13.0 confirmed latest stable (September 2024). TextAppearance attr renames confirmed from Material 3 migration guide.

---

## Feature 5: App Rename — TransChat → Unmuffle

### What Changes (No New Libraries)

This is a mechanical rename across multiple files. No new Gradle dependencies. The Android build system separates `namespace` (Java package, used for R class and JNI mangling) from `applicationId` (Play Store / F-Droid identity). They can differ.

**Decision: rename namespace AND applicationId together**

Since this app has not been published to Play Store yet (F-Droid submission is pending), changing `applicationId` does not break existing installs. Both should be updated to `com.simonpena.unmuffle` for consistency.

**Files to update:**

| File | What Changes |
|------|-------------|
| `app/build.gradle.kts` | `namespace = "com.simonpena.unmuffle"` and `applicationId = "com.simonpena.unmuffle"` |
| `whisper_jni.cpp` | JNI function names use the package: `Java_com_simonpena_transchat_WhisperLib_...` → `Java_com_simonpena_unmuffle_WhisperLib_...` |
| All `*.kt` files | `package com.simonpena.transchat` → `package com.simonpena.unmuffle` |
| `AndroidManifest.xml` | No explicit `package` attribute needed (AGP uses `namespace`), but check for any hardcoded package references |
| `app/src/main/res/values/strings.xml` | `app_name` string: `TransChat` → `Unmuffle` |
| `app/src/main/res/values/themes.xml` | Theme name: `Theme.TransChat` → `Theme.Unmuffle` |
| `README.md` | All references to "TransChat" → "Unmuffle" |
| `.github/workflows/*.yml` | Any references to `transchat` in artifact names, app labels |
| `proguard-rules.pro` | Package path: `-keep class com.simonpena.transchat.WhisperLib` → `com.simonpena.unmuffle.WhisperLib` |

**CRITICAL — JNI function name mangling:**
The JNI functions in `whisper_jni.cpp` are named using the Kotlin package + class name. Renaming the Kotlin package **without** updating `whisper_jni.cpp` will cause `UnsatisfiedLinkError` at runtime — the C++ exported symbols will not match what the JNI loader expects.

Current:
```cpp
Java_com_simonpena_transchat_WhisperLib_00024Companion_initContext
```

After rename:
```cpp
Java_com_simonpena_unmuffle_WhisperLib_00024Companion_initContext
```

The `00024` is the JNI encoding of `$` (the Kotlin companion object delimiter) — it stays unchanged.

**DataStore file name:** If a DataStore instance is created with `preferencesDataStore(name = "settings")`, the backing file is `files/datastore/settings.preferences_pb`. This does not contain the package name — no migration issue.

**Confidence: HIGH** — all rename mechanics are standard Android build system behavior, confirmed from official documentation. JNI mangling rule is deterministic.

---

## Settings Activity (New Screen)

### No New Library — Hand-written Activity with `MaterialAlertDialogBuilder`

For two preference items (language default, model selection), a dedicated `PreferenceFragmentCompat` setup (which adds `androidx.preference` as a dependency and requires a separate XML preference file) is disproportionate overhead. The simpler approach:

A plain `AppCompatActivity` with a `RecyclerView` or simple `LinearLayout` showing two tappable rows. Each row opens a `MaterialAlertDialogBuilder` picker on click. The selected value is shown as a subtitle on the row. Values are persisted to DataStore.

**Pattern (already in use in `DownloadActivity` / `ReadyActivity`):** No new paradigms. Reuse existing `AppCompatActivity` + `MaterialButton` + DataStore pattern established by Feature 1.

**Launch from:** A settings icon (`MaterialButton` or `Toolbar` menu item) added to `TranscriptionActivity` or `ReadyActivity`. Since there's no Navigation component in this app, a simple `startActivity(Intent(this, SettingsActivity::class.java))` suffices.

**Confidence: HIGH** — no external research needed, direct extension of existing patterns.

---

## New Dependencies Summary

| Library | Version | Purpose | Replaces / New |
|---------|---------|---------|---------------|
| `androidx.datastore:datastore-preferences` | 1.2.0 | Persist language and model preferences | New — replaces SharedPreferences (none currently used) |

| Library | Old Version | New Version | Reason |
|---------|-------------|-------------|--------|
| `com.google.android.material:material` | 1.12.0 | 1.13.0 | Material 3 theme support, latest stable |

**All other dependencies remain unchanged.**

---

## Updated `libs.versions.toml`

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
coreKtx = "1.15.0"
appcompat = "1.7.0"
material = "1.13.0"         # bumped from 1.12.0 for Material 3
coroutines = "1.9.0"
lifecycle = "2.8.7"
activity = "1.9.3"
datastore = "1.2.0"         # new

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
androidx-lifecycle-viewmodel-ktx = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
androidx-activity-ktx = { group = "androidx.activity", name = "activity-ktx", version.ref = "activity" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }  # new

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

---

## Alternatives Considered

| Recommended | Alternative | Why Not |
|-------------|-------------|---------|
| DataStore Preferences 1.2.0 | SharedPreferences | Race condition on process death; deprecated by Google for new code; DataStore is the official 2025 recommendation |
| DataStore Preferences 1.2.0 | Proto DataStore | Proto DataStore requires Protocol Buffers schema; overkill for 2 string preferences |
| Hand-written SettingsActivity | `PreferenceFragmentCompat` | Adds `androidx.preference` dependency; tightly coupled to SharedPreferences; more boilerplate for 2 prefs than a custom Activity |
| Material 1.13.0 with Theme.Material3 | Stay on Theme.MaterialComponents | MaterialComponents is deprecated path; M3 is the current design system; migration is low-risk at this project size |
| MaterialAlertDialogBuilder.setSingleChoiceItems | ExposedDropdownMenuBox | DropdownMenu requires embedding in a form layout; dialog pattern is more appropriate for a modal choice before transcription starts |
| Rename namespace + applicationId both | Rename only applicationId, keep namespace | Mismatched namespace/applicationId adds confusion; app is pre-publish so no install compatibility risk |

---

## What NOT to Add

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| Jetpack Compose | App is View-based; adding Compose for 3 screens would require `compose-bom`, `activity-compose`, `material3` Compose artifact, `lifecycle-runtime-compose` — major scope increase for v1.3 | Stick with XML Views; the existing stack works and is familiar |
| `androidx.preference:preference` | Couples settings to SharedPreferences; fighting the DataStore migration | Hand-written SettingsActivity |
| Navigation Component | Only adds value at 5+ destinations; the app has a linear flow (Download → Ready → [Share] → Transcription → Result) | Direct `startActivity()` intent chain |
| Room database | No persistent transcript history by design (privacy model) | Not applicable |
| Hilt / Dagger | Dependency injection framework is overkill for an app with 1 ViewModel and 1 object singleton (ModelManager) | Manual constructor injection or Application-level singletons |

---

## Version Compatibility

| Package | Compatible With | Notes |
|---------|-----------------|-------|
| `datastore-preferences:1.2.0` | AGP 8.7.3, Kotlin 2.0.21, coroutines 1.9.0 | Confirmed — DataStore 1.2.0 uses Kotlin coroutines, fully compatible with 1.9.0 |
| `material:1.13.0` | compileSdk 35, minSdk 26 | Confirmed — MDC-Android 1.13.0 minSdk is 21, compileSdk 35 is supported |
| `Theme.Material3.DayNight.NoActionBar` | `material >= 1.5.0` | Confirmed — 1.13.0 well exceeds the 1.5.0 threshold for M3 themes |
| DataStore 1.2.0 R8 compatibility | R8 with `isMinifyEnabled = true` | Specifically fixed in 1.2.0 (UnsatisfiedLinkError fix in release notes) |

---

## Confidence Assessment

| Area | Confidence | Source | Notes |
|------|------------|--------|-------|
| DataStore Preferences 1.2.0 | HIGH | Official Android Developers docs + release notes (November 2025) | R8 UnsatisfiedLinkError fix is specifically relevant to this project |
| Material 1.13.0 stable | HIGH | GitHub releases (material-components-android, September 2024) | Confirmed latest stable; 1.14.0 requires M3 Expressive themes, avoid until stable |
| Theme.Material3.DayNight.NoActionBar | HIGH | MDC-Android getting-started.md official docs | Confirmed as canonical M3 parent for XML view-based apps |
| MaterialAlertDialogBuilder.setSingleChoiceItems | HIGH | Android Developers documentation + MaterialAlertDialogBuilder API reference | Standard, documented, no gotchas |
| JNI rename mechanics (00024 mangling) | HIGH | Android NDK JNI documentation (deterministic name mangling algorithm) | Package rename is a high-risk area — JNI names must stay in sync |
| whisper-tiny-q5_1 model file | HIGH | Verified directly from HuggingFace ggerganov/whisper.cpp tree | 32.2 MB, confirmed present |
| Language parameter in whisper_jni.cpp | HIGH | Direct inspection of whisper_jni.cpp in repository | `params.language = "auto"` is confirmed hardcoded; needs parameterization |

---

## Sources

- [Android Developers — DataStore](https://developer.android.com/topic/libraries/architecture/datastore) — official DataStore documentation
- [AndroidX DataStore Releases](https://developer.android.com/jetpack/androidx/releases/datastore) — confirmed 1.2.0 stable, released November 19, 2025
- [MDC-Android Getting Started](https://github.com/material-components/material-components-android/blob/master/docs/getting-started.md) — Theme.Material3.DayNight.NoActionBar confirmed; 1.14.0 required for Material3Expressive
- [material-components-android GitHub Releases](https://github.com/material-components/material-components-android/releases) — 1.13.0 confirmed latest stable (September 2024)
- [Android Developers — Dialogs](https://developer.android.com/develop/ui/views/components/dialogs) — MaterialAlertDialogBuilder setSingleChoiceItems pattern
- [MaterialAlertDialogBuilder API Reference](https://developer.android.com/reference/com/google/android/material/dialog/MaterialAlertDialogBuilder) — confirmed setSingleChoiceItems API
- [Material Design 3 for MDC-Android](https://m3.material.io/develop/android/mdc-android) — M3 on Views overview
- [HuggingFace ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp/tree/main) — ggml-tiny-q5_1.bin (32.2 MB) and ggml-small-q5_1.bin (190 MB) confirmed
- `app/src/main/cpp/whisper_jni.cpp` — in-repo, confirms `params.language = "auto"` hardcoded, JNI function name pattern
- `app/src/main/res/values/themes.xml` — in-repo, confirms current MaterialComponents theme
- `gradle/libs.versions.toml` — in-repo, confirms current dependency versions

---

*Stack research for: Unmuffle v1.3 — language selection, settings, model choice, M3 UI polish, app rename*
*Researched: 2026-03-01*
