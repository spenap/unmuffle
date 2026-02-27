# Phase 4: R8 Release Configuration - Research

**Researched:** 2026-02-27
**Domain:** Android R8 code/resource shrinking, ProGuard keep rules, JNI preservation
**Confidence:** HIGH

## Summary

This phase replaces a blanket `-keep class com.simonpena.transchat.** { *; }` rule with surgical keep rules, enables resource shrinking, and disables obfuscation. The app has 17 Kotlin files with 3 sealed class hierarchies, 6 JNI native methods, and 2 ViewModels instantiated via reflection. The current `proguard-android-optimize.txt` base already handles activities (via manifest), native method downcalls, enums, and Parcelable. The AndroidX `lifecycle-viewmodel` library ships consumer ProGuard rules that automatically keep ViewModel/AndroidViewModel constructors. This means the only things requiring **explicit** keep rules in the app's `proguard-rules.pro` are: (1) the WhisperLib class and companion (JNI name mangling), (2) sealed class subclasses as a safety measure, and (3) the future ProgressCallback placeholder.

**Primary recommendation:** Remove the blanket keep, add `-dontobfuscate`, add `shrinkResources = true`, write minimal targeted keep rules for WhisperLib and sealed hierarchies, then verify the full flow on a physical device.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **Code shrinking**: enabled (default with `minifyEnabled = true`)
- **Obfuscation**: disabled -- add `-dontobfuscate` to proguard-rules.pro. Keeps class names readable in crash reports. Open-source F-Droid app has nothing to hide.
- **Resource shrinking**: enabled -- add `shrinkResources = true` in build.gradle.kts release block. Free size reduction, low risk.
- Remove blanket `-keep class com.simonpena.transchat.** { *; }` -- this is the whole point of the phase
- Keep WhisperLib native methods (JNI name mangling must be preserved) -- existing rules for native methods are correct
- Keep sealed class hierarchies used in `when()` expressions: `DownloadViewModel.State`, `AudioCopier.CopyResult`, `TranscriptionViewModel.UiState` -- R8 may strip subclasses it considers unreachable
- ViewModels instantiated via ViewModelProvider reflection -- keep `DownloadViewModel` and `TranscriptionViewModel`
- Activities are referenced in AndroidManifest.xml -- R8 keeps these automatically, no explicit rule needed
- Add commented placeholder keep rule for `ProgressCallback` interface (Phase 5 will uncomment)
- **Full flow verification**: fresh install on physical arm64-v8a device
- Test sequence: install release APK -> model download completes -> share voice message -> transcription succeeds -> transcript displays -> Copy/Share buttons work
- Physical device only (no emulator) -- same device used for v1.0 verification
- Measure APK size before/after, document in SUMMARY.md

### Claude's Discretion
- Exact keep rule syntax and granularity (class-level vs member-level)
- Whether to keep entire sealed hierarchies or just the subclasses
- Order and commenting style in proguard-rules.pro
- Any additional keeps discovered during R8 analysis (e.g., Kotlin metadata, reflection)

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| REL-01 | ProGuard/R8 rules replaced with targeted keeps (JNI native methods, sealed classes) | Full analysis of all 3 sealed hierarchies, 6 JNI methods, 2 ViewModels. Exact keep rule syntax provided below. |
| REL-02 | Release APK verified with R8 shrinking on physical device | Verification checklist covers full flow: install, download, share, transcribe, display, copy/share. |
</phase_requirements>

## Standard Stack

### Core (already in project)
| Library | Version | Purpose | R8 Relevance |
|---------|---------|---------|--------------|
| AGP (Android Gradle Plugin) | (project version) | Build tooling, R8 integration | R8 is the default shrinker since AGP 3.4 |
| proguard-android-optimize.txt | (bundled with AGP) | Default Android keep rules | Auto-keeps manifest entries, native downcalls, enums, Parcelable |
| androidx.lifecycle:viewmodel | (from libs.versions.toml) | ViewModel framework | Ships consumer rules keeping ViewModel constructors |
| androidx.activity:activity-ktx | (from libs.versions.toml) | `by viewModels()` delegate | Uses ViewModelProvider internally |

### Supporting (no new dependencies)
No new libraries needed. R8 configuration is entirely build-configuration and ProGuard rules.

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `-dontobfuscate` | Full obfuscation | Obfuscation saves ~2-5% APK size but makes crash reports unreadable. For open-source F-Droid app, readability wins. User locked this decision. |
| `shrinkResources = true` | No resource shrinking | Free size win with no risk for this app. User locked this decision. |

## Architecture Patterns

### What proguard-android-optimize.txt Already Covers

