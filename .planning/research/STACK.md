# Technology Stack

**Project:** TransChat v1.1 — Progress Bar, F-Droid, ProGuard
**Researched:** 2026-02-27
**Research Mode:** Milestone-scoped (v1.1 additions only)
**Scope note:** v1.0 stack (whisper.cpp v1.7.4, Kotlin, NDK 26.1, MediaCodec, ViewModel + StateFlow) is validated and unchanged. This document covers ONLY what changes or is added for v1.1.

---

## What Is NOT Changing

The following v1.0 decisions are locked and not re-researched here:

- whisper.cpp v1.7.4 via JNI (already compiled and working in codebase)
- `NDK 26.1.10909125` (already installed in environment)
- `compileSdk 34`, `minSdk 26`, `targetSdk 34`
- `arm64-v8a` only ABI filter
- Kotlin + AndroidViewModel + StateFlow pattern
- MediaCodec audio decoding pipeline

---

## Feature 1: Transcription Progress Callbacks

### What the whisper.cpp API Provides (Confirmed from source in repo)

The bundled `whisper.cpp/include/whisper.h` at v1.7.4 exposes two relevant callbacks in `whisper_full_params`:

```c
// Called on each progress update (0..100 integer)
typedef void (*whisper_progress_callback)(
    struct whisper_context * ctx,
    struct whisper_state   * state,
    int                      progress,   // 0-100
    void                   * user_data);

// Called whenever a new text segment is produced
typedef void (*whisper_new_segment_callback)(
    struct whisper_context * ctx,
    struct whisper_state   * state,
    int                      n_new,      // number of new segments since last call
    void                   * user_data);
```

Both are set on `whisper_full_params` (passed to `whisper_full()`):

```c
wparams.progress_callback           = my_progress_fn;
wparams.progress_callback_user_data = my_data_ptr;

wparams.new_segment_callback           = my_segment_fn;
wparams.new_segment_callback_user_data = my_data_ptr;
```

**Confirmed from `src/whisper.cpp` source (lines 5526-5530, in repo):** `progress_callback` fires with `progress_cur = (100*(seek - seek_start))/(seek_end - seek_start)` — integer 0 to 100, called once per audio chunk of WHISPER_CHUNK_SIZE (30s). For a sub-2-minute voice message (1-4 chunks), expect 3-6 firings.

**Confirmed from `src/whisper.cpp` source (lines 6166-6227):** `new_segment_callback` fires after each decoded text segment is committed. For a 30-second message this may fire 3-10 times.

### Recommendation: Use `new_segment_callback`, Not `progress_callback`

For short voice messages (12-60 seconds), `progress_callback` fires too infrequently for a meaningful UI — voice messages map to 1-2 audio chunks, so you get at most 2 firings. `new_segment_callback` fires per transcript segment (a phrase or sentence), which is more frequent and semantically meaningful.

**Chosen approach:** `new_segment_callback` driving a determinate-style progress bar approximation using time offsets. Alternatively, use it to stream partial text in a "typing" style — but the simpler implementation is to count segments and use a weighted progress estimate.

### JNI Callback Architecture (No New Libraries)

This is a pure C++ + Kotlin change inside the existing JNI bridge. No new dependencies.

**The threading constraint:** `new_segment_callback` fires on the same thread that called `whisper_full()` — which is the `newSingleThreadExecutor` coroutine dispatcher in `WhisperContext`. This thread is NOT the JVM main thread, so calling back into Kotlin from it requires care.

**Implementation pattern (two approaches):**

**Approach A — Shared mutable reference (recommended, zero overhead):**
Store a Kotlin lambda reference in a C++ struct passed as `user_data`. Access is safe because the callback runs on the same thread as `whisper_full()`, which is already serialized by the single-thread executor.

