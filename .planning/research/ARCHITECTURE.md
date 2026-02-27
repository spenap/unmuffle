# Architecture Patterns: TransChat v1.1 Feature Integration

**Domain:** Android on-device ML inference — progress callbacks, F-Droid distribution, R8 shrinking
**Researched:** 2026-02-27
**Overall confidence:** HIGH (all three areas verified against live codebase, whisper.h API, F-Droid official docs, Android R8 docs)

---

## Context: What Already Exists (v1.0)

The v1.0 architecture is stable and well-structured. This document focuses exclusively on how the three v1.1 features integrate with it, identifying exactly which files change, which files are new, and the data flow across component boundaries.

Existing pipeline (unchanged in v1.1):

```
ShareReceiverActivity
  → TranscriptionActivity
    → TranscriptionViewModel (StateFlow<UiState>)
      → WhisperEngine.transcribe(FloatArray): String
        → WhisperContext.transcribeData(FloatArray): String
          → WhisperLib.fullTranscribe(contextPtr, numThreads, audioData)  [JNI]
            → whisper_jni.cpp: fullTranscribe()
              → whisper_full(ctx, params, audioDataArr, audioDataLength)
```

Key constraint: `whisper_full()` is synchronous and blocking. The whisper.cpp context is owned by a `newSingleThreadExecutor`. The JNI call runs to completion before returning — there is no cooperative cancellation point inside `whisper_full`.

---

## Feature 1: Progress Callbacks

### What whisper.cpp Provides

`whisper.h` (v1.7.4, confirmed in `/app/src/main/cpp/whisper.cpp/include/whisper.h`) exposes two callback hooks on `whisper_full_params`:

```c
// Called on each progress update (0-100 int)
typedef void (*whisper_progress_callback)(
    struct whisper_context * ctx,
    struct whisper_state   * state,
    int                      progress,  // 0-100
    void                   * user_data
);

// Called for each newly completed text segment
typedef void (*whisper_new_segment_callback)(
    struct whisper_context * ctx,
    struct whisper_state   * state,
    int                      n_new,     // number of new segments
    void                   * user_data
);
```

Both are set on the `whisper_full_params` struct before calling `whisper_full()`. The `user_data` pointer is opaque to whisper.cpp — it is passed through unchanged to each callback invocation.

These callbacks fire on the same thread that called `whisper_full()` — in this app, the single-thread executor thread owned by `WhisperContext`.

### Integration Point: whisper_jni.cpp

The current `fullTranscribe` JNI function in `whisper_jni.cpp` does not set callbacks:

```cpp
// Current (v1.0):
struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
params.n_threads = numThreads;
// ... other params ...
// NO callbacks set
int ret = whisper_full(ctx, params, audioDataArr, audioDataLength);
```

To add progress reporting, `fullTranscribe` must accept a Java/Kotlin callback object and register it with the params before calling `whisper_full`.

### JNI Callback Pattern: How Upcalls Work

The standard Android NDK pattern for C++ → Kotlin callbacks:

1. The Kotlin side passes a callback object (or the `WhisperLib.Companion` object itself) to the JNI function as a `jobject`.
2. The C++ side stores a `GlobalRef` to that object (local refs are invalid after the JNI call returns to Java).
3. The C++ callback function receives `user_data` (the GlobalRef cast to `void*`), re-attaches the JVM thread (callbacks fire on a native thread — the JNIEnv from the original call is NOT valid here), calls `env->CallVoidMethod(jobj, methodId, progress)`, then detaches.
4. After `whisper_full` returns, the GlobalRef is deleted.

The critical constraint: **callbacks fire on the native thread (the single-thread executor), not the main thread**. The Kotlin callback must not touch UI directly — it must post to a StateFlow or Channel.

### Recommended Pattern: Atomic Integer (No JNI Upcall)

The JNI upcall pattern is correct but adds complexity (GlobalRef, thread attach/detach, FindClass caching). For a progress bar that shows 0-100%, a simpler approach has lower risk:

