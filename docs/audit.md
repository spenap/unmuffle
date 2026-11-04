# Audit

Third-party checks of what the app does, newest first. Each names the APK
it checked by version and SHA-256.

## 2026-11-04 · Exodus Privacy: no trackers
`scripts/exodus-scan.sh` on the release build of `main` (v1.7.0-11-g3872220,
SHA-256 `ee8b65c4…6210e99`): exodus-core 1.3.14, the library behind
[reports.exodus-privacy.eu.org](https://reports.exodus-privacy.eu.org),
matched its 432 tracker signatures (Firebase, Crashlytics, Facebook,
Sentry…) against the APK's 1,148 classes and found none. Permissions:
INTERNET, and the app-private one AndroidX adds.
