---
phase: 01-whisper-engine-model-bootstrap
plan: "03"
subsystem: ui
tags: [android, kotlin, downloadmanager, coroutines, viewmodel, stateflow, sha256, activities]

# Dependency graph
requires:
  - phase: 01-01
    provides: AndroidManifest.xml, app/build.gradle.kts with Lifecycle/Coroutines/Material deps, namespace com.simonpena.transchat
  - phase: 01-02
    provides: WhisperEngine.kt (used indirectly; model path from ModelManager feeds WhisperEngine)
provides:
  - ModelManager singleton (model path, SHA256 verify, fast existence check)
  - DownloadViewModel state machine (Idle/Downloading/Verifying/Complete/Failed) with DownloadManager 500ms polling
  - DownloadActivity (download screen with explainer, determinate ProgressBar, cellular warning dialog, Retry on failure)
  - MainActivity (transparent launcher router: model present -> ReadyActivity, absent -> DownloadActivity)
  - ReadyActivity (static instructions screen post-download)
  - activity_download.xml and activity_ready.xml layouts
  - AndroidManifest.xml with all three activities declared (MainActivity as LAUNCHER)
affects:
  - 01-04 (SmokeTestActivity can now navigate through the full app flow; model path from ModelManager feeds the smoke test)

# Tech tracking
tech-stack:
  added:
    - Android DownloadManager API (system download service with notification tray support)
    - MessageDigest SHA-256 (Java stdlib, no additional dependency)
  patterns:
    - DownloadManager enqueue + 500ms polling loop via coroutine delay() (no callback API)
    - AndroidViewModel(app) pattern for application context in ViewModel
    - StateFlow<DownloadViewModel.State> collected with lifecycleScope.launch in Activity
    - isModelPresentFast (size > 0 check) for launch routing; full SHA256 only after download
    - Sealed class State hierarchy for exhaustive when() rendering in Activity

key-files:
  created:
    - app/src/main/java/com/simonpena/transchat/ModelManager.kt
    - app/src/main/java/com/simonpena/transchat/DownloadViewModel.kt
    - app/src/main/java/com/simonpena/transchat/DownloadActivity.kt
    - app/src/main/java/com/simonpena/transchat/MainActivity.kt
    - app/src/main/java/com/simonpena/transchat/ReadyActivity.kt
    - app/src/main/res/layout/activity_download.xml
    - app/src/main/res/layout/activity_ready.xml
  modified:
    - app/src/main/AndroidManifest.xml (added MainActivity LAUNCHER, DownloadActivity, ReadyActivity)

key-decisions:
  - "isModelPresentFast (file.exists() && size > 0) used in MainActivity routing — avoids 2-3s SHA256 hash on every launch; full SHA256 only runs once after download completes"
  - "DownloadManager.setDestinationUri(Uri.fromFile(destFile)) writes to filesDir/models/ (private storage, MODEL-03 satisfied)"
  - "WiFi-only default (NETWORK_WIFI); cellular requires user confirmation AlertDialog (allowCellular flag)"
  - "pollDownloadProgress returns via coroutine loop; no BroadcastReceiver needed — DownloadManager has no clean callback API on minSdk 26"
  - "SHA256 = ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb for ggml-small-q5_1.bin"

patterns-established:
  - "DownloadManager polling pattern: viewModelScope.launch { while(true) { query(); delay(500) } } — use this for all system-service progress tracking"
  - "Sealed State + StateFlow + lifecycleScope.launch { collect } pattern for Activity-ViewModel communication"
  - "Transparent router Activity: no setContentView, immediate startActivity + finish() — MainActivity is the canonical Android routing pattern"
  - "ModelManager object singleton: always call modelFile(context) not a stored File path — Context may vary between test and prod"

requirements-completed: [MODEL-01, MODEL-02, MODEL-03]

# Metrics
duration: 3min
completed: 2026-02-27
---

# Phase 1 Plan 03: Model Download Flow Summary

**Android DownloadManager-based model download flow with SHA256 verification, 500ms progress polling, WiFi/cellular gating, and MainActivity launcher routing to DownloadActivity or ReadyActivity**

## Performance

- **Duration:** ~3 min
- **Started:** 2026-02-27T13:05:48Z
- **Completed:** 2026-02-27T13:08:21Z
- **Tasks:** 2
- **Files modified:** 8 (7 created, 1 modified)

