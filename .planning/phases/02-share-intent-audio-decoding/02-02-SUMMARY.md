---
phase: 02-share-intent-audio-decoding
plan: "02"
subsystem: android-audio-decode
tags: [android, mediacodec, mediaextractor, opus, pcm, resampling, wav]

# Dependency graph
requires:
  - phase: 02-share-intent-audio-decoding
    plan: "01"
    provides: "ShareReceiverActivity with cached audio file, AudioCopier for URI-to-cache copy"
provides:
  - "AudioDecoder: MediaExtractor+MediaCodec decode pipeline for OGG/Opus and MP3"
  - "PcmResampler: any-rate to 16kHz mono float32 conversion for Whisper"
  - "WavWriter: standard WAV file writer for decode verification via adb pull"
  - "ShareReceiverActivity with full decode pipeline: copy -> decode -> resample -> WAV -> log"
affects: [03-end-to-end-pipeline]

# Tech tracking
tech-stack:
  added: []
  patterns: [mediacodec-synchronous-decode, linear-interpolation-resample, verification-wav-output]

key-files:
  created:
    - app/src/main/java/com/simonpena/transchat/AudioDecoder.kt
    - app/src/main/java/com/simonpena/transchat/PcmResampler.kt
    - app/src/main/java/com/simonpena/transchat/WavWriter.kt
  modified:
    - app/src/main/java/com/simonpena/transchat/ShareReceiverActivity.kt

key-decisions:
  - "Linear interpolation resampler handles any input rate (48kHz, 44.1kHz, etc.) with a single code path"
  - "Two verification WAVs per decode: one at native rate (pre-resample) and one at 16kHz (Whisper-ready)"
  - "Decode runs on Dispatchers.Default via withContext -- not Dispatchers.IO -- matching WhisperEngine threading"

patterns-established:
  - "MediaCodec synchronous polling: dequeueInputBuffer/dequeueOutputBuffer with 10ms timeout, handle INFO_OUTPUT_FORMAT_CHANGED for actual output rate"
  - "DecodedAudio data class as intermediate: ShortArray + sampleRate + channels for pipeline flexibility"
  - "Verification WAV pattern: write WAV to cacheDir for adb pull testing at each pipeline stage"

requirements-completed: [TRANS-01]

# Metrics
duration: 3min
completed: 2026-02-27 (Tasks 1-2 only; Task 3 checkpoint pending)
---

# Phase 2 Plan 02: AudioDecoder + PcmResampler + WavWriter Summary

**MediaCodec OGG/Opus decode pipeline with linear-interpolation resampling to 16kHz mono float32 and WAV verification output, wired into ShareReceiverActivity**

## Status

**PAUSED AT CHECKPOINT** -- Tasks 1-2 complete, Task 3 (device verification) awaiting human approval.

## Performance

- **Duration:** 3 min (Tasks 1-2 only)
- **Started:** 2026-02-27T14:35:45Z
- **Paused:** 2026-02-27T14:38:31Z
- **Tasks:** 2/3 complete (Task 3 is checkpoint:human-verify)
- **Files modified:** 4

## Accomplishments
- AudioDecoder decodes OGG/Opus and MP3 via MediaExtractor+MediaCodec to DecodedAudio(ShortArray, sampleRate, channels)
- PcmResampler converts any decoded audio to 16kHz mono float32 using stereo-to-mono, linear interpolation resample, and int16-to-float32 normalization
- WavWriter writes standard 44-byte RIFF/WAV header + int16 PCM for verification via adb pull
- ShareReceiverActivity now runs the full decode pipeline: AudioDecoder.decode -> WavWriter.write (native rate) -> PcmResampler.toWhisperFormat -> WavWriter.write (16kHz) -> log duration and sample count

## Task Commits

Each task was committed atomically:

1. **Task 1: AudioDecoder, PcmResampler, and WavWriter** - `608eb27` (feat)
2. **Task 2: Wire decode pipeline into ShareReceiverActivity** - `b5dc7d9` (feat)
3. **Task 3: Device verification** - PENDING (checkpoint:human-verify)

## Files Created/Modified
- `app/src/main/java/com/simonpena/transchat/AudioDecoder.kt` - MediaExtractor+MediaCodec decode pipeline returning DecodedAudio
- `app/src/main/java/com/simonpena/transchat/PcmResampler.kt` - Resample any rate to 16kHz mono float32 via linear interpolation
- `app/src/main/java/com/simonpena/transchat/WavWriter.kt` - Standard 44-byte RIFF/WAV header writer for verification
- `app/src/main/java/com/simonpena/transchat/ShareReceiverActivity.kt` - Replaced placeholder coroutine with full decode pipeline

## Decisions Made
- Linear interpolation resampler instead of special-case 3:1 decimation -- handles 48kHz (Opus), 44.1kHz (MP3), and any other rate with one code path
- Two verification WAVs per decode at native and 16kHz rates -- enables comparison and confirms both decode quality and resample correctness
- Decode on Dispatchers.Default (CPU-bound work) not Dispatchers.IO (blocking I/O) -- consistent with WhisperEngine threading convention

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Task 3 checkpoint requires user to: install on device, share WhatsApp voice message, adb pull WAV, verify playback
- After verification, Phase 2 is complete and Phase 3 can begin (wiring WhisperEngine.transcribe to decoded FloatArray)
- ShareReceiverActivity already has the placeholder comment for Phase 3: "Phase 3 will pass 'result' to WhisperEngine.transcribe() here"

## Self-Check: PENDING

Self-check deferred until Task 3 checkpoint is resolved and plan completes fully.

---
*Phase: 02-share-intent-audio-decoding*
*Paused: 2026-02-27 (Tasks 1-2 complete, Task 3 checkpoint pending)*
