# Unmuffle

Unmuffle is an Android share target that transcribes voice messages
(WhatsApp, Telegram, any shared audio) on the phone with whisper.cpp.
Audio never leaves the device.

- Plan and progress: `docs/ROADMAP.md`. Update it when an item is done.
- Decisions: `docs/decisions.md`, newest first. Add an entry for each
  lasting decision (why, and where the detail lives); a reversal is a new
  entry that supersedes the old one, never a rewrite.
- `.planning/` (the earlier GSD milestones) is gone; git history keeps it.

## Layout (one repo, single `app` module)
- `app/src/main/java/com/simonpena/unmuffle/`: Kotlin, XML Views,
  ViewModel + StateFlow.
- `app/src/main/cpp/`: JNI bridge (`whisper_jni.cpp`) and
  `CMakeLists.txt`; `whisper.cpp/` is a git submodule (clone with
  `--recurse-submodules`).
- Build: `./gradlew assembleDebug`. SDK in `~/Android/Sdk`, JDK 17,
  NDK 27.2.12479018, CMake 3.22.1.

## Target device
- **Samsung Galaxy S25** (arm64, current Android/One UI): the device it
  is built for and measured on. APK is arm64-v8a only; `minSdk` 26.

## Principles
- Private: audio and transcripts stay on the phone; transcripts are never
  written to disk. Network is used only to download a model, from a fixed
  URL, checked by SHA256. No analytics, accounts or Google Play Services.
- Write docs, comments and commit messages concisely: state what holds,
  not the reasoning that got there or every case it excludes.
- New ideas go to ROADMAP.md, "Ideas and not doing", until there is
  evidence or a clear need.
- Smallest maintainable stack; no premature abstraction.
- whisper.cpp is not thread-safe: all native calls go through one
  single-thread executor.
- Copy a shared URI's bytes synchronously in `onCreate`; the read
  permission ends when the activity is backgrounded.
- New features are test-driven: write the failing test first, at the
  cheapest level that can catch the behaviour (JVM unit test, then
  instrumented), then the code. A device check confirms; it doesn't
  replace a test.
- No regressions get in. Before every commit:
  1. `./gradlew testDebugUnitTest lintDebug` passes.
  2. A change to transcription (decoding, model, whisper parameters,
     filtering): transcribe the same voice messages on the S25 and compare
     text and time with the version before.

## Working on this repo
For any agent (Claude Code, Codex…) and any person.
- One branch and pull request per change. Merge only when CI passes, or
  CI didn't run because only Markdown or `docs/` changed. Push and merge
  when the user asks.
- Commits: atomic, Conventional Commits, ending with a `Co-Authored-By:`
  line that names the agent and model.
- Fail fast. Before an expensive run (device tests, benchmarks, a CI
  round trip), check its assumptions in seconds: the right build is
  installed, the device is awake and unlocked, each command succeeded,
  the first log line appears. Reproduce CI failures locally rather than
  iterating through pushes. Give every wait a timeout.
- Releases: `scripts/release.sh vX.Y.Z` tags a green `main`, signs with
  the key in `~/.config/unmuffle` (backed up in Bitwarden) and publishes a
  GitHub Release. Release builds without the key use the debug key.
- Never commit models, keystores, tokens or users' audio and transcripts.
