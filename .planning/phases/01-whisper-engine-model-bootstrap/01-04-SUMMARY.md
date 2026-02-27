---
phase: 01-whisper-engine-model-bootstrap
plan: "04"
subsystem: testing
tags: [android, kotlin, whisper.cpp, jni, smoke-test, benchmark, pcm, wav, assets]

# Dependency graph
requires:
  - phase: 01-02
    provides: WhisperEngine.kt (loadModel/transcribe/release API), WhisperContext.getSystemInfo()
  - phase: 01-03
    provides: ModelManager.modelFile(context) path helper, isModelPresentFast() existence check

provides:
  - SmokeTestActivity — developer screen that validates end-to-end JNI pipeline
  - jfk.wav bundled in app assets for real-speech transcription validation
  - activity_smoke_test.xml layout with scrollable monospace log + Run Tests button
  - AndroidManifest.xml SmokeTestActivity declaration (exported=true, adb launchable)

affects:
  - Phase 2 planning — inference timing from SmokeTestActivity establishes the benchmark baseline

# Tech tracking
tech-stack:
  added:
    - jfk.wav 16kHz mono WAV (344KB) — 16-bit PCM speech sample from whisper.cpp submodule
  patterns:
    - WAV-to-float decode inline in Activity: skip 44-byte header, read 16-bit LE PCM, divide by 32768f
    - lifecycleScope.launch { withContext(Dispatchers.Default) { ... } } for long-running inference in Activity
    - Silence FloatArray(16000 * 3) { 0f } and sine FloatArray(16000 * 3) { i -> sin(440Hz) } as synthetic test vectors
    - Append-to-TextView log pattern via withContext(Dispatchers.Main) for thread-safe UI updates from background

key-files:
  created:
    - app/src/main/java/com/simonpena/transchat/SmokeTestActivity.kt
    - app/src/main/res/layout/activity_smoke_test.xml
    - app/src/main/assets/jfk.wav
  modified:
    - app/src/main/AndroidManifest.xml (added SmokeTestActivity with exported=true)

key-decisions:
  - "SmokeTestActivity is exported=true so it can be launched via adb without making it the LAUNCHER — avoids accidental production UX change"
  - "jfk.wav bundled in assets rather than downloaded at runtime — 344KB is acceptable bundle size for a developer tool, simplifies test setup"
  - "WAV header skipped with fixed 44-byte offset — standard PCM WAV; jfk.wav from whisper.cpp samples always conforms"
  - "JFK test uses try/catch with SKIP log — graceful fallback if assets not bundled (e.g. stripped release variant)"
  - "engine.release() called at end of runSmokeTests() and in onDestroy() — belt-and-suspenders native memory cleanup"

patterns-established:
  - "Smoke test pattern: load model, run synthetic tests first (silence/sine — no network needed), then real-speech test"
  - "Inference timing: record System.currentTimeMillis() before/after engine.transcribe() — captures wall-clock time for UX copy decisions"

requirements-completed: [MODEL-01, MODEL-02, MODEL-03]

# Metrics
duration: 2min
completed: 2026-02-27
---

# Phase 1 Plan 04: SmokeTestActivity Summary

**Developer smoke test Activity that validates the end-to-end JNI pipeline: model load from filesDir, silence produces empty transcript (anti-hallucination active), 440Hz sine produces empty transcript, and jfk.wav produces real speech output — with millisecond timing for the Phase 1 benchmark baseline**

## Performance

- **Duration:** ~2 min
- **Started:** 2026-02-27T13:11:08Z
- **Completed:** 2026-02-27T13:12:44Z
- **Tasks:** 1 of 2 (Task 2 is checkpoint:human-verify — awaiting device testing)
- **Files modified:** 4 (3 created, 1 modified)

