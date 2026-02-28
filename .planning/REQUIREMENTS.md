# Requirements: TransChat

**Defined:** 2026-02-28
**Core Value:** Transcribe shared voice messages locally and privately — no cloud, no data leaks.

## v1.2 Requirements

Requirements for GitHub Readiness milestone. Each maps to roadmap phases.

### Open Source

- [x] **OSS-01**: Repository has MIT LICENSE file at root
- [x] **OSS-02**: README.md with project description, features, and privacy highlights
- [x] **OSS-03**: README.md includes build instructions (prerequisites, clone, build steps)
- [x] **OSS-04**: README.md includes 5 screenshots (model download, ready state, share target, progress bar, result)
- [x] **OSS-05**: README.md documents whisper.cpp version used

### CI / Signing

- [ ] **CI-01**: GitHub Actions workflow builds debug APK on push/PR
- [ ] **CI-02**: GitHub Actions workflow builds release APK on push/PR
- [ ] **CI-03**: GitHub Actions workflow runs lint
- [ ] **CI-04**: GitHub Actions uploads debug and release APKs as artifacts
- [ ] **CI-05**: Release builds use signing config from GitHub Actions secrets (not debug keystore)

### Dependencies

- [ ] **DEP-01**: whisper.cpp submodule upgraded from v1.7.4 to v1.8.3
- [ ] **DEP-02**: compileSdk and targetSdk upgraded from 34 to 35
- [ ] **DEP-03**: NDK upgraded from r26.1 to r27.2
- [ ] **DEP-04**: core-ktx upgraded from 1.13.1 to 1.15.0

## Future Requirements

Deferred to future release. Tracked but not in current roadmap.

### Distribution

- **DIST-01**: Fastlane metadata structure for F-Droid
- **DIST-02**: fdroiddata YAML recipe for F-Droid submission
- **DIST-03**: versionCode bump for F-Droid release

## Out of Scope

| Feature | Reason |
|---------|--------|
| F-Droid submission | Deferred — complete GitHub readiness first, submit next milestone |
| Google Play Store | Not a target — F-Droid is the distribution channel |
| NOTICES/ATTRIBUTION file | All deps are MIT/Apache 2.0, no copyleft — not legally required |
| Local release keystore | CI-only signing chosen — no local keystore file |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| OSS-01 | Phase 8 | Complete |
| OSS-02 | Phase 8 | Complete |
| OSS-03 | Phase 8 | Complete |
| OSS-04 | Phase 8 | Complete |
| OSS-05 | Phase 8 | Complete |
| CI-01 | Phase 9 | Pending |
| CI-02 | Phase 9 | Pending |
| CI-03 | Phase 9 | Pending |
| CI-04 | Phase 9 | Pending |
| CI-05 | Phase 9 | Pending |
| DEP-01 | Phase 10 | Pending |
| DEP-02 | Phase 10 | Pending |
| DEP-03 | Phase 10 | Pending |
| DEP-04 | Phase 10 | Pending |

**Coverage:**
- v1.2 requirements: 14 total
- Mapped to phases: 14
- Unmapped: 0

---
*Requirements defined: 2026-02-28*
*Last updated: 2026-02-28 after roadmap creation*
