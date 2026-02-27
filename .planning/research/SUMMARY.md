# Project Research Summary

**Project:** TransChat v1.1 — Progress Callbacks, F-Droid Distribution, R8 Shrinking
**Domain:** Android on-device ML transcription — v1.1 polish and distribution milestone
**Researched:** 2026-02-27
**Scope:** Milestone-scoped. v1.0 foundations (whisper.cpp v1.7.4 via JNI, Kotlin, NDK 26.1, MediaCodec, ViewModel + StateFlow) are validated and unchanged. This summary covers only what changes or is added for v1.1.
**Confidence:** HIGH across all three feature areas

---

## Executive Summary

TransChat v1.1 adds three engineering deliverables to the validated v1.0 MVP: a real-time transcription progress bar driven by whisper.cpp native callbacks, F-Droid distribution readiness (metadata files and build recipe), and a surgical R8/ProGuard configuration that replaces the current over-broad keep rule with targeted rules. Research across all four domains confirms these features are achievable within the existing codebase with no new Gradle dependencies, no new library integrations, and no architectural changes. The v1.0 component chain (whisper_jni.cpp → WhisperLib → WhisperContext → WhisperEngine → TranscriptionViewModel → TranscriptionActivity) absorbs all three changes through incremental extension.

The progress callback implementation has two viable approaches. STACK.md and FEATURES.md both recommend wiring the JNI upcall directly (Approach A: `NewGlobalRef` + `CallVoidMethod` from the native thread). ARCHITECTURE.md recommends an `AtomicInteger` poll approach (Approach B: C++ atomic written by the callback, Kotlin coroutine polling every 100ms) as lower-risk for the specific case of a progress bar. Both are architecturally sound; the polling approach eliminates `AttachCurrentThread` / `DetachCurrentThread` complexity but requires a child coroutine to run alongside `whisper_full()`. The direct upcall approach is more real-time but has two critical pitfalls (global reference leak, wrong-thread UI access) documented in PITFALLS.md with prevention code. Choose based on team comfort with JNI threading.

F-Droid submission has no code changes — it is entirely metadata and build-recipe work. The app's stack is already F-Droid-clean (no Firebase, no Play Services, no prebuilt `.so` files, all dependencies are Apache 2.0 / open source). The two actions required before submission are: add a `LICENSE` file (hard gate, automatic rejection without it), and create the `fastlane/metadata/android/` directory tree. The fastlane file naming follows F-Droid's convention (`summary.txt` and `description.txt`), which diverges from standard fastlane naming (`short_description.txt`, `full_description.txt`) — this is the most likely silent failure mode. R8 configuration is a targeted rewrite of the existing `proguard-rules.pro` that removes the blanket `-keep class com.simonpena.transchat.** { *; }` and replaces it with surgical rules covering only the JNI surface, ViewModels, and UiState sealed hierarchy.

---

## Key Findings

### From STACK.md: Technology Decisions for v1.1

**No new Gradle dependencies.** All three v1.1 features are implementable with the existing dependency set:

| Feature | Stack Impact |
|---------|-------------|
| Progress callbacks | Pure C++ + Kotlin change in existing JNI bridge; android.widget.ProgressBar is a framework widget |
| F-Droid metadata | Text files + YAML in repo; no build dependencies |
| R8 configuration | `proguard-rules.pro` edits only; no new tools |

**Confirmed whisper.cpp callback API (HIGH confidence — read directly from repo source):**

Both `progress_callback` (fires per audio chunk, 0-100 integer, 1-4 firings for a 30-60s voice message) and `new_segment_callback` (fires per decoded text segment, 3-10 firings for a 30s message) are present in `whisper.h` v1.7.4 and currently unwired in `whisper_jni.cpp`. For a progress bar, `progress_callback` provides the integer 0-100 directly; `new_segment_callback` is semantically richer but requires segment-count-to-percentage estimation.

**Version codes:** `app/build.gradle.kts` must increment `versionCode = 2` and `versionName = "1.1"` for F-Droid and Play Store. The fastlane changelog file must be named `2.txt` to match.

**F-Droid build recipe NDK field:** Specify `ndk: r26c` in the fdroiddata YAML (shorthand) or `26.1.10909125` (numeric exact form). The numeric form is more precise and avoids aliasing bugs.

### From FEATURES.md: What to Build and What to Defer

