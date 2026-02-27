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

---

## Sources

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
