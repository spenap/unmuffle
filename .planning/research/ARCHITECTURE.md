# Architecture Patterns: Unmuffle v1.3 Feature Integration

**Domain:** Android on-device ML — settings persistence, language selection, model switching, UI theming
**Researched:** 2026-03-01
**Confidence:** HIGH (based on direct codebase inspection + Android official docs)

---

## Context: Existing v1.2 Architecture

The v1.2 codebase is a multi-activity Kotlin app using the traditional View system (XML layouts + AppCompatActivity). No Jetpack Compose exists — all UIs use `setContentView()` with Material Components for Android.

### Activity Roster

```
MainActivity                       ← transparent router (model present? → Ready : Download)
  ↓ startActivity + finish()
DownloadActivity                   ← model download + SHA256 verify (DownloadViewModel)
  ↓ startActivity + finish() on complete
ReadyActivity                      ← static "how to use" screen, no ViewModel

ShareReceiverActivity              ← transparent share target, syncs URI copy, routes to Transcription
  ↓ startActivity + finish()
TranscriptionActivity              ← progress + result screen (TranscriptionViewModel)
SmokeTestActivity                  ← developer tool, not in production flow
```

### Core Pipeline (Unchanged v1.0→v1.2)

```
ShareReceiverActivity.onCreate()
  → AudioCopier.copyToCache()            [synchronous, URI permission expires on background]
  → startActivity(TranscriptionActivity)

TranscriptionActivity
  → TranscriptionViewModel.startTranscription(audioPath)
    → WhisperEngine.loadModel(modelPath)
      → WhisperContext.createContextFromFile() [JNI: whisper_init_from_file_with_params]
    → AudioDecoder.decode(audioPath)      [MediaCodec, Dispatchers.Default]
    → PcmResampler.toWhisperFormat()      [linear interpolation to 16kHz mono]
    → WhisperEngine.transcribe(pcm) { p → _uiState.emit(Processing(p)) }
      → WhisperContext.transcribeData()   [single-thread executor, blocks 12-15s]
        → WhisperLib.fullTranscribe()     [JNI: whisper_full, params.language = "auto"]
        → poller coroutine: getProgress() every 200ms
    → HallucinationFilter.filter(raw)
    → _uiState.emit(Success(transcript)) or NoSpeech or Error
```

### Key Architectural Constraints (Cannot Change)

| Constraint | Reason | Impact on v1.3 |
|------------|--------|----------------|
| `whisper_full()` is synchronous/blocking | whisper.cpp design | Language must be passed before call, not during |
| `WhisperContext` owns a `newSingleThreadExecutor` | whisper.cpp not thread-safe | Model switching must serialize through this thread |
| URI content permission expires on backgrounding | Android platform | `AudioCopier.copyToCache()` must remain synchronous in `onCreate` |
| `params.language = "auto"` is hardcoded in `whisper_jni.cpp` | Current implementation | Language selection requires adding JNI parameter |
| `ModelManager.MODEL_FILENAME` is a constant string | Current implementation | Model switching requires refactoring to a variable |
| All activities are `AppCompatActivity` (no Compose) | Existing codebase | Settings UI uses XML + Material Components, not Compose |

---

## v1.3 Feature Integration Analysis

### Feature A: Language Selection Dialog

**What it is:** On each share intent, show a dialog asking "Which language?" with an option to remember the choice.

**Integration point:** `ShareReceiverActivity.onCreate()` — between URI copy and launching `TranscriptionActivity`.

**Current flow:**
```
ShareReceiverActivity.onCreate()
  → AudioCopier.copyToCache()   [sync]
  → startActivity(TranscriptionActivity)  [immediately]
  → finish()
```

**v1.3 flow:**
```
ShareReceiverActivity.onCreate()
  → AudioCopier.copyToCache()   [sync — must remain first]
  → read SettingsRepository.getLanguage()
  → if (language == "ask"):
      show language dialog (AlertDialog)
        → on confirm: startActivity(TranscriptionActivity + EXTRA_LANGUAGE)
        → on dismiss: finish()
  → else:
      startActivity(TranscriptionActivity + EXTRA_LANGUAGE)
  → finish()  [deferred until dialog confirmed]
```