**Option A — AtomicInteger polling (RECOMMENDED for this app):**

```cpp
// In whisper_jni.cpp
struct ProgressState {
    std::atomic<int> progress{0};
};

static void progress_cb(whisper_context*, whisper_state*, int p, void* user_data) {
    auto* state = static_cast<ProgressState*>(user_data);
    state->progress.store(p, std::memory_order_relaxed);
}

// fullTranscribeWithProgress: runs whisper_full, returns progress pointer
// Kotlin polls via a new JNI function: getProgress(ptr): Int
```

This requires a new JNI function `getProgress(Long): Int` but eliminates all thread attach/detach complexity. Kotlin polls from a coroutine using `delay(200)` while `fullTranscribe` runs on the executor thread.

However, since `transcribeData` in `WhisperContext` uses `withContext(scope.coroutineContext)` to block the coroutine, polling from the ViewModel requires a parallel coroutine.

**Option B — Channel-based upcall (RECOMMENDED if segment text is needed):**

If the UI should show partial transcript text as segments complete (not just a progress bar), a JNI upcall is the only viable approach. The new_segment_callback fires with completed segment text available via `whisper_full_get_segment_text`.

### Chosen Architecture for v1.1: Progress Bar Only (Option A)

Given that v1.1 scope is specifically "progress bar with segment callbacks," and voice messages are typically under 2 minutes, the AtomicInteger approach is appropriate. The progress bar goes from 0% to 100% based on the `whisper_progress_callback` integer value.

### New and Modified Components

**Modified: `whisper_jni.cpp`**

Add a static `ProgressState` struct and register `progress_callback` on params. Add a new exported function:

```cpp
JNIEXPORT jint JNICALL
Java_com_simonpena_transchat_WhisperLib_00024Companion_getProgress(
        JNIEnv*, jobject, jlong progressStatePtr) {
    auto* state = (ProgressState*)(intptr_t)progressStatePtr;
    return state ? state->progress.load() : 0;
}
```

Modify `fullTranscribe` signature to return `jlong` (the ProgressState pointer) instead of `void`, OR add a separate `beginTranscribeSession`/`endTranscribeSession` pair.

Simpler approach: allocate the ProgressState before calling `fullTranscribe`, pass its address via `progress_callback_user_data`, and expose a `getProgress(contextPtr)` that reads the atomic stored on the context-level ProgressState. Since the context is single-use per session, this is safe.

**Modified: `WhisperLib.kt`**

Add:
```kotlin
external fun getProgress(contextPtr: Long): Int
```

**Modified: `WhisperContext.transcribeData()`**

The current signature returns `String`. To enable progress polling, the ViewModel needs to launch two concurrent coroutines on the executor scope — one for transcription, one for progress polling. This requires exposing the scope or restructuring `transcribeData`.

Better: pass a progress lambda into `transcribeData`:

```kotlin
suspend fun transcribeData(
    data: FloatArray,
    onProgress: (Int) -> Unit = {}
): String = withContext(scope.coroutineContext) {
    // Launch progress poller as a child coroutine
    val pollerJob = launch {
        while (isActive) {
            onProgress(WhisperLib.getProgress(ptr))
            delay(100)
        }
    }
    WhisperLib.fullTranscribe(ptr, WhisperCpuConfig.preferredThreadCount, data)
    pollerJob.cancel()
    onProgress(100) // Ensure 100% shown
    // ... collect segments ...
}
```

**Modified: `WhisperEngine.transcribe()`**

Add `onProgress: (Int) -> Unit = {}` parameter, forwarded to `WhisperContext.transcribeData()`.

**Modified: `TranscriptionViewModel.startTranscription()`**

Pass a progress lambda to `engine.transcribe()`:

```kotlin
val raw = engine.transcribe(pcm) { progress ->
    _uiState.value = UiState.Processing(progress)
}
```

