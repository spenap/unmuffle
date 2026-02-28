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

## Milestone: v1.1 — Polish & Distribution

**Shipped:** 2026-02-28
**Phases:** 4 (2 GSD, 1 manual, 1 deferred) | **Plans:** 3

### What Was Built
- Surgical R8 rules replacing blanket ProGuard keep — 8.7% APK size reduction
- C++ atomic progress state with JNI bridge and Kotlin 200ms polling loop
- Determinate CircularProgressIndicator (0-100%) driven by real whisper.cpp callbacks
- Default branch renamed master→main (manual)

### What Worked
- Phase 4→5 dependency was well-designed: R8 rules in place before adding ProgressCallback interface
- AtomicInteger polling pattern avoided JNI upcall threading complexity entirely
- Physical device verification caught nothing new — v1.0 checkpoint discipline paid off
- Milestone was scoped tightly: two focused features + two housekeeping tasks

### What Was Inefficient
- F-Droid distribution prep (Phase 6) was premature — needed GitHub readiness (license, README, CI) first
- Phase 7 (branch rename) was trivial enough to do manually but occupied a full roadmap phase
- discuss-phase for Phase 6 captured detailed F-Droid decisions that will need re-evaluation when the work actually happens

### Patterns Established
- AtomicInteger polling for C++→Kotlin progress data (avoids JNI upcall complexity)
- UiState.Processing as data class with progress field (supports both indeterminate and determinate states)
- Manual completion path for trivial phases (no need to plan/execute everything via GSD)

### Key Lessons
1. Distribution prep depends on GitHub readiness — license, README, and CI should come before F-Droid metadata
2. Trivial tasks (branch rename) don't need full GSD phase treatment — `/gsd:quick` or manual is better
3. Context gathered too early for deferred work may go stale — discuss-phase should happen close to execution
4. AskUserQuestion tool depends on TTY allocation — non-TTY environments silently return empty answers

### Cost Observations
- Model mix: 100% opus (balanced profile)
- Sessions: 3 (phases 4-5 execution, phase 6 context, milestone completion)
- Notable: Both feature phases (4+5) executed in one session; milestone took longer due to scope revision

---

## Cross-Milestone Trends

### Process Evolution

| Milestone | Sessions | Phases | Key Change |
|-----------|----------|--------|------------|
| v1.0 | 4 | 3 | Established wave-based parallel execution, device verification checkpoints |
| v1.1 | 3 | 4 | Learned to scope distribution work after GitHub readiness; manual path for trivial phases |

### Top Lessons (Verified Across Milestones)

1. Device verification checkpoints catch integration issues that compilation alone misses
2. discuss-phase context gathering produces better designs than jumping straight to planning
3. Scope distribution/publishing work AFTER repo readiness (license, README, CI) — not before
