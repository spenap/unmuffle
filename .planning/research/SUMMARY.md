# Project Research Summary

**Project:** Unmuffle v1.3 — Language Selection, Settings Page, Model Choice, UI Polish, App Rename
**Domain:** Android on-device ML — JNI-backed Whisper transcription with share-target UX
**Researched:** 2026-03-01
**Confidence:** HIGH

## Executive Summary

Unmuffle v1.3 is an incremental feature milestone on a stable, production-validated Android app that transcribes voice messages on-device using whisper.cpp via JNI. The existing stack (Kotlin, NDK r27c, whisper.cpp v1.8.3, MediaCodec, ViewModel + StateFlow, R8, GitHub Actions) is locked and proven. v1.3 adds only two new Gradle artifacts: `androidx.datastore:datastore-preferences:1.2.0` (for the settings persistence layer) and a material version bump from 1.12.0 to 1.13.0 (for Material 3 theme support). All five feature areas — language selection, settings page, model management, Material 3 polish, and the app rename — extend existing patterns without introducing new architectural paradigms or new third-party library risk.

The recommended implementation order is strictly dependency-driven: establish the SettingsRepository persistence singleton and WhisperModel enum first, then generalize ModelManager to support multiple models, then thread the language parameter down the full JNI call chain (five Kotlin layers plus one C++ function), then perform the mechanical app rename, then apply the Material 3 theme migration and layout bug fix, then wire the language selection dialog, and finally build the SettingsActivity that surfaces all prior work. This sequence is not arbitrary — each step is independently compilable and testable before the next step depends on it. The architecture research derives this order directly from the actual code dependency graph and documents it as the recommended build order.

The three critical risks are: JNI function name breakage after the package rename (silent at compile time, fatal at runtime — all 7 C++ function signatures must be updated atomically with the Kotlin package rename), content URI permission expiry if any dialog appears in ShareReceiverActivity before the audio file is copied to cache (the language dialog must be placed in TranscriptionActivity, not ShareReceiverActivity), and DataStore multiple-instance corruption if the DataStore property delegate is declared in more than one place (a single top-level declaration in SettingsRepository.kt is the required pattern). All three risks have documented, low-effort mitigations — none requires rearchitecting the app.

## Key Findings

### Recommended Stack

The only new library is `androidx.datastore:datastore-preferences:1.2.0`. DataStore 1.2.0 specifically fixes a `UnsatisfiedLinkError` in R8-shrunk apps — directly relevant because this project uses R8 with `isMinifyEnabled = true`. The material artifact bumps from 1.12.0 to 1.13.0 for Material 3 theme parent availability and dialog styling fixes. All other dependencies are unchanged. Notably, the architecture research recommends using SharedPreferences (not DataStore) for synchronous preference reads in `ShareReceiverActivity.onCreate()`, because DataStore's async Flow-based API is incompatible with the synchronous read required before the content URI permission window closes. DataStore is used in SettingsRepository for the settings screen, where async reads are acceptable.

**Core technologies:**
- `whisper.cpp v1.8.3 via JNI` — on-device transcription engine, locked and production-verified; unchanged for v1.3
- `androidx.datastore:datastore-preferences:1.2.0` — settings persistence layer; R8-compatible (fixes UnsatisfiedLinkError in minified builds); declared once as a top-level singleton
- `com.google.android.material:material:1.13.0` — Material 3 theme support; `Theme.Material3.DayNight.NoActionBar` confirmed as canonical parent for XML view-based apps
- `SharedPreferences` (no new artifact) — synchronous preference reads in `ShareReceiverActivity.onCreate()` and `MainActivity.onCreate()` where DataStore's async nature would introduce URI permission race conditions
- `MaterialAlertDialogBuilder.setSingleChoiceItems()` — language picker dialog; included in the existing material artifact, no new dependency
- `WhisperModel` enum + `SettingsRepository` object — new Kotlin types implementing multi-model support and preference persistence; no external library required

