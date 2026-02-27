# Architecture Patterns: Android On-Device Audio Transcription

**Domain:** Android on-device ML inference — audio transcription via share intent
**Researched:** 2026-02-27
**Overall confidence:** HIGH (core patterns verified against official Android docs and whisper.cpp repository)

---

## Recommended Architecture

TransChat is a single-purpose utility app. It has no persistent UI, no user accounts, and no network. Its entire value is: receive audio → transcribe → emit text. This constrains the architecture in a useful direction: keep it flat and avoid over-engineering.

```
┌─────────────────────────────────────────────────────────────────┐
│                    SHARE ENTRY POINT                            │
│  ShareActivity (transparent, no UI of its own)                  │
│  - android:theme="@style/Theme.Translucent.NoTitleBar"         │
│  - Intent filter: ACTION_SEND, audio/*                          │
│  - Extracts URI from EXTRA_STREAM                               │
│  - Launches TranscriptionViewModel coroutine                    │
│  - Shows ProgressDialog (blocks back to caller during work)     │
└───────────────────┬─────────────────────────────────────────────┘
                    │ Uri (content:// or file://)
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                    AUDIO ACQUISITION LAYER                      │
│  AudioLoader (object/repository, runs on Dispatchers.IO)        │
│  - ContentResolver.openInputStream(uri)                         │
│  - Copies bytes to app-private cache file (cacheDir/audio_tmp)  │
│  - Returns File path for MediaCodec (requires seekable source)  │
└───────────────────┬─────────────────────────────────────────────┘
                    │ File (ogg/opus/mp4/aac)
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                    AUDIO DECODE + PREPROCESS LAYER              │
│  AudioPreprocessor (runs on Dispatchers.Default, CPU-bound)     │
│                                                                 │
│  Step 1: MediaExtractor → detect track format (mime type)       │
│  Step 2: MediaCodec (async mode) → decode to PCM               │
│          - Request KEY_PCM_ENCODING = ENCODING_PCM_FLOAT        │
│          - Output: raw float samples at original sample rate    │
│  Step 3: Downsample to 16 kHz mono                              │
│          - Downmix stereo → mono (average L+R)                  │
│          - Resample using linear or sinc interpolation          │
│  Step 4: Produce FloatArray of PCM @ 16kHz mono                 │
│          (Whisper expects exactly this format)                   │
└───────────────────┬─────────────────────────────────────────────┘
                    │ FloatArray (16kHz, mono, float32)
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                    INFERENCE LAYER (JNI BRIDGE)                 │
│  WhisperEngine (singleton, owns native context pointer)         │
│                                                                 │
│  Kotlin side:                                                    │
│  - WhisperEngine.getInstance() — lazy-loads model once         │
│  - transcribe(audio: FloatArray, lang: String): String          │
│  - Delegates to native via JNI                                  │
│                                                                 │
│  JNI bridge (whisper_jni.cpp):                                  │
│  - Java_com_transchat_WhisperEngine_loadModel(assetManager)     │
│    → whisper_init_from_buffer() or whisper_init()              │
│    → returns jlong context_ptr                                  │
│  - Java_com_transchat_WhisperEngine_transcribe(                 │
│        context_ptr, jfloatArray audio, jstring lang)            │
│    → whisper_full_default_params(WHISPER_SAMPLING_GREEDY)       │
│    → whisper_full(ctx, params, audio_data, n_samples)           │
│    → whisper_full_n_segments() + whisper_full_get_segment_text()│
│    → concatenate, return jstring                                │
│                                                                 │
│  Native (whisper.cpp via CMake):                                │
│  - Links ggml + whisper.cpp compiled for arm64-v8a, armeabi-v7a │
│  - Uses NEON SIMD on ARM for matrix ops                         │
│  - n_threads: Runtime.getRuntime().availableProcessors() / 2    │
└───────────────────┬─────────────────────────────────────────────┘
                    │ String (transcript text)
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                    OUTPUT LAYER                                  │
│  TranscriptOutputHandler                                        │
│  - ClipboardManager.setPrimaryClip(transcript)                  │
│  - Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT)       │
│  - ShareActivity.finish() → returns user to caller app          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Component Boundaries

| Component | Responsibility | Input | Output | Thread |
|-----------|---------------|-------|--------|--------|
| `ShareActivity` | Intent entry point, UI shell | Android `Intent` | Decoded `Uri` | Main |
| `TranscriptionViewModel` | Orchestrates pipeline, holds state | `Uri` | `UiState` (Flow) | Main (launches coroutines) |
| `AudioLoader` | URI → File (copy to cache) | `Uri`, `ContentResolver` | `File` | `Dispatchers.IO` |
| `AudioPreprocessor` | File → PCM float array | `File` | `FloatArray` | `Dispatchers.Default` |
| `WhisperEngine` | PCM → transcript text via JNI | `FloatArray`, language hint | `String` | `Dispatchers.Default` |
| `TranscriptOutputHandler` | Text → clipboard + toast | `String` | (side effects) | Main |
| `whisper_jni.cpp` | JNI bridge to whisper.cpp C API | JNI types | JNI types | Caller's thread |
| `whisper.cpp` (native) | Transformer inference | raw C float* | C string | Caller's thread |

---

## Data Flow: Audio URI to Transcript Text

```
1. WhatsApp/Telegram → shares URI via ACTION_SEND
   Content-type: audio/ogg, audio/opus, audio/aac
   Extra: EXTRA_STREAM = content://com.whatsapp.provider.media/...