**Progress bar (table stakes):**
- Determinate `LinearProgressIndicator` driven by real `progress_callback` values — not timer-based simulation
- `UiState.Processing` promoted from `object` to `data class(val progress: Int = 0)` — sealed class breaking change, Activity's `when` block must be updated
- `ProgressCallback` functional interface in `WhisperLib` — must survive R8 with an explicit keep rule

**Progress bar (defer to v1.2):**
- Partial segment text appearing during inference — requires `new_segment_callback` + second JNI upcall path; high complexity, not needed for the progress win
- `abort_callback` for real cancellation — coroutine cancellation does not interrupt `whisper_full()`; cancel button without this is misleading; defer or omit entirely

**F-Droid (table stakes):**
- `LICENSE` file in repo root — hard gate; no license = automatic rejection
- `fastlane/metadata/android/en-US/` directory with `summary.txt`, `description.txt`, `changelogs/2.txt`
- `submodules: true` in fdroiddata YAML — whisper.cpp is a git submodule; omitting this causes F-Droid build failure

**F-Droid (defer):**
- Reproducible builds (bit-for-bit APK match) — NDK reproducibility across macOS vs Linux is not achievable without significant infrastructure; acceptable to skip for initial inclusion
- IzzyOnDroid pre-listing — good stepping stone but optional; prioritize official F-Droid

**R8 (table stakes):**
- Remove `-keep class com.simonpena.transchat.** { *; }` — defeats all R8 shrinking
- Add targeted rules for: JNI downcalls (`WhisperLib$Companion` native methods), JNI upcall (`ProgressCallback.onProgress`), ViewModels, UiState sealed hierarchy, stack trace attributes
- **Critical new rule for v1.1:** `-keepclassmembers interface com.simonpena.transchat.WhisperLib$ProgressCallback { public abstract void onProgress(int); }` — R8 cannot see that this method is called from native code via `CallVoidMethod` and will strip it without an explicit keep rule

**Recommended build order from FEATURES.md:**
1. R8 rules rewrite (lowest risk, proves release build is clean)
2. `progress_callback` C++ + Kotlin wiring ([1]-[5] in the dependency chain)
3. `UiState.Processing` change + `LinearProgressIndicator` UI ([6]-[7])
4. `LICENSE` file
5. `fastlane/metadata/` directory and content
6. fdroiddata YAML or `.fdroid.yml` (submit after v1.1 tagged)
7. Branch rename (`master` to `main`) — do last

### From ARCHITECTURE.md: Component Changes and Data Flow

**8 modified files, 6 new files. No new classes required.**

Modified components for the progress callback:

| File | What Changes |
|------|-------------|
| `app/src/main/cpp/whisper_jni.cpp` | Add `ProgressState` struct, register `progress_callback` via `user_data`, add `getProgress` JNI export |
| `app/src/main/java/.../WhisperLib.kt` | Add `external fun getProgress(contextPtr: Long): Int` |
| `WhisperContext` (in WhisperLib.kt) | `transcribeData()` gains `onProgress: (Int) -> Unit = {}` param; launches child poller coroutine |
| `app/src/main/java/.../WhisperEngine.kt` | `transcribe()` gains `onProgress` param, forwards to WhisperContext |
| `app/src/main/java/.../TranscriptionViewModel.kt` | `UiState.Processing` becomes `data class(val progress: Int = 0)`; passes progress lambda to engine |
| `app/src/main/java/.../TranscriptionActivity.kt` | Reads `state.progress` in `UiState.Processing` branch; sets on progress bar |
| `app/src/main/res/layout/activity_transcription.xml` | Replaces `CircularProgressIndicator` (indeterminate) with `LinearProgressIndicator` |
| `app/build.gradle.kts` | `versionCode = 2`, `versionName = "1.1"` |

New files for F-Droid:

| File | Content |
|------|---------|
| `fastlane/metadata/android/en-US/title.txt` | `TransChat` |
| `fastlane/metadata/android/en-US/summary.txt` | 80-char tagline (use F-Droid naming, not fastlane naming) |
| `fastlane/metadata/android/en-US/description.txt` | Full privacy-focused store description |
| `fastlane/metadata/android/en-US/changelogs/1.txt` | Initial release notes |
| `fastlane/metadata/android/en-US/changelogs/2.txt` | v1.1 release notes |
| `fastlane/metadata/android/en-US/images/icon.png` | 512x512 app icon |

**Progress data flow (AtomicInteger polling architecture — ARCHITECTURE.md recommendation):**

