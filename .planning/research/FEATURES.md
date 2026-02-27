# Feature Landscape: Android Audio Transcription (Share Target)

**Domain:** On-device audio transcription utility — share target for messaging app voice messages
**Researched:** 2026-02-27
**Overall confidence:** HIGH (primary sources: WhatsApp official blog, Play Store competitors, Whisper Notes, VoiceScriber, direct product research)

---

## Context: What We're Building

TransChat is a **single-purpose share-target utility**. A user in WhatsApp or Telegram long-presses a voice message, taps Share, selects TransChat, and gets the transcript on their clipboard 10-60 seconds later. There is no persistent UI, no account, no network. This scope is a deliberate constraint — not a limitation.

Understanding this scope is the prerequisite to every feature decision below.

---

## Table Stakes

Features users expect from any transcription tool that shares this category. Missing any of these means users leave or leave 1-star reviews.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| **Accurate transcription output** | The entire reason for the app's existence. If accuracy is poor, nothing else matters. | High (model quality, not code) | Whisper small multilingual achieves ~7-9% WER on mixed benchmarks — acceptable for voice messages. Accuracy complaints are the #1 user issue in every comparable app. |
| **Works offline / no network required** | Users sharing confidential voice messages (medical, legal, personal) won't accept a network dependency. Also required in low-connectivity environments. | Low (architectural) | This is already baked into the design. Must be communicated clearly to users — competitors (WAVO, Letterly) send audio to cloud servers. This is a genuine differentiator but also a baseline expectation for privacy apps. |
| **Share intent integration** | The entire interaction model. If WhatsApp → Share → TransChat doesn't work, the app cannot function. | Medium | Must handle `audio/*` and `application/octet-stream` MIME types. WhatsApp uses `audio/ogg`, Telegram uses `audio/ogg`. Both wrap Opus codec. |
| **Clipboard output** | Expected output destination for a "transcribe and use" workflow. Users immediately paste into chat, notes, email. | Low | Android 13+ shows a system clipboard preview popup automatically. No custom UI needed. |
| **User-visible progress feedback** | Transcription takes 10-60 seconds. With no feedback, users will assume the app crashed and dismiss it. | Low | Progress dialog or indeterminate spinner is mandatory. A "Transcribing..." message is the minimum. |
| **Completion notification** | User needs to know when the transcript is ready. A Toast ("Copied to clipboard") is the standard pattern for this interaction model. | Low | Toast is fine for MVP. Post-Android 13, clipboard write also triggers a system-level confirmation overlay. |
| **Multilingual support** | Voice messages are inherently multilingual — a user may receive messages in languages they do not speak, which is a primary reason to transcribe. Whisper small supports 99 languages. | Low (model handles it) | Auto-detection via Whisper's language detection is the correct default. Explicit language selection is a differentiator, not table stakes. |
| **Graceful error handling** | Decode failures, unsupported formats, empty audio, and OOM are all real failure modes. Silently doing nothing after a share is unacceptable. | Low-Medium | At minimum: show a toast with the error category ("Could not decode audio" vs "Audio too long"). |
| **Fast enough to be useful** | Users will abandon the workflow if waiting feels unreasonable. Empirically: under 30 seconds for a typical 30-second voice message is acceptable; over 60 seconds for the same is a dealbreaker. | High (hardware-dependent) | Whisper small on mid-range hardware: ~15-30 seconds for 30-second audio. On low-end: 30-90 seconds. Need to set user expectations. |

---

## Differentiators

Features that set TransChat apart from cloud-based competitors like WAVO, Letterly, and WhatsApp's own native transcription. These create competitive advantage and justify installation of a separate tool.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| **Verified on-device processing (no server)** | Biometric trust: voice is a biometric identifier that cannot be changed after exposure. "Your audio cannot leave your device — not as a setting but as a technical constraint." Users in legal, medical, and personal domains have strong demand for this. | Low (architectural) | Frame this as "zero network required" and consider including a network-off indicator in the UI. WhatsApp's own transcription is on-device but limited to specific languages; TransChat extends this to 99 languages. |
| **No account required** | Eliminates onboarding friction and identity tracking. Competing tools (WAVO, Letterly, Otter.ai) require sign-up. | None | Already implicit in the design. Explicitly communicate it: "No sign-in. No account. Open and transcribe." |
| **No subscription / no usage caps** | WAVO's free tier gives 5 minutes/month (users called it "costs more than Netflix"). Letterly is cloud-billed per minute. A flat-payment or open-source model removes this friction entirely. | None (pricing, not code) | WAVO's #1 complaint in user reviews is the subscription paywall after 5 free minutes. This is a clear gap TransChat can own. |
| **Telegram support** | Most WhatsApp-branded transcription tools work only with WhatsApp's share flow. Telegram voice notes use the same OGG/Opus format and the same Android share intent — supporting both is effectively free. | Low | Frame as "works with WhatsApp, Telegram, and any app that shares audio." |
| **Any-audio share target** | Because TransChat registers for `audio/*`, it can receive audio from any app: Signal, voice recorders, file managers, email attachments. Users discover this as a bonus. | None (already implicit) | Don't over-advertise initially — keep messaging focused on the primary use case. |
| **Language auto-detection** | Whisper's language detection identifies the spoken language without user configuration. Users with multilingual contacts (family, international colleagues) don't need to change settings per message. | Low (model handles it) | Most competing apps require manual language selection, which is friction. Auto-detect should be the default; manual override is a plus-one. |
| **Manual language selection** | Expert users who know the expected language can get faster, more accurate results by bypassing the auto-detection step. Also fixes edge cases where accent or background noise confuses the detector. | Low | Settings screen: pick from Whisper's 99 supported languages. Persist the selection. |
| **Processing speed indicator** | Show elapsed time or an audio-length-proportional progress bar during transcription. Users tolerate waits much better when they have a sense of progress. | Low-Medium | Whisper.cpp's segment-by-segment callback API can drive a real progress indicator. Each segment emitted = a percentage of audio processed. |

