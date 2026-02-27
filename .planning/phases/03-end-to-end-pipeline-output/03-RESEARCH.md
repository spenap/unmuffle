# Phase 3: End-to-End Pipeline and Output - Research

**Researched:** 2026-02-27
**Domain:** Android pipeline wiring (ViewModel, coroutines, clipboard, share sheet), Whisper post-processing
**Confidence:** HIGH

## Summary

Phase 3 wires the existing decode pipeline (Phase 2) into the Whisper inference engine (Phase 1) and presents results to the user via a progress screen and result screen. The codebase already has all building blocks: `ShareReceiverActivity` copies audio and decodes to `FloatArray`, `WhisperEngine.transcribe()` accepts `FloatArray` and returns a transcript string. The remaining work is a `TranscriptionViewModel` to manage the inference lifecycle, two new screens (progress + result), clipboard/share integration, hallucination detection, and cancellation handling.

The existing project uses `AndroidViewModel` (see `DownloadViewModel`), `StateFlow` for state management, XML layouts (not Compose), and `lifecycleScope` for coroutine launching. All dependencies needed are already in the version catalog (`lifecycle-viewmodel-ktx 2.8.7`, `activity-ktx 1.9.3`, `material 1.12.0`, `coroutines-android 1.9.0`). No new library dependencies are required.

**Primary recommendation:** Build a single `TranscriptionActivity` with a `TranscriptionViewModel` managing a `StateFlow<UiState>` sealed class. The activity swaps between a progress layout and a result layout based on state transitions. Keep the architecture minimal -- no Navigation component, no fragments, just `setContentView` swaps or view visibility toggling driven by the ViewModel state.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- Do NOT auto-copy to clipboard -- privacy concern (clipboard readable by other apps on older Android)
- Show transcript on a result screen with explicit Copy and Share buttons
- User controls when text leaves the app
- Plain text, no formatting or prefixes -- clean for pasting into any context
- Always show the full transcript regardless of length (no truncation)
- Simple screen: transcript text displayed prominently, Copy button, Share button
- Copy button copies plain text to clipboard
- Share button opens Android share sheet with the transcript text
- For no-speech results: show a friendly "No speech detected" message, hide Copy/Share buttons
- Show a progress screen with spinner and "Transcribing..." during the 15-60s inference window
- Progress screen transitions to result screen when inference completes
- Back button during inference cancels and returns to previous app
- If user navigates away (backgrounds the app), cancel inference and clean up -- user can re-share
- When Whisper returns markers like `[BLANK_AUDIO]`: show "No speech detected" on result screen instead of raw marker
- Hallucination detection approach: Claude's discretion
- Short clips (under 2s): process normally
- Auto-detect language (`language = "auto"`) -- already configured in JNI params
- No language indicator in output
- Wrong language / garbled output: show as-is, no retry mechanism for MVP

### Claude's Discretion
- Progress screen visual design (spinner style, layout)
- Result screen layout details (spacing, typography, button style)
- Hallucination detection implementation approach
- How to detect and handle Whisper non-speech markers (pattern matching, token analysis, etc.)
- ViewModel architecture for managing inference lifecycle

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| TRANS-02 | Whisper small multilingual model transcribes audio on-device via whisper.cpp JNI | WhisperEngine.transcribe() already functional from Phase 1; this phase wires it into the share pipeline via TranscriptionViewModel |
| TRANS-03 | Transcription runs off main thread (no ANR) | viewModelScope launches on Dispatchers.Default; WhisperContext uses newSingleThreadExecutor internally; all inference work is off-main-thread by construction |
| TRANS-04 | Anti-hallucination params set (no_timestamps=true, no_speech_thold=0.6) | STATE.md documents that no_timestamps=true was reverted (caused speech filtering); current params use default no_speech_thold + timestamp-based segmentation; post-processing hallucination detection handles the rest |
| OUT-01 | Transcript automatically copied to clipboard on completion | CONTEXT.md overrides this: do NOT auto-copy; provide explicit Copy button on result screen instead |
| OUT-02 | Toast notification displays transcript text | CONTEXT.md refines this: result screen with transcript text, Copy/Share buttons replaces simple toast |
</phase_requirements>

## Standard Stack

