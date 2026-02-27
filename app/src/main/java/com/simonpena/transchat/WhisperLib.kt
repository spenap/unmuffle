package com.simonpena.transchat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Low-level JNI bridge matching function signatures in whisper_jni.cpp.
 * Package name (com.simonpena.transchat) and inner class name (WhisperLib$Companion)
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
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)

        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun freeContext(contextPtr: Long)
        external fun getSystemInfo(): String
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
     */
    suspend fun transcribeData(data: FloatArray): String = withContext(scope.coroutineContext) {
        WhisperLib.fullTranscribe(ptr, WhisperCpuConfig.preferredThreadCount, data)
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
