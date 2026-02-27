package com.simonpena.transchat

/**
 * CPU thread count configuration for Whisper inference.
 *
 * Strategy: leave one core free for the UI/render thread, cap at 4.
 * Inference is memory-bandwidth-bound above 4 threads on most ARM SoCs;
 * more threads increase thermal pressure without improving throughput.
 */
internal object WhisperCpuConfig {
    val preferredThreadCount: Int
        get() = maxOf(1, minOf(Runtime.getRuntime().availableProcessors() - 1, 4))
}
