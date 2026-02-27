# Phase 1 Research: Whisper Engine and Model Bootstrap

**Phase:** 01-whisper-engine-model-bootstrap
**Researched:** 2026-02-27
**Overall confidence:** HIGH (core JNI API verified against official source; CMakeLists.txt verified against official repo; SHA256 verified from HuggingFace; DownloadManager API verified from official Android docs)

---

## Research Questions Answered

1. whisper.cpp Android example — exact build steps, CMakeLists.txt structure, JNI API surface
2. Android project from scratch with NDK/CMake and a git submodule
3. Exact HuggingFace URL for ggml-small-q5_1.bin and its SHA256 hash
4. Android DownloadManager vs OkHttp vs HttpURLConnection for large file downloads
5. Minimal download Activity in Kotlin with progress
6. whisper.cpp JNI function signatures — what parameters whisper_full() needs
7. How to generate test PCM data for verification

---

## 1. whisper.cpp Android Example — Build Steps and CMakeLists.txt

**Source:** Official whisper.cpp repo, `examples/whisper.android` (verified 2026-02-27)
**Confidence:** HIGH

### Project Layout

The official Android example lives at `examples/whisper.android/` within the whisper.cpp repo. Key structure:

```
examples/whisper.android/
  app/                         # Demo app module (not relevant for TransChat)
  lib/                         # Library module — this is what to copy/reference
    build.gradle
    src/main/
      java/com/whispercpp/whisper/
        LibWhisper.kt           # JNI bridge (see Section 2)
        WhisperCpuConfig.kt     # Thread count helpers
      jni/whisper/
        CMakeLists.txt          # Native build config
        jni.c                   # C wrapper calling whisper.cpp API
```

**Critical note:** The example is designed to be run from within the whisper.cpp tree. It uses a relative path `../../../../../../..` from the CMakeLists.txt to reach the whisper.cpp root. When using a git submodule, this path must be adjusted to point at your submodule root.

### CMakeLists.txt (Verified from Official Repo)

```cmake
cmake_minimum_required(VERSION 3.10)

project(whisper.cpp)

set(CMAKE_CXX_STANDARD 17)
# Points to whisper.cpp root — adjust this for submodule placement
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../../../..)

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
)

find_library(LOG_LIB log)

include(FetchContent)

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        ${SOURCE_FILES}
    )

    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
        set(GGML_COMPILE_OPTIONS -mfpu=neon-vfpv4)
    endif ()

    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O3)
        target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
        target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)
        target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
        target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
        target_link_options(${target_name} PRIVATE -flto)
    endif ()

    FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
    FetchContent_MakeAvailable(ggml)
    target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)
endfunction()

# Build architecture-optimized variants for arm64 and armeabi-v7a
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif ()

build_library("whisper")  # Default fallback target

include_directories(${WHISPER_LIB_DIR})
include_directories(${WHISPER_LIB_DIR}/src)
include_directories(${WHISPER_LIB_DIR}/include)
include_directories(${WHISPER_LIB_DIR}/ggml/include)
include_directories(${WHISPER_LIB_DIR}/ggml/src)
include_directories(${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)  # Required since Dec 2024 PR #2624
```

**Key decisions baked into this CMakeLists.txt:**

- Builds three `.so` variants: `libwhisper.so` (baseline), `libwhisper_v8fp16_va.so` (arm64 with FP16), `libwhisper_vfpv4.so` (armeabi-v7a with VFPv4). `LibWhisper.kt` detects CPU features at runtime and loads the best variant.
- `GGML_USE_CPU` is the only backend enabled — no OpenCL, no Vulkan (correct for TransChat arm64-only build).
- `FetchContent_Declare(ggml SOURCE_DIR ...)` pulls GGML from the whisper.cpp submodule, not from the internet.
- Release builds get `-O3` — this is what makes inference fast. Debug builds will be 2-10x slower.
- `ggml/src/ggml-cpu` include was added in PR #2624 (December 2024) to fix a "ggml-cpu-impl.h file not found" error. This include must be present.

