# Feature Landscape: TransChat v1.1

**Domain:** On-device voice transcription Android app (share target, Whisper via JNI)
**Milestone:** v1.1 Polish and Distribution
**Researched:** 2026-02-27
**Overall confidence:** HIGH — all four feature areas verified against authoritative sources
  (local whisper.h header at v1.7.4, live codebase inspection, F-Droid official docs, Android official docs)

---

## Context: What Already Exists

v1.0 shipped 2,635 LOC. The relevant existing pieces for v1.1 are:

- `WhisperLib.kt` — JNI bridge; `WhisperLib$Companion` exposes `external fun fullTranscribe(contextPtr, numThreads, audioData)`
- `whisper_jni.cpp` — C++ side; calls `whisper_full(ctx, params, ...)` with **no callbacks set** (blank `progress_callback` and `new_segment_callback` fields)
- `WhisperContext.kt` — Wraps native pointer, single-thread executor; `transcribeData()` returns `String`
- `WhisperEngine.kt` — Public API; `suspend fun transcribe(audioData: FloatArray): String`
- `TranscriptionViewModel.kt` — `UiState.Processing` (indeterminate spinner), `UiState.Success`, `UiState.Error`
- `proguard-rules.pro` — Exists but is over-broad: `-keep class com.simonpena.transchat.** { *; }` blocks all shrinking
- `build.gradle.kts` — `isMinifyEnabled = true` for release; NDK version `26.1.10909125`

v1.1 adds three feature areas plus one housekeeping task.

---

## Feature Area 1: Transcription Progress Bar via whisper.cpp Callbacks

### Table Stakes

| Feature | Why Expected | Complexity | Existing Code Hook |
|---------|--------------|------------|-------------------|
| Determinate progress bar (0-100%) during inference | 12-15s inference with only a spinner is perceived as a freeze; UX research shows 3x longer wait tolerance with determinate bars | Medium | `UiState.Processing` needs a `progress: Int` field |
| Progress updates sourced from real callback, not a timer | Fake/timer-based progress stalls or over-runs unpredictably; erodes trust | Medium | `progress_callback` in `whisper_full_params` — already in whisper.h, just not wired |

### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Partial segment text appearing during inference | Words appear as Whisper produces them; strong confidence signal to user that it's working | High | Requires `new_segment_callback` + JNI upcall from inference thread to Kotlin; architecturally correct but adds threading complexity — defer to v1.2 |
| Estimated time remaining ("~8s left") | Reduces perceived wait length | Low | Computable from `elapsedMs / (progress / 100.0)` — only worth adding once real progress is wired |

### Anti-Features

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| Indeterminate spinner with text (current state) | Industry threshold for determinate progress is >3s; at 12-15s this is a UX failure | Determinate progress bar using real `progress_callback` |
| Fake/timed progress simulation | Progress will stall at any fixed percentage if inference finishes early or runs late; users notice | Use actual `progress_callback` |
| Cancel button wired only to coroutine cancellation | `whisper_full()` is a blocking native call; coroutine cancellation marks the job cancelled but native execution continues. A cancel button that does not stop the 12-15s wait is misleading | Either omit cancel button for v1.1 or implement `abort_callback` — document the limitation |

### Callback API (HIGH confidence — verified from local `whisper.h`)

The `whisper_full_params` struct in `whisper.cpp/include/whisper.h` (v1.7.4, in this repo) exposes:

```c
// Called on each progress update — progress is an integer 0 to 100
typedef void (*whisper_progress_callback)(
    struct whisper_context * ctx,
    struct whisper_state   * state,
    int                      progress,
    void                   * user_data
);
whisper_progress_callback progress_callback;
void *                    progress_callback_user_data;

// Called on every newly generated text segment (n_new = segments added this call)
typedef void (*whisper_new_segment_callback)(
    struct whisper_context * ctx,
    struct whisper_state   * state,
    int                      n_new,
    void                   * user_data
);
whisper_new_segment_callback new_segment_callback;
void *                       new_segment_callback_user_data;
```

Both fields default to `nullptr` (confirmed: current `whisper_jni.cpp` never sets them).

### Implementation Path — progress_callback (recommended for v1.1)

**Minimum scope:** wire `progress_callback` only. Skip `new_segment_callback` for v1.1.

**C++ changes in `whisper_jni.cpp`:**

