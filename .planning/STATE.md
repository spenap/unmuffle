# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-27)

**Core value:** Transcribe shared voice messages locally and privately — no cloud, no data leaks.
**Current focus:** Phase 1 — Whisper Engine and Model Bootstrap

## Current Position

Phase: 1 of 3 (Whisper Engine and Model Bootstrap)
Plan: 4 of 4 in current phase — COMPLETE (device verification passed)
Status: Phase 1 complete — ready for Phase 2 planning
Last activity: 2026-02-27 — 01-04 device verification passed: all smoke tests PASS on hardware

Progress: [████░░░░░░] 33%

## Performance Metrics

**Velocity:**
- Total plans completed: 3
- Average duration: 4 min
- Total execution time: 0.2 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1. Whisper Engine and Model Bootstrap | 4/4 | 16 min | 4 min |

**Recent Trend:**
- Last 5 plans: 01-01 (7 min), 01-02 (2 min), 01-03 (3 min), 01-04 Task 1 (2 min)
- Trend: fast (Kotlin Activity/layout files, no native build changes)

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
Stopped at: Phase 2 context gathered — CONTEXT.md written
Next step: /gsd:plan-phase 2
