# Milestones: TransChat

## v1.1 Polish & Distribution — Shipped 2026-02-28

**Phases:** 4 (2 executed via GSD, 1 manual, 1 deferred) | **Plans:** 3 | **Kotlin LOC:** 2,676

Surgical R8 shrinking, determinate progress bar driven by whisper.cpp callbacks, and branch rename. F-Droid distribution prep deferred to next milestone pending GitHub readiness (license, README, CI).

**Key Accomplishments:**
1. Replaced blanket ProGuard keep with surgical R8 rules — APK shrunk 8.7% (6.0→5.4 MB)
2. C++ atomic progress state bridges whisper.cpp callback → JNI → Kotlin polling loop (200ms)
3. Determinate 0-100% progress bar replaces indeterminate spinner during Whisper inference
4. Full release pipeline verified end-to-end on physical arm64-v8a device
5. Default branch renamed master→main (manual)

**Requirements:** 4/9 complete, 4 deferred (DIST-*), 1 manual (REPO-01)

### Known Gaps
- **DIST-01**: MIT LICENSE file — deferred to next milestone
- **DIST-02**: Fastlane metadata — deferred to next milestone
- **DIST-03**: fdroiddata YAML recipe — deferred to next milestone
- **DIST-04**: versionCode bump — deferred to next milestone

**Archive:** `milestones/v1.1-ROADMAP.md`, `milestones/v1.1-REQUIREMENTS.md`

---

## v1.0 MVP — Shipped 2026-02-27

**Phases:** 3 | **Plans:** 8 | **Commits:** 42 | **Kotlin LOC:** 2,635

On-device voice message transcription. Share a WhatsApp or Telegram voice message, see a progress spinner during Whisper inference, read the transcript on a result screen, copy or share it. Zero network permissions — fully private.

**Key Accomplishments:**
1. whisper.cpp compiles via NDK with JNI bridge — on-device Whisper inference at 12-15s per message
2. Model download flow with WiFi gating, progress UI, and SHA256 verification
3. Android share sheet integration accepting audio/* and application/octet-stream MIME types
4. OGG/Opus to 16kHz mono PCM decode pipeline via MediaCodec + linear interpolation resampler
5. Full transcription pipeline: share → decode → Whisper → hallucination filter → result screen
6. Privacy-first output: explicit Copy/Share buttons, no auto-clipboard, no network

**Requirements:** 12/12 complete (3 evolved during implementation — all improvements)
**Archive:** `milestones/v1.0-ROADMAP.md`, `milestones/v1.0-REQUIREMENTS.md`