**Modified: `UiState` sealed class**

```kotlin
// v1.0:
object Processing : UiState()

// v1.1:
data class Processing(val progress: Int = 0) : UiState()
```

This is a data class change, not a sealed class restructure — the Activity already handles `UiState.Processing` in a `when` block and only needs to read `state.progress` to drive a `LinearProgressIndicator`.

**Modified: `activity_transcription.xml`**

Replace the `CircularProgressIndicator` (indeterminate spinner) with a `LinearProgressIndicator`:

```xml
<LinearProgressIndicator
    android:id="@+id/progress_bar"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:trackCornerRadius="4dp"
    android:indeterminate="false"
    app:indicatorColor="?attr/colorPrimary"
    android:max="100"
    android:progress="0" />
```

**Modified: `TranscriptionActivity`**

In the `UiState.Processing` branch of the `when` block:

```kotlin
is UiState.Processing -> {
    progressBar.progress = state.progress
    progressPercent.text = "${state.progress}%"
    // ...
}
```

### Data Flow: Progress Callback

```
whisper_full() [C++]
  → progress_callback fires every ~10%
    → ProgressState.progress.store(p)

WhisperContext.transcribeData() [Kotlin, executor thread]
  → child coroutine polls every 100ms
    → WhisperLib.getProgress(ptr) [JNI]
      → returns ProgressState.progress.load()
    → onProgress(progress) [lambda]
      → WhisperEngine forwards to ViewModel lambda

TranscriptionViewModel [Main thread via StateFlow]
  → _uiState.value = UiState.Processing(progress)

TranscriptionActivity [Main thread]
  → progressBar.progress = state.progress
```

### Component Change Summary

| Component | Change Type | What Changes |
|-----------|------------|--------------|
| `whisper_jni.cpp` | Modified | Add `ProgressState` struct, register `progress_callback`, add `getProgress` JNI export |
| `WhisperLib.kt` | Modified | Add `external fun getProgress(contextPtr: Long): Int` |
| `WhisperContext.kt` | Modified | `transcribeData` gains `onProgress: (Int) -> Unit` param, launches poller coroutine |
| `WhisperEngine.kt` | Modified | `transcribe()` gains `onProgress` param, forwards to WhisperContext |
| `UiState` (in `TranscriptionViewModel.kt`) | Modified | `Processing` changes from `object` to `data class(val progress: Int = 0)` |
| `TranscriptionViewModel.kt` | Modified | Pass progress lambda to `engine.transcribe()`, emit `UiState.Processing(progress)` |
| `TranscriptionActivity.kt` | Modified | Read `state.progress` in `UiState.Processing` branch, set on progress bar |
| `activity_transcription.xml` | Modified | Replace `CircularProgressIndicator` (indeterminate) with `LinearProgressIndicator` |

No new classes required. The entire change propagates through the existing call chain.

---

## Feature 2: F-Droid Metadata

### F-Droid Fastlane Structure

F-Droid reads app metadata from a `fastlane/metadata/android/` tree inside the repository root. This is the same format used by the fastlane supply tool. The structure is checked into the repository alongside the app source code.

Confirmed layout (from F-Droid official documentation, HIGH confidence):

```
<repo root>/
  fastlane/
    metadata/
      android/
        en-US/
          title.txt              ← App name (50 chars max)
          short_description.txt  ← Tagline (80 chars max, no trailing period)
          full_description.txt   ← Store listing body (4000 chars max)
          changelogs/
            2.txt                ← Changelog for versionCode=2 (500 chars max)
            1.txt                ← Changelog for versionCode=1
          images/
            icon.png             ← 512x512 PNG
            featureGraphic.png   ← 1024x500 PNG (optional)
            phoneScreenshots/
              01.png
              02.png
```

For the TransChat project, where `app/build.gradle.kts` has `versionCode = 1`:

```
fastlane/metadata/android/en-US/changelogs/1.txt
```

