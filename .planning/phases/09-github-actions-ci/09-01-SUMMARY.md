---
phase: 09-github-actions-ci
plan: 01
subsystem: infra
tags: [github-actions, ci, android, gradle, signing, ndk, cmake]

# Dependency graph
requires:
  - phase: 08-open-source-foundation
    provides: public GitHub repository with LICENSE, README, and project structure
provides:
  - GitHub Actions CI workflow with split push/PR vs tag-push strategy
  - Release signing config in build.gradle.kts using environment variables
  - Full pipeline for tagged releases including signed APK artifact upload
affects: [10-dependency-upgrades]

# Tech tracking
tech-stack:
  added: [github-actions, actions/checkout@v4, actions/setup-java@v4, android-actions/setup-android@v3, actions/cache@v4, actions/upload-artifact@v4]
  patterns: [split-ci-strategy, env-var-signing, base64-keystore-secret]

key-files:
  created:
    - .github/workflows/build.yml
  modified:
    - app/build.gradle.kts

key-decisions:
  - "Single workflow file with if: startsWith(github.ref, 'refs/tags/') conditions rather than two separate workflow files"
  - "Tag-conditioned signing: condition is based on tag push, not secrets presence (cleaner and more intentional)"
  - "SIGNING_KEYSTORE_PATH env var as the presence check to toggle CI vs local dev signing in build.gradle.kts"
  - "Local dev builds fall back to debug keystore when SIGNING_KEYSTORE_PATH is not set — no developer regression"
  - "lintDebug runs on every trigger (push, PR, and tag) — fast feedback on lint issues"

patterns-established:
  - "CI split: lint+debug on push/PR (fast), full pipeline+release signing on tag (v*)"
  - "Keystore stored as base64-encoded GitHub secret, decoded at runtime to temp file, never committed"
  - "SIGNING_* env vars written to $GITHUB_ENV by signing step, read by build.gradle.kts via System.getenv()"

requirements-completed: [CI-01, CI-02, CI-03, CI-04, CI-05]

# Metrics
duration: 1min
completed: 2026-02-28
---

# Phase 9 Plan 01: GitHub Actions CI Workflow Summary

**Single GitHub Actions workflow with split strategy: lint+debug APK on push/PR for fast feedback, full signed release pipeline with artifact upload only on tag push (v*)**

## Performance

- **Duration:** ~1 min
- **Started:** 2026-02-28T19:27:49Z
- **Completed:** 2026-02-28T19:28:49Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Created `.github/workflows/build.yml` with push/PR/tag triggers and split CI strategy via `if:` conditions
- Added release `signingConfig` to `app/build.gradle.kts` reading from four SIGNING_* environment variables
- Release build type uses CI signing when env vars present, falls back to debug keystore for local dev
- Tag-only steps: release signing, assembleRelease, and artifact upload for both debug and release APKs
- No keystore file committed to repository — keystore decoded from base64 GitHub secret at runtime

## Task Commits

Each task was committed atomically:

1. **Task 1: Add CI release signing config to build.gradle.kts** - `fc6da4d` (feat)
2. **Task 2: Create GitHub Actions workflow with split CI strategy** - `5c598c2` (feat)

## Files Created/Modified
- `.github/workflows/build.yml` - Single CI workflow file with push/PR/tag triggers and conditional tag-only steps
- `app/build.gradle.kts` - Added release signingConfig reading from SIGNING_* environment variables

## Decisions Made
- Single workflow file with `if: startsWith(github.ref, 'refs/tags/')` conditions rather than two separate files — simpler maintenance
- Tag-conditioned signing uses tag presence as condition (not secrets presence check) — more intentional and correct semantics
- `SIGNING_KEYSTORE_PATH` used as the presence check in build.gradle.kts to toggle between CI and local dev signing
- `lintDebug` variant chosen for lint step — works without release signing and catches all lint issues

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None.

## User Setup Required

GitHub Actions secrets must be configured before tagged release builds will succeed:

- `KEYSTORE_BASE64` — base64-encoded `.jks` keystore file (`base64 -w 0 release.keystore`)
- `KEYSTORE_PASSWORD` — keystore store password
- `KEY_ALIAS` — key alias within keystore
- `KEY_PASSWORD` — key password

These secrets are expected to already exist in the repository settings. Tagged releases (`git tag v1.2.0 && git push origin v1.2.0`) will trigger the full pipeline.

## Next Phase Readiness
- CI infrastructure complete for Phase 9
- Ready for Phase 10 (dependency upgrades) — CI will validate NDK/SDK upgrade changes automatically
- Tagged release builds will produce signed APKs as downloadable artifacts

---
*Phase: 09-github-actions-ci*
*Completed: 2026-02-28*