---

## Anti-Features

Features to deliberately NOT build. Each one is a trap that has broken comparable tools, adds maintenance burden disproportionate to value, or violates the core design contract.

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| **Transcript history / saved transcripts** | Storing every voice message a user transcribes creates a database of sensitive communications on-device. If the phone is lost, shared with family, or the app is uninstalled, this data is a liability. The design principle of "your audio never leaves your device" should extend to stored transcripts. Additionally, clipboard is ephemeral by design — users who want persistence will paste to their notes app. | Let the user paste to wherever they want to keep it. Clipboard is the right ephemeral handoff. |
| **Built-in text editor / note-taking** | Letterly, Otter.ai, and Wave all have transcript editing, formatting, and note organization. These are full products, not features. Building editing UI duplicates what Notes, Keep, and Obsidian already do — and does it worse. | Transcribe and get out. The user chose their notes app already. |
| **AI summary / action items / rewrite** | Letterly and VoiceAI offer AI post-processing (turn transcript into email, extract action items). This requires either a cloud API (violating the no-network contract) or a second large model on-device (doubling memory requirements). Neither is acceptable for TransChat's scope. | The Whisper transcript is the output. Let users copy it to their AI tool of choice (ChatGPT, Claude, etc.) if they want post-processing. |
| **Translation output** | Whisper can translate to English, but it requires a separate inference pass and the model's translation quality for non-English→English paths is inconsistent. More importantly, offering translation invites scope expansion into a language tool, not a transcription tool. | Transcribe in the source language. Let the user paste to Google Translate or DeepL. |
| **Background/batch transcription** | Processing multiple voice messages in the background without user initiation requires foreground service notifications, `FOREGROUND_SERVICE` permission, and a queue management UI. This is a fundamentally different product (a voice message inbox manager). | One transcription per share intent. Sequential sharing works fine for most users. |
| **Real-time / live microphone transcription** | This is Google Live Transcribe's domain and requires an entirely different architecture (streaming ASR, not batch file transcription). It would also require microphone permission (a privacy red flag for a privacy-focused app) and conflicts with the share-intent interaction model. | Stay in the file-transcription lane. |
| **Cloud upload option / fallback** | "Optional" cloud processing as a fallback for slow devices sounds helpful but destroys the trust model. Users who chose TransChat for privacy won't trust an "optional" network path. | Accept that slow devices have slow transcription. Communicate this at first launch: "Processing is done on your device. Speed depends on your hardware." |
| **Speaker identification / diarization** | Whisper small does not support diarization. Adding it requires either WhisperX (Python, not available on Android), a separate speaker model (another 50-200 MB), or an entirely different ASR pipeline. | Out of scope for MVP and likely for the foreseeable future. |
| **Persistent notification / system tray integration** | A foreground service with a permanent notification is appropriate for music players and sync tools. For a fire-and-forget transcription tool, a persistent notification is noise that trains users to dismiss all your notifications. | Use Activity lifecycle scope (already decided in ARCHITECTURE.md). Toast on completion. Done. |
| **Settings screen with 20+ options** | Transcription apps that expose every Whisper parameter (beam size, temperature, VAD threshold, etc.) have steep learning curves and confuse average users. | One setting: language selection (auto-detect vs manual). Possibly a quality/speed tradeoff toggle (small vs tiny model) in a later phase. |

---

## Feature Dependencies

```
Audio format support (OGG/Opus via MediaCodec)
  └── Share intent reception (must decode whatever WhatsApp sends)
       └── Clipboard output (requires successful transcription)
            └── Toast confirmation (requires clipboard write)

Language auto-detection (Whisper built-in)
  └── Manual language selection (user override of auto-detect)

Progress indicator
  └── Whisper.cpp segment callback (requires whisper_full with callback params)

Model size in APK (244MB)
  └── First-launch model extraction (aaptOptions noCompress)
       └── WhisperEngine lazy singleton (don't re-extract on every transcription)

Error handling states
  └── All pipeline stages (decode error, inference error, OOM)
       └── User-visible error toast (requires distinguishing error types)
```

