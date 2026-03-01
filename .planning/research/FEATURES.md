# Feature Research

**Domain:** Android on-device Whisper transcription app — v1.3 additions (language selection, settings, model management, UI polish)
**Researched:** 2026-03-01
**Confidence:** HIGH — existing codebase read directly; patterns verified against Android official docs and whisper.cpp source

---

## Scope

This document covers only the net-new features for v1.3. The existing core (share
intent, transcription pipeline, progress bar, Copy/Share result screen, model
download with WiFi gating, hallucination filter, R8 shrinking, CI) is already
shipped and not re-researched here.

---

## Feature Landscape

### Table Stakes (Users Expect These)

Features that feel broken or incomplete if missing from v1.3.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| **Layout bug fix** — title overlaps transcript, buttons partially occluded | A broken result screen is disqualifying; first thing the user sees after every transcription | LOW | `activity_transcription.xml` result group has a `ScrollView` with `layout_weight="1"` but no title `TextView` above it inside the `LinearLayout`. The result group `LinearLayout` has no title — the overlap is likely the Toolbar or a missing `paddingTop`. Fix: add a title `TextView` at the top of the result `LinearLayout` before the `ScrollView`. Ensure the buttons `LinearLayout` at the bottom is not cut off — use `wrap_content` height, not weight=0. Pure XML fix, no Kotlin changes. |
| **App rename: TransChat → Unmuffle** | App icon label and share target say "TransChat"; confusing after any public announcement of the rename | LOW | Touch points: `android:label` in `AndroidManifest.xml` (top `<application>` + `ShareReceiverActivity`), `app_name` in `strings.xml` (create it if missing), README title and badge, GitHub repo description, CI workflow comments. Package namespace (`com.simonpena.transchat`) can stay — `applicationId` is the Play/F-Droid identifier, namespace is internal. Rename the namespace only if targeting F-Droid; F-Droid accepts `applicationId` mismatch with namespace. |
| **Fix README permissions claim** | README says app has "no network permissions" but manifest has `INTERNET` + `ACCESS_NETWORK_STATE` for model download | LOW | Single-sentence README edit. No code change. |
| **Language selection dialog on share intent** | Users sharing non-English voice messages need to set the language; "auto" is slower and less accurate when language is known | MEDIUM | `AlertDialog` with single-choice radio list of common languages + an "Other" option. "Remember my choice" checkbox underneath. On confirm: persist to `SharedPreferences`, pass 2-char language code to `TranscriptionActivity` via `Intent.putExtra`. If remembered: `ShareReceiverActivity` skips dialog. Audio must be copied to cache FIRST (current pattern, synchronous, before any async) — then show the dialog — then launch `TranscriptionActivity`. URI permission survives while the sending app is still in the foreground; the dialog is fast enough that this is not a practical problem, but the cache copy already happens synchronously before the dialog is shown. |
| **"Remember my choice" persistent preference** | Without persistence, the dialog is friction on every share; users will abandon the app | LOW | `SharedPreferences` (synchronous, no coroutine overhead — correct for a value read in `onCreate`). Keys: `pref_language` (String, default `"auto"`), `pref_language_remembered` (Boolean, default `false`). If `pref_language_remembered == true`, skip the dialog entirely and pass `pref_language` directly to `TranscriptionActivity`. |

### Differentiators (Competitive Advantage)

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| **Settings page** (language default + model selection) | Power users want control without being prompted; surfaces the "private by default" story | MEDIUM | Single-screen `SettingsActivity` using `PreferenceFragmentCompat` + `res/xml/preferences.xml`. Two `ListPreference` items: default language, active model. Access: overflow menu icon in `ReadyActivity`. Reads/writes the same `SharedPreferences` keys as the language dialog. No Compose needed — app is View-based; `PreferenceFragmentCompat` integrates with existing `AppCompat`/Material theme. |
| **Tiny model option in settings** (~32MB vs ~190MB) | Older or low-storage devices can use a faster, lighter model; power users keep small for accuracy | HIGH | `ggml-tiny-q5_1.bin` is multilingual, ~32MB. `ggml-small-q5_1.bin` is the existing model, ~190MB. Switching requires: downloading the target model (if not present), then `WhisperEngine` picks up the correct path on next transcription. `ModelManager` must be generalized from a singleton with hardcoded constants to a `Model` enum/sealed class. `DownloadActivity` must accept a `MODEL_TARGET` intent extra. Largest scoped work item in v1.3. |
| **Material 3 theming** | Existing theme is `Theme.MaterialComponents.DayNight` (Material 2). Material 3 gives dynamic color on Android 12+, updated typography, improved shapes on existing MDC components | MEDIUM | Change parent in `themes.xml` to `Theme.Material3.DayNight`. The app already uses `MaterialButton` and `CircularProgressIndicator` — these render correctly under M3. Key action: rename text appearance attributes throughout all XML layouts (M3 renames them — see Architecture Impact below). Add `DynamicColors.applyToActivitiesIfAvailable(this)` in a new `Application` subclass. |

