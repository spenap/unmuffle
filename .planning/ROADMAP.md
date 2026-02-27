# Roadmap: TransChat

## Milestones

- **v1.0 MVP** — Phases 1-3 (shipped 2026-02-27)
- **v1.1 Polish & Distribution** — Phases 4-7 (in progress)

## Phases

<details>
<summary>v1.0 MVP (Phases 1-3) — SHIPPED 2026-02-27</summary>

- [x] Phase 1: Whisper Engine and Model Bootstrap (4/4 plans) — completed 2026-02-27
- [x] Phase 2: Share Intent and Audio Decoding (2/2 plans) — completed 2026-02-27
- [x] Phase 3: End-to-End Pipeline and Output (2/2 plans) — completed 2026-02-27

Full details: `milestones/v1.0-ROADMAP.md`

</details>

### v1.1 Polish & Distribution

- [ ] **Phase 4: R8 Release Configuration** - Replace blanket ProGuard keep with surgical R8 rules and verify release build
- [ ] **Phase 5: Transcription Progress Bar** - Wire whisper.cpp progress callback through JNI to a determinate progress indicator
- [ ] **Phase 6: F-Droid Distribution Prep** - Add license, fastlane metadata, build recipe, and version bump for F-Droid submission
- [ ] **Phase 7: Branch Rename** - Rename default branch from master to main with reference audit

## Phase Details

### Phase 4: R8 Release Configuration
**Goal**: Release builds use meaningful R8 shrinking with targeted keep rules instead of the blanket keep that defeats all optimization
**Depends on**: Phase 3 (v1.0 complete)
**Requirements**: REL-01, REL-02
**Success Criteria** (what must be TRUE):
  1. The blanket `-keep class com.simonpena.transchat.** { *; }` rule is removed from proguard-rules.pro
  2. Release APK built with `minifyEnabled true` launches, transcribes a voice message, and displays the result correctly on a physical device
  3. JNI native methods, ViewModels, and UiState sealed hierarchy survive R8 shrinking (no `NoSuchMethodError` or `ClassNotFoundException` at runtime)
  4. A placeholder keep rule for the future `ProgressCallback` interface is present (commented) so Phase 5 can uncomment it
**Plans**: TBD

### Phase 5: Transcription Progress Bar
**Goal**: Users see a real 0-100% progress bar during Whisper inference instead of an indeterminate spinner
**Depends on**: Phase 4 (R8 rules must be in place before adding ProgressCallback interface)
**Requirements**: PROG-01, PROG-02
**Success Criteria** (what must be TRUE):
  1. During transcription, a determinate progress bar advances from 0% toward 100% driven by real whisper.cpp callback values (not a timer-based simulation)
  2. The progress bar completes and the transcript result screen appears after inference finishes
  3. The progress callback works correctly in both debug and release builds (R8 does not strip the callback interface)
**Plans**: TBD

### Phase 6: F-Droid Distribution Prep
**Goal**: Repository contains all metadata and configuration required for F-Droid submission
**Depends on**: Phase 4 (release build must be verified before distribution prep)
**Requirements**: DIST-01, DIST-02, DIST-03, DIST-04
**Success Criteria** (what must be TRUE):
  1. An MIT LICENSE file exists at the repository root
  2. The fastlane metadata directory contains correctly-named F-Droid files: `summary.txt`, `description.txt`, and `changelogs/2.txt`
  3. A draft fdroiddata YAML build recipe specifies `submodules: true`, the exact NDK version, and produces a valid configuration
  4. `versionCode` is 2 and `versionName` is "1.1" in build.gradle.kts
  5. A clean checkout followed by `./gradlew assembleRelease` produces a complete APK with no prebuilt .so files
**Plans**: TBD

### Phase 7: Branch Rename
**Goal**: Default branch is renamed from master to main with all references updated
**Depends on**: Phase 6 (all feature work merged before rename)
**Requirements**: REPO-01
**Success Criteria** (what must be TRUE):
  1. The default branch on GitHub is `main` (not `master`)
  2. No references to `master` remain in repository configuration files, CI workflows, or documentation
**Plans**: TBD

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Whisper Engine and Model Bootstrap | v1.0 | 4/4 | Complete | 2026-02-27 |
| 2. Share Intent and Audio Decoding | v1.0 | 2/2 | Complete | 2026-02-27 |
| 3. End-to-End Pipeline and Output | v1.0 | 2/2 | Complete | 2026-02-27 |
| 4. R8 Release Configuration | v1.1 | 0/? | Not started | - |
| 5. Transcription Progress Bar | v1.1 | 0/? | Not started | - |
| 6. F-Droid Distribution Prep | v1.1 | 0/? | Not started | - |
| 7. Branch Rename | v1.1 | 0/? | Not started | - |