---

## Competitor Feature Matrix

| Feature | TransChat (target) | WhatsApp Native | WAVO | Letterly | Google Recorder |
|---------|-------------------|-----------------|------|----------|-----------------|
| On-device (no network) | YES | YES | NO (cloud) | NO (cloud) | YES (Pixel only) |
| Share intent target | YES | NO (built-in) | YES | YES | NO |
| No account required | YES | YES | NO | NO | YES |
| No usage cap | YES | YES | 5 min/month free | Paid per minute | YES |
| Multilingual auto-detect | YES (99 langs) | Limited (6 langs) | YES | YES | Limited |
| Telegram support | YES | NO | Limited | Limited | NO |
| Transcript history | NO (by design) | NO | YES | YES | YES |
| AI post-processing | NO (by design) | NO | NO | YES | NO |
| Cost | Free/open | Free | Subscription | Subscription | Free (Pixel only) |

---

## MVP Recommendation

Build in this order, stopping when you have a working product:

**Phase 1 (Mandatory core):**
1. Share intent reception for `audio/*` — the entry point
2. OGG/Opus decode via MediaCodec — the format reality of WhatsApp/Telegram
3. Whisper small inference via JNI — the core value
4. Clipboard output + completion toast — the exit point
5. Progress indication during transcription — the non-negotiable UX requirement
6. Error states with user-visible messages — the difference between "broken" and "informative"

**Phase 2 (Differentiation, after Phase 1 ships):**
7. Language auto-detection (already in Whisper — just expose it properly)
8. Manual language selection setting — helps multilingual-household users
9. Processing progress bar driven by Whisper segment callbacks — improves perceived performance

**Defer indefinitely (anti-features):**
- Transcript history
- Text editor
- AI post-processing
- Translation
- Background/batch processing
- Real-time microphone transcription

---

## Notes on Whisper Small Quality for Voice Messages

Whisper small (244 MB, ~245M parameters) achieves roughly 7-9% WER on diverse multilingual benchmarks as of 2025. For voice messages specifically:

- **Favorable conditions:** Single speaker, near-microphone recording, conversational register — typical of voice notes. Whisper performs well here.
- **Challenging conditions:** Background noise, multiple speakers, strong accents, domain jargon. Accuracy drops but remains useful.
- **Known failure mode:** Hallucination on silent or near-silent audio. Whisper can generate plausible-sounding text from background noise. Mitigation: check audio duration and amplitude before inference; show a warning if input appears to be silence.
- **Language support:** 99 languages. Quality varies significantly — English, Spanish, French, German, Portuguese, and Chinese have the most training data. Smaller-language results should be treated as "best effort."

---

## Sources

- [WhatsApp Blog: Introducing Voice Message Transcripts](https://blog.whatsapp.com/introducing-voice-message-transcripts) — On-device processing, language support, privacy model (HIGH confidence)
- [WhatsApp Help Center: Voice Message Transcripts](https://faq.whatsapp.com/241617298315321/?cms_platform=web) — Enable/disable, language selection UX (HIGH confidence)
- [VoiceScriber: Best Offline Transcription Apps 2026](https://voicescriber.com/best-offline-transcription-apps) — Feature matrix for privacy-focused offline transcription tools (MEDIUM confidence)
- [Whisper Notes: Stop Uploading Your Voice](https://whispernotes.app/whisper-app) — Privacy trust model, on-device architecture positioning (MEDIUM confidence)
- [WAVO: Transcriber for WhatsApp — AppBrain](https://www.appbrain.com/app/wavo-transcriber-for-whatsapp/speechtotext.transcriber.forwhatsapp.voice.messages.to.text) — User complaints: 5 min/month free tier, subscription pricing vs Netflix comparison (MEDIUM confidence, user review source)
- [Letterly: WhatsApp Audio to Text](https://letterly.app/blog/how-to-turn-whatsapp-messages-into-text/) — AI post-processing features, scope of competing tools (MEDIUM confidence)
- [Riverside: Best Audio & Video Transcription Apps 2026](https://riverside.com/blog/best-audio-video-transcription-apps) — Market landscape, feature expectations across category (MEDIUM confidence)
- [DIY AI: Can Whisper Still Win? Transcription Benchmarks 2025](https://diyai.io/ai-tools/speech-to-text/can-whisper-still-win-transcription-benchmarks/) — WER benchmarks, multilingual quality (MEDIUM confidence)
- [Northflank: Best Open Source STT Models 2026](https://northflank.com/blog/best-open-source-speech-to-text-stt-model-in-2026-benchmarks) — Whisper accuracy vs alternatives (MEDIUM confidence)
- [VideoSDK: Speech to Text Software for Android 2025](https://www.videosdk.live/developer-hub/stt/speech-to-text-software-for-android) — Android-specific patterns, on-device vs cloud tradeoffs (MEDIUM confidence)
- [Whisper Notes: Offline Transcription](https://whispernotes.app/offline-transcribe) — User trust language for privacy-focused positioning (MEDIUM confidence)
