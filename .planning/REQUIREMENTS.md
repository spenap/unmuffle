# Requirements: TransChat

**Defined:** 2026-02-27
**Core Value:** Transcribe shared voice messages locally and privately — no cloud, no data leaks.

## v1 Requirements

Requirements for initial release. Each maps to roadmap phases.

### Share Intent

- [ ] **SHARE-01**: App appears in Android share sheet when user shares audio from WhatsApp/Telegram
- [ ] **SHARE-02**: App accepts `audio/*` and `application/octet-stream` MIME types
- [ ] **SHARE-03**: Audio bytes copied from content URI to private storage before processing

### Transcription

- [ ] **TRANS-01**: Audio decoded from OGG/Opus to 16kHz mono PCM using platform MediaCodec
- [ ] **TRANS-02**: Whisper small multilingual model transcribes audio on-device via whisper.cpp JNI
- [ ] **TRANS-03**: Transcription runs off main thread (no ANR)
- [ ] **TRANS-04**: Anti-hallucination params set (no_timestamps=true, no_speech_thold=0.6)

### Output

- [ ] **OUT-01**: Transcript automatically copied to clipboard on completion
- [ ] **OUT-02**: Toast notification displays transcript text

### Model Management

- [ ] **MODEL-01**: Whisper small model (ggml-small-q5_1.bin, ~190MB) downloaded on first launch
- [ ] **MODEL-02**: Download progress shown to user
- [ ] **MODEL-03**: Model stored in app private storage (filesDir)

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### UX Polish

- **UX-01**: Progress feedback during transcription (segment-callback progress bar)
- **UX-02**: User-visible error messages distinguishing decode failure vs inference failure vs OOM
- **UX-03**: Manual language selection settings screen (persist preference)
- **UX-04**: "No network" privacy indicator in UI

### Quality

- **QUAL-01**: VAD (Voice Activity Detection) pre-screening to prevent hallucination on silent audio
- **QUAL-02**: Configurable model size (tiny for low-end devices, small as default)
- **QUAL-03**: Detected language surfaced to user post-transcription

### Distribution

- **DIST-01**: Play Store listing with privacy positioning
- **DIST-02**: ProGuard/R8 configuration for release builds
- **DIST-03**: 16KB page-size compliance for Pixel 9+ devices
- **DIST-04**: Device tier testing (2GB, 4GB, 8GB RAM)

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Transcript history / saved transcripts | Violates ephemeral privacy model; users paste to their notes app |
| AI post-processing (summary, action items) | Requires cloud API or second large model |
| Translation output | Scope expansion; users can paste to Google Translate |
| Background/batch transcription | Fundamentally different product architecture |
| Real-time microphone transcription | Different architecture; requires mic permission (privacy red flag) |
| Speaker diarization | Not supported by Whisper small; requires second model |
| iOS version | Android only for v1 |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| SHARE-01 | — | Pending |
| SHARE-02 | — | Pending |
| SHARE-03 | — | Pending |
| TRANS-01 | — | Pending |
| TRANS-02 | — | Pending |
| TRANS-03 | — | Pending |
| TRANS-04 | — | Pending |
| OUT-01 | — | Pending |
| OUT-02 | — | Pending |
| MODEL-01 | — | Pending |
| MODEL-02 | — | Pending |
| MODEL-03 | — | Pending |

**Coverage:**
- v1 requirements: 12 total
- Mapped to phases: 0
- Unmapped: 12

---
*Requirements defined: 2026-02-27*
*Last updated: 2026-02-27 after initial definition*
