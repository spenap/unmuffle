# Project Research Summary

**Project:** TransChat — Android Audio Transcription App (Share Target)
**Domain:** On-device ML inference utility — single-purpose share target for voice message transcription
**Researched:** 2026-02-27
**Confidence:** HIGH

---

## Executive Summary

TransChat is a fire-and-forget share-target utility: the user shares a WhatsApp or Telegram voice message, the app transcribes it on-device using Whisper, and copies the result to the clipboard. The entire interaction model is one-way and ephemeral — no accounts, no network, no stored data. Research across all four domains confirms this narrow scope is not a limitation but the product's core competitive advantage. Cloud-based competitors (WAVO, Letterly) are held back by subscription models and privacy concerns; WhatsApp's own native transcription is limited to six languages. TransChat's position — 99-language, offline, no-account transcription — is genuinely unoccupied in the market.

The recommended implementation is: whisper.cpp via JNI (the only production-validated on-device Whisper runtime for Android), `ggml-small-q5_1.bin` at 190 MB (best accuracy-to-size tradeoff), MediaExtractor + MediaCodec for Opus decoding (zero-dependency, platform-native), and coroutines scoped to the Activity lifecycle (no foreground service needed for MVP). All four researchers converge on this stack independently. The architecture is a flat six-component linear pipeline with no server, no database, and no background scheduler — complexity is concentrated in the JNI bridge and the NDK build, not in business logic.

The top risks are not architectural but operational: model loading via byte arrays causes OOM crashes on any device (use file-path loading); inference on the main thread triggers ANR after 5 seconds (use `Dispatchers.Default`); URI permissions from WhatsApp are revoked before background workers can read them (copy bytes synchronously in `onCreate`); and Whisper hallucinates fluent text on silent audio (set `no_timestamps=true` and `no_speech_thold=0.6`). All four of these must be addressed in Phase 1 or they will invalidate the entire pipeline.

---

## Key Findings

### Recommended Stack

whisper.cpp is the unambiguous choice — it ships an official Android Kotlin example, compiles cleanly via CMake NDK, and consumes GGML model files without conversion tooling. All alternative runtimes (sherpa-onnx, TFLite, WhisperKitAndroid, ONNX Runtime directly) introduce either conversion complexity, accuracy regression, or unmaintained code paths. The `ggml-small-q5_1.bin` model at 190 MB provides multilingual accuracy at 7-9% WER; `ggml-tiny.bin` at 75 MB is available as a fallback for low-end devices. The model must be downloaded on first use — bundling it in the APK violates Play Store size limits.

**Core technologies:**
- **whisper.cpp v1.7.x+** (target v1.8.3): on-device Whisper inference — only runtime with official Android JNI example and active maintenance
- **Kotlin 2.x + Android SDK minSdk 26 / targetSdk 35**: app language and platform — Kotlin coroutines integrate natively; API 26+ has reliable native Opus decoding
- **Android NDK r27 LTS**: compiles whisper.cpp C++ — required for CMake/JNI; r27 is current LTS (note: r28+ needed for 16KB page-size compliance on Pixel 9+)
- **MediaExtractor + MediaCodec (platform-native)**: decodes OGG/Opus from WhatsApp and Telegram — zero added APK size; supported since API 21
- **Kotlin Coroutines 1.8.x on `Dispatchers.Default`**: async CPU-bound inference — lightweight, cancellable, correct dispatcher for compute work
- **`ggml-small-q5_1.bin` (190 MB)**: the model — multilingual, acceptable WER, fits on mid-range devices
- **Download-on-first-use to `filesDir`**: model distribution — APK bundling not viable; this is the same pattern used by whisperIME

### Expected Features

**Must have (table stakes) — v1:**
- Share intent reception for `audio/*` and `application/octet-stream` — the only entry point
- OGG/Opus decode via MediaExtractor + MediaCodec — WhatsApp and Telegram both use this format
- Whisper small inference via JNI — the core value, no substitutes
- Clipboard output + completion Toast — the only expected output destination
- Progress feedback during transcription — mandatory; without it users assume crash (10-60s wait)
- Graceful error states with user-visible messages — distinguish decode failure from inference failure from OOM
- On-device processing (no network during transcription) — table stakes for the privacy-focused positioning
- Multilingual auto-detection — Whisper provides this natively; voice messages are inherently multilingual
- First-run model download UI — progress bar, download size, WiFi awareness; required before any transcription works