**What NOT to add:**
- Jetpack Compose: app is 100% XML Views; adding Compose for a settings screen adds ~5-10MB to the APK with no user-visible benefit
- `androidx.preference:preference` (PreferenceFragmentCompat): couples settings to SharedPreferences; harder to style with Material 3; hand-written Activity is cleaner for two preference items
- Navigation Component, Hilt, Room: disproportionate for this app's linear six-activity flow and single ViewModel

### Expected Features

The feature research establishes a clear three-tier priority structure for v1.3.

**Must have (table stakes — P1 for v1.3 launch):**
- Layout bug fix (title overlaps transcript, buttons partially occluded) — result screen is visually broken without this; P0
- App rename: TransChat to Unmuffle — required before any public announcement or F-Droid submission
- Fix README permissions claim (says "no network permissions" but `INTERNET` and `ACCESS_NETWORK_STATE` exist) — documentation correctness
- Language selection dialog on share intent — the headline feature of the milestone
- "Remember my choice" persistent preference — without this the dialog is friction on every share
- Material 3 theme migration — applies in the same XML pass as the layout fix

**Should have (competitive differentiators — P2):**
- Settings page (language default) — useful for power users; the "remember" checkbox covers the primary use case
- Settings page (model selection UI placeholder) — surfaces the model story before full implementation

**Defer to v1.4+:**
- Tiny model download + ModelManager refactor — largest work item; validate user demand before investing
- Tiny model accuracy disclaimer — defer until the feature ships
- Cancel button that truly interrupts whisper.cpp inference — requires `abort_callback` JNI support; significant scope increase

**Anti-features explicitly rejected:**
- Per-App Language Preferences API (Android 13+): switches the app's UI locale, not the Whisper transcription language — wrong API for this use case
- Jetpack DataStore for synchronous reads in ShareReceiverActivity: DataStore is async; `runBlocking` workarounds create URI permission race conditions
- Whisper turbo or medium model: ~530MB and ~790MB respectively — impractical for mobile on-device storage and RAM
- Transcript history: violates the ephemeral privacy model that is the app's core value proposition

### Architecture Approach

The existing architecture is a six-activity Kotlin app using XML Views and AppCompatActivity throughout. v1.3 adds one new Activity (SettingsActivity), one new Kotlin object singleton (SettingsRepository), and one new enum class (WhisperModel). The core transcription pipeline is extended by threading a `language: String` parameter down the entire call chain: ShareReceiverActivity intent extra → TranscriptionActivity → TranscriptionViewModel.startTranscription() → WhisperEngine.transcribe() → WhisperContext.transcribeData() → WhisperLib.fullTranscribe() → `whisper_jni.cpp` `params.language` field (currently hardcoded to `"auto"`). Model switching extends ModelManager from a single-model object with hardcoded constants into a WhisperModel-enum-driven, multi-model-aware component.

**Major components:**
1. `SettingsRepository` (new object singleton) — reads/writes SharedPreferences for language code and model selection; single source of truth for all preference access across all activities
2. `WhisperModel` enum (new, in ModelManager.kt or own file) — encapsulates filename, URL, SHA256, sizeBytes, and displayName for TINY (~32MB) and SMALL (~190MB) model variants
3. `SettingsActivity` (new AppCompatActivity) — hand-written settings screen with Material Components; plain XML layout with two tappable preference rows; avoids PreferenceFragmentCompat to maintain full Material 3 styling control consistent with the rest of the app
4. Modified JNI call chain — language parameter propagates from ShareReceiverActivity intent extra through five Kotlin layers and one C++ function into `whisper_jni.cpp params.language`
5. `UnmuffleApplication` (new Application subclass) — registers `DynamicColors.applyToActivitiesIfAvailable(this)` for Android 12+ dynamic color support; registered in AndroidManifest.xml

**Architecture rule confirmed by research:** No Jetpack Compose, no Navigation Component, no Hilt, no DI framework. The app is a linear activity chain with direct `startActivity()` calls. This remains correct at six activities.

### Critical Pitfalls

