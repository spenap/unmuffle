---
phase: 01-whisper-engine-model-bootstrap
plan: "01"
subsystem: infra
tags: [android, ndk, cmake, whisper.cpp, jni, gradle, kotlin, arm64]

# Dependency graph
requires: []
provides:
  - Android Gradle project scaffold (settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml)
  - whisper.cpp git submodule pinned to v1.7.4
  - CMakeLists.txt building libwhisper.so and libwhisper_v8fp16_va.so for arm64-v8a
  - JNI C++ bridge (whisper_jni.cpp) with 6 exported functions for com.simonpena.transchat package
  - ProGuard keep rules protecting JNI methods from R8 stripping
  - Gradle 8.9 wrapper for reproducible builds
  - Minimal AndroidManifest.xml, themes.xml, adaptive launcher icons
affects:
  - 01-02 (WhisperEngine Kotlin API depends on JNI function signatures)
  - 01-03 (DownloadActivity depends on app manifest and theme)
  - 01-04 (SmokeTestActivity depends on native build producing working .so files)

# Tech tracking
tech-stack:
  added:
    - whisper.cpp v1.7.4 (ggml-based on-device speech recognition)
    - Android NDK 26.1.10909125 via CMake 3.22.1
    - Gradle 8.9 + AGP 8.7.3
    - Kotlin 2.0.21
    - AndroidX: core-ktx 1.13.1, appcompat 1.7.0, material 1.12.0
    - Kotlin Coroutines 1.9.0
    - Lifecycle ViewModel 2.8.7, Activity 1.9.3
  patterns:
    - whisper.cpp integrated as git submodule (pinned tag, not master)
    - Dual shared library build: libwhisper.so (baseline) + libwhisper_v8fp16_va.so (FP16 arm64 variant)
    - JNI function naming: Java_com_simonpena_transchat_WhisperLib_00024Companion_*
    - Anti-hallucination params set in JNI layer: no_timestamps=true, no_speech_thold=0.6f
    - Model loaded from file path (not byte array) to avoid OOM
    - 16KB page size linker flag for Play Store / Pixel 9+ compatibility

key-files:
  created:
    - settings.gradle.kts
    - build.gradle.kts
    - gradle.properties
    - gradle/libs.versions.toml
    - gradle/wrapper/gradle-wrapper.properties
    - gradle/wrapper/gradle-wrapper.jar
    - gradlew
    - gradlew.bat
    - local.properties.template
    - app/build.gradle.kts
    - app/proguard-rules.pro
    - app/src/main/AndroidManifest.xml
    - app/src/main/cpp/CMakeLists.txt
    - app/src/main/cpp/whisper_jni.cpp
    - app/src/main/cpp/whisper.cpp (submodule at v1.7.4)
    - app/src/main/res/values/themes.xml
    - app/src/main/res/drawable/ic_launcher_foreground.xml
    - app/src/main/res/drawable/ic_launcher_background.xml
    - app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
    - app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
    - .gitignore
    - .gitmodules
  modified:
    - app/build.gradle.kts (NDK version and SDK level adjusted for environment)
    - gradle/libs.versions.toml (core-ktx downgraded for SDK 34 compatibility)

key-decisions:
  - "NDK 26.1.10909125 used instead of planned r27 — r27 not installed in build environment"
  - "compileSdk/targetSdk set to 34 instead of 35 — only android-34 platform available"
  - "core-ktx 1.13.1 instead of 1.15.0 — 1.15.0 requires compileSdk 35"
  - "whisper.cpp pinned to v1.7.4 (not master) for reproducible builds"
  - "Both libwhisper.so (baseline) and libwhisper_v8fp16_va.so (FP16) built for arm64-v8a"
  - "Gradle 8.9 wrapper generated and committed for CI reproducibility"

patterns-established:
  - "JNI bridge pattern: JNIEnv* + jobject thiz, file-path model loading, LOGI/LOGE macros"
  - "CMake FetchContent for ggml subdirectory within whisper.cpp submodule"
  - "Anti-hallucination: no_timestamps=true + no_speech_thold=0.6f set at JNI layer"
  - "arm64-v8a only: single ABI keeps APK small and build simple"

