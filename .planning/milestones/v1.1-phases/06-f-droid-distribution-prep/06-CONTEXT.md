# Phase 6: F-Droid Distribution Prep - Context

**Gathered:** 2026-02-28
**Status:** Ready for planning

<domain>
## Phase Boundary

Add all metadata and configuration required for F-Droid submission: MIT license, fastlane metadata directory, draft fdroiddata YAML build recipe, and version bump. No app functionality changes — this is purely distribution prep.

</domain>

<decisions>
## Implementation Decisions

### App description & metadata
- Minimal, technical tone — factual, no marketing language
- Emphasize both zero network permissions (verifiable in manifest) and on-device processing equally
- Keep messenger app references generic ("messaging apps", "any app that shares audio") — no WhatsApp/Telegram by name
- summary.txt: "Private, offline voice message transcription"
- description.txt: technical, concise, privacy-focused

### Build recipe
- Stay on NDK r26 (26.1.10909125) — current builds work, no need to introduce risk
- Target arm64-v8a only — covers 95%+ of devices, keeps F-Droid build times reasonable
- No anti-features to declare (no tracking, ads, non-free deps, no network access)
- `submodules: true` required (whisper.cpp is a git submodule)

### License
- MIT license as specified in roadmap requirements (DIST-01)
- Consistent with whisper.cpp submodule (also MIT)

### Version bump
- versionCode: 1 → 2
- versionName: "1.0" → "1.1"
- As specified in success criteria (DIST-04)

### Claude's Discretion
- Draft YAML file location in repo (fdroid/ directory or repo root)
- Exact description.txt wording (within tone/content guidelines above)
- Changelog format for changelogs/2.txt
- Fastlane directory structure details

</decisions>

<specifics>
## Specific Ideas

No specific requirements — open to standard F-Droid approaches within the decisions captured above.

</specifics>

<code_context>
## Existing Code Insights

### Reusable Assets
- `app/build.gradle.kts`: Contains current versionCode/versionName, ndkVersion, applicationId — direct edit target
- `.gitmodules`: Already configured with whisper.cpp submodule path and URL

### Established Patterns
- NDK version pinned in app/build.gradle.kts with comment noting r27 preference for future
- Native code builds from source via CMake (no prebuilt .so files)
- applicationId: `com.simonpena.transchat`

### Integration Points
- build.gradle.kts version fields (versionCode, versionName)
- Repository root for LICENSE file
- New fastlane/ directory structure at repo root
- Draft YAML references build.gradle.kts configuration

</code_context>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 06-f-droid-distribution-prep*
*Context gathered: 2026-02-28*