1. **JNI function names break silently after package rename** — all 7 function signatures in `whisper_jni.cpp` encode the Kotlin package path using Java name-mangling (e.g., `Java_com_simonpena_transchat_WhisperLib_00024Companion_initContext`). Update them atomically with the Kotlin package rename. Update `proguard-rules.pro` `-keep class` rules in the same commit. Verify with `nm -D app/build/intermediates/stripped_native_libs/.../libwhisper.so | grep Java_`. Failure is silent at compile time and fatal at runtime (`UnsatisfiedLinkError` on first transcription).

2. **Content URI permission expires if dialog precedes audio copy in ShareReceiverActivity** — Android grants temporary read access to the content URI for the duration of the intent dispatch. If the user switches apps while a dialog is visible, the permission can be revoked. `AudioCopier.copyToCache()` must always be the first operation in `ShareReceiverActivity.onCreate()`, before any dialog. The language selection dialog belongs in TranscriptionActivity (before inference starts), not in ShareReceiverActivity. Failure is intermittent (`SecurityException` only when the user is slow with the dialog), making it hard to reproduce in testing.

3. **ProGuard R8 keep rules become no-ops after package rename** — Android Studio's Rename refactor does not update `proguard-rules.pro`. After renaming the package, the `-keep class com.simonpena.transchat.WhisperLib` rules silently match nothing, R8 removes the JNI bridge classes, and the release build crashes. Debug builds (R8 disabled) work fine. Update `proguard-rules.pro` in the same commit as the package rename; consider replacing `-keep` rules with `@Keep` annotations on WhisperLib and WhisperLib$Companion so they survive future refactors.

4. **DataStore multiple instance corruption** — `DataStore<Preferences>` throws `IllegalStateException: There are multiple DataStores active for the same file` if instantiated more than once for the same file name. Declare the DataStore property delegate exactly once as a top-level property in SettingsRepository.kt (`private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")`). Pass SettingsRepository as a dependency to any consumer rather than creating DataStore instances ad hoc. Failure is runtime-only, not visible at compile time.

5. **Material 2 to Material 3 attribute names break existing layouts** — `?attr/textAppearanceBody1`, `?attr/textAppearanceBody2`, `?attr/textAppearanceHeadline6`, and `?attr/textAppearanceCaption` are Material 2 attribute names that do not exist in Material 3 themes. After changing the theme parent, these resolve to null — text becomes invisible or InflateException occurs. Update all four attribute names to their M3 equivalents in the same commit as the theme parent change. Test all four UI states (Processing, Success, NoSpeech, Error) on a device before merging.

## Implications for Roadmap

The architecture research provides an explicit 7-step build order derived from the actual code dependency graph. The roadmap phases below map directly to that order. The dependency structure is non-negotiable: SettingsRepository must exist before any other component can read or write preferences; the JNI language chain must work before the language dialog can have any effect; the rename must precede the theme migration to avoid renaming theme files twice.

### Phase 1: Persistence Foundation
**Rationale:** SettingsRepository and WhisperModel enum have zero dependencies on any other v1.3 work. Every subsequent phase depends on them. Establishing these first ensures all other phases build on a stable, testable foundation.
**Delivers:** `SettingsRepository` object with `getLanguage`/`setLanguage`/`getModel`/`setModel` methods backed by SharedPreferences; `WhisperModel` enum with TINY and SMALL variants including filename, URL, SHA256, sizeBytes, and displayName constants.
**Addresses:** "Remember my choice" persistence, model selection storage; foundational prerequisite for all other phases.
**Avoids:** DataStore multiple-instance corruption (singleton established once here, not recreated in each consumer).

### Phase 2: ModelManager Generalization
**Rationale:** ModelManager must become multi-model-aware before DownloadActivity, MainActivity routing, or SettingsActivity can reference model variants. This is a prerequisite for the settings UI model selection and for the model download flow.
**Delivers:** Refactored ModelManager using WhisperModel enum; model-aware `isModelPresentFast(model)` and `modelFile(model)` methods; updated MainActivity routing using `SettingsRepository.getModel()`; updated DownloadActivity and DownloadViewModel to accept a model target intent extra.
**Uses:** WhisperModel enum and SettingsRepository from Phase 1.
**Avoids:** Model-switching context memory race (proper WhisperEngine reload path established; model selector disabled while transcription is active).

