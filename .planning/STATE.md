# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-27)

**Core value:** Transcribe shared voice messages locally and privately — no cloud, no data leaks.
**Current focus:** Phase 1 — Whisper Engine and Model Bootstrap

## Current Position

Phase: 1 of 3 (Whisper Engine and Model Bootstrap)
Plan: 0 of ? in current phase
Status: Ready to plan
Last activity: 2026-02-27 — Roadmap created; research complete; ready to plan Phase 1

Progress: [░░░░░░░░░░] 0%

## Performance Metrics

**Velocity:**
- Total plans completed: 0
- Average duration: —
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

**Recent Trend:**
- Last 5 plans: —
- Trend: —

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

### Pending Todos

None yet.

### Blockers/Concerns

- **Phase 1:** Benchmark inference speed on real hardware before committing to any UX copy about speed — community reports vary widely (15-30s mid-range, 30-90s low-end)
- **Phase 3 (VAD):** Energy-threshold VAD is the safe default for silence detection; Silero VAD Android port needs evaluation during Phase 3 planning if energy-threshold proves insufficient

## Session Continuity

Last session: 2026-02-27
Stopped at: Phase 1 context gathered
Resume file: .planning/phases/01-whisper-engine-model-bootstrap/01-CONTEXT.md
