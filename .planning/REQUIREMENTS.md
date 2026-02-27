# Requirements: TransChat

**Defined:** 2026-02-27
**Core Value:** Transcribe shared voice messages locally and privately — no cloud, no data leaks.

## v1.1 Requirements

Requirements for v1.1 Polish & Distribution release. Each maps to roadmap phases.

### Progress Feedback

- [ ] **PROG-01**: Determinate progress bar shows 0-100% during Whisper inference
- [ ] **PROG-02**: Progress updates driven by whisper.cpp progress callback via JNI

### Distribution

- [ ] **DIST-01**: MIT LICENSE file added to repository root
- [ ] **DIST-02**: Fastlane metadata structure created (summary.txt, description.txt, changelogs/)
- [ ] **DIST-03**: fdroiddata YAML build recipe drafted with NDK version and submodules flag
- [ ] **DIST-04**: versionCode incremented to 2 for F-Droid release

### Release Quality

- [ ] **REL-01**: ProGuard/R8 rules replaced with targeted keeps (JNI native methods, sealed classes)
- [ ] **REL-02**: Release APK verified with R8 shrinking on physical device

### Repository

- [ ] **REPO-01**: Default branch renamed from master to main

## Future Requirements

Deferred to v1.2 or later.

### UX Enhancements

- **UX-01**: Partial transcript preview during transcription (segment callback text)
- **UX-02**: User-visible error messages distinguishing decode failure vs inference failure vs OOM
- **UX-03**: Manual language selection settings screen (persist preference)

### Quality

- **QUAL-01**: VAD (Voice Activity Detection) pre-screening for silent audio
- **QUAL-02**: Configurable model size (tiny for low-end devices, small as default)
- **QUAL-03**: Reproducible builds for F-Droid verification badge

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Partial transcript during inference | JNI upcall complexity; deferred to v1.2 |
| Reproducible builds | Not required for F-Droid inclusion; NDK reproducibility is difficult |
| Play Store listing | F-Droid is the distribution target |
| abort_callback (real inference cancellation) | 12-15s window is short enough; current coroutine cancel is sufficient |
| Transcript history / saved transcripts | Violates ephemeral privacy model |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| PROG-01 | TBD | Pending |
| PROG-02 | TBD | Pending |
| DIST-01 | TBD | Pending |
| DIST-02 | TBD | Pending |
| DIST-03 | TBD | Pending |
| DIST-04 | TBD | Pending |
| REL-01 | TBD | Pending |
| REL-02 | TBD | Pending |
| REPO-01 | TBD | Pending |

**Coverage:**
- v1.1 requirements: 9 total
- Mapped to phases: 0
- Unmapped: 9

---
*Requirements defined: 2026-02-27*
*Last updated: 2026-02-27 after v1.1 milestone definition*