```
whisper_full() [C++]
  -> progress_callback fires every ~10% of audio processed
     -> ProgressState.progress.store(p) [atomic write, no JNI upcall]

WhisperContext.transcribeData() [Kotlin, single-thread executor]
  -> child coroutine polls every 100ms: WhisperLib.getProgress(ptr)
     -> onProgress(p) [lambda propagated up through WhisperEngine to ViewModel]
        -> _uiState.value = UiState.Processing(progress)

TranscriptionActivity [Main thread, StateFlow collector]
  -> progressBar.progress = state.progress
```

**R8 rules: no change required for the `getProgress` method** — it is an `external` fun in `WhisperLib$Companion`, already protected by the existing companion keep rule. The only new rule required is for the `ProgressCallback.onProgress` upcall method.

**Untouched components:** `AudioDecoder`, `PcmResampler`, `HallucinationFilter`, `ShareReceiverActivity`, `ModelManager`, `CMakeLists.txt`, `AndroidManifest.xml`.

### From PITFALLS.md: Critical Risks for v1.1

**v1.1 critical pitfalls (must address during implementation):**

**Pitfall 15 — JNI callback fires on native thread; wrong-thread UI access crashes app.** The single-thread executor thread is already JVM-attached; `GetEnv` retrieves the existing `JNIEnv*` without `AttachCurrentThread`. The Kotlin callback method must only write to `MutableStateFlow` (thread-safe); any `Handler`, `View`, or `Looper` access from the callback crashes with `CalledFromWrongThreadException` or `IllegalStateException`. With the polling architecture, this pitfall is eliminated entirely — the C++ callback only writes an atomic integer.

**Pitfall 16 — JNI global reference leak crashes app after many transcriptions.** Every `NewGlobalRef` must be paired with `DeleteGlobalRef`. Implement cleanup in `ViewModel.onCleared()` or the equivalent teardown path. Enable CheckJNI during development to catch leaks early. With the polling architecture (AtomicInteger, no `jobject` passed to C++), this pitfall is also eliminated.

**Pitfall 19 — R8 strips JNI upcall methods called only from native code.** R8 performs static dead-code analysis; methods with no Kotlin/Java callers are removed. `ProgressCallback.onProgress(int)` has no Kotlin caller — it is invoked only via `CallVoidMethod`. Without an explicit `-keepclassmembers` rule, it is stripped in release builds and `NoSuchMethodError` occurs at the first progress update. This is a release-only failure — debug builds skip R8.

**Pitfall 17 — F-Droid rejects prebuilt `.so` files.** The current build compiles whisper.cpp from source via `CMakeLists.txt` — this is correct. Verify no `.so` files are committed to `app/src/main/jniLibs/` before submission. F-Droid's build server must produce a complete APK from `./gradlew assembleRelease` with no prebuilt binaries.

**v1.1 moderate pitfalls:**

**Pitfall 20 — High-frequency callbacks degrade inference speed.** `new_segment_callback` fires 10-20 times for a 60s clip — acceptable overhead. `encoder_begin_callback` fires ~30 times per 30s clip — still acceptable. Any finer-grained polling (per-tensor callback, tight polling loop from Kotlin) adds measurable latency on mid-range devices. Benchmark before and after adding the callback.

**Pitfall 21 — Wrong fastlane file names cause silent F-Droid linter failure.** F-Droid uses `summary.txt` (not `short_description.txt`) and `description.txt` (not `full_description.txt`). Using standard fastlane names results in an empty listing with no error from the linter.

**Pitfall 22 — R8 full mode strips generic type signatures for StateFlow-wrapped state classes.** Add `-keepattributes Signature` and `-keepattributes *Annotation*` to `proguard-rules.pro`. The `proguard-android-optimize.txt` base already includes `Signature` for common cases, but explicit inclusion is safer after narrowing the broad keep rule.

**Pitfall 23 — Branch rename breaks CI and F-Droid source checkout.** Before renaming `master` to `main`: search all of `.github/`, `.fdroid.yml`, `fastlane/`, and `README*` for the string "master"; update references in the same commit; set GitHub default branch immediately after push.

---

## Implications for Roadmap

The three v1.1 features are largely independent of each other. R8 configuration and F-Droid metadata have zero code dependencies on the callback feature. The progress callback is a sequential chain through 7 files. This independence supports parallel development tracks.

### Suggested Phase Structure

