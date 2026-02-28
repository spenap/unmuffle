# TransChat

## What This Is

An Android share target that transcribes voice messages on-device using Whisper. Share a voice message from WhatsApp or Telegram, see a real-time progress bar during inference, read the transcript on a result screen, copy or share it. Zero network permissions — fully private, no data leaves the phone.

## Core Value

Transcribe shared voice messages locally and privately — no cloud, no data leaks.

## Requirements

### Validated

- Receive audio files via Android share intent (voice messages in .opus/.ogg format) — v1.0
- Transcribe audio locally using Whisper small model (~190MB q5_1 quantized) — v1.0
- Support Spanish transcription (multilingual model auto-detects language) — v1.0
- Display transcript on result screen with Copy and Share buttons — v1.0
- No network permissions — fully offline operation — v1.0
- Anti-hallucination post-processing for silent/noise audio — v1.0
- Surgical R8 rules for release builds (8.7% APK size reduction) — v1.1
- Determinate 0-100% progress bar driven by whisper.cpp callbacks — v1.1
- Default branch renamed master→main — v1.1

### Active

- [ ] MIT LICENSE file at repository root
- [ ] README with project description, build instructions, screenshots
- [ ] CI pipeline (GitHub Actions for build verification)
- [ ] F-Droid compatible build configuration and metadata

### Out of Scope

- Transcript history / saved transcripts — violates ephemeral privacy model
- Cloud-based transcription — defeats the privacy purpose
- Real-time/streaming transcription — batch processing of shared files only
- Background/batch transcription — fundamentally different product architecture
- Speaker diarization — not supported by Whisper small
- iOS version — Android only
- AI post-processing (summary, action items) — requires cloud API or second large model

## Context

Shipped v1.1 with 2,676 LOC Kotlin + whisper.cpp native layer.
Tech stack: Kotlin, whisper.cpp (v1.7.4) via JNI, NDK 26.1, MediaCodec, Jetpack ViewModel + StateFlow.
APK size: 5.4 MB (release, arm64-v8a, after R8 shrinking).
Inference: 12-15s on arm64-v8a test device.
User feedback: "Better than the built-in WhatsApp transcript feature."

## Constraints

- **Platform**: Android only (no iOS)
- **Language**: Kotlin + C++ (JNI bridge)
- **Privacy**: Zero network permissions — must work fully offline
- **Model**: Whisper small multilingual q5_1 (~190MB) — balances accuracy and device resources
- **Input**: Short voice messages (typically under 2 minutes)

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Local-only (no cloud) | Privacy is the core value | Good — key differentiator |
| Whisper small q5_1 model | Balance between accuracy and device size/speed | Good — 12-15s inference, accurate Spanish |
| whisper.cpp via JNI | Only viable on-device Whisper runtime for Android | Good — stable, well-tested |
| File path model loading (not byte array) | Byte array causes OOM on all device tiers | Good — 136ms load time |
| Synchronous URI copy in onCreate | Permission expires on backgrounding | Good — prevented crash |
| Kotlin post-processing for hallucination | whisper params (no_timestamps) filtered real speech on q5_1 | Good — catches markers + repetition |
| Explicit Copy/Share buttons (no auto-clipboard) | Clipboard readable by other apps on older Android | Good — privacy improvement |
| newSingleThreadExecutor for whisper.cpp | Guarantees sequential access to non-thread-safe native code | Good — no race conditions |
| Linear interpolation resampler | Handles any input rate (48kHz, 44.1kHz) with single code path | Good — simple and universal |
| Surgical R8 over blanket keep | Enables real shrinking while protecting JNI + sealed classes | Good — 8.7% APK reduction |
| AtomicInteger polling for progress | Avoids JNI upcall threading complexity | Good — clean, 200ms polling |
| F-Droid as distribution target | Open-source, privacy-aligned, no Google Play dependency | Pending — deferred to after GitHub readiness |

---
*Last updated: 2026-02-28 after v1.1 milestone completion*
