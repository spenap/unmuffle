# Technology Stack

**Project:** TransChat - Android Audio Transcription App
**Researched:** 2026-02-27
**Research Mode:** Ecosystem

---

## Recommended Stack

### Inference Runtime

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| whisper.cpp | v1.7.x (latest stable: v1.8.3, Jan 2025) | On-device Whisper inference | CPU-only GGML-native runtime; no Maven/AAR needed; official Android JNI example in repo; directly consumes gguf/ggml model files without conversion |

**Why whisper.cpp and not alternatives:**

- whisper.cpp is the **reference C/C++ implementation** of OpenAI Whisper; all other runtimes ultimately convert from OpenAI's model weights into a different format, introducing conversion complexity or accuracy loss.
- The official `examples/whisper.android` directory ships a working Kotlin app; it is the closest thing to an "official Android sample."
- It uses the GGML format directly; models can be downloaded as `.bin` files from Hugging Face (`ggerganov/whisper.cpp`) without any conversion toolchain.
- whisper.cpp has GPU acceleration on **other** platforms (CUDA, Metal) but on Android CPU-only is the practical baseline; GPU via OpenCL/CLBlast is theoretically possible but produces incorrect results in several community reports (see PITFALLS.md).

### Core Framework

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Kotlin | 2.x (stable) | App language | Modern Android standard; coroutines native; JNI from Kotlin is identical to Java |
| Android SDK | minSdk 26 (Android 8.0), targetSdk 35 | Platform | API 26+ gets reliable native Opus decoding in MediaCodec; most modern Android devices exceed this |
| Android NDK | r27 LTS | Compile whisper.cpp C++ | Required for CMake/JNI; r27 is the current long-term support NDK release |
| CMake | 3.22+ | Native build system | whisper.cpp uses CMake; AGP integrates via `externalNativeBuild` |

### Audio Decoding

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Android MediaExtractor + MediaCodec | Platform (API 21+) | Decode .ogg/.opus files to PCM | Native, no dependency; Opus-in-Ogg supported natively since API 21; zero added APK size |
| Android MediaExtractor resampling (manual) | Platform | Resample to 16kHz mono PCM Float | whisper.cpp expects 16kHz mono float32; MediaCodec can output `ENCODING_PCM_FLOAT`; linear resampling in Kotlin is trivial for the 3:1 ratio (48kHz→16kHz) |

**Audio format reality for WhatsApp/Telegram:**
- WhatsApp voice notes: Opus codec in `.opus` container (MIME: `audio/ogg; codecs=opus`)
- Telegram voice notes: Opus codec in `.ogg` container
- Both decode identically; the container is Ogg, the codec is Opus

**Opus support on Android:**
- **API 21+**: Ogg/Opus decoding supported natively via `MediaExtractor` + `MediaCodec`
- **API 29+**: `.opus` file extension is recognized (API 28 and below need `.ogg` extension)
- Confirmed: `MediaExtractor` handles Ogg containers with Opus audio on API 21+ without any third-party library

### Background Work

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Kotlin Coroutines | 1.8.x | Async inference on background thread | Inference runs on `Dispatchers.Default`; lightweight; cancellable if Activity dies |
| Android Foreground Service (`mediaProcessing` type) | API 29+ | Hold CPU lock during transcription | Android 14+ requires declaring `foregroundServiceType`; `mediaProcessing` is the correct type for "processing a media file" — no microphone access required; allows up to 6 hours runtime |

**Service type declaration (Android 14+ mandatory):**
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />

<service
    android:name=".TranscriptionService"
    android:foregroundServiceType="mediaProcessing"
    android:exported="false" />