### Phase 3: Language JNI Call Chain
**Rationale:** This is the deepest change in v1.3 — a `language: String` parameter must be threaded through five Kotlin layers and one C++ function. It must be built bottom-up (C++ first, Kotlin layers upward) and tested at each step with a hardcoded language code before wiring the dialog UI. Doing this before the app rename keeps the JNI function names stable during the threading work.
**Delivers:** `whisper_jni.cpp fullTranscribe` gains `jstring languageCode` parameter; WhisperLib.fullTranscribe(), WhisperContext.transcribeData(), WhisperEngine.transcribe(), TranscriptionViewModel.startTranscription() all gain `language: String` parameter; TranscriptionActivity reads `EXTRA_LANGUAGE` from intent and passes to ViewModel.
**Avoids:** Language UI appearing functional while JNI still uses hardcoded "auto" (the primary "looks done but isn't" failure mode documented in PITFALLS.md).

### Phase 4: App Rename (TransChat to Unmuffle)
**Rationale:** The rename is mechanical but touches 8 distinct locations. Doing it after Phase 3 means the JNI C++ functions are in their final form before the package namespace is changed; doing it before Phase 5 means themes.xml is renamed once (Theme.TransChat → Theme.Unmuffle) during the theme migration rather than as a separate step.
**Delivers:** App displays as "Unmuffle" in launcher, share sheet, and download notification; `applicationId` and `namespace` updated to `com.simonpena.unmuffle` in build.gradle.kts; all 7 JNI function signatures updated in whisper_jni.cpp; proguard-rules.pro updated; strings.xml updated; themes.xml renamed; README corrected; GitHub Actions workflow comments updated.
**Avoids:** JNI function name breakage (atomic rename across Kotlin source and C++), ProGuard keep rule staleness (proguard-rules.pro updated in same commit), share sheet label gap (manifest `android:label` on ShareReceiverActivity explicitly updated), DownloadManager notification title updated.

### Phase 5: Material 3 Theme Migration and Layout Bug Fix
**Rationale:** The layout regression (title overlapping transcript, buttons partially occluded) is a P0 fix that belongs in the same XML pass as the Material 3 migration because the textAppearance attribute renames must happen simultaneously. Doing these together avoids touching layout files twice and ensures new UI components built in Phases 6 and 7 are built against the final M3 theme from the start.
**Delivers:** Theme parent changed to `Theme.Material3.DayNight.NoActionBar` (or `Theme.Unmuffle` after Phase 4 rename); all textAppearance attributes updated to M3 equivalents; `activity_transcription.xml` layout regression fixed; `UnmuffleApplication.kt` created with DynamicColors; material bumped to 1.13.0 in libs.versions.toml.
**Avoids:** M2/M3 attribute mismatch leaving text invisible or buttons misshapen; ensures all four UI states (Processing, Success, NoSpeech, Error) render correctly before new screens are built.

### Phase 6: Language Selection Dialog
**Rationale:** This is the headline feature of v1.3 but depends on Phase 1 (SettingsRepository), Phase 3 (language JNI chain working end-to-end), and Phase 5 (correct theme for dialog styling). After those three phases are complete, the dialog is purely additive UI wiring with no risk to existing functionality.
**Delivers:** Language selection dialog (MaterialAlertDialogBuilder.setSingleChoiceItems) placed in TranscriptionActivity (before inference starts); "Remember my choice" behavior via SettingsRepository.setLanguage(); language code flows to whisper.cpp params.language at inference time; fast path skips dialog if preference is already set.
**Avoids:** URI permission expiry pitfall (audio copy in ShareReceiverActivity.onCreate() remains first; dialog is placed in TranscriptionActivity, not ShareReceiverActivity).