### Placement in This Repository

The fastlane tree sits at the repository root, not inside `app/`:

```
transchat-app/           ← repository root
  fastlane/
    metadata/
      android/
        en-US/
          title.txt
          short_description.txt
          full_description.txt
          changelogs/
            1.txt        ← for versionCode 1 (initial release)
            2.txt        ← for versionCode 2 (v1.1)
```

versionCode must match between `app/build.gradle.kts` and the changelog filename. When v1.1 ships, `versionCode` should be incremented to 2 and `changelogs/2.txt` added.

### F-Droid Submission Metadata (`metadata/com.simonpena.transchat.yml`)

In addition to the fastlane tree (which supplies the display content), F-Droid's build server requires a YAML file in the fdroiddata repository. This is maintained by F-Droid maintainers/volunteers, not the app developer. However, the app developer can prepare a draft:

```yaml
# This file lives in fdroiddata repo, not this repo
AntiFeatures: []
Categories:
  - Multimedia
License: MIT   # or whatever the license is
AuthorName: Simon Pena
SourceCode: https://github.com/simonpena/transchat-app
IssueTracker: https://github.com/simonpena/transchat-app/issues

RepoType: git
Repo: https://github.com/simonpena/transchat-app

Builds:
  - versionName: '1.1'
    versionCode: 2
    commit: v1.1
    submodules: true
    gradle:
      - yes
    ndk: r26c

AutoUpdateMode: None
UpdateCheckMode: Tags
```

Note: `submodules: true` is required because whisper.cpp is a git submodule.

### Reproducible Build Consideration

F-Droid reproducible builds are sensitive to NDK path differences (they affect debug symbol paths, which affect the linker's build-id). The app already uses `ndkVersion = "26.1.10909125"` (NDK r26c) in `build.gradle.kts`, which pins the NDK version for reproducibility.

To further improve reproducibility for the NDK build, consider adding to `CMakeLists.txt`:

```cmake
# Disable build-id to improve reproducibility across different build machines
# (different paths in debug symbols cause different build-ids)
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,--build-id=none")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--build-id=none")
```

This is a known F-Droid recommendation for NDK-based apps (MEDIUM confidence — from F-Droid reproducible builds docs).

### New Files for F-Droid

| File Path | Type | Content |
|-----------|------|---------|
| `fastlane/metadata/android/en-US/title.txt` | New | `TransChat` |
| `fastlane/metadata/android/en-US/short_description.txt` | New | `Transcribe WhatsApp and Telegram voice messages on-device` |
| `fastlane/metadata/android/en-US/full_description.txt` | New | Full privacy-focused store description |
| `fastlane/metadata/android/en-US/changelogs/1.txt` | New | Initial release changelog |
| `fastlane/metadata/android/en-US/changelogs/2.txt` | New | v1.1 changelog (add when versionCode bumped to 2) |
| `fastlane/metadata/android/en-US/images/icon.png` | New | 512x512 app icon |

No changes to `app/build.gradle.kts` required for fastlane metadata. The versionCode increment is the only build file change for v1.1.

---

## Feature 3: R8 Configuration

### Current State

`app/proguard-rules.pro` already exists with these rules:

```proguard
# Keep all JNI-facing native methods
-keepclassmembers class * {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the entire transchat package
-keep class com.simonpena.transchat.** { *; }

# Keep WhisperLib companion object specifically
-keep class com.simonpena.transchat.WhisperLib { *; }
-keep class com.simonpena.transchat.WhisperLib$Companion { *; }
```

`app/build.gradle.kts` already references this file:

```kotlin
release {
    isMinifyEnabled = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

### Analysis of Existing Rules

**The broad `-keep class com.simonpena.transchat.** { *; }` is correct but overprotective.** It preserves every field and method in every class in the package, including things R8 could otherwise shrink. This is safe but leaves optimization on the table.

**The two `native <methods>` rules are the critical ones.** The `proguard-android-optimize.txt` base file already includes `-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }`, so the existing rules in `proguard-rules.pro` are partly redundant. No harm — redundant keep rules do not break anything.

### What v1.1 Adds to R8 Configuration

#### New JNI function: `getProgress`

Adding `external fun getProgress(contextPtr: Long): Int` to `WhisperLib.Companion` requires no new ProGuard rule because:
- The broad `-keep class com.simonpena.transchat.WhisperLib$Companion { *; }` already covers it.
- The `native <methods>` rules already cover it.

#### UiState change: `Processing` becomes a data class

`UiState.Processing` changing from `object` to `data class(val progress: Int = 0)` has no R8 impact. The class is used internally by the app and never accessed via reflection or JNI.

#### Sealed class and ViewModels

The existing rules keep all of `com.simonpena.transchat.**`, which includes `UiState`, `TranscriptionViewModel`, and all sealed subclasses. No additional rules needed.

### Recommended R8 Refinement (Optional)

The current `-keep class com.simonpena.transchat.** { *; }` could be narrowed to reduce APK size, but given that the app is small and privacy-focused (simple UX), this is a minor concern. If the rule is tightened:

```proguard
# Keep JNI bridge — class name and companion must not be renamed
# (JNI name mangling requires exact package+class names)
-keep class com.simonpena.transchat.WhisperLib { *; }
-keep class com.simonpena.transchat.WhisperLib$Companion { *; }

