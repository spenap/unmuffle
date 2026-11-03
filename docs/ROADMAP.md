# Roadmap

Each item has a "done when" criterion. Tick items off as they're completed
and record decisions in `decisions.md`.

## Done (v1.0–v1.3, February–March 2026)
Share target; MediaCodec decode (OGG/Opus, MP3) and resampling to 16 kHz
mono; whisper.cpp small q5_1 over JNI; model download with Wi-Fi prompt and
SHA256 check; hallucination filter; result screen with Copy and Share;
0–100% progress; R8 shrinking; MIT licence, README, CI with signed `v*`
releases; renamed TransChat → Unmuffle; Material 3 with dynamic colour;
language code passed through to whisper.cpp.

## Track R: Releases
- [ ] `versionCode`/`versionName` come from the tag. Done when a `v*` tag
      builds an APK whose version matches it.
- [ ] GitHub Releases. Done when a tag publishes the signed APK as a
      release asset.
- [ ] Obtainium on the S25 follows the releases. Done when a new tag shows
      up as an update and installs over the previous one.
- [ ] Debug-only smoke test. Done when `SmokeTestActivity` and `jfk.wav`
      are in `src/debug/` and absent from the release APK.

## Track T: Tests
- [ ] JVM unit tests for the pure parts (`HallucinationFilter`,
      `PcmResampler`, `WavWriter`). Done when they run in
      `./gradlew testDebugUnitTest`.
- [ ] CI runs `testDebugUnitTest`. Done when a failing test fails the
      build.
- [ ] One instrumented test of share → transcript on `jfk.wav` with the
      tiny model.

## Track P: Transcription
Written test-first; measured on the S25.
- [ ] Streamed segments. Done when the transcript appears segment by
      segment while inference runs.
- [ ] whisper.cpp VAD. Done when silence is skipped and a voice message
      with long pauses transcribes faster, with the same text.
- [ ] base vs small. Done when time and accuracy on the same messages are
      recorded in `docs/` and the default model chosen from them.

## Track S: Settings
- [ ] Language dialog on share, with "Remember my choice" (persisted and
      respected on later shares).
- [ ] Settings screen: default language (always ask, or one language) and
      model; changing model downloads it if missing.

## Ideas and not doing
- Ideas: F-Droid (Fastlane metadata, fdroiddata recipe); a GitHub Pages
  site; Android's on-device recognizer as an optional mode.
- Not doing: Galician; transcript history (transcripts are ephemeral);
  cloud transcription; live microphone transcription; speaker
  diarization; medium/large models (too big and slow on a phone); iOS;
  Compose (Views are enough for four screens).
