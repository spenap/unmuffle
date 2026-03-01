# Pitfalls Research

**Domain:** Android on-device Whisper transcription app — adding language selection, settings, model switching, app rename, and Material 3 polish to existing JNI-backed share target
**Researched:** 2026-03-01
**Confidence:** HIGH — all JNI/package rename pitfalls verified against official Android docs and NDK guides; DataStore pitfalls verified against official docs; model-switching pitfalls inferred from direct codebase analysis of WhisperContext/WhisperEngine patterns

---

## Critical Pitfalls

### Pitfall 1: JNI Function Names Break Silently After Package Rename

**What goes wrong:**
Every JNI function in `whisper_jni.cpp` encodes the full package path in its name using Java name-mangling rules: `Java_com_simonpena_transchat_WhisperLib_00024Companion_initContext`. If the Kotlin package is renamed from `com.simonpena.transchat` to a new package (e.g., `com.simonpena.unmuffle`) without updating every corresponding C++ function signature, the JNI linkage silently breaks. The app will compile and link without errors, but every call to a JNI function will throw `java.lang.UnsatisfiedLinkError` at runtime and crash.

**Why it happens:**
JNI uses string matching to resolve the Java-to-native bridge. The linker does not validate function name alignment at build time — it only fails at the moment the native method is first called. The `extern "C"` guard in `whisper_jni.cpp` prevents C++ mangling but does not help with the Java package path embedded in the function name. There are 7 functions in `whisper_jni.cpp` that must all be updated in sync.

**How to avoid:**
Rename the package in `whisper_jni.cpp` in the same commit as the Kotlin package rename. The transformation is mechanical: replace every occurrence of `com_simonpena_transchat` with `com_simonpena_unmuffle` (or the new package path with underscores instead of dots). Update the ProGuard rules in `proguard-rules.pro` at the same time — the `-keep` rules for `WhisperLib` and `WhisperLib$Companion` also embed the old package name. Verify with `nm -D app/build/intermediates/stripped_native_libs/.../libwhisper.so | grep Java_` after build.

**Warning signs:**
- `UnsatisfiedLinkError` on first transcription attempt after rename
- App compiles cleanly but crashes in `WhisperLib` `companion object { init { ... } }` block
- ProGuard/R8 warnings about missing class `com.simonpena.transchat.WhisperLib` after rename

**Phase to address:** App rename phase (whichever phase handles package rename). Must be done atomically — partial rename leaves the app unrunnable.

---

### Pitfall 2: ProGuard R8 Keep Rules Still Reference Old Package Name After Rename

**What goes wrong:**
`proguard-rules.pro` contains explicit `-keep class com.simonpena.transchat.WhisperLib` and `-keep class com.simonpena.transchat.WhisperLib$Companion` rules. After renaming the Kotlin package, these rules silently become no-ops — they match nothing, R8 now has no rule protecting the JNI bridge classes, and it freely renames or removes them during shrinking. Release builds crash; debug builds (where R8 is disabled) work fine. This creates a CI green / device red situation that only surfaces when testing a signed release APK.

**Why it happens:**
Android Studio's "Rename" refactor updates Kotlin source files but does not scan or update `proguard-rules.pro`. The rules file is treated as a raw text file. The failure is delayed to release build testing, which in this project happens only on tagged CI runs.

**How to avoid:**
After renaming the package, search `proguard-rules.pro` for every occurrence of the old package string and update them. Add a comment above each keep rule documenting why it exists and what to update if renamed. Consider switching to `@Keep` annotations on `WhisperLib` and `WhisperLib$Companion` directly in Kotlin source — these survive refactoring because the annotation moves with the class.

**Warning signs:**
- Debug build works; release build crashes with `UnsatisfiedLinkError` or `ClassNotFoundException`
- R8 output in `app/build/outputs/mapping/release/mapping.txt` shows `WhisperLib` was removed or renamed

**Phase to address:** App rename phase — verify release build specifically, not just debug.

---

