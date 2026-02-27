# Project Retrospective

*A living document updated after each milestone. Lessons feed forward into future planning.*

## Milestone: v1.0 — MVP

**Shipped:** 2026-02-27
**Phases:** 3 | **Plans:** 8

### What Was Built
- On-device Whisper inference engine via whisper.cpp JNI bridge (arm64-v8a)
- Model download flow with WiFi gating, progress UI, SHA256 verification
- Android share sheet integration for WhatsApp/Telegram voice messages
- OGG/Opus decode pipeline via MediaCodec + linear interpolation resampler
- Transcription pipeline: share -> decode -> Whisper -> hallucination filter -> result screen
- Privacy-first output with explicit Copy/Share buttons

### What Worked
- Three-phase dependency chain was the right decomposition — each phase was independently testable before the next began
- Wave-based parallelism within phases (01-02 and 01-03 ran in parallel)
- Device verification checkpoints at end of each phase caught real issues early
- discuss-phase sessions produced high-quality context — Phase 3 user decisions (no auto-clipboard, result screen instead of toast) were strictly better than the original spec
- Existing project patterns (DownloadViewModel, sealed results) established in early phases made later phases fast

### What Was Inefficient
- NDK 26.1 vs r27 — should have checked available NDK version before specifying in plans
- TRANS-01 requirement tracking slipped (marked pending when it was done in Phase 2)
- Theme.TransChat.Transparent was committed late — caught during Phase 3 build

### Patterns Established
- Sealed result classes for error handling (AudioCopier.CopyResult, UiState)
- AndroidViewModel + StateFlow for lifecycle-aware UI state
- Synchronous URI copy in onCreate before any async work
- Kotlin post-processing for Whisper output filtering (not param tuning)

### Key Lessons
1. whisper.cpp quantized model params behave differently than full-precision — always test anti-hallucination on actual model variant
2. Android URI permissions expire on backgrounding — copy bytes synchronously in onCreate, never defer to coroutine
3. MediaCodec Opus decoder always outputs 48kHz regardless of source — must check outputFormat sample rate and resample
4. User decisions during discuss-phase produced better UX than original requirements — invest time in context gathering

### Cost Observations
- Model mix: 100% opus (balanced profile)
- Sessions: 4 (project init, phases 1-2, phase 3, milestone completion)
- Notable: Entire MVP built in a single day — 8 plans averaging 4 minutes each

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v1.0 | 4 | 3 | Established wave-based parallel execution, device verification checkpoints |

### Top Lessons (Verified Across Milestones)

1. Device verification checkpoints catch integration issues that compilation alone misses
2. discuss-phase context gathering produces better designs than jumping straight to planning