```cpp
// In whisper_jni.cpp — extend fullTranscribeWithCallback

struct ProgressCallbackData {
    JavaVM* jvm;
    jobject callbackGlobalRef;  // global ref to Kotlin functional interface
    jmethodID onProgressMethod;
};

static void new_segment_callback_jni(
    struct whisper_context* ctx, struct whisper_state* state,
    int n_new, void* user_data)
{
    auto* data = static_cast<ProgressCallbackData*>(user_data);
    JNIEnv* env = nullptr;
    bool didAttach = false;
    int status = data->jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        data->jvm->AttachCurrentThread(&env, nullptr);
        didAttach = true;
    }
    // Call back into Kotlin
    env->CallVoidMethod(data->callbackGlobalRef, data->onProgressMethod, n_new);
    if (didAttach) data->jvm->DetachCurrentThread();
}
```

**Approach B — AtomicInteger (zero JNI upcall, simpler):**
In C++, just write `atomic_int segment_count++` into the user_data struct. In Kotlin, poll it from a coroutine with a `delay(100)` loop. Simpler, but polling adds latency and complexity.

**Recommendation: Approach A (direct JNI upcall)** because:
- No polling loop to manage or cancel
- Progress updates arrive immediately
- The single-thread executor guarantees sequential execution — no race between the callback and segment reading

**Required JNI changes:**
- `whisper_jni.cpp`: Add new `fullTranscribeWithCallback(contextPtr, numThreads, audioData, callback)` JNI function
- `WhisperLib.kt`: Add corresponding `external fun fullTranscribeWithCallback(...)` declaration
- `WhisperContext.kt`: Add `transcribeDataWithCallback(data, onSegment: (Int) -> Unit)` overload
- `WhisperEngine.kt`: Expose `transcribe(audioData, onSegment)` overload
- `TranscriptionViewModel.kt`: Wire callback to `_progressState: MutableStateFlow<Int>`
- `TranscriptionActivity.kt`: Observe new `progressState` flow, show/hide progress bar

**New StateFlow in ViewModel:**
```kotlin
private val _progress = MutableStateFlow(0)
val progress: StateFlow<Int> = _progress
```

Emitted from the callback: `_progress.value = segmentCount * estimatedWeight`.

**No new Gradle dependencies required for this feature.**

### UI: ProgressBar Widget (Already Available)

`android.widget.ProgressBar` is a framework widget — no library needed. Use `style="?android:attr/progressBarStyleHorizontal"` for a horizontal bar.

```xml
<ProgressBar
    android:id="@+id/progress_bar"
    style="?android:attr/progressBarStyleHorizontal"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:max="100"
    android:progress="0"
    android:indeterminate="false" />
```

For indeterminate mode (during model load, before first segment fires): set `android:indeterminate="true"` initially, switch to determinate when the first segment callback arrives.

**Confidence: HIGH** — confirmed from whisper.h and whisper.cpp source in the repository, and from Android framework docs.

---

## Feature 2: F-Droid Distribution

### Submission Model

F-Droid is a community-maintained repository. Apps are NOT submitted via self-service upload (unlike Play Store). The process:

1. Developer opens a merge request against [fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata) with a metadata `.yml` file
2. F-Droid volunteer reviewers inspect the app source and build script
3. F-Droid's build server clones the repo, builds the APK from source, and signs it with F-Droid's key (by default)
4. Reproducible build path: F-Droid can verify developer-signed APK matches their own build — then serve the developer's APK

### Required Files in the App Repository

No files need to be added to fdroiddata — those live in F-Droid's own repo. Files that must be in THIS repo:

#### Fastlane Metadata Structure (for descriptions/screenshots)

F-Droid reads fastlane-compatible metadata directly from the app repository. Place at the repo root:

```
fastlane/
  metadata/
    android/
      en-US/
        short_description.txt   # REQUIRED. Max 80 chars, no trailing period
        full_description.txt    # REQUIRED. Max 4000 chars (Play Store limit respected by F-Droid)
        changelogs/
          2.txt                 # Named by versionCode. Max 500 chars. versionCode 2 = v1.1
        images/
          icon.png              # 512x512 recommended
          featureGraphic.png    # 1024x500 optional
          phoneScreenshots/
            01.png              # Optional. Portrait preferred.
```

**Key constraints (confirmed from F-Droid docs):**
- `short_description.txt`: one line, no trailing period, under 80 characters
- `full_description.txt`: plain text (no markdown), under 4000 characters
- Changelog filename must match the `versionCode` in `build.gradle.kts` exactly (`2.txt` for `versionCode = 2`)
- All fastlane files must be at `fastlane/metadata/android/`, NOT inside `app/` subdirectory
- F-Droid docs confirm: "placing the structure anywhere else, like in the gradle module's root, won't work"