```cpp
// Struct passed through user_data to the callback
struct ProgressCallbackData {
    JavaVM *  jvm;
    jobject   callbackObj;   // global ref to Kotlin ProgressCallback object
    jmethodID onProgressId;
};

static void progress_callback_impl(
        whisper_context *, whisper_state *, int progress, void * user_data) {
    auto * data = static_cast<ProgressCallbackData *>(user_data);
    JNIEnv * env = nullptr;
    bool attached = false;
    if (data->jvm->GetEnv((void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        data->jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    env->CallVoidMethod(data->callbackObj, data->onProgressId, (jint)progress);
    if (attached) data->jvm->DetachCurrentThread();
}
```

In `fullTranscribe`, add a `jobject progressCallback` JNI parameter. When non-null, populate the struct and assign:

```cpp
params.progress_callback           = progress_callback_impl;
params.progress_callback_user_data = &callbackData;
// callbackData is stack-allocated — safe because whisper_full() is synchronous
```

**Kotlin `WhisperLib.kt` signature change:**

```kotlin
// New functional interface — callable from C via CallVoidMethod by name "onProgress"
fun interface ProgressCallback {
    fun onProgress(progress: Int)
}

// Updated external fun — add nullable callback param
external fun fullTranscribe(
    contextPtr: Long,
    numThreads: Int,
    audioData: FloatArray,
    progressCallback: ProgressCallback?
)
```

**`WhisperContext.transcribeData()` signature change:**

```kotlin
suspend fun transcribeData(
    data: FloatArray,
    onProgress: ((Int) -> Unit)? = null
): String = withContext(scope.coroutineContext) {
    WhisperLib.fullTranscribe(
        ptr,
        WhisperCpuConfig.preferredThreadCount,
        data,
        onProgress?.let { cb -> WhisperLib.ProgressCallback { p -> cb(p) } }
    )
    val segmentCount = WhisperLib.getTextSegmentCount(ptr)
    buildString {
        for (i in 0 until segmentCount) append(WhisperLib.getTextSegment(ptr, i))
    }.trim()
}
```

**`UiState.Processing` change in `TranscriptionViewModel.kt`:**

```kotlin
// Before: object Processing : UiState()
// After:
data class Processing(val progress: Int = 0) : UiState()
```

Pass the lambda from `startTranscription()`:

```kotlin
val raw = engine.transcribe(pcm) { progress ->
    _uiState.value = UiState.Processing(progress)
}
```

**Complexity:** Medium. The JNI upcall pattern is well-established. Key risk: the callback fires on whisper's inference thread, not the JNI caller's thread, so `AttachCurrentThread`/`DetachCurrentThread` is required. The existing single-thread executor in `WhisperContext` means `whisper_full` always runs on a predictable non-main thread, simplifying the threading story. The `ProgressCallback` functional interface must survive R8 (see Feature Area 3).

### Feature Dependencies

```
whisper_jni.cpp: fullTranscribe gains a jobject progressCallback param
  └── WhisperLib.kt: external fun fullTranscribe gains ProgressCallback? param
      └── WhisperContext.transcribeData gains onProgress lambda param
          └── WhisperEngine.transcribe gains onProgress lambda param
              └── TranscriptionViewModel: UiState.Processing(progress: Int)
                  └── TranscriptionActivity: renders LinearProgressIndicator
```

This is a sequential chain. Any break means a regression. The UiState change is a **breaking sealed class change** — `TranscriptionActivity`'s `when` expression must be updated to match.

---

## Feature Area 2: F-Droid Distribution

### Table Stakes

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| FOSS LICENSE file in repo root | F-Droid hard requirement; no license = automatic rejection | Trivial | Choose MIT or Apache 2.0 — both are FOSS and compatible |
| Public source repository (already met) | F-Droid hard requirement | None | GitHub repo already public |
| All runtime dependencies must be FOSS | Firebase, GMS, Play Services would disqualify the app | Low | Audit `libs.versions.toml` — app already has zero network permissions, likely already compliant |
| fastlane metadata directory in repo | F-Droid reads this to populate the store listing automatically; without it, listing is sparse | Low | Directory structure defined below |
| fdroiddata YAML build recipe or `.fdroid.yml` | Tells F-Droid's build server the NDK version, subdir, gradle variant | Medium | NDK version must exactly match `build.gradle.kts` |