## Accomplishments
- SmokeTestActivity.kt created: loads model via WhisperEngine.loadModel(), runs 3 tests in sequence (silence, sine, jfk.wav), logs PASS/FAIL/WARN/SKIP with inference ms for each test
- jfk.wav (344KB, 16kHz mono, 16-bit PCM) copied from whisper.cpp submodule samples to app/src/main/assets/ — available to assets.open() at runtime
- activity_smoke_test.xml: LinearLayout with ScrollView-wrapped monospace TextView (tv_log) and Run Tests button (btn_run_tests) — layout matches plan exactly
- AndroidManifest.xml updated with SmokeTestActivity declared exported=true for adb launch
- assembleRelease BUILD SUCCESSFUL — all Plans 01+02+03+04 compile together, 48 tasks executed

## Task Commits

Task 1 was committed atomically:

1. **Task 1: SmokeTestActivity.kt, layout, manifest, jfk.wav** - `2679056` (feat)

**Plan metadata:** (docs commit follows — includes SUMMARY.md and STATE.md)

_Note: Task 2 is a checkpoint:human-verify — requires physical device testing and is not auto-executed._

## Files Created/Modified
- `app/src/main/java/com/simonpena/transchat/SmokeTestActivity.kt` - Developer smoke test: model load, silence/sine/JFK tests, timing display
- `app/src/main/res/layout/activity_smoke_test.xml` - ScrollView + monospace TextView log + Run Tests button
- `app/src/main/assets/jfk.wav` - 344KB 16kHz mono WAV from whisper.cpp samples for real-speech test
- `app/src/main/AndroidManifest.xml` - Added SmokeTestActivity with exported=true and label "TransChat Smoke Test"

## Decisions Made
- SmokeTestActivity exported=true to allow `adb shell am start -n com.simonpena.transchat/.SmokeTestActivity` without making it the launcher — developer tool stays hidden from end users
- jfk.wav bundled in assets (344KB acceptable for dev tool) rather than runtime download — eliminates network dependency for smoke test
- JFK test wrapped in try/catch with SKIP fallback — future stripped release builds can omit assets without crashing the test runner
- engine.release() called both at end of runSmokeTests coroutine and in onDestroy() — prevents native memory leak if Activity is closed while tests are running

## Deviations from Plan

None - plan executed exactly as written. All four files match the plan specification. The jfk.wav sample was found at `app/src/main/cpp/whisper.cpp/samples/jfk.wav` as expected.

## Issues Encountered
None. The assets directory did not yet exist (app/src/main/assets/ was not created by previous plans); `mkdir -p` created it before copying jfk.wav — expected prerequisite.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- SmokeTestActivity is ready for device testing via Task 2 (checkpoint:human-verify)
- Install APK on arm64-v8a device running Android 8.0+ (minSdk 26)
- After model download completes on device: `adb shell am start -n com.simonpena.transchat/.SmokeTestActivity`
- Record silence inference time and sine inference time in STATE.md before Phase 2 begins
- Phase 1 is NOT complete until Task 2 checkpoint is approved (silence/JFK PASS on real hardware)

## Self-Check: PASSED

Files verified present:
- SmokeTestActivity.kt: FOUND
- activity_smoke_test.xml: FOUND
- jfk.wav (assets): FOUND
- 01-04-SUMMARY.md: FOUND

Commits verified:
- 2679056 (Task 1: SmokeTestActivity + layout + manifest + jfk.wav): FOUND

Build verification:
- assembleRelease: BUILD SUCCESSFUL (48 tasks, 10s)

Verification checks (from plan):
- `grep "SmokeTestActivity" AndroidManifest.xml`: FOUND (exported=true)
- `grep "no_speech_thold" whisper_jni.cpp`: FOUND (= 0.6f)
- `grep "no_timestamps" whisper_jni.cpp`: FOUND (= true)
- `grep "max_tokens" whisper_jni.cpp`: FOUND (= 224)

---
*Phase: 01-whisper-engine-model-bootstrap*
*Completed: 2026-02-27 (Task 1 only — Task 2 awaiting device verification)*
