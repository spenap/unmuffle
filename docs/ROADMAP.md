# Roadmap

Each item has a "done when" criterion. Tick items off as they're completed
and record decisions in `decisions.md`.

## Done (v1.0–v1.3, February–March 2026)
Share target; MediaCodec decode (OGG/Opus, MP3) and resampling to 16 kHz
mono; whisper.cpp small over JNI; model download with Wi-Fi prompt and
SHA256 check; hallucination filter; result screen with Copy and Share;
0–100% progress; R8 shrinking; MIT licence, README, CI with signed `v*`
releases; renamed TransChat → Unmuffle; Material 3 with dynamic colour;
language code passed through to whisper.cpp.

## Track R: Releases
- [x] `versionCode`/`versionName` come from git (commit count,
      `git describe`). Done when a release's APK reports its tag.
      (v1.3.0: versionCode 125.)
- [x] GitHub Releases. Done when `scripts/release.sh` publishes the signed
      APK as a release asset. (v1.3.0, 2026-11-03.)
- [x] Obtainium on the S25 follows the releases. Done when a new tag shows
      up as an update and installs over the previous one. (v1.5.0 over
      v1.3.0, 2026-11-03.)
- [x] Debug-only smoke test. Done when `SmokeTestActivity` and `jfk.wav`
      are in `src/debug/` and absent from the release APK
      (`scripts/check-release-apk.sh`, run by CI).

## Track T: Tests
- [x] JVM unit tests for the pure parts (`HallucinationFilter`,
      `PcmResampler`). Done when they run in
      `./gradlew testDebugUnitTest`.
- [x] CI runs `testDebugUnitTest`. Done when a failing test fails the
      build.
- [x] One instrumented test of share → transcript on `jfk.wav` with the
      tiny model. (`scripts/device-test.sh`; 9 s on the Pixel 5.)

## Track P: Transcription
Written test-first; measured on the S25.
- [x] Streamed segments. Done when the transcript appears segment by
      segment while inference runs.
- [x] base vs small. Done when time and accuracy on the same messages are
      recorded in `docs/` and the default model chosen from them
      (`docs/measurements.md`: small stays).
- [x] ggml built for fp16 and dot products, loaded only where the CPU has
      both (`asimddp`). Done when the S25 is about 24% faster with the
      same text.
- [x] Thread count. Done when the S25 uses 6 and a rule for other phones
      is measured on the Pixel 5. (All cores but two.)
- [x] small q8_0 replaces q5_1. Done when installs download it and remove
      q5_1.
- [ ] Time to first word. Done when share → first word on the S25 is
      recorded by phase (model load, audio decode, language detection,
      encoder, decoder) in `docs/measurements.md`, and a shorter first
      chunk and detection on the first seconds are measured and kept if
      faster with the same text.
- [ ] GPU spike: ggml's OpenCL backend (Adreno 7xx/8xx) on the S25.

## Track S: Settings
- [x] Settings screen: language (auto-detect by default, or one, from a
      searchable list with the phone's languages first), model, licences,
      version.
- [x] The transcript shows its language (detected or chosen) and can be
      transcribed again in another.

## Track C: Cleanup
Next after time to first word. Done when behaviour is unchanged (unit and
device tests pass) and there is less code.
- [ ] Comments state what holds: no references to the old planning phases,
      RESEARCH.md or MVP notes; no long rationale.
- [ ] One clear job per whisper layer (`WhisperLib`, `WhisperContext`,
      `WhisperEngine`); the native progress state is no longer a global.
- [ ] `TranscriptionActivity.onCreate` split per state; the
      view model's pipeline steps testable on the JVM.
- [ ] Remove what no longer earns its place: `SmokeTestActivity` (the
      instrumented tests cover it), dead code such as the verification WAV
      cleanup.

## Ideas and not doing
- Ideas: words appear as they are decoded (branch `feat/word-streaming`,
  #28: the first word only ~4 s sooner on the Pixel 5); CI runs the
  instrumented tests on an emulator (needs an x86_64
  build alongside arm64); F-Droid (Fastlane metadata, fdroiddata recipe); a GitHub Pages
  site; tapping an unsure word plays the audio from it (whisper's token
  timestamps); a transcript cache in memory for the life of the process,
  keyed by the audio's SHA-256 and never written to disk, so sharing the
  same message again is instant; a whisper small fine-tuned on Spanish
  (or Galician-accented Spanish); silencing comfort noise in pauses
  (whisper's words change with it; better or worse needs more
  messages); showing when the audio runs on past the transcript
  (whisper's last segment ends early).
- Not doing: Android's on-device recognizer (closed source; see
  decisions.md); whisper.cpp VAD (no faster on real messages, dropped
  speech; see decisions.md); Galician; transcript history (transcripts are ephemeral);
  cloud transcription; live microphone transcription; speaker
  diarization; medium/large models (too big and slow on a phone); iOS;
  Compose (Views are enough for four screens).
