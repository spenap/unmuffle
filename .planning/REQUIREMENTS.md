# Requirements: Unmuffle

**Defined:** 2026-03-01
**Core Value:** Transcribe shared voice messages locally and privately — no cloud, no data leaks.

## v1.3 Requirements

Requirements for the Unmuffle milestone. Each maps to roadmap phases.

### Fixes

- [x] **FIX-01**: Layout regression fixed — transcript text not overlapped by title, copy/share buttons fully visible
- [x] **FIX-02**: README accurately describes INTERNET + ACCESS_NETWORK_STATE permissions used for model download

### Rebrand

- [x] **REBRAND-01**: App name displayed as "Unmuffle" everywhere (launcher, share sheet, app bar)
- [x] **REBRAND-02**: Package renamed com.simonpena.transchat → com.simonpena.unmuffle (namespace, applicationId, JNI C++ symbols)
- [x] **REBRAND-03**: README, CI workflow, and all documentation reference "Unmuffle"
- [x] **REBRAND-04**: GitHub repository renamed from transchat-app to unmuffle (remote URL updated in local config)

### Language

- [x] **LANG-01**: User can choose transcription language before inference starts
- [ ] **LANG-02**: User can select "Remember my choice" to skip the language dialog on future shares
- [ ] **LANG-03**: Language preference persisted and respected on subsequent shares

### Settings

- [ ] **SET-01**: Settings page accessible from the app's main screen
- [ ] **SET-02**: User can change default language (always prompt, or a specific language)
- [ ] **SET-03**: User can select Whisper model (tiny ~32MB, base ~57MB, small ~190MB)
- [ ] **SET-04**: Model change triggers download if selected model not already present on device

### UI Polish

- [x] **UI-01**: Material 3 theme applied (Theme.Material3.DayNight)
- [x] **UI-02**: All layouts updated to M3 component styles and attributes
- [x] **UI-03**: Dynamic color support (Material You on Android 12+)

## Future Requirements

Deferred to v1.4 or later. Tracked but not in current roadmap.

### Quality

- **TEST-01**: Unit tests for core transcription pipeline
- **TEST-02**: CI runs test suite on push/PR
- **TEST-03**: Instrumented UI tests for critical flows

### Presence

- **WEB-01**: GitHub Pages site for project
- **DIST-01**: F-Droid submission (Fastlane metadata, fdroiddata recipe)

## Out of Scope

| Feature | Reason |
|---------|--------|
| Transcript history / saved transcripts | Violates ephemeral privacy model |
| Cloud-based transcription | Defeats the privacy purpose |
| Real-time/streaming transcription | Batch processing of shared files only |
| Medium/large Whisper models | Impractical on mobile (490MB+, 60s+ inference) |
| iOS version | Android only |
| Compose migration | App uses XML Views throughout; Compose adds 4MB APK overhead with no benefit |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| FIX-01 | Phase 12 | Complete |
| FIX-02 | Phase 11 | Complete |
| REBRAND-01 | Phase 12 | Complete |
| REBRAND-02 | Phase 12 | Complete |
| REBRAND-03 | Phase 12 | Complete |
| REBRAND-04 | Phase 12 | Complete |
| LANG-01 | Phase 11 | Complete |
| LANG-02 | Phase 13 | Pending |
| LANG-03 | Phase 13 | Pending |
| SET-01 | Phase 14 | Pending |
| SET-02 | Phase 14 | Pending |
| SET-03 | Phase 14 | Pending |
| SET-04 | Phase 14 | Pending |
| UI-01 | Phase 12 | Complete |
| UI-02 | Phase 12 | Complete |
| UI-03 | Phase 12 | Complete |

**Coverage:**
- v1.3 requirements: 16 total
- Mapped to phases: 16
- Unmapped: 0 ✓

---
*Requirements defined: 2026-03-01*
*Last updated: 2026-03-01 — traceability updated after roadmap creation*