**Should have (competitive differentiators) — v1 or fast follow:**
- Manual language selection override — reduces wrong-language transcription on short clips; settings screen with persist
- Real progress bar driven by Whisper segment callbacks — improves perceived performance significantly
- Explicit "no network" indicator in UI — communicates the privacy guarantee that distinguishes TransChat from WAVO/Letterly
- VAD (Voice Activity Detection) pre-screening — prevents hallucination on silent audio; lightweight Silero VAD or energy threshold

**Defer to v2+:**
- Transcript history / saved transcripts — violates the ephemeral privacy model; users paste to their notes app
- AI post-processing (summary, action items) — requires cloud API or second large model; out of scope
- Translation output — scope expansion; users can paste to Google Translate
- Background/batch transcription — fundamentally different product
- Real-time microphone transcription — different architecture entirely; requires mic permission (privacy red flag)
- Speaker diarization — not supported by Whisper small; would require second model

### Architecture Approach

TransChat uses a flat, linear six-component pipeline with no server, no database, and no persistent background scheduler. The entire flow runs within the Activity lifecycle scope using coroutines, which eliminates foreground service complexity for MVP. Each component is independently testable and has a single responsibility: `ShareActivity` (intent entry), `AudioLoader` (URI to cached file), `AudioPreprocessor` (file to 16kHz mono float32 PCM), `WhisperEngine` (PCM to transcript via JNI), `TranscriptOutputHandler` (clipboard + toast), and `TranscriptionViewModel` (orchestration and UI state). The WhisperEngine is a lazy singleton — the 190 MB model loads once and stays resident, released only under memory pressure via `onTrimMemory`.

**Major components:**
1. **ShareActivity** — transparent Activity; receives `ACTION_SEND` intent; copies URI bytes synchronously; shows progress dialog; scopes all coroutines
2. **TranscriptionViewModel** — orchestrates the pipeline via `viewModelScope`; holds `UiState` as a `Flow`; the only component that touches all others
3. **AudioLoader** — copies `content://` URI bytes to private cache file before URI permission expires; runs on `Dispatchers.IO`
4. **AudioPreprocessor** — MediaExtractor + MediaCodec decode; mono downmix; linear resample to 16kHz; runs on `Dispatchers.Default`
5. **WhisperEngine** — lazy singleton holding native context pointer; calls `whisper_full()` via JNI; manages model lifecycle and memory pressure
6. **whisper_jni.cpp + whisper.cpp (CMake)** — C++ JNI bridge and transformer inference; compiled for `arm64-v8a` (primary) and `armeabi-v7a` (legacy)

**Key patterns:**
- Audio bytes copied to private cache file immediately in `onCreate` before any async work (Pitfall 3 prevention)
- Model loaded from file path, never as byte array (Pitfall 1 prevention)
- All inference on `Dispatchers.Default`, never `Dispatchers.Main` (Pitfall 5 prevention)
- Sealed `TranscriptionResult` type (`Success`, `DecodeError`, `InferenceError`, `OomError`) — no silent exception swallowing
- `try/finally` cleanup of temp audio files after every transcription (Pitfall 14 prevention)

### Critical Pitfalls

1. **Model byte-array OOM (Pitfall 1)** — Loading 190+ MB model via `readBytes()` creates 3 JVM copies and crashes even on 4GB devices. Prevention: always pass absolute file path to `whisper_init()` / JNI load function, never read into a Java byte array.

2. **URI permission revoked before inference (Pitfall 3)** — WhatsApp/Telegram `content://` URIs expire when the Activity goes to background. Prevention: call `contentResolver.openInputStream(uri)` and copy bytes to `cacheDir` synchronously in `onCreate`, before launching any coroutine.

3. **ANR from main-thread inference (Pitfall 5)** — Whisper small takes 15-60 seconds on mid-range hardware; any invocation on `Dispatchers.Main` triggers an ANR dialog in 5 seconds. Prevention: always launch inference with `viewModelScope.launch(Dispatchers.Default)`.

4. **Whisper hallucination on silent audio (Pitfall 4)** — Whisper generates fluent fabricated text from silence or sub-2-second clips instead of returning empty output. Prevention: set `no_timestamps = true` and `no_speech_thold = 0.6f` in whisper params for Phase 1; add lightweight VAD pre-screening in Phase 2.

5. **Debug build performance invalidates benchmarks (Pitfall 2)** — Debug NDK builds disable NEON/FMA optimizations; inference appears 2-10x slower than release. Prevention: always benchmark against a release build; establish a baseline in Phase 1 before any optimization decisions.