### Phase 7: Settings Activity
**Rationale:** SettingsActivity surfaces the language default and model selection controls established by prior phases. It is appropriately last because it integrates all prior phases: reads/writes SettingsRepository (Phase 1), displays WhisperModel options and triggers DownloadActivity for undownloaded models (Phase 2), appears in the correctly named and themed app (Phases 4 and 5), and provides an alternative path to setting the language preference (Phase 6).
**Delivers:** SettingsActivity (plain AppCompatActivity with XML layout) containing language default picker and model selector; ReadyActivity gains a settings button (gear icon); model download trigger from settings if selected model is not present; SettingsActivity registered in AndroidManifest.xml.
**Uses:** All prior phases.

### Phase Ordering Rationale

- Phases 1 and 2 establish the data foundation before any UI work; they introduce zero user-visible changes and zero risk to the existing transcription pipeline.
- Phase 3 (JNI language chain) is the highest technical risk in v1.3 and is done early while the codebase is still in its pre-rename, pre-theme-migration state, minimizing the number of simultaneous variables.
- Phase 4 (rename) follows Phase 3 so the JNI functions are already in their correct renamed form before the Kotlin package changes; the rename happens before Phase 5 so theme files are renamed once.
- Phase 5 (M3 migration) before Phase 6 (dialog) ensures the language dialog is built against the final Material 3 theme and does not need restyling.
- Phase 6 (dialog) before Phase 7 (settings) because the settings page's language section is a superset of the dialog's language preference — the dialog UX should be proven before exposing a second path to the same preference.
- Phase 7 is last because it integrates all prior phases.

### Research Flags

Phases with well-documented patterns (standard execution, skip additional research):
- **Phase 1 (Persistence):** SharedPreferences pattern is standard Android; SettingsRepository is a simple object singleton; DataStore singleton requirement is officially documented.
- **Phase 4 (Rename):** Mechanical file edits; the 8-location checklist in PITFALLS.md is exhaustive; JNI name mangling is deterministic.
- **Phase 5 (M3 migration):** The complete textAppearance attribute rename table is confirmed from MDC-Android official migration guide; material 1.13.0 is confirmed latest stable.

Phases requiring careful task breakdown before implementation (not full research, but explicit task decomposition):
- **Phase 3 (JNI chain):** Five-layer parameter threading is low-risk individually but high-risk as a single large diff. Recommend one task per layer: C++ whisper_jni.cpp first, then WhisperLib.kt, WhisperContext, WhisperEngine, TranscriptionViewModel, TranscriptionActivity. Test with a hardcoded "es" before wiring the dialog.
- **Phase 6 (Language dialog):** The URI permission pitfall requires an explicit architectural decision — dialog in TranscriptionActivity (recommended) vs other locations — to be locked in before implementation begins. This decision materially affects the diff.
- **Phase 2 (ModelManager):** The model-switching edge case (switching while transcription is active) requires disabling the model selector in the Settings UI while `uiState` is Processing. This guard must be an explicit task in Phase 2 or Phase 7 planning.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | All recommendations verified against official Android docs and in-repo dependency files; DataStore 1.2.0 R8 UnsatisfiedLinkError fix is directly relevant to this project's R8 configuration |
| Features | HIGH | Priority tiers based on direct codebase read of all relevant source files; dependency graph derived from actual source code, not inference; anti-features backed by official API rationale |
| Architecture | HIGH | Build order derived from direct inspection of whisper_jni.cpp, WhisperLib.kt, WhisperContext, ShareReceiverActivity.kt, ModelManager.kt; URI permission constraint is documented Android platform behavior |
| Pitfalls | HIGH | JNI mangling rules are deterministic (NDK documentation); URI permission expiry is documented Android behavior; DataStore singleton requirement is official documentation; M3 attribute renames are confirmed from MDC migration guide |

**Overall confidence:** HIGH

### Gaps to Address

- **Tiny model SHA256:** The architecture document lists the SMALL model SHA256 as verified (`ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb`) but notes the TINY model SHA256 as `<verified at research time>` — this value must be confirmed from the HuggingFace repository before Phase 1 or 2 implementation. The URL (`ggml-tiny-q5_1.bin`, ~32.2MB) is confirmed; only the SHA256 is outstanding.

