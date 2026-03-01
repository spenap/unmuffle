---
phase: 11-foundation-language-jni-chain
plan: 01
subsystem: infra
tags: [kotlin, shared-preferences, enum, android, model-management]

# Dependency graph
requires: []
provides:
  - WhisperModel enum (TINY/SMALL) with filename, url, sha256, sizeBytes, displayName
  - SettingsRepository singleton (SharedPreferences) with language, model, and remember-my-choice persistence
  - ModelManager model-aware overloads (modelFile, isModelPresentFast, isModelReady) accepting WhisperModel
affects:
  - 11-02-PLAN (JNI language chain — reads SettingsRepository.getLanguage)
  - 12 (model switching — uses WhisperModel enum, SettingsRepository.setModel)
  - 13 (language dialog — uses SettingsRepository.getLanguage/setLanguage/isLanguageRemembered)
  - 14 (settings screen — uses WhisperModel enum, SettingsRepository, ModelManager overloads)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "SettingsRepository object pattern: applicationContext.getSharedPreferences avoids activity leaks"
    - "WhisperModel enum: all model metadata (filename, url, sha256, sizeBytes, displayName) co-located in enum"
    - "ModelManager overload pattern: model-aware 2-arg method + backward-compat 1-arg delegating to SettingsRepository.getModel()"

key-files:
  created:
    - app/src/main/java/com/simonpena/transchat/WhisperModel.kt
    - app/src/main/java/com/simonpena/transchat/SettingsRepository.kt
  modified:
    - app/src/main/java/com/simonpena/transchat/ModelManager.kt

key-decisions:
  - "Keep const val string literals in ModelManager (not enum delegation) — const val requires compile-time constants; enum property access is not a compile-time constant in Kotlin. Remove in Phase 14."
  - "Use WhisperModel.entries.find (not deprecated .values()) for enum deserialization in SettingsRepository"
  - "SettingsRepository uses context.applicationContext.getSharedPreferences to prevent activity context leaks"
  - "isModelReady single-arg overload now delegates to SettingsRepository.getModel() rather than hardcoded MODEL_SHA256"

patterns-established:
  - "SettingsRepository: single object with private PREFS_NAME and KEY_* constants; all writes use .apply()"
  - "ModelManager: 2-arg model-aware overloads are the primary API; 1-arg overloads are backward-compat shims"

requirements-completed: [LANG-01]

# Metrics
duration: 1min
completed: 2026-03-01
---

# Phase 11 Plan 01: Foundation Types Summary

**WhisperModel enum + SettingsRepository SharedPreferences singleton + ModelManager model-aware overloads, enabling v1.3 model-switching and language-selection features**

## Performance

- **Duration:** 1 min
- **Started:** 2026-03-01T12:42:37Z
- **Completed:** 2026-03-01T12:43:57Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- WhisperModel enum with TINY and SMALL variants, each carrying filename, URL, SHA256, sizeBytes, and displayName metadata
- SettingsRepository object providing synchronous SharedPreferences access for language code, language-remember toggle, and model selection — safe to call from Activity.onCreate()
- ModelManager generalized with 2-arg model-aware overloads; existing no-arg callers continue to work via SettingsRepository.getModel() delegation

## Task Commits

Each task was committed atomically:

1. **Task 1: Create WhisperModel enum and SettingsRepository singleton** - `a15477b` (feat)
2. **Task 2: Generalize ModelManager to be WhisperModel-aware** - `f0bd786` (feat)

## Files Created/Modified
- `app/src/main/java/com/simonpena/transchat/WhisperModel.kt` - Enum with TINY/SMALL variants and all metadata fields
- `app/src/main/java/com/simonpena/transchat/SettingsRepository.kt` - SharedPreferences singleton with 6 typed getter/setter methods
- `app/src/main/java/com/simonpena/transchat/ModelManager.kt` - Added model-aware overloads; existing const val constants retained for Phase 14 removal

## Decisions Made
- `const val` literals in ModelManager kept as string literals (not delegated to enum properties) because Kotlin `const val` requires compile-time constants and enum property access does not qualify. Callers in DownloadViewModel continue to compile unchanged. These constants will be removed in Phase 14.
- Used `WhisperModel.entries.find` (Kotlin 1.9+, non-deprecated) instead of `WhisperModel.values().find` for enum deserialization in SettingsRepository.
- `isModelReady(context)` (no-arg) now delegates to `SettingsRepository.getModel(context)` rather than the hardcoded `MODEL_SHA256` constant, so the method correctly validates the active model.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- WhisperModel and SettingsRepository are ready for consumption by plan 11-02 (JNI language chain threading)
- ModelManager.modelFile(context, model) and isModelPresentFast(context, model) are ready for Phase 12 (model switching)
- SettingsRepository.getLanguage/setLanguage/isLanguageRemembered are ready for Phase 13 (language dialog)
- No blockers.

---
*Phase: 11-foundation-language-jni-chain*
*Completed: 2026-03-01*