**Phase 1: R8 Configuration Rewrite**

Rationale: lowest risk, highest confidence, no new code. Proves the release build is clean before JNI complexity is added. The surgical keep rules must be in place before the `ProgressCallback` interface is finalized (the interface name must match the keep rule).

Delivers:
- `proguard-rules.pro` rewritten with targeted rules replacing the blanket keep
- Release build compiles and runs correctly with minification active
- Placeholder `ProgressCallback` keep rule ready (commented, to be uncommented when interface is named)
- `-keepattributes Signature` and stack trace attributes added

Pitfalls avoided: Pitfall 19 (R8 strips upcall), Pitfall 22 (R8 full mode strips signatures).

Research flag: Standard patterns. No additional research needed.

---

**Phase 2: Progress Callback — C++ and Kotlin Wiring**

Rationale: Highest user-visible value. Sequential chain through 5 files (whisper_jni.cpp through WhisperEngine). Each step is independently testable. Complete this chain before touching the UI — validate callback firing via logcat before wiring to StateFlow.

Delivers:
- `ProgressState` struct in `whisper_jni.cpp` with atomic int
- `progress_callback` registered on `whisper_full_params`
- `getProgress` JNI export function
- `WhisperLib.kt`: `external fun getProgress(contextPtr: Long): Int`
- `WhisperContext.transcribeData()`: `onProgress` param + child poller coroutine
- `WhisperEngine.transcribe()`: `onProgress` param forwarded

Implementation note: Use the AtomicInteger polling approach (ARCHITECTURE.md recommendation) rather than direct JNI upcall. Eliminates Pitfalls 15 and 16. The poller coroutine runs as a `launch { }` child inside `withContext(scope.coroutineContext)` and is cancelled automatically when `fullTranscribe` returns.

Pitfalls avoided: Pitfall 15 (wrong-thread UI access — eliminated by polling architecture), Pitfall 16 (global ref leak — eliminated by polling architecture), Pitfall 20 (callback overhead — polling every 100ms is negligible).

Research flag: AtomicInteger + polling is a standard pattern. JNI `GetEnv` threading rules are documented in Android NDK JNI Tips.

---

**Phase 3: Progress UI — UiState Change and LinearProgressIndicator**

Rationale: Completes the user-visible progress feature. Depends on Phase 2. The `UiState.Processing` change from `object` to `data class` is a breaking sealed-class change — all `when` branches in `TranscriptionActivity` must be updated in the same commit.

Delivers:
- `UiState.Processing` changed to `data class(val progress: Int = 0)`
- `TranscriptionViewModel` passes progress lambda to `engine.transcribe()`
- `activity_transcription.xml`: `CircularProgressIndicator` replaced with `LinearProgressIndicator`
- `TranscriptionActivity`: reads `state.progress`, sets on progress bar + optional percentage text label
- Back press handler during inference (show dialog or disable back — omit cancel button unless `abort_callback` is implemented)

Pitfalls avoided: Fake/timer-based progress anti-pattern avoided by using real callback values. Cancel button omitted to avoid the "cancels coroutine but not native call" misleading UX.

Research flag: `LinearProgressIndicator` is Material Design 3 standard. No research needed.

---

**Phase 4: F-Droid Metadata and Submission Prep**

Rationale: Entirely metadata work, independent of all other phases. Can be done in parallel with Phases 2-3 or after. Gate: `LICENSE` file must exist before any submission.

Delivers:
- `LICENSE` file in repository root
- `fastlane/metadata/android/en-US/` directory tree with F-Droid-named files (`summary.txt`, `description.txt`)
- `changelogs/1.txt` (initial release) and `changelogs/2.txt` (v1.1)
- 512x512 app icon at `fastlane/metadata/android/en-US/images/icon.png`
- `build.gradle.kts`: `versionCode = 2`, `versionName = "1.1"`
- Draft fdroiddata YAML with `submodules: true`, exact NDK version `26.1.10909125`, `subdir: app`
- Pre-submission verification: clean checkout + `./gradlew assembleRelease` produces complete APK with no prebuilt `.so`

Pitfalls avoided: Pitfall 17 (prebuilt `.so` rejection), Pitfall 21 (wrong fastlane file names — use `summary.txt` not `short_description.txt`).

Research flag: F-Droid official docs are authoritative. Reproducible build not targeted for v1.1 — standard F-Droid signing (F-Droid signs with their key) is acceptable for initial submission.