These rules are baked into the default ProGuard config file that the project already references. No need to duplicate them:

| What | Rule in Default File | Implication |
|------|---------------------|-------------|
| Activities in AndroidManifest.xml | R8 uses manifest as entry point | All 6 activities (MainActivity, DownloadActivity, ReadyActivity, ShareReceiverActivity, TranscriptionActivity, SmokeTestActivity) are kept automatically |
| Native method downcalls | `-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }` | WhisperLib.Companion's 6 external methods survive as downcall targets |
| View onClick handlers | `-keepclassmembers public class * extends android.view.View { void set*(***); *** get*(); }` | Covers XML onClick if used |
| Enums | `-keepclassmembers enum * { ... }` | Not currently used but covered |
| Parcelable CREATOR | `-keepclassmembers class * implements android.os.Parcelable { ... }` | Not currently used but covered |

### What AndroidX Lifecycle Library Already Covers

The `lifecycle-viewmodel` AAR ships consumer ProGuard rules (verified from [Google Source](https://android.googlesource.com/platform/frameworks/support/+/a9ac247af2afd4115c3eb6d16c05bc92737d6305/lifecycle/viewmodel/proguard-rules.pro)):

```proguard
-keep class * extends androidx.lifecycle.ViewModel { <init>(); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(android.app.Application); }
```

This means **DownloadViewModel** and **TranscriptionViewModel** constructors are already preserved by the library. The `by viewModels()` delegate uses `ViewModelProvider` which instantiates via reflection -- but the library's own rules handle this.

**Recommendation:** Do NOT add redundant ViewModel keep rules. The library handles it. However, document this reasoning in the proguard-rules.pro as a comment so future developers understand why there's no explicit ViewModel rule.

### What Needs Explicit Keep Rules

#### 1. WhisperLib JNI Class and Companion (CRITICAL)

The default native methods rule keeps native method signatures, but with `-dontobfuscate` disabled (meaning obfuscation is off), we don't need to worry about renaming. However, the class itself must not be tree-shaken. Since `WhisperLib` is `internal class` and its companion methods are called from `WhisperContext` (which is also internal), R8's static analysis **should** see the call chain from Activities -> ViewModel -> WhisperEngine -> WhisperContext -> WhisperLib. But JNI is the critical risk: the C++ side resolves methods by fully-qualified name at runtime.

**Keep rule:**
```proguard
# WhisperLib JNI bridge — C++ resolves methods by package/class/method name
# Both the class and its Companion must survive with original names
-keep class com.simonpena.transchat.WhisperLib { *; }
-keep class com.simonpena.transchat.WhisperLib$Companion { *; }
```

**Why class-level, not just members:** The JNI C++ code uses `Java_com_simonpena_transchat_WhisperLib_00024Companion_initContext` style names. The class name is baked into the JNI symbol. Even with `-dontobfuscate`, R8 could still remove the class if it incorrectly determines it's unused (the native upcall path is invisible to static analysis).

#### 2. Sealed Class Hierarchies (SAFETY)

Analysis of whether R8 actually needs keep rules for these:

| Sealed Class | Location | Subclasses | Referenced via `is` checks | R8 Risk |
|---|---|---|---|---|
| `DownloadViewModel.State` | Nested in DownloadViewModel | `Idle`, `Downloading`, `Verifying`, `Complete`, `Failed` | Yes, in `DownloadActivity.renderState()` | LOW -- all subclasses are referenced in `when` expression |
| `UiState` | Top-level in TranscriptionViewModel.kt | `Processing`, `Success`, `NoSpeech`, `Error` | Yes, in `TranscriptionActivity.onCreate()` | LOW -- all subclasses are referenced in `when` expression |
| `AudioCopier.CopyResult` | Nested in AudioCopier object | `Success`, `TooLarge`, `NoContent`, `CopyFailed` | Yes, in `ShareReceiverActivity.onCreate()` | LOW -- all subclasses are referenced in `when` expression |

**Analysis:** All sealed class subclasses are directly referenced in `when` expressions with `is` type checks. R8 can trace from Activity (manifest entry point) -> `when` branch -> sealed subclass. In theory, R8 should keep all of them.

**However:** The CONTEXT.md locks the decision to keep these as a safety measure. Sealed class + R8 has a history of subtle issues (see [moshi#1200](https://github.com/square/moshi/issues/1200)). The cost of keeping them is near-zero (they're tiny classes). The cost of NOT keeping them and hitting a runtime crash is high.

**Recommended keep rules:**
```proguard
# Sealed class hierarchies — keep all subclasses to prevent R8 from
# removing them if static analysis misses indirect references.
# Cost: negligible. Risk of not keeping: runtime ClassNotFoundException.

# DownloadViewModel.State (nested sealed class)
-keep class com.simonpena.transchat.DownloadViewModel$State { *; }
-keep class com.simonpena.transchat.DownloadViewModel$State$* { *; }

# UiState (top-level sealed class in TranscriptionViewModel.kt)
-keep class com.simonpena.transchat.UiState { *; }
-keep class com.simonpena.transchat.UiState$* { *; }

# AudioCopier.CopyResult (nested sealed class)
-keep class com.simonpena.transchat.AudioCopier$CopyResult { *; }
-keep class com.simonpena.transchat.AudioCopier$CopyResult$* { *; }
```

**Note on granularity:** Using `$*` wildcard matches all inner classes (subclasses). This is more maintainable than listing each subclass individually and costs nothing extra since we're using `-dontobfuscate`.

#### 3. ProgressCallback Placeholder (Phase 5 prep)

```proguard
# Phase 5: Uncomment when ProgressCallback interface is added
# -keep class com.simonpena.transchat.ProgressCallback { *; }
```

### Recommended proguard-rules.pro Structure

```proguard
# ============================================================================
# TransChat ProGuard/R8 Rules
# ============================================================================
# R8 mode: code shrinking ON, obfuscation OFF, resource shrinking ON
# Readable class names for crash reports (open-source F-Droid app)
# ============================================================================

# --- Global flags -----------------------------------------------------------
-dontobfuscate

# --- JNI bridge -------------------------------------------------------------
# WhisperLib: C++ resolves methods by fully-qualified JNI name
# (Java_com_simonpena_transchat_WhisperLib_00024Companion_*)
# Both the class and Companion must survive with original names and members
-keep class com.simonpena.transchat.WhisperLib { *; }
-keep class com.simonpena.transchat.WhisperLib$Companion { *; }

# --- Sealed class hierarchies -----------------------------------------------
# R8 can see these via when() expressions, but we keep them defensively.
# Cost: negligible. Risk without: potential ClassNotFoundException at runtime.

# DownloadViewModel.State and subclasses
-keep class com.simonpena.transchat.DownloadViewModel$State { *; }
-keep class com.simonpena.transchat.DownloadViewModel$State$* { *; }

# UiState and subclasses (top-level class)
-keep class com.simonpena.transchat.UiState { *; }
-keep class com.simonpena.transchat.UiState$* { *; }

# AudioCopier.CopyResult and subclasses
-keep class com.simonpena.transchat.AudioCopier$CopyResult { *; }
-keep class com.simonpena.transchat.AudioCopier$CopyResult$* { *; }

# --- Phase 5: Progress callback (uncomment when interface is added) ----------
# -keep class com.simonpena.transchat.ProgressCallback { *; }

# --- Notes -------------------------------------------------------------------
# ViewModels: Kept automatically by lifecycle-viewmodel library consumer rules
#   (-keep class * extends AndroidViewModel { <init>(Application); })
# Activities: Kept automatically by R8 via AndroidManifest.xml entry points
# Native methods: Kept by proguard-android-optimize.txt default rules
#   (-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; })
```

### build.gradle.kts Changes

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true   // <-- ADD THIS LINE
    signingConfig = signingConfigs.getByName("debug")
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

Only one line changes in build.gradle.kts: adding `isShrinkResources = true`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Native method preservation | Manual listing of each JNI method | Default `proguard-android-optimize.txt` rule + class-level keep for WhisperLib | Default rules already handle native downcalls; class-level keep is simpler and safer |
| ViewModel reflection keep | Custom ViewModel keep rules | AndroidX lifecycle library consumer rules | The library ships its own ProGuard rules that are automatically merged |
| Activity preservation | Manual activity keep rules | R8 automatic manifest scanning | R8 uses AndroidManifest.xml as entry point; all declared activities are kept |
| R8 debugging | Manual inspection of DEX | `-printconfiguration` and `-whyareyoukeeping` flags | Built-in R8 debugging tools show exactly what rules are active and why classes are kept |

**Key insight:** The Android toolchain already handles most R8 concerns through default rules and library consumer rules. The only app-specific rules needed are for JNI class names and sealed class safety.

## Common Pitfalls

### Pitfall 1: Keeping Too Much (Blanket Keep)
**What goes wrong:** Using `-keep class com.package.** { *; }` defeats all R8 optimization -- no dead code elimination, no unused class removal.
**Why it happens:** Fear of runtime crashes leads to overly broad rules.
**How to avoid:** Remove blanket keep (the whole point of this phase). Start with minimal rules and verify on device.
**Warning signs:** APK size doesn't decrease after enabling R8.

### Pitfall 2: Forgetting JNI Upcalls
**What goes wrong:** R8 removes a method that native code calls back into (upcall), causing NoSuchMethodError at runtime.
**Why it happens:** R8 is a static analysis tool and cannot see calls originating from C++ JNI code.
**How to avoid:** Keep any class/method that C++ calls into. Currently WhisperLib only has downcalls (Kotlin -> C++), but Phase 5's ProgressCallback will add an upcall (C++ -> Kotlin). The commented placeholder addresses this.
**Warning signs:** `NoSuchMethodError` or `UnsatisfiedLinkError` only in release builds.

### Pitfall 3: shrinkResources Without minifyEnabled
**What goes wrong:** `shrinkResources` requires `minifyEnabled = true`. Build will fail or warn if enabled alone.
**Why it happens:** Resource shrinking depends on code shrinking to identify unused resources.
**How to avoid:** Always enable both together (which we are).
**Warning signs:** Gradle build error or warning about incompatible settings.

### Pitfall 4: Testing Only Happy Path
**What goes wrong:** Release build works for transcription but crashes on download or error states because sealed class subclasses for error/edge states were stripped.
**Why it happens:** R8 might determine error-state subclasses are unreachable in certain code paths.
**How to avoid:** Test ALL states of ALL sealed hierarchies: Idle, Downloading, Verifying, Complete, Failed for DownloadViewModel.State; Processing, Success, NoSpeech, Error for UiState; Success, TooLarge, NoContent, CopyFailed for CopyResult.
**Warning signs:** Crash reports mentioning `ClassNotFoundException` for inner classes.

### Pitfall 5: Forgetting to Measure Before
**What goes wrong:** Can't document APK size improvement without a "before" baseline.
**Why it happens:** Eagerness to start changing rules without first building the current version.
**How to avoid:** Build the release APK with current blanket keep FIRST, record size, THEN make changes.
**Warning signs:** No baseline number when writing SUMMARY.md.

### Pitfall 6: Not Removing the Old Blanket Rule Completely
**What goes wrong:** Leaving the blanket rule alongside surgical rules means R8 still keeps everything.
**Why it happens:** Incremental editing -- adding new rules without removing old ones.
**How to avoid:** Complete replacement of proguard-rules.pro content, not incremental additions.
**Warning signs:** APK size unchanged after "optimization."

## Code Examples

### Current proguard-rules.pro (BEFORE -- to be replaced)
```proguard
# Source: /workspace/transchat-app/app/proguard-rules.pro
# Keep all JNI-facing native methods
-keepclassmembers class * {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}
# Keep the entire transchat package to preserve JNI name mangling
-keep class com.simonpena.transchat.** { *; }
# Keep WhisperLib companion object specifically
-keep class com.simonpena.transchat.WhisperLib { *; }
-keep class com.simonpena.transchat.WhisperLib$Companion { *; }
```

**Problems:** Line 10 (`-keep class com.simonpena.transchat.** { *; }`) keeps every class and member in the entire package, making R8 unable to remove any dead code. Lines 1-6 are redundant with the default `proguard-android-optimize.txt` rules.

### Current build.gradle.kts release block (BEFORE)
```kotlin
// Source: /workspace/transchat-app/app/build.gradle.kts lines 42-54
release {
    isMinifyEnabled = true
    signingConfig = signingConfigs.getByName("debug")
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

### R8 Debugging Commands
```bash
# Add to proguard-rules.pro temporarily to debug issues:
# -printconfiguration build/outputs/logs/configuration.txt
# -whyareyoukeeping class com.simonpena.transchat.SomeClass

# Build release APK:
./gradlew assembleRelease

# Check APK size:
ls -la app/build/outputs/apk/release/app-release.apk

# Install on device:
adb install -r app/build/outputs/apk/release/app-release.apk

# Watch logcat for R8-related runtime errors:
adb logcat -s "AndroidRuntime:E" "TranscriptionVM:*" "DownloadViewModel:*"
```

### Sealed Class Inventory (Complete)

| Sealed Class | File | Subclasses | Kotlin Type |
|---|---|---|---|
| `DownloadViewModel.State` | DownloadViewModel.kt:22 | `Idle` (object), `Downloading` (data class), `Verifying` (object), `Complete` (object), `Failed` (data class) | Nested sealed class |
| `UiState` | TranscriptionViewModel.kt:23 | `Processing` (object), `Success` (data class), `NoSpeech` (data class), `Error` (data class) | Top-level sealed class |
| `AudioCopier.CopyResult` | AudioCopier.kt:23 | `Success` (data class), `TooLarge` (data class), `NoContent` (data object), `CopyFailed` (data object) | Nested sealed class in object |

### JNI Method Inventory (Complete)

All 6 methods are on `WhisperLib.Companion` (lines 42-51 of WhisperLib.kt):
| Method | JNI Signature |
|--------|---------------|
| `initContext(String): Long` | Downcall |
| `fullTranscribe(Long, Int, FloatArray)` | Downcall |
| `getTextSegmentCount(Long): Int` | Downcall |
| `getTextSegment(Long, Int): String` | Downcall |
| `freeContext(Long)` | Downcall |
| `getSystemInfo(): String` | Downcall |

All are **downcalls** (Kotlin -> C++). No upcalls exist yet. Phase 5 will add a ProgressCallback upcall.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `proguard-android.txt` (includes `-dontoptimize`) | `proguard-android-optimize.txt` | AGP 8.1+ default, AGP 9.0 drops old file | Project already uses optimize variant -- good |
| Manual ViewModel keep rules | Library consumer rules auto-merged | AndroidX Lifecycle 2.x+ | No app-level ViewModel rules needed |
| R8 compat mode | R8 full mode (default since AGP 8.0) | AGP 8.0 | More aggressive optimization; sealed class rules are extra protection |

**Deprecated/outdated:**
- `proguard-android.txt` -- dropped in AGP 9.0. Project correctly uses `proguard-android-optimize.txt`.
- Manual `-keep` for Activities -- unnecessary since R8 scans AndroidManifest.xml automatically.

## Open Questions

1. **Will R8 actually strip sealed subclasses in this codebase?**
   - What we know: All subclasses are directly referenced in `when` expressions. R8 static analysis should trace them from Activity entry points.
   - What's unclear: R8 full mode can be more aggressive than expected. Historical issues exist with sealed classes and R8 (moshi#1200).
   - Recommendation: Keep them defensively. The cost is near-zero. Verify by temporarily removing keep rules and checking if build still works (optional, low priority).

2. **Exact APK size reduction?**
   - What we know: Blanket keep prevents all dead code elimination. With surgical rules, unused code from AndroidX/Material libraries will be stripped.
   - What's unclear: The app is small (17 files); most savings will come from library tree-shaking, not app code.
   - Recommendation: Build before and after, document the delta. Even a small reduction validates the approach.

## Sources

### Primary (HIGH confidence)
- [Android official: Enable app optimization with R8](https://developer.android.com/build/shrink-code) -- shrinkResources, minifyEnabled configuration
- [Android official: Keep rule use cases and examples](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples) -- JNI native methods, reflection patterns
- [Android official: About keep rules](https://developer.android.com/topic/performance/app-optimization/keep-rules-overview) -- R8 entry points, automatic manifest scanning
- [AndroidX lifecycle-viewmodel proguard-rules.pro](https://android.googlesource.com/platform/frameworks/support/+/a9ac247af2afd4115c3eb6d16c05bc92737d6305/lifecycle/viewmodel/proguard-rules.pro) -- Library ships ViewModel/AndroidViewModel keep rules
- [proguard-android-optimize.txt source](https://android.googlesource.com/platform/sdk/%2B/master/files/proguard-android-optimize.txt) -- Default rules covering native methods, enums, Parcelable
- Project source code analysis (WhisperLib.kt, ViewModels, sealed classes, build.gradle.kts, AndroidManifest.xml)

### Secondary (MEDIUM confidence)
- [Android Developers Blog: Configure and troubleshoot R8 Keep Rules (Nov 2025)](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html) -- narrowly scoped rules, debugging with -printconfiguration
- [Zac Sweers: Android's Built-in ProGuard Rules: The Missing Guide](https://www.zacsweers.dev/android-proguard-rules/) -- What default rules actually cover
- [R8 FAQ](https://r8.googlesource.com/r8/+/refs/heads/main/compatibility-faq.md) -- Reflection-instantiated classes need explicit keep

### Tertiary (LOW confidence)
- [moshi#1200: Sealed class issues with R8 obfuscation](https://github.com/square/moshi/issues/1200) -- Historical evidence of sealed class + R8 problems (specific to Moshi serialization, but establishes pattern)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- directly verified from project source and official Android docs
- Architecture: HIGH -- complete inventory of all sealed classes, JNI methods, ViewModels; library consumer rules verified from Google Source
- Pitfalls: HIGH -- based on official docs, verified library behavior, and project source analysis
- Keep rule syntax: HIGH -- follows official Android documentation patterns, verified against default rules

**Research date:** 2026-02-27
**Valid until:** 2026-06-27 (stable domain; R8 configuration patterns change slowly)