**For TransChat (arm64-v8a only):** Only the `whisper_v8fp16_va` and `whisper` targets are needed. The `whisper_vfpv4` target can be omitted since armeabi-v7a is out of scope.

**16KB page size flag to add (required for Play Store, Pixel 9+):**
```cmake
target_link_options(whisper PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(whisper_v8fp16_va PRIVATE "-Wl,-z,max-page-size=16384")
```

---

## 2. JNI API Surface — Kotlin Side and C Side

**Source:** Official `LibWhisper.kt` from `examples/whisper.android` (verified 2026-02-27)
**Confidence:** HIGH

### Kotlin External Function Declarations (LibWhisper.kt)

The official example uses a private class `WhisperLib` with a companion object containing all `external fun` declarations. The public API is exposed through `WhisperContext`:

```kotlin
// Private class — only WhisperContext calls these
private class WhisperLib {
    companion object {
        init {
            // CPU feature detection chooses which .so to load:
            // - arm64 with fphp → libwhisper_v8fp16_va.so
            // - armeabi-v7a with vfpv4 → libwhisper_vfpv4.so
            // - otherwise → libwhisper.so
            System.loadLibrary("whisper")
        }

        external fun initContextFromInputStream(inputStream: InputStream): Long
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String): Long          // FILE PATH — use this one
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long  // timestamp start
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long  // timestamp end
        external fun getSystemInfo(): String
        external fun benchMemcpy(nthread: Int): String
        external fun benchGgmlMulMat(nthread: Int): String
    }
}
```

### Public WhisperContext API

```kotlin
class WhisperContext private constructor(private var ptr: Long) {
    // Single-threaded executor prevents concurrent access — whisper.cpp is not thread-safe
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    suspend fun transcribeData(data: FloatArray, printTimestamp: Boolean = true): String

    suspend fun release()  // Frees native memory; call when done

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext  // USE THIS — file path, not bytes
        fun createContextFromInputStream(stream: InputStream): WhisperContext  // OOM risk on large models
        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext
        fun getSystemInfo(): String
    }
}
```

**For TransChat, always use `WhisperContext.createContextFromFile(absolutePath)`.** The `createContextFromInputStream` path exists in the official example but causes OOM on 190MB models (see Pitfall 1 in PITFALLS.md).

### C JNI Function Signatures (jni.c)

The C file in the official example exports these functions (names constructed per JNI naming convention from the package `com.whispercpp.whisper` and class `WhisperLib$Companion`):

```c
// Initialize from file path — returns opaque pointer as jlong (0 on failure)
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
    JNIEnv *env, jobject thiz, jstring modelPathStr)
{
    const char *modelPath = env->GetStringUTFChars(modelPathStr, nullptr);
    struct whisper_context_params params = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(modelPath, params);
    env->ReleaseStringUTFChars(modelPathStr, modelPath);
    return (jlong)(intptr_t)ctx;
}

// Runs transcription; results available via getTextSegment* calls
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
    JNIEnv *env, jobject thiz, jlong contextPtr, jint numThreads, jfloatArray audioData)
{
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    jfloat *audioDataArr = env->GetFloatArrayElements(audioData, nullptr);
    jsize audioDataLength = env->GetArrayLength(audioData);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads        = numThreads;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.no_context       = true;
    // TransChat must add these (not in the stock example):
    params.no_timestamps    = true;   // prevents hallucination amplification
    params.no_speech_thold  = 0.6f;  // discard segments where model is unsure
    params.max_tokens       = 224;   // cap repetition loops

    whisper_full(ctx, params, audioDataArr, (int)audioDataLength);
    env->ReleaseFloatArrayElements(audioData, audioDataArr, JNI_ABORT);
}

// Query segment count after fullTranscribe
JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
    JNIEnv *env, jobject thiz, jlong contextPtr)
{
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    return whisper_full_n_segments(ctx);
}

// Get text of one segment (call for each index 0..count-1)
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
    JNIEnv *env, jobject thiz, jlong contextPtr, jint index)
{
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    const char *text = whisper_full_get_segment_text(ctx, index);
    return env->NewStringUTF(text);
}

// Free native memory
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
    JNIEnv *env, jobject thiz, jlong contextPtr)
{
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)contextPtr;
    whisper_free(ctx);
}
```