**Confidence:** HIGH — from F-Droid official documentation (f-droid.org/en/docs/)

### F-Droid Eligibility Check for TransChat

| Criterion | Status | Action Required |
|-----------|--------|----------------|
| FOSS source repo | PASS | Already public on GitHub |
| No GMS/Firebase | PASS | Zero network permissions; no Play Services |
| FOSS build tools (Gradle, NDK, CMake) | PASS | All FOSS toolchain |
| FOSS license file | UNKNOWN | Must add `LICENSE` file before submission |
| No embedded pre-built `.so` binaries | PASS | whisper.cpp compiled from source via CMake |
| Model weights not in APK | PASS | Downloaded separately to `filesDir` |
| NDK version documented in recipe | PENDING | Must specify `26.1.10909125` in YAML |

### fastlane Metadata Directory Structure

Create this directory tree in the repository root. F-Droid automatically reads it when building the store entry.

```
fastlane/
  metadata/
    android/
      en-US/
        short_description.txt   # Required. Max 80 chars. No trailing period.
        full_description.txt    # Required. Max 4000 chars.
        title.txt               # Optional. Max 50 chars.
        images/
          icon.png              # Optional. 512x512 PNG
          featureGraphic.png    # Optional. 1024x500 PNG
          phoneScreenshots/
            01_transcribing.png # Optional. Numbered sequentially.
            02_result.png
```

**`short_description.txt` draft (60 chars — well under 80 limit):**
```
Transcribe voice messages on-device. Private. Offline.
```

**`full_description.txt` key points:**
- Share a voice message from WhatsApp or Telegram
- Whisper AI model runs entirely on your device — no cloud, no server, no internet
- Supports Spanish and 98 other languages with automatic language detection
- Zero network permissions — your audio never leaves your phone
- Copy or share the transcript

### fdroiddata Build Recipe

F-Droid requires either:
1. A `metadata/com.simonpena.transchat.yml` file submitted as a merge request to the fdroiddata GitLab repo (standard path)
2. A `.fdroid.yml` in the app source repo root (alternative, faster for smaller projects)

**Critical fields for an NDK app (from F-Droid Build Metadata Reference):**

```yaml
Categories:
  - Multimedia

License: MIT   # or Apache-2.0 — must match LICENSE file

Build:
  - versionName: '1.1'
    versionCode: 2
    commit: v1.1
    subdir: app
    gradle:
      - release
    ndk: 26.1.10909125   # Must match ndkVersion in build.gradle.kts EXACTLY
```

**Why exact NDK version is critical:** F-Droid's documentation states NDK builds are "much more sensitive" to reproducibility than pure Java code. Even the same NDK version across host platforms (macOS vs Ubuntu) can produce binary differences in `.so` files. Specifying the numeric form (`26.1.10909125`) rather than the shorthand (`r26`) is more precise.

### Reproducible Builds

Reproducible builds are **not a hard requirement** for initial F-Droid inclusion. However:
- F-Droid's build server will attempt to build from source and compare
- NDK-compiled `.so` files commonly embed timestamps — this creates reproducibility failures
- For v1.1: target inclusion without full reproducibility; flag reproducible builds as a v1.2 goal
- If reproducibility is needed later: `doNotStrip '**/*.so'` in `packagingOptions` and `-Wl,--build-id=none` in CMake link flags

### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| IzzyOnDroid pre-listing | An F-Droid-compatible repo with faster review; good stepping stone before official F-Droid | Low | Submit fastlane metadata, point IzzyOnDroid at GitHub repo — no YAML recipe needed |
| Reproducible builds (APK signature match) | F-Droid can verify developer's APK equals build server output — maximum trust signal | High | NDK reproducibility is genuinely hard; defer to post-1.1 |

### Anti-Features (F-Droid perspective)

| Anti-Feature | Why Avoid | Notes |
|--------------|-----------|-------|
| Bundling pre-built `.so` binaries | F-Droid builds from source; pre-built native libs are an automatic review flag | whisper.cpp is compiled from source via CMake — already correct |
| Any Play Store API at runtime | F-Droid users have no Play Services; Play API calls crash | App has none — verify with dependency audit |
| Non-FOSS license for model weights | Whisper model weights are MIT licensed — this is fine | Already compliant |

### Feature Dependencies

