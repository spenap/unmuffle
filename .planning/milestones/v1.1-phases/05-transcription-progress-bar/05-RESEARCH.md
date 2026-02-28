# Phase 5: Transcription Progress Bar - Research

**Researched:** 2026-02-27
**Domain:** Android JNI progress callback + Material 3 determinate progress indicator
**Confidence:** HIGH

## Summary

This phase replaces the indeterminate CircularProgressIndicator spinner with a real 0-100% determinate progress bar driven by whisper.cpp's native `progress_callback`. The approach uses an AtomicInteger polling pattern -- a C++ `std::atomic<int>` stores the progress value written by the whisper.cpp callback, and a Kotlin coroutine polls it every 100-200ms via a lightweight JNI downcall. This eliminates the complexity of JNI upcalls (GlobalRef management, thread attachment, R8 stripping risk) while providing sufficient update granularity for the UI.

The existing Material 3 `CircularProgressIndicator` (com.google.android.material 1.12.0) natively supports runtime switching between indeterminate and determinate modes via `setProgressCompat(progress, animated)` and `setIndeterminate(boolean)`. No XML layout structural changes are needed -- only runtime property changes and a new `android:id` on the status label for programmatic text updates.

The main technical concern is that `progress_callback` fires infrequently for short audio: whisper.cpp processes audio in 30-second chunks (`WHISPER_CHUNK_SIZE = 30`), so a 30-second voice message produces only 1-2 callback firings. However, for TransChat's typical 12-60 second messages, this translates to 1-4 progress updates at roughly 25%/50%/75%/100% granularity, which provides meaningful visual feedback even if not perfectly smooth. The polling loop will simply re-read the last stored value between callback firings, giving the UI a "step" pattern rather than smooth animation -- this is acceptable per user decisions (real callback-driven progress, not simulated).

**Primary recommendation:** Implement the AtomicInteger polling pattern as designed in ARCHITECTURE.md. Add a `ProgressState` struct with `std::atomic<int>` in `whisper_jni.cpp`, register the native `progress_callback`, expose `getProgress()` JNI downcall, poll from a child coroutine in `WhisperContext.transcribeData()`, and pipe progress through the existing `UiState.Processing` (changed from `object` to `data class`).

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- Circular determinate indicator (keep existing CircularProgressIndicator, switch from indeterminate to determinate)
- Centered layout -- same position as current spinner, no layout changes needed
- Minimal visual disruption from v1.0 design
- Show percentage number: "Transcribing... 42%"
- Static label text -- "Transcribing..." stays the same throughout inference, only the percentage updates
- No phase-aware labels (no "Preparing audio..." during decode)
- Pre-inference (decode + resample, ~1s): indeterminate spinner (current behavior preserved)
- During inference (12-15s): determinate circular progress bar advancing 0-100%
- On completion: immediate switch to result screen -- no artificial delay at 100%
- State flow: UiState.Processing (indeterminate) -> UiState.Processing(progress) (determinate) -> UiState.Success/NoSpeech/Error

### Claude's Discretion
- JNI callback mechanism (AtomicInteger polling vs upcall -- research decided AtomicInteger polling)
- Exact R8 keep rule for ProgressCallback (placeholder ready from Phase 4)
- How to change UiState.Processing from object to data class (add progress percentage field)
- Progress update frequency / smoothing
- Whether CircularProgressIndicator needs XML changes or just runtime property changes

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| PROG-01 | Determinate progress bar shows 0-100% during Whisper inference | AtomicInteger polling pattern pipes real `progress_callback` values (0-100 int) through JNI to `UiState.Processing(progress)`, rendered by `CircularProgressIndicator.setProgressCompat()` |
| PROG-02 | Progress updates driven by whisper.cpp progress callback via JNI | `whisper_full_params.progress_callback` is set in `whisper_jni.cpp`, writes to `std::atomic<int>`, polled by Kotlin coroutine via `getProgress()` JNI downcall |
</phase_requirements>

## Standard Stack