**The `00024` in function names is the JNI encoding for `$` (dollar sign), because `WhisperLib$Companion` is the actual class name.**

### whisper_full_params — Complete Field Reference (from whisper.h, verified)

The full struct from the official header:

```c
struct whisper_full_params {
    enum whisper_sampling_strategy strategy;  // WHISPER_SAMPLING_GREEDY or WHISPER_SAMPLING_BEAM_SEARCH
    int   n_threads;         // Number of CPU threads (use WhisperCpuConfig.preferredThreadCount)
    int   n_max_text_ctx;    // max tokens in text context, 0 = full
    int   offset_ms;         // start offset in ms
    int   duration_ms;       // audio duration to process in ms, 0 = all
    bool  translate;         // translate to English (false for multilingual)
    bool  no_context;        // do not use past transcription as initial prompt for next segment
    bool  no_timestamps;     // do not generate timestamps (SET TO TRUE — reduces hallucination)
    bool  single_segment;    // force single segment output (useful for short clips)
    bool  print_special;     // print special tokens (false)
    bool  print_progress;    // print progress to stdout (false on Android)
    bool  print_realtime;    // print results in real-time (false)
    bool  print_timestamps;  // print timestamps (false for TransChat)
    bool  token_timestamps;  // enable token-level timestamps
    float thold_pt;          // timestamp token probability threshold (~0.01)
    float thold_ptsum;       // timestamp token sum probability threshold (~0.01)
    int   max_len;           // max segment length in chars, 0 = no constraint
    bool  split_on_word;     // split on word boundaries if possible
    int   max_tokens;        // max output tokens per segment, 0 = no limit (SET TO 224 to cap loops)
    bool  debug_mode;        // false
    int   audio_ctx;         // overwrite the audio context size (0 = use default)
    bool  tdrz_enable;       // tinydiarize (false)
    const char *suppress_regex;  // suppress output matching this regex (null)
    const char *initial_prompt;  // initial prompt to condition decoder (null)
    bool  carry_initial_prompt;
    const whisper_token *prompt_tokens;
    int   prompt_n_tokens;
    const char *language;    // null = auto-detect, "en" = English, etc.
    bool  detect_language;   // auto-detect language (true when language is null)
    bool  suppress_blank;    // suppress blank outputs
    bool  suppress_nst;      // suppress non-speech tokens
    float temperature;       // decoder temperature (0.0 = greedy)
    float max_initial_ts;    // max initial timestamp (1.0)
    float length_penalty;    // n/a with greedy decoding
    float temperature_inc;   // temperature increment on fallback (0.2f)
    float entropy_thold;     // entropy threshold for fallback (2.4f)
    float logprob_thold;     // log probability threshold for fallback (-1.0f)
    float no_speech_thold;   // no-speech probability threshold (SET TO 0.6f)
    struct { int best_of; } greedy;          // best_of for greedy sampling (1-5)
    struct { int beam_size; float patience; } beam_search;  // for beam search

    // Callbacks (set to null for TransChat Phase 1):
    whisper_new_segment_callback new_segment_callback;  // called per new segment
    void *new_segment_callback_user_data;
    whisper_progress_callback progress_callback;  // progress 0-100
    void *progress_callback_user_data;
    // ... encoder_begin_callback, abort_callback, logits_filter_callback
    // ... grammar_rules, vad (built-in VAD support added recently)
};
```

**TransChat Phase 1 recommended param values:**