---

**Phase 5: Branch Rename and Final Verification**

Rationale: Low-risk, discrete, and must be done after all PRs are merged. Audit before rename.

Delivers:
- Audit: `grep -r "master" .github/ .fdroid.yml fastlane/ README*`
- Branch renamed: `git branch -m master main && git push origin -u main && git push origin --delete master`
- GitHub Settings: default branch changed to `main`
- All CI workflows verified running on `main`

Pitfalls avoided: Pitfall 23 (CI stops running, F-Droid source checkout breaks).

Research flag: Standard git/GitHub operational guidance.

---

### Phase Ordering Rationale

- Phase 1 (R8) first because: (a) proves release build is clean before adding complexity; (b) keep rules must exist before `ProgressCallback` interface is named in Phase 2.
- Phases 2 and 3 are sequential: UI can only read `state.progress` after the ViewModel emits it, which requires the callback chain to be wired.
- Phase 4 (F-Droid) is fully independent — can run in parallel with Phases 2-3. The `versionCode` bump in Phase 4 is the only shared file with the callback chain.
- Phase 5 (branch rename) must be last — after all feature branches are merged.
- Deferred to v1.2: `new_segment_callback` for partial segment text preview; reproducible builds; `abort_callback` for real cancellation.

### Research Flags

Needs deeper research before or during planning:
- **None identified.** All three v1.1 features have well-documented implementation patterns verified against authoritative sources (whisper.h in repo, F-Droid official docs, Android Developer R8 blog Nov 2025, Android NDK JNI Tips).

Standard patterns (skip research-phase):
- **Phase 1 (R8):** Android Developers Blog Nov 2025 is the current reference. Rules are concrete and testable.
- **Phase 2 (callback C++):** AtomicInteger + polling is standard. whisper.cpp callback API confirmed from repo source.
- **Phase 3 (progress UI):** `LinearProgressIndicator` is Material Design 3 standard. `UiState` sealed class change is straightforward Kotlin.
- **Phase 4 (F-Droid):** F-Droid official docs are authoritative and current.
- **Phase 5 (branch rename):** Standard git operations.

---

## Confidence Assessment

| Area | Confidence | Basis |
|------|------------|-------|
| whisper.cpp callback API | HIGH | Verified directly from `whisper.h` and `whisper.cpp` source in this repository (lines confirmed in STACK.md) |
| JNI callback threading model | HIGH | Android NDK JNI Tips official docs; AtomicInteger approach eliminates the hardest threading pitfalls |
| F-Droid fastlane file structure | HIGH | F-Droid official documentation (multiple sources confirm directory layout and file naming) |
| F-Droid YAML/build recipe | MEDIUM | Field syntax confirmed from F-Droid Build Metadata Reference; `submodules: true` requirement confirmed; specific field values should be verified against current fdroiddata templates at submission time |
| R8 JNI keep rules | HIGH | Android Developers Blog Nov 2025 + Android official keep-rule docs + direct inspection of existing `proguard-rules.pro` |
| `progress_callback` fire frequency | MEDIUM | whisper.cpp source confirms formula (`(100*(seek-start))/(end-start)`); exact firing count depends on audio length and model segment boundaries; 1-4 firings for typical voice messages |
| F-Droid NDK reproducibility | MEDIUM | F-Droid reproducible builds docs confirm NDK sensitivity; accepted as non-blocking for v1.1 initial submission |

**Overall confidence:** HIGH for implementation approach; MEDIUM for F-Droid YAML specifics and callback frequency on atypical audio.

### Gaps to Address

- **Direct JNI upcall vs AtomicInteger polling:** Research files disagree on the preferred approach (STACK.md and FEATURES.md prefer upcall; ARCHITECTURE.md prefers polling). Both are correct. This is a team decision, not a research gap. Recommendation: polling (eliminates two critical pitfalls with negligible downside for a progress bar).

- **`progress_callback` granularity on very short audio:** A 12-second voice message maps to 1 WHISPER_CHUNK_SIZE (30s). `progress_callback` may fire only once at 100%. The AtomicInteger polling approach still shows "something happening" via the poller, but the progress bar may jump from 0 to 100 in a single step. Consider whether a hybrid (show indeterminate until first callback, then switch to determinate) is needed. Low-risk decision, can be made during Phase 3.

- **F-Droid review timeline:** Community reports indicate 1-8 weeks from MR open to inclusion, depending on reviewer availability. Not a research gap but a planning assumption to document.

