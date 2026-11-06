# Decisions

Lasting decisions, newest first: what was decided, why, and where the
detail lives.

- Add an entry when a decision constrains later work: a rule, a choice
  between real alternatives, something ruled out. Not for every UI tweak;
  commits cover those.
- Entries aren't rewritten, except to drop what shouldn't be public. A reversal is a new entry that says what it
  supersedes and what changed our minds; the old entry only gains a
  "Superseded by" line.
- Ideas not yet decided go to ROADMAP.md, "Ideas and not doing".

## 2026-11-06 · Transcription stays on the CPU
A spike built ggml's OpenCL (+0.7 MB) and Vulkan (+6–11 MB) backends.
On the S25, OpenCL transcribed a 70 s message in 26.6 s after a 12 s
load (kernel compiles), against 6.4 s on the CPU: the encoder is faster
(0.43 s vs 1.17 s per pass) but each decoded token takes 81 ms instead
of 7.5 ms. On the Pixel 5 neither backend runs: Vulkan needs 1.2, and
OpenCL hung the GPU and System UI. On the S25, Vulkan aborts: the
driver can't create ggml's matrix-vector pipelines, whichever shader
compiler, ggml switches or subgroup settings.
Detail: branch `spike/gpu`, `docs/measurements.md`.

## 2026-11-06 · The app in English, Spanish and Galician
Every user-facing text is a string resource, and lint enforces it:
hard-coded text in layouts or `setText` and a string missing from a
language fail `lintDebug`. English is the default; Spanish and Galician
are the maintainer's languages. Android picks the language, per app on
13+ (AGP's generated locale config). Language names show their own name
and their name in the app's language, lowercase mid-sentence where the
language writes it so. To add a language: a `values-xx/strings.xml` with
every string, `values-xx-sw600dp` for those that say "phone" (tablets
read "tablet"), and `bools.xml` if it lowercases language names; the
locale config follows.
The licences text stays in English. Detail: `docs/architecture.md`.

## 2026-11-06 · Redo from one Change, not chips
Supersedes the "one tap" in "A language setting and a redo, no asking".
Under Copy and Share, the transcript says its language with a Change
button, like translation and caption menus; Change opens a sheet with
the phone's other languages and "More languages". A redo is two taps;
the screen loses a row of chips and the odd "Other language".

## 2026-11-05 · Public with its history; issues, not pull requests
A review before going public found no secrets and nothing private worth
rewriting history for, so the history stays as it is. Contributions are
issues: bugs and ideas, prompts included; no pull requests, since the
code is written by an agent under the maintainer's direction. Detail:
CONTRIBUTING.md, SECURITY.md.

Entries before 2026-11-03 were recovered from `.planning/` (removed in
the commit that added this file) and commits.

## 2026-11-04 · The phone's language by default; a preview
Supersedes the default in "A language setting and a redo, no asking".
The language setting defaults to the phone's first language whisper
supports (auto-detect without one). With a language set, the first
~10 s, cut at a pause, are transcribed alone with a fitted window as a
preview; the whole message is then transcribed as before and replaces
it. First words in ~5 s instead of ~24 s on the Pixel 5, for ~3 s more
in total and the same text. Transcribing the parts separately was as
fast but changed words and once lost the end. Auto-detect has no
preview. Numbers in `docs/measurements.md`.

## 2026-11-03 · A language setting and a redo, no asking
Settings holds the transcription language: auto-detect (default) or one
language. Fixing it is about 20% faster and avoids misdetection
(Galician-accented Spanish read as Portuguese). There is no "ask each
time": the transcript shows its language, and a wrong guess is one tap
to transcribe again in another.
Superseded (the default) by "The phone's language by default; a
preview".

## 2026-11-03 · small q8_0 instead of q5_1
Supersedes the quantisation in "whisper.cpp small q5_1" (2026-02-27).
With ggml's CPU kernels using dot products, q8_0 is about twice as fast
as q5_1 on the S25 and a third faster on the Pixel 5, and fixed most of
the words that differ. The cost: 252 MB instead of 181 MB, downloaded
again by existing installs, which then delete q5_1. Numbers in
`docs/measurements.md`.

## 2026-11-03 · small stays the model; no VAD
base q5_1 is three times faster than small q5_1 but changed 12–21% of
the words of two real messages, mostly for the worse. whisper.cpp's VAD
made those messages slightly slower (they have no long pauses), and on
one with added silence it was faster but dropped a phrase; losing
speech is worse than waiting. Numbers in `docs/measurements.md`.

## 2026-11-03 · No closed-source recognizer, not even as an option
Android's on-device recognizer is Google's proprietary app (Speech
Services by Google on the S25, Android System Intelligence on Pixels);
AOSP ships none. Privacy that can't be audited isn't privacy, so
transcription stays on whisper.cpp. It may be benchmarked for reference.
A fork that wants another backend replaces `WhisperEngine`; keep the
rest of the app independent of it. Supersedes the 2026-11-03 decision
"Galician dropped; the system recognizer is at most an option" on the
recognizer.

## 2026-11-03 · Releases signed on the laptop, not in CI
`scripts/release.sh` signs with the key in `~/.config/unmuffle`, backed
up off the laptop; CI builds with the debug key. Why: the key stays out
of CI. The S25 has a debug build, so Obtainium's
first install needs an uninstall. Supersedes the 2026-02-28 decision's
release signing. Detail: `scripts/release.sh`.

## 2026-11-03 · Distribution through GitHub Releases and Obtainium
Why: installs and updates on the S25 without a store; F-Droid stays an
idea. Detail: ROADMAP.md, Track R.

## 2026-11-03 · Galician dropped; the system recognizer is at most an option
Why: whisper.cpp covers the languages in use. Android's on-device
recognizer (Android System Intelligence) depends on Google components.
Detail: ROADMAP.md, "Ideas and not doing".
Superseded on the recognizer by 2026-11-03, "No closed-source recognizer".

## 2026-11-03 · AGENTS.md and docs/ replace .planning/
Why: readable by any agent; the milestone files duplicated what commits
record. CLAUDE.md imports
AGENTS.md. Detail: AGENTS.md, ROADMAP.md.

## 2026-03-01 · Settings in SharedPreferences
Why: `ShareReceiverActivity` and `MainActivity` read them synchronously
in `onCreate`; DataStore is asynchronous. Detail: `SettingsRepository.kt`.

## 2026-03-01 · Material 3, XML Views, no Compose
`Theme.Material3.DayNight.NoActionBar` with dynamic colour and an explicit
`MaterialToolbar` per screen. Compose would add ~4 MB for four screens.
Detail: `res/values/themes.xml`.

## 2026-03-01 · The app is Unmuffle, `com.simonpena.unmuffle`
Formerly TransChat. JNI symbol names follow the package; rename them in
the same commit. Detail: `whisper_jni.cpp`.

## 2026-02-28 · Split CI; release signing only in CI
Lint and debug build on every push and PR; the signed release only on a
`v*` tag, so the signing secrets are exposed only then. Local release
builds fall back to the debug key. Detail: `.github/workflows/build.yml`.
Superseded by 2026-11-03, "Releases signed on the laptop".

## 2026-02-28 · whisper.cpp compiled directly, not through its CMake
Our `CMakeLists.txt` lists the sources and sets `WHISPER_VERSION`.
Detail: `app/src/main/cpp/CMakeLists.txt`.

## 2026-02-27 · Progress by polling an atomic
whisper.cpp's progress callback writes an atomic in C++; Kotlin polls it
every 200 ms. Avoids JNI upcalls from the inference thread.
Detail: `whisper_jni.cpp`, `WhisperLib.kt`.

## 2026-02-27 · Surgical R8 rules
Keep only the JNI entry points and sealed classes; 6.0 → 5.4 MB.
Detail: `app/proguard-rules.pro`.

## 2026-02-27 · Hallucinations filtered in Kotlin
whisper parameters such as `no_timestamps` dropped real speech with the
q5_1 model; a post-filter for markers and repetition doesn't.
Detail: `HallucinationFilter.kt`.

## 2026-02-27 · One single-thread executor for whisper.cpp
The native context isn't thread-safe. Detail: `WhisperLib.kt`.

## 2026-02-27 · Shared audio copied synchronously in onCreate
The URI read permission ends when the activity is backgrounded.
Detail: `AudioCopier.kt`.

## 2026-02-27 · Model loaded from a file path
Loading from a byte array ran out of memory. Detail: `WhisperLib.kt`.

## 2026-02-27 · Copy and Share buttons, no automatic clipboard
Other apps could read the clipboard on older Android.

## 2026-02-27 · whisper.cpp small q5_1, arm64-v8a only
Local whisper.cpp over JNI is the only private option; small q5_1
(~190 MB) transcribed Spanish accurately in 12–15 s. Model downloaded
once from Hugging Face and checked by SHA256. Detail: `WhisperModel.kt`.
Superseded by "small q8_0 instead of q5_1" (2026-11-03) for the
quantisation.