```

### Model

| Attribute | Value | Notes |
|-----------|-------|-------|
| Model | `ggml-small-q5_1.bin` | Multilingual; 190 MB on disk (HuggingFace: `ggerganov/whisper.cpp`) |
| Format | GGML binary | Consumed directly by whisper.cpp JNI |
| Distribution | Download-on-first-use | See "Model Distribution" section below |
| Fallback | `ggml-base.bin` (142 MB unquantized) | Smaller alternative if 190 MB is too large for initial testing |

**Model size facts (sourced from HuggingFace `ggerganov/whisper.cpp`, verified 2026-02-27):**

| Model | Size |
|-------|------|
| `ggml-small.bin` (full precision) | 488 MB |
| `ggml-small-q8_0.bin` | 264 MB |
| `ggml-small-q5_1.bin` | 190 MB |
| `ggml-base.bin` | 142 MB |
| `ggml-tiny.bin` | 75 MB |

`q5_1` at 190 MB is the best balance: multilingual accuracy is maintained at Q5 precision; the ~244 MB target stated in requirements is achievable if a future model variant arrives, but `q5_1` at 190 MB is the current smallest-with-acceptable-accuracy option.

### Model Distribution Strategy

**Recommendation: Download-on-first-use, stored in `filesDir`**

| Strategy | Pros | Cons | Verdict |
|----------|------|------|---------|
| Bundle in APK/AAB | Instant availability | APK > 200 MB violates Play Store limits; 190 MB model alone exceeds the uncompressed APK limit | NOT viable |
| Play Asset Delivery (install-time) | Seamless install | Requires Play Store; 190 MB is within PAD limits; complex setup for a simple app | Overkill for MVP |
| Download-on-first-use to `filesDir` | Simple; sideload-friendly; works outside Play Store | User sees a download progress screen on first use | **Recommended for MVP** |

**Implementation:** On first launch, check for `context.filesDir/models/ggml-small-q5_1.bin`. If absent, show a one-time download screen (progress bar) fetching from HuggingFace or a self-hosted URL. Use Android `DownloadManager` or `OkHttp` + coroutine. Store checksum (SHA256) to verify integrity.

For F-Droid distribution, self-hosting the model on a reliable CDN is required since HuggingFace URLs may be subject to rate limits.

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `androidx.core:core-ktx` | 1.13.x | Kotlin extensions for Android framework | Always |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.x | `lifecycleScope` for coroutine-aware lifecycle | Share target Activity scope |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.8.x | Coroutines on Android MainThread dispatcher | Always |
| `androidx.activity:activity-ktx` | 1.9.x | `ComponentActivity` result APIs | Share intent handling |

No networking libraries needed at runtime (privacy requirement: no network during transcription). OkHttp or similar only in a "model download" module that is optional.

---

## Alternatives Considered and Rejected

### Alternative Runtimes

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| Inference | whisper.cpp JNI | sherpa-onnx | Requires converting Whisper to ONNX format; sherpa-onnx's Maven artifact (`com.bihe0832.android:lib-sherpa-onnx`) is an unofficial third-party wrapper, not from the k2-fsa team; higher CER vs faster-whisper in some tests; added complexity for no benefit given whisper.cpp already has a Kotlin Android example |
| Inference | whisper.cpp JNI | TFLite (vilassn/whisper_android) | TFLite models require conversion toolchain; the vilassn/whisper_android project was last meaningfully updated ~2023; accuracy of quantized TFLite small model is inferior to ggml format |
| Inference | whisper.cpp JNI | WhisperKitAndroid | Deprecated as of 2024; argmax-sdk-kotlin rewrite not yet production-ready; Qualcomm-specific |
| Inference | whisper.cpp JNI | MediaPipe | MediaPipe's Whisper integration targets on-device LLMs differently; no well-documented Whisper path; larger framework overhead |
| Inference | whisper.cpp JNI | ONNX Runtime directly | Requires exporting Whisper to ONNX (encoder + decoder separately); significantly more complex integration; no net performance benefit on Android CPU vs ggml |
| Audio decode | MediaExtractor | ExoPlayer/Media3 | Media3 can decode Opus but adds ~2 MB of library overhead; for a transcription app that never plays audio, full ExoPlayer is overkill; raw `MediaExtractor` + `MediaCodec` is 0-dependency |
| Audio decode | MediaExtractor | FFmpeg Android | GPL-3 license implications; adds 10-30 MB to APK; unnecessary given platform-native Opus support on API 21+ |
| Audio decode | MediaExtractor | android-opus-codec JNI | JNI Opus library bypasses MediaCodec; redundant for API 21+ devices; another .so to ship |
| Background work | Foreground Service | WorkManager | WorkManager is designed for deferrable, not-time-critical work; transcription is user-initiated with immediate result expectation; WorkManager adds scheduling latency; sharing intent must complete before Android kills the receive context |

---

## JNI Bridge Architecture

whisper.cpp does not have an official Maven/AAR release. The integration approach is:

1. Include the `whisper.cpp` source (or a Git submodule) in the Android project at `app/src/main/cpp/`
2. Write a `CMakeLists.txt` that compiles `whisper.cpp` + `ggml` sources for Android targets (`arm64-v8a` is the primary; `armeabi-v7a` optional for old devices)
3. Expose a `LibWhisper.kt` Kotlin object backed by `@JvmStatic external fun` declarations
4. The JNI `.c` wrapper matches the pattern in `examples/whisper.android/lib/src/main/java/com/whispercpp/whisper/WhisperLib.kt`

**Key build.gradle snippet:**
```kotlin
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }  // x86_64 for emulator testing
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
```

**Key CMake flags for Android:**
```cmake
set(CMAKE_ANDROID_STL_TYPE c++_shared)
# Do NOT enable GGML_USE_CLBLAST — OpenCL produces incorrect results on many Android devices
# Do NOT enable GGML_USE_VULKAN — not supported in whisper.cpp Android builds as of v1.8.3
```

---

## Audio Pipeline

```
Share Intent URI (content://)
        |
        v
MediaExtractor.setDataSource(fd)      # Open file via ContentResolver
        |
        v
MediaCodec decoder (Opus)             # Platform native, no dependency
        |  ENCODING_PCM_FLOAT
        v
FloatArray at native sample rate      # Typically 48000 Hz
        |
        v
Linear resample to 16000 Hz           # Kotlin, pure JVM, no library needed
        |
        v
whisper_full() via JNI                # C++ inference on Dispatchers.Default
        |
        v
String transcript
        |
        v
ClipboardManager.setPrimaryClip()     # Copy to clipboard
Toast.makeText()                      # Notify user
```

**Sample rate note:** WhatsApp and Telegram encode voice messages at 16kHz internally (they already use Opus at 16kHz for speech), so resampling may be a no-op in many real-world cases. Confirm by checking `MediaFormat.KEY_SAMPLE_RATE` at runtime before resampling.

---

## Installation Reference

**app/build.gradle.kts:**
```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // No whisper.cpp Gradle dep — built via CMake externalNativeBuild
}
```

No additional repositories required (all deps on `google()` and `mavenCentral()`).

---

## Confidence Assessment

| Area | Confidence | Source | Notes |
|------|------------|--------|-------|
| whisper.cpp as runtime | HIGH | Official GitHub (ggml-org/whisper.cpp), v1.8.3 release notes, official Android example | Confirmed active maintenance; Android JNI example ships in repo |
| Model sizes | HIGH | HuggingFace ggerganov/whisper.cpp repository, verified 2026-02-27 | Direct file listing from HF |
| Native Opus decode (API 21+) | HIGH | Official Android docs (developer.android.com/guide/topics/media/media-formats) | Explicit API level table in official docs |
| mediaProcessing FGS type | HIGH | Official Android docs (developer.android.com/develop/background-work/services/fgs/service-types) | Confirmed correct type for file processing |
| whisper.cpp GPU on Android (rejection) | HIGH | GitHub issues #1070, #1140, #2413; community reports | Multiple independent reports of OpenCL producing incorrect results; no NNAPI/Vulkan in Android whisper.cpp as of v1.8.3 |
| sherpa-onnx rejection | MEDIUM | Maven artifact from third-party wrapper; CER regression issue #2900 | Official k2-fsa does not publish an Android AAR to Maven; accuracy regression noted in issues |
| Download-on-first-use strategy | MEDIUM | Play Store size docs; whisperIME app pattern (github.com/woheller69/whisperIME) | whisperIME uses identical pattern; Play Store limits confirm APK bundling not viable |
| Inference speed (small model, Android) | LOW | GitHub issue #1070 (community report: ~30s for short audio on some devices) | No official Android benchmark; "release build" significantly faster than debug; real-world varies by device CPU tier |

---

## Sources

- [whisper.cpp official repository (ggml-org/whisper.cpp)](https://github.com/ggml-org/whisper.cpp)
- [whisper.cpp v1.8.3 release (Jan 15, 2025)](https://github.com/ggml-org/whisper.cpp/releases/tag/v1.8.3)
- [whisper.cpp Android inference slow issue #1070](https://github.com/ggml-org/whisper.cpp/issues/1070)
- [whisper.cpp LiteRT/Android GPU request #2413](https://github.com/ggml-org/whisper.cpp/issues/2413)
- [whisper.cpp OpenCL issue #1140](https://github.com/ggerganov/whisper.cpp/issues/1140)
- [ggml model files on HuggingFace (ggerganov/whisper.cpp)](https://huggingface.co/ggerganov/whisper.cpp/tree/main)
- [Android supported media formats - Opus API level table](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [Android media formats guide](https://developer.android.com/guide/topics/media/media-formats)
- [Android foreground service types (mediaProcessing)](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android share intent receive guide](https://developer.android.com/training/sharing/receive)
- [sherpa-onnx repository (k2-fsa)](https://github.com/k2-fsa/sherpa-onnx)
- [sherpa-onnx Whisper CER regression issue #2900](https://github.com/k2-fsa/sherpa-onnx/issues/2900)
- [vilassn/whisper_android (TFLite, last updated 2023)](https://github.com/vilassn/whisper_android)
- [WhisperKitAndroid (deprecated)](https://github.com/argmaxinc/WhisperKitAndroid)
- [whisperIME Android app (download-on-first-use pattern)](https://github.com/woheller69/whisperIME)
- [mikeesto/whispercpp-android (community Android whisper.cpp example)](https://github.com/mikeesto/whispercpp-android)
- [Building LocalMind: whisper.cpp JNI on Android (Medium, Jan 2026)](https://medium.com/@mohammedrazachandwala/building-localmind-how-i-ported-openais-whisper-to-android-using-jni-and-kotlin-575dddd38fdc)
- [Android sampling audio NDK guide](https://developer.android.com/ndk/guides/audio/sampling-audio)
- [MediaExtractor to PCM guide](https://imnotyourson.com/enhance-poor-performance-of-decoding-audio-with-mediaextractor-and-mediacodec-to-pcm/)