### Anti-Features (Commonly Requested, Often Problematic)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| **Per-App Language Preferences API (Android 13+)** | Official Google recommendation for language pickers | This API switches the app's *display locale* (UI language). Unmuffle's UI is English-only; only the Whisper transcription target language varies. The system API involves `LocaleManager`, `LocaleList`, and a `locales_config.xml` — significant complexity for zero user-visible benefit in this context. | Simple `SharedPreferences` string storing the 2-char Whisper language code passed to `params.language` in `whisper_jni.cpp`. |
| **Jetpack Compose migration** | Modern Android narrative | App is 100% View-based with no Compose dependency in `libs.versions.toml`. Adding Compose for a dialog and a settings screen adds the entire Compose runtime (~4MB APK) with no user-visible benefit. `PreferenceFragmentCompat` handles the settings screen in ~30 lines. | Stay View-based. |
| **Jetpack DataStore for preferences** | "Modern replacement for SharedPreferences" per Google 2020 blog | DataStore is async (Flow-based). Reading a language preference in `ShareReceiverActivity.onCreate()` before showing a dialog would require `runBlocking` or restructuring the entire flow. For 2-3 simple key-value preferences read synchronously, DataStore adds complexity with zero benefit. SharedPreferences is not deprecated. | `SharedPreferences` with synchronous `getString`/`getBoolean`. Correct for this scope. |
| **Whisper "turbo" or "medium" model option** | Higher accuracy | `ggml-medium-q5_1.bin` is ~530MB; turbo is ~790MB. Both are impractical for on-device mobile storage and RAM budgets for the target user. | Offer only tiny (~32MB) and small (~190MB) as the two tiers. Document the accuracy tradeoff clearly in settings. |
| **Transcript history / saved transcripts** | "Where did my transcript go?" | Violates the ephemeral privacy model — the app's core value proposition. Any persistence creates a data liability. Already listed as Out of Scope in `PROJECT.md`. | Show transcript on screen; user explicitly copies or shares. Current behavior is correct and intentional. |
| **Cancel button that actually interrupts whisper.cpp** | Users want to cancel 12-15s inference | `whisper_full()` is a blocking C++ call. Coroutine cancellation marks the Kotlin job cancelled but the native call runs to completion. Implementing real cancellation requires `abort_callback` support in `whisper_full_params`, which is a significant JNI change. | Omit a cancel button in v1.3. The back press already cancels the coroutine and finishes the Activity. Document the native-call limitation. |

---

## Feature Dependencies

```
[App rename: Unmuffle]
    must precede -> [Settings page title] ("Unmuffle Settings")
    must precede -> [F-Droid submission]

[SharedPreferences: pref_language + pref_language_remembered]
    required by -> [Language dialog "remember my choice"]
    required by -> [Settings page: language default ListPreference]
    required by -> [ShareReceiverActivity: skip dialog if remembered]

[Language code → JNI params.language at runtime]
    required by -> [Language dialog: result passed to TranscriptionActivity]
    requires:      whisper_jni.cpp fullTranscribe must accept language string parameter
                   (currently hardcoded "auto" at line 66)

[ModelManager generalization: Model enum/sealed class]
    required by -> [Settings page: model selection ListPreference]
    required by -> [DownloadActivity: accept MODEL_TARGET intent extra]

[Material 3 theme migration]
    parallel to -> [Layout bug fix] (textAppearance attribute renames must be applied in same pass)
    independent of -> [Language dialog, Settings page, Model management]

[Layout bug fix]
    independent of all other features — pure XML change
```

### Dependency Notes