### Core (already in project -- no new dependencies)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| com.google.android.material:material | 1.12.0 | `CircularProgressIndicator` with `setProgressCompat()` | Already in project; supports indeterminate/determinate mode switching at runtime |
| whisper.cpp | v1.7.4 | `whisper_full_params.progress_callback` native API | Already compiled in project; callback provides 0-100 int progress |
| kotlinx-coroutines-android | 1.9.0 | Child coroutine for progress polling loop | Already in project; `launch` + `delay` for polling |
| C++ std::atomic | C++17 | Thread-safe progress value storage | No library needed; built into C++ standard library |

### No New Dependencies Required
This phase modifies 7 existing files and adds 0 new libraries. The entire implementation uses the existing stack.

## Architecture Patterns

### Data Flow: Progress Callback Pipeline

```
whisper_full() [C++ thread, single-thread executor]
  -> progress_callback fires (0-100 int, ~1-4 times per clip)
    -> ProgressState.progress.store(p, memory_order_relaxed)

WhisperContext.transcribeData() [same executor thread]
  -> child coroutine polls every 200ms:
    -> WhisperLib.getProgress(contextPtr) [JNI downcall]
      -> ProgressState.progress.load()
    -> onProgress(progress) [lambda]

TranscriptionViewModel [Main thread via StateFlow]
  -> _uiState.value = UiState.Processing(progress)

TranscriptionActivity [Main thread, repeatOnLifecycle collector]
  -> when (is UiState.Processing):
    -> if progress < 0: indeterminate mode
    -> if progress >= 0: setProgressCompat(progress, true) + update label text
```

### Pattern 1: AtomicInteger Polling (JNI Progress)

**What:** C++ atomic stores progress, Kotlin polls via JNI downcall.
**When to use:** When you need to communicate a single int value from a native callback to Kotlin without JNI upcall complexity.
**Why chosen over JNI upcall:** Eliminates GlobalRef management (Pitfall 16), thread attachment (Pitfall 15), and R8 stripping of upcall methods (Pitfall 19). The polling overhead is negligible -- one JNI downcall every 200ms vs 15s of inference.

**C++ side:**
```cpp
// whisper_jni.cpp
#include <atomic>

struct ProgressState {
    std::atomic<int> progress{0};
};

// File-scoped -- safe because WhisperContext serializes all access
// through its single-thread executor
static ProgressState g_progress_state;

static void whisper_progress_cb(
    struct whisper_context* /*ctx*/,
    struct whisper_state*   /*state*/,
    int progress,
    void* user_data)
{
    auto* state = static_cast<ProgressState*>(user_data);
    state->progress.store(progress, std::memory_order_relaxed);
}
```

**In fullTranscribe -- register the callback:**
```cpp
// Inside existing fullTranscribe JNI function
g_progress_state.progress.store(0, std::memory_order_relaxed);
params.progress_callback           = whisper_progress_cb;
params.progress_callback_user_data = &g_progress_state;
// ... whisper_full(ctx, params, ...) ...
```

**New JNI export -- getProgress:**
```cpp
JNIEXPORT jint JNICALL
Java_com_simonpena_transchat_WhisperLib_00024Companion_getProgress(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_progress_state.progress.load(std::memory_order_relaxed);
}
```

**Source:** Confirmed from ARCHITECTURE.md research (HIGH confidence). The `progress_callback` typedef verified in `whisper.h` lines 454-455. The callback invocation verified in `whisper.cpp` line 5526-5530.

### Pattern 2: UiState.Processing Data Class Upgrade

**What:** Change `UiState.Processing` from a Kotlin `object` (singleton) to a `data class` with a progress field.
**Convention:** Use `-1` to mean "indeterminate" (pre-inference), and `0..100` for determinate progress.

```kotlin
// Before (v1.0):
sealed class UiState {
    object Processing : UiState()
    // ...
}

// After (v1.1):
sealed class UiState {
    data class Processing(val progress: Int = -1) : UiState()
    // ...
}
```