```
F-Droid submission
  ├── LICENSE file (new, hard gate — required before ANY submission)
  ├── fastlane/metadata/android/en-US/ directory (new)
  └── .fdroid.yml or fdroiddata MR (new, NDK version must be exact)
```

All three are independent of each other and independent of Feature Areas 1 and 3.

---

## Feature Area 3: R8/ProGuard Configuration

### Current State Assessment

The existing `proguard-rules.pro` (15 lines) is functional but over-broad:

```proguard
# Blocks ALL R8 shrinking, renaming, optimization for the entire app package
-keep class com.simonpena.transchat.** { *; }
```

This single line prevents R8 from removing dead code, renaming classes for obfuscation, or inlining anything in the app. The `build.gradle.kts` already has `isMinifyEnabled = true` for release, but the over-broad keep negates most of its benefit.

### Table Stakes

| Feature | Why Expected | Complexity | Existing Rule |
|---------|--------------|------------|--------------|
| Keep JNI downcall methods (declared `native` in Kotlin) | If R8 renames these, JNI lookup fails: `UnsatisfiedLinkError` at runtime | Low | Present but can be tightened |
| Keep `WhisperLib$Companion` class name exact | JNI mangled name `Java_com_simonpena_transchat_WhisperLib_00024Companion_*` is hardcoded in compiled C++ | Low | Present but over-broad |
| Keep `UiState` sealed class hierarchy | StateFlow type checks on sealed subclasses fail if R8 renames them | Low | Currently kept by over-broad rule |
| Keep ViewModel classes | ViewModels are instantiated by reflection via `ViewModelProvider.Factory` | Low | Currently kept by over-broad rule |

**Confidence:** HIGH — verified against `whisper_jni.cpp` JNI naming pattern and Android official R8 documentation (Nov 2025 blog post)

### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Strip `Log.d`/`Log.i` calls from release builds | Removes debug log strings from release APK — privacy improvement + slight size reduction | Low | Add after confirming core rules work |
| R8 full mode (`android.enableR8.fullMode=true` in `gradle.properties`) | Stronger cross-class optimization | Medium | Can cause issues with Kotlin reflection patterns; test carefully on release build |

