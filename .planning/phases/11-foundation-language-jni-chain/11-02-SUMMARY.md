---
phase: 11-foundation-language-jni-chain
plan: "02"
subsystem: infra
tags: [jni, whisper, kotlin, cpp, language, android]

# Dependency graph
requires:
  - phase: 11-01
    provides: SettingsRepository.getLanguage(context) returning stored ISO language code
provides:
  - languageCode parameter threaded through entire JNI call chain (C++ whisper_jni.cpp -> WhisperLib -> WhisperContext -> WhisperEngine -> TranscriptionViewModel)
  - TranscriptionViewModel.startTranscription() reads language default from SettingsRepository
  - README accurately documents INTERNET and ACCESS_NETWORK_STATE permissions for model download
affects:
  - Phase 12 (package rename — JNI symbols encode package name, chain is now complete before rename)
  - Phase 13 (language dialog — will call startTranscription with explicit languageCode)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "JNI string lifetime: acquire with GetStringUTFChars BEFORE whisper_full(), release AFTER — params.language pointer must remain valid during inference"
    - "Default parameter cascade: each layer defaults to 'auto' for backward compatibility; top layer reads SettingsRepository"

key-files:
  created: []
  modified:
    - app/src/main/cpp/whisper_jni.cpp
    - app/src/main/java/com/simonpena/transchat/WhisperLib.kt
    - app/src/main/java/com/simonpena/transchat/WhisperEngine.kt
    - app/src/main/java/com/simonpena/transchat/TranscriptionViewModel.kt
    - README.md

key-decisions:
  - "ReleaseStringUTFChars called AFTER whisper_full() returns — the params.language char* must stay valid for the entire inference duration"
  - "languageCode: String = 'auto' default at WhisperContext and WhisperEngine layers keeps SmokeTestActivity and other existing callers working without changes"
  - "TranscriptionViewModel default reads SettingsRepository at call time (not class init) — ensures fresh preference each invocation"

patterns-established:
  - "JNI parameter ordering: add new params at end of existing signature to preserve backward ABI compatibility"
  - "Language default cascade: top of chain reads SettingsRepository; lower layers default to 'auto' for testability"

requirements-completed: [LANG-01, FIX-02]

# Metrics
duration: 10min
completed: 2026-03-01
---

# Phase 11 Plan 02: Foundation + Language JNI Chain Summary

**languageCode threaded from TranscriptionViewModel through WhisperEngine/WhisperContext/WhisperLib to whisper_jni.cpp params.language, with SettingsRepository wired as default; README permissions corrected**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-03-01T00:00:00Z
- **Completed:** 2026-03-01T00:10:00Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments

- JNI call chain now carries runtime language code from TranscriptionViewModel all the way to whisper.cpp params.language at inference time
- TranscriptionViewModel.startTranscription() reads language preference from SettingsRepository.getLanguage(getApplication()) as its default, satisfying LANG-01
- JNI string memory correctly managed: GetStringUTFChars before whisper_full(), ReleaseStringUTFChars after — prevents use-after-free
- All existing callers (SmokeTestActivity, TranscriptionActivity) continue to work via default parameter values without code changes
- README Features bullet and Privacy section both now accurately state INTERNET and ACCESS_NETWORK_STATE are used for one-time model download only (FIX-02)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add languageCode parameter to JNI chain** - `54c39e1` (feat)
2. **Task 2: Fix README permissions claim** - `4993bae` (fix)

## Files Created/Modified

- `app/src/main/cpp/whisper_jni.cpp` - Added jstring languageCode param; acquire/release around whisper_full(); replaced hardcoded "auto" with runtime lang
- `app/src/main/java/com/simonpena/transchat/WhisperLib.kt` - Added languageCode: String to fullTranscribe external fun; added languageCode: String = "auto" to transcribeData()
- `app/src/main/java/com/simonpena/transchat/WhisperEngine.kt` - Added languageCode: String = "auto" to transcribe(); logs lang=; passes through to transcribeData()
- `app/src/main/java/com/simonpena/transchat/TranscriptionViewModel.kt` - Added languageCode: String = SettingsRepository.getLanguage(getApplication()) to startTranscription(); passes to engine.transcribe()
- `README.md` - Fixed Features bullet and Privacy section to accurately state INTERNET/ACCESS_NETWORK_STATE for model download only

## Decisions Made

- JNI string release placed AFTER whisper_full() returns — params.language stores the raw char pointer during inference; releasing before would be a use-after-free
- Default parameter "auto" placed at WhisperContext and WhisperEngine layers so these can be unit-tested independently of SettingsRepository
- No import needed for SettingsRepository in TranscriptionViewModel — same package (com.simonpena.transchat)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None - all changes compiled cleanly on first attempt.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Phase 11 fully complete: SettingsRepository (11-01) and full JNI language chain (11-02) are both done
- Phase 12 (package rename TransChat -> Unmuffle) can proceed — JNI chain is complete, C++ symbols will only need to be renamed once
- Phase 13 (language dialog) can call viewModel.startTranscription(audioPath, selectedLanguageCode) with an explicit language after the user selects one

---
*Phase: 11-foundation-language-jni-chain*
*Completed: 2026-03-01*
