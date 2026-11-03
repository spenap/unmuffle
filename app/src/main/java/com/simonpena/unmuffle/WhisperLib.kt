package com.simonpena.unmuffle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Low-level JNI bridge matching function signatures in whisper_jni.cpp.
 * Package name (com.simonpena.unmuffle) and inner class name (WhisperLib$Companion)
 * must stay in sync with the C++ JNI name mangling. Do NOT rename this class or its
 * companion — that would break the JNI binding without a compile error.
 *
 * CPU feature detection at init time loads the best available .so:
 *   arm64 with FP16 hardware → libwhisper_v8fp16_va.so
 *   everything else           → libwhisper.so (baseline)
 */
internal class WhisperLib {
    companion object {
        init {
            // Check for FP16 capability on arm64 to load the optimized variant
            // Reading /proc/cpuinfo is the standard Android approach (used in official example)
            val cpuInfo = try {
                java.io.File("/proc/cpuinfo").readText()
            } catch (e: Exception) {
                ""
            }
            val hasFp16 = cpuInfo.contains("fphp") || cpuInfo.contains("asimdhp")

            if (hasFp16) {
                try {
                    System.loadLibrary("whisper_v8fp16_va")
                } catch (e: UnsatisfiedLinkError) {
                    System.loadLibrary("whisper")  // fallback
                }
            } else {
                System.loadLibrary("whisper")
            }
        }

        // Initialize context from absolute file path — always prefer this over byte arrays (OOM risk)
        external fun initContext(modelPath: String): Long

        // Run full transcription — blocking, must be called from a non-main thread
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, languageCode: String)

        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun freeContext(contextPtr: Long)
        external fun getSystemInfo(): String
        external fun getProgress(): Int
        // UTF-8 text of the segments decoded since the last call, one per line
        external fun takeNewSegments(): ByteArray
    }
}

/**
 * Wraps a native whisper_context pointer. Single-threaded executor prevents concurrent
 * access — whisper.cpp is NOT thread-safe and crashes on concurrent calls.
 *
 * Use WhisperEngine (Plan 02) rather than this class directly.
 */
internal class WhisperContext private constructor(private var ptr: Long) {
    // Dedicated single-thread executor — whisper.cpp context must not be accessed concurrently
    private val scope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    /**
     * Transcribes [data] (16kHz mono float32 PCM) and returns concatenated segment text.
     * This function suspends on the single-thread executor — it does NOT run on the caller's thread.
     *
     * @param languageCode ISO 639-1 language code (e.g. "en", "es") or "auto" for auto-detection.
     *   Default "auto" for backward compatibility with existing callers.
     * @param onProgress Called with 0-100 progress values during inference. Invoked on
     *   Dispatchers.Default, NOT the main thread. Default no-op for backward compatibility.
     * @param onSegment Called with each segment's text as it is decoded, from the same
     *   thread as [onProgress], never concurrently with it.
     */
    suspend fun transcribeData(
        data: FloatArray,
        languageCode: String = "auto",
        onProgress: (Int) -> Unit = {},
        onSegment: (String) -> Unit = {}
    ): String = withContext(scope.coroutineContext) {
        fun takeSegments() = String(WhisperLib.takeNewSegments(), Charsets.UTF_8)
            .split('\n').filter { it.isNotEmpty() }.forEach(onSegment)
        // Poller runs on Default, NOT the single-thread executor (which is blocked by fullTranscribe)
        val pollerJob = launch(Dispatchers.Default) {
            while (isActive) {
                onProgress(WhisperLib.getProgress())
                takeSegments()
                delay(200)
            }
        }
        try {
            WhisperLib.fullTranscribe(ptr, WhisperCpuConfig.preferredThreadCount, data, languageCode)
        } finally {
            pollerJob.cancelAndJoin()
        }
        takeSegments()
        onProgress(100) // Ensure 100% is reported before collecting segments
        val segmentCount = WhisperLib.getTextSegmentCount(ptr)
        buildString {
            for (i in 0 until segmentCount) {
                append(WhisperLib.getTextSegment(ptr, i))
            }
        }.trim()
    }

    /** Release native memory. Call when the engine is no longer needed. */
    suspend fun release() = withContext(scope.coroutineContext) {
        WhisperLib.freeContext(ptr)
        ptr = 0L
    }

    companion object {
        /**
         * Load model from an absolute file path. Returns null if the file does not exist
         * or whisper_init_from_file fails (corrupt model, out of memory, etc.).
         * Never pass a byte array — 190MB byte array causes OOM on all device tiers.
         */
        fun createContextFromFile(filePath: String): WhisperContext? {
            val ptr = WhisperLib.initContext(filePath)
            return if (ptr != 0L) WhisperContext(ptr) else null
        }

        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }
}