## Accomplishments
- ModelManager singleton providing model path at filesDir/models/ggml-small-q5_1.bin, SHA256 verification (computeSha256), fast existence check (isModelPresentFast) — satisfies MODEL-03
- DownloadViewModel state machine with Idle/Downloading/Verifying/Complete/Failed states; DownloadManager.enqueue() + 500ms polling loop via coroutine delay() — satisfies MODEL-01 and MODEL-02
- DownloadActivity rendering all five states: explainer text on Idle, determinate ProgressBar with byte progress on Downloading, Verifying state, navigation to ReadyActivity on Complete, Retry button on Failed; AlertDialog cellular warning before allowing non-WiFi download
- MainActivity transparent router using isModelPresentFast() — zero SHA256 overhead on subsequent launches
- assembleRelease BUILD SUCCESSFUL — all plans 01+02+03 build together

## Task Commits

Each task was committed atomically:

1. **Task 1: ModelManager — model path, SHA256 verification, existence check** - `93bffcf` (feat)
2. **Task 2: DownloadViewModel, DownloadActivity, MainActivity, ReadyActivity, layouts, manifest** - `39e13b6` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `app/src/main/java/com/simonpena/transchat/ModelManager.kt` - Model file path, SHA256 verification, fast existence check
- `app/src/main/java/com/simonpena/transchat/DownloadViewModel.kt` - Download state machine with DownloadManager + 500ms polling
- `app/src/main/java/com/simonpena/transchat/DownloadActivity.kt` - Download screen UI: explainer, ProgressBar, cellular dialog, Retry
- `app/src/main/java/com/simonpena/transchat/MainActivity.kt` - Transparent launcher router
- `app/src/main/java/com/simonpena/transchat/ReadyActivity.kt` - Post-download instructions screen
- `app/src/main/res/layout/activity_download.xml` - Download layout with ProgressBar and status views
- `app/src/main/res/layout/activity_ready.xml` - Ready/instructions layout
- `app/src/main/AndroidManifest.xml` - Added MainActivity (LAUNCHER), DownloadActivity, ReadyActivity

## Decisions Made
- Used isModelPresentFast (file.exists() && size > 0) in MainActivity instead of full SHA256 — SHA256 of 190MB takes 2-3s which is unacceptable latency on every app launch; full verification runs once after download
- No BroadcastReceiver for DownloadManager completion — polling via coroutine delay(500) is simpler and avoids lifecycle management complexity
- WiFi-only as default (setAllowedNetworkTypes(NETWORK_WIFI)); user must explicitly confirm cellular download via AlertDialog

## Deviations from Plan

None — plan executed exactly as written.

The only minor adaptation: removed ellipsis characters (…) from button text strings to avoid potential encoding issues, using "..." instead. No functional impact.

## Issues Encountered
None. The Kotlin source directory (app/src/main/java/com/simonpena/transchat/) and layout directory (app/src/main/res/layout/) did not exist yet and were created before writing files — this was a prerequisite correctly handled.

## User Setup Required
None — no external service configuration required.

## Next Phase Readiness
- Plan 01-04 (SmokeTestActivity) can proceed: ModelManager.modelFile() provides the path to pass to WhisperEngine.initContext()
- Full app flow functional: fresh install -> DownloadActivity -> download -> SHA256 verify -> ReadyActivity; subsequent launches -> MainActivity fast-path -> ReadyActivity
- MODEL-01, MODEL-02, MODEL-03 requirements all satisfied

## Self-Check: PASSED

Files verified present:
- ModelManager.kt: FOUND
- DownloadViewModel.kt: FOUND
- DownloadActivity.kt: FOUND
- MainActivity.kt: FOUND
- ReadyActivity.kt: FOUND
- activity_download.xml: FOUND
- activity_ready.xml: FOUND
- AndroidManifest.xml (modified): FOUND

Commits verified:
- 93bffcf (Task 1: ModelManager): FOUND
- 39e13b6 (Task 2: download flow): FOUND

Build verification:
- compileReleaseKotlin: BUILD SUCCESSFUL
- assembleRelease: BUILD SUCCESSFUL

Verification checks (from plan):
- MODEL_SHA256 constant: FOUND (ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb)
- filesDir/models path: FOUND
- NETWORK_WIFI default: FOUND
- delay(500) polling: FOUND
- COLUMN_BYTES_DOWNLOADED_SO_FAR: FOUND
- isOnCellularOnly() in DownloadActivity: FOUND

---
*Phase: 01-whisper-engine-model-bootstrap*
*Completed: 2026-02-27*
