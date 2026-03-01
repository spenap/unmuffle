# Roadmap: TransChat / Unmuffle

## Milestones

- ✅ **v1.0 MVP** — Phases 1-3 (shipped 2026-02-27)
- ✅ **v1.1 Polish & Distribution** — Phases 4-7 (shipped 2026-02-28)
- ✅ **v1.2 GitHub Readiness** — Phases 8-10 (shipped 2026-02-28)
- 🚧 **v1.3 Unmuffle** — Phases 11-14 (in progress)

## Phases

<details>
<summary>✅ v1.0 MVP (Phases 1-3) — SHIPPED 2026-02-27</summary>

- [x] Phase 1: Whisper Engine and Model Bootstrap (4/4 plans) — completed 2026-02-27
- [x] Phase 2: Share Intent and Audio Decoding (2/2 plans) — completed 2026-02-27
- [x] Phase 3: End-to-End Pipeline and Output (2/2 plans) — completed 2026-02-27

Full details: `milestones/v1.0-ROADMAP.md`

</details>

<details>
<summary>✅ v1.1 Polish & Distribution (Phases 4-7) — SHIPPED 2026-02-28</summary>

- [x] Phase 4: R8 Release Configuration (1/1 plans) — completed 2026-02-27
- [x] Phase 5: Transcription Progress Bar (2/2 plans) — completed 2026-02-27
- [~] Phase 6: F-Droid Distribution Prep — deferred to next milestone
- [x] Phase 7: Branch Rename — completed manually 2026-02-28

Full details: `milestones/v1.1-ROADMAP.md`

</details>

<details>
<summary>✅ v1.2 GitHub Readiness (Phases 8-10) — SHIPPED 2026-02-28</summary>

- [x] Phase 8: Open Source Foundation (2/2 plans) — completed 2026-02-28
- [x] Phase 9: GitHub Actions CI (2/2 plans) — completed 2026-02-28
- [x] Phase 10: Dependency Upgrades (2/2 plans) — completed 2026-02-28

Full details: `milestones/v1.2-ROADMAP.md`

</details>

### 🚧 v1.3 Unmuffle (In Progress)

**Milestone Goal:** Ship the app as "Unmuffle" — a named, polished product with language selection, configurable settings, and a Material 3 UI.

- [x] **Phase 11: Foundation + Language JNI Chain** — SettingsRepository persistence singleton, WhisperModel enum, language parameter threaded through JNI, README permissions fix (completed 2026-03-01)
- [ ] **Phase 12: App Rename + Material 3 + Layout Fix** — TransChat renamed to Unmuffle across all code and config, M3 theme applied, layout regression fixed, dynamic color enabled
- [ ] **Phase 13: Language Selection Dialog** — Language picker dialog before inference, remember-my-choice checkbox persisted and respected on subsequent shares
- [ ] **Phase 14: Settings Activity** — Settings screen accessible from the app, language default picker, model selector (tiny/small), model download trigger

## Phase Details

### Phase 11: Foundation + Language JNI Chain
**Goal**: Users' language choice reaches whisper.cpp at inference time; README accurately describes network permissions
**Depends on**: Nothing (first phase of milestone)
**Requirements**: LANG-01, FIX-02
**Success Criteria** (what must be TRUE):
  1. A hardcoded language code passed through the JNI call chain produces a transcript in that language (verifiable by changing the code and running inference)
  2. `SettingsRepository` reads and writes language preference synchronously via SharedPreferences without DataStore instantiation errors
  3. `WhisperModel` enum defines TINY and SMALL variants with filename, download URL, SHA256, size, and display name
  4. README states that INTERNET and ACCESS_NETWORK_STATE are used for one-time model download, not for transcription
**Plans:** 2/2 plans complete

Plans:
- [ ] 11-01: SettingsRepository, WhisperModel enum, ModelManager generalization
- [ ] 11-02: Language parameter through JNI call chain (C++ → WhisperLib → WhisperContext → WhisperEngine → ViewModel → Activity) + README fix

