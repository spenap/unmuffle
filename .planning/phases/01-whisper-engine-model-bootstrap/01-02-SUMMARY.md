---
phase: 01-whisper-engine-model-bootstrap
plan: "02"
subsystem: infra
tags: [android, kotlin, jni, whisper.cpp, coroutines, arm64, fp16]

# Dependency graph
requires:
  - phase: 01-01
    provides: JNI C++ bridge (whisper_jni.cpp) with 6 exported functions for com.simonpena.transchat.WhisperLib$Companion
provides:
  - WhisperLib.kt — JNI bridge with 6 external fun declarations matching whisper_jni.cpp
  - WhisperContext — native pointer wrapper with single-thread executor for thread safety
  - WhisperCpuConfig.kt — thread count helper capped at min(availableProcessors-1, 4)
  - WhisperEngine.kt — public API: loadModel/transcribe/isModelLoaded/release with Dispatchers.Default threading
affects:
  - 01-03 (DownloadActivity calls WhisperEngine.loadModel after download completes)
  - 01-04 (SmokeTestActivity calls WhisperEngine.loadModel + transcribe for end-to-end smoke test)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - WhisperLib companion object as JNI bridge — class name and package must never change (breaks JNI binding silently)
    - Single-thread executor per WhisperContext — whisper.cpp context is not thread-safe
    - CPU feature detection via /proc/cpuinfo (fphp/asimdhp flags) for FP16 arm64 variant selection
    - File-path model loading only — byte-array loading causes OOM on 190MB model

key-files:
  created:
    - app/src/main/java/com/simonpena/transchat/WhisperLib.kt
    - app/src/main/java/com/simonpena/transchat/WhisperCpuConfig.kt
    - app/src/main/java/com/simonpena/transchat/WhisperEngine.kt
  modified: []

key-decisions:
  - "WhisperLib is internal (not public) — WhisperEngine is the only public API surface for callers"
  - "WhisperContext uses single-thread Executors.newSingleThreadExecutor() to prevent concurrent JNI access"
  - "WhisperEngine.transcribe throws IllegalStateException (not silent failure) when model not loaded"
  - "loadModel() returns false on missing/OOM/corrupt model instead of throwing — callers can show user-friendly error"

patterns-established:
  - "JNI class name guard: WhisperLib companion MUST stay in com.simonpena.transchat — renaming silently breaks JNI"
  - "All inference on Dispatchers.Default — whisper transcription takes 15-60s, main thread ANR triggers at 5s"
  - "Native context lifecycle: loadModel() releases previous context before loading new one"

requirements-completed: [MODEL-01]

# Metrics
duration: 2min
completed: 2026-02-27
---

# Phase 1 Plan 02: WhisperEngine Kotlin JNI Bridge and Public API Summary

**Kotlin JNI bridge (WhisperLib) + thread-safe context wrapper (WhisperContext) + public engine API (WhisperEngine) with Dispatchers.Default transcription and file-path model loading**

## Performance

- **Duration:** ~2 min
- **Started:** 2026-02-27T13:05:37Z
- **Completed:** 2026-02-27T13:07:09Z
- **Tasks:** 2
- **Files modified:** 3 created

## Accomplishments
- WhisperLib.kt created with 6 external fun declarations exactly matching the C++ JNI function signatures in whisper_jni.cpp (package + companion class name alignment confirmed)
- CPU feature detection at class init time: reads /proc/cpuinfo for fphp/asimdhp flags, loads libwhisper_v8fp16_va.so on capable arm64 hardware, falls back to libwhisper.so
- WhisperContext wraps native pointer in single-thread CoroutineScope (Executors.newSingleThreadExecutor) to prevent concurrent access crashes — whisper.cpp is not thread-safe
- WhisperEngine public API: loadModel returns false on failure (no crash), transcribe runs on Dispatchers.Default, IllegalStateException thrown if called before model loaded
- All three files compile in release build (./gradlew :app:compileReleaseKotlin — BUILD SUCCESSFUL)

## Task Commits

Each task was committed atomically:

1. **Task 1: WhisperLib.kt and WhisperCpuConfig.kt** - `236933b` (feat)
2. **Task 2: WhisperEngine.kt** - `fafc9c8` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `app/src/main/java/com/simonpena/transchat/WhisperLib.kt` - JNI bridge with 6 external funs + WhisperContext wrapper with single-thread executor
- `app/src/main/java/com/simonpena/transchat/WhisperCpuConfig.kt` - Thread count helper: min(availableProcessors - 1, 4)
- `app/src/main/java/com/simonpena/transchat/WhisperEngine.kt` - Public API: loadModel/transcribe/isModelLoaded/release with Dispatchers.Default

## Decisions Made
- WhisperLib is `internal` — WhisperEngine is the sole public API surface; callers (Plans 03, 04) never import WhisperLib directly
- WhisperContext uses newSingleThreadExecutor (not Dispatchers.IO) to guarantee sequential access — whisper.cpp crashes on concurrent calls to the same context
- WhisperEngine.transcribe throws IllegalStateException rather than returning empty string — forces callers to check isModelLoaded explicitly
- loadModel returns false on failure (missing file, OOM, corrupt) so UI can display user-friendly error without catching exceptions

## Deviations from Plan

None — plan executed exactly as written. The plan contained complete implementation code; all three files were created verbatim and compiled without modification.

## Issues Encountered
None — Kotlin source directory did not yet exist (only app/src/main/cpp/ and app/src/main/res/ were present from Plan 01-01), so `mkdir -p` was needed before creating files. This is expected behavior, not a deviation.

## User Setup Required
None — no external service configuration required.

## Next Phase Readiness
- WhisperEngine public API complete — Plans 01-03 and 01-04 can now call loadModel() and transcribe()
- Plan 01-03 (model download): call `engine.loadModel(filesDir.absolutePath + "/models/ggml-small-q5_1.bin")` after HTTP download
- Plan 01-04 (smoke test): call `engine.loadModel(path)` then `engine.transcribe(floatArray)` to verify end-to-end JNI stack

---
*Phase: 01-whisper-engine-model-bootstrap*
*Completed: 2026-02-27*