### Pitfall 3: Language Dialog in ShareReceiverActivity Violates Content URI Permission Window

**What goes wrong:**
`ShareReceiverActivity` currently copies the shared content URI synchronously in `onCreate()` before any async work, because the grant-URI permission expires when the sending app is backgrounded. If a language selection dialog is shown in `ShareReceiverActivity` before the copy happens, the user could switch to another app while the dialog is visible, causing the sending app to background. When the user returns and dismisses the dialog, the URI permission may have already expired, and the subsequent `AudioCopier.copyToCache(uri)` call will fail with `SecurityException: Permission Denial`.

**Why it happens:**
Android's URI permission model grants temporary read access to the receiving activity's process for the duration of the intent dispatch. When the user switches apps via recents, the permission can be revoked. The current architecture correctly guards against this by doing the copy in `onCreate()` before any suspension or UI event. Introducing a dialog between receipt and copy breaks this invariant.

**How to avoid:**
Two safe patterns:
1. **Copy first, then ask:** Keep `AudioCopier.copyToCache()` as the first operation in `ShareReceiverActivity.onCreate()`. Pass the cached file path to `TranscriptionActivity`, and show the language selection dialog there before starting inference. This is the minimal-risk pattern — the URI is already consumed before any user interaction.
2. **Ask before sharing (not at share time):** Show language selection in `ReadyActivity` and persist the choice to DataStore. `ShareReceiverActivity` reads the persisted preference and uses it directly, with no dialog in the share flow at all. This is the best UX pattern and avoids the timing problem entirely.

Never show a blocking dialog in `ShareReceiverActivity` after receiving the intent but before calling `AudioCopier.copyToCache()`.

**Warning signs:**
- `SecurityException: Permission Denial: opening provider` appears in logcat after the language dialog
- The error is intermittent — it only appears when the user takes time with the dialog (race with background garbage collection of the grant)
- Works fine on fast devices; breaks on slow devices where GC runs more aggressively

**Phase to address:** Language selection phase — the dialog location must be architecturally decided before implementation, not retrofitted.

---

### Pitfall 4: whisper.cpp Context Not Released Before Reloading a Different Model

**What goes wrong:**
`WhisperEngine.loadModel()` calls `whisperContext?.release()` then sets `whisperContext = null` before loading the new model. However, `release()` is a `suspend fun` that dispatches to `WhisperContext`'s single-thread executor — the same executor that `fullTranscribe` runs on. If an in-progress transcription is running when `loadModel()` is called for a model switch, the release is queued after the transcription completes, but the `initContext` JNI call for the new model may execute before the old context is freed, because `initContext` runs on `Dispatchers.Default`, not the single-thread executor. The result is two whisper contexts resident in native memory simultaneously — a ~400MB native memory spike (small + tiny = ~190MB + ~75MB). On constrained devices, `initContext` returns 0 (null) because there is insufficient native memory for the new model.

**Why it happens:**
`WhisperEngine.loadModel()` does `whisperContext?.release()` but does not await the release completing in native code before calling `WhisperContext.createContextFromFile()`. The current single-model design never triggers this because model switching never occurs in production v1.2. Adding a settings page that lets users switch models introduces the possibility of a switch while or immediately after a transcription.

**How to avoid:**
Model switching should only be possible from the settings page, not mid-transcription. Enforce this at the UI level: disable the model selector while transcription is active. At the engine level, add a mutex or serialize model switches through the same single-thread executor so the sequence is always: `fullTranscribe completes → freeContext → initContext(new model)`. Never allow initContext to run concurrently with freeContext.

**Warning signs:**
- Native OOM (initContext returns 0) immediately after switching models while a transcription was in progress
- `whisper_init_from_file_with_params` returns null in logcat right after model switch
- Native memory usage in Android Profiler shows two model-sized allocations briefly before dropping to one

**Phase to address:** Model switching / settings phase. This pitfall does not exist in the current codebase and is introduced only when model switching is added.

---

### Pitfall 5: DataStore Multiple Instance Corruption

