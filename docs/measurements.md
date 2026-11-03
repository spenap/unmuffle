# Transcription measurements

Two real voice messages in Spanish (70 s and 82 s, 193 and 226 words),
kept outside the repo. whisper.cpp v1.8.3 `whisper-cli`, 4 threads, on
the laptop; `-l auto`. "Changed" counts words that differ from small
without VAD, ignoring case and punctuation.

## 2026-11-03 · VAD and base vs small

| Run | 70 s message | 82 s message |
|---|---|---|
| small q5_1 (current) | 22.7 s | 25.3 s |
| small + VAD (Silero v6.2.0) | 23.6 s, 9 words changed | 26.8 s, 19 changed |
| base q5_1 | 7.6 s, 23 changed | 8.5 s, 47 changed |

- VAD: the messages have no long pauses, so it only adds its own pass.
  Its changes are a mix of better and worse words. With 24 s of digital
  silence added to the 70 s message (four 6 s gaps), VAD took 23.4 s
  instead of 28.4 s but dropped a 12-word phrase.
- base: three times faster; most changed words are worse (words split
  or merged, wrong words, dropped words). Accuracy doesn't depend on
  the device, so the S25 run was skipped.