### Core (Already in Project)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| lifecycle-viewmodel-ktx | 2.8.7 | TranscriptionViewModel with viewModelScope | Auto-cancels coroutines on ViewModel clear |
| kotlinx-coroutines-android | 1.9.0 | Dispatchers.Default for inference, Dispatchers.Main for UI | Standard Android coroutine runtime |
| material | 1.12.0 | MaterialButton, ProgressIndicator, layout styling | Already used in existing layouts |
| activity-ktx | 1.9.3 | viewModels() delegate for ViewModel creation | Type-safe ViewModel access in Activity |
| appcompat | 1.7.0 | AppCompatActivity base class | Already used everywhere |

### Supporting (No new dependencies)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| android.content.ClipboardManager | Platform API | Copy transcript to clipboard | On Copy button press |
| android.content.Intent.ACTION_SEND | Platform API | Share transcript via Android share sheet | On Share button press |
| android.widget.Toast | Platform API | Feedback after copy ("Copied to clipboard") | Android 12L and below only |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| XML layouts | Jetpack Compose | Project consistently uses XML layouts; switching mid-project adds complexity and a large dependency |
| Single Activity with view swapping | Two Activities (ProgressActivity + ResultActivity) | Two activities complicates ViewModel sharing; single activity with state-driven layout changes is simpler |
| Navigation Component | Manual Activity/Intent navigation | App has 5 activities, no complex nav graph; Navigation Component is overkill |

**Installation:** No new dependencies needed. Everything is already in `libs.versions.toml` and `build.gradle.kts`.

## Architecture Patterns

### Recommended Project Structure
```
com/simonpena/transchat/
    ShareReceiverActivity.kt    # Modified: launches TranscriptionActivity with file path
    TranscriptionActivity.kt    # NEW: progress screen + result screen
    TranscriptionViewModel.kt   # NEW: manages inference lifecycle via StateFlow
    HallucinationFilter.kt      # NEW: post-processing to clean Whisper output
    ... (existing files unchanged)
```

### Pattern 1: Single Activity with State-Driven UI
**What:** TranscriptionActivity observes `TranscriptionViewModel.uiState: StateFlow<UiState>` and switches between progress and result layouts based on the current state.
**When to use:** When a flow has 2-3 visual states and no complex navigation. Avoids ViewModel sharing between activities.
**Example:**
```kotlin
// TranscriptionViewModel.kt
sealed class UiState {
    object Processing : UiState()
    data class Success(val transcript: String) : UiState()
    data class NoSpeech(val message: String) : UiState()
    data class Error(val message: String) : UiState()
}

class TranscriptionViewModel(app: Application) : AndroidViewModel(app) {
    private val _uiState = MutableStateFlow<UiState>(UiState.Processing)
    val uiState: StateFlow<UiState> = _uiState

    private val engine = WhisperEngine()
    private var inferenceJob: Job? = null

    fun startTranscription(audioFilePath: String) {
        inferenceJob = viewModelScope.launch {
            // decode + transcribe on Dispatchers.Default
            val pcm = withContext(Dispatchers.Default) { decodeToWhisperPcm(audioFilePath) }
            if (pcm == null) {
                _uiState.value = UiState.Error("Could not decode audio")
                return@launch
            }
            val raw = engine.transcribe(pcm)
            val filtered = HallucinationFilter.filter(raw)
            _uiState.value = if (filtered == null) {
                UiState.NoSpeech("No speech detected")
            } else {
                UiState.Success(filtered)
            }
        }
    }

    fun cancelInference() {
        inferenceJob?.cancel()
        // Note: whisper.cpp JNI call is blocking and not cooperatively cancellable
        // The coroutine will be marked cancelled but the native call completes
        // See "Cancellation Limitations" in Common Pitfalls
    }
}

// TranscriptionActivity.kt
class TranscriptionActivity : AppCompatActivity() {
    private val viewModel: TranscriptionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcription)
        // Collect state and toggle visibility of progress/result views
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> updateUi(state) }
            }
        }
        val audioFilePath = intent.getStringExtra(EXTRA_AUDIO_PATH) ?: run { finish(); return }
        if (savedInstanceState == null) viewModel.startTranscription(audioFilePath)
    }
}
```

