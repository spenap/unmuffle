# Phase 2: Share Intent and Audio Decoding - Research

**Researched:** 2026-02-27
**Domain:** Android Share Intent handling, Content URI acquisition, MediaCodec audio decoding
**Confidence:** HIGH

## Summary

Phase 2 wires TransChat into the Android share sheet so users can share voice messages from WhatsApp/Telegram, then decodes those OGG/Opus files to 16kHz mono float32 PCM ready for Whisper. The phase has two distinct technical sub-problems: (1) reliably acquiring audio bytes from a content URI before permission expires, and (2) decoding OGG/Opus (and potentially MP3) to the exact PCM format whisper.cpp expects.

The Android platform provides all necessary tools without third-party libraries. `ACTION_SEND` intent filters are well-documented, `ContentResolver.openInputStream()` provides synchronous byte access, and `MediaExtractor` + `MediaCodec` handle Opus decoding natively since API 21. The critical nuance is that Opus always decodes to 48kHz on Android, so we must downsample 3:1 to reach 16kHz. This is a clean integer ratio allowing simple decimation with a low-pass filter.

**Primary recommendation:** Create a dedicated `ShareReceiverActivity` with transparent theme that synchronously copies URI bytes to cache in `onCreate`, then launches a coroutine for `MediaExtractor`/`MediaCodec` decode + resample pipeline. Output is a `FloatArray` of 16kHz mono PCM. Write a verification WAV to cache for `adb pull` testing.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- Permissive intent filter: accept both `audio/*` and `application/octet-stream` (Telegram sends voice messages as octet-stream)
- Audio only -- do not accept `video/*` in v1
- Single file only -- accept `ACTION_SEND`, ignore `ACTION_SEND_MULTIPLE`
- Generic error message for unsupported/undecodable files: "Could not process this file."
- Copy bytes synchronously in onCreate before launching any coroutine -- URI permission expires on backgrounding
- 25 MB file size limit -- reject larger files with specific message: "File too large (25MB max). TransChat works best with voice messages."
- Store copied audio in `cacheDir/audio/` -- OS auto-cleans, no manual cleanup needed
- Keep cached audio file until activity finishes (delete in onDestroy) -- allows retry without re-sharing if downstream processing fails
- On decode failure: show Toast with error message and finish() the activity -- user returns to sending app
- MediaCodec only -- no FFmpeg fallback. WhatsApp/Telegram use Opus which MediaCodec handles on API 26+
- File size rejection gets a specific explanatory message; all other errors use the generic message
- Transparent activity (Theme.Translucent.NoTitleBar) -- no visible screen. Copy + decode happen behind the scenes, toast on completion or error
- If model not yet downloaded: redirect to DownloadActivity with toast "Model needed"
- Share sheet shows just "TransChat" -- no subtitle or description
- Phase 2 verification: log decoded PCM path and duration; write test WAV to cache for adb pull and playback verification on computer

### Claude's Discretion
- Exact Activity class structure for the share target (new activity vs reuse existing)
- MediaCodec configuration details for Opus/OGG decode
- Resampling approach if source audio is not 16kHz
- Temporary file naming convention
- Whether to use MediaExtractor + MediaCodec or a simpler API

