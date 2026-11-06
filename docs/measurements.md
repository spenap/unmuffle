# Transcription measurements

Two real voice messages in Spanish (70 s and 82 s, about 190 and 225
words), kept outside the repo. whisper.cpp v1.8.3 `whisper-cli` unless
stated.
"Changed" counts words that differ from the first row, ignoring case and
punctuation.

## 2026-11-06 · GPU spike: OpenCL and Vulkan (Pixel 5)
whisper.cpp v1.9.5, small q8_0, release builds (`./gradlew assembleRelease
-Pbench -Pgpu=opencl|opencl-generic|vulkan`; branch `spike/gpu`). No time
or text yet: no GPU run finished.

| Build | APK | Native libraries added (installed) |
|---|---|---|
| CPU (as today) | 5.19 MB | |
| OpenCL | 5.91 MB | 3.0 MB `libggml-opencl.so`, 10 KB loader |
| OpenCL, generic kernels only | 5.63 MB | |
| Vulkan, NDK's glslc (2022.3) | 11.56 MB | 21 MB `libggml-vulkan.so` |
| Vulkan, glslc 2026.1 | 16.11 MB | 35 MB |

- Both build for arm64-v8a with NDK r27c. OpenCL needs the Khronos
  headers and a loader for the phone's driver; Vulkan needs Vulkan's C++
  and SPIR-V headers (the NDK has neither) and links Android 9's
  libvulkan. The NDK's glslc lacks the cooperative-matrix and integer
  dot product extensions ggml uses on Adreno 7xx/8xx.
- Pixel 5 (Adreno 620, OpenCL 2.0, LineageOS 24): the vendor
  `libOpenCL.so` isn't open to apps; `libOpenCL-pixel.so` is, and ggml
  found the GPU through it. Then:
  1. The app crashed loading the model: whisper asks the OpenCL buffer
     type for its alignment before ggml has set up the device (null
     pointer). Fixed by setting GPU devices up first.
  2. The driver lacks `clGetKernelSubGroupInfo` (OpenCL 2.1); calling it
     crashed. Its `cl_khr_subgroups` twin stands in.
  3. The driver refused to compile ggml's Adreno flash-attention kernel
     (`cl_khr_3d_image_writes` missing) and ggml exited the app.
  4. Without the Adreno kernels, the run hung the GPU (kgsl "gpu
     syncpoint deadlock") and System UI stopped responding. Runs on the
     Pixel stopped there.
- Vulkan didn't run on any phone.


The app's release build, small q8_0, Spanish. Whole message / first
words; two runs each, none throttled below 93% or asleep.

| Run | 70 s | 82 s |
|---|---|---|
| Pixel 5, v1.8.3 | 79.9 / 74.2 s, 4.8 s | 98.1 / 92.4 s, 5.6–5.7 s |
| Pixel 5, v1.9.5 | 44.1 / 43.8 s, 4.0 s | 52.8 / 52.7 s, 4.2–4.7 s |
| S25, v1.8.3 (2026-11-04) | 8.9 / 8.9 s, 0.6 s | 11.8 / 12.1 s, 0.65 s |
| S25, v1.9.5 | 7.0 / 7.1 s, 0.6 s | 8.6 / 8.9 s, 0.6 s |

- Whole message: Pixel 5 about 43% faster, S25 about 23%. First words:
  the same on the S25, 0.8 s sooner on the Pixel 5.
- Text: the same on both phones and every run; 15 and 7 words changed
  from v1.8.3, neither better nor worse overall.

## 2026-11-04 · Time to first word
Pixel 5, q8_0, 6 threads, `-l es` unless stated. "First" is the first
segment, as the app shows it.

Spike, `whisper.cpp` directly (first / total):

| Run | 70 s | 82 s |
|---|---|---|
| auto | 28.3 / 63.4 s | 30.9 / 68.9 s |
| `es` | 15.4 / 50.7 | 16.9 / 53.4 |
| `es`, first ~10 s alone (`audio_ctx` fitted), then the rest | 3.5 / 56.7 | 4.1 / 59.5 |
| no temperature fallback | 16.4 / 53.6 | 16.8 / 53.2 |
| initial prompt | 16.2 / 53.3 | 18.4 / 56.7 |
| no timestamps | 17.2 / 51.6 | 17.0 / 52.4 |
| q5_K | 20.0 / 65.5 | 23.3 / 74.2 |
| q4_K | 16.4 / 54.1 | 16.9 / 68.5 |
| q4_0 | 15.2 / 47.9 | 16.6 / 65.8 |

Detecting the language on the first seconds still costs ~4 s, and read
the 82 s message as Galician.

Splitting the message changed words (on the 82 s one, whisper stopped
1.6 s before the end and lost the last words). A preview instead: the
first ~10 s alone, then the whole message as before, replacing it. The
app (`bench.sh`, two runs each, share → first words / done):

| Build | 70 s | 82 s |
|---|---|---|
| whole message | 25.4 / 75.4, 24.0 / 71.9 s | 22.4 / 91.8, 23.8 / 94.2 s |
| preview | 4.8 / 78.0, 4.4 / 75.7 | 5.8 / 95.2, 5.3 / 96.4 |

Text identical to the whole message's. The preview is replaced 28–31 s
in.

The text above, outside the app, is from audio decoded by ffmpeg's own
Opus decoder, which leaves pauses silent; Android and libopus fill them
with comfort noise, and whisper's words change with it. Decode with
`ffmpeg -c:a libopus` to match the app.

S25, by phase (82 s message, `es`, release build, two shares; whisper's
own timings, logcat tag `whisper`):

| Phase | Time |
|---|---|
| Share → model loaded (process start, 264 MB model) | 0.32–0.39 s |
| Copy, decode, resample | 0.20–0.21 s |
| Preview: mel / encoder / decoder | 0.01 / 0.37–0.40 / 0.26 s |
| **Share → first words** | **1.2–1.3 s** |
| Whole message: encoder (4 windows) | 6.8–7.2 s |
| Whole message: decoder, prompt | 2.5 s, 0.9–1.0 s |
| Whole message, total | 10.4–10.9 s |

No language detection with a language set. `bench.sh`, v1.7.0 against
this build (two runs, text identical): done in 7.6–8.9 / 10.6–11.8 s
against 8.9 / 11.8–12.1 s; v1.7.0 shows nothing until done.

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
- Pixel 5 threads (2 fast and 6 slow cores; `+dotprod`, q8_0, `-l es`,
  70 s message, two rounds): 2: 58.6 / 63.3 s, 4: 60.9 / 71.6, 6:
  50.6 / 51.2, 8: 48.2 / 49.9. The app uses all cores but two.
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