### Pattern 2: ShareReceiverActivity as Coordinator
**What:** ShareReceiverActivity handles the synchronous copy (as it does now), then launches TranscriptionActivity via Intent with the cached file path. It no longer does decoding itself -- that moves into the ViewModel.
**When to use:** Always. The existing ShareReceiverActivity is the entry point and must remain transparent (no UI). It hands off to TranscriptionActivity for all visual work.
**Example:**
```kotlin
// In ShareReceiverActivity.onCreate(), replace the decode coroutine with:
is AudioCopier.CopyResult.Success -> {
    val intent = Intent(this, TranscriptionActivity::class.java)
    intent.putExtra(TranscriptionActivity.EXTRA_AUDIO_PATH, result.file.absolutePath)
    startActivity(intent)
    finish()
}
```

### Pattern 3: Clipboard with Conditional Toast
**What:** Copy to clipboard via ClipboardManager.setPrimaryClip(). Show a toast only on Android 12L (API 32) and below. Android 13+ shows its own system clipboard notification automatically.
**When to use:** Every Copy button press.
**Example:**
```kotlin
fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("transcript", text)
    clipboard.setPrimaryClip(clip)
    // Android 13+ shows its own visual confirmation; avoid duplicate
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
```

### Pattern 4: Android Share Sheet for Text
**What:** Launch the system share sheet with ACTION_SEND and EXTRA_TEXT containing the plain transcript.
**When to use:** Every Share button press.
**Example:**
```kotlin
fun shareText(context: Context, text: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(sendIntent, null))
}
```

### Anti-Patterns to Avoid
- **Auto-copy to clipboard:** Locked decision -- user must explicitly press Copy. Clipboard is readable by other apps on older Android versions.
- **Sharing ViewModel between Activities:** Android ViewModels are scoped to a single ViewModelStoreOwner. Do not try to share between ShareReceiverActivity and TranscriptionActivity; pass data via Intent extras.
- **Running inference on Dispatchers.IO:** WhisperEngine already uses `Dispatchers.Default` internally and `WhisperContext` uses a `newSingleThreadExecutor`. Dispatchers.IO is for blocking I/O, not CPU-intensive work.
- **Using LiveData instead of StateFlow:** The project already uses StateFlow in DownloadViewModel. Stay consistent. StateFlow also works better with coroutines.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Clipboard access | Custom clipboard wrapper | `ClipboardManager.setPrimaryClip(ClipData.newPlainText(...))` | 3-line platform API, zero complexity |
| Share sheet | Custom share UI | `Intent.createChooser(Intent(ACTION_SEND), null)` | Platform handles app list, previews, accessibility |
| ViewModel scoping | Manual lifecycle tracking | `by viewModels()` delegate from activity-ktx | Handles configuration changes, automatic cleanup |
| State observation | Manual observer pattern | `StateFlow` + `repeatOnLifecycle` | Lifecycle-aware, no leaks, no manual unsubscribe |
| Progress indicator | Custom animation | `CircularProgressIndicator` from Material Components | Follows Material Design, handles theming automatically |

**Key insight:** Every component in this phase uses standard Android platform APIs or libraries already in the project. There is zero reason to add dependencies.

## Common Pitfalls

### Pitfall 1: Cancellation Limitations with JNI
**What goes wrong:** The user presses Back or backgrounds the app during inference. The coroutine is cancelled but `WhisperLib.fullTranscribe()` is a blocking JNI call that cannot be interrupted from Kotlin.
**Why it happens:** whisper.cpp supports `abort_callback` in `whisper_full_params`, but the current JNI bridge (`whisper_jni.cpp`) does not expose it. The native call runs to completion regardless of coroutine cancellation.
**How to avoid:** Accept this limitation for MVP. When the coroutine is cancelled:
1. The `withContext(Dispatchers.Default)` block will throw `CancellationException` at the next suspension point AFTER the JNI call returns.
2. The ViewModel's `onCleared()` should call `engine.release()` to free native memory.
3. The activity finishes immediately when Back is pressed -- the ViewModel coroutine will complete in the background and be discarded.
4. For future improvement: add `abort_callback` support to the JNI bridge (requires C++ changes to `whisper_jni.cpp`).
**Warning signs:** Pressing Back during inference causes a 10-15s delay before the process actually completes cleanup. This is acceptable for MVP.

