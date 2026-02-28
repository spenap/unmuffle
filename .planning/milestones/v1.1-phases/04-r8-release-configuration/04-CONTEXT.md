# Phase 4: R8 Release Configuration - Context

**Gathered:** 2026-02-27
**Status:** Ready for planning

<domain>
## Phase Boundary

Release builds use meaningful R8 shrinking with targeted keep rules instead of the blanket `-keep class com.simonpena.transchat.** { *; }` that defeats all optimization. Verified on physical device with full app flow.

</domain>

<decisions>
## Implementation Decisions

### R8 optimization level
- **Code shrinking**: enabled (default with `minifyEnabled = true`)
- **Obfuscation**: disabled — add `-dontobfuscate` to proguard-rules.pro. Keeps class names readable in crash reports. Open-source F-Droid app has nothing to hide.
- **Resource shrinking**: enabled — add `shrinkResources = true` in build.gradle.kts release block. Free size reduction, low risk.

### Keep rules strategy
- Remove blanket `-keep class com.simonpena.transchat.** { *; }` — this is the whole point of the phase
- Keep WhisperLib native methods (JNI name mangling must be preserved) — existing rules for native methods are correct
- Keep sealed class hierarchies used in `when()` expressions: `DownloadViewModel.State`, `AudioCopier.CopyResult`, `TranscriptionViewModel.UiState` — R8 may strip subclasses it considers unreachable
- ViewModels instantiated via ViewModelProvider reflection — keep `DownloadViewModel` and `TranscriptionViewModel`
- Activities are referenced in AndroidManifest.xml — R8 keeps these automatically, no explicit rule needed
- Add commented placeholder keep rule for `ProgressCallback` interface (Phase 5 will uncomment)

### Verification scope
- **Full flow verification**: fresh install on physical arm64-v8a device
- Test sequence: install release APK → model download completes → share voice message → transcription succeeds → transcript displays → Copy/Share buttons work
- This covers both DownloadViewModel and TranscriptionViewModel sealed hierarchies
- Physical device only (no emulator) — same device used for v1.0 verification

### APK size tracking
- Measure APK size before removing blanket keep (current release build)
- Measure APK size after surgical keep rules applied
- Document before/after comparison in SUMMARY.md

### Claude's Discretion
- Exact keep rule syntax and granularity (class-level vs member-level)
- Whether to keep entire sealed hierarchies or just the subclasses
- Order and commenting style in proguard-rules.pro
- Any additional keeps discovered during R8 analysis (e.g., Kotlin metadata, reflection)

</decisions>

<specifics>
## Specific Ideas

No specific requirements — standard R8 configuration practices apply. The key constraint is that this is an open-source, privacy-first app targeting F-Droid, so readability of crash reports matters more than APK size minimization.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 04-r8-release-configuration*
*Context gathered: 2026-02-27*