### Phase 12: App Rename + Material 3 + Layout Fix
**Goal**: The app is named Unmuffle everywhere and uses Material 3 styling with no visual regressions
**Depends on**: Phase 11 (JNI function names finalized before package rename)
**Requirements**: REBRAND-01, REBRAND-02, REBRAND-03, REBRAND-04, FIX-01, UI-01, UI-02, UI-03
**Success Criteria** (what must be TRUE):
  1. The app appears as "Unmuffle" in the Android launcher, share sheet, and app bar
  2. The package is com.simonpena.unmuffle in both applicationId and Kotlin namespace; all 7 JNI function signatures in whisper_jni.cpp match the new package
  3. README, GitHub Actions workflow, and all documentation refer to "Unmuffle" with no remaining "TransChat" references
  4. REBRAND-04: GitHub repository is renamed from transchat-app to unmuffle and the local remote URL is updated (manual step guided by phase)
  5. The transcript result screen shows title and transcript text without overlap; copy and share buttons are fully visible and tappable
  6. All screens use Theme.Material3.DayNight.NoActionBar; all textAppearance attributes use M3 equivalents; Dynamic color applies on Android 12+
**Plans**: 2 plans

Plans:
- [ ] 12-01: Atomic package rename — Kotlin namespace, applicationId, 7 JNI signatures, ProGuard rules, manifest labels, theme names, settings.gradle.kts
- [ ] 12-02: M3 theme migration + dynamic color + layout fix + README rename + REBRAND-04 guide

### Phase 13: Language Selection Dialog
**Goal**: Users choose their transcription language before inference starts and can opt to skip the dialog on future shares
**Depends on**: Phase 11 (JNI language chain working), Phase 12 (correct M3 theme for dialog styling)
**Requirements**: LANG-02, LANG-03
**Success Criteria** (what must be TRUE):
  1. A language picker dialog appears in TranscriptionActivity before the progress bar starts; audio copy in ShareReceiverActivity completes before any dialog is shown
  2. User can check "Remember my choice" and the dialog does not appear on the next share — the previously chosen language is used directly
  3. The remembered language preference is correctly read and applied on every subsequent share, with no reset between app restarts
**Plans**: TBD

Plans:
- [ ] 13-01: Language selection dialog wired end-to-end (MaterialAlertDialogBuilder, remember-my-choice checkbox, SettingsRepository read/write, fast path when preference already set)

### Phase 14: Settings Activity
**Goal**: Users can change their language default and select a Whisper model from a dedicated settings screen
**Depends on**: Phase 11 (SettingsRepository, WhisperModel enum), Phase 12 (app named and themed), Phase 13 (language preference proven in dialog)
**Requirements**: SET-01, SET-02, SET-03, SET-04
**Success Criteria** (what must be TRUE):
  1. A settings icon in the app's main screen opens a settings screen (SettingsActivity)
  2. User can change the language default between "Always ask" and any supported language; the next share reflects the new setting
  3. User can select the Whisper model (tiny ~32MB or small ~190MB) from the settings screen
  4. Selecting a model that is not present on device triggers the download flow; selecting an already-downloaded model takes effect immediately
**Plans**: TBD

Plans:
- [ ] 14-01: SettingsActivity (AppCompatActivity, XML layout, language picker row, model selector row, ReadyActivity gear icon)
- [ ] 14-02: Model download trigger from settings + model-switching guard while transcription is active

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Whisper Engine and Model Bootstrap | v1.0 | 4/4 | Complete | 2026-02-27 |
| 2. Share Intent and Audio Decoding | v1.0 | 2/2 | Complete | 2026-02-27 |
| 3. End-to-End Pipeline and Output | v1.0 | 2/2 | Complete | 2026-02-27 |
| 4. R8 Release Configuration | v1.1 | 1/1 | Complete | 2026-02-27 |
| 5. Transcription Progress Bar | v1.1 | 2/2 | Complete | 2026-02-27 |
| 6. F-Droid Distribution Prep | v1.1 | — | Deferred | — |
| 7. Branch Rename | v1.1 | — | Manual | 2026-02-28 |
| 8. Open Source Foundation | v1.2 | 2/2 | Complete | 2026-02-28 |
| 9. GitHub Actions CI | v1.2 | 2/2 | Complete | 2026-02-28 |
| 10. Dependency Upgrades | v1.2 | 2/2 | Complete | 2026-02-28 |
| 11. Foundation + Language JNI Chain | 2/2 | Complete    | 2026-03-01 | - |
| 12. App Rename + Material 3 + Layout Fix | v1.3 | 0/2 | Not started | - |
| 13. Language Selection Dialog | v1.3 | 0/1 | Not started | - |
| 14. Settings Activity | v1.3 | 0/2 | Not started | - |