### Pitfall 2: WhisperEngine Lifecycle and Model Loading
**What goes wrong:** TranscriptionViewModel creates a new WhisperEngine but the model is not loaded, causing `IllegalStateException` on transcribe.
**Why it happens:** The 190MB model must be loaded before transcription. Loading takes ~136ms (benchmarked) but it still needs to happen.
**How to avoid:** The ViewModel must call `engine.loadModel(modelPath)` before `engine.transcribe()`. The model path comes from `ModelManager.modelFile(context).absolutePath`. Loading is fast (136ms) so doing it per-transcription is acceptable. An Application-scoped singleton could avoid reloading, but for MVP the per-ViewModel approach is simpler and matches the existing SmokeTestActivity pattern.
**Warning signs:** `IllegalStateException("Model not loaded")` in logs.

### Pitfall 3: TRANS-04 Anti-Hallucination Parameter Conflict
**What goes wrong:** Setting `no_timestamps=true` or custom `no_speech_thold` causes the q5_1 quantized model to filter real speech.
**Why it happens:** Documented in STATE.md -- `no_timestamps=true` was reverted because it caused `is_no_speech` to trigger on actual speech. The q5_1 quantized model behaves differently from the full model with these parameters.
**How to avoid:** Do NOT change the whisper params in `whisper_jni.cpp`. The current defaults work correctly. TRANS-04 is addressed via post-processing: the `HallucinationFilter` detects known non-speech markers (`[BLANK_AUDIO]`, `[MUSIC]`, etc.) and common hallucination patterns (repeated text) AFTER inference, not via whisper params.
**Warning signs:** Previously-working Spanish voice messages returning empty strings.

### Pitfall 4: Android 13 Duplicate Clipboard Toast
**What goes wrong:** On Android 13+, calling `Toast.makeText()` after `setPrimaryClip()` results in TWO visual notifications: the system's automatic clipboard overlay plus the app's toast.
**Why it happens:** Android 13 (API 33) added automatic visual feedback when apps write to the clipboard.
**How to avoid:** Conditionally show toast only on API 32 and below: `if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2)`.
**Warning signs:** Users on Android 13+ seeing two overlapping notifications.

### Pitfall 5: Activity Destroyed During Inference
**What goes wrong:** Configuration change (rotation) or process death kills the Activity while inference is running in the ViewModel.
**Why it happens:** The ViewModel survives configuration changes, but if the process is killed, everything is lost.
**How to avoid:** The ViewModel survives rotation automatically. For process death: the user can simply re-share. No need to persist intermediate state. Ensure the ViewModel properly checks `isActive` after the inference call returns before updating state.
**Warning signs:** Blank result screen after rotation.

### Pitfall 6: Cached Audio File Cleanup
**What goes wrong:** Cached audio files in `cacheDir/audio/` accumulate and waste storage.
**Why it happens:** ShareReceiverActivity creates cache files, and if it finishes before TranscriptionActivity reads the file, the file may be deleted prematurely by `onDestroy`. Conversely, if cleanup is only in ShareReceiverActivity, the file may remain if TranscriptionActivity is the one using it.
**How to avoid:** ShareReceiverActivity should NOT delete the cached file in its `onDestroy` if it successfully launched TranscriptionActivity. Instead, TranscriptionViewModel should delete the cached file in `onCleared()`. Also delete verification WAVs since they were a Phase 2 debugging aid.
**Warning signs:** "File not found" errors in TranscriptionViewModel, or ever-growing cache directory.

## Code Examples