#### F-Droid-Specific Build Requirements

**Anti-features to declare (if applicable):**
- The app downloads a model on first run — this may need the `NonFreeNet` anti-feature tag if downloading from a non-free service (HuggingFace). Hosting the model on a CDN or self-hosted URL avoids this.
- No ads, no trackers, no Play Services dependencies — clean for F-Droid inclusion.

**Network permission for model download:** TransChat currently has `INTERNET` permission for model download only. F-Droid is fine with this; the zero-network-during-transcription constraint is an app-level policy, not a manifest issue.

**No proprietary SDKs:** The stack is clean — no Firebase, no Google Play Services, no Crashlytics. Confirm `libs.versions.toml` has no proprietary deps (verified: all deps are `androidx.*`, `material`, `kotlinx.*` — all Apache 2.0/open source).

### Fdroiddata Metadata YAML

This file does NOT live in the app repo — it lives in fdroiddata. Shown here for reference only:

```yaml
# metadata/com.simonpena.transchat.yml  (in fdroiddata repo, not this repo)
Categories:
  - Multimedia
License: Apache-2.0  # or whatever the app license is
AuthorName: Simon Pena
SourceCode: https://github.com/<user>/transchat-app
IssueTracker: https://github.com/<user>/transchat-app/issues

AutoName: TransChat

RepoType: git
Repo: https://github.com/<user>/transchat-app

Builds:
  - versionName: '1.1'
    versionCode: 2
    commit: v1.1
    subdir: app
    gradle:
      - yes
    ndk: 26.1.10909125
    buildjni:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: '1.1'
CurrentVersionCode: 2
```

**Key fields:**
- `ndk`: must match `ndkVersion` in `app/build.gradle.kts` exactly
- `buildjni: yes` is required — tells F-Droid server to compile native code
- `gradle: yes` means build the default Gradle variant
- `subdir: app` points to the Gradle module directory

**Reproducible builds (optional, recommended):**
To opt into F-Droid's reproducible build verification:
1. Generate a release signing key (not debug keystore)
2. Sign your release APK with it
3. Provide the APK SHA-256 signing key fingerprint in fdroiddata:
   ```yaml
   AllowedAPKSigningKeys: <sha256-of-your-cert>
   Binaries: https://github.com/<user>/transchat-app/releases/download/v{v}/transchat-v{v}.apk
   ```
4. F-Droid builds from source, compares byte-for-byte, and if matching, serves YOUR signed APK

**Reproducible build is NOT required for v1.1.** Standard F-Droid signing is simpler for first submission. Reproducible builds are a best practice for later adoption.

### Reproducible Build Prerequisites (if adopted later)

To achieve byte-for-byte reproducibility:
- Pin all tool versions: AGP, Kotlin, NDK (already done — `ndkVersion` is explicit in build.gradle.kts)
- Set `SOURCE_DATE_EPOCH` in the build environment for timestamp stability
- Ensure no build-time generated files that embed timestamps (check CMake outputs)
- Use `--reproducible` flag with apksigner

**Confidence: HIGH** for fastlane file structure (confirmed from F-Droid official docs in search).
**Confidence: MEDIUM** for the fdroiddata YAML format (confirmed structure, specific field values need verification against current fdroiddata templates when submitting).

---

## Feature 3: ProGuard / R8 Configuration

### Current State

`app/proguard-rules.pro` already has basic JNI keep rules (from v1.0). The existing file is:

```proguard
# Keep all JNI-facing native methods
-keepclassmembers class * {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the entire transchat package to preserve JNI name mangling
-keep class com.simonpena.transchat.** { *; }

# Keep WhisperLib companion object specifically
-keep class com.simonpena.transchat.WhisperLib { *; }
-keep class com.simonpena.transchat.WhisperLib$Companion { *; }
```

### What Needs to Change for v1.1

