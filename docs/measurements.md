# Transcription measurements

Two real voice messages in Spanish (70 s and 82 s, about 190 and 225
words), kept outside the repo. whisper.cpp v1.8.3 `whisper-cli`.
"Changed" counts words that differ from the first row, ignoring case and
punctuation.

## 2026-11-03 · Speed: build flags, q8_0, language, threads
Greedy decoding (`-bs 1`, as the app), 4 threads unless stated. Two runs
per cell; on the S25 each starts at AP ≤ 40 °C with the screen on.
"baseline" is ggml built as the app builds it today: no `-march` for its
CPU kernels (the `+fp16` in `CMakeLists.txt` reaches only the JNI code).

| Run | S25, 70 s | S25, 82 s | Pixel 5, 70 s | Pixel 5, 82 s |
|---|---|---|---|---|
| baseline, q5_1, auto (the app) | 37.6 / 41.2 s | 39.5 / 40.9 s | 141.3 s | 141.6 s |
| `armv8.2-a+fp16` | 33.5 / 34.3 | 33.1 / 34.6 | 124.1 | 125.5 |
| `+dotprod` | 29.3 / 30.3 | 29.9 / 30.5 | 110.6 | 112.0 |
| `+dotprod`, q8_0 | 13.6 / 13.6 | 15.0 / 14.7 | 76.7 | 77.6 |
| `+dotprod`, q8_0, `-l es` | 11.6 / 10.8 | 11.1 / 11.3 | 63.4 / 66.2 | 61.4 |
| `+dotprod`, q8_0, `-l es`, 6 threads | 8.3 / 12.7 | 8.8 / 9.0 | | |
| `+dotprod+i8mm`, q8_0, `-l es`, 6 threads | 7.9 / 8.3 / 8.2 / 11.5 | 11.4 / 8.0 / 8.6 / 8.2 | | |

- S25: about 40 s → 8.5 s. Pixel 5: 141 s → 63 s.
- q8_0 vs q5_1 (laptop, greedy): 6 and 21 words changed, mostly fixed
  (wrong words, garbled phrases); two words dropped.
- `-l es` vs auto: same text. Auto-detect picked Spanish with p ≈ 0.55
  on both messages.
- S25 threads (i8mm, q8_0, `-l es`): 7 about as fast as 6, 8 slower
  (11.9–17.0 s); any setting has an occasional slow run.
- i8mm: no measurable gain.
- A first S25 attempt, partly with the screen locked, was discarded.

Laptop, greedy, 4 threads:

| Run | 70 s | 82 s |
|---|---|---|
| q5_1, auto | 18.1 / 20.2 s | 19.4 / 20.8 s |
| q5_1, `-l es` | 15.5 / 15.8 | 16.2 / 16.4 |
| q8_0, auto | 16.5 / 16.7 | 16.7 / 16.9 |
| q8_0, `-l es` | 13.3 / 13.5 | 13.7 / 13.8 |

Streaming: per 30 s window, segments arrive together at its end
(laptop, q8_0: 3.9, 8.2, 12.2 s for the 70 s message). The first token
is decoded at 2.9 s; `logits_filter_callback` sees each token as it is
decoded, and none were retracted in 623 steps.

## 2026-11-03 · VAD and base vs small
`whisper-cli` defaults: beam search (5 beams), 4 threads, laptop, `-l
auto`. The app decodes greedily; the comparison holds, the times don't.

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
