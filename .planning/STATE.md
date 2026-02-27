# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-27)

**Core value:** Transcribe shared voice messages locally and privately — no cloud, no data leaks.
**Current focus:** All phases complete — MVP verified on device

## Current Position

Phase: 3 of 3 (End-to-End Pipeline and Output) -- COMPLETE
Plan: 2 of 2 -- COMPLETE
Status: All plans complete -- MVP verified on physical device
Last activity: 2026-02-27 — Release APK built, installed, and all end-to-end tests passed on device

Progress: [██████████] 100%

## Performance Metrics

**Velocity:**
- Total plans completed: 8
- Average duration: 4 min
- Total execution time: 0.5 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1. Whisper Engine and Model Bootstrap | 4/4 | 16 min | 4 min |
| 2. Share Intent and Audio Decoding | 2/2 | 9 min | 4.5 min |
| 3. End-to-End Pipeline and Output | 2/2 | 3 min | 1.5 min |

**Recent Trend:**
- Last 5 plans: 01-04 Task 1 (2 min), 02-01 (3 min), 02-02 Tasks 1-2 (3 min), 03-01 (3 min)
- Trend: fast (Kotlin ViewModels and Activities, no native build changes)

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- **Stack:** whisper.cpp JNI (only viable on-device Whisper runtime for Android)
- **Model:** ggml-small-q5_1.bin at 190MB — download on first use to filesDir, never bundle in APK
- **Model loading:** Always pass absolute file path to JNI — byte-array loading causes OOM on all device tiers
- **Threading:** All inference on Dispatchers.Default — main thread ANR triggers in 5s; Whisper takes 15-60s
- **URI acquisition:** Copy bytes synchronously in onCreate before launching any coroutine — permission expires on backgrounding
- **Anti-hallucination:** Default whisper params (no custom no_speech_thold override); no_timestamps=true caused q5_1 quantized model to filter real speech via is_no_speech — reverted to timestamp-based segmentation per official whisper.android example
- **01-01 Build env:** NDK 26.1 used (r27 not installed); compileSdk/targetSdk 34; core-ktx 1.13.1. Update to r27/35 on real dev machine.
- **01-01 Submodule:** whisper.cpp pinned to v1.7.4 — stable tag, not master
- **01-01 JNI pattern:** WhisperLib$Companion inner class with 6 exported functions using 00024 dollar-sign encoding
- **01-02 API surface:** WhisperEngine is the sole public API (WhisperLib/WhisperContext are internal); callers never import raw JNI types
- **01-02 Thread safety:** WhisperContext uses newSingleThreadExecutor (not Dispatchers.IO) to guarantee sequential whisper.cpp access
- **01-02 Error contract:** loadModel returns false on failure; transcribe throws IllegalStateException if not loaded
- **01-03 Launch routing:** isModelPresentFast (file.exists() && size > 0) used in MainActivity — avoids 2-3s SHA256 on every launch; full SHA256 only after download
- **01-03 DownloadManager pattern:** DownloadManager.enqueue() + 500ms coroutine polling loop — no BroadcastReceiver needed
- **01-03 Network gating:** WiFi-only by default; cellular requires user AlertDialog confirmation (allowCellular flag)
- **01-04 Smoke test exported:** SmokeTestActivity exported=true for adb launch without launcher swap — developer tool hidden from end users
- **01-04 jfk.wav in assets:** 344KB WAV bundled in assets (not runtime download) — eliminates network dependency for smoke test; JFK test uses try/catch SKIP fallback for robustness
- **01-04 Inference benchmark:** Record silence/sine inference times from SmokeTestActivity in STATE.md before Phase 2 begins — establishes UX copy baseline for speed claims
- **02-01 KDoc comment pitfall:** Avoid literal `audio/*` in KDoc block comments — Kotlin lexer treats `/*` as nested comment opener, causing unclosed comment error
- **02-01 Sealed result pattern:** AudioCopier.CopyResult with Success/TooLarge/NoContent/CopyFailed — exhaustive when-match in ShareReceiverActivity
- **02-02 Opus decode output:** MediaCodec Opus decoder always outputs 48kHz regardless of source rate — must read KEY_SAMPLE_RATE from outputFormat and resample
- **02-02 Resampler approach:** Linear interpolation handles any input rate (48kHz, 44.1kHz, etc.) with a single code path — no need for special-case 3:1 decimation
- **02-02 Verification WAVs:** Two WAVs written per decode: one at native rate (pre-resample) and one at 16kHz (Whisper-ready) for adb pull testing
- **03-01 WhisperEngine per-ViewModel:** Per-ViewModel instance, not Application singleton — 136ms load time negligible vs 12-15s inference; simpler lifecycle
- **03-01 Engine cleanup:** Deferred to process death in onCleared() — viewModelScope cancelled by super.onCleared(), cannot launch release coroutine; acceptable for single-use activity
- **03-01 Conditional clipboard toast:** Toast shown on API 32 and below only — Android 13+ shows automatic system visual confirmation
- **03-01 HallucinationFilter approach:** Pure Kotlin post-processing (no whisper.cpp param changes) — detects non-speech markers and repetitive text (6+ words with <1/3 unique)

### Pending Todos

- When setting up real dev environment: update NDK to r27.2.12479018, compileSdk/targetSdk to 35, core-ktx to 1.15.0

### Blockers/Concerns

- **Phase 3 (VAD):** Energy-threshold VAD is the safe default for silence detection; Silero VAD Android port needs evaluation during Phase 3 planning if energy-threshold proves insufficient

### Inference Timing Benchmark (Phase 1 Baseline)

- Silence inference time: 11974ms (returned "[BLANK_AUDIO]" — correct)
- Sine inference time: 14173ms (returned "(BEEPING)" — correct)
- JFK inference time: 15483ms (returned transcript — PASS)
- Model load time: 136ms
- Device tested: arm64-v8a physical device, release build, 2026-02-27

## Session Continuity

Last session: 2026-02-27
Stopped at: Completed 03-02-PLAN.md — ALL PHASES COMPLETE
Next step: Project complete. All 8 plans across 3 phases executed. MVP verified on device.