```c
struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
params.n_threads       = numThreads;    // from WhisperCpuConfig or min(4, cores)
params.print_progress  = false;
params.print_timestamps = false;
params.no_context      = true;         // each clip is independent
params.no_timestamps   = true;         // prevents hallucination amplification (Issue #1724)
params.no_speech_thold = 0.6f;         // discard near-silent segments
params.max_tokens      = 224;          // cap repetition loops (5 words/sec × 30 sec ÷ ~2 chars)
params.language        = nullptr;      // auto-detect (multilingual)
params.detect_language = true;
params.temperature     = 0.0f;         // greedy decoding
params.temperature_inc = 0.2f;         // fallback temperature increment
```

---

## 3. Model URL and SHA256

**Source:** HuggingFace `ggerganov/whisper.cpp` repository (verified 2026-02-27)
**Confidence:** HIGH

### Download URL

```
https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin
```

This URL is the "resolve" form that triggers a direct download (not a web page). HuggingFace may redirect to its CDN; the redirect must be followed.

### File Details

| Attribute | Value |
|-----------|-------|
| Filename | `ggml-small-q5_1.bin` |
| Size | 190 MB (199,819,264 bytes approximately) |
| SHA256 | `ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb` |
| Format | GGML binary (consumed directly by whisper.cpp) |
| Model | Whisper small, 5-bit quantized (q5_1) |
| Languages | 99 (multilingual) |

**SHA256 confidence note:** The hash `ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb` was retrieved directly from the HuggingFace file page metadata (2026-02-27). It should be verified against the actual downloaded file during Phase 1 implementation. HuggingFace does update models; pin to the commit hash (`main` may drift) if long-term reproducibility is required.

**HuggingFace rate limits:** The `resolve/main/` URL is subject to HuggingFace rate limiting for unauthenticated requests. For app distribution at scale, consider using a Cloudflare R2, GitHub Releases (max 2GB), or similar CDN as a mirror. For v1 development and personal use, the direct HuggingFace URL is adequate.

---

## 4. Download Strategy: DownloadManager vs OkHttp vs HttpURLConnection

**Confidence:** HIGH (verified against Android official docs and community patterns)

### Comparison

| Approach | Resumable | Background | WiFi-only | Progress | Verdict |
|----------|-----------|------------|-----------|----------|---------|
| Android `DownloadManager` | YES (built-in) | YES (system-managed) | YES (one call) | Polling only | **Recommended** |
| OkHttp + coroutine | Manual | Manual (FGS or foreground only) | Manual | `ResponseBody` streaming | More work, same result |
| `HttpURLConnection` | Manual | Manual | Manual | Manual | Most work, no benefit |

### DownloadManager — Recommended for This Phase

**Why:** System-managed, automatically resumes after network drop or app death, integrates with Android Download Manager notification, WiFi restriction is a single API call, and the download survives the Activity lifecycle without a Foreground Service.

**Limitation:** Progress tracking requires polling DownloadManager's content provider — there is no callback API. Use a `Flow` with a delay loop in a coroutine to poll every 500ms and update the UI.

**Key API (verified from official Android docs):**

```kotlin
// Enqueue download
val request = DownloadManager.Request(
        Uri.parse("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"))
    .setTitle("Whisper Model")
    .setDescription("Downloading transcription model (190 MB)")
    .setDestinationUri(Uri.fromFile(File(context.filesDir, "models/ggml-small-q5_1.bin")))
    .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)  // WiFi-only default
    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
val downloadId = downloadManager.enqueue(request)

// Poll progress using a coroutine flow
fun observeProgress(downloadId: Long): Flow<DownloadProgress> = flow {
    while (true) {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        cursor.use {
            if (it.moveToFirst()) {
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytesDownloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val bytesTotal = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                emit(DownloadProgress(status, bytesDownloaded, bytesTotal))
                if (status == DownloadManager.STATUS_SUCCESSFUL ||
                    status == DownloadManager.STATUS_FAILED) return@flow
            }
        }
        delay(500)
    }
}

// WiFi override for cellular: allow NETWORK_MOBILE in addition to NETWORK_WIFI
// Show a dialog first: "This file is 190MB. Continue on cellular data?"
```

