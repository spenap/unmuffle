# Domain Pitfalls: Android On-Device ML Transcription

**Domain:** Android audio transcription app (share target + local Whisper inference)
**Researched:** 2026-02-27
**Confidence:** HIGH (most pitfalls verified against GitHub issues, official Android docs, ONNX Runtime issue tracker)

---

## Critical Pitfalls

Mistakes that cause rewrites, crashes, or fundamental UX failure if not addressed early.

---

### Pitfall 1: Model Loaded as Byte Array Causes OOM Despite Available RAM

**What goes wrong:** When loading a 244MB Whisper small model via ONNX Runtime (or similar JNI-backed runtimes), passing the model as a byte array causes the JVM to hold up to 3 copies simultaneously: one in Java heap, one in JNI layer, one during session construction. This exhausts the Java heap limit (typically capped at 256-512MB per app regardless of device RAM) and throws `OutOfMemoryError: Failed to allocate a [N] byte allocation` even on a device with multiple gigabytes of free native memory.

**Why it happens:** The JNI interface does not share memory between the Java layer and native layer. Each boundary crossing requires a copy. The Java heap limit is a per-app VM limit, not total device RAM. A 244MB model requires ~730MB of Java heap peak allocation, which almost no Android app is allowed.

**Consequences:** App crash on model load. Zero graceful degradation. Affects all model sizes above roughly 100MB when loaded via byte arrays.

**Prevention:** Load the model from a file path on disk, never as a byte array. ONNX Runtime's Java API accepts file paths and does the load entirely in native memory, bypassing Java heap. Store the model in `filesDir` (not `assets/` as a stream) and pass the absolute path string.

```kotlin
// WRONG - triggers OOM
val modelBytes = assets.open("whisper-small.onnx").readBytes()
val session = OrtEnvironment.getEnvironment().createSession(modelBytes)

// CORRECT - native load, no Java heap pressure
val modelFile = File(filesDir, "whisper-small.onnx")
val session = OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath)
```

**Warning signs:** `OutOfMemoryError` in logcat during app startup or model initialization. Crash on device with 4GB+ RAM. Works with tiny model, fails with small model.

**Phase to address:** Phase 1 (model loading / core inference setup). Get file-path loading right before any other work.