### Complete Hallucination Filter
```kotlin
// Source: whisper.cpp community patterns + STATE.md anti-hallucination notes
object HallucinationFilter {
    // Known non-speech markers that whisper.cpp outputs for silence/noise
    private val NON_SPEECH_MARKERS = listOf(
        "[BLANK_AUDIO]",
        "[MUSIC]",
        "[SILENCE]",
        "(BEEPING)",
        "(MUSIC)",
        "(music)",
    )

    // Regex for markers in square brackets or parentheses that contain no speech
    private val MARKER_REGEX = Regex("""\[[\w\s_]+\]|\([\w\s_]+\)""")

    /**
     * Filter whisper output, returning null if no meaningful speech was detected.
     *
     * @param raw Raw transcript from WhisperEngine.transcribe()
     * @return Cleaned transcript text, or null if input is non-speech
     */
    fun filter(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        // Check for known non-speech markers
        if (NON_SPEECH_MARKERS.any { trimmed.contains(it, ignoreCase = true) }) {
            // If the entire output is just markers, it's non-speech
            val withoutMarkers = MARKER_REGEX.replace(trimmed, "").trim()
            if (withoutMarkers.isBlank()) return null
        }

        // Check for repetitive hallucination: same short phrase repeated 3+ times
        val words = trimmed.split("\\s+".toRegex())
        if (words.size >= 6) {
            val uniqueWords = words.toSet()
            // If fewer than 1/3 unique words, likely hallucination
            if (uniqueWords.size.toFloat() / words.size < 0.33f) return null
        }

        return trimmed
    }
}
```

### TranscriptionActivity Layout Pattern
```xml
<!-- activity_transcription.xml: single layout with progress/result groups -->
<FrameLayout ...>
    <!-- Progress group: visible during inference -->
    <LinearLayout android:id="@+id/progress_group" ...>
        <com.google.android.material.progressindicator.CircularProgressIndicator
            android:id="@+id/progress_spinner"
            android:indeterminate="true"
            ... />
        <TextView
            android:text="Transcribing..."
            android:textAppearance="?attr/textAppearanceBody1" />
    </LinearLayout>

    <!-- Result group: visible after inference -->
    <LinearLayout android:id="@+id/result_group" android:visibility="gone" ...>
        <ScrollView ...>
            <TextView android:id="@+id/tv_transcript" ... />
        </ScrollView>
        <LinearLayout android:orientation="horizontal" ...>
            <Button android:id="@+id/btn_copy" android:text="Copy" />
            <Button android:id="@+id/btn_share" android:text="Share" />
        </LinearLayout>
    </LinearLayout>

    <!-- No-speech group: visible when no speech detected -->
    <LinearLayout android:id="@+id/no_speech_group" android:visibility="gone" ...>
        <TextView android:text="No speech detected" ... />
    </LinearLayout>
</FrameLayout>
```

### State Collection with Lifecycle Awareness
```kotlin
// Source: Android official docs - repeatOnLifecycle pattern
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state ->
            when (state) {
                is UiState.Processing -> showProgressGroup()
                is UiState.Success -> showResultGroup(state.transcript)
                is UiState.NoSpeech -> showNoSpeechGroup(state.message)
                is UiState.Error -> showErrorAndFinish(state.message)
            }
        }
    }
}
```

### Back Press Handling for Cancellation
```kotlin
// In TranscriptionActivity
onBackPressedDispatcher.addCallback(this) {
    viewModel.cancelInference()
    finish()
}
```