**New extra on TranscriptionActivity intent:**
```kotlin
// ShareReceiverActivity:
intent.putExtra(TranscriptionActivity.EXTRA_LANGUAGE, resolvedLanguageCode)

// TranscriptionActivity:
const val EXTRA_LANGUAGE = "extra_language"
val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "auto"
viewModel.startTranscription(audioPath, language)
```

**Language code passes down the entire call chain:**

```
TranscriptionActivity
  → TranscriptionViewModel.startTranscription(audioPath, language: String)
    → WhisperEngine.transcribe(pcm, language, onProgress)
      → WhisperContext.transcribeData(data, language, onProgress)
        → WhisperLib.fullTranscribe(contextPtr, numThreads, audioData, language)
          → whisper_jni.cpp: params.language = languageCode.c_str()
```

**JNI change required in `whisper_jni.cpp`:**

```cpp
// Current:
JNIEXPORT void JNICALL
Java_com_simonpena_transchat_WhisperLib_00024Companion_fullTranscribe(
    JNIEnv *env, jobject, jlong contextPtr, jint numThreads, jfloatArray audioData) {
    // ...
    params.language = "auto";

// v1.3:
JNIEXPORT void JNICALL
Java_com_simonpena_transchat_WhisperLib_00024Companion_fullTranscribe(
    JNIEnv *env, jobject, jlong contextPtr, jint numThreads, jfloatArray audioData,
    jstring languageCode) {                             // ← new parameter
    const char* lang = env->GetStringUTFChars(languageCode, nullptr);
    // ...
    params.language = lang;
    // ...
    env->ReleaseStringUTFChars(languageCode, lang);
```

**Dialog implementation:** Use `AlertDialog.Builder` (Material Components) with a single-choice list of language options. Matches the existing non-Compose UI pattern of the app.

```kotlin
// In ShareReceiverActivity (or extracted to a helper):
AlertDialog.Builder(this)
    .setTitle("Language")
    .setSingleChoiceItems(languageNames, selectedIndex) { _, which ->
        selectedLanguage = languageCodes[which]
    }
    .setPositiveButton("OK") { _, _ ->
        launchTranscription(cachedFile, selectedLanguage)
    }
    .setNegativeButton("Cancel") { _, _ -> finish() }
    .show()
```

**"Remember my choice" toggle:** A `CheckBox` in the dialog content view, or a `MultiChoiceItems` item at the bottom. On confirmation with box checked: `SettingsRepository.setLanguage(selectedLanguage)`. If unchecked: `SettingsRepository.setLanguage("ask")`.

---

### Feature B: Settings Page

