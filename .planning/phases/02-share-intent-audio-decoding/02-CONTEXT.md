# Phase 2: Share Intent and Audio Decoding - Context

**Gathered:** 2026-02-27
**Status:** Ready for planning

<domain>
## Phase Boundary

TransChat appears in the Android share sheet for audio files. When the user shares a voice message, the app acquires the audio bytes before URI permission expires and decodes OGG/Opus to 16kHz mono PCM ready for Whisper. Wiring the decoded PCM to the Whisper engine and producing output is Phase 3.

</domain>

<decisions>
## Implementation Decisions

### MIME Type Handling
- Permissive intent filter: accept both `audio/*` and `application/octet-stream` (Telegram sends voice messages as octet-stream)
- Audio only — do not accept `video/*` in v1
- Single file only — accept `ACTION_SEND`, ignore `ACTION_SEND_MULTIPLE`
- Generic error message for unsupported/undecodable files: "Could not process this file."

### Audio Acquisition Flow
- Copy bytes synchronously in onCreate before launching any coroutine — URI permission expires on backgrounding
- 25 MB file size limit — reject larger files with specific message: "File too large (25MB max). TransChat works best with voice messages."
- Store copied audio in `cacheDir/audio/` — OS auto-cleans, no manual cleanup needed
- Keep cached audio file until activity finishes (delete in onDestroy) — allows retry without re-sharing if downstream processing fails

### Decode Failure Behavior
- On decode failure: show Toast with error message and finish() the activity — user returns to sending app
- MediaCodec only — no FFmpeg fallback. WhatsApp/Telegram use Opus which MediaCodec handles on API 26+
- File size rejection gets a specific explanatory message; all other errors use the generic message

### Share Target UX
- Transparent activity (Theme.Translucent.NoTitleBar) — no visible screen. Copy + decode happen behind the scenes, toast on completion or error
- If model not yet downloaded: redirect to DownloadActivity with toast "Model needed"
- Share sheet shows just "TransChat" — no subtitle or description
- Phase 2 verification: log decoded PCM path and duration; write test WAV to cache for adb pull and playback verification on computer

### Claude's Discretion
- Exact Activity class structure for the share target (new activity vs reuse existing)
- MediaCodec configuration details for Opus/OGG decode
- Resampling approach if source audio is not 16kHz
- Temporary file naming convention
- Whether to use MediaExtractor + MediaCodec or a simpler API

</decisions>

<specifics>
## Specific Ideas

- Phase 1 established: URI bytes must be copied synchronously in onCreate before any async work — this is a locked architectural decision
- The Phase 1 research found that WhatsApp uses OGG/Opus and Telegram uses OGG/Opus or MP3 — MediaCodec handles both on API 26+
- 16kHz mono float32 PCM is the required output format (whisper.cpp input format)

</specifics>

<deferred>
## Deferred Ideas

- Video file audio extraction — potential future phase
- Multiple file sharing (ACTION_SEND_MULTIPLE) — queuing adds complexity, defer to v2
- FFmpeg as fallback decoder — only if users report unsupported formats post-launch

</deferred>

---

*Phase: 02-share-intent-audio-decoding*
*Context gathered: 2026-02-27*