2. ShareActivity.onCreate()
   → intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
   → viewModel.transcribe(uri)

3. AudioLoader (IO thread)
   → contentResolver.openInputStream(uri)
   → copy to File(cacheDir, "audio_input.tmp")
   → close stream
   → return File

4. AudioPreprocessor (Default thread)
   → MediaExtractor.setDataSource(file.path)
   → find audio track, get MediaFormat
   → MediaCodec.createDecoderByType(mime)
   → configure with KEY_PCM_ENCODING = ENCODING_PCM_FLOAT
   → drain: queueInputBuffer() + dequeueOutputBuffer() loop
   → accumulate ByteBuffer output into FloatArray
   → downmix to mono if channel count > 1
   → resample from native rate (typically 48000 Hz) to 16000 Hz
   → return FloatArray

5. WhisperEngine.transcribe() (Default thread)
   → JNI call: nativeTranscribe(ctxPtr, floatArray, "auto")
   → whisper_full() runs transformer inference
   → collect segment texts
   → return concatenated String

6. TranscriptOutputHandler (Main thread, via withContext)
   → ClipboardManager.setPrimaryClip(ClipData.newPlainText(...))
   → Toast.makeText(...).show()
   → activity.finish()
```

---

## Share Target Setup (AndroidManifest.xml)

```xml
<activity
    android:name=".ShareActivity"
    android:theme="@style/Theme.TransChat.Translucent"
    android:exported="true"
    android:taskAffinity=""
    android:excludeFromRecents="true">

    <!-- WhatsApp voice messages: .opus files with audio/ogg MIME -->
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="audio/*" />
    </intent-filter>

    <!-- Fallback: some apps share as application/octet-stream -->
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="application/octet-stream" />
    </intent-filter>
</activity>
```

Key manifest attributes:
- `android:taskAffinity=""` — runs in its own task, does not steal the caller's back stack
- `android:excludeFromRecents="true"` — does not pollute the recents screen
- `android:exported="true"` — required for other apps to reach it
- No `FOREGROUND_SERVICE` permission needed for MVP (runs in coroutine within Activity lifecycle)

---

## Background Processing Decision: Coroutines in Activity Scope (MVP)

**Decision: Use coroutines scoped to the Activity (lifecycleScope), not WorkManager.**

Rationale:

| Option | Verdict | Reason |
|--------|---------|--------|
| `lifecycleScope` + coroutines | **USE for MVP** | Simple, no extra permissions, works while Activity is alive. Transcription of a 1-3 minute voice message takes 5-30 seconds — well within Activity lifetime. |
| WorkManager long-running worker | Defer | Correct for batch/background jobs. Adds foreground service notification requirement (mandatory on Android 14+). Overkill when user is waiting for result. |
| Foreground Service (direct) | Avoid | Requires `FOREGROUND_SERVICE` permission, persistent notification, more complex lifecycle. User-visible cost higher than benefit for MVP. |
| `Dispatchers.Default` coroutine | **USE** | Whisper inference is CPU-bound, not IO-bound. `Default` dispatcher uses a thread pool sized to CPU count. |

**Upgrade path:** If future use cases need background-without-UI (e.g., batch transcription of multiple messages), migrate `WhisperEngine` to a `CoroutineWorker` with `setForeground()`. The engine itself is already decoupled.

---

## WhisperEngine: Model Loading and Memory Management

### Loading Strategy: Lazy Singleton

```kotlin
object WhisperEngine {
    private var ctxPtr: Long = 0L
    private val lock = Mutex()