**Source:** [ONNX Runtime Issue #19514](https://github.com/microsoft/onnxruntime/issues/19514) — confirmed root cause and solution.

---

### Pitfall 2: Debug Build Inference Is 2-10x Slower Than Release

**What goes wrong:** Running whisper.cpp (or any NDK-based inference) in a debug build causes profiling overhead and disables NEON/FMA instruction optimizations. A 3-second voice message that takes 3 seconds in release can take 30+ seconds in debug. Developers conclude the chosen model or approach is too slow and either switch to a worse model or abandon the approach.

**Why it happens:** NDK debug builds omit `-O2`/`-O3` compiler optimizations and include additional runtime checks. For compute-bound SIMD code (whisper.cpp does heavy vectorized math), this gap is enormous. Additionally, R8 code shrinking disabled (`minifyEnabled = false`) adds another 50% overhead even on top of a release NDK build.

**Consequences:** Developers abandon a viable model because they only tested in debug mode. False performance conclusions shape the entire product architecture.

**Prevention:** Always run inference benchmarks in a release build. Create a dedicated `benchmarkRelease` build variant if needed. Never draw performance conclusions from debug builds for NDK-heavy code.

```kotlin
// build.gradle - ensure release builds have correct flags
buildTypes {
    release {
        minifyEnabled true
        shrinkResources true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
}
```

For whisper.cpp CMakeLists.txt, explicitly set: `set(CMAKE_BUILD_TYPE Release)` when building for benchmarking.

**Warning signs:** Inference taking 10-30x longer than documented benchmarks. Performance that seems implausible for the hardware. Reports of whisper.cpp being "unusably slow" on modern devices.

**Phase to address:** Phase 1. Establish a release-mode benchmark baseline before any optimization work.

**Source:** [whisper.cpp Issue #1022](https://github.com/ggml-org/whisper.cpp/issues/1022) — root cause confirmed as R8 minification + debug build. [whisper.cpp Issue #1070](https://github.com/ggml-org/whisper.cpp/issues/1070) — community confirmation of debug vs release gap.

---

### Pitfall 3: URI Permission Revoked Before Inference Completes

**What goes wrong:** Android grants temporary URI read permission to the share-target Activity for the duration of the Activity's lifecycle. If inference is offloaded to a coroutine, WorkManager, or Service and the originating Activity is finished (or destroyed) before inference reads the audio bytes, `SecurityException: Permission Denial` is thrown when the background task calls `contentResolver.openInputStream(uri)`.

**Why it happens:** `FLAG_GRANT_READ_URI_PERMISSION` on `ACTION_SEND` intents grants permission scoped to the receiving Activity's lifetime. The permission is automatically revoked when the Activity finishes. Background workers and services do not inherit this permission.

**Consequences:** Transcription fails silently or crashes. Failure is intermittent — it works when the user keeps the app foregrounded but fails when Android kills the Activity. WhatsApp's content provider URIs are especially short-lived.

**Prevention:** Copy the audio bytes to a private app file immediately on Activity receipt — before launching any background work. Do the `contentResolver.openInputStream(uri)` copy synchronously in `onCreate` or `onStart`, then pass the private file path to the background worker.

```kotlin
// In the share-target Activity — copy first, transcribe second
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val audioUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return

    // Copy while URI permission is still valid
    val localFile = copyToPrivateStorage(audioUri)

    // Now it is safe to finish the activity or go to background
    TranscriptionService.start(this, localFile.absolutePath)
}

private fun copyToPrivateStorage(uri: Uri): File {
    val dest = File(cacheDir, "audio_${System.currentTimeMillis()}.tmp")
    contentResolver.openInputStream(uri)!!.use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    }
    return dest
}
```

**Warning signs:** `SecurityException` in logcat with `Permission Denial: opening provider`. Works reliably in testing (where tester keeps app open) but fails in production (where users switch away). Fails specifically for WhatsApp/Telegram URIs, not local file URIs.

**Phase to address:** Phase 1 (share intent handling). This must be the very first thing implemented correctly.

**Source:** [Android ContentResolver Security Risks](https://developer.android.com/privacy-and-security/risks/content-resolver) — official Android documentation. [FLAG_GRANT_READ_URI_PERMISSION behavior](https://medium.com/@anishkothari/android-tidbits-intent-flag-grant-read-uri-permission-and-intent-flag-grant-write-uri-permission-59f1bbb0db18) — lifecycle scoping confirmed.

---

### Pitfall 4: Whisper Hallucinates Text on Silent Audio

**What goes wrong:** When a voice message begins with silence (common — user holds phone, pauses before speaking), or when the message is too short (under ~2 seconds of actual speech), Whisper does not output empty text. Instead it outputs fabricated, plausible-sounding sentences: "Thanks for watching," "Please subscribe," or repetitions of phrases from nearby text. This is not a configuration error — it is a fundamental model behavior.

**Why it happens:** Whisper uses previous transcription tokens as context for the next segment. When there is insufficient audio signal, the model's language modeling head continues generating plausible tokens rather than outputting `<|endoftext|>`. Timestamp prediction exacerbates this: computing timestamps forces the model through additional decoding steps that amplify hallucination. Silence that is 1+ seconds long reliably triggers this.

**Consequences:** Users receive completely wrong transcription text with no indication of error. For a privacy-focused transcription app, this is a trust-destroying failure mode. The user may unknowingly paste fabricated text.

**Prevention (in priority order):**

1. **Run Voice Activity Detection (VAD) before transcription.** Use a lightweight VAD (Silero VAD or WebRTC VAD) to check that the audio contains speech. If no speech detected, show "No speech detected" instead of running Whisper.
2. **Disable timestamp computation.** Use `no_timestamps = true` in whisper.cpp params. A developer reported 4x reduction in word error rate and elimination of hallucinations with this flag alone.
3. **Strip leading/trailing silence** before feeding to Whisper. Use audio energy threshold trimming.
4. **Add no-speech probability threshold.** whisper.cpp exposes `no_speech_thold` — set it to ~0.6. If the model's own no-speech probability exceeds this, discard the segment.

```kotlin
// whisper.cpp JNI params (in WhisperParams)
params.no_timestamps = true
params.no_speech_thold = 0.6f
params.temperature_inc = 0.2f  // helps detect silence faster
```

**Warning signs:** Output of phrases like "Thanks for watching," "Please like and subscribe," repetitive phrases, or text in the wrong language on very short or silent clips. Appears on audio under 2 seconds.

**Phase to address:** Phase 1 and Phase 2. Basic no-speech threshold in Phase 1. Full VAD preprocessing in Phase 2 (quality improvements).

**Source:** [whisper.cpp Issue #1724](https://github.com/ggml-org/whisper.cpp/issues/1724) — no_timestamps as root-cause fix. [OpenAI Whisper Discussion #679](https://github.com/openai/whisper/discussions/679) — silence hallucination confirmed. [Research study on hallucinations in non-speech audio](https://www.researchgate.net/publication/388232036_Investigation_of_Whisper_ASR_Hallucinations_Induced_by_Non-Speech_Audio).

---

### Pitfall 5: Blocking the UI Thread with Inference Triggers ANR

**What goes wrong:** If Whisper inference (which takes 5-60 seconds on a mobile CPU for the small model) is called on the main thread — even inside a coroutine launched without a dispatcher switch — Android kills the app with an Application Not Responding (ANR) dialog. This can happen subtly: if the Activity's `onCreate` does `runBlocking { transcribe() }` or if a coroutine defaults to `Dispatchers.Main`.

**Why it happens:** Android requires the main thread to respond to UI events within 5 seconds. Whisper small model inference is strictly CPU-bound and can take 15-60 seconds on mid-range devices. Any inference on the main thread will exceed this limit.

**Consequences:** ANR dialog appears. User sees "App isn't responding." Google Play may surface this as a vitals issue.

**Prevention:** Always launch inference on `Dispatchers.Default` (CPU-bound thread pool, sized to core count). Never use `Dispatchers.IO` for inference — it uses up to 64 threads and can create CPU contention. For longer inference, use a `ForegroundService` with a coroutine scope.

```kotlin
// CORRECT dispatcher for CPU-bound inference
viewModelScope.launch(Dispatchers.Default) {
    val result = whisperEngine.transcribe(audioFile)
    withContext(Dispatchers.Main) {
        showResult(result)
    }
}
```

**Warning signs:** ANR dialogs in testing. "App isn't responding" reported by users. StrictMode violations in logcat showing main thread disk/compute activity.

**Phase to address:** Phase 1. The coroutine dispatcher must be set correctly from the first implementation of the inference call.

**Source:** [Android ANR documentation](https://developer.android.com/topic/performance/vitals/anr). [Kotlin Coroutines Dispatchers guide](https://www.droidcon.com/2024/11/21/exploring-the-secrets-of-dispatchers-default-and-io-in-kotlin-coroutines/).

---

## Moderate Pitfalls

Mistakes that cause bad UX or significant rework but do not break the product.

---

### Pitfall 6: Android 14/15 Foreground Service Type Requirements Break Background Processing

**What goes wrong:** Android 14 (API 34) made foreground service types mandatory. Apps targeting API 34+ that start a foreground service without declaring `android:foregroundServiceType` in the manifest receive `MissingForegroundServiceTypeException` at runtime. Android 15 adds a 6-hour cumulative time limit for `dataSync` and `mediaProcessing` foreground service types across all of a day's service runs.

**Why it happens:** Android 14 enforced type declarations to give users visibility into what background work apps perform. Android 15 added time limits to prevent indefinite background execution abuse.

**Consequences for TransChat:** If inference is run in a foreground service with `dataSync` type, and a user transcribes many long voice messages in one day, the service can be killed mid-inference after the 6-hour daily cap is hit.

**Prevention:**

1. Declare the correct foreground service type. For TransChat, `dataSync` or the newer `mediaProcessing` type is most appropriate.
2. Handle `Service.onTimeout(int, int)` (Android 15+) to gracefully stop if the limit is hit.
3. For MVP simplicity, use `WorkManager` with `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` instead of a raw service — WorkManager handles Android version differences automatically.

```xml
<!-- AndroidManifest.xml -->
<service
    android:name=".TranscriptionService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

**Warning signs:** `MissingForegroundServiceTypeException` on Android 14+ devices. Service silently killed after long usage sessions on Android 15 devices. Transcription stops mid-clip.

**Phase to address:** Phase 1 (architecture decision). Choose WorkManager vs ForegroundService at the start.

**Source:** [Android 14 foreground service type requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required). [Android 15 foreground service timeout](https://developer.android.com/develop/background-work/services/fgs/timeout).

---

### Pitfall 7: Wrong Audio Format Assumptions for WhatsApp and Telegram

**What goes wrong:** WhatsApp voice messages are `.ogg` files with Opus codec. Telegram voice messages are `.ogg` or `.oga` with Opus codec. The MIME type sent via share intent is often `audio/ogg` without codec specification, or sometimes `audio/opus`. Android's `MediaExtractor` can decode these natively, but only if the file is correctly identified. Attempting to decode as `.mp4` or `.aac` fails silently or throws `IllegalArgumentException`.

Additionally, Whisper expects 16kHz mono PCM float32 audio. Opus voice messages are typically encoded at 24kHz or 48kHz. The sample rate conversion step is mandatory but easy to skip.

**Why it happens:** Developers test with MP3 or WAV files (easier to work with) and only discover codec issues when trying real WhatsApp/Telegram audio. The MIME type `audio/ogg` does not specify the codec, and different apps send different variants.

**Consequences:** Crash or silent empty transcription on the primary use case. The app works in tests but fails for users.

**Prevention:**

1. Use Android `MediaExtractor` + `MediaCodec` for decoding — it handles Opus natively since Android 5.0. Do not attempt manual Opus decoding.
2. Do not rely on MIME type for format detection. Sniff the file header (OGG magic bytes: `4F 67 67 53`) to confirm container format.
3. Always resample decoded PCM to 16kHz mono before passing to Whisper. Use Android's built-in `AudioTrack` resampler or write a simple linear resampler.
4. Test explicitly with voice messages forwarded from WhatsApp and Telegram, not synthetic test files.

```kotlin
// Safe MediaExtractor pattern for Opus
val extractor = MediaExtractor()
extractor.setDataSource(audioFile.absolutePath)
val audioTrack = (0 until extractor.trackCount)
    .map { extractor.getTrackFormat(it) }
    .indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
// Always check audioTrack != -1 before proceeding
```

**Warning signs:** Transcription returning empty string on WhatsApp audio but working on MP3 test files. `IllegalArgumentException` in `MediaExtractor`. Transcription of a short voice message with no speech detected (wrong sample rate causes all-silence input to Whisper).

**Phase to address:** Phase 1. The audio decoding pipeline must be tested against real WhatsApp/Telegram audio immediately.

**Source:** [WhatsApp audio format documentation](https://developers.cm.com/messaging/docs/whatsapp-media-message). [Android sample rate conversion](https://source.android.com/docs/core/audio/src). [MediaExtractor API reference](https://developer.android.com/reference/android/media/MediaExtractor).

---

### Pitfall 8: Whisper Language Detection Fails on Short or Code-Switched Audio

**What goes wrong:** Whisper uses the first 30 seconds of audio for language detection. On voice messages shorter than ~5 seconds, language detection accuracy drops significantly and the model may default to English or confuse similar-sounding languages (e.g., Spanish/Portuguese, Malay/Indonesian). Code-switching (user starts in one language, switches mid-sentence) is not supported at all and causes the model to pick one language and mistranscribe the other.

**Why it happens:** Whisper's language detection is trained on longer samples. Short clips do not provide enough phoneme context for reliable language ID. The model commits to one language before the audio provides enough disambiguation signal.

**Consequences:** Wrong-language transcription output. User receives garbled text in the wrong language. This is especially likely for TransChat's multilingual WhatsApp/Telegram use case where users communicate in multiple languages.

**Prevention:**

1. Let users pin a preferred language in settings. Many users in multilingual households always receive messages in one language.
2. When auto-detecting, increase the Whisper `best_of` parameter to retry detection: `best_of = 3` reduces wrong-language detection.
3. Surface the detected language to the user so they can re-run with a forced language if wrong.
4. For MVP, consider offering a "language" quick-setting in the share sheet result UI.

**Warning signs:** User complaints of "wrong language" transcription. Short clips (under 3 seconds) consistently transcribed in English regardless of actual language. Testing with 1-2 word voice messages.

**Phase to address:** Phase 2 (quality improvements). MVP can ship with auto-detect, but language selection UI should be added quickly.

**Source:** [Whisper language detection discussion](https://github.com/openai/whisper/discussions/529). [Multi-language audio inconsistencies](https://github.com/openai/whisper/discussions/2009).

---

### Pitfall 9: Whisper Repetition Loops on Partial or Degraded Audio

**What goes wrong:** When audio quality is poor (heavy background noise, clipping, low bitrate), or when the audio cuts off unexpectedly mid-sentence, Whisper enters a repetition loop. It outputs the same phrase or word dozens or hundreds of times, generating transcription output that is megabytes long for a 10-second clip. This fills the clipboard with garbage and can cause a UI hang while rendering.

**Why it happens:** Whisper uses previous tokens to condition future generation. When the audio signal is ambiguous, the model finds a locally high-probability token sequence (a repeated phrase) and stays there. This is a known model behavior, not a bug in any particular implementation.

**Consequences:** Memory spike from massive output string. UI hang when copying to clipboard. User-visible failure with no useful transcription.

**Prevention:**

1. Set a hard cap on output tokens: `max_tokens = 224` (reasonable for a 30-second voice message at 5 words/second).
2. Enable `no_repeat_ngram_size` equivalent in whisper.cpp to penalize repeated n-grams.
3. Detect repetition post-inference: if any 5-gram appears more than 3 times in the output, truncate and show a warning.
4. Set `temperature = 0` and `beam_size = 1` for greedy decoding — reduces (but does not eliminate) repetition loops.

**Warning signs:** Inference taking much longer than expected (repetition loops extend inference time linearly with output length). Output string with thousands of repeated words. Large spikes in transcription time on "bad" audio clips.

**Phase to address:** Phase 2 (quality hardening). Implement output validation after basic transcription works.

**Source:** [Whisper repetition/hallucination discussion](https://github.com/ggml-org/whisper.cpp/discussions/1490). [Whisper large-v3 hallucination thread](https://huggingface.co/openai/whisper-large-v3/discussions/19).

---

### Pitfall 10: 244MB Model Distribution Exceeds APK Practical Limits

**What goes wrong:** Including the 244MB Whisper small GGML model directly in the APK `assets/` folder produces an APK that is 244MB+ before any other assets. Play Store's hard limit for AAB downloads is effectively 150MB for the initial install (though the AAB format allows asset delivery modules). An APK with a 244MB binary asset will either be rejected by Play Store or require Play Asset Delivery (PAD), which has significant implementation complexity.

**Why it happens:** Developers bundle models in assets for simplicity, not realizing Play Store enforces download size limits and that users will abandon installs over 100MB.

**Consequences:** Play Store rejection. Or: 50%+ abandonment rate during install. Or: users never get the model and the app shows a permanent "model not found" error.

**Prevention:** Use one of two strategies:

**Strategy A (simpler, recommended for MVP):** First-run download. Ship a minimal APK (~5MB). On first launch, download the model from a CDN (GitHub Releases, Hugging Face, or self-hosted) to `filesDir`. Show clear progress UI with download size and estimated time. Cache permanently.

**Strategy B (polished):** Play Asset Delivery with an install-time asset pack containing the model. Requires Play Store distribution but automates delivery. Not available for sideloaded APKs.

For MVP, Strategy A is appropriate. The download experience must be explicitly designed — progress bar, WiFi warning, resume on failure — or users will see a blank screen and uninstall.

**Warning signs:** APK size over 100MB during build. Gradle asset packaging taking unusually long. Play Console upload rejection at submission time.

**Phase to address:** Phase 1 (project structure). Decide the distribution strategy before writing any model loading code, as it affects the entire file path and initialization architecture.

**Source:** [Android App Bundle FAQ](https://developer.android.com/guide/app-bundle/faq). [On-device Whisper inference discussion](https://github.com/openai/whisper/discussions/506).

---

## Minor Pitfalls

Issues that cause friction but are straightforward to fix once identified.

---

### Pitfall 11: 16KB Page Size Requirement for NDK Libraries (Google Play Deadline: November 2025)

**What goes wrong:** Google Play requires all new app submissions targeting Android 15+ to support 16KB memory page sizes as of November 1, 2025. Apps with NDK native libraries compiled with older NDK (pre-r28) that use 4KB ELF alignment will fail to load on 16KB page-size devices (currently Pixel 9 series and future devices).

**Prevention:** Build whisper.cpp and all native code with NDK r28 or higher. NDK r28 enables 16KB alignment by default. Add the `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES` flag in CMakeLists.txt. Test on the 16KB Android Studio emulator target.

```cmake
# CMakeLists.txt
target_link_options(whisper PRIVATE "-Wl,-z,max-page-size=16384")
```

**Warning signs:** App fails to start on Pixel 9 or emulated 16KB device. Logcat shows `dlopen failed: ... alignment`.

**Phase to address:** Phase 1 (NDK build setup). Set the correct NDK version and build flags before any native code is compiled.

**Source:** [Android 16KB page size guide](https://developer.android.com/guide/practices/page-sizes). [Google Play 16KB compliance deadline](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html).

---

### Pitfall 12: R8 Minification Breaking JNI Bindings

**What goes wrong:** When R8 code shrinking is enabled (release builds), JNI method names referenced by native code can be obfuscated. If the Kotlin/Java class or method names that whisper.cpp's JNI bridge calls are renamed by R8, the native code throws `NoSuchMethodError` or `UnsatisfiedLinkError` at runtime only in release builds.

**Prevention:** Add ProGuard/R8 keep rules for all JNI-facing classes and methods:

```
# proguard-rules.pro
-keep class com.yourapp.whisper.** { *; }
-keepclassmembers class * {
    native <methods>;
}
```

**Warning signs:** Works in debug, crashes in release. `UnsatisfiedLinkError` or `NoSuchMethodError` in release logcat. Previously worked, broke after enabling minification.

**Phase to address:** Phase 1 (build configuration). Add keep rules when first setting up the NDK/JNI bridge.

**Source:** [whisper.cpp NDK build issues](https://github.com/ggml-org/whisper.cpp/issues/856).

---

### Pitfall 13: Model Cold-Load Time Creates Apparent Hang

**What goes wrong:** Loading a 244MB model from disk into native memory takes 2-8 seconds on mid-range Android hardware. If the app loads the model on every share intent invocation without caching the loaded session, users experience a multi-second hang before transcription begins. If load time is not surfaced in the UI, users assume the app is frozen.

**Prevention:**

1. Load the model once at app startup and keep the inference session alive in memory (as a singleton in the Application class or a retained ViewModel).
2. Show an explicit "Loading model..." progress indicator on first launch.
3. Measure actual model load time on target hardware during Phase 1 development to set realistic UI expectations.

**Warning signs:** Users report "app freezes" when they share audio. Long ANR-adjacent hangs at the start of the first transcription. No loading indicator visible.

**Phase to address:** Phase 2 (UX polish). Basic loading works in Phase 1, but the singleton session pattern should be established early.

---

### Pitfall 14: Temporary File Accumulation in Cache

**What goes wrong:** Each share intent creates a temporary copy of the audio file in the app's cache directory (required to avoid URI permission expiry — see Pitfall 3). Without explicit cleanup, these accumulate. Voice messages can be 200KB-2MB each. Heavy users can accumulate hundreds of megabytes in the app's cache within weeks.

**Prevention:** Delete the temporary audio file immediately after transcription completes (success or failure). Use `try/finally` to guarantee cleanup:

```kotlin
val tempFile = copyToPrivateStorage(audioUri)
try {
    val result = transcribe(tempFile)
    showResult(result)
} finally {
    tempFile.delete()
}
```

Also register a `WorkManager` periodic task to sweep stale files from `cacheDir` older than 24 hours as a safety net.

**Warning signs:** App storage usage grows indefinitely. Users complain about storage consumption. `cacheDir` contains many `.tmp` audio files.

**Phase to address:** Phase 1. The cleanup belongs in the same code path as the copy.

---

## v1.1 Pitfalls: Progress Callbacks, F-Droid, and R8

The following pitfalls are specific to the v1.1 milestone features being added to the existing codebase. All four domains are integration pitfalls — failures that only surface when combining these features with the existing architecture.

---

## v1.1 Critical Pitfalls

---

### Pitfall 15: JNI Callback Fires on Native Thread — UI Update Crashes or Silently Drops

**What goes wrong:** The whisper.cpp `new_segment_callback` (and `encoder_begin_callback`) fires on whichever C++ thread is running inference. In TransChat's architecture, that thread is the `newSingleThreadExecutor`'s Java thread — not the Android main thread. Any attempt to directly update a `MutableStateFlow`, post to a `Handler`, or call any UI method from inside the JNI callback (via a `CallVoidMethod` / `CallStaticVoidMethod` upcall) runs on the wrong thread.

The specific failure modes are:

- Calling a Kotlin method that internally does `_flow.value = x` from inside the JNI callback is actually safe if StateFlow's thread-safe `MutableStateFlow` is used, but only if the Kotlin object was passed to native code correctly. The silent failure mode is that the native callback's upcall crashes with a `java.lang.RuntimeException: Can't create handler inside thread that has not called Looper.prepare()` if any path inside the callback tries to post to a `Handler`.
- Calling any view method (even indirectly) from the callback crashes with `CalledFromWrongThreadException`.

**Why it happens:** `JNIEnv` is thread-local and cannot be passed across thread boundaries. The JNI callback receives a `JNIEnv*` valid only on the thread that entered native code. Because TransChat uses a `newSingleThreadExecutor`, inference runs on a dedicated background thread — the `JNIEnv*` available at callback time belongs to that background thread, not the main thread. Any Android API that requires the main thread (Looper, Handler, View) will throw.

**The Executor thread distinction:** Critically, `newSingleThreadExecutor` starts a Java-managed thread, not a raw `pthread`. This thread is already attached to the JVM (the JVM manages it). You do NOT need to call `AttachCurrentThread` for it. You do need to cache the `JavaVM*` (not `JNIEnv*`) in `JNI_OnLoad` and retrieve a fresh `JNIEnv*` per callback invocation via `GetEnv` if needed from the same thread.

**Consequences:** Progress bar never updates (callback fires, StateFlow update silently succeeds on background thread but UI collector not running), or app crashes with `CalledFromWrongThreadException` in release, or `IllegalStateException: Looper.prepare()` error in logcat.

**Prevention:**

The correct bridge pattern is:

1. In `JNI_OnLoad`, cache `JavaVM*` globally.
2. Pass a global reference to the Kotlin callback object to native code via `NewGlobalRef`. Store in a C++ global or the whisper `user_data` pointer.
3. In the C++ `new_segment_callback`, call `vm->GetEnv(...)` to obtain the thread's `JNIEnv*` (the executor thread is already attached; this is a no-op retrieve).
4. Call the Kotlin method via `CallVoidMethod`. The Kotlin method should do nothing but `_progressFlow.value = n` — StateFlow is thread-safe for this.
5. Do NOT create any local JNI references that outlive the callback without promoting them to global references.
6. The StateFlow collector on the UI side uses `repeatOnLifecycle` and `Dispatchers.Main` — the UI update happens there, not in the callback.

```cpp
// whisper_jni.cpp — callback implementation
static jobject g_progressCallback = nullptr;  // global ref, set during init
static jmethodID g_onSegmentMethod = nullptr;

void on_new_segment(struct whisper_context* ctx, struct whisper_state*, int n_new, void* user_data) {
    JNIEnv* env;
    // Executor thread is already attached; GetEnv retrieves existing JNIEnv
    if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return;
    if (g_progressCallback == nullptr || g_onSegmentMethod == nullptr) return;
    int total = whisper_full_n_segments(ctx);
    env->CallVoidMethod(g_progressCallback, g_onSegmentMethod, total);
    // No UI calls here — Kotlin side updates StateFlow only
}
```

```kotlin
// WhisperContext.kt — the Kotlin callback receives on background thread
fun onSegmentCompleted(totalSegments: Int) {
    _progressFlow.value = totalSegments  // StateFlow: thread-safe write
}
```

**Warning signs:** Progress bar never advances despite inference running. `CalledFromWrongThreadException` in logcat. `IllegalStateException: Can't create handler inside thread` in release crash logs. Callback appears to fire (confirmed by log) but UI does not reflect it.

**Phase to address:** v1.1 Phase 1 (progress callback implementation). Must be the first thing validated with an instrumented test before wiring the UI.

**Confidence:** HIGH — verified against [Android NDK JNI Tips](https://developer.android.com/training/articles/perf-jni), [JNI threading NDK wiki](https://github.com/android/ndk/wiki/JNI).

---

### Pitfall 16: JNI Global Reference Leak Crashes App After Multiple Transcriptions

**What goes wrong:** When wiring the progress callback, developers commonly pass the Kotlin listener object to native code. If the native code calls `NewGlobalRef` to hold a reference to the listener across the transcription and then never calls `DeleteGlobalRef` when transcription ends (or when the ViewModel is cleared), the JVM's global reference table grows by one entry per transcription. The hard limit is 51,200 global references on modern Android. With enough transcriptions, the app crashes with `JNI ERROR (app bug): local reference table overflow (max=512)` or a similar global ref exhaustion error.

**Why it happens:** Developers familiar with Java assume the garbage collector handles native references. It does not. `NewGlobalRef` creates a pinning reference that prevents GC and is not freed until `DeleteGlobalRef` is explicitly called. Local references (created within a callback invocation) are freed at callback return, but anything promoted to global via `NewGlobalRef` lives forever unless deleted.

**Consequences:** Slow memory leak that causes crash after many uses. Does not appear in development (small number of test runs). CheckJNI in debug mode will log warnings but the default limit is high enough that tests pass. Manifests in production from heavy users.

**Prevention:**

- Call `NewGlobalRef` once on the listener at transcription start and `DeleteGlobalRef` at transcription end (in a `finally` block on the Kotlin side via a cleanup JNI call).
- Alternatively, pass the listener as a local reference on each call into native code rather than storing globally.
- Enable CheckJNI during development: add `android:debuggable="true"` and set system property `debug.checkjni=1` to get early warnings.
- In `WhisperContext.onCleared()` or equivalent teardown, call a JNI method that calls `DeleteGlobalRef` on all held references.

```kotlin
// WhisperContext.kt
override fun onCleared() {
    super.onCleared()
    nativeReleaseCallback()  // JNI: calls DeleteGlobalRef on g_progressCallback
}
```

**Warning signs:** `JNI ERROR: local reference table overflow` in logcat after many transcriptions. Native memory growing across transcription sessions. Works fine in development (few test transcriptions), crashes in production after 50+ uses by a single user.

**Phase to address:** v1.1 Phase 1 (progress callback implementation). Must be paired with the callback setup — implement DeleteGlobalRef at the same time as NewGlobalRef, not as a follow-up.

**Confidence:** HIGH — [JNI global reference leak in Google Filament](https://github.com/google/filament/issues/4957), [JGRE vulnerability analysis](https://ieeexplore.ieee.org/document/8023142/), [Android NDK JNI Tips](https://developer.android.com/training/articles/perf-jni).

---

### Pitfall 17: F-Droid Rejects App Because whisper.cpp Is Compiled from Source But Included as Prebuilt .so

**What goes wrong:** F-Droid's inclusion policy requires that all binary blobs are built from source during the F-Droid build process, or come from a trusted repository (Maven Central, etc.). If the project ships whisper.cpp as a prebuilt `.so` file checked into the repository (common during development for faster builds), F-Droid reviewers will reject the submission. This is not a warning — it is a hard rejection.

The exact rule: "Prebuilt executable files must not be included in the project's codebase, and shared libraries must be built from source, always."

**Why it happens:** Checking in `.so` files or using `libs/arm64-v8a/libwhisper.so` instead of building from CMakeLists.txt is a common development shortcut. Many developers are unaware F-Droid's build server compiles the app from scratch and will detect prebuilt binaries.

**Consequences:** F-Droid submission blocked. The reviewer will identify the prebuilt `.so` and require its removal before re-review. This adds weeks to the F-Droid inclusion timeline.

**Prevention:**

- Ensure the CMakeLists.txt builds whisper.cpp from source completely. The F-Droid build server must be able to run `./gradlew assembleRelease` and produce a complete APK with no prebuilt native binaries.
- Remove any prebuilt `.so` files from the git repository before submission.
- The whisper.cpp source files (`.cpp`, `.h`) must be in the repository or fetched during the build via the F-Droid `srclibs` mechanism.
- Verify by doing a clean checkout and running the build with no local SDK/NDK cache.

**Warning signs:** `.so` files committed to `app/src/main/jniLibs/`. CMakeLists.txt references a `prebuilt` or `imported` library target rather than compiling from source. Build succeeds only when `jniLibs/` is present.

**Phase to address:** v1.1 Phase 2 (F-Droid preparation). Must be verified before F-Droid submission.

**Confidence:** HIGH — [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/), [F-Droid Building Applications](https://f-droid.org/en/docs/Building_Applications/).

---

### Pitfall 18: F-Droid Build Produces Non-Deterministic APK Due to NDK Binary Differences Across Platforms

**What goes wrong:** F-Droid's reproducible build system builds the APK on its own Ubuntu-based server using a specific NDK version. If the developer-signed APK (used for reproducible signing) was built on macOS with NDK 26.1, it will differ from what F-Droid builds on Ubuntu with NDK 26.1. The difference is in ELF metadata: linker version strings embedded in `.so` files, `.shstrtab` sections, and debug symbol layout vary by host OS even with identical NDK versions.

Additionally, since NDK r26d, there is a known behavior where the `.shstrtab` section in ELF binaries differs between builds even on the same host, caused by AGP stripping behavior.

**Why it happens:** NDK's linker (`lld`) embeds host-platform metadata into the output ELF. The same source code, same NDK version, same flags, produce different binaries on macOS vs Ubuntu. Reproducible builds require bit-for-bit identical output.

**Consequences:** F-Droid's reproducible build verification fails. The app cannot participate in F-Droid's verified builds (where F-Droid signs a matching APK). The app can still be included in F-Droid, but without the reproducibility badge, F-Droid must sign the APK itself (not the developer), which requires submitting to fdroiddata rather than a simpler self-hosted path.

**Mitigation (not full prevention):**

- Accept that full reproducibility with native code is very hard. Non-reproducible apps are still accepted by F-Droid — they just use F-Droid's key rather than the developer's key.
- If reproducibility is required: build the developer-signed APK on Linux (not macOS) to match F-Droid's build environment. Use a Docker container with the same Ubuntu version as F-Droid's build server.
- Pin the exact NDK version in `build.gradle`: `ndkVersion "26.1.10909125"`. F-Droid's build metadata must specify the same version.
- Do not use R8 full mode for the reproducible build — it increases non-determinism due to CPU-count-dependent DEX generation (fixed in R8 8.6.33+).

**Warning signs:** F-Droid build verification reports APK mismatch. `apksigner verify` passes on both APKs but `diff` of unzipped contents shows differences in `.so` files. macOS-built developer APK that does not match F-Droid's server-built APK.

**Phase to address:** v1.1 Phase 2 (F-Droid preparation). Evaluate reproducibility early, set expectations appropriately — do not block submission on full reproducibility if non-reproducible inclusion is acceptable.

**Confidence:** MEDIUM — [F-Droid Reproducible Builds docs](https://f-droid.org/docs/Reproducible_Builds/), community reports on NDK non-determinism, [reproducible-builds.org 2025 report](https://reproducible-builds.org/reports/2025-05/).

---

### Pitfall 19: R8 Strips JNI Upcall Methods That Are Called Only From Native Code

**What goes wrong:** When R8 is enabled in release builds, it performs static dead-code analysis. Methods that are only called from native C++ code (via `CallVoidMethod` / `CallStaticVoidMethod`) have no visible Java/Kotlin callers in the bytecode. R8 concludes they are unreachable and removes them. The result is `NoSuchMethodError` at runtime — in release builds only — when the native callback attempts to invoke the now-removed method.

The default `proguard-android-optimize.txt` protects `native <methods>` (methods with the `native` keyword — i.e., downcalls: Kotlin methods that dispatch to C++). It does NOT protect upcall methods (plain Kotlin methods called FROM C++). These are indistinguishable from unused code from R8's perspective.

**Why it happens:** R8 is a static analyzer. JNI upcalls are dynamic — the native code holds a `jmethodID` at runtime. R8 cannot see this relationship. The official Android docs confirm: "When your app uses JNI to make upcalls from native code to Java or Kotlin, R8 can't see which methods are called from your native code and incorrectly assumes they are unused."

**Specific risk for TransChat:** The progress callback Kotlin method (e.g., `WhisperContext.onSegmentCompleted(Int)`) will be called via `CallVoidMethod` from native. It has no Kotlin caller. Without an explicit keep rule, R8 removes it.

**Prevention:**

```
# proguard-rules.pro

# Protect JNI downcalls (whisper.cpp native methods — already covered by default rules,
# but explicit is safer with 6 dollar-sign-encoded exported functions)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Protect JNI upcall methods called from whisper_jni.cpp
# These are NOT native methods — they are Kotlin methods invoked via CallVoidMethod
-keepclassmembers class com.example.transchat.whisper.WhisperContext {
    void onSegmentCompleted(int);
}

# If using a separate callback interface
-keep interface com.example.transchat.whisper.WhisperProgressCallback {
    *;
}
```

The rules must be as narrow as possible — keeping the entire package would defeat R8's size reduction. Keep only the specific upcall method signatures.

**AGP 9.0 companion object caveat:** If the upcall target is in a Kotlin companion object, keep rules on the interface no longer automatically extend to companion methods as of AGP 9.0. Explicitly keep the companion method.

**Warning signs:** `NoSuchMethodError` with the upcall method name in release logcat. Progress callback works in debug (`minifyEnabled false`) but not release. R8's mapping file (build output) shows the method was removed. Crash only in release, only when a transcription with progress tracking begins.

**Phase to address:** v1.1 Phase 1 (progress callback) AND v1.1 Phase 3 (R8 configuration). Add the upcall keep rule at the same time the upcall method is written. Validate in a release build before merging.

**Confidence:** HIGH — [Android Developers: Keep rule use cases](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples), [Android Developers Blog: Configure and troubleshoot R8 Keep Rules (2025)](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html), [SDL JNI @Keep issue #3563](https://github.com/libsdl-org/SDL/issues/3563).

---

## v1.1 Moderate Pitfalls

---

### Pitfall 20: Progress Callback Invoked Too Frequently Degrades Inference Speed

**What goes wrong:** Whisper processes audio in segments. The `new_segment_callback` fires once per decoded segment — typically 10-20 times for a 60-second clip. This is low-frequency and has negligible overhead.

The problem arises if developers add a more granular progress hook — such as polling `whisper_full_n_segments` inside a loop, using the `encoder_begin_callback` for per-frame progress, or calling into Kotlin on every ggml computation. The JNI boundary crossing itself is cheap, but calling back into Kotlin on every tensor operation (which can be thousands of times per second) adds measurable latency.

For TransChat's 12-15s inference baseline, even 5% overhead adds ~0.7s per clip. On slower devices, it can add 2-3 seconds.

**Why it happens:** Developers want smooth progress bars and attempt to emit progress more frequently than the natural segment cadence. The `encoder_begin_callback` fires once per encoder invocation (~30 times for a 30-second audio clip) which is still acceptable, but any finer granularity risks inference slowdown.

**Prevention:**

- Use only `new_segment_callback` for progress. It fires at the natural segment boundary and requires zero additional polling.
- Do not add polling loops in C++ that call back into Kotlin.
- Map segment count to progress percent in Kotlin, not in C++.
- Benchmark inference time with and without the callback on the target device before shipping.
- The callback's Kotlin method must do nothing except `_flow.value = n`. Any allocation, string formatting, or additional computation inside the callback path adds to inference time.

**Warning signs:** Inference time increases after adding the progress callback. Logcat shows callback firing hundreds of times per second. CPU profiler shows JNI overhead during inference.

**Phase to address:** v1.1 Phase 1 (progress callback). Benchmark before and after adding the callback.

**Confidence:** MEDIUM — inferred from JNI overhead characteristics and whisper.cpp callback documentation; specific benchmark numbers are LOW confidence without device-specific measurement.

---

### Pitfall 21: F-Droid fastlane Metadata File Naming Causes Silent Linter Failure

**What goes wrong:** F-Droid's metadata scanner accepts fastlane-style metadata in the source repository at `fastlane/metadata/android/en-US/`. The file naming convention differs from standard fastlane:

- F-Droid expects `short_description.txt` → actually `summary.txt` (F-Droid's name)
- F-Droid expects `full_description.txt` → actually `description.txt` (F-Droid's name)

Using the standard fastlane names causes the linter to silently skip these files, resulting in the F-Droid listing showing no description. The app is listed but appears to have no description text, reducing user trust and install rate.

**Why it happens:** F-Droid's convention diverges from fastlane's convention at these two specific file names. Developers who follow fastlane documentation (very common) use the wrong names.

**Prevention:**

Use exactly this structure:
```
fastlane/metadata/android/
  en-US/
    title.txt           (max 30 characters)
    summary.txt         (max 80 characters — NOT short_description.txt)
    description.txt     (max 4000 characters — NOT full_description.txt)
    changelogs/
      3.txt             (versionCode 3 changelog)
  images/
    icon.png            (512x512)
    featureGraphic.png  (1024x500)
    phoneScreenshots/
      01.png
```

**Warning signs:** F-Droid listing shows the app name but empty description. Linter passes (no error) but metadata is not rendered. Other apps with similar fastlane structures have descriptions, yours does not.

**Phase to address:** v1.1 Phase 2 (F-Droid metadata). A one-time naming fix, low effort, but easy to miss.

**Confidence:** HIGH — [F-Droid: All About Descriptions, Graphics, and Screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/).

---

### Pitfall 22: R8 Full Mode Strips Runtime Annotations — Breaks Kotlin Reflection Paths

**What goes wrong:** R8 full mode (enabled by default with AGP 8+ and `android.enableR8.fullMode=true` in `gradle.properties`) is more aggressive than compatibility mode. It can strip runtime-retained annotations and remove class signature information needed for generic type resolution. For TransChat, the specific risk is that any Kotlin data classes used for ViewModel state (e.g., a `TranscriptionState` sealed class or data class) may have their generic type parameters erased by R8 if the class is only referenced through a generic StateFlow type.

**Why it happens:** R8 full mode removes the `Signature` attribute from classes that it determines are only used in dead code paths. If `StateFlow<TranscriptionState>` is only referenced through a generic parameter in a way R8 cannot trace statically, it may strip the `TranscriptionState` class's generic signature.

**Consequences:** Runtime crash (`ClassCastException` or `IllegalArgumentException`) when collecting from the StateFlow in the UI. Works in debug, crashes in release.

**Prevention:**

Add to `proguard-rules.pro`:
```
# Preserve generic type signatures for StateFlow-wrapped state classes
-keepattributes Signature
-keepattributes *Annotation*

# Keep ViewModel state sealed classes
-keep class com.example.transchat.ui.state.** { *; }
```

Additionally, verify that `proguard-android-optimize.txt` (the default base) is used, not a stripped-down custom base file. The default base already includes `Signature` preservation for common cases.

**Warning signs:** `ClassCastException` when collecting StateFlow in UI. Only occurs in release. R8's `missing_rules.txt` output (in `build/outputs/mapping/release/`) lists state classes as candidates for removal.

**Phase to address:** v1.1 Phase 3 (R8 configuration). Test release builds with a real device after enabling minification.

**Confidence:** MEDIUM — [R8 full mode documentation](https://developer.android.com/topic/performance/app-optimization/full-mode), [kotlinx.serialization R8 full mode issue #2501](https://github.com/Kotlin/kotlinx.serialization/issues/2501).

---

### Pitfall 23: Branch Rename master → main Breaks CI and Any Hardcoded References

**What goes wrong:** Renaming the default branch from `master` to `main` is operationally simple (three git commands) but breaks anything that hardcodes the branch name. Common breakage points for a solo Android project:

1. GitHub Actions workflows with `on: push: branches: [master]` trigger condition
2. Any badge URLs in README pointing to `master`
3. Dependabot config referencing `master` as the target branch
4. F-Droid metadata in fdroiddata referencing the `master` branch for source checkout

**Why it happens:** The rename is a git operation but the branch name is also a string embedded in many configuration files. Developers rename the branch and verify it works but forget to search for all string occurrences of "master" across config files.

**Consequences:** CI stops running on pushes to `main` (the most severe — no automation). F-Droid's build server cannot checkout the source (if it hardcodes the branch). README badges show "unknown" status.

**Prevention:**

Before renaming:
1. Search the entire repository for the string "master": `grep -r "master" .github/ .fdroid.yml fastlane/ README*`
2. Update all found references in the same commit as the rename
3. After rename: verify GitHub Actions workflows triggered on the first push to `main`
4. Set GitHub default branch to `main` in repository Settings immediately after rename — this is what determines what F-Droid and GitHub Dependabot target

The three-command rename:
```bash
git branch -m master main
git push origin -u main
git push origin --delete master
```

Then in GitHub Settings: change default branch from `master` to `main`.

**Warning signs:** GitHub Actions tab shows no recent runs after the rename. CI last run was before the rename. Badge shows "unknown" or old state. F-Droid build log shows "branch not found".

**Phase to address:** v1.1 Phase 4 (branch rename). This is a discrete, low-risk, low-effort task. Do it in isolation with a complete pre-rename audit.

**Confidence:** HIGH — standard git/GitHub operational guidance; [GitHub renaming guidance](https://github.com/github/renaming).

---

## Phase-Specific Warnings Summary

| Phase Topic | Likely Pitfall | Mitigation |
|-------------|---------------|------------|
| Model loading | OOM from byte array load (Pitfall 1) | File path API, never byte array |
| NDK build setup | Debug build performance lies (Pitfall 2) | Benchmark release builds only |
| NDK build setup | 16KB page size (Pitfall 11) | NDK r28+, set max-page-size flag |
| NDK build setup | R8 breaking JNI (Pitfall 12) | ProGuard keep rules for JNI classes |
| Share intent handling | URI permission revoked (Pitfall 3) | Copy audio bytes synchronously in Activity |
| Audio decoding pipeline | Wrong format assumed (Pitfall 7) | Test with real WhatsApp/Telegram audio day 1 |
| Inference execution | ANR from main thread (Pitfall 5) | Dispatchers.Default for all inference |
| Inference execution | FGS type missing (Pitfall 6) | Declare foregroundServiceType in manifest |
| Model distribution | 244MB in APK rejected (Pitfall 10) | First-run download strategy, not bundled asset |
| Transcription quality | Silence hallucination (Pitfall 4) | no_timestamps=true, no_speech_thold |
| Transcription quality | Repetition loops (Pitfall 9) | max_tokens cap, output validation |
| Transcription quality | Wrong language detection (Pitfall 8) | Language pin option in UI, best_of=3 |
| Resource management | Temp file accumulation (Pitfall 14) | Delete in finally block |
| UX | Model cold-load hang (Pitfall 13) | Singleton session, loading indicator |
| **v1.1** Progress callback | Wrong thread for UI update (Pitfall 15) | StateFlow-only updates in callback; no Handler/View calls |
| **v1.1** Progress callback | JNI global reference leak (Pitfall 16) | DeleteGlobalRef paired with NewGlobalRef; cleanup in onCleared() |
| **v1.1** Progress callback | R8 strips upcall method (Pitfall 19) | Explicit -keepclassmembers for each upcall method signature |
| **v1.1** Progress callback | Callback overhead degrades inference (Pitfall 20) | Use new_segment_callback only; benchmark before/after |
| **v1.1** F-Droid prep | Prebuilt .so rejection (Pitfall 17) | Build whisper.cpp from source via CMakeLists.txt only |
| **v1.1** F-Droid prep | NDK non-deterministic binary (Pitfall 18) | Accept non-reproducible or build on Linux; pin exact NDK version |
| **v1.1** F-Droid prep | Wrong fastlane file names (Pitfall 21) | summary.txt not short_description.txt; description.txt not full_description.txt |
| **v1.1** R8 config | Full mode strips state class signatures (Pitfall 22) | -keepattributes Signature; keep state sealed classes |
| **v1.1** Branch rename | CI stops running after rename (Pitfall 23) | Audit all "master" string occurrences before renaming |

---

## Sources

### v1.0 Sources
- [ONNX Runtime Android OOM Issue #19514](https://github.com/microsoft/onnxruntime/issues/19514) — confirmed byte array vs file path OOM root cause
- [whisper.cpp Android slow inference Issue #1022](https://github.com/ggml-org/whisper.cpp/issues/1022) — R8 minification as performance bottleneck
- [whisper.cpp Android inference slow Issue #1070](https://github.com/ggml-org/whisper.cpp/issues/1070) — debug vs release performance gap community confirmation
- [whisper.cpp hallucination on silence Issue #1724](https://github.com/ggml-org/whisper.cpp/issues/1724) — no_timestamps as root-cause fix
- [Whisper hallucination discussion](https://github.com/openai/whisper/discussions/679) — silence triggering fabricated text
- [Whisper repetition/hallucination](https://github.com/ggml-org/whisper.cpp/discussions/1490) — repetition loop behavior
- [Android ContentResolver security risks](https://developer.android.com/privacy-and-security/risks/content-resolver) — official Android docs on URI confusion attacks
- [Android foreground service types Android 14](https://developer.android.com/about/versions/14/changes/fgs-types-required) — mandatory type declarations
- [Android foreground service timeout Android 15](https://developer.android.com/develop/background-work/services/fgs/timeout) — 6-hour daily cap
- [Android 16KB page size requirement](https://developer.android.com/guide/practices/page-sizes) — NDK r28 alignment requirement
- [Google Play 16KB compliance deadline](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html) — November 2025 enforcement
- [Whisper language detection failures](https://github.com/openai/whisper/discussions/529) — short audio language ID issues
- [WhatsApp audio format documentation](https://developers.cm.com/messaging/docs/whatsapp-media-message) — OGG/Opus format specifics
- [Android sample rate conversion](https://source.android.com/docs/core/audio/src) — resampling documentation
- [Kotlin Coroutines Dispatchers](https://www.droidcon.com/2024/11/21/exploring-the-secrets-of-dispatchers-default-and-io-in-kotlin-coroutines/) — Default vs IO for CPU-bound work
- [Investigation of Whisper ASR Hallucinations](https://www.researchgate.net/publication/388232036_Investigation_of_Whisper_ASR_Hallucinations_Induced_by_Non-Speech_Audio) — academic study on non-speech hallucination

### v1.1 Sources
- [Android NDK JNI Tips — Threading](https://developer.android.com/training/articles/perf-jni) — JNIEnv thread-locality, AttachCurrentThread, global vs local references
- [Android NDK JNI Wiki](https://github.com/android/ndk/wiki/JNI) — authoritative threading rules
- [JNI Global Reference Leak — Google Filament Issue #4957](https://github.com/google/filament/issues/4957) — real-world global ref leak pattern
- [JGRE: JNI Global Reference Exhaustion Vulnerabilities in Android](https://ieeexplore.ieee.org/document/8023142/) — confirmed crash mechanism
- [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) — prebuilt .so rejection policy
- [F-Droid Building Applications](https://f-droid.org/en/docs/Building_Applications/) — source-only build requirement
- [F-Droid Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/) — NDK non-determinism across platforms
- [Reproducible Builds May 2025 Report](https://reproducible-builds.org/reports/2025-05/) — current reproducibility status
- [F-Droid: All About Descriptions, Graphics, and Screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/) — fastlane file naming
- [Android Developers: Keep Rule Use Cases and Examples](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples) — upcall protection
- [Android Developers Blog: Configure and troubleshoot R8 Keep Rules (2025)](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html) — current R8 guidance
- [SDL JNI @Keep issue #3563](https://github.com/libsdl-org/SDL/issues/3563) — real-world JNI upcall stripping example
- [R8 Full Mode documentation](https://developer.android.com/topic/performance/app-optimization/full-mode) — full mode vs compatibility mode behavior
- [kotlinx.serialization R8 full mode issue #2501](https://github.com/Kotlin/kotlinx.serialization/issues/2501) — Signature attribute stripping pattern
- [GitHub renaming repository guidance](https://github.com/github/renaming) — branch rename steps and CI impact
- [whisper-jni JNI wrapper](https://github.com/GiviMAD/whisper-jni) — reference JNI wrapper for whisper.cpp callbacks