requirements-completed: [MODEL-01]

# Metrics
duration: 7min
completed: 2026-02-27
---

# Phase 1 Plan 01: Android Project Scaffold + whisper.cpp NDK Build Summary

**whisper.cpp v1.7.4 compiled via NDK CMake as dual arm64 shared libs (libwhisper.so + libwhisper_v8fp16_va.so) with JNI C++ bridge exporting 5 transcription functions for com.simonpena.transchat**

## Performance

- **Duration:** ~7 min
- **Started:** 2026-02-27T12:55:11Z
- **Completed:** 2026-02-27T13:01:57Z
- **Tasks:** 2
- **Files modified:** 30+

## Accomplishments
- Complete Android Gradle project scaffold created from scratch — settings, build, version catalog, manifest, themes, launcher icons
- whisper.cpp v1.7.4 added as git submodule and compiled via NDK 26.1 CMake into libwhisper.so and libwhisper_v8fp16_va.so for arm64-v8a
- JNI C++ wrapper with correct mangled names for WhisperLib$Companion: initContext, fullTranscribe, getTextSegmentCount, getTextSegment, freeContext, getSystemInfo
- ProGuard rules keeping native <methods> and WhisperLib$Companion safe from R8
- `./gradlew assembleRelease` exits 0 — BUILD SUCCESSFUL confirmed

## Task Commits

Each task was committed atomically:

1. **Task 1: Android project scaffold** - `e502ead` (feat)
2. **Task 2: whisper.cpp submodule + CMake + JNI + ProGuard** - `57ede17` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `settings.gradle.kts` - Root settings with plugin/dep repositories and :app include
- `build.gradle.kts` - Root build referencing plugin aliases from version catalog
- `gradle.properties` - AndroidX, nonTransitiveRClass, JVM heap settings
- `gradle/libs.versions.toml` - Version catalog: AGP 8.7.3, Kotlin 2.0.21, all deps
- `gradle/wrapper/gradle-wrapper.properties` - Gradle 8.9 wrapper config
- `app/build.gradle.kts` - Module: arm64-v8a ABI filter, minSdk 26, CMake integration
- `app/src/main/AndroidManifest.xml` - Minimal manifest with INTERNET permission
- `app/src/main/res/values/themes.xml` - MaterialComponents.DayNight base theme
- `app/src/main/cpp/CMakeLists.txt` - CMake build for libwhisper + libwhisper_v8fp16_va
- `app/src/main/cpp/whisper_jni.cpp` - JNI bridge with 6 exported functions
- `app/src/main/cpp/whisper.cpp` - Git submodule at v1.7.4
- `app/proguard-rules.pro` - Keep rules for native methods and WhisperLib$Companion
- `.gitignore` - Excludes build artifacts, .so/.a files, local.properties
- `.gitmodules` - Submodule registration for whisper.cpp

## Decisions Made
- Used NDK 26.1.10909125 (installed) vs planned r27 — functionally equivalent for this build
- compileSdk/targetSdk 34 instead of 35 — only android-34 platform available in environment; can be bumped to 35 when dev machine has r27 + SDK 35 installed
- core-ktx 1.13.1 instead of 1.15.0 — 1.15.0 requires compileSdk 35
- Gradle 8.9 wrapper generated and committed — enables `./gradlew` on any machine without pre-installed Gradle

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Downgraded NDK from r27 to NDK 26.1 to match installed environment**
- **Found during:** Task 2 (build verification)
- **Issue:** app/build.gradle.kts specified `ndkVersion = "27.2.12479018"` but only NDK 26.1.10909125 was installed at `/workspace/android-sdk/ndk/26.1.10909125`
- **Fix:** Updated ndkVersion in app/build.gradle.kts to `"26.1.10909125"`
- **Files modified:** app/build.gradle.kts
- **Verification:** Build proceeded past NDK resolution
- **Committed in:** 57ede17 (Task 2 commit)