**Impact on existing code:**
- `_uiState.value = UiState.Processing` becomes `_uiState.value = UiState.Processing()` (default -1 = indeterminate)
- The `when` block in `TranscriptionActivity` already uses `is UiState.Processing` which matches both `object` and `data class` instances
- The `is UiState.Processing` smart cast now provides `state.progress` for the Activity to read

### Pattern 3: Coroutine Polling in WhisperContext

**What:** Launch a child coroutine that polls `getProgress()` while `fullTranscribe` blocks on the executor thread.
**Key constraint:** `transcribeData` runs inside `withContext(scope.coroutineContext)` on the single-thread executor. A child coroutine launched with `launch` inside this `withContext` also dispatches to the executor. Since `fullTranscribe` is blocking the only thread, the child coroutine cannot run.

**Solution:** The polling coroutine must run on a different dispatcher. Use `launch(Dispatchers.Default)` for the poller:

```kotlin
suspend fun transcribeData(
    data: FloatArray,
    onProgress: (Int) -> Unit = {}
): String = withContext(scope.coroutineContext) {
    // Poller runs on Default, NOT the single-thread executor
    val pollerJob = launch(Dispatchers.Default) {
        while (isActive) {
            val p = WhisperLib.getProgress()
            onProgress(p)
            delay(200)
        }
    }
    try {
        WhisperLib.fullTranscribe(ptr, WhisperCpuConfig.preferredThreadCount, data)
    } finally {
        pollerJob.cancel()
    }
    onProgress(100)  // Ensure 100% is reported
    // ... collect segments ...
}
```

**Why `Dispatchers.Default` for the poller:** The `scope.coroutineContext` dispatches to the single-thread executor, which is blocked by `fullTranscribe`. Launching the poller on `Dispatchers.Default` gives it a thread pool to run on. The `getProgress()` JNI call is a simple atomic load -- it does not access the whisper context and does not need serialization.

### Pattern 4: CircularProgressIndicator Mode Switching

**What:** Switch from indeterminate to determinate mode at runtime using Material 3 API.
**No XML changes needed** -- the existing XML with `android:indeterminate="true"` is the correct initial state.

```kotlin
// In TranscriptionActivity, inside the UiState.Processing branch:
is UiState.Processing -> {
    progressGroup.visibility = View.VISIBLE
    // ... hide other groups ...

    val spinner = findViewById<CircularProgressIndicator>(R.id.progress_spinner)
    val statusLabel = findViewById<TextView>(R.id.tv_status)

    if (state.progress < 0) {
        // Pre-inference: keep indeterminate spinner
        spinner.isIndeterminate = true
        statusLabel.text = "Transcribing..."
    } else {
        // During inference: determinate progress
        spinner.isIndeterminate = false
        spinner.setProgressCompat(state.progress, true)
        statusLabel.text = "Transcribing... ${state.progress}%"
    }
}
```

**Critical API detail:** `setProgressCompat(progress, true)` smoothly animates from indeterminate to determinate on the first call. The `true` parameter enables animation. Using `setProgress()` (without compat) skips the smooth transition.