# Keep ViewModels (accessed by AndroidX ViewModelProvider via reflection)
-keep class com.simonpena.transchat.*ViewModel { *; }
-keep class com.simonpena.transchat.*ViewModel$** { *; }

# Keep Activities (accessed by Android system via reflection at startup)
-keep class com.simonpena.transchat.*Activity { *; }

# Keep UiState sealed hierarchy (accessed via when() — no reflection, but keep to be safe)
-keep class com.simonpena.transchat.UiState { *; }
-keep class com.simonpena.transchat.UiState$** { *; }
```

However, the risk of breaking things by narrowing rules outweighs the size benefit for this app. **Recommendation: leave the existing rules as-is for v1.1 and only tighten if APK size becomes a concern.**

### R8 Rule File Location

There is exactly one ProGuard rule file location in this project:

```
app/proguard-rules.pro
```

Referenced from `app/build.gradle.kts`. No separate module-level files (the app has a single module). No `consumer-rules.pro` (only relevant for library modules).

The base rules from AOSP are injected automatically by the Android Gradle Plugin:

```
<SDK>/tools/proguard/proguard-android-optimize.txt
```

Both files are merged at build time. Rules in `proguard-rules.pro` take precedence for conflicts.

### Critical R8 Constraint: JNI Name Mangling

The JNI function names in `whisper_jni.cpp` are derived from the Java package + class + method name via a deterministic mangling rule:

```
Java_<package>_<class>_<method>
```

For example:
```
Java_com_simonpena_transchat_WhisperLib_00024Companion_fullTranscribe
```

The `00024` is the Unicode escape for `$` (the companion object separator). If R8 renames `WhisperLib` or its `Companion`, the JNI symbol lookup fails at runtime with `UnsatisfiedLinkError`. The existing keep rules correctly prevent this.

**Any new JNI method added to `WhisperLib.Companion` is automatically protected by the existing `-keep class com.simonpena.transchat.WhisperLib$Companion { *; }` rule.** No additional rule needed per new method.

---

## Build Order for v1.1

Dependencies between the three feature areas:

```
R8 rules (proguard-rules.pro)
  ← no dependencies on other v1.1 features
  ← can ship independently

F-Droid metadata (fastlane/)
  ← no code dependencies
  ← depends on: versionCode incremented in build.gradle.kts
  ← can be prepared in parallel with progress callback work

