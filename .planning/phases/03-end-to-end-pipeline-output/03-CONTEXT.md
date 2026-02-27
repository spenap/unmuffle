# Phase 3: End-to-End Pipeline and Output - Context

**Gathered:** 2026-02-27
**Status:** Ready for planning

<domain>
## Phase Boundary

Wire the decoded PCM from Phase 2 into Whisper inference (Phase 1), present the transcript to the user, and provide copy/share actions. This is the complete working MVP: share a voice message, see the transcript, copy or share it.

</domain>

<decisions>
## Implementation Decisions

### Transcript delivery
- Do NOT auto-copy to clipboard — privacy concern (clipboard readable by other apps on older Android)
- Show transcript on a **result screen** with explicit **Copy** and **Share** buttons
- User controls when text leaves the app
- Plain text, no formatting or prefixes — clean for pasting into any context
- Always show the full transcript regardless of length (no truncation)

### Result screen
- Simple screen: transcript text displayed prominently, Copy button, Share button
- Copy button copies plain text to clipboard
- Share button opens Android share sheet with the transcript text
- No language indicator — user can read what language it is
- For no-speech results: show a friendly "No speech detected" message, hide Copy/Share buttons

### Processing feedback
- Show a **progress screen** with spinner and "Transcribing..." during the 15-60s inference window
- Progress screen transitions to result screen when inference completes
- Back button during inference **cancels** and returns to previous app
- If user navigates away (backgrounds the app), **cancel inference** and clean up — user can re-share

### Hallucination handling
- When Whisper returns markers like `[BLANK_AUDIO]`: show "No speech detected" on result screen instead of raw marker
- Hallucination detection approach: Claude's discretion (energy-threshold VAD, token analysis, or similar)
- Short clips (under 2s): process normally — short audio may still contain useful speech

### Language behavior
- Auto-detect language (Whisper `language = "auto"`) — already configured in JNI params
- No language indicator in output — keep result screen clean
- Wrong language / garbled output: show as-is, no retry mechanism for MVP

### Claude's Discretion
- Progress screen visual design (spinner style, layout)
- Result screen layout details (spacing, typography, button style)
- Hallucination detection implementation approach
- How to detect and handle Whisper non-speech markers (pattern matching, token analysis, etc.)
- ViewModel architecture for managing inference lifecycle

</decisions>

<specifics>
## Specific Ideas

- Privacy-first: transcript never leaves the app without explicit user action (Copy or Share button)
- The flow is: share sheet → progress screen (spinner) → result screen (transcript + actions)
- Back press at any point returns to the previous app
- Cancel inference on background — simple and predictable, user can re-share

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 03-end-to-end-pipeline-output*
*Context gathered: 2026-02-27*
