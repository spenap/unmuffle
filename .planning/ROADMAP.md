# Roadmap: TransChat

## Overview

TransChat is a headless Android share target that transcribes voice messages on-device using Whisper. The build proceeds in three phases that mirror the strict technical dependency chain: first the Whisper inference engine and model download must work in isolation; then the app can receive audio and decode it to PCM; then all components wire together into a working MVP. Each phase produces something independently testable before the next begins.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Whisper Engine and Model Bootstrap** - whisper.cpp compiles via NDK, model downloads on first launch, JNI transcribes test PCM
- [x] **Phase 2: Share Intent and Audio Decoding** - App appears in share sheet, audio bytes acquired from URI, OGG/Opus decoded to 16kHz mono PCM
- [ ] **Phase 3: End-to-End Pipeline and Output** - All components wired through ViewModel, transcript copied to clipboard, toast shown, real voice messages work

## Phase Details

### Phase 1: Whisper Engine and Model Bootstrap
**Goal**: The Whisper inference engine compiles and transcribes audio on-device; the model downloads reliably on first launch and loads without OOM
**Depends on**: Nothing (first phase)
**Requirements**: MODEL-01, MODEL-02, MODEL-03
**Success Criteria** (what must be TRUE):
  1. On a fresh install, the app presents a download prompt and shows progress while downloading the ~190MB model to private storage
  2. After download, `WhisperEngine.transcribe(FloatArray)` returns a non-empty Spanish transcript when given known-good test PCM data
  3. Model loads from file path (not byte array) — verified by no OOM crash on a 4GB RAM device
  4. A release-build benchmark exists: inference time on target hardware is measured and recorded
**Plans**: 4 plans

Plans:
- [x] 01-01-PLAN.md — Android project scaffold + whisper.cpp submodule + CMake native build + JNI C++ bridge (Wave 1)
- [x] 01-02-PLAN.md — WhisperEngine Kotlin API + WhisperLib JNI declarations + WhisperCpuConfig (Wave 2, parallel with 01-03)
- [x] 01-03-PLAN.md — Model download flow: DownloadActivity, ViewModel, ModelManager, ReadyActivity (Wave 2, parallel with 01-02)
- [x] 01-04-PLAN.md — SmokeTestActivity: end-to-end validation + release build benchmark (Wave 3)

### Phase 2: Share Intent and Audio Decoding
**Goal**: Users can share a WhatsApp or Telegram voice message to TransChat; the app acquires the audio bytes before URI permission expires and decodes them to PCM ready for Whisper
**Depends on**: Phase 1
**Requirements**: SHARE-01, SHARE-02, SHARE-03, TRANS-01
**Success Criteria** (what must be TRUE):
  1. TransChat appears in the Android share sheet when the user shares an audio file from WhatsApp or Telegram
  2. The app accepts both `audio/*` and `application/octet-stream` MIME types without rejecting the share
  3. Audio bytes are copied to private cache storage before any async work begins — verified by sharing while immediately backgrounding the app (no "permission denied" crash)
  4. A real WhatsApp `.opus` voice message decodes to audible 16kHz mono PCM — verified by writing to a WAV file and playing it back
**Plans**: 2 plans

Plans:
- [x] 02-01-PLAN.md — ShareReceiverActivity + AudioCopier + manifest intent-filters (Wave 1)
- [x] 02-02-PLAN.md — AudioDecoder + PcmResampler + WavWriter + device verification (Wave 2)

### Phase 3: End-to-End Pipeline and Output
**Goal**: Sharing a real voice message from WhatsApp or Telegram produces a transcript on the result screen with Copy and Share buttons — the complete working MVP
**Depends on**: Phase 2
**Requirements**: TRANS-02, TRANS-03, TRANS-04, OUT-01, OUT-02
**Success Criteria** (what must be TRUE):
  1. Sharing a WhatsApp voice message in Spanish results in the transcript text appearing on the result screen within the inference time window (no manual steps)
  2. The result screen displays the transcript with Copy and Share buttons for user-controlled output
  3. Inference runs off the main thread — verified by sharing a voice message and confirming no ANR dialog appears during the 15-60s processing window
  4. Sharing a silent or sub-2-second audio file does not produce hallucinated text — the result screen shows "No speech detected"
**Plans**: 2 plans

Plans:
- [x] 03-01-PLAN.md — HallucinationFilter + TranscriptionViewModel + TranscriptionActivity + layout + ShareReceiverActivity wiring (Wave 1)
- [ ] 03-02-PLAN.md — Release APK build + end-to-end device verification checkpoint (Wave 2)

## Progress

**Execution Order:**
Phases execute in strict numeric order: 1 → 2 → 3

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Whisper Engine and Model Bootstrap | 4/4 | Complete | 2026-02-27 |
| 2. Share Intent and Audio Decoding | 2/2 | Complete | 2026-02-27 |
| 3. End-to-End Pipeline and Output | 1/2 | In Progress | - |