Progress callbacks
  ← depends on: whisper_jni.cpp changes first (adds getProgress)
  ← then: WhisperLib.kt (new external fun)
  ← then: WhisperContext.kt (poller coroutine)
  ← then: WhisperEngine.kt (forward onProgress param)
  ← then: UiState.Processing change
  ← then: TranscriptionViewModel.kt (emit progress)
  ← then: activity_transcription.xml (new progress bar widget)
  ← then: TranscriptionActivity.kt (read progress from state)
```

Recommended implementation order within progress callbacks:

1. Add `ProgressState` struct + `progress_callback` to `whisper_jni.cpp` + add `getProgress` JNI export — verify with `adb logcat` that callback fires.
2. Add `external fun getProgress` to `WhisperLib.kt`.
3. Modify `WhisperContext.transcribeData()` to launch poller coroutine + accept `onProgress` lambda.
4. Modify `WhisperEngine.transcribe()` to accept + forward `onProgress`.
5. Change `UiState.Processing` from `object` to `data class`.
6. Modify `TranscriptionViewModel` to pass progress lambda.
7. Update `activity_transcription.xml` — replace spinner with linear progress bar.
8. Update `TranscriptionActivity` to set `progressBar.progress`.

Each step is independently testable before proceeding.

---

## Component Boundaries: New vs Modified

### New Files (v1.1)

| File | Location | Purpose |
|------|----------|---------|
| `title.txt` | `fastlane/metadata/android/en-US/` | F-Droid app name |
| `short_description.txt` | `fastlane/metadata/android/en-US/` | F-Droid tagline |
| `full_description.txt` | `fastlane/metadata/android/en-US/` | F-Droid store listing |
| `changelogs/1.txt` | `fastlane/metadata/android/en-US/changelogs/` | Initial release notes |
| `changelogs/2.txt` | `fastlane/metadata/android/en-US/changelogs/` | v1.1 release notes |
| `icon.png` | `fastlane/metadata/android/en-US/images/` | 512x512 app icon |

### Modified Files (v1.1)

| File | Location | What Changes |
|------|----------|-------------|
| `whisper_jni.cpp` | `app/src/main/cpp/` | Add `ProgressState` struct, register `progress_callback`, add `getProgress` export |
| `WhisperLib.kt` | `app/src/main/java/com/simonpena/transchat/` | Add `external fun getProgress(contextPtr: Long): Int` |
| `WhisperContext` (inside `WhisperLib.kt`) | same file | `transcribeData()` gains `onProgress` param + poller coroutine |
| `WhisperEngine.kt` | `app/src/main/java/com/simonpena/transchat/` | `transcribe()` gains `onProgress: (Int) -> Unit = {}` |
| `TranscriptionViewModel.kt` | `app/src/main/java/com/simonpena/transchat/` | `UiState.Processing` becomes `data class`, pass progress lambda to engine |
| `TranscriptionActivity.kt` | `app/src/main/java/com/simonpena/transchat/` | Read `state.progress`, set on progress bar |
| `activity_transcription.xml` | `app/src/main/res/layout/` | Replace `CircularProgressIndicator` with `LinearProgressIndicator` |
| `app/build.gradle.kts` | `app/` | Increment `versionCode` from 1 to 2 for v1.1 |
| `proguard-rules.pro` | `app/` | No required changes; optional narrowing of broad keep rule |

### No Changes Required

| Component | Why Untouched |
|-----------|---------------|
| `AudioDecoder.kt` | Progress is only needed during Whisper inference, not decode |
| `PcmResampler.kt` | Same reason |
| `HallucinationFilter.kt` | Runs after inference, no progress needed |
| `ShareReceiverActivity.kt` | Passes audio path through, does not observe progress |
| `ModelManager.kt` | Model load is fast (~136ms), no progress needed there |
| `CMakeLists.txt` | No new source files; `getProgress` is in existing `whisper_jni.cpp` |
| AndroidManifest.xml | No new activities, permissions, or intent filters |

---

## Architecture Diagram: v1.1 Progress Flow

```
┌─────────────────────────────────────────────────────────────────┐
│  TranscriptionActivity (Main thread)                            │
│  Observes: StateFlow<UiState>                                   │
│  UiState.Processing(progress: Int) → progressBar.progress = p  │
└───────────────────┬─────────────────────────────────────────────┘
                    │ StateFlow emission (Dispatchers.Main)
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│  TranscriptionViewModel                                          │
│  _uiState.value = UiState.Processing(progress)                  │
│  Passes onProgress lambda → engine.transcribe(pcm) { p → ... } │
└───────────────────┬─────────────────────────────────────────────┘
                    │ lambda call (executor thread)
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│  WhisperEngine.transcribe(audioData, onProgress)                │
│  Delegates to WhisperContext.transcribeData(data, onProgress)   │
└───────────────────┬─────────────────────────────────────────────┘
                    │ coroutine on single-thread executor
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│  WhisperContext.transcribeData() [single-thread executor]        │
│  ├── child coroutine: poller                                    │
│  │     loop: WhisperLib.getProgress(ptr) every 100ms           │
│  │           → calls onProgress(p)                             │
│  └── WhisperLib.fullTranscribe(ptr, threads, data) [blocks]    │
│         → whisper_full() running in C++ [12-15s]               │
│               → progress_callback fires every ~10%              │
│                     → ProgressState.progress.store(p)          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Confidence Assessment