**What goes wrong:**
`androidx.datastore.preferences.preferencesDataStore` creates a DataStore backed by a file on disk. If multiple `DataStore<Preferences>` instances are created for the same file name (e.g., one in a ViewModel, one in the Application class, one in a helper object), DataStore throws `IllegalStateException: There are multiple DataStores active for the same file` at runtime. All DataStore reads and writes fail for the session. This is particularly easy to trigger in a ViewModel-heavy app where the ViewModel observes DataStore but an Activity also tries to create its own instance directly.

**Why it happens:**
DataStore enforces single-instance through a global registry. Any second call to `Context.dataStore` (the property delegate) for the same file name, even in a different class, is detected and throws. In a simple app without DI, developers often create instances ad hoc wherever they need access to preferences, not realizing DataStore is not thread-safe across instances.

**How to avoid:**
Declare the DataStore instance exactly once, at the top level of a file (not inside a class), using the `preferencesDataStore` property delegate on `Context`:
```kotlin
// In SettingsRepository.kt — exactly once, top-level
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
```
Access it only through a single `SettingsRepository` (or similar wrapper) that holds the sole reference. ViewModels receive the repository via constructor injection, not by creating their own DataStore instances. If no DI framework is available, use an `object` singleton.

**Warning signs:**
- `IllegalStateException: There are multiple DataStores active for the same file` in logcat
- Settings work on first launch but crash on subsequent launches after a configuration change
- Settings reads return stale data because only one of multiple instances received the write

**Phase to address:** Settings phase — establish the singleton pattern in the first commit before any other component reads from DataStore.

---

### Pitfall 6: Material 3 Theme Mismatch With Existing MaterialComponents Views

**What goes wrong:**
The existing `themes.xml` extends `Theme.MaterialComponents.DayNight.DarkActionBar` (Material 2). Migrating to `Theme.Material3` breaks every `MaterialButton`, `CircularProgressIndicator`, and `MaterialComponents` XML attribute that currently exists in the layout files (`activity_transcription.xml`, etc.). Specifically: `?attr/materialButtonOutlinedStyle`, `?attr/textAppearanceBody1`, `?attr/textAppearanceHeadline6`, and `?attr/textAppearanceBody2` are Material 2 attribute names. Material 3 renames them to `?attr/textAppearanceBodyMedium`, `?attr/textAppearanceHeadlineSmall`, etc. A partial migration — changing the theme parent but not the attribute references — causes attribute resolution failures that appear as white/missing text, wrong button shapes, or `InflateException` at runtime.

**Why it happens:**
M2 and M3 are not drop-in compatible. The color system, typography tokens, and component attribute names changed significantly between them. The MDC-Android Compose Theme Adapter bridges Compose → XML theme, but cannot bridge XML → Compose in reverse. Developers often change the theme parent and assume components "just work."

