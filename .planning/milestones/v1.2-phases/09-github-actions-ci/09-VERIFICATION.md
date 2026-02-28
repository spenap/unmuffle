---
phase: 09-github-actions-ci
verified: 2026-02-28T20:30:00Z
status: gaps_found
score: 11/13 must-haves verified
re_verification: false
gaps:
  - truth: "No keystore file is committed to the repository"
    status: partial
    reason: "release.keystore file exists in the working directory but is not in .gitignore — untracked but at risk of accidental future commit"
    artifacts:
      - path: "release.keystore"
        issue: "File exists on disk, not tracked by git, but also not excluded by .gitignore — any `git add .` will stage it"
      - path: ".gitignore"
        issue: "Does not contain *.jks, *.keystore, or release.keystore exclusion patterns"
    missing:
      - "Add *.keystore and *.jks patterns to .gitignore to prevent accidental future commits"
human_verification:
  - test: "Verify GitHub Actions secrets are configured"
    expected: "Four secrets exist in repo settings: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD"
    why_human: "GitHub secrets are not readable via API (by design) — can only be listed, not read. gh CLI access denied in this environment."
  - test: "Verify push to main triggers fast-path CI (lint + debug only, no release steps)"
    expected: "Workflow run shows: lint PASS, debug build PASS, release signing SKIP, release build SKIP, no artifacts uploaded"
    why_human: "Requires live GitHub Actions run — cannot verify workflow execution behavior from local codebase inspection."
  - test: "Verify tag push (v*) triggers full pipeline with signed artifacts"
    expected: "Workflow run shows: lint PASS, debug build PASS, release signing RAN, release build PASS, both app-debug and app-release artifacts downloadable"
    why_human: "Requires live GitHub Actions run and APK signature inspection."
  - test: "Verify release APK is signed with release keystore (not debug keystore)"
    expected: "apksigner verify --print-certs shows release certificate CN, not Android debug certificate"
    why_human: "Requires downloading and inspecting the artifact from a completed tag-triggered workflow run."
---

# Phase 9: GitHub Actions CI Verification Report

