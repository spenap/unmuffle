# Roadmap: TransChat

## Milestones

- **v1.0 MVP** — Phases 1-3 (shipped 2026-02-27)
- **v1.1 Polish & Distribution** — Phases 4-7 (shipped 2026-02-28)
- **v1.2 GitHub Readiness** — Phases 8-10 (in progress)

## Phases

<details>
<summary>v1.0 MVP (Phases 1-3) — SHIPPED 2026-02-27</summary>

- [x] Phase 1: Whisper Engine and Model Bootstrap (4/4 plans) — completed 2026-02-27
- [x] Phase 2: Share Intent and Audio Decoding (2/2 plans) — completed 2026-02-27
- [x] Phase 3: End-to-End Pipeline and Output (2/2 plans) — completed 2026-02-27

Full details: `milestones/v1.0-ROADMAP.md`

</details>

<details>
<summary>v1.1 Polish & Distribution (Phases 4-7) — SHIPPED 2026-02-28</summary>

- [x] Phase 4: R8 Release Configuration (1/1 plans) — completed 2026-02-27
- [x] Phase 5: Transcription Progress Bar (2/2 plans) — completed 2026-02-27
- [~] Phase 6: F-Droid Distribution Prep — deferred to next milestone
- [x] Phase 7: Branch Rename — completed manually 2026-02-28

Full details: `milestones/v1.1-ROADMAP.md`

</details>

### v1.2 GitHub Readiness (In Progress)

**Milestone Goal:** Make the repo open-source ready with LICENSE, README, GitHub Actions CI, and updated dependencies.

- [x] **Phase 8: Open Source Foundation** - MIT LICENSE and full README documenting the project — completed 2026-02-28
- [ ] **Phase 9: GitHub Actions CI** - Split CI: lint + debug on push/PR, full pipeline with release signing on tag push (v*)
- [ ] **Phase 10: Dependency Upgrades** - whisper.cpp submodule upgrade and SDK/NDK/library bumps

## Phase Details

### Phase 8: Open Source Foundation
**Goal**: The repository is legally and documentarily ready for public open-source release
**Depends on**: Nothing (first phase of milestone)
**Requirements**: OSS-01, OSS-02, OSS-03, OSS-04, OSS-05
**Success Criteria** (what must be TRUE):
  1. MIT LICENSE file exists at the repository root with correct copyright text
  2. README.md opens with a clear project description, feature list, and privacy highlights
  3. README.md contains step-by-step build instructions a developer can follow from a fresh clone
  4. README.md includes 5 screenshots covering model download, ready state, share target, progress bar, and result screens
  5. README.md states the whisper.cpp version in use
**Plans**: 2 plans
- [x] 08-01-PLAN.md — MIT LICENSE + comprehensive README (description, features, privacy, build instructions, tech stack) — completed 2026-02-28
- [x] 08-02-PLAN.md — Add 5 device screenshots to README (user-provided captures) — completed 2026-02-28

### Phase 9: GitHub Actions CI
**Goal**: Split CI strategy — every push/PR runs lint + debug APK (fast feedback); tag push (v*) runs full pipeline with release signing and artifact upload
**Depends on**: Phase 8
**Requirements**: CI-01, CI-02, CI-03, CI-04, CI-05
**Success Criteria** (what must be TRUE):
  1. A push/PR to main triggers a workflow that runs lint and builds a debug APK (fast feedback, no release build or artifacts)
  2. A tag push (v*) triggers the full pipeline: lint, debug APK, release APK signed with a keystore from GitHub Actions secrets, and artifact upload
  3. The workflow runs Android lint and the job fails if lint errors are present
  4. On tag push, both debug and release APKs are available as downloadable artifacts on the completed workflow run
**Plans**: 2 plans
- [ ] 09-01-PLAN.md — CI signing config + GitHub Actions workflow (build, lint, artifact upload)
- [ ] 09-02-PLAN.md — Configure GitHub secrets + verify CI pipeline end-to-end (checkpoint)

### Phase 10: Dependency Upgrades
**Goal**: The codebase builds successfully against current dependency versions with no regressions
**Depends on**: Phase 9
**Requirements**: DEP-01, DEP-02, DEP-03, DEP-04
**Success Criteria** (what must be TRUE):
  1. The whisper.cpp git submodule points to v1.8.3 and the JNI bridge compiles cleanly against it
  2. The app compiles and passes lint with compileSdk and targetSdk set to 35
  3. The NDK version in the build configuration is r27.2 and the native layer builds without warnings promoted to errors
  4. core-ktx 1.15.0 is declared in the dependency block and the app assembles without version conflict errors
**Plans**: TBD

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
| 8. Open Source Foundation | v1.2 | Complete    | 2026-02-28 | 2026-02-28 |
| 9. GitHub Actions CI | 1/2 | In Progress|  | - |
| 10. Dependency Upgrades | v1.2 | 0/TBD | Not started | - |