### Anti-Features

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| `-keep class com.simonpena.transchat.** { *; }` (current) | Defeats all R8 shrinking for the entire app package | Replace with surgical rules below |
| `-dontobfuscate` | Disables name mangling, makes reverse-engineering trivial | Remove if present (it is not — just don't add it) |
| `-dontshrink` | Disables dead code removal entirely | Never add |
| Keeping every class in the JNI layer | Only `WhisperLib$Companion` native methods and upcall targets need keeping | Use `keepclassmembers` not `keep class` |

### Recommended proguard-rules.pro (full replacement)

Replace the existing 15-line file with this surgical version:

```proguard
# ─── JNI Downcalls ────────────────────────────────────────────────────────────
# proguard-android-optimize.txt already includes:
#   -keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
# That handles WhisperLib$Companion's external funs. Explicit rule for safety:
-keepclassmembers,includedescriptorclasses class com.simonpena.transchat.WhisperLib$Companion {
    native <methods>;
}

# ─── JNI Upcalls: ProgressCallback (added in v1.1) ───────────────────────────
# C++ calls onProgress(int) by method name via CallVoidMethod — R8 must not rename it.
# Without this rule, first callback fires but method is renamed → NoSuchMethodError.
-keepclassmembers interface com.simonpena.transchat.WhisperLib$ProgressCallback {
    public abstract void onProgress(int);
}

# ─── Kotlin Sealed Class UiState ─────────────────────────────────────────────
# StateFlow type checks on sealed subclasses require original names
-keep class com.simonpena.transchat.UiState { *; }
-keep class com.simonpena.transchat.UiState$* { *; }

# ─── ViewModels (instantiated by reflection via ViewModelProvider) ─────────────
-keep class com.simonpena.transchat.TranscriptionViewModel { *; }
-keep class com.simonpena.transchat.DownloadViewModel { *; }

# ─── Stack trace readability (optional but recommended) ───────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
```

**What changes vs. current config:**

| Scope | Current (over-broad) | Recommended (surgical) |
|-------|---------------------|----------------------|
| App package shrinking | None (blocked by blanket keep) | All non-JNI, non-ViewModel, non-UiState classes eligible for R8 |
| Dead code removal | Blocked | Enabled for eligible classes |
| Class name obfuscation | Blocked | Enabled for eligible classes |
| JNI surface | All kept | Only native methods and upcall methods kept |
| APK size impact | ~0 reduction | Moderate (small app; savings likely 20-100KB of Kotlin bytecode) |
| Risk of runtime crash | Low (everything kept) | Low (all JNI surface covered by targeted rules) |

### Critical Rule: ProgressCallback Upcall

This rule is the v1.1 addition. The C++ `progress_callback_impl` function calls `env->CallVoidMethod(callbackObj, onProgressId, progress)` where `onProgressId` was looked up by name at JNI attach time. If R8 renames `onProgress`, the method ID lookup succeeds at attach but the renamed method does not match at invocation — resulting in a `NoSuchMethodError` on the first progress update. The `keepclassmembers interface` rule prevents this.

### Feature Dependencies

```
R8 surgical rewrite
  ├── Must be done AFTER WhisperLib.ProgressCallback interface is finalized (name must match keep rule)
  ├── Must be tested with a release build before shipping (debug builds skip R8)
  └── Log stripping (optional differentiator) should be added last, after all functional tests pass
```

---

## Feature Area 4: UX Patterns for Transcription Progress Display

### What Users Experience Today

`UiState.Processing` renders an indeterminate spinner. For a 12-15 second operation this causes:
- Perceived freeze ("Did the app crash?")
- Back button taps during inference — Activity recreation → second transcription job launched
- User uncertainty about whether the share worked at all

### Table Stakes (from UX research and Material Design 3 guidelines)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Determinate `LinearProgressIndicator` (0-100%) | Material Design 3 spec: use determinate indicator when duration is known; at 12-15s, users need visible progress | Low | Single Compose component swap from current indeterminate |
| Progress percentage text label | Reinforces determinate nature; anchors expectation at "64% — almost there" | Low | One `Text()` composable below the bar |
| Back press handling during inference | Tapping Back currently cancels the coroutine but native call continues; at minimum, show a dialog warning ("Transcription in progress") | Low | `onBackPressedDispatcher` override |

**Research basis:** Material Design 3 Progress Indicators; UX study finding 3x longer wait tolerance with determinate bars vs. spinners (University of Nebraska-Lincoln, cited in multiple UX analysis sources).

### Differentiators

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Phase label that changes with pipeline stage | "Loading model..." → "Transcribing (45%)..." → "Done" anchors user in the 5-step pipeline | Low | Map progress value to label strings |
| Partial segment text preview during inference | Live words appearing as Whisper produces them — strong signal that it's working | High | Requires `new_segment_callback` + additional JNI upcall + `StateFlow` update from inference thread; deferred to v1.2 |

### Anti-Features (UX)

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| Fake/timer-based determinism | Users notice the stall at 90%; erodes trust immediately | Use real `progress_callback` values |
| Full-screen blocking overlay that prevents Back | Users sharing while busy need to be able to leave and return; the ViewModel survives Activity recreation | Small progress bar within the existing result screen layout |
| Showing raw Whisper segment timestamps | Confusing to non-technical users (`[00:00:01 --> 00:00:06]`) | Already stripped by `HallucinationFilter` — keep it that way |
| Cancel button that does not actually interrupt inference | Current `cancelInference()` cancels the coroutine but native `whisper_full()` runs to completion | Either add `abort_callback` support (out of scope v1.1) or omit the cancel button entirely |

### Progress Rendering Logic

```
progress value (0-100) from progress_callback → UI state

0        → Indeterminate spinner (model loading, not yet in encoder)
1-10     → Determinate bar, label: "Transcribing..."
11-89    → Determinate bar + percentage text, label: "Transcribing..."
90-99    → Determinate bar, label: "Finishing..."
100      → Immediate transition to UiState.Success / NoSpeech / Error
          (callback fires before whisper_full() returns; Success follows)
```

**Why not show partial text in v1.1:** `new_segment_callback` fires on whisper's inference thread. The `user_data` upcall to Kotlin requires `AttachCurrentThread`. Each segment triggers a `MutableStateFlow` update from a background thread — which is thread-safe with `MutableStateFlow` but adds architectural complexity (a second JNI callback type, a second global ref, a second upcall path). The progress percentage is the right v1.1 scope. Segment preview is the right v1.2 differentiator once the callback infrastructure is proven.

---

## Feature Area 5: Branch Rename (master to main)

### Classification

Housekeeping. Not a feature. Complexity: Trivial.

### Dependencies

```
Branch rename
  ├── Check GitHub Actions workflow files for hardcoded 'master' references
  ├── Update local git remote tracking: git branch -m master main + git push origin --delete master
  └── No code changes required
```

---

## Full v1.1 Feature Dependency Graph

```
[1] whisper_jni.cpp: progress_callback_impl + ProgressCallbackData struct
    └── [2] WhisperLib.kt: ProgressCallback functional interface
        └── [3] WhisperLib.kt: fullTranscribe gains ProgressCallback? param
            └── [4] WhisperContext.transcribeData gains onProgress lambda param
                └── [5] WhisperEngine.transcribe gains onProgress lambda param
                    └── [6] TranscriptionViewModel: UiState.Processing(progress: Int)
                        └── [7] TranscriptionActivity: LinearProgressIndicator

[8]  LICENSE file (new) ────────────────────────── F-Droid hard gate
[9]  fastlane/metadata/ directory (new) ─────────── F-Droid store listing
[10] .fdroid.yml or fdroiddata MR (new) ─────────── F-Droid build recipe

[11] proguard-rules.pro surgical rewrite
     └── ProgressCallback keep rule must come AFTER [2] is finalized

[12] git branch rename (independent)
```

Items [8], [9], [10], [11], [12] are independent of the callback chain [1]-[7] and of each other.
Items [1]-[7] are a sequential chain — break any link = crash or regression.

---

## Build Order Recommendation

1. **R8 rules rewrite** ([11]) — no new code, lowest risk; proves the release build compiles cleanly before adding JNI complexity
2. **progress_callback C++ + Kotlin wiring** ([1]-[5]) — highest user-visible value; medium complexity
3. **UiState.Processing(progress) + UI LinearProgressIndicator** ([6]-[7]) — completes the progress feature
4. **LICENSE file** ([8]) — add before any F-Droid submission; 5-minute task
5. **fastlane/metadata/** ([9]) — populate with text and screenshots
6. **fdroiddata YAML or `.fdroid.yml`** ([10]) — submit after v1.1 is tagged and pushed
7. **Branch rename** ([12]) — do last, after all PRs are merged

**Defer to v1.2:**
- `new_segment_callback` for partial segment text preview — high complexity, not needed for the progress win
- Reproducible builds — NDK reproducibility is hard; acceptable to skip for initial F-Droid inclusion
- `abort_callback` for real cancellation — out of scope for v1.1

---

## Sources

- Callback API: local file `/workspace/transchat-app/app/src/main/cpp/whisper.cpp/include/whisper.h` lines 449-564 — HIGH confidence (read directly)
- Current JNI implementation: `/workspace/transchat-app/app/src/main/cpp/whisper_jni.cpp` — HIGH confidence (read directly)
- Current Kotlin JNI bridge: `/workspace/transchat-app/app/src/main/java/com/simonpena/transchat/WhisperLib.kt` — HIGH confidence (read directly)
- Current R8 config: `/workspace/transchat-app/app/proguard-rules.pro` and `app/build.gradle.kts` — HIGH confidence (read directly)
- F-Droid Inclusion Policy: [F-Droid Inclusion How-To](https://f-droid.org/en/docs/Inclusion_How-To/) — HIGH confidence
- F-Droid Fastlane Metadata: [All About Descriptions, Graphics, and Screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/) — HIGH confidence
- F-Droid Reproducible Builds: [Reproducible Builds](https://f-droid.org/en/docs/Reproducible_Builds/) — HIGH confidence (NDK sensitivity warning verified here)
- F-Droid Build Metadata Reference: [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/) — HIGH confidence (NDK YAML field syntax)
- R8 Keep Rules (Nov 2025 blog): [Configure and troubleshoot R8 Keep Rules](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html) — HIGH confidence
- R8 Keep Rule Examples: [Keep rule use cases and examples](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples) — HIGH confidence
- Progress UX: [Material Design 3 Progress Indicators](https://m3.material.io/components/progress-indicators) — HIGH confidence
- JNI Callback threading: [JNI tips — Android NDK](https://developer.android.com/training/articles/perf-jni) — HIGH confidence
