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
- [x] Time to first word. Done when share → first word on the S25 is
      recorded by phase (model load, audio decode, language detection,
      encoder, decoder) in `docs/measurements.md`, and a shorter first
      chunk and detection on the first seconds are measured and kept if
      faster with the same text.
- [x] GPU spike: ggml's OpenCL and Vulkan backends. Done when time and
      text vs the CPU are measured on the S25. (CPU stays: OpenCL 4×
      slower on the S25; neither runs on the Pixel 5. decisions.md.)
- [ ] Locking the phone pauses a transcription (S25: no progress for
      20 s with the screen locked). Keep transcribing in the background.

## Track S: Settings
- [x] Settings screen: language (auto-detect by default, or one, from a
      searchable list with the phone's languages first), model, licences,
      version.
- [x] The transcript shows its language (detected or chosen) and can be
      transcribed again in another.

## Track L: Languages of the app
- [x] Every user-facing text in `strings.xml`. Done when lint fails on
      hard-coded text and on a missing translation.
- [x] Spanish and Galician. Done when `values-es` and `values-gl`
      translate every string and language names' second line is in the
      app's language.
- [x] Per-app language. Done when the app is listed in Settings › App
      languages (Android 13+) with English, Spanish and Galician.

## Track C: Trim
A smaller, simpler app its owner can read. Done for each item when
behaviour is unchanged apart from the trims named (unit and device tests
pass, bench text and times as before) and there is less code.
- [x] Remove what no longer earns its place (`SmokeTestActivity`,
      `WavWriter`, dead code); comments state what holds.
- [x] Privacy: no transcript text in logs; no backup; cached audio swept
      at start; clipboard marked sensitive; no cellular dialog, so only
      the INTERNET permission remains.
- [x] Trims: the stats line leaves the screen (kept in logcat); one Home
      screen instead of the launcher, download and ready screens.
- [x] One transcript path: the streamed segments, filtered once.
- [x] One whisper class over the JNI declarations; native state per
      context, not global.
- [x] The transcription pipeline tested on the JVM with a fake
      recognizer; `TranscriptionActivity` one function per state.
- [x] Docs: README for what rarely changes; `docs/architecture.md`; fresh
      screenshots; AGENTS.md: a change updates the docs and screenshots
      that reference it.

## Track A: Audit
Proof for privacy-minded users, run privately until the repo is public.
- [ ] Project page in `docs/site/`: the flow with screenshots, what the
      app never does, each permission and dependency.
- [ ] Exodus and MobSF scans of the release APK, locally and in CI on
      release tags (reports as private artifacts). Exodus:
      `scripts/exodus-scan.sh`, no trackers (`docs/audit.md`).
- [x] Reproducible builds: `scripts/repro-check.sh <tag>` rebuilds a tag
      from a fresh clone and compares it with the release, signature
      aside. Releases from v1.8.0 on (v1.8.0 checked). CI's release APK (an artifact)
      matches a build on this laptop.
- [ ] F-Droid metadata and a local `fdroid build`; submit when public.
- [x] The model from a file: "Use a model file" on Home (the system file
      picker, no permission; the same SHA-256 check). If the download is
      still at 0 bytes after a few seconds, Home offers it ("The download
      hasn't started. Pick a model file instead?") while the download
      keeps waiting. With LineageOS's per-app
      network access off, DownloadManager keeps the download pending
      (status 190) forever, with no prompt or failure (Pixel 5,
      LineageOS 24, 2026-11-04). Mockup first.
- [ ] The model from a model APK (the model as an uncompressed asset, its
      own release; the app copies it out and checks it). Only if F-Droid
      accepts a ~250 MB APK.
- [ ] Then decide on a build without the INTERNET permission.
- [x] No implicit local-network access: Android 17 grants
      `ACCESS_LOCAL_NETWORK` ("Nearby devices" in app info) to apps with
      INTERNET that target an older SDK. The app targets SDK 37 (Pixel 5,
      Android 17: no longer granted).

## Ideas and not doing
- Ideas: the encoder alone on the GPU (OpenCL on the S25: 0.43 s per
  pass vs 1.17 s on the CPU; only with the 12 s kernel compile cached);
  words appear as they are decoded (branch `feat/word-streaming`,
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
  speech; see decisions.md); Galician transcription; transcript history (transcripts are ephemeral);
  cloud transcription; live microphone transcription; speaker
  diarization; medium/large models (too big and slow on a phone); iOS;
  Compose (Views are enough for four screens).