**Problem with the existing rules:** `-keep class com.simonpena.transchat.** { *; }` keeps EVERYTHING in the package including all Kotlin classes, all methods, all fields. This defeats the purpose of R8 shrinking — no dead code elimination, no obfuscation. For a small app this is not a correctness problem, but it wastes shrinking benefit.

**The correct targeted approach for JNI apps:**

```proguard
# --- JNI: Native method declarations in Kotlin ---
# These are the Kotlin-side external fun declarations that JNI C++ calls by name.
# R8 must not rename them or the JNI name mangling breaks at runtime.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- JNI: WhisperLib companion object ---
# The JNI function names are mangled from the Kotlin class+companion name.
# Java_com_simonpena_transchat_WhisperLib_00024Companion_initContext etc.
# must not be renamed.
-keep class com.simonpena.transchat.WhisperLib { *; }
-keep class com.simonpena.transchat.WhisperLib$Companion { *; }

# --- v1.1 addition: Progress callback interface ---
# If using a functional interface for the JNI upcall, keep it.
# Replace with actual interface name once implemented.
# -keep interface com.simonpena.transchat.SegmentCallback { *; }

# --- Android Activity entry points (kept by AGP automatically via manifest) ---
# No manual keep needed for Activity subclasses — AGP generates keep rules from AndroidManifest.

# --- Kotlin coroutines ---
# kotlinx.coroutines ships its own consumer rules via AAR; no manual keep needed.

# --- ViewModel ---
# AndroidViewModel is kept by the lifecycle AAR's consumer rules; no manual keep needed.

# --- StateFlow / Flow ---
# Kotlin stdlib and kotlinx.coroutines are kept by their bundled rules.

# --- Debugging: preserve stack traces in release builds ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
```

**What to REMOVE from the existing rules:**
- `-keep class com.simonpena.transchat.** { *; }` — overly broad, keeps all internal Kotlin classes that R8 could otherwise shrink/obfuscate. Replace with targeted keeps above.

### Why `includedescriptorclasses` Matters

The flag `-keepclasseswithmembernames,includedescriptorclasses` (confirmed from [Android Developers Blog, Nov 2025](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html)) ensures parameter and return types used in native method signatures are also kept. Without it, R8 might rename a parameter type class even though the native method signature references it by name.

### proguard-android-optimize.txt vs proguard-android.txt

**Breaking change noted (confirmed from research, Nov 2025):**
- `proguard-android.txt` is being phased out starting AGP 8.x
- AGP 9.0 drops support entirely
- Current `app/build.gradle.kts` correctly uses `proguard-android-optimize.txt` (the non-deprecated version)
- No change needed here for v1.1

### Native Library (.so) — No R8 Rules Needed

The `.so` files (libwhisper.so, libwhisper_v8fp16_va.so) are native binaries — R8 does not process them. They pass through the APK build unchanged. No ProGuard/R8 rules are needed for the native side.

### V1.1 Callback Lambda Keep Rule

If the `new_segment_callback` JNI upcall uses a Kotlin functional interface (SAM conversion), R8 may inline or rename the lambda class. Add a keep rule for it:

```proguard
# Keep the segment callback interface if using SAM/functional interface for JNI upcall
# Add this after the interface is named in implementation
-keep interface com.simonpena.transchat.SegmentCallback { *; }
```

If using a plain Kotlin lambda (not a named interface), R8 may optimize it — test in release build before shipping.

### R8 Full Mode

R8 in "full mode" (enabled by default in AGP 8+) is more aggressive than classic ProGuard. The key difference: it inlines and removes classes that have only one usage site. The existing `WhisperLib` companion + `external fun` declarations are safe because the `external` keyword signals to R8 that the method crosses the JNI boundary.

**Confidence: HIGH** for JNI keep rules (confirmed from Android official docs + Android Developers Blog Nov 2025).
**Confidence: HIGH** for `proguard-android-optimize.txt` requirement (confirmed from official source).
**Confidence: MEDIUM** for `includedescriptorclasses` necessity in this specific app (the existing `native <methods>` rule likely works, but the descriptor flag is more correct and the official 2025 guidance recommends it).

---

## No New Gradle Dependencies for v1.1

All three v1.1 features are implementable with existing dependencies:

