# Phase 5: Transcription Progress Bar - Context

**Gathered:** 2026-02-27
**Status:** Ready for planning

<domain>
## Phase Boundary

Replace the indeterminate spinner with a real 0-100% determinate progress bar during Whisper inference, driven by whisper.cpp progress callback values through JNI. The existing CircularProgressIndicator in activity_transcription.xml switches from indeterminate to determinate mode.

</domain>

<decisions>
## Implementation Decisions

### Progress indicator style
- Circular determinate indicator (keep existing CircularProgressIndicator, switch from indeterminate to determinate)
- Centered layout — same position as current spinner, no layout changes needed
- Minimal visual disruption from v1.0 design

### Progress text and labels
- Show percentage number: "Transcribing... 42%"
- Static label text — "Transcribing..." stays the same throughout inference, only the percentage updates
- No phase-aware labels (no "Preparing audio..." during decode)

### Transition behavior
- Pre-inference (decode + resample, ~1s): indeterminate spinner (current behavior preserved)
- During inference (12-15s): determinate circular progress bar advancing 0-100%
- On completion: immediate switch to result screen — no artificial delay at 100%, users already waited 12-15s
- State flow: `UiState.Processing` (indeterminate) → `UiState.Processing(progress)` (determinate) → `UiState.Success`/`NoSpeech`/`Error`

### Claude's Discretion
- JNI callback mechanism (AtomicInteger polling vs upcall — research decided AtomicInteger polling)
- Exact R8 keep rule for ProgressCallback (placeholder ready from Phase 4)
- How to change UiState.Processing from `object` to `data class` (add progress percentage field)
- Progress update frequency / smoothing
- Whether CircularProgressIndicator needs XML changes or just runtime property changes

</decisions>

<specifics>
## Specific Ideas

- The v1.1 research (ARCHITECTURE.md) recommended changing `UiState.Processing` from `object` to `data class Processing(val progress: Int = -1)` where -1 means indeterminate
- Research recommended AtomicInteger polling pattern to avoid JNI upcall threading pitfalls
- Phase 4 left a commented ProgressCallback keep rule in proguard-rules.pro ready to uncomment

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 05-transcription-progress-bar*
*Context gathered: 2026-02-27*