- **`ProgressCallback` interface placement:** FEATURES.md shows it as an inner interface of `WhisperLib`; ARCHITECTURE.md uses the polling approach which requires no interface at all. Finalize before writing the R8 keep rule (Phase 1).

---

## Consensus Across Research Files

All four research files agree on:

1. **No new Gradle dependencies for v1.1** — all three features use existing infrastructure.
2. **The broad `-keep class com.simonpena.transchat.** { *; }` must be replaced** — it defeats all R8 shrinking and must be narrowed before shipping a release build with meaningful size reduction.
3. **F-Droid fastlane files must use F-Droid naming** (`summary.txt`, `description.txt`) **not standard fastlane naming** — this is the single most likely silent failure in F-Droid submission prep.
4. **`LICENSE` file is a hard gate for F-Droid** — no license = automatic rejection, not a review comment.
5. **Reproducible builds are not required for initial F-Droid inclusion** — NDK binary differences across platforms make bit-for-bit reproducibility very hard; standard F-Droid signing is acceptable for v1.1.
6. **The progress callback chain is a strict sequential dependency** — any break between whisper_jni.cpp and TranscriptionActivity causes a crash or regression; each step must be tested before proceeding to the next.
7. **Release build validation is mandatory** — R8 stripping of upcall methods (Pitfall 19) and JNI name mangling issues (Pitfall 12) only manifest in release builds. Test with a real release APK before merging the progress callback feature.

---

## Sources

### From STACK.md (HIGH confidence)
- `app/src/main/cpp/whisper.cpp/include/whisper.h` — callback typedefs, lines 449-456 (in repo)
- `app/src/main/cpp/whisper.cpp/src/whisper.cpp` — `progress_callback` invocation, lines 5526-5530; `new_segment_callback` invocation, lines 6166-6227 (in repo)
- [Android NDK JNI Tips — GetEnv / AttachCurrentThread](https://developer.android.com/training/articles/perf-jni)
- [F-Droid All About Descriptions, Graphics, and Screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)
- [F-Droid Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/)
- [Android Developers Blog: Configure and Troubleshoot R8 Keep Rules (Nov 2025)](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html)

### From FEATURES.md (HIGH confidence)
- `app/src/main/cpp/whisper.cpp/include/whisper.h` lines 449-564 — callback API (read directly)
- `app/src/main/cpp/whisper_jni.cpp` — current JNI bridge, no callbacks set (read directly)
- `app/src/main/java/com/simonpena/transchat/WhisperLib.kt` — current Kotlin JNI binding (read directly)
- `app/proguard-rules.pro` and `app/build.gradle.kts` — current R8 config (read directly)
- [F-Droid Inclusion How-To](https://f-droid.org/en/docs/Inclusion_How-To/)
- [Material Design 3 Progress Indicators](https://m3.material.io/components/progress-indicators)
- [R8 Keep rule use cases and examples](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples)

### From ARCHITECTURE.md (HIGH confidence)
- `whisper.h` v1.7.4 lines 449-570 — progress_callback and new_segment_callback fields (verified in repo)
- `TranscriptionViewModel.kt` — UiState sealed class, Processing object, StateFlow (direct read)
- `proguard-rules.pro` — existing R8 rules (direct read)
- [F-Droid: Submitting to F-Droid Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [F-Droid: Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/)

### From PITFALLS.md (HIGH confidence)
- [Android NDK JNI Tips — Threading](https://developer.android.com/training/articles/perf-jni)
- [Android NDK JNI Wiki](https://github.com/android/ndk/wiki/JNI)
- [JNI Global Reference Leak — Google Filament Issue #4957](https://github.com/google/filament/issues/4957)
- [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) — prebuilt .so rejection
- [F-Droid: All About Descriptions](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/) — fastlane file naming
- [Android Developers: Keep Rule Use Cases](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples) — upcall protection
- [Android Developers Blog: Configure and troubleshoot R8 Keep Rules (2025)](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html)
- [SDL JNI @Keep issue #3563](https://github.com/libsdl-org/SDL/issues/3563) — real-world upcall stripping example
- [GitHub renaming guidance](https://github.com/github/renaming)

---

*Research completed: 2026-02-27*
*Milestone scope: v1.1 — progress callbacks, F-Droid distribution, R8 shrinking*
*Ready for roadmap: yes*