**Additional phase-critical pitfalls:**
- **16KB page size (Pitfall 11)** — Google Play requires NDK r28+ for API 35+ submissions targeting Pixel 9+ devices. Set `target_link_options(... "-Wl,-z,max-page-size=16384")` in CMakeLists.txt from day one.
- **R8 breaking JNI bindings (Pitfall 12)** — Release builds obfuscate JNI method names. Add ProGuard keep rules for all `native` methods before first release build.
- **Model distribution APK rejection (Pitfall 10)** — 190 MB model cannot be bundled in APK. Design the first-run download flow before writing any model-loading code.
- **Repetition loops on degraded audio (Pitfall 9)** — Set `max_tokens = 224` cap and detect repeated n-grams post-inference to prevent clipboard flooding.

---

## Implications for Roadmap

All four research files independently suggest the same build sequence. Dependencies are strict: JNI must compile before decoding can be tested; decoding must produce valid PCM before inference can run; inference must produce output before UX can be designed. The phase structure below mirrors the dependency graph.

### Phase 1: Foundation — JNI Bridge, Build Pipeline, and Model Loading

**Rationale:** Everything else is blocked on whisper.cpp compiling and the model loading successfully. This phase has the most irreversible decisions (NDK version, ABI targets, model distribution strategy, file-path loading) and the most critical pitfalls. Get this wrong and every downstream phase is built on a broken foundation.

**Delivers:** whisper.cpp compiles for `arm64-v8a`; model downloads on first run and loads from `filesDir` via file path; `WhisperEngine.transcribe(FloatArray)` returns a string from test PCM data; memory budget verified on target hardware; release-build benchmark established.

**Addresses:** Model distribution (FEATURES.md table stakes), first-run download UX.

**Avoids:** OOM byte-array load (Pitfall 1), debug-build performance illusion (Pitfall 2), 16KB page size rejection (Pitfall 11), R8 JNI obfuscation (Pitfall 12), APK size rejection (Pitfall 10).

**Research flag:** Standard patterns — whisper.cpp Android example and NDK CMake integration are well-documented. No additional research needed.

---

### Phase 2: Share Intent Reception and Audio Acquisition

**Rationale:** The share intent is the only entry point to the app. Audio acquisition (URI to private file) must be implemented correctly and atomically — copy bytes before launching any async work. This phase is a prerequisite for audio decoding.

**Delivers:** `ShareActivity` receives `ACTION_SEND` intent from WhatsApp and Telegram; URI bytes copied synchronously to `cacheDir`; progress dialog shown; app visible in Android share sheet for `audio/*` and `application/octet-stream`.