| Area | Confidence | Source |
|------|------------|--------|
| whisper.cpp progress_callback API | HIGH | Verified in `/app/src/main/cpp/whisper.cpp/include/whisper.h` lines 454-455, 553-555 |
| AtomicInteger pattern for JNI progress | HIGH | Standard C++ atomic + JNI pattern; no upcall complexity |
| UiState.Processing change | HIGH | Direct code inspection of `TranscriptionViewModel.kt` |
| F-Droid fastlane directory layout | HIGH | F-Droid official documentation (multiple sources confirm) |
| F-Droid fastlane file names | HIGH | F-Droid official documentation |
| R8 existing rule sufficiency | HIGH | Code inspection of `proguard-rules.pro` + `build.gradle.kts` |
| NDK build-id reproducibility | MEDIUM | F-Droid reproducible builds docs, not yet tested against this specific project |
| Progress callback fire frequency | MEDIUM | whisper.cpp source shows ~10% increments but exact behavior depends on audio length and model |

---

## Sources

- `whisper.h` (v1.7.4, lines 449-570): progress_callback and new_segment_callback typedefs and struct fields — verified in this repository at `/app/src/main/cpp/whisper.cpp/include/whisper.h` (HIGH confidence)
- `whisper_jni.cpp` (this repository): Current JNI bridge — 6 exported functions, no callbacks set (HIGH confidence — direct code read)
- `WhisperLib.kt` / `WhisperContext` (this repository): Current JNI Kotlin binding — single-thread executor pattern (HIGH confidence)
- `TranscriptionViewModel.kt` (this repository): UiState sealed class, Processing object, StateFlow<UiState> (HIGH confidence)
- `proguard-rules.pro` (this repository): Existing R8 rules (HIGH confidence — direct read)
- [F-Droid: All About Descriptions, Graphics, and Screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/) — fastlane directory structure (HIGH confidence)
- [F-Droid: Submitting to F-Droid Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/) — submission process (HIGH confidence)
- [F-Droid: Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/) — NDK path / build-id concerns (MEDIUM confidence)
- [Android Developers: Keep rule use cases and examples](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples) — JNI keep rules, upcall preservation (HIGH confidence)
- [Android Developers Blog: Configure and troubleshoot R8 Keep Rules (Nov 2025)](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html) — R8 native method handling (HIGH confidence)
- [Android Developers: JNI Tips](https://developer.android.com/training/articles/perf-jni) — GlobalRef pattern, thread attachment for upcalls (HIGH confidence)
