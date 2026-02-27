# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-27)

**Core value:** Transcribe shared voice messages locally and privately — no cloud, no data leaks.
**Current focus:** Phase 1 — Whisper Engine and Model Bootstrap

## Current Position

Phase: 1 of 3 (Whisper Engine and Model Bootstrap)
Plan: 1 of 4 in current phase (01-01 complete)
Status: Executing
Last activity: 2026-02-27 — 01-01 complete: Android scaffold + whisper.cpp NDK build + JNI bridge

Progress: [█░░░░░░░░░] 8%

## Performance Metrics

**Velocity:**
- Total plans completed: 1
- Average duration: 7 min
- Total execution time: 0.12 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1. Whisper Engine and Model Bootstrap | 1/4 | 7 min | 7 min |

**Recent Trend:**
- Last 5 plans: 01-01 (7 min)
- Trend: baseline established

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
- **Anti-hallucination:** Set no_timestamps=true and no_speech_thold=0.6f in Phase 1 before any real audio testing
- **01-01 Build env:** NDK 26.1 used (r27 not installed); compileSdk/targetSdk 34; core-ktx 1.13.1. Update to r27/35 on real dev machine.
- **01-01 Submodule:** whisper.cpp pinned to v1.7.4 — stable tag, not master
- **01-01 JNI pattern:** WhisperLib$Companion inner class with 6 exported functions using 00024 dollar-sign encoding

### Pending Todos

- Plans 01-02 and 01-03 are parallel (Wave 2) — both can execute independently
- When setting up real dev environment: update NDK to r27.2.12479018, compileSdk/targetSdk to 35, core-ktx to 1.15.0

### Blockers/Concerns

- **Phase 1:** Benchmark inference speed on real hardware before committing to any UX copy about speed — community reports vary widely (15-30s mid-range, 30-90s low-end)
- **Phase 3 (VAD):** Energy-threshold VAD is the safe default for silence detection; Silero VAD Android port needs evaluation during Phase 3 planning if energy-threshold proves insufficient

## Session Continuity

Last session: 2026-02-27
Stopped at: Completed 01-01-PLAN.md — Android scaffold + whisper.cpp NDK build succeeded
Resume file: .planning/phases/01-whisper-engine-model-bootstrap/01-02-PLAN.md