**How to avoid:**
Take a staged approach:
1. Add M3 theme alongside M2 theme (don't delete the old one immediately)
2. Migrate layout files and attribute references in a single sweep — do a global search for every `?attr/textAppearanceBody1`, `?attr/textAppearanceBody2`, `?attr/textAppearanceHeadline6`, `style="?attr/materialButtonOutlinedStyle"` and update to M3 equivalents
3. Test all four UI states (Processing, Success, NoSpeech, Error) on a device before removing M2 theme

If using Jetpack Compose for new screens (settings, language dialog), use `MaterialTheme` from `androidx.compose.material3` and do not mix M2 and M3 Compose imports in the same file.

**Warning signs:**
- `InflateException: Binary XML file: Error inflating class` for Material components after theme change
- Text appears in system default font with no applied style
- Buttons render with square corners instead of rounded (M3 default)
- `?attr/textAppearanceBody1` resolves to null causing transparent/invisible text

**Phase to address:** UI polish phase — do the theme migration as the first step of that phase, before any new UI work, so new components are built against the correct theme from the start.

---

## Technical Debt Patterns

Shortcuts that seem reasonable but create long-term problems.

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Keep `language = "auto"` in JNI params even with a language selection UI | No C++ changes needed | User's explicit language choice is ignored at inference time — UI lies to user | Never — if UI offers language selection, JNI params must reflect it |
| Store language preference in `SharedPreferences` instead of DataStore | No new dependency | Synchronous I/O on main thread risk; cannot be observed as Flow without workarounds | Only if DataStore is considered too heavy for the milestone (it isn't) |
| Rename only `applicationId` in `build.gradle.kts` but not the Kotlin source package | Simpler rename | Source code still says `com.simonpena.transchat`, confusing for open-source contributors | Acceptable as an interim step only if Kotlin namespace rename follows in same PR |
| Show language dialog in a new transparent Activity instead of in the destination Activity | Avoids modifying `ShareReceiverActivity` | Creates a four-activity stack on a simple share, makes back-press behavior unpredictable | Never — adds complexity with no benefit |
| Load both tiny and small models simultaneously to avoid reload delay | Zero latency on model switch | ~265MB native memory usage at all times; OOM on 3GB devices | Never |
| Hardcode language codes as string literals in JNI params | Simple and fast | Unmaintainable when more languages are added; language codes must match whisper.cpp's internal names exactly | Only during initial POC, never in production |

---

## Integration Gotchas

Common mistakes at the boundaries between components.

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| JNI `language` param for whisper.cpp | Passing human-readable names ("Spanish", "English") or locale codes ("es-ES") | whisper.cpp expects ISO 639-1 two-letter codes: `"es"`, `"en"`, `"fr"`. Passing `"auto"` enables language detection. Passing anything else that whisper.cpp does not recognize silently falls back to English |
| DataStore + ViewModel | Creating DataStore instance inside ViewModel | DataStore must outlive ViewModels; create it at Application scope or as a top-level singleton; pass a repository to the ViewModel |
| Model switching + `WhisperEngine.loadModel()` | Calling `loadModel()` without ensuring previous transcription has finished | `loadModel()` calls `release()` on the old context via the single-thread executor, but if `fullTranscribe` is still running on that executor, the executor queue serializes them correctly — however, any new `loadModel()` call on `Dispatchers.Default` that skips the executor will race. Always call `loadModel()` through the same serialized path |
| Rename + CI artifact paths | `upload-artifact` step uses hardcoded path `app/build/outputs/apk/release/app-release*.apk` | This path is determined by Gradle's `outputFileName`, not the app name. The glob `app-release*.apk` already handles variant suffixes. However, the artifact `name:` field in the workflow ("app-release") does not need to change — it is just an artifact label, not a path |
| ShareReceiverActivity label + rename | `android:label="TransChat"` on `ShareReceiverActivity` in `AndroidManifest.xml` | This is the name shown in Android's share sheet. Must be updated to "Unmuffle" in the manifest, not just in `strings.xml`. Both the `<application android:label>` and the `<activity android:label>` on `ShareReceiverActivity` need updating |
| `DownloadManager` notification title after rename | `setTitle("TransChat — Whisper Model")` in `DownloadViewModel.startDownload()` | Hardcoded display string — update to "Unmuffle — Whisper Model" or use a string resource. This appears in system notification shade during the 190MB model download |

---

## Performance Traps

Patterns that work at small scale but cause problems on real devices.

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Showing SHA256 verification progress during model switch in Settings | 190MB SHA256 takes 3-8 seconds on mid-range devices; UI appears frozen | Run verification on `Dispatchers.IO`, show indeterminate spinner, never block the main thread | Any device; immediate on first use |
| Reloading the 190MB model on every share intent instead of keeping it resident | 2-5 second model load per transcription | Keep the current design: model loads once per process in `TranscriptionViewModel`. Model switching from settings takes effect on next share, not mid-session | Always — this was solved in v1.0 with the file-path loading decision |
| Polling DataStore on every frame to check language preference | Battery drain, unnecessary I/O | Use `collectAsState()` on the DataStore Flow in the ViewModel; it only emits on actual changes | Not a severe problem in practice, but causes observable battery usage over time |
| Using `AlertDialog` (View-based) inside a Compose screen | Works but causes interop overhead | Use `AlertDialog` from `androidx.compose.material3` for any Compose screen; reserve View-based dialogs for the existing View-based activities | No immediate breakage, but mixing means maintaining two dialog paradigms |

---

## Security Mistakes

Privacy-specific security issues for this app.

| Mistake | Risk | Prevention |
|---------|------|------------|
| Writing language preference to a world-readable location (e.g., external storage) | Other apps could read the user's language preference, inferring their native language — a privacy violation | Use DataStore in app-private internal storage (`context.dataStore` defaults to `context.dataDir`) — never write preferences to `getExternalFilesDir` |
| Logging the full transcript text at INFO level | Logcat is readable by any app with READ_LOGS permission (granted to some system apps and adb) | Keep `Log.i("Transcript: '${filtered.take(80)}'")` truncated as-is; never log full transcript. Consider removing transcript logging entirely in release builds via BuildConfig flag |
| Caching whisper model in external storage without size check | A corrupt or malicious file at the expected path could be passed to `initContext` | The existing SHA256 verification in `ModelManager.isModelReady()` mitigates this. Do not bypass verification when adding model switching |
| Using `android.permission.WRITE_EXTERNAL_STORAGE` to store tiny model | Grants broader storage access than needed; rejected on API 29+ | Keep using `getExternalFilesDir()` which does not require `WRITE_EXTERNAL_STORAGE` — this is already correct in `ModelManager` |

---

## UX Pitfalls

Common user experience mistakes specific to this app's share-target model.

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| Language selection dialog appears every time, even when user has set a preference | Friction on every use; defeats the "fast path" for the primary use case | "Remember my choice" checkbox in the dialog, or a separate Settings page that sets the default. The dialog becomes the fallback, not the primary flow |
| Model switch in settings takes effect immediately while a transcription is running | User sees a different model transcribing the audio than the one they selected | Disable model selector while transcription is active; apply model switch only at start of next session |
| "Unmuffle" name not appearing in Android share sheet after rename | Users cannot find the app in the share sheet; they look for "TransChat" | `android:label` on the `ShareReceiverActivity` in `AndroidManifest.xml` controls the share sheet label. Update this, not just the launcher label |
| Settings page accessible from share flow (user presses back mid-transcription) | Back-press during transcription cancels inference (current behavior) but users expect to reach settings | Keep settings accessible only from the launcher (home screen tap), not from the share flow. The share flow is a one-shot workflow |
| Layout regression (title overlaps transcript text) shipped without fix | Core result screen is broken; users cannot read transcripts | This is listed as the first v1.3 requirement — fix the layout bug before all other polish work |

---

## "Looks Done But Isn't" Checklist

Things that appear complete but are missing critical pieces.

- [ ] **Package rename:** Kotlin source, `AndroidManifest.xml`, `build.gradle.kts` (`namespace` + `applicationId`), `whisper_jni.cpp` (7 function names), `proguard-rules.pro` (2 keep rules), `CMakeLists.txt` (`project(whisper_transchat)`), README.md. All 8 locations must be updated.
- [ ] **App display name rename:** `android:label` in `<application>` tag, `android:label` on `ShareReceiverActivity`, notification title in `DownloadViewModel.setTitle()`. Three separate locations.
- [ ] **Language selection:** UI allows picking a language, but whisper.cpp `params.language` in `whisper_jni.cpp` still reads `"auto"`. Language choice must be passed through ViewModel → WhisperEngine → `fullTranscribe()` → JNI params. Otherwise the UI is decorative.
- [ ] **Model switching:** Settings page shows a model selector, but `ModelManager` only knows about `ggml-small-q5_1.bin`. A tiny model variant needs its own URL, SHA256, and filename constant. `DownloadViewModel` must handle downloading a second model. `WhisperEngine.loadModel()` must accept a dynamic path, not a hardcoded one from `ModelManager.modelFile()`.
- [ ] **DataStore singleton:** Settings values are persisted, but DataStore is instantiated in more than one place. The `IllegalStateException` only appears at runtime, not at compile time.
- [ ] **CI after rename:** Workflow hardcodes `name: app-release` and `name: app-debug` as artifact names — these do not need to change. However, verify the `lintDebug` and `assembleDebug` task names still work (they are Gradle task names based on build variants, not the app name — they do not change on rename).

---

## Recovery Strategies

When pitfalls occur despite prevention, how to recover.

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| JNI function names not updated after rename | LOW | Update all 7 occurrences in `whisper_jni.cpp`; clean build (`./gradlew clean assembleDebug`); verify with `nm` |
| ProGuard keep rules still use old package | LOW | Update 2 rules in `proguard-rules.pro`; build release variant specifically (`assembleRelease`); test on device |
| URI permission expired due to dialog in ShareReceiverActivity | MEDIUM | Move dialog to `TranscriptionActivity` (show before starting inference); no architectural rework needed |
| Two WhisperContexts in memory simultaneously | MEDIUM | Add check: disable model switch UI while `uiState` is `Processing`; single-line guard in the ViewModel |
| DataStore multiple instance crash | LOW | Find all `dataStore` property accesses; consolidate into single top-level declaration; remove duplicates |
| M2 → M3 partial migration breaks layouts | MEDIUM | Revert theme change; re-apply with full attribute sweep before re-committing; test all four UiState branches |

---

## Pitfall-to-Phase Mapping

How roadmap phases should address these pitfalls.

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| JNI names break after package rename | App rename phase | `adb shell am start -n com.simonpena.unmuffle/.ShareReceiverActivity` followed by share test produces transcript, not `UnsatisfiedLinkError` |
| ProGuard rules reference old package | App rename phase | Build `assembleRelease` and install on device; confirm transcription works in release build |
| URI permission expires during dialog | Language selection phase | Test: share a WhatsApp voice message, spend 30+ seconds on language dialog, switch to WhatsApp and back, then confirm — should either show transcript or show clean error, not `SecurityException` crash |
| Two WhisperContexts in memory | Model switching phase | Switch model from Settings during active transcription; verify no crash, verify memory in Android Profiler shows only one model-sized allocation |
| DataStore multiple instance | Settings phase | Launch app, rotate device twice, navigate to settings and change language twice — no `IllegalStateException` in logcat |
| M2 → M3 theme mismatch | UI polish phase | All four `UiState` branches visible on device with correct text sizes, button shapes, and colors per Material 3 spec |
| Language not passed to JNI | Language selection phase | Set language to "Spanish" in settings; share an English audio file; verify transcript is in Spanish (not auto-detected) |
| Display name not updated everywhere | App rename phase | Check share sheet label, launcher icon label, and download notification title all read "Unmuffle" |

---

## Sources

- Android NDK JNI Tips (official): https://developer.android.com/training/articles/perf-jni
- Android configure app module / applicationId vs namespace: https://developer.android.com/build/configure-app-module
- Android URI permissions documentation: https://developer.android.com/reference/android/content/UriPermission
- DataStore official docs: https://developer.android.com/topic/libraries/architecture/datastore
- DataStore singleton requirement: https://medium.com/@ivanlocode/kotlin-android-development-a-simple-fix-there-are-multiple-datastores-active-for-the-same-7a007b4b6137
- Material 2 to Material 3 migration guide: https://developer.android.com/develop/ui/compose/designsystems/material2-material3
- whisper.cpp language detection issue: https://github.com/ggml-org/whisper.cpp/issues/1831
- Direct codebase analysis: `whisper_jni.cpp`, `WhisperLib.kt`, `WhisperEngine.kt`, `ModelManager.kt`, `ShareReceiverActivity.kt`, `proguard-rules.pro`

---
*Pitfalls research for: Unmuffle v1.3 — language selection, settings, model switching, app rename, Material 3 polish*
*Researched: 2026-03-01*