**Addresses:** Share intent integration (FEATURES.md table stakes #1), progress feedback (table stakes #5).

**Avoids:** URI permission expiry (Pitfall 3), wrong MIME type assumptions (Pitfall 7).

**Research flag:** Standard patterns — Android share intent documentation is comprehensive and authoritative.

---

### Phase 3: Audio Decoding Pipeline

**Rationale:** Decoding OGG/Opus to 16kHz mono float32 PCM is the most technically fiddly step. It must be tested against real WhatsApp and Telegram audio — not synthetic MP3/WAV test files — to catch MIME-type and container edge cases. This phase must complete before end-to-end transcription is possible.

**Delivers:** `AudioPreprocessor` decodes OGG/Opus via MediaExtractor + MediaCodec; mono downmix and linear resample to 16kHz; output verified by writing decoded WAV and playing it back.

**Addresses:** OGG/Opus audio format support (FEATURES.md table stakes #2).

**Avoids:** Wrong audio format assumptions (Pitfall 7), sample rate mismatch causing all-silence input to Whisper.

**Research flag:** Standard patterns — MediaExtractor + MediaCodec Opus path is documented and platform-native. Test against real device audio immediately.

---

### Phase 4: End-to-End Pipeline and Core UX

**Rationale:** Wire all components together through `TranscriptionViewModel`. This phase produces the first working MVP: share a voice message, get a transcript on the clipboard. All table stakes features are present. This is the earliest shippable state.

**Delivers:** Full pipeline from share intent to clipboard copy; `TranscriptionViewModel` orchestrating all stages; sealed `TranscriptionResult` error states surfaced as Toasts; basic inference params configured (`no_timestamps=true`, `no_speech_thold=0.6f`, `max_tokens=224`); temp file cleanup in `finally` blocks; app passes smoke test on real WhatsApp voice messages.

**Addresses:** All Phase 1 FEATURES.md table stakes: inference, clipboard output, completion notification, error handling, multilingual auto-detect.

**Avoids:** ANR from main thread (Pitfall 5), silence hallucination (Pitfall 4), repetition loops (Pitfall 9), temp file accumulation (Pitfall 14).

**Research flag:** Standard patterns for coroutine orchestration and clipboard API. Inference params (no_timestamps, no_speech_thold) are documented in whisper.cpp source and GitHub issues.

---

### Phase 5: Quality and Differentiation

**Rationale:** The MVP works but lacks the features that convert a "it works" experience into one users recommend. Language selection and real progress feedback are the two highest-leverage quality improvements, both low-complexity given the foundation.

**Delivers:** Manual language selection settings screen (persist preference); progress bar driven by Whisper segment callbacks; detected language surfaced to user post-transcription; `best_of=3` for language detection on short clips; lightweight VAD pre-screening (energy threshold or Silero VAD) to catch silent audio before inference.

**Addresses:** Manual language selection (FEATURES.md differentiator), processing speed indicator (differentiator), VAD pre-screening (Pitfall 4 hardening).

**Avoids:** Wrong language detection on short clips (Pitfall 8), silence hallucination at the input level (Pitfall 4 second line of defense).

**Research flag:** VAD integration may need targeted research — Silero VAD Android port is not as well-documented as the Python version. Energy-threshold VAD is a simpler alternative with no additional dependencies.

---

### Phase 6: Distribution Hardening and Play Store Submission

**Rationale:** The app is feature-complete but not production-hardened. Play Store submission requires 16KB page-size compliance, ProGuard configuration, and thorough testing across device tiers.

**Delivers:** Release build tested on low-end (2GB RAM), mid-range (4GB), and high-end (8GB) devices; ProGuard keep rules verified; 16KB page-size emulator test passes; Play Store listing copy communicating "no network, no account, no subscription" positioning; model download CDN finalized (self-hosted or HuggingFace with rate-limit handling).

**Avoids:** Play Store 16KB page-size rejection (Pitfall 11), R8 JNI breakage in production release (Pitfall 12), model CDN rate-limiting on HuggingFace for F-Droid distribution.

**Research flag:** CDN strategy for model hosting may need research if self-hosting is chosen — costs, reliability, and resume-on-failure implementation.

---

### Phase Ordering Rationale

- Phases 1-4 are strictly ordered by technical dependency: each requires the previous to compile and pass a basic test.
- Phase 5 is intentionally decoupled from Phase 4 — the MVP is shippable without language selection and segment-callback progress; these are quality improvements, not blockers.
- Phase 6 is last because distribution concerns (CDN, ProGuard, page size) can be addressed in parallel with Phase 5 testing but should not delay the MVP pipeline.
- Anti-features (transcript history, AI post-processing, batch transcription) are excluded from all phases by design.

### Research Flags

Phases needing deeper research during planning:
- **Phase 5 (VAD integration):** Silero VAD Android port documentation is sparse; energy-threshold VAD is the safe default fallback. Evaluate during phase planning.
- **Phase 6 (CDN/model hosting):** Self-hosted CDN costs and resume-on-failure implementation details not covered in current research.

Phases with standard, well-documented patterns (skip research-phase):
- **Phase 1:** whisper.cpp Android example is the reference implementation; NDK CMake is standard.
- **Phase 2:** Android share intent receiving is covered exhaustively in official Android docs.
- **Phase 3:** MediaExtractor + MediaCodec Opus path is fully documented in Android media format guides.
- **Phase 4:** Coroutine orchestration, ViewModel, clipboard API are all standard AndroidX patterns.

---

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | whisper.cpp Android example verified in repo; model sizes confirmed from HuggingFace; Opus decode API level confirmed in official Android docs; all major alternatives explicitly evaluated and rejected |
| Features | HIGH | Competitor feature matrix built from direct product research; table stakes validated against real user reviews (WAVO Play Store reviews, WhatsApp blog); anti-features grounded in concrete failure modes of existing tools |
| Architecture | HIGH | Core patterns verified against official Android docs (ContentResolver, MediaCodec, ViewModel, coroutines); JNI structure mirrors official whisper.cpp Android example exactly |
| Pitfalls | HIGH | Most pitfalls sourced from GitHub issues with confirmed root causes and fixed commits; OOM pitfall from ONNX Runtime issue tracker; FGS type from official Android 14/15 docs |

**Overall confidence:** HIGH

### Gaps to Address

- **Inference speed on target hardware:** No authoritative Android benchmark exists for whisper.cpp small model. Community reports suggest 15-30s on mid-range (Snapdragon 7xx) and 30-90s on low-end. Validate during Phase 1 on representative devices before committing to UX copy about speed expectations.

- **WhatsApp URI permission window duration:** Research confirms URIs expire when Activity finishes, but the exact window when Activity is backgrounded (not finished) is unspecified. The synchronous copy-in-`onCreate` pattern is safe regardless; this is a documentation gap, not an implementation risk.

- **Silero VAD Android feasibility:** The Python Silero VAD is well-tested; the Android JNI port is less documented. If energy-threshold VAD is insufficient for the quality bar, this needs a dedicated spike in Phase 5 planning.

- **Play Store review time for NDK apps:** Apps with native libraries sometimes receive additional manual review. Factor into Phase 6 timeline planning.

- **Low-end device (2GB RAM) behavior:** Memory budget analysis shows 380-450 MB peak usage, which is marginal on 2GB devices. The `ggml-tiny.bin` fallback (75 MB) is available but the trigger heuristic (detect available RAM at launch and offer tiny model) is not designed. Address in Phase 5 or Phase 6.

---

## Consensus Across Researchers

All four research domains agree on:

1. **whisper.cpp JNI is the only viable on-device Whisper runtime for Android** — no alternative passes the evaluation.
2. **Model must be downloaded on first use, not bundled** — APK size limits make bundling impossible.
3. **Copy URI bytes synchronously in `onCreate` before any async work** — URI permission lifetime makes this mandatory, not optional.
4. **All inference on `Dispatchers.Default`** — CPU-bound work; main thread ANR is a guaranteed failure mode otherwise.
5. **No foreground service for MVP** — Activity-scoped coroutines are sufficient; foreground service adds complexity with no MVP benefit.
6. **Silence hallucination must be mitigated in Phase 1** — `no_timestamps=true` and `no_speech_thold` are trivial to set and prevent a trust-destroying failure mode.
7. **Test with real WhatsApp/Telegram audio from day one** — synthetic test files do not expose the MIME-type and container edge cases that break the decoding pipeline.

---

## Sources

### Primary (HIGH confidence)
- [ggml-org/whisper.cpp official repository](https://github.com/ggml-org/whisper.cpp) — runtime, JNI example, model files, Android inference issues
- [Android Developers: Receive simple data from other apps](https://developer.android.com/training/sharing/receive) — share intent setup
- [Android Developers: MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) — PCM float decode pipeline
- [Android Developers: Supported media formats](https://developer.android.com/guide/topics/media/media-formats) — Opus API level table
- [Android Developers: Foreground service types (mediaProcessing)](https://developer.android.com/develop/background-work/services/fgs/service-types) — FGS type requirements
- [Android Developers: 16KB page size guide](https://developer.android.com/guide/practices/page-sizes) — NDK alignment requirement
- [Android Developers: ContentResolver security risks](https://developer.android.com/privacy-and-security/risks/content-resolver) — URI permission lifecycle
- [HuggingFace ggerganov/whisper.cpp model files](https://huggingface.co/ggerganov/whisper.cpp/tree/main) — model sizes verified 2026-02-27

### Secondary (MEDIUM confidence)
- [whisperIME Android app](https://github.com/woheller69/whisperIME) — download-on-first-use pattern reference implementation
- [Building LocalMind: whisper.cpp JNI on Android (Jan 2026)](https://medium.com/@mohammedrazachandwala/building-localmind-how-i-ported-openais-whisper-to-android-using-jni-and-kotlin-575dddd38fdc) — JNI memory management patterns
- [ONNX Runtime Issue #19514](https://github.com/microsoft/onnxruntime/issues/19514) — byte array OOM root cause confirmed
- [whisper.cpp Issue #1724](https://github.com/ggml-org/whisper.cpp/issues/1724) — no_timestamps as hallucination fix
- [whisper.cpp Issue #1022/#1070](https://github.com/ggml-org/whisper.cpp/issues/1022) — debug vs release performance gap
- [WhatsApp Blog: Voice Message Transcripts](https://blog.whatsapp.com/introducing-voice-message-transcripts) — on-device processing, language support
- [WAVO AppBrain reviews](https://www.appbrain.com/app/wavo-transcriber-for-whatsapp/speechtotext.transcriber.forwhatsapp.voice.messages.to.text) — competitor user pain points

### Tertiary (LOW confidence)
- Community reports on whisper.cpp inference speed on Android — no official benchmark; device variance is high; validate empirically in Phase 1

---

*Research completed: 2026-02-27*
*Ready for roadmap: yes*