**Phase Goal:** Split CI strategy — every push/PR runs lint + debug APK (fast feedback); tag push (v*) runs full pipeline with release signing and artifact upload
**Verified:** 2026-02-28T20:30:00Z
**Status:** gaps_found
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| #  | Truth | Status | Evidence |
|----|-------|--------|----------|
| 1  | Workflow file exists, triggered on push, pull_request, and tags (v*) | VERIFIED | `.github/workflows/build.yml` line 3-8: `on.push.branches[main]`, `on.push.tags['v*']`, `on.pull_request.branches[main]` |
| 2  | Checkout with recursive submodules | VERIFIED | Line 16-18: `uses: actions/checkout@v4` with `submodules: recursive` |
| 3  | JDK 17, Android SDK, NDK 26.1.10909125, CMake 3.22.1 configured | VERIFIED | Lines 21-33: setup-java@v4 (java-version 17), android-actions/setup-android@v3, sdkmanager installs ndk;26.1.10909125 and cmake;3.22.1 |
| 4  | Push/PR: lint + assembleDebug only (no release steps) | VERIFIED | Lines 51-55: lintDebug and assembleDebug run unconditionally; all release steps have `if: startsWith(github.ref, 'refs/tags/')` guards |
| 5  | Tag push (v*): full pipeline — lint, debug, release signing, release build, artifact upload | VERIFIED | Lines 61, 76, 81, 89: four steps gated on `startsWith(github.ref, 'refs/tags/')`: signing, assembleRelease, upload debug APK, upload release APK |
| 6  | Release signing reads keystore from env vars decoded from GitHub secrets | VERIFIED | `build.gradle.kts` lines 41-48: `System.getenv("SIGNING_KEYSTORE_PATH")`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`; workflow lines 69-72 write these to `$GITHUB_ENV` |
| 7  | Release build type falls back to debug signing locally | VERIFIED | `build.gradle.kts` lines 58-62: `if (System.getenv("SIGNING_KEYSTORE_PATH") != null)` else `signingConfigs.getByName("debug")` |
| 8  | Workflow runs lintDebug and fails job if lint errors present | VERIFIED | Line 51: `./gradlew lintDebug` — no `continue-on-error`, job fails on non-zero exit |
| 9  | Gradlew wrapper is executable and invoked by workflow | VERIFIED | gradlew exists at repo root (executable), workflow lines 47-55 invoke `./gradlew` |
| 10 | build.gradle.kts externalNativeBuild wires to CMakeLists.txt | VERIFIED | `build.gradle.kts` line 31: `path = file("src/main/cpp/CMakeLists.txt")`; file exists at that path |
| 11 | No keystore file is committed to git history | VERIFIED | `git ls-files release.keystore` returns empty; `git log --all -- release.keystore` returns no commits |
| 12 | release.keystore not at risk of accidental commit (.gitignore) | FAILED | `release.keystore` exists on disk (untracked `??`), but `.gitignore` has no `*.keystore`, `*.jks`, or `release.keystore` exclusion — accidental `git add .` would commit it |
| 13 | GitHub secrets configured and live CI verified end-to-end | HUMAN NEEDED | SUMMARY.md claims both verified by human (Task 1 and Task 2 of Plan 09-02); cannot verify remotely |

**Score:** 11/13 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `.github/workflows/build.yml` | GitHub Actions CI workflow | VERIFIED | 94 lines, non-stub, all required patterns present |
| `app/build.gradle.kts` | Updated signing config with CI env var support | VERIFIED | 87 lines, `create("release")` block with `System.getenv` calls for all 4 SIGNING_* vars |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `.github/workflows/build.yml` | `app/build.gradle.kts` | Writes SIGNING_* env vars to $GITHUB_ENV; build.gradle.kts reads via System.getenv | VERIFIED | Workflow lines 69-72 write 4 SIGNING_* vars to GITHUB_ENV; build.gradle.kts lines 41-48 read the same 4 var names |
| `.github/workflows/build.yml` | `gradlew` | Workflow invokes Gradle wrapper for build tasks | VERIFIED | Lines 51, 55, 77: `./gradlew lintDebug`, `./gradlew assembleDebug`, `./gradlew assembleRelease`; gradlew is executable |
| `app/build.gradle.kts` | `app/src/main/cpp/CMakeLists.txt` | externalNativeBuild triggers CMake compilation of whisper.cpp JNI layer | VERIFIED | Line 31: `path = file("src/main/cpp/CMakeLists.txt")`; CMakeLists.txt exists (2089 bytes) |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| CI-01 | 09-01 | GitHub Actions workflow builds debug APK on push/PR to main | SATISFIED | `./gradlew assembleDebug` runs unconditionally in workflow (no if: condition) |
| CI-02 | 09-01 | GitHub Actions workflow builds release APK on tag push (v*) | SATISFIED | `./gradlew assembleRelease` at line 77 with `if: startsWith(github.ref, 'refs/tags/')` |
| CI-03 | 09-01 | GitHub Actions workflow runs lint on push/PR to main and tag push | SATISFIED | `./gradlew lintDebug` at line 51 runs unconditionally on all triggers |
| CI-04 | 09-01, 09-02 | GitHub Actions uploads debug and release APKs as artifacts on tag push (v*) | SATISFIED | Lines 80-93: two `upload-artifact@v4` steps both conditioned on `startsWith(github.ref, 'refs/tags/')` |
| CI-05 | 09-01, 09-02 | Release builds use signing config from GitHub Actions secrets on tag push (v*) | SATISFIED (code) / HUMAN for live | Code: SIGNING_* env chain verified. Live run: claimed verified per SUMMARY.md, cannot re-verify programmatically |

No orphaned requirements — all 5 CI-* requirements (CI-01 through CI-05) appear in Plan 09-01 frontmatter and all map to Phase 9 in REQUIREMENTS.md traceability table.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `.gitignore` | (missing) | No `*.keystore` or `*.jks` exclusion | WARNING | `release.keystore` exists untracked in working directory; a `git add .` or `git add release.keystore` would commit the private signing key to the repository |

No TODO/FIXME/placeholder comments found. No stub implementations detected in either file.

### Human Verification Required

#### 1. GitHub Secrets Configured

**Test:** Go to GitHub repo > Settings > Secrets and variables > Actions. Verify all four secrets exist: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
**Expected:** All four secrets listed (values hidden by GitHub, existence is what matters)
**Why human:** GitHub secrets API does not expose values; secret existence confirmed via SUMMARY.md but cannot be re-verified programmatically in this environment.

#### 2. Push-to-Main Fast Path Verified on Live GitHub Actions

**Test:** Check GitHub Actions tab for the most recent push-to-main workflow run. Confirm: lint passed, debug build passed, "Configure release signing" step SKIPPED, "Build release APK" step SKIPPED, "Upload debug APK" SKIPPED, "Upload release APK" SKIPPED. No artifacts section visible.
**Expected:** Workflow run completes with exactly 7 steps run and 4 steps skipped.
**Why human:** Requires inspecting a live GitHub Actions run — not verifiable from local codebase.

#### 3. Tag Push Full Pipeline Verified on Live GitHub Actions

**Test:** Check GitHub Actions tab for the tag v1.2.0-rc1 workflow run (or equivalent). Confirm: all steps ran (not skipped), both `app-debug` and `app-release` artifacts are downloadable.
**Expected:** Workflow completed successfully with both APK artifacts present.
**Why human:** Requires inspecting a live GitHub Actions run.

#### 4. Release APK Signed with Release Keystore

**Test:** Download the `app-release` artifact from a tag-triggered run and run: `apksigner verify --print-certs app-release.apk`. The signer CN should match the release keystore subject (e.g., `CN=Simon Pena, O=TransChat`), not the Android debug certificate.
**Expected:** Certificate shows release CN, not `CN=Android Debug, O=Android, C=US`.
**Why human:** Requires APK download and signature inspection tool.

### Gaps Summary

**One gap requires a code fix before the phase can be considered fully complete:**

The `.gitignore` file does not exclude `*.keystore` or `*.jks` files. A `release.keystore` file currently exists untracked in the repository root. While it is not currently committed (verified via `git ls-files` and `git log`), the absence of `.gitignore` protection means any future `git add .` would include the private signing keystore in the git history — a security issue that cannot be reversed without rewriting git history.

**Fix required:** Add `*.keystore` and `*.jks` to `.gitignore`.

Three additional items require human confirmation of live GitHub Actions behavior. Per the Phase 09-02 SUMMARY.md, a human approved both the fast-path push and the full tag pipeline as passing on 2026-02-28. These items are flagged for human verification rather than as failures, since the code structure fully supports the described behavior.

---

_Verified: 2026-02-28T20:30:00Z_
_Verifier: Claude (gsd-verifier)_