**What it is:** A new Settings screen reachable from `ReadyActivity` (and potentially from `TranscriptionActivity`'s overflow menu). Contains: default language, model selection (tiny/small).

**Integration point:** `ReadyActivity` gains a settings button. New `SettingsActivity` added to manifest.

**Navigation:**
```
ReadyActivity  ──[Settings button]──→  SettingsActivity
```

`ReadyActivity` is currently a static XML screen with no ViewModel. For v1.3:
- Add a Settings/gear icon button to `activity_ready.xml`.
- `ReadyActivity.onCreate()`: `btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }`.

**`SettingsActivity` structure:**
- `AppCompatActivity` subclass (consistent with existing app)
- XML layout with Material Components: `SwitchMaterial` or `RadioGroup` for language default; separate section for model selection
- No ViewModel needed — reads/writes `SettingsRepository` directly (synchronous SharedPreferences reads are acceptable for this simple use case)
- Back button saves settings implicitly (no explicit "Save" needed — write on each change)

**Alternative (PreferenceFragmentCompat):** The AndroidX Preference library provides a built-in settings UI framework. Pros: handles save/restore automatically, matches system Settings UI conventions. Cons: less visual control, requires another dependency, styling with Material 3 is indirect. **Recommendation: use plain `AppCompatActivity` + XML layout for full control over Material 3 appearance, consistent with the rest of the app.**

---

### Feature C: Model Switching (Tiny vs Small)

**What it is:** Allow the user to select between `ggml-tiny-q5_1.bin` (~38MB) and `ggml-small-q5_1.bin` (~190MB, current). Both are multilingual Whisper models.

**Complexity:** This is the most architecturally significant v1.3 change.

**Current `ModelManager` is built around a single model:**

```kotlin
object ModelManager {
    const val MODEL_FILENAME = "ggml-small-q5_1.bin"
    const val MODEL_URL = "https://huggingface.co/..."
    const val MODEL_SHA256 = "ae85e4a..."
    const val MODEL_SIZE_BYTES = 199_819_264L

    fun modelFile(context: Context): File { ... }
    fun isModelPresentFast(context: Context): Boolean { ... }
}
```

**Required refactoring:** `ModelManager` needs to be aware of which model is active.

```kotlin
enum class WhisperModel(
    val filename: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val displayName: String
) {
    TINY(
        filename = "ggml-tiny-q5_1.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
        sha256 = "<verified at research time>",
        sizeBytes = 31_953_408L,  // ~30MB q5_1
        displayName = "Tiny (~30MB, faster)"
    ),
    SMALL(
        filename = "ggml-small-q5_1.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        sizeBytes = 199_819_264L,
        displayName = "Small (~190MB, more accurate)"
    )
}
```

`ModelManager` methods become model-aware:

```kotlin
fun modelFile(context: Context, model: WhisperModel = activeModel(context)): File { ... }
fun isModelPresentFast(context: Context, model: WhisperModel): Boolean { ... }
fun activeModel(context: Context): WhisperModel {
    return SettingsRepository.getModel(context)  // reads SharedPreferences
}
```

**Model switching cascades affect:**

| Component | Change |
|-----------|--------|
| `ModelManager` | Add `WhisperModel` enum, make all methods model-aware |
| `SettingsRepository` | Persist active model selection |
| `MainActivity` | Route based on `isModelPresentFast(activeModel)` |
| `ShareReceiverActivity` | Check active model presence |
| `DownloadActivity` + `DownloadViewModel` | Download the selected model's URL/filename |
| `TranscriptionViewModel` | Load `ModelManager.modelFile(activeModel)` |
| `SettingsActivity` | UI to select model; if selected model not present, prompt download |

**Critical model-switching edge case:** If the user switches from Small→Tiny and Tiny is not downloaded yet, the app must either (a) start a download or (b) revert to the previously downloaded model. The simplest correct behavior: switching to a model that isn't downloaded triggers `DownloadActivity` for that model. The old model file is kept — storage is not deleted on switch.

**WhisperEngine memory implication:** The engine keeps the loaded model in native memory (~190MB for small, ~40MB for tiny). Switching models requires `engine.release()` then `engine.loadModel(newPath)`. This 2-5s reload only happens when the active model changes between transcriptions — not on every share. Since `TranscriptionViewModel` is recreated for each transcription session, this is handled naturally (the ViewModel always calls `loadModel` in `startTranscription`).

---

### Feature D: App Rename (TransChat → Unmuffle)

**What it is:** Rename `android:label`, package strings, `strings.xml` references, manifest label, README, CI references.

**NOT a package rename:** The requirement says "package, strings, manifest" but changing the Java package (`com.simonpena.transchat`) would break the JNI name mangling. **Recommendation: rename display strings only, not the Java package.** The JNI C++ symbols (`Java_com_simonpena_transchat_WhisperLib_...`) would require renaming all C++ JNI functions if the package changes — that is refactoring risk without user benefit.

**What changes:**
- `AndroidManifest.xml`: `android:label="Unmuffle"` and `android:label` on `ShareReceiverActivity`
- `res/values/strings.xml`: All string references to "TransChat"
- `DownloadViewModel.kt`: `setTitle("Unmuffle — Whisper Model")`
- `README.md`, `.github/workflows/` references

**What does NOT change:**
- Java package `com.simonpena.transchat`
- JNI function names in `whisper_jni.cpp`
- ProGuard keep rules (class names unchanged)

---

### Feature E: UI/UX Polish (Material 3)

**Current state:** App uses `Theme.MaterialComponents.DayNight.DarkActionBar` (Material Components 2/3 hybrid). The theme is defined in `res/values/themes.xml`.

**v1.3 goal:** Material 3 (`Theme.Material3.*`) styling, layout regression fix (title overlapping transcript).

**Theme migration path:**

```xml
<!-- Current: -->
<style name="Theme.TransChat" parent="Theme.MaterialComponents.DayNight.DarkActionBar">

<!-- v1.3: -->
<style name="Theme.Unmuffle" parent="Theme.Material3.DayNight">
```

Material 3 is available via `com.google.android.material:material:1.12.0` (already in `libs.versions.toml`) — version 1.12.0 includes Material 3 components including `Theme.Material3.*`. No dependency upgrade needed.

**Layout regression fix:** The `activity_transcription.xml` layout has a known bug where the title overlaps the transcript text and buttons are partially occluded. The fix requires adding proper top/bottom constraints. The existing layout uses `FrameLayout` as root with `LinearLayout` groups — the result group needs a title `TextView` that is properly constrained above the `ScrollView`.

**Transparent theme for headless activities:** The `Theme.TransChat.Transparent` → `Theme.Unmuffle.Transparent` rename needs to update both `themes.xml` and the two manifest entries that reference it (`MainActivity`, `ShareReceiverActivity`).

---

## New Components Required

| Component | Type | Purpose | Integration |
|-----------|------|---------|-------------|
| `SettingsRepository` | `object` (singleton) | Read/write SharedPreferences for language + model settings | Used by `ShareReceiverActivity`, `MainActivity`, `TranscriptionViewModel`, `SettingsActivity` |
| `WhisperModel` | `enum class` | Model variants (tiny, small) with URL/SHA256/size | Lives in `ModelManager.kt` or its own file |
| `SettingsActivity` | `AppCompatActivity` | Settings screen UI | New activity, registered in manifest |
| `activity_settings.xml` | Layout XML | Settings screen layout (language selector + model picker) | Referenced by `SettingsActivity` |
| Language codes list | `object` or `companion object` | Supported language codes + display names | Used by dialog in `ShareReceiverActivity` and settings |

---

## Modified Components

| Component | What Changes | Why |
|-----------|-------------|-----|
| `whisper_jni.cpp` | `fullTranscribe` gains `jstring languageCode` param | Language must reach whisper_full params |
| `WhisperLib.kt` | `fullTranscribe` external fun gains `language: String` param | JNI signature must match C++ |
| `WhisperContext.transcribeData()` | Gains `language: String` param, passes to JNI | Propagate language down call chain |
| `WhisperEngine.transcribe()` | Gains `language: String` param, forwards down | Same |
| `TranscriptionViewModel.startTranscription()` | Gains `language: String` param | Receives from Activity intent extra |
| `TranscriptionActivity` | Reads `EXTRA_LANGUAGE` from intent, passes to ViewModel | Passes language from share dialog |
| `ShareReceiverActivity` | Shows language dialog or reads saved preference; adds `EXTRA_LANGUAGE` to intent | New language flow entry point |
| `ModelManager` | Becomes multi-model-aware using `WhisperModel` enum | Support tiny/small switching |
| `MainActivity` | Routes using active model from `SettingsRepository` | Model-aware routing |
| `DownloadViewModel` | Downloads the selected model's URL/filename | Model-aware download |
| `DownloadActivity` | Shows which model is being downloaded | UI clarity |
| `ReadyActivity` | Gains Settings button | Entry point to `SettingsActivity` |
| `AndroidManifest.xml` | New `SettingsActivity` registration; rename labels | New screen + rename |
| `res/values/themes.xml` | Migrate to Material 3 theme parent; rename to Unmuffle | UI polish |
| `activity_transcription.xml` | Fix layout regression (title + button constraints) | Known bug |
| `activity_ready.xml` | Add Settings button | Navigation to Settings |
| `libs.versions.toml` | No dependency change needed (material 1.12.0 has M3) | — |
| `proguard-rules.pro` | Add `SettingsActivity` keep rule (Activities are already kept by broad rule) | No change actually needed |

---

## SettingsRepository: Recommended Implementation

**Use SharedPreferences (not DataStore) for this app.**

Rationale: The values read in `ShareReceiverActivity.onCreate()` and `MainActivity.onCreate()` are read synchronously on the main thread before any UI appears. DataStore is async (Flow/coroutine-based) and is not suitable for synchronous reads in `onCreate`. SharedPreferences `getString()` is synchronous and safe for small key-value data like language and model selection.

DataStore is the Google-recommended modern API, but its async nature would require restructuring the share flow to be coroutine-driven before the URI permission expires — adding risk without benefit for 2 small preference values.

```kotlin
object SettingsRepository {

    private const val PREFS_NAME = "unmuffle_settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_MODEL = "model"
    private const val DEFAULT_LANGUAGE = "ask"  // "ask" = always show dialog

    fun getLanguage(context: Context): String {
        return prefs(context).getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    fun setLanguage(context: Context, languageCode: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    fun getModel(context: Context): WhisperModel {
        val name = prefs(context).getString(KEY_MODEL, WhisperModel.SMALL.name)
        return WhisperModel.entries.find { it.name == name } ?: WhisperModel.SMALL
    }

    fun setModel(context: Context, model: WhisperModel) {
        prefs(context).edit().putString(KEY_MODEL, model.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
```

---

## Data Flow Changes for v1.3

### Language Selection Flow

```
[User shares voice message]
    ↓
ShareReceiverActivity.onCreate()
    ↓ sync
AudioCopier.copyToCache()  →  CopyResult.Success(cachedFile)
    ↓
SettingsRepository.getLanguage()
    ├── "ask" → show AlertDialog with language list
    │     → user picks language + optional "remember"
    │     → if remember: SettingsRepository.setLanguage(code)
    │     → startActivity(TranscriptionActivity + EXTRA_LANGUAGE=code + EXTRA_AUDIO_PATH=path)
    └── "es"/"en"/etc → startActivity directly with saved language code
    ↓
TranscriptionActivity.onCreate()
    → reads EXTRA_LANGUAGE and EXTRA_AUDIO_PATH
    → viewModel.startTranscription(audioPath, language)
    ↓
TranscriptionViewModel → WhisperEngine → WhisperContext → WhisperLib.fullTranscribe(language)
    ↓ JNI
whisper_jni.cpp: params.language = languageCode  (or "auto")
```

### Model Switching Flow

```
[User opens SettingsActivity]
    → reads current model from SettingsRepository.getModel()
    → shows RadioGroup/Spinner: Tiny / Small

[User selects different model]
    → SettingsRepository.setModel(newModel)
    → if !ModelManager.isModelPresentFast(context, newModel):
        → show dialog: "Tiny model not downloaded. Download now?"
        → startActivity(DownloadActivity + EXTRA_MODEL=newModel.name)

[Next share intent]
    → ShareReceiverActivity checks ModelManager.isModelPresentFast(activeModel)
    → TranscriptionViewModel loads ModelManager.modelFile(activeModel)
```

### State Management Summary

```
SettingsRepository (SharedPreferences)
    ↑ write                      ↑ write
SettingsActivity          ShareReceiverActivity (language dialog + remember)
    ↓ read                       ↓ read
ModelManager              ShareReceiverActivity (language for intent)
MainActivity              MainActivity (model routing)
```

---

## Recommended Build Order

Dependencies between v1.3 features determine safe implementation order:

```
Step 1: SettingsRepository (no deps)
        WhisperModel enum (no deps)
        ← These are pure data/storage, no UI, independently testable

Step 2: ModelManager refactor (depends on WhisperModel + SettingsRepository)
        ← Enables model-aware routing/checking throughout

Step 3: Language JNI chain (depends on nothing but impacts many files)
        whisper_jni.cpp → WhisperLib.kt → WhisperContext → WhisperEngine
          → TranscriptionViewModel → TranscriptionActivity
        ← Work bottom-up (JNI first, UI last); test with hardcoded "es" before wiring dialog

Step 4: App rename (TransChat → Unmuffle)
        ← Touches strings/manifest only; safe to do in isolation
        ← Do before UI polish so themes.xml rename is done once

Step 5: UI polish + layout fix (depends on rename for theme naming)
        themes.xml migration to Material 3
        activity_transcription.xml layout regression fix
        ← Visual changes; low risk of breaking logic

Step 6: Language selection dialog in ShareReceiverActivity
        (depends on: Step 1 SettingsRepository, Step 3 language chain working)
        ← Wire dialog to SettingsRepository.getLanguage/setLanguage
        ← At this point, end-to-end language flow works

Step 7: Settings screen (SettingsActivity + ReadyActivity button)
        (depends on: Step 1 SettingsRepository, Step 2 ModelManager, Step 4 rename)
        ← Last because it surfaces all settings that prior steps established
        ← Model download trigger from settings requires DownloadActivity changes (Step 2)
```

**Why this order:**

- `SettingsRepository` is a zero-risk foundation. Nothing else in the app breaks if it's added first.
- Language JNI chain (Step 3) must go bottom-up because each layer depends on the layer below it compiling.
- The rename (Step 4) is done before UI polish (Step 5) to avoid renaming themes twice.
- The language dialog (Step 6) is wired after both the persistence (Step 1) and the downstream JNI chain (Step 3) are in place — otherwise the dialog would have nowhere to route the selected language.
- Settings screen (Step 7) is last because it is the surface that integrates all prior steps.

---

## Component Diagram: v1.3 Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  ENTRY POINTS                                                        │
│  MainActivity (router)    ShareReceiverActivity (share target)       │
│       ↓                              ↓                              │
│  SettingsRepository ←────────────────┘                             │
│  ModelManager ←──────────────────────┘                             │
└─────────────────────────────────────────────────────────────────────┘
            ↓                            ↓
┌───────────────────────┐   ┌────────────────────────────────────────┐
│  DOWNLOAD FLOW        │   │  TRANSCRIPTION FLOW                    │
│  DownloadActivity     │   │  TranscriptionActivity                 │
│  DownloadViewModel    │   │  TranscriptionViewModel                │
│  ModelManager         │   │  WhisperEngine                         │
│  (WhisperModel enum)  │   │  WhisperContext (single-thread exec)   │
└───────────────────────┘   │  WhisperLib (JNI bridge)               │
                             │  whisper_jni.cpp                       │
                             │    params.language = langCode          │
                             └────────────────────────────────────────┘
            ↓
┌─────────────────────────────────────────────────────────────────────┐
│  SETTINGS FLOW (NEW in v1.3)                                        │
│  ReadyActivity ──[settings btn]──→ SettingsActivity                 │
│  SettingsActivity reads/writes: SettingsRepository                  │
│    - language preference (ask / es / en / pt / ...)                 │
│    - model preference (TINY / SMALL)                                │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  PERSISTENCE LAYER (NEW in v1.3)                                    │
│  SettingsRepository (SharedPreferences "unmuffle_settings")         │
│    KEY_LANGUAGE: String  ("ask" | "auto" | "es" | "en" | ...)      │
│    KEY_MODEL: String  ("TINY" | "SMALL")                            │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Async Settings Read in ShareReceiverActivity

**What people do:** Use DataStore (Kotlin Flow) for settings, then `runBlocking` in `onCreate` to read synchronously, or launch a coroutine and defer the URI copy.

**Why it's wrong:** The URI content permission expires when the sending app is backgrounded. Any async gap between `onCreate` and `copyToCache` risks the permission expiring. This is why the existing `copyToCache` is explicitly synchronous.

**Do this instead:** SharedPreferences synchronous read. Language code and model name are not sensitive — no encryption needed.

---

### Anti-Pattern 2: Package Rename for App Rename

**What people do:** Rename the Java package from `com.simonpena.transchat` to `com.simonpena.unmuffle` as part of the app rename.

**Why it's wrong:** The JNI C++ function names in `whisper_jni.cpp` encode the package name (`Java_com_simonpena_transchat_WhisperLib_00024Companion_*`). Renaming the package requires renaming all 7 JNI functions in C++ and updating the ProGuard rules. This is refactoring risk with zero user-visible benefit (users never see the package name).

**Do this instead:** Rename `android:label` strings only. Update `strings.xml`, manifest labels, README, and CI — not the Java package.

---

### Anti-Pattern 3: Reloading the Model on Every Transcription

**What people do:** Call `engine.loadModel()` on every `startTranscription()` invocation, assuming this is safer.

**Why it's wrong:** Model loading takes 2-5 seconds and allocates ~190MB of native memory. `WhisperEngine` is instantiated inside `TranscriptionViewModel`, which is created fresh for each `TranscriptionActivity` instance. The model is already loaded once per session; reloading is just a 2-5s delay for the user on every voice message.

**Do this instead:** Keep the existing pattern — load once in `startTranscription`, release in `onCleared`. When model switching is added, the new model path is passed to the same `loadModel()` call; there's no need to pre-cache or warm the model.

---

### Anti-Pattern 4: Storing Model Files on Internal filesDir vs External

**What people do:** Move model storage to `context.filesDir` for simpler access.

**Why it's wrong:** `DownloadManager` (a separate system process) cannot write to `filesDir`. The existing `getExternalFilesDir(null)` is app-private, deleted on uninstall, and accessible by `DownloadManager`. This must be preserved.

**Do this instead:** Keep model storage in `getExternalFilesDir(null)/models/` for both tiny and small model files.

---

### Anti-Pattern 5: Adding Jetpack Compose for New Screens

**What people do:** Add Compose to the new Settings screen for "modern" UI while keeping XML for existing screens.

**Why it's wrong:** The codebase is 100% XML + AppCompatActivity. Introducing Compose for one screen adds the Compose dependency tree (~5-10MB to the APK), requires migrating to `ComponentActivity` for that Activity (or mixing AppCompat + Compose), and creates an inconsistent codebase pattern. The Material Components XML library already provides equivalent Material 3 components.

**Do this instead:** Use `AppCompatActivity` + XML + Material Components (the existing pattern) for `SettingsActivity`.

---

## Integration Points Summary

| Boundary | Direction | Protocol | Notes |
|----------|-----------|----------|-------|
| `ShareReceiverActivity` → `TranscriptionActivity` | Intent extras | `EXTRA_AUDIO_PATH` (exists) + `EXTRA_LANGUAGE` (new) | Language must be resolved before `startActivity` |
| `SettingsRepository` ↔ All Activities | Direct call | SharedPreferences (sync read, async `.apply()` write) | Single `SettingsRepository` object, no dependency injection needed |
| `ModelManager` ↔ `WhisperModel` | Type reference | `WhisperModel` enum passed to all model methods | Enum serialized as `.name` String in SharedPreferences |
| `TranscriptionViewModel` → `WhisperEngine` | Kotlin API | `transcribe(pcm, language, onProgress)` | Language added to existing signature |
| `WhisperEngine` → `WhisperContext` → `WhisperLib` | Kotlin API → JNI | `fullTranscribe(contextPtr, numThreads, audioData, language)` | JNI function signature change requires both C++ and Kotlin updates |
| `SettingsActivity` → `DownloadActivity` | Intent extras | `EXTRA_MODEL` (new) | Only if selected model not downloaded |

---

## Confidence Assessment

| Area | Confidence | Basis |
|------|------------|-------|
| Language JNI chain | HIGH | Direct read of `whisper_jni.cpp` — `params.language` is hardcoded "auto", adding param is mechanical |
| SharedPreferences for settings | HIGH | Official Android docs; synchronous requirement from existing URI copy pattern |
| `WhisperModel` enum approach | HIGH | Direct read of `ModelManager.kt` — single-model constants are obvious extension points |
| Settings screen as plain AppCompatActivity | HIGH | Consistent with existing 5-activity pattern; Compose adds APK cost without benefit |
| Material 3 theme migration | HIGH | `com.google.android.material:material:1.12.0` (already in project) supports `Theme.Material3.DayNight` |
| Model switching edge cases | MEDIUM | WhisperEngine reload path is correct; DownloadActivity multi-model flow needs careful testing |
| Language dialog "remember" UX | MEDIUM | Pattern is standard but exact dialog widget (Spinner vs RadioGroup vs SingleChoiceItems) is implementation decision |

---

## Sources

- `whisper_jni.cpp` (this repository, direct read): `params.language = "auto"` — line 66; `fullTranscribe` signature — HIGH confidence
- `WhisperLib.kt` / `WhisperContext` (this repository, direct read): Single-thread executor, `transcribeData` signature — HIGH confidence
- `TranscriptionViewModel.kt` (this repository): `startTranscription(audioPath: String)` signature — HIGH confidence
- `ShareReceiverActivity.kt` (this repository): Synchronous `copyToCache` constraint — HIGH confidence
- `ModelManager.kt` (this repository): Single-model constants, `getExternalFilesDir` rationale in comments — HIGH confidence
- `libs.versions.toml` (this repository): `material = "1.12.0"` already present — HIGH confidence
- `res/values/themes.xml` (this repository): `Theme.MaterialComponents.DayNight.DarkActionBar` current parent — HIGH confidence
- [Android DataStore vs SharedPreferences 2025](https://www.atipik.ch/en/blog/android-jetpack-datastore-vs-sharedpreferences): DataStore is async, SharedPreferences is synchronous — confirmed official recommendation context
- [Android Developers: Preferences DataStore codelab](https://developer.android.com/codelabs/android-preferences-datastore): DataStore uses Flow/coroutines — confirms incompatibility with sync-required share flow
- [whisper.cpp discussions: Android multilingual support](https://github.com/ggml-org/whisper.cpp/discussions/549): `params.language` accepts 2-char ISO codes or "auto" — MEDIUM confidence (community, not official docs)

---

*Architecture research for: Unmuffle v1.3 (language selection, settings, model switching, UI polish)*
*Researched: 2026-03-01*
