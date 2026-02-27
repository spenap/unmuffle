# Phase 1: Whisper Engine and Model Bootstrap - Context

**Gathered:** 2026-02-27
**Status:** Ready for planning

<domain>
## Phase Boundary

The Whisper inference engine compiles and transcribes audio on-device; the model downloads reliably on first launch and loads without OOM. This phase delivers: whisper.cpp compiled via NDK, model download flow, JNI bridge that can transcribe test PCM data. Share intent reception and audio decoding are Phase 2.

</domain>

<decisions>
## Implementation Decisions

### Model Source and Download
- Download from HuggingFace (official ggerganov/whisper.cpp repo)
- Model file: `ggml-small-q5_1.bin` (~190MB)
- On download failure: restart from scratch (delete partial file, re-download)
- Verify integrity with SHA256 checksum after download
- WiFi-only by default: warn user and offer override if on cellular data
- Store model in app private storage (`filesDir`)

### ABI Targets and Platform
- arm64-v8a only (no 32-bit, no x86)
- minSdk 26 (Android 8.0) / targetSdk 35
- Single architecture keeps APK small and build simple

### Project Structure
- whisper.cpp integrated as git submodule (pinned to specific tag)
- Gradle + CMake build via `externalNativeBuild`
- Application ID: `com.simonpena.transchat`
- Kotlin as app language

### First-Launch Flow
- App has a launcher icon on home screen
- Launcher opens a dedicated download screen
- Download screen shows: brief explainer text (what's being downloaded, why it's local/private), file size, download button, progress bar
- After successful download: "Ready" screen with instructions on how to use the share target ("Share a voice message from WhatsApp or Telegram to transcribe it")
- If user shares audio before model exists: redirect to download screen first

### Claude's Discretion
- Exact JNI bridge API design
- CMakeLists.txt structure and whisper.cpp build flags
- Download screen layout and styling details
- Progress bar implementation approach
- NDK version selection (r27 LTS vs r28 for 16KB page size)
- ProGuard/R8 keep rules for JNI methods

</decisions>

<specifics>
## Specific Ideas

- Research found whisper.cpp ships an official Kotlin Android example (`examples/whisper.android`) — use as reference
- Model must load from file path, never as byte array (OOM prevention)
- Set anti-hallucination params from day one: `no_timestamps=true`, `no_speech_thold=0.6`
- Always benchmark on release builds (debug builds are 2-10x slower with NDK)

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 01-whisper-engine-model-bootstrap*
*Context gathered: 2026-02-27*