- **Language code → JNI:** `whisper_jni.cpp` line 66 has `params.language = "auto"` hardcoded. The `fullTranscribe` JNI function signature (`WhisperLib.fullTranscribe(contextPtr, numThreads, audioData)`) does not accept a language string. To thread a runtime language selection through: add a `languageCode: String` parameter to `WhisperLib.fullTranscribe()` and the corresponding JNI `jstring` parameter in `whisper_jni.cpp`. This is a clean, minimal change — the entire call chain (`WhisperLib` → `WhisperContext.transcribeData` → `WhisperEngine.transcribe` → `TranscriptionViewModel.startTranscription`) needs the extra parameter threaded through.

- **ModelManager generalization:** Current `ModelManager` is a Kotlin `object` with `MODEL_FILENAME`, `MODEL_URL`, `MODEL_SHA256`, and `MODEL_SIZE_BYTES` as hardcoded constants. To support two models: create a `Model` sealed class or enum with `filename`, `url`, `sha256`, and `sizeBytes` fields. `WhisperEngine.loadModel()` already accepts an arbitrary path — no JNI changes needed for model switching. Only `ModelManager` and `DownloadActivity` need updating.

- **Material 3 text appearance attribute rename (HIGH confidence — official MDC docs):**

  | Material 2 attribute | Material 3 attribute |
  |---------------------|---------------------|
  | `?attr/textAppearanceBody1` | `?attr/textAppearanceBodyMedium` |
  | `?attr/textAppearanceBody2` | `?attr/textAppearanceBodySmall` |
  | `?attr/textAppearanceHeadline6` | `?attr/textAppearanceHeadlineSmall` |
  | `?attr/textAppearanceCaption` | `?attr/textAppearanceLabelMedium` |

  Current layouts use `textAppearanceBody1`, `textAppearanceBody2`, `textAppearanceHeadline6`, and `textAppearanceCaption` — all must be updated when the parent theme changes. R8 may strip unresolved attributes silently; missing attribute references render as blank text.

---

## MVP Definition for v1.3

### Launch With (v1.3)

Minimum set needed for a releasable, correctly-named, polished v1.3.

- [ ] **Layout bug fix** — product is visually broken without this; P0
- [ ] **App rename: TransChat → Unmuffle** — required before any public announcement or F-Droid submission
- [ ] **Fix README permissions claim** — documentation correctness; trivial effort
- [ ] **Language selection dialog** — the headline feature of the milestone
- [ ] **"Remember my choice" preference** — without this the dialog is friction every share
- [ ] **Material 3 theme migration** — ships in the same XML pass as the layout fix

### Add After Validation (v1.3.x)

- [ ] **Settings page (language default)** — useful, but the "remember" checkbox covers the primary use case; add when the dialog is proven stable
- [ ] **Settings page (model selection)** — placeholder UI until tiny model download is implemented

### Future Consideration (v1.4+)

- [ ] **Tiny model download + ModelManager refactor** — largest work item; not needed until user demand for the smaller model is confirmed
- [ ] **Tiny model accuracy disclaimer** — in-app warning that tiny is significantly less accurate for non-English; defer until the feature ships

---

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Layout bug fix | HIGH | LOW | P1 |
| App rename (Unmuffle) | HIGH | LOW | P1 |
| README fix | LOW | LOW | P1 (trivial) |
| Language selection dialog | HIGH | MEDIUM | P1 |
| "Remember my choice" preference | HIGH | LOW | P1 |
| Material 3 theme | MEDIUM | MEDIUM | P1 (same pass as layout fix) |
| Settings page (language) | MEDIUM | MEDIUM | P2 |
| Settings page (model selection UI) | LOW | LOW | P2 (placeholder only) |
| Tiny model download + ModelManager refactor | LOW | HIGH | P3 |

**Priority key:**
- P1: Must have for v1.3 launch
- P2: Should have, add within v1.3 if time allows
- P3: Defer to v1.4+

---

## Architecture Impact per Feature

### Language Dialog (ShareReceiverActivity changes)

Current flow:
```
ShareReceiverActivity.onCreate()
  → ModelManager.isModelPresentFast()
  → AudioCopier.copyToCache()     [synchronous]
  → startActivity(TranscriptionActivity + EXTRA_AUDIO_PATH)
  → finish()
```

New flow:
```
ShareReceiverActivity.onCreate()
  → ModelManager.isModelPresentFast()
  → AudioCopier.copyToCache()     [synchronous — unchanged, URI permission must not expire]
  → SharedPreferences.getBoolean("pref_language_remembered")
      If true:  pass pref_language directly to TranscriptionActivity intent
      If false: show AlertDialog with language radio list + "remember" checkbox
                  → on confirm: write SharedPreferences, launch TranscriptionActivity
  → TranscriptionActivity receives EXTRA_AUDIO_PATH + EXTRA_LANGUAGE_CODE (new)
```