### Manifest Registration for TranscriptionActivity
```xml
<activity
    android:name=".TranscriptionActivity"
    android:exported="false"
    android:theme="@style/Theme.TransChat" />
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Toast for clipboard feedback | System clipboard notification (Android 13+) | Android 13 (2022) | Must conditionally suppress app toast on API 33+ |
| LiveData for UI state | StateFlow + repeatOnLifecycle | Lifecycle 2.4+ (2021) | Project already uses StateFlow; stay consistent |
| onBackPressed() override | onBackPressedDispatcher.addCallback() | AndroidX Activity 1.6+ (2022) | onBackPressed() is deprecated; use callback-based API |
| whisper.cpp no_timestamps=true | Default timestamp-based segmentation | Project-specific (Phase 1) | no_timestamps=true was reverted -- do NOT re-enable |

**Deprecated/outdated:**
- `onBackPressed()`: Deprecated in favor of `onBackPressedDispatcher.addCallback()`. The project has `activity-ktx:1.9.3` which fully supports the new API.
- `getParcelableExtra(String)`: Deprecated in API 33. Already suppressed with `@Suppress("DEPRECATION")` in ShareReceiverActivity. Fine for minSdk 26.
- LiveData: Not deprecated but StateFlow is the project convention. Do not introduce LiveData.

## Open Questions

1. **WhisperEngine singleton vs per-ViewModel instance**
   - What we know: Model loading takes 136ms, so reloading per transcription is fast. The SmokeTestActivity creates its own WhisperEngine instance. But if the user shares multiple messages quickly, each would reload the model.
   - What's unclear: Whether an Application-scoped WhisperEngine singleton would be worthwhile for MVP.
   - Recommendation: Use per-ViewModel instance for MVP. It's simpler, matches existing patterns, and 136ms is negligible compared to 12-15s inference. Singleton optimization can be added later if needed.

2. **Verification WAV files from Phase 2**
   - What we know: ShareReceiverActivity.decodeToWhisperPcm() writes two verification WAV files per decode. These were debugging aids for Phase 2.
   - What's unclear: Should they be removed in Phase 3, or kept for ongoing debugging?
   - Recommendation: Remove verification WAV writing from the production path. They consume storage and are no longer needed for device verification. The decode logic itself should move into the ViewModel.

3. **JNI abort_callback for true cancellation**
   - What we know: whisper.cpp supports `abort_callback` in `whisper_full_params`. The current JNI bridge does not expose it. Adding it requires C++ changes to `whisper_jni.cpp`.
   - What's unclear: Whether the 10-15s post-cancel delay is acceptable for MVP UX.
   - Recommendation: Skip for MVP. The inference thread completes and result is discarded. The user sees immediate UI response (activity finishes). The background thread completing silently is harmless. Add abort_callback in a future optimization pass.

## Sources

### Primary (HIGH confidence)
- Codebase analysis: `ShareReceiverActivity.kt`, `WhisperEngine.kt`, `WhisperLib.kt`, `whisper_jni.cpp`, `AudioDecoder.kt`, `PcmResampler.kt`, `DownloadViewModel.kt` -- direct source reading
- `STATE.md` -- all accumulated project decisions, anti-hallucination notes, inference benchmarks
- `CONTEXT.md` -- locked user decisions for Phase 3
- `whisper.h` -- confirmed `abort_callback` field in `whisper_full_params` struct
- `whisper_jni.cpp` -- confirmed abort_callback is NOT exposed in JNI bridge
- [Android official: Copy and paste](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste) -- ClipboardManager API, Android 13 behavior
- [Android official: Send simple data](https://developer.android.com/training/sharing/send) -- Intent.ACTION_SEND share sheet pattern
- [Android official: StateFlow and SharedFlow](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow) -- StateFlow collection patterns
- [Android official: Coroutines with lifecycle](https://developer.android.com/topic/libraries/architecture/coroutines) -- viewModelScope, repeatOnLifecycle

### Secondary (MEDIUM confidence)
- [whisper.cpp issue #1724](https://github.com/ggml-org/whisper.cpp/issues/1724) -- hallucination on silence behavior
- [whisper.cpp discussion #1258](https://github.com/ggml-org/whisper.cpp/discussions/1258) -- suppress_blank/suppress_non_speech behavior
- [whisper.cpp discussion #913](https://github.com/ggml-org/whisper.cpp/discussions/913) -- abort mechanism discussion
- [Android 13 behavior changes](https://developer.android.com/about/versions/13/behavior-changes-all) -- automatic clipboard visual confirmation

### Tertiary (LOW confidence)
- [Whisper hallucination community thread](https://community.openai.com/t/whisper-hallucination-how-to-recognize-and-solve/218307) -- common hallucination patterns ("[MUSIC]", repeated text, "thank you for watching"). Used to inform HallucinationFilter markers but patterns may vary with q5_1 quantized model.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- all dependencies already in project, APIs are stable platform-level
- Architecture: HIGH -- ViewModel + StateFlow + XML layout matches existing project patterns exactly
- Pitfalls: HIGH -- most pitfalls derive from direct codebase analysis (JNI bridge limitations, TRANS-04 history)
- Hallucination filter: MEDIUM -- non-speech markers are documented in whisper.cpp issues but the exact set for q5_1 quantized model may differ; the SmokeTestActivity confirmed "[BLANK_AUDIO]" and "(BEEPING)" on this model

**Research date:** 2026-02-27
**Valid until:** 2026-03-27 (stable -- all APIs and patterns are mature)