**Restart on failure:** Per the CONTEXT.md decision "restart from scratch (delete partial file, re-download)". When DownloadManager reports `STATUS_FAILED`, delete the partial file at `destinationUri` and call `downloadManager.enqueue(request)` again. The partial file at the destination URI may or may not exist depending on the failure mode — always attempt delete before re-enqueue.

**WiFi warning dialog:** Check `ConnectivityManager.activeNetwork` and `NetworkCapabilities.hasTransport(TRANSPORT_CELLULAR)` before enqueueing. If cellular, show an AlertDialog warning before setting `NETWORK_WIFI or NETWORK_MOBILE`.

**INTERNET permission:** Required but has no `android:maxSdkVersion` — downloads happen before transcription, so this does not violate the "no network during transcription" privacy requirement.

### OkHttp — When to Use Instead

Use OkHttp if: (a) streaming progress callbacks are required during download, or (b) custom headers (HuggingFace auth token) are needed. For this project OkHttp is not needed for v1.

---

## 5. Model SHA256 Verification

**Confidence:** HIGH (standard Java MessageDigest, platform API)

```kotlin
// Verify downloaded model file integrity
fun File.sha256(): String {
    val md = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            md.update(buffer, 0, bytesRead)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

// In download completion handler:
val MODEL_SHA256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"

fun verifyModel(modelFile: File): Boolean {
    return modelFile.exists() && modelFile.sha256() == MODEL_SHA256
}
```

**Important:** Call `sha256()` before passing the file path to `WhisperContext.createContextFromFile()`. A corrupted model produces undefined behavior in native code — at best a crash, at worst a silent mis-transcription.

---

## 6. Download Activity — Minimal Kotlin Implementation Pattern

**Confidence:** HIGH (standard Android patterns)

The download screen is a standard `AppCompatActivity` with a `ViewModel` that owns the download state. Key design decisions:

```kotlin
// DownloadViewModel.kt
class DownloadViewModel(app: Application) : AndroidViewModel(app) {
    sealed class State {
        object Idle : State()
        data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : State()
        object Verifying : State()
        object Complete : State()
        data class Failed(val reason: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)

    fun startDownload() { /* enqueue DownloadManager, poll flow, emit states */ }
}

// DownloadActivity.kt layout elements:
// - TextView: explainer text ("190 MB · No account needed · Stays on device · Private")
// - ProgressBar (determinate, 0-100)
// - TextView: "X MB of 190 MB"
// - Button: "Download" / "Retry"
// - Optional cellular warning shown as AlertDialog before download starts
```

**Navigation contract:**
- If model already exists and SHA256 passes: skip DownloadActivity, go directly to "Ready" screen.
- Check at `Application.onCreate()` or in `MainActivity.onCreate()` before setting content view.
- On "Ready" screen: show instruction text ("Share a voice message from WhatsApp or Telegram to transcribe it").

```kotlin
// In MainActivity.onCreate() or LauncherActivity.onCreate():
val modelFile = File(filesDir, "models/ggml-small-q5_1.bin")
if (modelFile.exists() && verifyModel(modelFile)) {
    startActivity(Intent(this, ReadyActivity::class.java))
} else {
    startActivity(Intent(this, DownloadActivity::class.java))
}
finish()
```

**Model directory setup:**
```kotlin
val modelsDir = File(context.filesDir, "models")
modelsDir.mkdirs()  // create if not exists; filesDir itself always exists
val modelFile = File(modelsDir, "ggml-small-q5_1.bin")
```

---

## 7. Android Project Setup — NDK/CMake with Git Submodule

**Confidence:** HIGH (verified from official Android docs and community patterns)

### New Project Setup Steps

**Step 1: Create the Android project**

In Android Studio: File → New Project → Native C++ (selects the "Native C++" template that pre-configures CMake and NDK).