- **Language dialog placement — final decision:** Both research files agree the dialog must not appear in ShareReceiverActivity between intent receipt and audio copy. The consensus recommendation is TranscriptionActivity (show before starting inference, while the audio is loaded). The exact UX flow (dialog appears after the audio-loading state or before the progress bar starts) should be locked in at the start of Phase 6 planning.

- **DataStore vs SharedPreferences implementation boundary:** STACK.md recommends DataStore for SettingsRepository while ARCHITECTURE.md recommends SharedPreferences for all preference reads. These are reconciled: use SharedPreferences throughout SettingsRepository (synchronous reads required by ShareReceiverActivity and MainActivity); DataStore is available as an option only if SettingsActivity needs reactive preference observation (e.g., updating the UI as preferences change). A single decision at Phase 1 start avoids implementing both.

## Sources

### Primary (HIGH confidence)
- Android Developers — DataStore (developer.android.com/topic/libraries/architecture/datastore) — DataStore 1.2.0 R8 compatibility fix confirmed
- AndroidX DataStore Releases (developer.android.com/jetpack/androidx/releases/datastore) — version 1.2.0 confirmed stable, released November 19, 2025
- MDC-Android Getting Started (github.com/material-components/material-components-android/blob/master/docs/getting-started.md) — Theme.Material3.DayNight.NoActionBar confirmed; material 1.13.0 confirmed latest stable (September 2024)
- Android NDK JNI Tips (developer.android.com/training/articles/perf-jni) — JNI function name mangling rules, GetEnv threading behavior
- Android URI Permissions (developer.android.com/reference/android/content/UriPermission) — URI grant expiry on backgrounding
- Android configure app module (developer.android.com/build/configure-app-module) — namespace vs applicationId distinction
- Direct codebase inspection: `whisper_jni.cpp` (params.language hardcoded "auto" at line 66; 7 JNI function signatures confirmed), `WhisperLib.kt`, `WhisperEngine.kt`, `WhisperContext`, `ModelManager.kt` (single-model constants), `ShareReceiverActivity.kt` (synchronous copyToCache constraint), `TranscriptionViewModel.kt`, `activity_transcription.xml`, `themes.xml` (MaterialComponents parent confirmed), `libs.versions.toml` (material 1.12.0 confirmed), `proguard-rules.pro` (WhisperLib keep rules confirmed), `AndroidManifest.xml`
- HuggingFace ggerganov/whisper.cpp (huggingface.co/ggerganov/whisper.cpp/tree/main) — ggml-tiny-q5_1.bin (~32.2MB) and ggml-small-q5_1.bin (~190MB) confirmed; SMALL SHA256 verified

### Secondary (MEDIUM confidence)
- whisper.cpp discussions #549 (github.com/ggml-org/whisper.cpp/discussions/549) — `params.language` accepts ISO 639-1 two-letter codes and "auto"; community-confirmed, not official docs
- whisper.cpp language detection issue #1831 (github.com/ggml-org/whisper.cpp/issues/1831) — language detection behavior on multilingual models
- DataStore vs SharedPreferences 2025 (atipik.ch/en/blog/android-jetpack-datastore-vs-sharedpreferences) — confirms SharedPreferences remains appropriate for synchronous reads in onCreate

### Tertiary (MEDIUM-LOW confidence)
- Whisper model accuracy comparison (openwhispr.com/blog/whisper-model-sizes-explained) — tiny vs small accuracy delta; tiny significantly less accurate for non-English. Use as guidance for accuracy disclaimer copy, not as engineering spec.
- DataStore singleton requirement blog (medium.com/@ivanlocode) — practical explanation of the multiple-instance IllegalStateException; consistent with official docs

---
*Research completed: 2026-03-01*
*Milestone scope: v1.3 — language selection, settings, model management, Material 3 polish, app rename*
*Ready for roadmap: yes*