**2. [Rule 3 - Blocking] Downgraded compileSdk/targetSdk from 35 to 34**
- **Found during:** Task 2 (build verification)
- **Issue:** Only `android-34` platform was installed; `android-35` not present
- **Fix:** Updated compileSdk = 34, targetSdk = 34 in app/build.gradle.kts
- **Files modified:** app/build.gradle.kts
- **Verification:** AAR metadata check passed
- **Committed in:** 57ede17 (Task 2 commit)

**3. [Rule 3 - Blocking] Downgraded core-ktx from 1.15.0 to 1.13.1**
- **Found during:** Task 2 (assembleRelease)
- **Issue:** `androidx.core:core-ktx:1.15.0` requires compileSdk 35; compilation failed with AAR metadata error
- **Fix:** Downgraded coreKtx version in gradle/libs.versions.toml to 1.13.1 (last version requiring only SDK 34)
- **Files modified:** gradle/libs.versions.toml
- **Verification:** `BUILD SUCCESSFUL` — assembleRelease exits 0
- **Committed in:** 57ede17 (Task 2 commit)

**4. [Rule 3 - Blocking] Created Gradle wrapper (not in plan)**
- **Found during:** Task 2 (attempting ./gradlew)
- **Issue:** No `gradlew` script existed; plan assumed it would be present
- **Fix:** Downloaded Gradle 8.9 binary, ran `gradle wrapper --gradle-version 8.9`, committed wrapper files
- **Files modified:** gradlew, gradlew.bat, gradle/wrapper/gradle-wrapper.properties, gradle/wrapper/gradle-wrapper.jar
- **Verification:** `./gradlew assembleRelease` succeeded
- **Committed in:** 57ede17 (Task 2 commit)

---

**Total deviations:** 4 auto-fixed (all Rule 3 — blocking environment constraints)
**Impact on plan:** All fixes are environment-specific SDK/NDK version adjustments. The NDK and SDK versions should be updated back to r27 + SDK 35 when those are available on the developer machine. No scope creep — all fixes necessary for the build to run in this sandbox environment.

## Issues Encountered
- Build environment had NDK 26.1 and android-34 instead of the planned NDK 27 and android-35. The CMakeLists.txt is identical to plan specification. The JNI bridge and ProGuard rules are exactly as specified. Only version numbers were adjusted.

## User Setup Required
- Copy `local.properties.template` to `local.properties` and set `sdk.dir` to your Android SDK path
- On developer machines with NDK r27 + SDK 35: update `ndkVersion`, `compileSdk`, `targetSdk`, and `coreKtx` back to their original plan values (27.2.12479018, 35, 35, 1.15.0)

## Next Phase Readiness
- Build foundation complete — all downstream plans can proceed
- Plan 01-02: WhisperLib.kt JNI declarations must match the 6 function signatures in whisper_jni.cpp with package com.simonpena.transchat and inner class WhisperLib$Companion
- Plan 01-03: DownloadActivity has app namespace and theme ready
- Plan 01-04: SmokeTestActivity can load libwhisper_v8fp16_va.so on arm64 devices

## Self-Check: PASSED

All key files verified present:
- settings.gradle.kts: FOUND
- app/build.gradle.kts: FOUND
- gradle/libs.versions.toml: FOUND
- app/src/main/AndroidManifest.xml: FOUND
- app/src/main/cpp/CMakeLists.txt: FOUND
- app/src/main/cpp/whisper_jni.cpp: FOUND
- app/proguard-rules.pro: FOUND
- .gitmodules: FOUND
- gradlew: FOUND
- 01-01-SUMMARY.md: FOUND

Task commits verified:
- e502ead (Task 1: Android scaffold): FOUND
- 57ede17 (Task 2: whisper.cpp + JNI + build): FOUND

Build verification:
- libwhisper.so: FOUND in app/build/intermediates/stripped_native_libs/release/*/arm64-v8a/
- libwhisper_v8fp16_va.so: FOUND in same location
- JNI functions (grep count): 6 matches for Java_com_simonpena_transchat
- ProGuard native rules: 2 matches for native <methods>
- whisper.cpp tag: v1.7.4 (pinned, not master)

---
*Phase: 01-whisper-engine-model-bootstrap*
*Completed: 2026-02-27*