| Feature | Dependencies Added | Reason |
|---------|-------------------|--------|
| Progress callbacks | None | Pure C++ + Kotlin change in existing JNI bridge |
| F-Droid metadata | None | Text files + YAML, not Gradle dependencies |
| ProGuard/R8 rules | None | Configuration file changes only |

The existing `libs.versions.toml` (AGP 8.7.3, Kotlin 2.0.21, core-ktx 1.13.1, material 1.12.0, coroutines 1.9.0, lifecycle 2.8.7, activity 1.9.3) remains unchanged.

---

## Version Codes for v1.1

`app/build.gradle.kts` must increment for F-Droid and Play Store:

```kotlin
versionCode = 2        // was 1 in v1.0
versionName = "1.1"    // was "1.0"
```

The fastlane changelog file must be named `2.txt` (matching `versionCode = 2`).

---

## Confidence Assessment

| Area | Confidence | Source | Notes |
|------|------------|--------|-------|
| whisper.cpp callback API | HIGH | whisper.h + whisper.cpp source in repo (verified directly) | Both callbacks confirmed present in v1.7.4 |
| `progress_callback` integer range | HIGH | whisper.cpp src/whisper.cpp lines 5526-5530 (in repo) | Computed as `(100*(seek-start))/(end-start)` |
| `new_segment_callback` firing frequency | MEDIUM | whisper.cpp src/whisper.cpp + knowledge of WHISPER_CHUNK_SIZE | Depends on audio length and model segmentation |
| JNI upcall via AttachCurrentThread | HIGH | Android NDK JNI tips (developer.android.com/training/articles/perf-jni) | Standard documented pattern for callbacks from non-JVM threads |
| F-Droid fastlane directory structure | HIGH | F-Droid official docs (f-droid.org/docs) via web search | Multiple sources confirm same structure |
| F-Droid YAML format | MEDIUM | F-Droid Build Metadata Reference (official) | Specific NDK field name needs verification against current fdroiddata templates |
| ProGuard JNI keep rules | HIGH | Android Developers Blog Nov 2025 + official Android keep-rule docs | includedescriptorclasses flag is 2025 recommended practice |
| `proguard-android-optimize.txt` requirement | HIGH | Android Developers Blog Nov 2025 — confirms AGP 9.0 drops proguard-android.txt | Current build.gradle.kts already uses optimize version |

---

## Sources

- [whisper.h — callback typedef definitions](app/src/main/cpp/whisper.cpp/include/whisper.h) — in repo, lines 449-456
- [whisper.cpp src — progress_callback invocation](app/src/main/cpp/whisper.cpp/src/whisper.cpp) — in repo, lines 5526-5530
- [whisper.cpp src — new_segment_callback invocation](app/src/main/cpp/whisper.cpp/src/whisper.cpp) — in repo, lines 6166-6227
- [Android NDK JNI Tips — GetEnv / AttachCurrentThread](https://developer.android.com/training/articles/perf-jni)
- [F-Droid All About Descriptions, Graphics, and Screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)
- [F-Droid Submitting Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [F-Droid Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/)
- [F-Droid Reproducible Builds](https://f-droid.org/en/docs/Reproducible_Builds/)
- [Android Developers Blog: Configure and Troubleshoot R8 Keep Rules (Nov 2025)](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html)
- [Android Developers Blog: Use R8 to shrink, optimize, and fast-track your app (Nov 2025)](https://android-developers.googleblog.com/2025/11/use-r8-to-shrink-optimize-and-fast.html)
- [Keep rule use cases and examples — Android Developers](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples)
- [About keep rules — Android Developers](https://developer.android.com/topic/performance/app-optimization/keep-rules-overview)
- [whisper.cpp bindings/java — WhisperProgressCallback.java](app/src/main/cpp/whisper.cpp/bindings/java/src/main/java/io/github/ggerganov/whispercpp/callbacks/WhisperProgressCallback.java) — official Java binding showing callback signature
- [F-Droid in 2025 — Reproducible builds at 21%](https://f-droid.org/en/2026/01/23/fdroid-in-2025-strengthening-our-foundations-in-a-changing-mobile-landscape.html)