Or manually in `app/build.gradle.kts`:

```kotlin
android {
    namespace = "com.simonpena.transchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.simonpena.transchat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")  // arm64-only per CONTEXT.md decision
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

**Step 2: Add whisper.cpp as git submodule**

```bash
# From the project root (beside app/)
git init  # if not already a git repo
git submodule add https://github.com/ggml-org/whisper.cpp.git app/src/main/cpp/whisper.cpp
git submodule update --init --recursive

# Pin to specific tag for reproducibility
cd app/src/main/cpp/whisper.cpp
git checkout v1.7.4  # or latest stable tag
cd ../../../../..
git add .gitmodules app/src/main/cpp/whisper.cpp
git commit -m "Add whisper.cpp as submodule at v1.7.4"
```

**Step 3: CMakeLists.txt for TransChat**

Adapt the official CMakeLists.txt with corrected paths for the submodule placement:

```cmake
cmake_minimum_required(VERSION 3.22)
project(whisper_transchat)
set(CMAKE_CXX_STANDARD 17)

# Whisper.cpp submodule root (relative to this CMakeLists.txt)
set(WHISPER_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

set(SOURCE_FILES
    ${WHISPER_CPP_DIR}/src/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.cpp  # TransChat JNI wrapper
)

find_library(LOG_LIB log)
include(FetchContent)

function(build_library target_name)
    add_library(${target_name} SHARED ${SOURCE_FILES})
    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)

    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)
    endif ()

    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
        target_link_options(${target_name} PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -flto)
    endif ()

    # 16KB page size — required for Pixel 9+ and Play Store (Google Play enforcement)
    target_link_options(${target_name} PRIVATE "-Wl,-z,max-page-size=16384")

    FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_CPP_DIR}/ggml)
    FetchContent_MakeAvailable(ggml)
    target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)

    target_include_directories(${target_name} PRIVATE
        ${WHISPER_CPP_DIR}
        ${WHISPER_CPP_DIR}/src
        ${WHISPER_CPP_DIR}/include
        ${WHISPER_CPP_DIR}/ggml/include
        ${WHISPER_CPP_DIR}/ggml/src
        ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu  # required since PR #2624
    )
endfunction()

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
endif ()
build_library("whisper")
```

**Step 4: NDK version in build.gradle.kts**

```kotlin
android {
    ndkVersion = "27.2.12479018"  // NDK r27 LTS (current LTS as of 2026-02-27)
    // Note: NDK r28 is required for 16KB page size IF using the emulator test target.
    // The linker flag above handles the actual 16KB alignment; r27 can build the binary.
    // Upgrade to r28 before Play Store submission.
}
```

**Step 5: ProGuard keep rules (proguard-rules.pro)**

```
# Keep all JNI-facing classes and native methods
-keepclassmembers class * {
    native <methods>;
}
-keep class com.simonpena.transchat.** { *; }
# Keep the specific package where JNI bridge lives
-keepclasseswithmembernames class * {
    native <methods>;
}
```

**Step 6: Verify the build compiles**

```bash
./gradlew assembleRelease
# The first build downloads NDK, runs CMake, and compiles whisper.cpp (~5-10 min)
# Subsequent builds are incremental
```

---

## 8. Test PCM Data for Verification

**Confidence:** HIGH (pure Kotlin math, no Android API dependency)

To verify the JNI bridge works before integrating real audio, generate synthetic PCM data:

### Option A: Silence (Fastest Verify — Expected Output: Empty or No-Speech)

```kotlin
// 3 seconds of silence at 16kHz
val sampleRate = 16000
val durationSeconds = 3
val silencePcm = FloatArray(sampleRate * durationSeconds) { 0.0f }