`TranscriptionViewModel.startTranscription(audioPath, languageCode)` — new parameter.
`WhisperEngine.transcribe(pcm, languageCode, onProgress)` — new parameter.
`WhisperLib.fullTranscribe(contextPtr, numThreads, audioData, languageCode)` — new JNI parameter.
`whisper_jni.cpp fullTranscribe` — new `jstring languageCode` param, `params.language = langCodeCStr`.

### Settings Page (new SettingsActivity)

New files:
- `SettingsActivity.kt` — `AppCompatActivity` hosting `SettingsFragment`
- `SettingsFragment.kt` — `PreferenceFragmentCompat` loading `preferences.xml`
- `res/xml/preferences.xml` — two `ListPreference` items

No changes to the transcription pipeline — settings only affect default values read by `ShareReceiverActivity` from `SharedPreferences`.

Access: overflow menu in `ReadyActivity` toolbar → `startActivity(SettingsActivity)`.

### Material 3 Theme Migration

Changes:
1. `res/values/themes.xml`: parent `Theme.MaterialComponents.DayNight.DarkActionBar` → `Theme.Material3.DayNight`
2. `res/values/themes.xml`: transparent theme parent `Theme.MaterialComponents.DayNight.NoActionBar` → `Theme.Material3.DayNight.NoActionBar`
3. All layout XML files: update `textAppearance` attributes per rename table above
4. New `UnmuffleApplication.kt` (or `TransChatApplication.kt` until renamed): `DynamicColors.applyToActivitiesIfAvailable(this)` in `onCreate()`
5. `AndroidManifest.xml`: add `android:name=".UnmuffleApplication"` to `<application>`

No `build.gradle` dependency changes needed — `material:1.12.0` (already in `libs.versions.toml`) includes Material 3 support.

---

## Competitor Feature Analysis

| Feature | WhatsApp built-in transcription | Otter.ai / cloud STT | Unmuffle approach |
|---------|--------------------------------|---------------------|-------------------|
| Language selection | Auto-detects only | User sets in account settings | Dialog on share + "remember" |
| Privacy | Processed on WhatsApp servers | Cloud-uploaded | Fully on-device |
| Model choice | Fixed, opaque | Fixed by vendor | tiny/small user-selectable |
| Settings page | In app settings | Full settings | Minimal — language + model only |
| Offline operation | No | No | Yes |

---

## Sources

- Codebase reviewed directly: `whisper_jni.cpp` (language hardcoded at line 66), `ShareReceiverActivity.kt`, `ModelManager.kt`, `TranscriptionViewModel.kt`, `WhisperLib.kt`, `WhisperEngine.kt`, `activity_transcription.xml`, `themes.xml`, `libs.versions.toml`, `AndroidManifest.xml`
- [Android Per-App Language Preferences (Nov 2022)](https://android-developers.googleblog.com/2022/11/per-app-language-preferences-part-1.html) — why NOT to use the system locale API for Whisper language selection
- [Organize your settings — Android Developers](https://developer.android.com/develop/ui/views/components/settings/organize-your-settings) — `PreferenceFragmentCompat` + `ListPreference` pattern
- [Material Design 3 for MDC-Android](https://m3.material.io/develop/android/mdc-android) — `Theme.Material3` parent themes, DynamicColors API
- [whisper.cpp multilingual Android discussion #549](https://github.com/ggml-org/whisper.cpp/discussions/549) — `params.language` is a 2-char C string; `"auto"` is the default
- [ggml-tiny-q5_1.bin on HuggingFace](https://huggingface.co/ggerganov/whisper.cpp/tree/main) — ~32MB multilingual tiny model confirmed available
- [DataStore vs SharedPreferences 2025](https://www.atipik.ch/en/blog/android-jetpack-datastore-vs-sharedpreferences) — SharedPreferences appropriate for synchronous single-key reads in `Activity.onCreate()`
- [Whisper model sizes and accuracy tradeoffs](https://openwhispr.com/blog/whisper-model-sizes-explained) — tiny vs small accuracy delta; tiny significantly less accurate for non-English

---

*Feature research for: Unmuffle v1.3 — language selection, settings, model management, UI polish*
*Researched: 2026-03-01*
