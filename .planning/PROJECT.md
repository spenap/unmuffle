# TransChat

## What This Is

An Android service that acts as a share target for audio voice messages from messaging apps (WhatsApp, Telegram, etc.). It uses a local Whisper speech-to-text model to transcribe the audio on-device, then copies the transcript to the clipboard and shows a toast — no internet required, no data leaves the phone.

## Core Value

Transcribe shared voice messages locally and privately — no cloud, no data leaks.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] Receive audio files via Android share intent (voice messages in .opus/.ogg format)
- [ ] Transcribe audio locally using Whisper small model (~244MB)
- [ ] Support Spanish transcription for PoC (multilingual model)
- [ ] Copy transcript to clipboard automatically
- [ ] Show toast notification with transcript text
- [ ] No network permissions — fully offline operation

### Out of Scope

- Full UI/activity with history — MVP is headless share-target only
- Cloud-based transcription — defeats the privacy purpose
- Real-time/streaming transcription — batch processing of shared files only
- Non-voice audio (music, podcasts) — optimized for short voice messages
- User-configurable model size — deferred to v2
- Multi-language selection UI — deferred to v2 (hardcoded Spanish for PoC)

## Context

- Target users are Android users who receive voice messages they can't listen to (meetings, public transit, quiet environments)
- Privacy is the key differentiator — existing transcription apps send audio to cloud services
- Existing open source projects to research: vilassn/whisper_android, F-Droid Whisper app
- WhatsApp voice messages are typically .opus in .ogg container; Telegram similar
- Whisper small multilingual model supports 99 languages including Spanish
- On-device inference requires careful memory management on mid-range phones

## Constraints

- **Platform**: Android only (no iOS)
- **Language**: Kotlin
- **Privacy**: Zero network permissions — must work fully offline
- **Model**: Whisper small multilingual (~244MB) — balances accuracy and device resources
- **Input**: Short voice messages (typically under 2 minutes)

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Local-only (no cloud) | Privacy is the core value | — Pending |
| Whisper small model | Balance between accuracy and device size/speed | — Pending |
| Share target (no standalone UI) | MVP simplicity — no pages needed for v1 | — Pending |
| Kotlin | Modern Android standard, user preference | — Pending |
| Research existing projects before build-vs-fork decision | User wants to see options first | — Pending |
| Spanish for PoC, multilingual later | User's immediate need; expand in v2 | — Pending |

---
*Last updated: 2026-02-27 after initialization*