**Source:** [Material Components ProgressIndicator docs](https://github.com/material-components/material-components-android/blob/master/docs/components/ProgressIndicator.md) (HIGH confidence).

### Anti-Patterns to Avoid

- **JNI upcall for simple integer progress:** Adds GlobalRef lifecycle management, thread attachment concerns, and R8 keep rule requirements. The AtomicInteger pattern achieves the same result with zero complexity.
- **Polling on the same single-thread executor:** The executor thread is blocked by `fullTranscribe`. A polling coroutine on that dispatcher will never run. Must use `Dispatchers.Default`.
- **Timer-based simulation instead of real callback:** Violates PROG-01/PROG-02 requirements. Progress must come from whisper.cpp, not estimation.
- **Calling `setIndeterminate(false)` + `setProgress()` separately:** Use `setProgressCompat()` which handles the transition atomically and smoothly.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Indeterminate/determinate toggle | Custom animation logic | `CircularProgressIndicator.setProgressCompat()` + `isIndeterminate` | Material 3 handles the smooth transition animation built-in |
| Thread-safe integer sharing (C++/Kotlin) | JNI upcall with GlobalRef + AttachCurrentThread | `std::atomic<int>` + JNI downcall poll | Eliminates 3 categories of pitfalls (15, 16, 19) |
| Progress smoothing/interpolation | Custom interpolator | Direct 0-100 int from callback | whisper.cpp's progress_callback already provides clean 0-100 values; smoothing adds complexity for negligible visual benefit |

## Common Pitfalls

### Pitfall 1: Poller Coroutine Deadlocks on Single-Thread Executor

**What goes wrong:** Launching the progress polling coroutine inside `withContext(scope.coroutineContext)` without specifying a different dispatcher causes it to be scheduled on the single-thread executor. Since `fullTranscribe` is blocking that thread, the poller never runs.
**Why it happens:** Kotlin structured concurrency dispatches child coroutines to the parent's dispatcher by default.
**How to avoid:** Always specify `launch(Dispatchers.Default)` for the poller coroutine.
**Warning signs:** Progress bar stays at 0% for the entire inference duration, then jumps to result.

### Pitfall 2: `UiState.Processing` object -> data class Breaks Existing Emission

**What goes wrong:** After changing `Processing` from `object` to `data class`, the existing line `_uiState.value = UiState.Processing` no longer compiles because `Processing` is no longer a singleton.
**Why it happens:** Kotlin `object` is accessed by name (`UiState.Processing`), while `data class` requires constructor call (`UiState.Processing()`).
**How to avoid:** Update the initial emission at line 44 of `TranscriptionViewModel.kt` from `UiState.Processing` to `UiState.Processing()` (uses default `progress = -1`).
**Warning signs:** Compilation error -- immediate, not runtime.

### Pitfall 3: Progress Callback Fires Only 1-2 Times for Short Audio

**What goes wrong:** For a 30-second voice message (1 chunk), `progress_callback` fires approximately once at the start (0%) then inference completes. The progress bar jumps from 0% to 100% with no intermediate values.
**Why it happens:** whisper.cpp's progress is computed as `(100*(seek - seek_start))/(seek_end - seek_start)` and `seek` increments by `seek_delta` (typically `100*WHISPER_CHUNK_SIZE = 3000` -- 30 seconds worth of 10ms frames) per loop iteration.
**How to avoid:** This is expected behavior for short audio. The indeterminate spinner runs during pre-inference (~1s), then 0% appears briefly, then jumps toward completion. For 60-120s audio, 2-4 intermediate values appear. Accept this as a feature limitation -- real progress from the callback is better than simulated smoothing.
**Warning signs:** None -- this is correct behavior, not a bug.

### Pitfall 4: `getProgress()` Returns Stale Value After Inference Completes

**What goes wrong:** After `whisper_full()` returns, the polling coroutine may read one more value before being cancelled. This is harmless but the final `onProgress(100)` call after cancellation ensures the UI shows completion.
**Why it happens:** Race between `fullTranscribe` returning and `pollerJob.cancel()` taking effect.
**How to avoid:** Call `onProgress(100)` explicitly after the `finally` block cancels the poller. This guarantees the UI sees 100% before transitioning to the result state.
**Warning signs:** Progress bar shows 75% then jumps to result screen -- cosmetically imperfect but functionally correct.

### Pitfall 5: R8 and the ProgressCallback Keep Rule

**What goes wrong:** Phase 4 left a commented placeholder `-keep class com.simonpena.transchat.ProgressCallback { *; }`. With the AtomicInteger polling approach, there is no `ProgressCallback` interface -- only a new `external fun getProgress()` on `WhisperLib.Companion`.
**Why it happens:** The placeholder assumed a JNI upcall pattern with a named callback interface.
**How to avoid:** Do NOT uncomment the placeholder. Instead, verify that the existing `-keep class com.simonpena.transchat.WhisperLib$Companion { *; }` rule covers the new `getProgress()` native method. It does -- the blanket `{ *; }` keeps all members. No new R8 rule is needed.
**Warning signs:** None if handled correctly. The commented placeholder can stay as documentation.

### Pitfall 6: Static ProgressState Shared Across Multiple Contexts

**What goes wrong:** `g_progress_state` is file-static (global). If the app ever creates multiple `WhisperContext` instances concurrently, they would share the same progress state.
**Why it happens:** The current app only ever creates one context (single-session pattern), but future changes could break this assumption.
**How to avoid:** This is acceptable for TransChat's architecture because `WhisperContext` is created once per session and serialized by the single-thread executor. Document the assumption in a code comment. If multiple contexts are ever needed, the state should be allocated per-context and its pointer passed via `user_data`.
**Warning signs:** Progress values from one transcription leaking into another. Not possible with current single-use pattern.

## Code Examples

### Complete Modified Files Summary

| File | Change Type | What Changes |
|------|------------|--------------|
| `app/src/main/cpp/whisper_jni.cpp` | Modified | Add `ProgressState` struct, register `progress_callback` in `fullTranscribe`, add `getProgress` JNI export |
| `app/src/main/java/.../WhisperLib.kt` | Modified | Add `external fun getProgress(): Int` to Companion; modify `transcribeData()` to accept `onProgress` lambda and launch poller |
| `app/src/main/java/.../WhisperEngine.kt` | Modified | Add `onProgress: (Int) -> Unit = {}` parameter to `transcribe()`, forward to context |
| `app/src/main/java/.../TranscriptionViewModel.kt` | Modified | Change `UiState.Processing` from `object` to `data class(val progress: Int = -1)`; pass progress lambda to engine; emit `UiState.Processing(progress)` |
| `app/src/main/java/.../TranscriptionActivity.kt` | Modified | In `UiState.Processing` branch: read `state.progress`, toggle `isIndeterminate`/`setProgressCompat()`, update status label text |
| `app/src/main/res/layout/activity_transcription.xml` | Modified | Add `android:id` to status label TextView (currently has no id, needed for programmatic text updates) |
| `app/proguard-rules.pro` | No change needed | Existing `WhisperLib$Companion { *; }` rule covers `getProgress()`. Commented placeholder stays as documentation. |

### XML Change: Add ID to Status Label

The current `<TextView>` showing "Transcribing..." has no `android:id`. It needs one for programmatic text updates:

```xml
<!-- Before: -->
<TextView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="Transcribing..."
    android:textAppearance="?attr/textAppearanceBody1" />

<!-- After: -->
<TextView
    android:id="@+id/tv_status"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="Transcribing..."
    android:textAppearance="?attr/textAppearanceBody1" />
```

### getProgress JNI Function Signature

The new `getProgress()` does NOT take a `contextPtr` parameter. It reads from the file-static `g_progress_state`, which is written by the callback during `fullTranscribe`. This is safe because:
1. Only one `fullTranscribe` runs at a time (single-thread executor)
2. The `getProgress()` call from the poller is a relaxed atomic load -- no locking needed

```kotlin
// In WhisperLib.Companion:
external fun getProgress(): Int  // No contextPtr -- reads static atomic
```

```cpp
// In whisper_jni.cpp:
JNIEXPORT jint JNICALL
Java_com_simonpena_transchat_WhisperLib_00024Companion_getProgress(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_progress_state.progress.load(std::memory_order_relaxed);
}
```

### ViewModel Progress Lambda Wiring

```kotlin
// In TranscriptionViewModel.startTranscription(), replace step 4:

// Before:
val raw = engine.transcribe(pcm)

// After:
val raw = engine.transcribe(pcm) { progress ->
    _uiState.value = UiState.Processing(progress)
}
```

### CircularProgressIndicator Max Value

`CircularProgressIndicator` inherits from `android.widget.ProgressBar`. The default `android:max` is `100`, which matches whisper.cpp's 0-100 progress range. No need to set `android:max` in XML or code.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| JNI upcall with GlobalRef + AttachCurrentThread | AtomicInteger polling via JNI downcall | Decided during v1.1 research | Eliminates 3 pitfall categories, simpler code |
| `UiState.Processing` as `object` | `data class Processing(val progress: Int = -1)` | This phase | Carries progress data through existing StateFlow pipeline |
| `CircularProgressIndicator` indeterminate only | Same widget, runtime switch to determinate | This phase | No new widget, minimal visual change |

## Open Questions

1. **Progress granularity for very short messages (< 10s)**
   - What we know: whisper.cpp chunks audio into 30-second windows. For audio shorter than 30s, there is only 1 chunk, so the progress callback fires at 0% at the start of the loop, then inference completes. The `if (seek + 100 >= seek_end) break` at line 5534 exits the loop before a second progress update.
   - What's unclear: Whether the UI experience of "indeterminate -> 0% -> result" (skipping intermediate values) is acceptable for very short audio.
   - Recommendation: Accept this. For 10s audio, inference takes ~5s -- the indeterminate phase is ~1s and then inference is fast enough that a progress bar is barely visible anyway. The user decision explicitly chose "real callback values, not simulation."

2. **Polling interval optimization**
   - What we know: 200ms polling gives 5 polls/second. With 1-4 callback firings per 12-15s inference, most polls return the same value.
   - What's unclear: Whether 200ms is optimal or if 100ms or 300ms is better.
   - Recommendation: Use 200ms. It gives good responsiveness (within 200ms of a callback firing, the UI updates) without noticeable overhead. Can tune later if needed.

## Sources

### Primary (HIGH confidence)
- `whisper.h` (v1.7.4) at `/workspace/transchat-app/app/src/main/cpp/whisper.cpp/include/whisper.h` lines 454-455 -- `whisper_progress_callback` typedef confirmed: `void (*)(whisper_context*, whisper_state*, int progress, void* user_data)`
- `whisper.cpp` source at `/workspace/transchat-app/app/src/main/cpp/whisper.cpp/src/whisper.cpp` lines 5526-5530 -- callback invocation: `progress_cur = (100*(seek - seek_start))/(seek_end - seek_start)`
- `whisper.h` line 36 -- `#define WHISPER_CHUNK_SIZE 30` (30-second chunks)
- `whisper_jni.cpp` at `/workspace/transchat-app/app/src/main/cpp/whisper_jni.cpp` -- current JNI bridge, 6 exports, no callbacks set
- `WhisperLib.kt` at `/workspace/transchat-app/app/src/main/java/com/simonpena/transchat/WhisperLib.kt` -- current JNI Kotlin binding, single-thread executor
- `TranscriptionViewModel.kt` at `/workspace/transchat-app/app/src/main/java/com/simonpena/transchat/TranscriptionViewModel.kt` -- current `UiState.Processing` as `object`
- `proguard-rules.pro` at `/workspace/transchat-app/app/proguard-rules.pro` -- Phase 4 rules with commented ProgressCallback placeholder
- `activity_transcription.xml` at `/workspace/transchat-app/app/src/main/res/layout/activity_transcription.xml` -- current layout with CircularProgressIndicator
- v1.1 milestone research: `/workspace/transchat-app/.planning/research/ARCHITECTURE.md` -- AtomicInteger pattern design
- v1.1 milestone research: `/workspace/transchat-app/.planning/research/PITFALLS.md` -- Pitfalls 15, 16, 19, 20

### Secondary (MEDIUM confidence)
- [Material Components ProgressIndicator docs](https://github.com/material-components/material-components-android/blob/master/docs/components/ProgressIndicator.md) -- `setProgressCompat()` smooth transition API
- [CircularProgressIndicator API reference](https://developer.android.com/reference/com/google/android/material/progressindicator/CircularProgressIndicator) -- `isIndeterminate`, `setProgressCompat(int, boolean)` confirmed

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- no new dependencies; all verified in existing codebase
- Architecture: HIGH -- AtomicInteger polling pattern verified against whisper.h API, Kotlin coroutine model, and Material 3 widget API
- Pitfalls: HIGH -- all 6 identified pitfalls are verified against codebase inspection and v1.1 research

**Research date:** 2026-02-27
**Valid until:** 2026-03-27 (stable -- no dependency changes expected)