### Deferred Ideas (OUT OF SCOPE)
- Video file audio extraction -- potential future phase
- Multiple file sharing (ACTION_SEND_MULTIPLE) -- queuing adds complexity, defer to v2
- FFmpeg as fallback decoder -- only if users report unsupported formats post-launch
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| SHARE-01 | App appears in Android share sheet when user shares audio from WhatsApp/Telegram | Intent-filter configuration with ACTION_SEND + audio/* and application/octet-stream MIME types; separate intent-filter elements per MIME type |
| SHARE-02 | App accepts audio/* and application/octet-stream MIME types | Two intent-filter blocks in manifest, each with the same action/category but different mimeType data element |
| SHARE-03 | Audio bytes copied from content URI to private storage before processing | Synchronous ContentResolver.openInputStream() + copyTo() in onCreate; file size check via ContentResolver.query() with OpenableColumns.SIZE |
| TRANS-01 | Audio decoded from OGG/Opus to 16kHz mono PCM using platform MediaCodec | MediaExtractor + MediaCodec pipeline; Opus decoder outputs 48kHz PCM which must be downsampled 3:1 to 16kHz; stereo-to-mono mixing if needed |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| android.media.MediaExtractor | Platform (API 26+) | Demux OGG/Opus container, extract audio track format and encoded packets | Only platform API for container parsing; no third-party needed |
| android.media.MediaCodec | Platform (API 26+) | Decode Opus/MP3 compressed audio to raw PCM | Hardware-accelerated on most devices; Opus decoder mandatory per CDD |
| android.content.ContentResolver | Platform | Read bytes from content:// URI shared by WhatsApp/Telegram | Only way to access shared content URIs |
| android.provider.OpenableColumns | Platform | Query file size and display name from content URI | Standard for pre-checking file size before copy |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlinx.coroutines.android | Already in project | Offload decode work from main thread | After synchronous byte copy, all decode work runs in coroutine |
| androidx.lifecycle.lifecycleScope | Already in project | Scoped coroutine launching in Activity | Decode coroutine auto-cancels on activity destroy |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| MediaExtractor+MediaCodec | FFmpeg (via mobile-ffmpeg) | FFmpeg handles more formats but adds 15-30MB to APK; locked decision says no |
| Custom resampler | libsamplerate / Oboe resampler | Adds native dependency; 48kHz->16kHz is clean 3:1 ratio, trivial to implement |
| Separate opus library | theeasiestway/android-opus-codec | Adds JNI dependency; MediaCodec handles Opus natively since API 21 |

**Installation:**
No additional dependencies needed. All APIs are platform-provided.

## Architecture Patterns

### Recommended Project Structure
```
app/src/main/java/com/simonpena/transchat/
├── ShareReceiverActivity.kt  # Transparent share target, byte copy + decode orchestration
├── AudioCopier.kt            # Synchronous URI -> cache file copy with size check
├── AudioDecoder.kt           # MediaExtractor + MediaCodec -> PCM pipeline
├── PcmResampler.kt           # 48kHz -> 16kHz downsampling + stereo-to-mono
├── WavWriter.kt              # Debug WAV file writer for verification
├── MainActivity.kt           # (existing) Launcher router
├── DownloadActivity.kt       # (existing) Model download
├── ...                        # (existing Phase 1 files)
```

### Pattern 1: Dedicated ShareReceiverActivity (New Activity)
**What:** A new `ShareReceiverActivity` separate from `MainActivity` handles the share intent. It uses `Theme.Translucent.NoTitleBar` and has no layout.
**When to use:** Always for Phase 2. The share target has fundamentally different lifecycle from the launcher flow.
**Why not reuse MainActivity:** MainActivity is the launcher router. Mixing share-intent handling into it would complicate the routing logic and intent discrimination. A dedicated activity keeps concerns separated and makes the manifest intent-filter configuration clean.

**Manifest configuration:**
```xml
<!-- Share target: transparent, no UI, receives audio shares -->
<activity
    android:name=".ShareReceiverActivity"
    android:exported="true"
    android:label="TransChat"
    android:theme="@android:style/Theme.Translucent.NoTitleBar">

    <!-- Intent filter 1: standard audio MIME types (WhatsApp, most apps) -->
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="audio/*" />
    </intent-filter>

    <!-- Intent filter 2: octet-stream (Telegram voice messages) -->
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="application/octet-stream" />
    </intent-filter>
</activity>
```

**Key details:**
- Two separate `<intent-filter>` elements, one per MIME type. Android requires separate filters, not multiple `<data>` elements in one filter for different MIME types with ACTION_SEND.
- `android:exported="true"` is required for other apps to send to this activity.
- `android:label="TransChat"` controls what appears in the share sheet.
- The `DEFAULT` category is required for the activity to appear in the share sheet.

### Pattern 2: Synchronous Byte Copy in onCreate (Racing the Backgrounding)
**What:** Copy all bytes from the content URI to a local cache file synchronously on the main thread in `onCreate`, before any coroutine or async work.
**When to use:** Always. Content URI read permissions from `ACTION_SEND` are valid only while the receiving activity's task is in the foreground. If the user switches apps (backgrounding), the content provider may revoke access.

**Critical implementation detail:**
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // No setContentView -- transparent activity

    if (intent?.action != Intent.ACTION_SEND) {
        finish()
        return
    }

    // Step 1: Check model is present (fast check, no SHA256)
    if (!ModelManager.isModelPresentFast(this)) {
        Toast.makeText(this, "Model needed", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, DownloadActivity::class.java))
        finish()
        return
    }

    // Step 2: Get URI from intent
    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    if (uri == null) {
        Toast.makeText(this, "Could not process this file.", Toast.LENGTH_SHORT).show()
        finish()
        return
    }

    // Step 3: Check file size BEFORE copying (fast, uses ContentResolver query)
    val fileSize = queryFileSize(uri)
    if (fileSize > MAX_FILE_SIZE_BYTES) {
        Toast.makeText(this, "File too large (25MB max)...", Toast.LENGTH_LONG).show()
        finish()
        return
    }

    // Step 4: SYNCHRONOUS copy -- must happen before any async work
    val cachedFile = copyUriToCache(uri)
    if (cachedFile == null) {
        Toast.makeText(this, "Could not process this file.", Toast.LENGTH_SHORT).show()
        finish()
        return
    }

    // Step 5: Now safe to go async -- we own the bytes
    lifecycleScope.launch { decodeAndProcess(cachedFile) }
}
```

**Why synchronous on main thread:** The byte copy for a 25MB file takes ~50-200ms on modern storage. This is well within the 5-second ANR threshold. The alternative (async copy) risks the URI becoming invalid if the user backgrounds the app during the copy.

### Pattern 3: MediaExtractor + MediaCodec Decode Pipeline
**What:** Use `MediaExtractor` to demux the container (OGG, MP3, etc.) and feed encoded packets to `MediaCodec` for decoding to raw PCM.
**When to use:** For all audio decode operations in this phase.

**Pipeline flow:**
```
Cache file (OGG/Opus)
  -> MediaExtractor (demux container, extract audio track)
  -> MediaCodec (decode Opus -> PCM 48kHz int16)
  -> Resample (48kHz -> 16kHz, 3:1 decimation)
  -> Mono mix (if stereo)
  -> Float normalize (int16 -> float32 in [-1.0, 1.0])
  -> FloatArray ready for WhisperEngine.transcribe()
```

### Anti-Patterns to Avoid
- **Reading URI in a coroutine:** The URI permission may expire between launching the coroutine and reading the stream. Always copy bytes synchronously first.
- **Using intent.getData() instead of EXTRA_STREAM:** `ACTION_SEND` puts the URI in `EXTRA_STREAM`, not in the intent data field.
- **Single intent-filter with multiple mimeTypes:** Combining `audio/*` and `application/octet-stream` in one `<intent-filter>` does not work correctly. Use separate filters.
- **Assuming PCM output is 16kHz:** MediaCodec Opus decoder always outputs at 48kHz (Opus native rate). You MUST check `getOutputFormat()` and resample.
- **Assuming mono output:** WhatsApp voice notes are mono, but Telegram or other apps might share stereo. Always check channel count from output format.
- **Large buffer allocation on main thread:** The synchronous file copy should use buffered stream copy (`inputStream.copyTo(outputStream)` with 8KB default buffer), not `readBytes()` which allocates the entire file in memory at once.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Container demuxing | OGG parser | MediaExtractor | OGG page structure is complex; MediaExtractor handles all container formats |
| Opus decoding | Raw libopus JNI | MediaCodec with "audio/opus" MIME | Platform decoder is hardware-aware and CDD-mandated on all devices |
| File size query | Read entire URI to count bytes | ContentResolver.query(OpenableColumns.SIZE) | Returns size without reading any content bytes |
| WAV file writing | Manual byte manipulation | Simple 44-byte header utility | WAV header is exactly 44 bytes for PCM; a clean utility function suffices |

**Key insight:** The entire decode pipeline uses platform APIs exclusively. No native code, no third-party JNI, no additional Gradle dependencies. This keeps the APK size unchanged from Phase 1.

## Common Pitfalls

### Pitfall 1: Content URI Permission Revocation
**What goes wrong:** App reads URI in a background coroutine; user switches to another app; content provider revokes read permission; `SecurityException` or `FileNotFoundException` crashes the app.
**Why it happens:** `FLAG_GRANT_READ_URI_PERMISSION` is tied to the receiving activity's task being in the foreground. WhatsApp's content provider is particularly aggressive about revoking access.
**How to avoid:** Copy ALL bytes to `cacheDir/audio/` synchronously in `onCreate` before launching any coroutine. Once bytes are in local storage, the URI is no longer needed.
**Warning signs:** Intermittent "Permission Denial" crashes in logs that only reproduce when sharing and quickly switching apps.

### Pitfall 2: MediaCodec Opus Decoder Outputs 48kHz, Not 16kHz
**What goes wrong:** Developer assumes decoded PCM is at the source sample rate or at 16kHz. Feeds 48kHz samples directly to Whisper. Whisper interprets 48kHz data as 16kHz, producing audio that plays at 3x speed, causing garbled transcription or silence.
**Why it happens:** Opus internally operates at 48kHz regardless of the original recording sample rate. The Android MediaCodec Opus decoder always outputs at 48kHz.
**How to avoid:** Always read `KEY_SAMPLE_RATE` from `codec.getOutputFormat()` after `INFO_OUTPUT_FORMAT_CHANGED`. If not 16000, resample. For 48kHz->16kHz, use 3:1 decimation with low-pass filter.
**Warning signs:** Transcription returns empty string or garbage for audio that should produce valid speech.

### Pitfall 3: Telegram Shares as application/octet-stream
**What goes wrong:** App only registers `audio/*` intent filter. Telegram shares voice messages with MIME type `application/octet-stream`. App never appears in Telegram's share sheet.
**Why it happens:** Telegram doesn't always set the correct audio MIME type when sharing voice messages to other apps.
**How to avoid:** Register a second intent-filter for `application/octet-stream`. The `MediaExtractor` will detect the actual codec from the file header regardless of the declared MIME type.
**Warning signs:** App works fine sharing from WhatsApp but is invisible in Telegram's share sheet.

### Pitfall 4: getParcelableExtra Deprecation
**What goes wrong:** Using deprecated `getParcelableExtra<Uri>(Intent.EXTRA_STREAM)` generates warnings. The replacement `getParcelableExtra(key, Uri::class.java)` requires API 33+.
**Why it happens:** API deprecation cycle in Android 13+.
**How to avoid:** Since minSdk is 26, use the deprecated variant with `@Suppress("DEPRECATION")`. The deprecated method still works on all API levels. Alternatively, use `IntentCompat.getParcelableExtra()` from AndroidX if available in the project's core-ktx version.
**Warning signs:** Lint warnings on build; crash on API 33+ if somehow using wrong overload.

### Pitfall 5: MediaCodec INFO_OUTPUT_FORMAT_CHANGED Must Be Handled
**What goes wrong:** Decode loop only handles positive output buffer indices. First few `dequeueOutputBuffer` calls return `INFO_OUTPUT_FORMAT_CHANGED` (value -2) or `INFO_OUTPUT_BUFFERS_CHANGED` (value -3). Loop ignores these and breaks.
**Why it happens:** MediaCodec signals format changes before delivering actual decoded data. This is by design.
**How to avoid:** Handle all negative return values from `dequeueOutputBuffer`: `INFO_OUTPUT_FORMAT_CHANGED` (update output format), `INFO_OUTPUT_BUFFERS_CHANGED` (deprecated but harmless), `INFO_TRY_AGAIN_LATER` (timeout, retry).
**Warning signs:** Decode completes instantly with zero bytes of output.

### Pitfall 6: OpenableColumns.SIZE May Return -1
**What goes wrong:** Some content providers don't report file size. `queryFileSize()` returns -1, and the size check logic incorrectly rejects or allows the file.
**Why it happens:** Not all content providers implement the SIZE column.
**How to avoid:** If size query returns -1 or null, allow the copy but track bytes copied. If copy exceeds 25MB, abort and delete partial file.
**Warning signs:** Files from certain apps always rejected or always bypass size check.

## Code Examples

### ContentResolver File Size Query
```kotlin
// Source: Android developer docs - OpenableColumns
private fun queryFileSize(uri: Uri): Long {
    contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                return cursor.getLong(sizeIndex)
            }
        }
    }
    return -1L  // Unknown size
}
```

### Synchronous URI to Cache File Copy
```kotlin
// Source: Android developer docs - ContentResolver.openInputStream
private fun copyUriToCache(uri: Uri): File? {
    val audioDir = File(cacheDir, "audio").apply { mkdirs() }
    val destFile = File(audioDir, "share_${System.currentTimeMillis()}.bin")

    return try {
        contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                val bytesCopied = input.copyTo(output)  // 8KB buffer default
                if (bytesCopied == 0L) {
                    destFile.delete()
                    return null
                }
            }
        } ?: return null
        destFile
    } catch (e: Exception) {
        destFile.delete()
        null
    }
}
```

### MediaExtractor + MediaCodec Decode Loop (Synchronous Pattern)
```kotlin
// Source: Android developer docs - MediaExtractor, MediaCodec
// Decodes audio file to raw PCM ByteBuffer (int16 samples at native rate)
fun decodeAudioFile(filePath: String): DecodedAudio? {
    val extractor = MediaExtractor()
    extractor.setDataSource(filePath)

    // Find audio track
    var audioTrackIndex = -1
    var audioFormat: MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("audio/")) {
            audioTrackIndex = i
            audioFormat = format
            break
        }
    }
    if (audioTrackIndex < 0 || audioFormat == null) return null

    extractor.selectTrack(audioTrackIndex)
    val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null

    // Create and configure decoder
    val codec = MediaCodec.createDecoderByType(mime)
    codec.configure(audioFormat, null, null, 0)
    codec.start()

    val bufferInfo = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false
    val pcmChunks = mutableListOf<ByteArray>()
    var outputSampleRate = 48000  // default for Opus
    var outputChannels = 1

    while (!outputDone) {
        // Feed input
        if (!inputDone) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)!!
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    val pts = extractor.sampleTime
                    codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0)
                    extractor.advance()
                }
            }
        }

        // Drain output
        val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
        when {
            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val outFormat = codec.outputFormat
                outputSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                outputChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            outputIndex >= 0 -> {
                if (bufferInfo.size > 0) {
                    val outBuffer = codec.getOutputBuffer(outputIndex)!!
                    val chunk = ByteArray(bufferInfo.size)
                    outBuffer.get(chunk)
                    pcmChunks.add(chunk)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }
    }

    codec.stop()
    codec.release()
    extractor.release()

    return DecodedAudio(pcmChunks, outputSampleRate, outputChannels)
}
```

### 48kHz to 16kHz Downsampling (3:1 Decimation with Averaging)
```kotlin
// 48kHz -> 16kHz is a clean 3:1 integer ratio
// Simple approach: average every 3 samples (acts as a basic low-pass filter)
// For voice messages this quality is sufficient; no need for a sinc filter
fun downsample48to16(samples: ShortArray): ShortArray {
    val outputSize = samples.size / 3
    val output = ShortArray(outputSize)
    for (i in 0 until outputSize) {
        val idx = i * 3
        // Average 3 consecutive samples (basic box filter = crude low-pass)
        val avg = (samples[idx].toInt() + samples[idx + 1].toInt() + samples[idx + 2].toInt()) / 3
        output[i] = avg.toShort()
    }
    return output
}
```

### Stereo to Mono Mixdown
```kotlin
// Interleaved stereo int16: [L0, R0, L1, R1, ...]
// Average left and right channels
fun stereoToMono(stereo: ShortArray): ShortArray {
    val mono = ShortArray(stereo.size / 2)
    for (i in mono.indices) {
        val left = stereo[i * 2].toInt()
        val right = stereo[i * 2 + 1].toInt()
        mono[i] = ((left + right) / 2).toShort()
    }
    return mono
}
```

### Int16 PCM to Float32 Normalization (Whisper Input Format)
```kotlin
// whisper.cpp expects float32 samples in [-1.0, 1.0] range
fun shortArrayToFloatArray(shorts: ShortArray): FloatArray {
    return FloatArray(shorts.size) { i ->
        shorts[i].toFloat() / 32768.0f
    }
}
```

### WAV Header Writer (Verification Only)
```kotlin
// Source: WAV PCM soundfile format spec (soundfile.sapp.org)
// 44-byte RIFF/WAV header for 16-bit PCM
fun writeWavFile(file: File, pcmData: ShortArray, sampleRate: Int, channels: Int = 1) {
    val bitsPerSample = 16
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = pcmData.size * 2  // 2 bytes per int16 sample

    file.outputStream().buffered().use { out ->
        // RIFF header
        out.write("RIFF".toByteArray())
        out.write(intToLittleEndian(36 + dataSize))
        out.write("WAVE".toByteArray())

        // fmt subchunk
        out.write("fmt ".toByteArray())
        out.write(intToLittleEndian(16))              // SubChunk1Size (PCM = 16)
        out.write(shortToLittleEndian(1))             // AudioFormat (PCM = 1)
        out.write(shortToLittleEndian(channels))
        out.write(intToLittleEndian(sampleRate))
        out.write(intToLittleEndian(byteRate))
        out.write(shortToLittleEndian(blockAlign))
        out.write(shortToLittleEndian(bitsPerSample))

        // data subchunk
        out.write("data".toByteArray())
        out.write(intToLittleEndian(dataSize))
        for (sample in pcmData) {
            out.write(shortToLittleEndian(sample.toInt()))
        }
    }
}

private fun intToLittleEndian(value: Int): ByteArray =
    byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

private fun shortToLittleEndian(value: Int): ByteArray =
    byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| getParcelableExtra<T>(key) | getParcelableExtra(key, T::class.java) | API 33 (Android 13) | Old method still works, suppress deprecation warning. New method not usable with minSdk 26. |
| MediaCodec synchronous polling | MediaCodec.Callback (async) | API 21 | Synchronous is simpler for file-based decode (not streaming). Use synchronous. |
| Reading content URI freely | Scoped storage restrictions | API 29 (Android 10) | Content URIs from ACTION_SEND still work fine; scoped storage affects direct file access, not ContentResolver |

**Deprecated/outdated:**
- `getParcelableExtra<T>(key)`: Deprecated in API 33 but still functional. Use with `@Suppress("DEPRECATION")` since minSdk is 26.
- `MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED`: Deprecated in API 21 when `getOutputBuffer(index)` was introduced. Handle in switch statement but no action needed.

## WhatsApp and Telegram Voice Message Formats

### WhatsApp
- **Container:** OGG
- **Codec:** Opus
- **MIME type when sharing:** `audio/ogg` or `audio/*` (varies by Android version and WhatsApp version)
- **Sample rate:** 48kHz (Opus standard) with original recording at 16kHz
- **Channels:** Mono
- **Typical size:** 30-60 KB per minute of voice
- **Content provider:** `content://com.whatsapp.provider.media/item/[UUID]`

### Telegram
- **Container:** OGG
- **Codec:** Opus
- **MIME type when sharing:** `application/octet-stream` (this is why we need the second intent-filter)
- **Sample rate:** 48kHz (Opus standard)
- **Channels:** Mono
- **Content provider:** `content://org.telegram.messenger.provider/...` (varies)

### MP3 (bonus format)
- Telegram can also share downloaded audio as MP3
- MediaExtractor + MediaCodec handle MP3 natively on all Android versions
- MP3 sample rate varies (44.1kHz, 48kHz, etc.) -- resample logic must handle non-3:1 ratios
- For simplicity, handle any rate by computing the ratio and using linear interpolation for non-integer ratios

## Open Questions

1. **Float PCM output from MediaCodec**
   - What we know: `KEY_PCM_ENCODING` with `ENCODING_PCM_FLOAT` can request float output from decoders on API 24+. This would skip the int16->float32 conversion step.
   - What's unclear: Whether all Opus decoders on all devices support float output. The safe path is int16 output (universally supported) with manual float conversion.
   - Recommendation: Use int16 output (default) and convert to float manually. It's a trivial operation and guarantees compatibility.

2. **Non-integer resampling ratios**
   - What we know: Opus always decodes to 48kHz. MP3 could be 44.1kHz. 44100->16000 is not a clean integer ratio.
   - What's unclear: How common MP3 voice message sharing is.
   - Recommendation: Implement general-purpose linear interpolation resampler that handles any input rate. For 48kHz, it simplifies to 3:1 decimation. For 44.1kHz, it uses linear interpolation. This handles all cases with one code path.

3. **Very short audio files (< 1 second)**
   - What we know: Whisper needs a minimum amount of audio context to produce useful output.
   - What's unclear: Exact minimum length for whisper.cpp small model.
   - Recommendation: Don't reject short files at the decode layer. Let Phase 3 handle minimum-length policy if needed.

## Sources

### Primary (HIGH confidence)
- [Android Developers: Receive simple data from other apps](https://developer.android.com/training/sharing/receive) - Intent filter configuration, EXTRA_STREAM handling
- [Android Developers: Supported media formats](https://developer.android.com/media/platform/supported-formats) - Opus decoder available since API 21, OGG container supported
- [Android Developers: MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor) - API for demuxing containers
- [Android Developers: MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) - Synchronous decode pattern
- [Android Developers: MediaFormat](https://developer.android.com/reference/android/media/MediaFormat) - MIMETYPE_AUDIO_OPUS = "audio/opus", KEY_SAMPLE_RATE, KEY_CHANNEL_COUNT
- [Android CDD 5.1 Media Codecs](https://android.googlesource.com/platform/compatibility/cdd/+/refs/tags/platform-tools-31.0.0/5_multimedia/5_1_media-codecs.md) - Opus decoder is MUST requirement
- [WAVE PCM soundfile format](http://soundfile.sapp.org/doc/WaveFormat/) - WAV header structure
- [OggOpus specification (Xiph wiki)](https://wiki.xiph.org/OggOpus) - OpusHead structure, sample rate behavior

### Secondary (MEDIUM confidence)
- [ExoPlayer float PCM issue #6749](https://github.com/google/ExoPlayer/issues/6749) - Float PCM output requires API 24+, not universally supported
- [Opus codec FAQ (Xiph)](https://wiki.xiph.org/OpusFAQ) - Opus native 48kHz operation
- [Android NDK sampling audio guide](https://developer.android.com/ndk/guides/audio/sampling-audio) - Resampling considerations

### Tertiary (LOW confidence)
- Community reports of Telegram sending `application/octet-stream` for voice messages - verified via n8n community forum and multiple Stack Overflow reports
- WhatsApp content provider URI format `content://com.whatsapp.provider.media/item/...` - observed in developer forums, not officially documented by WhatsApp

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - All platform APIs, well-documented, mandatory per CDD
- Architecture: HIGH - Standard Android patterns (intent-filter, ContentResolver, MediaCodec), verified against official docs
- Pitfalls: HIGH - URI permission lifecycle is well-documented; Opus 48kHz output is spec-defined; Telegram MIME type issue verified via multiple sources
- Resampling: MEDIUM - 3:1 decimation is mathematically trivial, but the averaging approach is a simplification vs proper sinc filter. For voice messages this is adequate.
- WhatsApp/Telegram format details: MEDIUM - Verified via API documentation and developer community, but these apps may change behavior in updates

**Research date:** 2026-02-27
**Valid until:** 2026-03-27 (platform APIs are stable; WhatsApp/Telegram sharing behavior may drift)