// Expected: whisper returns empty string or no segments (no_speech_thold=0.6 should catch this)
// If whisper returns fabricated text on silence → no_speech_thold is not being applied
```

### Option B: 440 Hz Sine Wave (Confirms Audio Processing Chain)

```kotlin
// 3 seconds of 440 Hz sine tone at 16kHz — tone has no speech content
// Expected: empty transcript (no speech detected) or possibly noise-induced output
fun generateSinePcm(
    frequencyHz: Double = 440.0,
    durationSeconds: Int = 3,
    sampleRate: Int = 16000
): FloatArray {
    return FloatArray(sampleRate * durationSeconds) { i ->
        (Math.sin(2.0 * Math.PI * frequencyHz * i / sampleRate) * 0.5).toFloat()
    }
}
```

### Option C: Pre-recorded Speech (Best Verification)

The whisper.cpp repo ships a test audio file at `samples/jfk.wav` (John F. Kennedy speech excerpt). Convert to 16kHz mono float32 PCM to use as a known-good test input:

```kotlin
// Load the JFK WAV file from assets and decode to PCM for testing
// Expected output: "And so my fellow Americans ask not what your country can do for you..."
// This is the gold standard test for the JNI bridge working correctly
```

**Recommended test sequence for Phase 1:**
1. Generate silence → confirm no hallucination (validates `no_speech_thold`)
2. Generate sine wave → confirm no hallucination (validates params)
3. Use the jfk.wav sample → confirm accurate transcription (validates entire stack)

The JFK sample is ideal because: (a) it's shipped in whisper.cpp, (b) the expected output is known, (c) it exercises multilingual detection (English), and (d) it's long enough (~11 seconds) to trigger meaningful inference.

---

## 9. whisper.cpp Version Selection

**Confidence:** MEDIUM (latest tag verified, but whisper.cpp updates frequently)

**Current latest stable release:** v1.7.4 (as of research date; check releases page)

**Pinning strategy for git submodule:**

```bash
# After adding submodule:
cd app/src/main/cpp/whisper.cpp
git tag --list "v1.*" | tail -10   # list recent tags
git checkout v1.7.4                # pin to specific tag
```

The CONTEXT.md mentions v1.8.3 from a previous research pass. **Verify the current latest stable tag during Phase 1 implementation.** Use the most recent stable (not pre-release) tag. Do not use `master` branch in production — whisper.cpp occasionally breaks Android builds on master between releases.

---

## 10. WhisperCpuConfig — Thread Count

**Source:** Official `examples/whisper.android` (verified 2026-02-27)
**Confidence:** HIGH

The official example includes a `WhisperCpuConfig.kt` that reads `/proc/cpuinfo` to determine the optimal thread count:

```kotlin
object WhisperCpuConfig {
    val preferredThreadCount: Int
        get() = max(1, min(Runtime.getRuntime().availableProcessors() - 1, 4))
}
```

**Rationale:** Leave one core free for the UI thread. Cap at 4 to avoid thermal throttling on phones. The inference is memory-bandwidth-bound above 4 threads on most ARM SoCs, so more threads rarely helps.

---

## Phase 1 Build Verification Checklist

Use this to confirm the phase is complete before handoff to Phase 2:

- [ ] `./gradlew assembleRelease` completes without error
- [ ] `libwhisper.so` and `libwhisper_v8fp16_va.so` appear in `app/build/intermediates/stripped_native_libs/release/arm64-v8a/`
- [ ] Model downloads from HuggingFace URL to `filesDir/models/ggml-small-q5_1.bin`
- [ ] SHA256 verification passes on downloaded model
- [ ] `WhisperContext.createContextFromFile(modelFile.absolutePath)` returns non-null context
- [ ] `whisperContext.transcribeData(silencePcm)` returns empty string (no hallucination)
- [ ] `whisperContext.transcribeData(jfkPcm)` returns approximately correct English text
- [ ] Release build inference time logged on test device (baseline for Phase 4 performance budget)
- [ ] ProGuard keep rules confirmed (test via `assembleRelease` + `aapt2 dump badging`)
- [ ] 16KB page size flag present in `readelf -l libwhisper.so | grep -i page`
- [ ] Download screen shows progress bar, file size, and explainer text
- [ ] "Ready" screen appears after successful download with usage instructions
- [ ] Cellular warning dialog shown when WiFi not available

---

## Key Sources

### HIGH Confidence (Official / Verified)
- [whisper.cpp official repository](https://github.com/ggml-org/whisper.cpp) — CMakeLists.txt, LibWhisper.kt, whisper.h
- [whisper.cpp include/whisper.h](https://raw.githubusercontent.com/ggml-org/whisper.cpp/master/include/whisper.h) — whisper_full_params struct, all field names verified
- [HuggingFace ggerganov/whisper.cpp — ggml-small-q5_1.bin](https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-small-q5_1.bin) — SHA256 `ae85e4a...`, 190 MB size
- [Android DownloadManager.Request API](https://developer.android.com/reference/kotlin/android/app/DownloadManager.Request) — setAllowedNetworkTypes, NETWORK_WIFI constant
- [Add Missing Include Directory for ggml-cpu — PR #2624](https://github.com/ggerganov/whisper.cpp/pull/2624) — ggml-cpu include requirement
- [Android NDK/CMake official guide](https://developer.android.com/ndk/guides/cmake) — externalNativeBuild setup
- [Link Gradle to native library](https://developer.android.com/studio/projects/gradle-external-native-builds) — build.gradle.kts configuration

### MEDIUM Confidence (Community / Secondary)
- [Building LocalMind: whisper.cpp JNI on Android (Jan 2026)](https://medium.com/@mohammedrazachandwala/building-localmind-how-i-ported-openais-whisper-to-android-using-jni-and-kotlin-575dddd38fdc) — JNI memory patterns confirmation
- [GiviMAD/whisper-jni](https://github.com/GiviMAD/whisper-jni) — alternative JNI wrapper API surface for reference
- [Android discussion: Multilingual support?](https://github.com/ggml-org/whisper.cpp/discussions/549) — params patterns
- [Observe DownloadManager progress using LiveData and Coroutine](https://gist.github.com/typebrook/cbb06d0b6c8cfaf4b3698eca44d5413a) — polling pattern

---

## Open Questions / Flags for Implementation

1. **whisper.cpp exact tag to pin:** Verify the current stable tag at implementation time. The CONTEXT.md notes v1.8.3 from project research; check for newer stable releases.

2. **SHA256 hash stability:** The HuggingFace SHA256 `ae85e4a...` was retrieved 2026-02-27. HuggingFace model repos can be updated. Confirm the hash matches after downloading during Phase 1.

3. **NDK r27 vs r28 for 16KB page size:** NDK r27 LTS can produce 16KB-aligned binaries using the `-Wl,-z,max-page-size=16384` linker flag. NDK r28 is only strictly required to use the 16KB Android emulator target for testing. Decide at build time: r27 for now, upgrade to r28 before Play Store submission.

4. **jni.c vs whisper_jni.cpp:** The official example uses `jni.c` (C, not C++). Using `.cpp` (C++) is fine and allows RAII and more natural JNI code; just ensure `extern "C"` is declared on all exported functions.

5. **Inference speed baseline:** CONTEXT.md notes community reports of 15-30s mid-range, 30-90s low-end. The actual benchmark on the development device determines whether UX copy during Phase 4 should say "usually under 30 seconds" or "may take up to a minute."

6. **Model commit hash for reproducibility:** Rather than `resolve/main/`, use the specific commit hash URL for the model download to prevent hash mismatch if HuggingFace model is updated:
   `https://huggingface.co/ggerganov/whisper.cpp/resolve/e7c63091148293a457a91f9b127a86250683f6b9/ggml-small-q5_1.bin`
   (The commit hash `e7c63091` was visible in the HuggingFace repository tree; verify this is the commit that produced the SHA256 `ae85e4a...`.)

---

*Phase research completed: 2026-02-27*
*Feeds into: 01-PLAN.md*