    suspend fun ensureLoaded(context: Context) {
        lock.withLock {
            if (ctxPtr == 0L) {
                ctxPtr = withContext(Dispatchers.IO) {
                    // Copy model from assets to files dir on first run
                    // (Assets cannot be mmap'd directly on all Android versions)
                    val modelFile = ensureModelExtracted(context)
                    nativeLoadModel(modelFile.absolutePath)
                }
            }
        }
    }

    suspend fun transcribe(audio: FloatArray, lang: String = "auto"): String =
        withContext(Dispatchers.Default) {
            nativeTranscribe(ctxPtr, audio, lang)
        }

    // Called in Application.onTrimMemory(TRIM_MEMORY_CRITICAL)
    fun release() {
        if (ctxPtr != 0L) {
            nativeFreeModel(ctxPtr)
            ctxPtr = 0L
        }
    }

    private external fun nativeLoadModel(path: String): Long
    private external fun nativeTranscribe(ctx: Long, audio: FloatArray, lang: String): String
    private external fun nativeFreeModel(ctx: Long)
}
```

### Model Extraction Strategy

The 244 MB model file must not be bundled as a compressed asset (Android compresses unknown extensions). Options:

1. **Preferred:** Place model in `assets/` with `.bin` extension. Declare it in `build.gradle` as `aaptOptions { noCompress "bin" }` so it stays uncompressed and can be memory-mapped by whisper.cpp.
2. **Alternative:** Copy from assets to `Context.filesDir` on first launch. Slower first-start (~5 seconds), but simpler and guaranteed to work across all Android versions. Cache check: `if (!modelFile.exists()) copyFromAssets()`.

### Memory Budget

| Component | Estimated RAM |
|-----------|---------------|
| Whisper small GGML model weights | ~244 MB |
| GGML computation buffers (KV cache, activations) | ~80-120 MB |
| Audio PCM float array (60s @ 16kHz) | ~3.8 MB |
| Android overhead (ART, Activity, etc.) | ~50-80 MB |
| **Total peak** | **~380-450 MB** |

Mid-range devices (3-4 GB RAM) can handle this. Devices with 2 GB RAM are risky — the Out-Of-Memory killer may terminate the process if background pressure is high. The 244 MB model sits at the edge of what is safely usable without quantization on low-end hardware.

**Memory pressure handling:**
- Override `Application.onTrimMemory()`: call `WhisperEngine.release()` on `TRIM_MEMORY_RUNNING_CRITICAL` or `TRIM_MEMORY_COMPLETE`.
- Model will be reloaded on next transcription request (adds ~2-5 second penalty).
- Do NOT hold the model in memory during idle periods if the process survives long-term.

---

## Audio Preprocessing Pipeline Detail

### Format Reality: WhatsApp and Telegram

| App | Container | Codec | Extension | MIME type shared |
|-----|-----------|-------|-----------|-----------------|
| WhatsApp | OGG | Opus | .opus | `audio/ogg` or `audio/mpeg` |
| Telegram | OGG | Opus | .ogg | `audio/ogg` |
| Both (forwarded) | varies | varies | varies | `audio/*` |

Native Android MediaCodec supports OGG/Opus from API level 21+. No external decoder library needed.

### Resampling Implementation

Android does not provide a high-quality public resampler API for Kotlin/Java code. Options:

1. **Simple linear interpolation (pure Kotlin):** Fast to implement, ~3-5 dB SNR loss. Acceptable for speech. Recommended for MVP.
2. **Oboe (NDK) with built-in resampler:** High quality, but adds NDK dependency and complexity.
3. **whisper.cpp built-in resampler:** whisper.cpp's `whisper_pcm_to_mel()` expects 16kHz input — it does not resample. You must provide 16kHz.

```kotlin
fun resampleToWhisper(input: FloatArray, inputRate: Int): FloatArray {
    if (inputRate == 16000) return input
    val ratio = inputRate.toDouble() / 16000.0
    val outputLength = (input.size / ratio).toInt()
    return FloatArray(outputLength) { i ->
        val srcIdx = i * ratio
        val lo = srcIdx.toInt().coerceAtMost(input.size - 1)
        val hi = (lo + 1).coerceAtMost(input.size - 1)
        val frac = srcIdx - lo
        (input[lo] * (1.0 - frac) + input[hi] * frac).toFloat()
    }
}
```

### MediaCodec Async vs Sync Mode

Use **synchronous mode** for MVP:
- Simpler state machine, easier to reason about in coroutines
- Sufficient for pre-recorded files (not real-time streaming)
- Drain pattern: loop `dequeueOutputBuffer(timeout=10_000)` until `BUFFER_FLAG_END_OF_STREAM`

Switch to async callback API only if decoding becomes a bottleneck (unlikely for voice messages under 5 minutes).

---

## CMake / JNI Build Structure

```
app/
  src/
    main/
      cpp/
        CMakeLists.txt        ← builds whisper.cpp + ggml + JNI bridge
        whisper_jni.cpp       ← JNI function implementations
      jniLibs/                ← pre-built .so if using AAR approach
      assets/
        models/
          ggml-small.bin      ← 244MB model (noCompress in build.gradle)
      kotlin/
        com/transchat/
          ShareActivity.kt
          TranscriptionViewModel.kt
          WhisperEngine.kt
          AudioLoader.kt
          AudioPreprocessor.kt
          TranscriptOutputHandler.kt
```

**CMakeLists.txt structure:**
```cmake
cmake_minimum_required(VERSION 3.22.1)
project(transchat)

# Include whisper.cpp as subdirectory (git submodule)
add_subdirectory(whisper.cpp)

add_library(transchat-jni SHARED whisper_jni.cpp)
target_link_libraries(transchat-jni whisper android log)
```

**ABI targets:** Build for `arm64-v8a` (primary) and `armeabi-v7a` (legacy 32-bit). Use:
```gradle
android {
    defaultConfig {
        ndk { abiFilters "arm64-v8a", "armeabi-v7a" }
    }
}
```

---

## Suggested Build Order (Dependency-Driven)

Build in this sequence — each step is independently testable before moving to the next.

### Step 1: JNI Foundation
**Goal:** Confirm whisper.cpp compiles and loads on device.
- Add whisper.cpp as git submodule
- Write minimal CMakeLists.txt
- Write stub `whisper_jni.cpp` with `nativeLoadModel` returning a dummy Long
- Write `WhisperEngine.kt` calling the stub
- Write unit test: load model, confirm no crash

**Milestone:** App starts, loads model, logs memory before/after load.

### Step 2: Share Intent Entry Point
**Goal:** Receive a URI from WhatsApp/Telegram without crashing.
- `ShareActivity` with intent filter in manifest
- Extract URI from `EXTRA_STREAM`
- Log URI to confirm receipt
- Show a simple ProgressBar Dialog

**Milestone:** Share a voice message from WhatsApp → TransChat receives the URI.

### Step 3: Audio Acquisition (URI → File)
**Goal:** Get the raw audio bytes into a file the app owns.
- `AudioLoader.kt` using `ContentResolver.openInputStream`
- Copy to `cacheDir/audio_tmp.bin`
- Log file size and format

**Milestone:** File lands in cache, bytes match source.

### Step 4: Audio Preprocessing (File → FloatArray)
**Goal:** Produce 16kHz mono float32 PCM that whisper.cpp can consume.
- `AudioPreprocessor.kt` with MediaExtractor + MediaCodec pipeline
- Mono downmix
- Linear resample to 16kHz
- Write decoded PCM to a WAV file for offline verification

**Milestone:** Play decoded WAV in a media player — it sounds like the original voice message.

### Step 5: End-to-End Transcription
**Goal:** Wire Steps 1-4 together and call `whisper_full`.
- `WhisperEngine.transcribe()` calling real JNI
- `TranscriptionViewModel` orchestrating the pipeline
- Log transcript to Logcat

**Milestone:** Transcription appears in Logcat for a test voice message.

### Step 6: Output + UX Polish
**Goal:** Deliver the transcript to the user.
- `ClipboardManager.setPrimaryClip()`
- Toast confirmation message
- Error states (file too large, decode failure, empty transcript)
- Loading indicator in `ShareActivity`

**Milestone:** Full MVP flow works end-to-end.

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Storing Model in Compressed Assets
**What:** Adding the `.bin` file to assets without `aaptOptions { noCompress }`.
**Why bad:** Android APK tool compresses unknown file types. Compressed assets cannot be memory-mapped, so whisper.cpp must decompress the entire 244 MB into RAM before loading, doubling peak RAM usage (244 MB buffer + 244 MB loaded).
**Instead:** Set `aaptOptions { noCompress "bin" }` in `build.gradle` and use `AAssetManager_open()` with `AASSET_MODE_BUFFER`.

### Anti-Pattern 2: Running Inference on the Main Thread
**What:** Calling `WhisperEngine.transcribe()` from `onCreate()` or any UI callback.
**Why bad:** ANR (Application Not Responding) dialog appears after 5 seconds. Whisper small takes 10-60 seconds on mid-range hardware.
**Instead:** Always call via `viewModelScope.launch(Dispatchers.Default)`.

### Anti-Pattern 3: Reading Content URI Directly in MediaExtractor
**What:** Passing the `content://` URI from `EXTRA_STREAM` directly to `MediaExtractor.setDataSource(uri)`.
**Why bad:** WhatsApp and Telegram URI permissions are granted only for the duration of the intent delivery. By the time a background coroutine accesses it, the permission may be revoked (SecurityException). Also, content:// URIs are not always seekable, which MediaExtractor requires.
**Instead:** Copy bytes to a private cache file first (Step 3 above), then pass the file path.

### Anti-Pattern 4: Creating New WhisperEngine Per Transcription
**What:** Calling `whisper_init()` and `whisper_free()` on every transcription.
**Why bad:** Loading the 244 MB model takes 2-8 seconds and causes a memory spike. Repeated alloc/free fragments the native heap.
**Instead:** Lazy singleton — load once, reuse, release only on memory pressure.

### Anti-Pattern 5: Catching All Exceptions Silently
**What:** `try { ... } catch (e: Exception) { /* ignore */ }` anywhere in the pipeline.
**Why bad:** Audio format mismatch, decode failures, and OOM all produce silent failures that appear to the user as infinite loading.
**Instead:** Model a sealed `TranscriptionResult` class with `Success`, `DecodeError`, `InferenceError`, and `OomError` states. Propagate errors to the ViewModel and show actionable messages.

---

## Scalability Considerations

This is a single-user device app — "scalability" means performance across diverse device tiers.

| Concern | Low-end (2GB RAM) | Mid-range (4GB) | High-end (8GB+) |
|---------|-------------------|-----------------|-----------------|
| Model fits in RAM | Marginal — OOM possible | Safe | Comfortable |
| Inference speed (30s audio) | 30-90 seconds | 15-30 seconds | 5-15 seconds |
| Mitigation | Offer quantized tiny model | Whisper small | Whisper small/medium |
| Thread count | 2 threads | 4 threads | 4-8 threads |

**CPU thread tuning:**
```kotlin
val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
```
More threads help up to ~4. Beyond that, coordination overhead dominates for Whisper.

---

## Sources

- [Android Developers: Receive simple data from other apps](https://developer.android.com/training/sharing/receive) — Intent filter setup, EXTRA_STREAM handling (HIGH confidence)
- [Android Developers: WorkManager long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) — CoroutineWorker setForeground() API (HIGH confidence)
- [ggml-org/whisper.cpp GitHub](https://github.com/ggml-org/whisper.cpp) — Android example, JNI architecture, whisper_full C API (HIGH confidence)
- [ggml-org/whisper.cpp Discussion #283](https://github.com/ggml-org/whisper.cpp/discussions/283) — Android JNI function signatures, device performance benchmarks (MEDIUM confidence)
- [mikeesto/whispercpp-android](https://github.com/mikeesto/whispercpp-android) — Reference Android implementation (MEDIUM confidence)
- [Android Developers: MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) — PCM float output, decode pipeline (HIGH confidence)
- [Android Developers: Sampling Audio (NDK)](https://developer.android.com/ndk/guides/audio/sampling-audio) — Sample rate handling (HIGH confidence)
- [Android Developers: Memory management](https://developer.android.com/topic/performance/memory-management) — onTrimMemory, paging, RAM management (HIGH confidence)
- [openai/whisper Discussion #11](https://github.com/openai/whisper/discussions/11) — Mobile inference feasibility, model size constraints (MEDIUM confidence)
- [openai/whisper Discussion #506](https://github.com/openai/whisper/discussions/506) — TFLite Android implementation, memory on 2GB devices (MEDIUM confidence)
- [Building LocalMind: Whisper on Android via JNI (Jan 2026)](https://medium.com/@mohammedrazachandwala/building-localmind-how-i-ported-openais-whisper-to-android-using-jni-and-kotlin-575dddd38fdc) — JNI memory management patterns, asset manager approach (MEDIUM confidence)
- [WhatsApp/Telegram audio format research](https://www.ticktechtold.com/whatsapp-supported-file-format-extension/) — OGG/Opus MIME types from messaging apps (MEDIUM confidence)
